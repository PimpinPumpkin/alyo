package app.sotto.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Titles {
    /** Default name for a new recording, e.g. "Sep 30 at 7:46 PM". */
    fun forDate(millis: Long): String = SimpleDateFormat("MMM d 'at' h:mm a", Locale.getDefault()).format(Date(millis))

    /**
     * A word or two saying what a recording is about, for naming it: the content words that come
     * up most, with names (words capitalized mid-sentence) counted double. Null when nothing
     * stands out, which is the right answer for a short or rambling recording.
     */
    fun topic(transcript: Transcript): String? {
        val scores = HashMap<String, Float>()
        val shown = HashMap<String, String>()
        for (segment in transcript.segments) {
            segment.words.forEachIndexed { index, word ->
                val bare = word.text.trim { !it.isLetterOrDigit() }
                val key = bare.lowercase().removeSuffix("'s")
                if (key.length < 4 || key in STOPWORDS || key.any { it.isDigit() }) return@forEachIndexed
                val previous = segment.words.getOrNull(index - 1)?.text
                val name = bare.first().isUpperCase() && previous != null && previous.last() !in ".?!"
                scores[key] = (scores[key] ?: 0f) + if (name) 2f else 1f
                if (name || key !in shown) shown[key] = if (name) bare.removeSuffix("'s") else key.replaceFirstChar { it.uppercase() }
            }
        }
        val top = scores.entries.filter { it.value >= 2f }.sortedWith(compareByDescending<Map.Entry<String, Float>> { it.value }.thenByDescending { it.key.length })
        return top.take(2).mapNotNull { shown[it.key] }.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    private val STOPWORDS = (
        "about above after again against also always another anyone anything around because been before being below between both cannot could " +
            "does doing done down during each either else even ever every everyone everything from further gets getting give given goes going gone " +
            "gonna gotta have having here hers herself himself however into itself just kind know known like little look looking lots made make " +
            "many maybe mean might more most much must myself need never next none nothing okay once only other ought ours ourselves over " +
            "really right said same says seem seems shall should since some someone something sort still such sure take taken tell than thank thanks " +
            "that their theirs them themselves then there these they thing things think this those though through today together took toward " +
            "under until upon used very want wants well went were what whatever when where whether which while whom whose will with within without " +
            "would yeah year years yours yourself actually basically definitely probably literally first second third last people time times " +
            "start started back come comes came called lets great good thought"
        ).split(' ').toHashSet()
}
