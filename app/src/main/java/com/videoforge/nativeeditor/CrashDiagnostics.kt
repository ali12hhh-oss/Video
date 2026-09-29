package com.videoforge.nativeeditor

import android.content.Context
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter

/** Temporary in-app crash diagnostics for devices without ADB access. */
object CrashDiagnostics {
    private const val PREFS = "videoforge_crash_diagnostics"
    private const val KEY_STAGE = "stage"
    private const val KEY_STAGE_TIME = "stage_time"
    private const val KEY_CRASH = "crash"
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
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
                    .apply()
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun mark(context: Context, stage: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STAGE, stage.take(180))
            .putLong(KEY_STAGE_TIME, System.currentTimeMillis())
            .apply()
    }

    fun last(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val crash = prefs.getString(KEY_CRASH, null)
        if (!crash.isNullOrBlank()) return crash
        val stage = prefs.getString(KEY_STAGE, null) ?: return null
        val time = prefs.getLong(KEY_STAGE_TIME, 0L)
        return "Last stage: " + stage + "\nTime: " + time +
            "\nNo uncaught JVM exception was captured.\nThis may indicate a native/process-level termination."
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_CRASH)
            .remove(KEY_STAGE)
            .remove(KEY_STAGE_TIME)
            .apply()
    }

    private fun lastStage(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STAGE, "unknown") ?: "unknown"
}
