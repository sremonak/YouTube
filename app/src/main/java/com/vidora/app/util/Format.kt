package com.vidora.app.util

import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

object Format {
    fun count(value: Long): String {
        if (value < 0) return ""
        if (value < 1000) return value.toString()
        val exp = (ln(value.toDouble()) / ln(1000.0)).toInt().coerceAtMost(4)
        val unit = "KMBT"[exp - 1]
        val v = value / 1000.0.pow(exp)
        return if (v < 10) String.format(Locale.US, "%.1f%c", v, unit).replace(".0", "") else String.format(Locale.US, "%d%c", v.toInt(), unit)
    }

    fun views(value: Long) = if (value < 0) "" else "${count(value)} views"

    fun subscribers(value: Long) = if (value < 0) "" else "${count(value)} subscribers"

    fun duration(seconds: Long): String {
        if (seconds <= 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun bytes(b: Long): String {
        if (b <= 0) return "—"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val exp = (ln(b.toDouble()) / ln(1024.0)).toInt().coerceIn(0, units.size - 1)
        return String.format(Locale.US, if (exp >= 2) "%.1f %s" else "%.0f %s", b / 1024.0.pow(exp), units[exp])
    }

    fun speed(bps: Long) = if (bps <= 0) "" else bytes(bps) + "/s"

    fun relative(epochMs: Long): String {
        if (epochMs <= 0) return ""
        val diff = (System.currentTimeMillis() - epochMs) / 1000
        return when {
            diff < 60 -> "just now"
            diff < 3600 -> "${diff / 60} min ago"
            diff < 86_400 -> "${diff / 3600} h ago"
            diff < 2_592_000 -> "${diff / 86_400} days ago"
            diff < 31_536_000 -> "${diff / 2_592_000} months ago"
            else -> "${diff / 31_536_000} years ago"
        }
    }
}
