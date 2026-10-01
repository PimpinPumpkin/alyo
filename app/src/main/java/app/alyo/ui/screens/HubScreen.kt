package app.alyo.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardVoice
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.alyo.AlyoApp
import app.alyo.R
import app.alyo.data.Feature
import app.alyo.ui.components.AlyoMark
import app.alyo.ui.formatBytes

/** The icon that stands for a feature wherever it is listed. */
val Feature.icon: ImageVector
    get() = when (this) {
        Feature.RECORDER -> Icons.Rounded.GraphicEq
        Feature.VOICE_TYPING -> Icons.Rounded.KeyboardVoice
        Feature.READ_ALOUD -> Icons.Rounded.RecordVoiceOver
    }

/**
 * The front page behind the "Alyo" icon: each thing the app can do, whether it is on, and the way
 * into it. Turning one off here removes it from the phone (its icon, its keyboard entry) without
 * touching what it has saved.
 */
@Composable
fun HubScreen(app: AlyoApp, onRecorder: () -> Unit, onVoiceTyping: () -> Unit, onReadAloud: () -> Unit, onSettings: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val features by app.settings.features.collectAsState()
    val recordings by app.store.recordings.collectAsState()
    val states by app.models.states.collectAsState()
    val quality by app.settings.quality.collectAsState()
    val language by app.settings.language.collectAsState()
    val voice by app.settings.voice.collectAsState()
    val progress = remember(states, quality, language, features, voice) { modelProgress(app.settings.downloads, states) }

    Column(
        Modifier.fillMaxSize().background(colors.surface).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 640.dp).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                AlyoMark(52.dp)
                Spacer(Modifier.width(8.dp))
                Text("Alyo", style = MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings") }
            }
            Text(
                "Speech for your phone. Everything you say stays on it.",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 20.dp),
            )

            if (features.isNotEmpty() && !progress.ready) {
                // Speech models are chosen in Settings; a voice on its own is chosen on its own page.
                Surface(onClick = if (app.settings.hearing) onSettings else onReadAloud, shape = RoundedCornerShape(28.dp), color = colors.tertiaryContainer, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            if (progress.downloading) "Downloading" else "One download to finish",
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.onTertiaryContainer,
                        )
                        Text(
                            if (progress.downloading) "${formatBytes(progress.doneBytes)} of ${formatBytes(progress.totalBytes)}. You can leave the app."
                            else "What you turned on needs ${formatBytes(progress.totalBytes - progress.doneBytes)} of models on the phone before it can work. Tap to choose and download.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onTertiaryContainer,
                        )
                    }
                }
            }

            FeatureCard(
                feature = Feature.RECORDER,
                on = Feature.RECORDER in features,
                status = when (recordings.size) {
                    0 -> "Nothing recorded yet"
                    1 -> "1 recording"
                    else -> "${recordings.size} recordings"
                },
                onOpen = onRecorder,
                onToggle = { app.settings.setFeature(Feature.RECORDER, it) },
            )
            FeatureCard(
                feature = Feature.VOICE_TYPING,
                on = Feature.VOICE_TYPING in features,
                status = "Set up, settings and a place to try it",
                onOpen = onVoiceTyping,
                onToggle = { app.settings.setFeature(Feature.VOICE_TYPING, it) },
            )
            FeatureCard(
                feature = Feature.READ_ALOUD,
                on = Feature.READ_ALOUD in features,
                status = "${app.settings.voiceSpec.title}. Voices, speed and a place to try it",
                onOpen = onReadAloud,
                onToggle = { app.settings.setFeature(Feature.READ_ALOUD, it) },
            )
            Spacer(Modifier.padding(bottom = 24.dp))
        }
    }
}

@Composable
private fun FeatureCard(feature: Feature, on: Boolean, status: String, onOpen: () -> Unit, onToggle: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = { if (on) onOpen() else onToggle(true) },
        shape = RoundedCornerShape(28.dp),
        color = if (on) colors.surfaceContainerHigh else colors.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Row(Modifier.padding(start = 20.dp, end = 16.dp, top = 20.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).background(if (on) colors.primaryContainer else colors.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) {
                Icon(feature.icon, null, tint = if (on) colors.onPrimaryContainer else colors.onSurfaceVariant)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(feature.title, style = MaterialTheme.typography.titleLarge)
                Text(if (on) status else feature.summary, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = on, onCheckedChange = onToggle)
        }
    }
}
