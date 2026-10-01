package app.sotto.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Titles {
    /** Default name for a new recording, e.g. "Sep 30 at 7:46 PM". */
    fun forDate(millis: Long): String = SimpleDateFormat("MMM d 'at' h:mm a", Locale.getDefault()).format(Date(millis))
}
