package app.alyo.tts

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import app.alyo.AlyoApp
import app.alyo.asr.ModelSpec
import java.util.Locale

/**
 * Alyo as the phone's voice. Android hands text to whichever speech engine the user has chosen;
 * with this one chosen, a screen reader, a navigation app or a "read aloud" button speaks with
 * an Alyo voice, made on the phone.
 */
class AlyoTtsService : TextToSpeechService() {
    private val app get() = application as AlyoApp

    // What the last successful load asked for, which is what onGetLanguage must report back.
    private var current = arrayOf("eng", "USA", "")

    private fun pick(language: String?, country: String?): ModelSpec? = app.voices.voiceFor(language, country, app.settings.voiceSpec)

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val voice = pick(lang, country) ?: return TextToSpeech.LANG_NOT_SUPPORTED
        val sameCountry = Locale.forLanguageTag(voice.locale).isO3Country.equals(country, ignoreCase = true)
        return if (sameCountry) TextToSpeech.LANG_COUNTRY_AVAILABLE else TextToSpeech.LANG_AVAILABLE
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val result = onIsLanguageAvailable(lang, country, variant)
        if (result != TextToSpeech.LANG_NOT_SUPPORTED) current = arrayOf(lang.orEmpty(), country.orEmpty(), "")
        return result
    }

    override fun onGetLanguage(): Array<String> = current

    override fun onGetVoices(): List<Voice> = app.voices.installed().map { spec ->
        Voice(spec.id, Locale.forLanguageTag(spec.locale), Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, false, emptySet())
    }

    override fun onIsValidVoiceName(voiceName: String?): Int =
        if (app.voices.installed().any { it.id == voiceName }) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onLoadVoice(voiceName: String?): Int = onIsValidVoiceName(voiceName)

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? = pick(lang, country)?.id

    override fun onStop() = app.voices.stop()

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val voice = app.voices.installed().firstOrNull { it.id == request.voiceName } ?: pick(request.language, request.country)
        if (voice == null) {
            callback.error(TextToSpeech.ERROR_NOT_INSTALLED_YET)
            return
        }
        callback.start(voice.synth.sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)
        val text = request.charSequenceText?.toString().orEmpty()
        if (text.isNotBlank()) {
            // The request's rate is a percentage of normal; the user's own setting scales it again.
            val speed = request.speechRate / 100f * app.settings.speechRate.value
            val limit = callback.maxBufferSize
            try {
                val language = request.language?.let { asked -> Locale.getISOLanguages().firstOrNull { Locale(it).isO3Language.equals(asked, ignoreCase = true) } }
                app.voices.speak(voice, text, speed, language) { samples ->
                    val bytes = ByteArray(samples.size * 2)
                    for (i in samples.indices) {
                        val s = (samples[i] * 32767f).toInt().coerceIn(-32768, 32767)
                        bytes[i * 2] = s.toByte()
                        bytes[i * 2 + 1] = (s shr 8).toByte()
                    }
                    var sent = 0
                    while (sent < bytes.size) {
                        val n = minOf(limit, bytes.size - sent)
                        if (callback.audioAvailable(bytes, sent, n) != TextToSpeech.SUCCESS) return@speak false
                        sent += n
                    }
                    true
                }
            } catch (e: Throwable) {
                callback.error(TextToSpeech.ERROR_SYNTHESIS)
                return
            }
        }
        callback.done()
    }
}
