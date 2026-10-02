package app.alyo.recognition

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.alyo.AlyoApp
import app.alyo.MainActivity
import app.alyo.dictation.Dictation
import app.alyo.ime.PanelPhase
import app.alyo.ime.VoiceOrb
import app.alyo.text.SpokenPunctuation
import app.alyo.ui.theme.AlyoTheme

/**
 * The sheet other apps get when they ask Android for speech input with its own prompt (a search
 * box's microphone button, say). It listens for one utterance and hands the text back.
 */
class RecognizeActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val app get() = application as AlyoApp
    private var dictation: Dictation? = null

    private val heard = mutableStateOf("")
    private val phase = mutableStateOf(PanelPhase.WARMING)
    private val problem = mutableStateOf<String?>(null)
    private val text = StringBuilder()

    private val askMicrophone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) begin() else problem.value = "Alyo needs the microphone permission."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val prompt = intent.getStringExtra(RecognizerIntent.EXTRA_PROMPT)
        setContent {
            AlyoTheme {
                val colors = MaterialTheme.colorScheme
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))
                        .clickable(interactionSource = null, indication = null) { cancel() },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Surface(
                        color = colors.surfaceContainer,
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        // Swallows taps, so only the dimmed area above the sheet dismisses it.
                        modifier = Modifier.fillMaxWidth().clickable(interactionSource = null, indication = null) {},
                    ) {
                        Column(Modifier.navigationBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                problem.value ?: heard.value.ifEmpty { prompt ?: "Listening" },
                                style = MaterialTheme.typography.headlineSmall,
                                color = if (heard.value.isEmpty()) colors.onSurfaceVariant else colors.onSurface,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            )
                            if (problem.value == null) {
                                VoiceOrb(phase = phase.value, level = { dictation?.level ?: 0f })
                                Spacer(Modifier.height(12.dp))
                                Button(onClick = { dictation?.finish() }, enabled = phase.value != PanelPhase.FINISHING, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                                    Text("Done", style = MaterialTheme.typography.titleMedium)
                                }
                            } else {
                                Spacer(Modifier.height(12.dp))
                                Button(onClick = { startActivity(Intent(this@RecognizeActivity, MainActivity::class.java)); cancel() }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                                    Text("Open Alyo", style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
        when {
            !app.hasModel -> problem.value = "Alyo needs its speech model before it can listen."
            !app.hasMicrophone -> askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
            else -> begin()
        }
    }

    private fun begin() {
        val listener = object : Dictation.Listener {
            override fun onText(final: Dictation.Sentence?, tail: String) = onMain {
                if (final != null) {
                    val sentence = if (app.settings.spokenPunctuation.value) SpokenPunctuation.apply(final.words).text else final.words.joinToString(" ") { it.text }
                    if (text.isNotEmpty() && !sentence.first().isWhitespace()) text.append(' ')
                    text.append(sentence)
                }
                heard.value = listOf(text.toString(), tail).filter { it.isNotEmpty() }.joinToString(" ")
            }

            override fun onState(state: Dictation.State) = onMain {
                when (state) {
                    Dictation.State.LOADING -> phase.value = PanelPhase.WARMING
                    Dictation.State.LISTENING -> phase.value = PanelPhase.LISTENING
                    Dictation.State.FINISHING -> phase.value = PanelPhase.FINISHING
                    Dictation.State.DONE -> if (problem.value == null) deliver()
                }
            }

            override fun onError(message: String) = onMain { problem.value = message }

            private fun onMain(block: () -> Unit) {
                main.post { if (!isFinishing && !isDestroyed) block() }
            }
        }
        dictation = app.newDictation(listener, singleUtterance = true)
        dictation?.start()
    }

    private fun deliver() {
        dictation = null
        if (text.isBlank()) return cancel()
        val result = Intent().apply {
            putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(text.toString()))
            putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, floatArrayOf(1f))
        }
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private fun cancel() {
        dictation?.cancel()
        dictation = null
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    override fun onStop() {
        super.onStop()
        // Leaving the sheet is cancelling it: the microphone must not stay open behind another app.
        if (!isFinishing && dictation != null) cancel()
    }

    override fun onDestroy() {
        dictation?.cancel()
        super.onDestroy()
    }
}
