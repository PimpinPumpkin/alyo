package app.alyo.tts

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.alyo.AlyoApp
import app.alyo.MainActivity
import app.alyo.ui.theme.AlyoTheme

/**
 * Reads aloud the text another app hands over: a selection ("Read aloud" in the menu over
 * selected text) or something shared. A sheet over that app shows what is being read and lets it
 * be stopped; closing the sheet stops it too.
 */
class ReadAloudActivity : ComponentActivity() {
    private lateinit var reader: Reader

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        reader = Reader(this)
        val text = (intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT) ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        val app = application as AlyoApp
        setContent {
            AlyoTheme {
                val colors = MaterialTheme.colorScheme
                val speaking by reader.speaking.collectAsState()
                val hasVoice = remember { app.voices.installed().isNotEmpty() }
                LaunchedEffect(Unit) { if (hasVoice && text.isNotEmpty()) reader.speak(text) }
                // Tapping outside the sheet closes it, as with any other sheet.
                Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { finish() }, contentAlignment = Alignment.BottomCenter) {
                    Surface(
                        color = colors.surfaceContainerLow,
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        modifier = Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) { },
                    ) {
                        Column(Modifier.navigationBarsPadding().padding(24.dp)) {
                            Text("Read aloud", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                when {
                                    !hasVoice -> "Alyo has no voice yet. Download one and this will work."
                                    text.isEmpty() -> "There was no text to read."
                                    else -> text
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                            )
                            Spacer(Modifier.height(20.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                                if (!hasVoice) {
                                    Button(onClick = {
                                        startActivity(Intent(this@ReadAloudActivity, MainActivity::class.java).setAction(MainActivity.ACTION_READ_ALOUD).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                        finish()
                                    }) { Text("Get a voice") }
                                } else {
                                    FilledTonalButton(onClick = { finish() }) { Text("Close") }
                                    Spacer(Modifier.width(8.dp))
                                    Button(onClick = { if (speaking) reader.stop() else reader.speak(text) }, enabled = text.isNotEmpty()) {
                                        Icon(if (speaking) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null)
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (speaking) "Stop" else "Read")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        reader.close()
        super.onDestroy()
    }
}
