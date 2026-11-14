package org.bin.apk.herder

import android.app.Application
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Application class. Installs permanent crash capture from the first build:
 * an uncaught exception is written to filesDir/crash-log.txt (newest first)
 * before the default handler runs, so a launch failure on a device leaves a
 * log the next diagnosis can read.
 */
class HerderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val entry = "=== ${java.util.Date()} thread=${thread.name} ===\n$sw\n"
                val file = File(filesDir, "crash-log.txt")
                val old = if (file.exists()) file.readText() else ""
                file.writeText((entry + old).take(64 * 1024))
                Log.e("Herder", "Crash captured to ${file.absolutePath}", error)
            } catch (_: Throwable) {
                // Never let the crash reporter crash the crash path.
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
