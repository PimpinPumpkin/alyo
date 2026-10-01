package app.alyo.ui.screens

import android.Manifest
import android.content.Intent
import android.provider.Settings as SystemSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.alyo.AlyoApp
import app.alyo.asr.InstallState
import app.alyo.asr.ModelCatalog
import app.alyo.asr.ModelSpec
import app.alyo.asr.works
import app.alyo.data.Settings
import app.alyo.tts.Reader
import app.alyo.tts.SampleText
import app.alyo.ui.components.SheetGroup
import app.alyo.ui.components.SheetRow
import app.alyo.ui.formatBytes
import java.util.Locale

/** Reading aloud: get a voice, make it the phone's voice, and a place to hear it. */
@Composable
fun ReadAloudScreen(app: AlyoApp, onBack: () -> Unit, onSettings: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val states by app.models.states.collectAsState()
    val voiceId by app.settings.voice.collectAsState()
    val rate by app.settings.speechRate.collectAsState()
    val chosen = ModelCatalog.voices.firstOrNull { it.id == voiceId } ?: ModelCatalog.voices.first()
    val chosenState = states[chosen.id] ?: InstallState.Missing
    val ready = chosenState.works

    fun isDefaultEngine() = SystemSettings.Secure.getString(context.contentResolver, "tts_default_synth") == context.packageName
    var systemVoice by remember { mutableStateOf(isDefaultEngine()) }
    LifecycleResumeEffect(Unit) {
        systemVoice = isDefaultEngine()
        onPauseOrDispose { }
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    val reader = remember { Reader(context) }
    DisposableEffect(Unit) { onDispose { reader.close() } }
    val speaking by reader.speaking.collectAsState()

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 640.dp).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Text("Read aloud", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
                IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings") }
            }
            Text(
                if (ready) "Ready. Select text in any app and choose Read aloud, or share text to Alyo." else "A voice that lives on the phone. Two steps and it is yours.",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 20.dp),
            )

            AnimatedVisibility(ready) {
                var text by rememberSaveable(chosen.id) { mutableStateOf(SampleText.sample(chosen.locale)) }
                Surface(color = colors.surfaceBright, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Try it", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(12.dp))
                        TextField(
                            value = text,
                            onValueChange = { text = it },
                            minLines = 3,
                            shape = RoundedCornerShape(20.dp),
                            colors = TextFieldDefaults.colors(
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedContainerColor = colors.surfaceContainerHigh,
                                unfocusedContainerColor = colors.surfaceContainerHigh,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Button(onClick = { if (speaking) reader.stop() else reader.speak(text, chosen.id) }, enabled = text.isNotBlank()) {
                                Icon(if (speaking) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (speaking) "Stop" else "Speak")
                            }
                        }
                    }
                }
            }

            Step(
                number = 1,
                title = "Voice",
                body = when (chosenState) {
                    is InstallState.Downloading -> "${formatBytes(chosenState.doneBytes)} of ${formatBytes(chosenState.totalBytes)}"
                    is InstallState.Failed -> chosenState.message
                    else -> if (ready) "${chosen.title} is on this phone." else "${chosen.title}, a ${formatBytes(chosen.source.downloadBytes)} download. Pick another below if you like."
                },
                done = ready,
                progress = (chosenState as? InstallState.Downloading)?.fraction,
            ) {
                if (chosenState is InstallState.Downloading) {
                    FilledTonalButton(onClick = { app.models.cancel(chosen) }) { Text("Pause") }
                } else {
                    Button(onClick = {
                        if (android.os.Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        app.models.install(chosen)
                    }) { Text(if (chosenState is InstallState.Failed) "Try again" else "Download") }
                }
            }
            Step(
                number = 2,
                title = "The phone's voice",
                body = if (systemVoice) "Alyo speaks for every app that talks: screen readers, navigation, read-aloud buttons." else "Optional. Choose Alyo as the preferred engine and every app that talks uses this voice. Reading from Alyo itself works either way.",
                done = systemVoice,
            ) {
                Button(onClick = { runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Open speech settings") }
            }

            Text("Voices", style = MaterialTheme.typography.titleSmall, color = colors.primary, modifier = Modifier.padding(start = 12.dp, top = 20.dp, bottom = 10.dp))
            // Voices for the phone's own language first.
            val mine = Locale.getDefault().language
            val ordered = ModelCatalog.voices.sortedBy { if (Locale.forLanguageTag(it.locale).language == mine) 0 else 1 }
            SheetGroup(ordered.map { voice -> voiceRow(app, voice, states[voice.id] ?: InstallState.Missing, voice.id == chosen.id) })

            Text("Speed", style = MaterialTheme.typography.titleSmall, color = colors.primary, modifier = Modifier.padding(start = 12.dp, top = 20.dp, bottom = 10.dp))
            SheetGroup(
                listOf(
                    SheetRow(Icons.Rounded.Speed, "Reading speed", detail = if (rate == 1f) "Normal" else "${rate}x") {
                        app.settings.setSpeechRate(Settings.SPEECH_RATES.let { it[(it.indexOf(rate) + 1) % it.size] })
                    },
                ),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** One voice in the list: tap to choose it (and fetch it if it is not here), bin to remove it. */
private fun voiceRow(app: AlyoApp, voice: ModelSpec, state: InstallState, selected: Boolean) = SheetRow(
    icon = Icons.Rounded.RecordVoiceOver,
    label = voice.title,
    detail = voice.summary + "\n" + when (state) {
        is InstallState.Downloading -> "Downloading, ${(state.fraction * 100).toInt()}%"
        is InstallState.Failed -> "Download failed. Tap to try again."
        else -> (if (state.works) "On this phone" else formatBytes(voice.source.downloadBytes)) + "  ·  " + voice.license + if (voice.nonCommercial) " (no commercial use)" else ""
    },
    trailing = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.works && !selected) {
                IconButton(onClick = { app.models.delete(voice) }) { Icon(Icons.Rounded.DeleteOutline, "Remove ${voice.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            RadioButton(selected = selected, onClick = null)
        }
    },
    onClick = {
        app.settings.setVoice(voice.id)
        if (!state.works && state !is InstallState.Downloading) app.models.install(voice)
    },
)
