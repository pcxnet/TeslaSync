package au.net.kal.teslasync.util

import android.content.Context
import java.io.File

/**
 * Captures uncaught exceptions to a file so a sideloaded build (no Play crash reporting)
 * can show the user the actual stack trace on the next launch — turning "it crashed" into
 * real evidence. Chains to the previously-installed handler so the OS still does its thing.
 */
object CrashReporter {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                File(appContext.filesDir, FILE).writeText(
                    "Thread: ${thread.name}\n\n" + throwable.stackTraceToString()
                )
            } catch (_: Throwable) {
                // best effort — never mask the original crash
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Returns the last crash trace (if any) and deletes it, so it shows only once. */
    fun consume(context: Context): String? {
        val f = File(context.applicationContext.filesDir, FILE)
        if (!f.exists()) return null
        return try {
            val text = f.readText()
            f.delete()
            text
        } catch (_: Throwable) {
            null
        }
    }
}
