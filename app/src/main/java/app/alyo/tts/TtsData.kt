package app.alyo.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import app.alyo.AlyoApp
import app.alyo.MainActivity
import app.alyo.asr.ModelCatalog
import java.util.Locale

// Three small screens Android expects every speech engine to answer. None of them shows anything.

private fun tag(locale: String): String = Locale.forLanguageTag(locale).let { "${it.isO3Language}-${it.isO3Country}" }

/** "Which voices do you have?", asked by the system's text-to-speech settings. */
class CheckVoiceData : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val installed = (application as AlyoApp).voices.installed().map { tag(it.locale) }.distinct()
        val missing = ModelCatalog.voices.map { tag(it.locale) }.distinct() - installed.toSet()
        val result = Intent()
            .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, ArrayList(installed))
            .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, ArrayList(missing))
        setResult(if (installed.isEmpty()) TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL else TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, result)
        finish()
    }
}

/** "Install voice data": voices are chosen and downloaded in the app itself, so open it there. */
class InstallVoiceData : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_READ_ALOUD).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}

/** The sentence the system's settings play when someone taps "Listen to an example". */
class SampleText : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val language = intent?.getStringExtra("language").orEmpty()
        setResult(TextToSpeech.LANG_AVAILABLE, Intent().putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, sample(language)))
        finish()
    }

    companion object {
        fun sample(language: String): String = when (language.take(2).lowercase()) {
            "de" -> "Das ist Alyo. Ich lese Ihnen vor, direkt auf diesem Telefon."
            "fr" -> "Voici Alyo. Je lis à voix haute, directement sur ce téléphone."
            "es" -> "Esto es Alyo. Leo en voz alta, directamente en este teléfono."
            else -> "This is Alyo. I read aloud right here on this phone, with nothing sent anywhere."
        }
    }
}
