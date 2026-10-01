package app.alyo.ui

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%d MB".format(bytes / 1_000_000L)
    else -> "%d KB".format(bytes / 1_000L)
}
