package org.bin.apk.herder.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/** The one SharedPreferences file the UI and the engine share
 *  (MainActivity's PREFS constant is the same name). */
const val PREFS_FILE = "herder"
const val PREF_KEY_LOG_TO_FILE = "log_to_file"
const val PREF_KEY_SETTINGS_TIER = "settings_tier"
const val PREF_KEY_BACKEND_TAG = "backend_tag"
const val PREF_KEY_EXTRA_ARGS = "extra_server_args"

/**
 * A value with main-thread listeners — the hand-rolled replacement for
 * the StateFlow the Compose UI collected. State holders assign [value]
 * from any thread; listeners are invoked on the main thread.
 *
 * Data-class values are conflated on exact repeats: the backend
 * downloader reports byte progress, and an unthrottled observer rebuild
 * per callback ground the heap to an OutOfMemoryError during the v0.2.3
 * gate (the progress bar resolves to 0.1% steps — an offer of a new
 * Working with a bar position already on screen is noise, not news).
 * One step: at most one pending notification per listener runs at a
 * time, and it delivers the latest value when it runs.
 */
class Observable<T>(initial: T) {
    @Volatile
    var value: T = initial
        set(newValue) {
            if (newValue == field) return
            field = newValue
            val main = Looper.getMainLooper()
            if (Looper.myLooper() === main) {
                listeners.forEach { it(newValue) }
            } else {
                mainHandler.post { listeners.forEach { it(value) } }
            }
        }

    private val listeners = CopyOnWriteArrayList<(T) -> Unit>()

    fun addListener(listener: (T) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (T) -> Unit) {
        listeners.remove(listener)
    }

    companion object {
        private val mainHandler = Handler(Looper.getMainLooper())
    }
}

/** Backend installation state, shared between the UI and the engine. */
sealed interface BackendStatus {
    data object NotInstalled : BackendStatus
    data class Working(val label: String, val downloaded: Long, val total: Long) : BackendStatus
    data class Installed(val variant: Backend.Variant) : BackendStatus
    data class Failed(val message: String) : BackendStatus

    /** A custom build (Expert) has downloaded and been hashed, and is
     *  waiting for the user to confirm that hash before extraction —
     *  trust on first use: there is no compiled-in pin for its tag. */
    data class AwaitingConfirm(
        val tag: String,
        val sha256: String,
        val tarballPath: String,
        val sizeBytes: Long,
    ) : BackendStatus
}

/** llama-server process state. */
sealed interface ServerStatus {
    data object Stopped : ServerStatus
    data class Starting(val modelName: String) : ServerStatus
    data class Running(val modelName: String, val port: Int) : ServerStatus
    data class Failed(val message: String) : ServerStatus
}

/** Process-wide engine state (single server, single backend — simple on purpose). */
object EngineState {
    val backendStatus = Observable<BackendStatus>(BackendStatus.NotInstalled)
    val serverStatus = Observable<ServerStatus>(ServerStatus.Stopped)

    @Volatile
    var process: Process? = null

    /**
     * Bumped on every start AND stop intent (v0.3.0, review item
     * F13). A start captures the generation it was launched with and
     * may only publish Running if the generation is still current:
     * two starts poll the same port, so a superseded start's health
     * probe can be answered by the NEWER server, and the status
     * check alone ("still Starting") cannot tell the two starts
     * apart — the older one would write Running with the wrong
     * model name over the newer start.
     */
    val startGeneration = AtomicInteger(0)

    fun refreshBackend(context: Context) {
        val installed = Backend.installedVariant(context)
        if (backendStatus.value !is BackendStatus.Working) {
            backendStatus.value = if (installed != null) {
                BackendStatus.Installed(installed)
            } else {
                BackendStatus.NotInstalled
            }
        }
    }
}

/**
 * The server's output, captured in-process (v0.3.0 log redesign):
 * ServerService pipes llama-server's output and a drainer thread
 * appends every line here; only the last [MAX_LINES] are kept. The
 * Engine tab tails THIS, not a file — writing every timing line to
 * flash by default was pure wear on the device's storage (Brian's
 * call: the user on the low-end device who needs it off most is the
 * least likely to go looking for the switch, so the file is opt-in
 * and off by default; support asks the user to turn it on when a
 * problem is actually being diagnosed).
 */
object ServerLog {
    const val MAX_LINES = 300

    private val lines = ArrayDeque<String>()

    @Synchronized
    fun append(line: String) {
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    @Synchronized
    fun tail(count: Int): List<String> {
        val all = lines.toList()
        return if (all.size <= count) all else all.drop(all.size - count)
    }
}

/**
 * The OOM guard: a model is expected to need roughly 0.43 GB +
 * 0.92 x its file size in GB, and the usable budget is
 * min(65% of total RAM, total RAM - 1.2 GB). A guard rail, not a guarantee.
 */
object MemoryGuard {
    data class Estimate(val requiredGb: Double, val budgetGb: Double) {
        val fits: Boolean get() = requiredGb <= budgetGb
    }

    fun totalRamGb(context: Context): Double {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem / 1_000_000_000.0
    }

    fun estimate(context: Context, modelFile: File): Estimate {
        val total = totalRamGb(context)
        val fileGb = modelFile.length() / 1_000_000_000.0
        val required = 0.43 + 0.92 * fileGb
        val budget = min(total * 0.65, total - 1.2)
        return Estimate(required, budget)
    }
}
