package com.vidora.app.util

import android.content.Context
import android.os.Build
import com.vidora.app.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local-only crash reporting: the stack trace is written to private storage and shown to the user
 * on the next launch, where they can copy or share it. Nothing is uploaded anywhere.
 */
object CrashReporter {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { File(app.filesDir, FILE).writeText(report(error, "Thread: ${thread.name}")) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun pending(context: Context): String? =
        File(context.filesDir, FILE).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }

    /** Human-readable report used both for crashes and for "error details" dialogs. */
    fun report(error: Throwable?, extra: String? = null): String {
        val sw = StringWriter()
        error?.printStackTrace(PrintWriter(sw))
        return buildString {
            appendLine("Orvixa ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            if (extra != null) appendLine(extra)
            appendLine()
            append(sw.toString().take(30_000))
        }
    }
}
