package com.videoforge.nativeeditor

import android.content.Context
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Temporary in-app crash diagnostics for devices without ADB access.
 *
 * The current stage is persisted synchronously so a native/process-level
 * termination can be reconstructed on the next launch. A previous-run
 * snapshot is kept separate from the current session.
 */
object CrashDiagnostics {
    private const val PREFS = "videoforge_crash_diagnostics"
    private const val KEY_STAGE = "stage"
    private const val KEY_STAGE_TIME = "stage_time"
    private const val KEY_CRASH = "crash"
    private const val KEY_PREVIOUS = "previous"
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // Anything left in KEY_STAGE belongs to the previous app session.
        // Preserve it before MAIN_ON_CREATE overwrites the current stage.
        if (prefs.contains(KEY_STAGE) && prefs.getString(KEY_CRASH, null).isNullOrBlank()) {
            val stage = prefs.getString(KEY_STAGE, "unknown") ?: "unknown"
            val time = prefs.getLong(KEY_STAGE_TIME, 0L)
            prefs.edit()
                .putString(
                    KEY_PREVIOUS,
                    "Android " + Build.VERSION.SDK_INT + " / " + Build.MANUFACTURER + " " + Build.MODEL + "\n" +
                        "Last stage: " + stage + "\n" +
                        "Time: " + time + "\n\n" +
                        "No uncaught JVM exception was captured.\n" +
                        "This indicates the process may have terminated natively."
                )
                .commit()
        }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val stack = StringWriter()
                throwable.printStackTrace(PrintWriter(stack))
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(
                        KEY_CRASH,
                        "Android " + Build.VERSION.SDK_INT + " / " + Build.MANUFACTURER + " " + Build.MODEL + "\n" +
                            "Last stage: " + lastStage(app) + "\n\n" + stack.toString()
                    )
                    .commit()
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun mark(context: Context, stage: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STAGE, stage.take(180))
            .putLong(KEY_STAGE_TIME, System.currentTimeMillis())
            .commit()
    }

    fun last(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val crash = prefs.getString(KEY_CRASH, null)
        if (!crash.isNullOrBlank()) return crash
        return prefs.getString(KEY_PREVIOUS, null)
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_CRASH)
            .remove(KEY_PREVIOUS)
            .commit()
    }

    private fun lastStage(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STAGE, "unknown") ?: "unknown"
}
