package app.alyo.recognition

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import app.alyo.AlyoApp
import app.alyo.dictation.Dictation
import app.alyo.text.SpokenPunctuation

/**
 * Alyo as the phone's speech recognizer: what other apps reach when they ask Android to turn
 * speech into text without showing a keyboard. One utterance per request, as that interface expects.
 */
class AlyoRecognitionService : RecognitionService() {
    private val main = Handler(Looper.getMainLooper())
    private var dictation: Dictation? = null

    override fun onStartListening(intent: Intent?, callback: Callback) {
        val app = application as AlyoApp
        if (!app.hasMicrophone) return callback.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        dictation?.cancel()
        val text = StringBuilder()
        val listener = object : Dictation.Listener {
            override fun onText(final: Dictation.Sentence?, tail: String) = report {
                if (final != null) {
                    val sentence = if (app.settings.spokenPunctuation.value) SpokenPunctuation.apply(final.words).text else final.words.joinToString(" ") { it.text }
                    if (text.isNotEmpty() && !sentence.first().isWhitespace()) text.append(' ')
                    text.append(sentence)
                }
                callback.partialResults(results(listOf(text.toString(), tail).filter { it.isNotEmpty() }.joinToString(" ")))
            }

            override fun onState(state: Dictation.State) = report {
                when (state) {
                    Dictation.State.LOADING -> Unit
                    Dictation.State.LISTENING -> {
                        callback.readyForSpeech(Bundle())
                        callback.beginningOfSpeech()
                    }
                    Dictation.State.FINISHING -> callback.endOfSpeech()
                    Dictation.State.DONE -> {
                        dictation = null
                        if (text.isBlank()) callback.error(SpeechRecognizer.ERROR_NO_MATCH) else callback.results(results(text.toString()))
                    }
                }
            }

            override fun onError(message: String) = report { callback.error(SpeechRecognizer.ERROR_AUDIO) }

            private fun report(block: () -> Unit) {
                // The caller may have gone away; its callback then throws, which is not this service's problem.
                main.post { runCatching(block) }
            }
        }
        val started = app.newDictation(listener, singleUtterance = true) ?: return callback.error(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
        dictation = started
        started.start()
    }

    override fun onStopListening(callback: Callback) {
        dictation?.finish()
    }

    override fun onCancel(callback: Callback) {
        dictation?.cancel()
        dictation = null
    }

    override fun onDestroy() {
        dictation?.cancel()
        super.onDestroy()
    }

    private fun results(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
        putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(1f))
    }
}
