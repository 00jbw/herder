package org.bin.apk.herder.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.bin.apk.herder.MainActivity
import org.bin.apk.herder.R
import org.bin.apk.herder.data.Models
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Runs llama-server as a child process under a foreground service, so a
 * generation in progress survives the UI being backgrounded or closed.
 * The server speaks the OpenAI API on [::1] — Herder's own chat tab is
 * just its first client; any local client can use the same endpoint.
 */
class ServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Log capture (v0.3.0 redesign): the server's output is piped and
    // drained in-process into ServerLog's ring buffer. The log FILE is
    // opt-in and off by default (PREF_KEY_LOG_TO_FILE) — see ServerLog.
    @Volatile
    private var logToFile = false
    private var logFile: File? = null

    /**
     * Every line the server produces (and Herder's own notes about
     * it) goes through here: always into the ring buffer the Engine
     * tab tails, and into server.log only when file logging is on.
     * Called from the drainer thread and the start path; the file
     * append is synchronized so the two never interleave a line.
     */
    private fun emitLogLine(line: String) {
        ServerLog.append(line)
        if (logToFile) {
            val file = logFile ?: return
            synchronized(this) { runCatching { file.appendText(line + "\n") } }
        }
    }

    /** Rotation for the opt-in log file, at server start: past 5 MB
     *  the current file becomes server.log.1 (any older .1 is dropped
     *  first) — one previous generation is all a diagnosis ever needs. */
    private fun rotateLogIfLarge(log: File) {
        if (log.exists() && log.length() > 5_000_000L) {
            val previous = File(filesDir, "server.log.1")
            previous.delete()
            log.renameTo(previous)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val modelPath = intent.getStringExtra(EXTRA_MODEL) ?: return START_NOT_STICKY
                val variantId = intent.getStringExtra(EXTRA_VARIANT) ?: Backend.STANDARD.id
                val port = intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT)
                val contextSize = intent.getIntExtra(EXTRA_CTX, DEFAULT_CTX)
                val threads = intent.getIntExtra(EXTRA_THREADS, DEFAULT_THREADS)
                val generation = EngineState.startGeneration.incrementAndGet()
                startForeground(NOTIFICATION_ID, buildNotification("Starting llama-server…"))
                startServer(File(modelPath), variantId, port, contextSize, threads, generation)
            }
            ACTION_STOP -> stopServer()
        }
        return START_STICKY
    }

    private fun startServer(model: File, variantId: String, port: Int, contextSize: Int, threads: Int, generation: Int) {
        scope.launch {
            try {
                stopProcess()
                val variant = Backend.VARIANTS.firstOrNull { it.id == variantId } ?: Backend.STANDARD
                val binary = Backend.serverBinary(this@ServerService, variant)
                if (!binary.exists()) {
                    EngineState.serverStatus.value = ServerStatus.Failed("Backend not installed")
                    stopSelf()
                    return@launch
                }
                if (!model.exists()) {
                    EngineState.serverStatus.value = ServerStatus.Failed("Model file missing: ${model.name}")
                    stopSelf()
                    return@launch
                }
                // User-facing surfaces (the status the Engine tab
                // shows, the notification) use the parsed display
                // name — "Qwen3.5 2B-UD-Q4_K_XL", not the raw file
                // name with its .gguf tail. The raw name stays where
                // exactness matters: the launch path and the log
                // header below.
                val displayName = Models.displayName(model.name)
                EngineState.serverStatus.value = ServerStatus.Starting(displayName)

                // Log setup comes before the process: read the opt-in
                // once per start, rotate the file if it has one coming,
                // and mark the session boundary in the buffer (and the
                // file, when on) — the tail shows where this run began.
                val prefs = getSharedPreferences(PREFS_FILE, MODE_PRIVATE)
                logToFile = prefs.getBoolean(PREF_KEY_LOG_TO_FILE, false)
                val log = File(filesDir, "server.log").also { logFile = it }
                if (logToFile) rotateLogIfLarge(log)
                emitLogLine("")
                emitLogLine("=== Herder start ${java.util.Date()} model=${model.name} ctx=$contextSize threads=$threads ===")

                // Guru extra arguments (Settings) ride AFTER the
                // managed ones: Herder always owns the binary, model,
                // host, port, context, threads and --jinja — the
                // extras can only add flags. A repeated flag is
                // llama-server's own affair, exactly as on a hand-typed
                // command line.
                val extraArgs = prefs.getString(PREF_KEY_EXTRA_ARGS, "")
                    ?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: emptyList()
                val command = listOf(
                    binary.absolutePath,
                    "-m", model.absolutePath,
                    "--host", "::1",
                    "--port", port.toString(),
                    "-c", contextSize.toString(),
                    "-t", threads.toString(),
                    "--jinja",
                ) + extraArgs

                val backendDir = binary.parentFile!!
                val builder = ProcessBuilder(command)
                builder.directory(backendDir)
                builder.environment()["LD_LIBRARY_PATH"] = Backend.libraryPath(this@ServerService, variant)
                builder.redirectErrorStream(true)
                builder.redirectOutput(ProcessBuilder.Redirect.PIPE)
                val process = builder.start()
                EngineState.process = process

                // The drainer: one daemon thread whose only job is
                // reading the pipe into the log sink, for the life of
                // the process. It must never block on anything but the
                // read — an undrained pipe fills (~64 KB) and stalls
                // the server itself. When the process dies the stream
                // closes and the thread ends with it.
                Thread {
                    try {
                        process.inputStream.bufferedReader().forEachLine { line -> emitLogLine(line) }
                    } catch (e: Exception) {
                        // The stream dies with the process; nothing to report.
                    }
                }.apply {
                    isDaemon = true
                    name = "herder-log-drain"
                    start()
                }

                // Watch for exit so the UI never shows a dead server as running.
                launch {
                    val exit = runCatching { process.waitFor() }.getOrDefault(-1)
                    if (EngineState.process === process) {
                        EngineState.process = null
                        if (EngineState.serverStatus.value is ServerStatus.Running ||
                            EngineState.serverStatus.value is ServerStatus.Starting
                        ) {
                            EngineState.serverStatus.value =
                                ServerStatus.Failed("llama-server exited (code $exit) — see the log")
                        }
                        stopSelf()
                    }
                }

                // Poll /health until the server is actually ready.
                val deadline = System.currentTimeMillis() + 180_000
                var healthy = false
                while (System.currentTimeMillis() < deadline) {
                    if (!process.isAlive) break
                    if (isHealthy(port)) {
                        healthy = true
                        break
                    }
                    delay(750)
                }
                // A concurrent stop destroys the process (and stopServer
                // has already set the status): this start is abandoned,
                // not failed — say nothing and leave the status alone.
                // (Writing Failed from a losing start is what put the
                // phantom error on screen in the v0.1.8 → v0.1.9 update.)
                // The generation must also still be current (F13): a
                // stop FOLLOWED BY a fresh start leaves the status at
                // Starting again — the newer start's — while this
                // start's probes are being answered by the newer server
                // on the same port. Without the generation check this
                // start would write Running (with ITS model name) over
                // the newer start.
                if (EngineState.serverStatus.value !is ServerStatus.Starting ||
                    EngineState.startGeneration.get() != generation
                ) {
                    return@launch
                }
                if (healthy && process.isAlive) {
                    EngineState.serverStatus.value = ServerStatus.Running(displayName, port)
                    updateNotification("Serving $displayName on [::1]:$port")
                } else if (process.isAlive) {
                    EngineState.serverStatus.value =
                        ServerStatus.Failed(
                            "Server did not become healthy in 180s " +
                                "(last probe error: ${lastProbeError ?: "none"}) — see the log"
                        )
                }
            } catch (e: CancellationException) {
                // The service's own scope was cancelled (onDestroy): the
                // start is abandoned, not failed. Before v0.2.0 this fell
                // into the generic catch below and the UI reported
                // "Failed: Job was cancelled" for a routine teardown —
                // the first post-app-update launch hit it.
                throw e
            } catch (e: Exception) {
                EngineState.serverStatus.value = ServerStatus.Failed(e.message ?: "Failed to start server")
                stopSelf()
            }
        }
    }

    @Volatile
    private var lastProbeError: String? = null

    private fun isHealthy(port: Int): Boolean = try {
        val connection = URL("http://[::1]:$port/health").openConnection() as HttpURLConnection
        connection.connectTimeout = 1500
        connection.readTimeout = 1500
        connection.connect()
        val ok = connection.responseCode == 200
        lastProbeError = if (ok) null else "HTTP ${connection.responseCode}"
        ok
    } catch (e: Exception) {
        // Never swallow this again: a silent probe failure is what hid the
        // cleartext-policy block behind a healthy-looking server for a day.
        val message = "${e.javaClass.simpleName}: ${e.message}"
        if (message != lastProbeError) {
            lastProbeError = message
            emitLogLine("health probe failed: $message")
        }
        false
    }

    private fun stopProcess() {
        EngineState.process?.let { process ->
            runCatching { process.destroy() }
            // Bounded wait (v0.3.0 review pass): this runs on the main
            // thread from stopServer/onDestroy, and the old unbounded
            // waitFor() let a slow-to-die or wedged server freeze the
            // service with it. Five seconds of grace, then force.
            val exited = runCatching { process.waitFor(5, TimeUnit.SECONDS) }.getOrDefault(false)
            if (!exited) runCatching { process.destroyForcibly() }
        }
        EngineState.process = null
    }

    private fun stopServer() {
        // Invalidate any in-flight start's generation (see startServer).
        EngineState.startGeneration.incrementAndGet()
        stopProcess()
        EngineState.serverStatus.value = ServerStatus.Stopped
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopProcess()
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Herder engine", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        // Tapping the notification places the user in the app
        // (v0.3.2): the intent carries CLEAR_TOP + SINGLE_TOP so it
        // raises the EXISTING MainActivity — chat, picks and all —
        // instead of stacking a second instance over it
        // (MainActivity uses the default launch mode).
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Herder")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        const val ACTION_START = "org.bin.apk.herder.engine.START"
        const val ACTION_STOP = "org.bin.apk.herder.engine.STOP"
        const val EXTRA_MODEL = "model"
        const val EXTRA_VARIANT = "variant"
        const val EXTRA_PORT = "port"
        const val EXTRA_CTX = "ctx"
        const val EXTRA_THREADS = "threads"
        const val DEFAULT_PORT = 9931
        const val DEFAULT_CTX = 4096
        const val DEFAULT_THREADS = 4
        private const val CHANNEL_ID = "herder_engine"
        private const val NOTIFICATION_ID = 42

        fun startIntent(
            context: Context,
            model: File,
            variant: Backend.Variant,
            port: Int,
            contextSize: Int,
            threads: Int,
        ): Intent = Intent(context, ServerService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_MODEL, model.absolutePath)
            putExtra(EXTRA_VARIANT, variant.id)
            putExtra(EXTRA_PORT, port)
            putExtra(EXTRA_CTX, contextSize)
            putExtra(EXTRA_THREADS, threads)
        }

        fun stopIntent(context: Context): Intent =
            Intent(context, ServerService::class.java).apply { action = ACTION_STOP }
    }
}
