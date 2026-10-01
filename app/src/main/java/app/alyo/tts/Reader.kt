package app.alyo.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Reads text aloud from inside the app. It goes through Android's own speech client, pointed at
 * Alyo's engine whatever the phone's default is: that way playback, audio focus and interruption
 * by a call are the system's job, and the path is the same one other apps will use.
 */
class Reader(context: Context) {
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking

    private var ready = false
    private var pending: Pair<String, String?>? = null
    private var last = ""

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext, { status ->
        ready = status == TextToSpeech.SUCCESS
        pending?.let { (text, voice) -> speak(text, voice) }
        pending = null
    }, context.packageName)

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _speaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == last) _speaking.value = false
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                _speaking.value = false
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                _speaking.value = false
            }
        })
    }

    /** @param voice the id of the voice to use, or null for the one chosen in Settings */
    fun speak(text: String, voice: String? = null) {
        if (!ready) {
            pending = text to voice
            return
        }
        tts.stop()
        tts.voices?.firstOrNull { it.name == voice }?.let { tts.voice = it }
        val pieces = pieces(text)
        if (pieces.isEmpty()) return
        _speaking.value = true
        pieces.forEachIndexed { index, piece ->
            last = "alyo-$index"
            tts.speak(piece, if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, Bundle(), last)
        }
    }

    fun stop() {
        pending = null
        tts.stop()
        _speaking.value = false
    }

    fun close() {
        tts.stop()
        tts.shutdown()
    }

    companion object {
        /**
         * Android accepts only so much text per request. Long text is cut into pieces under that
         * limit, at the end of a sentence where there is one.
         */
        fun pieces(text: String, limit: Int = 3000): List<String> {
            val out = ArrayList<String>()
            var rest = text.trim()
            while (rest.length > limit) {
                val window = rest.substring(0, limit)
                val cut = listOf(". ", "! ", "? ", "\n", "; ", ", ", " ").firstNotNullOfOrNull { mark -> window.lastIndexOf(mark).takeIf { it > limit / 2 }?.plus(mark.length) } ?: limit
                out += rest.substring(0, cut).trim()
                rest = rest.substring(cut).trim()
            }
            if (rest.isNotEmpty()) out += rest
            return out
        }
    }
}
