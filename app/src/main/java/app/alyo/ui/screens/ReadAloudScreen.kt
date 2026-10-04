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
import app.alyo.asr.Synth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.TextButton
import app.alyo.tts.VoicePreview
import androidx.compose.material3.FilledTonalIconButton
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
    val preview = remember { VoicePreview(context) }
    DisposableEffect(Unit) { onDispose { preview.stop() } }
    val previewing by preview.playing.collectAsState()

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
            SheetGroup(ordered.filter { it.synth == Synth.PIPER }.map { voice -> voiceRow(app, voice, states[voice.id] ?: InstallState.Missing, chosen, previewing == voice.id) { preview.toggle(voice.id) } })

            val supertonic = ordered.filter { it.synth == Synth.SUPERTONIC }
            Text("Supertonic voices", style = MaterialTheme.typography.titleSmall, color = colors.primary, modifier = Modifier.padding(start = 12.dp, top = 20.dp, bottom = 4.dp))
            Text(
                "More natural, and each speaks 31 languages. One 129 MB download for all ten, under the OpenRAIL-M license. They take about twice as long to start talking as the voices above.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            // They are one download, so its state is said once here and not on every row.
            val shared = supertonic.firstOrNull()
            val sharedState = shared?.let { states[it.id] }
            when {
                sharedState is InstallState.Downloading -> "Downloading, ${(sharedState.fraction * 100).toInt()}%"
                sharedState is InstallState.Failed -> "Download failed. Tap a voice to try again."
                sharedState?.works == true -> "On this phone."
                else -> null
            }?.let { status ->
                Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(status, style = MaterialTheme.typography.bodyMedium, color = colors.primary, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
                    if (sharedState?.works == true && shared.key != chosen.key) TextButton(onClick = { app.models.delete(shared) }) { Text("Remove") }
                }
            }
            Spacer(Modifier.height(10.dp))
            SheetGroup(supertonic.map { voice -> voiceRow(app, voice, states[voice.id] ?: InstallState.Missing, chosen, previewing == voice.id) { preview.toggle(voice.id) } })

            Text("Speed", style = MaterialTheme.typography.titleSmall, color = colors.primary, modifier = Modifier.padding(start = 12.dp, top = 20.dp, bottom = 10.dp))
            SheetGroup(
                listOf(
                    SheetRow(Icons.Rounded.Speed, "Reading speed", detail = if (rate == 1f) "Normal" else "${rate}x") {
                        app.settings.setSpeechRate(Settings.SPEECH_RATES.let { it[(it.indexOf(rate) + 1) % it.size] })
                    },
                ),
            )

            val ownWords by app.settings.ownWords.collectAsState()
            var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
            Text("Your words", style = MaterialTheme.typography.titleSmall, color = colors.primary, modifier = Modifier.padding(start = 12.dp, top = 20.dp, bottom = 4.dp))
            Text(
                "Names and words you want said your way. They stay on this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            )
            SheetGroup(
                ownWords.entries.sortedBy { it.key.lowercase() }.map { (word, spelling) ->
                    SheetRow(
                        Icons.Rounded.RecordVoiceOver,
                        word,
                        detail = "Said as: $spelling",
                        trailing = { IconButton(onClick = { app.settings.removeOwnWord(word) }) { Icon(Icons.Rounded.DeleteOutline, "Remove $word", tint = colors.onSurfaceVariant) } },
                    ) { editing = word to spelling }
                } + SheetRow(Icons.Rounded.Add, "Add a word") { editing = "" to "" },
            )
            editing?.let { (word, spelling) ->
                OwnWordDialog(
                    word, spelling,
                    canHear = ready,
                    onHear = { reader.speak(it, chosen.id) },
                    onSave = { newWord, newSpelling ->
                        if (word.isNotEmpty() && !word.equals(newWord, ignoreCase = true)) app.settings.removeOwnWord(word)
                        app.settings.setOwnWord(newWord, newSpelling)
                        editing = null
                    },
                    onDismiss = { editing = null },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Adds or changes one of the user's own words: the word as it is written, and a spelling that
 * reads the way they say it. The spelling can be heard before it is kept, since finding one
 * that works is trial and error.
 */
@Composable
private fun OwnWordDialog(word: String, spelling: String, canHear: Boolean, onHear: (String) -> Unit, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var newWord by rememberSaveable { mutableStateOf(word) }
    var newSpelling by rememberSaveable { mutableStateOf(spelling) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (word.isEmpty()) "Add a word" else "Change a word") },
        text = {
            Column {
                OutlinedTextField(newWord, { newWord = it }, label = { Text("The word, as it is written") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(newSpelling, { newSpelling = it.replace("\n", " ").replace("\t", " ") }, label = { Text("Spell it the way it sounds") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(
                    "Try a few spellings and listen. Small changes matter: a voice may read \"too-sahn\" and \"toosonn\" differently.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (canHear) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { onHear(newSpelling) }, enabled = newSpelling.isNotBlank()) {
                        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Hear it")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(newWord.trim(), newSpelling.trim()) }, enabled = newWord.isNotBlank() && newSpelling.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * One voice in the list: the button in front plays a short sample, a tap on the row chooses the
 * voice (and fetches it if it is not here), the bin removes it. Voices that share a download are
 * removed together, from above their group.
 */
private fun voiceRow(app: AlyoApp, voice: ModelSpec, state: InstallState, chosen: ModelSpec, previewing: Boolean, onPreview: () -> Unit) = SheetRow(
    icon = Icons.Rounded.RecordVoiceOver,
    leading = {
        FilledTonalIconButton(onClick = onPreview, modifier = Modifier.size(40.dp)) {
            Icon(if (previewing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, if (previewing) "Stop the sample" else "Hear ${voice.title}")
        }
    },
    label = voice.title,
    detail = when {
        // Voices that share a download say its size, license and progress once, above the group.
        voice.store.isNotEmpty() -> voice.summary
        state is InstallState.Downloading -> voice.summary + "\nDownloading, ${(state.fraction * 100).toInt()}%"
        state is InstallState.Failed -> voice.summary + "\nDownload failed. Tap to try again."
        else -> voice.summary + "\n" + (if (state.works) "On this phone" else formatBytes(voice.source.downloadBytes)) + "  ·  " + voice.license + if (voice.nonCommercial) " (no commercial use)" else ""
    },
    trailing = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.works && voice.store.isEmpty() && voice.id != chosen.id) {
                IconButton(onClick = { app.models.delete(voice) }) { Icon(Icons.Rounded.DeleteOutline, "Remove ${voice.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            RadioButton(selected = voice.id == chosen.id, onClick = null)
        }
    },
    onClick = {
        app.settings.setVoice(voice.id)
        if (!state.works && state !is InstallState.Downloading) app.models.install(voice)
    },
)
