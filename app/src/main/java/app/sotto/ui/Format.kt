package app.sotto.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 75_000 -> "1:15", 3_725_000 -> "1:02:05". */
fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Zero-padded position label as the player shows it: 5_000 -> "00:05", 3_725_000 -> "1:02:05". */
fun formatStamp(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** "Wed, Sep 30 • 8:41 PM" */
fun formatWhen(millis: Long): String = SimpleDateFormat("EEE, MMM d • h:mm a", Locale.getDefault()).format(Date(millis))

/** Like [formatClock] with tenths of a second, for trimming where 100 ms matters. */
fun formatClockFine(ms: Long): String = formatClock(ms) + "." + (ms.coerceAtLeast(0) % 1000 / 100)

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000 -> "%d MB".format(Math.round(bytes / 1e6))
    else -> "%d KB".format(Math.round(bytes / 1e3))
}

/** "Today", "Yesterday", or a date: the heading recordings are grouped under. */
fun dayLabel(millis: Long, now: Long = System.currentTimeMillis()): String {
    val day = Calendar.getInstance().apply { timeInMillis = millis }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    fun sameDay(a: Calendar, b: Calendar) = a[Calendar.YEAR] == b[Calendar.YEAR] && a[Calendar.DAY_OF_YEAR] == b[Calendar.DAY_OF_YEAR]
    if (sameDay(day, today)) return "Today"
    today.add(Calendar.DAY_OF_YEAR, -1)
    if (sameDay(day, today)) return "Yesterday"
    val pattern = if (day[Calendar.YEAR] == Calendar.getInstance()[Calendar.YEAR]) "EEEE, MMMM d" else "MMMM d, yyyy"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
}

fun timeOfDay(millis: Long): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(millis))
