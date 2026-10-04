package app.alyo.tts

import android.content.res.AssetManager
import java.io.File

/**
 * Words a voice gets wrong, and a spelling that makes it say them right. The table is a text
 * file in the app's assets, one per language, that anyone can add to; see `pronunciation/en.tsv`.
 * A spelling is used and not phonetic symbols because the engine takes nothing but text. The
 * spellings are written for the Piper voices, which all read text the same way.
 */
class Pronunciations(entries: Map<String, String>) {
    private val spellings = entries.mapKeys { it.key.lowercase() }
    // Longest first, so "Sault Ste. Marie" wins over a shorter entry inside it. A word only
    // counts when it stands alone, not inside a longer one.
    private val pattern = spellings.keys.takeIf { it.isNotEmpty() }
        ?.sortedByDescending { it.length }
        ?.joinToString("|", prefix = "(?<![\\p{L}\\p{N}])(?:", postfix = ")(?![\\p{L}\\p{N}])") { Regex.escape(it) }
        ?.toRegex(RegexOption.IGNORE_CASE)

    /** [text] with every word in the table spelled the way it is said. */
    fun apply(text: String): String = pattern?.replace(text) { spellings[it.value.lowercase()] ?: it.value } ?: text

    companion object {
        val NONE = Pronunciations(emptyMap())

        /**
         * Reads a table: `word<tab>spelling` lines, with blank lines and `#` notes skipped. A
         * line with a third column is skipped too: its exact sounds are in the dictionary the
         * voices read with (see [install]), and respelling the word would hide it from there.
         */
        fun parse(table: String): Pronunciations = Pronunciations(
            table.lineSequence()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .mapNotNull { line -> line.split('\t').takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
                .filter { (word, spelling) -> word.isNotEmpty() && spelling.isNotEmpty() }
                .toMap(),
        )

        /**
         * Puts Alyo's English dictionary, eSpeak's own with the table's exact sounds added, in
         * place of the one a Piper voice came with. [voiceDir] is the voice's folder. Done once
         * per voice and again whenever the dictionary in the app changes.
         */
        fun install(assets: AssetManager, voiceDir: File) {
            val data = File(voiceDir, "espeak-ng-data").takeIf { it.isDirectory } ?: return
            try {
                val version = assets.open("pronunciation/en_dict.version").bufferedReader().use { it.readText().trim() }
                val marker = File(data, "en_dict.alyo")
                if (marker.exists() && marker.readText() == version) return
                val fresh = File(data, "en_dict.new")
                assets.open("pronunciation/en_dict").use { input -> fresh.outputStream().use { input.copyTo(it) } }
                if (fresh.renameTo(File(data, "en_dict"))) marker.writeText(version)
            } catch (e: java.io.IOException) {
                // The voice still speaks, with the dictionary it came with.
            }
        }

        /** The table for [language], a two-letter code, or [NONE] where there is none yet. */
        fun load(assets: AssetManager, language: String): Pronunciations = try {
            parse(assets.open("pronunciation/$language.tsv").bufferedReader().use { it.readText() })
        } catch (e: java.io.IOException) {
            NONE
        }
    }
}
