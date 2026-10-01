package app.alyo.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.alyo.AlyoApp
import app.alyo.BuildConfig
import app.alyo.asr.InstallState
import app.alyo.asr.works
import app.alyo.data.Settings
import app.alyo.ui.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(app: AlyoApp, onBack: () -> Unit, onLicenses: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val settings = app.settings
    val states by app.models.states.collectAsState()
    val quality by settings.quality.collectAsState()
    val stopAfter by settings.stopAfterSeconds.collectAsState()
    val spoken by settings.spokenPunctuation.collectAsState()
    val keepWarm by settings.keepWarmMinutes.collectAsState()
    val theme by settings.theme.collectAsState()
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    val pickArchive = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val message = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)!!.use(app.models::importArchive) }
                    .fold({ "Installed ${it.title}" }, { it.message ?: "Could not read that file" })
            }
            importing = false
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Panel("Accuracy", "Words appear as you talk either way. This picks the model that rewrites each sentence with punctuation once you pause.") {
                settings.pack.qualities.forEach { option ->
                    val refiner = option.refiner
                    val state = refiner?.let { states[it.id] }
                    val selected = option.id == quality
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            settings.setQuality(option.id)
                            if (refiner != null && !state.works) app.models.install(refiner)
                        }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Column(Modifier.padding(start = 16.dp).weight(1f)) {
                            Text(option.title, style = MaterialTheme.typography.titleMedium)
                            Text(option.summary, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                            Text(
                                when (state) {
                                    is InstallState.Downloading -> "Downloading, ${formatBytes(state.doneBytes)} of ${formatBytes(state.totalBytes)}"
                                    is InstallState.Failed -> "Download failed: ${state.message}"
                                    InstallState.Installed, InstallState.Outdated -> "On this phone"
                                    else -> refiner?.let { "${formatBytes(it.source.downloadBytes)} download" }.orEmpty()
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.primary,
                            )
                            if (state is InstallState.Downloading) {
                                Spacer(Modifier.height(6.dp))
                                LinearWavyProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { pickArchive.launch(arrayOf("*/*")) }, enabled = !importing) {
                        Text(if (importing) "Importing" else "Import a model from files")
                    }
                }
            }

            Panel("Stop listening", "Alyo hands the keyboard back after this much silence. Done always stops it at once.") {
                Choices(Settings.STOP_AFTER_CHOICES, stopAfter, { if (it == 0) "Never" else "$it s" }, settings::setStopAfterSeconds)
            }

            Panel("Spoken punctuation", null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Punctuation is added for you. With this on you can also say comma, period, question mark, exclamation point, colon, semicolon, new line or new paragraph. " +
                            "If a word you meant gets turned into a mark, backspace straight away puts it back.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(end = 16.dp),
                    )
                    Switch(checked = spoken, onCheckedChange = settings::setSpokenPunctuation)
                }
            }

            Panel("Keep ready", "How long the speech model stays in memory after you dictate, so the next time starts without a pause.") {
                Choices(Settings.KEEP_WARM_CHOICES, keepWarm, { if (it == 0) "Off" else "$it min" }, settings::setKeepWarmMinutes)
            }

            Panel("Theme", null) {
                Choices(
                    listOf(Settings.THEME_SYSTEM, Settings.THEME_LIGHT, Settings.THEME_DARK), theme,
                    { it.replaceFirstChar(Char::uppercase) }, settings::setTheme,
                )
            }

            Surface(onClick = onLicenses, color = colors.surfaceBright, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text("Open-source licenses", style = MaterialTheme.typography.titleLarge)
                    Text("Alyo ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Panel(title: String, body: String?, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceBright, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(options: List<T>, chosen: T, label: (T) -> String, onChoose: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(selected = option == chosen, onClick = { onChoose(option) }, label = { Text(label(option)) })
        }
    }
}
