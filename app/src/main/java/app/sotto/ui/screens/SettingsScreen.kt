package app.sotto.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.sotto.BuildConfig
import app.sotto.SottoApp
import app.sotto.asr.InstallState
import app.sotto.asr.ModelSpec
import app.sotto.asr.Quality
import app.sotto.ui.components.SheetGroup
import app.sotto.ui.components.SheetRow
import app.sotto.ui.formatBytes

/**
 * Speech model setup. Doubles as the first-run screen ([firstRun]) and as Settings afterwards.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(app: SottoApp, firstRun: Boolean, onDone: () -> Unit, onLicenses: () -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    val states by app.models.states.collectAsState()
    val qualityId by app.settings.quality.collectAsState()
    val pack = app.settings.pack
    val quality = pack.qualities.firstOrNull { it.id == qualityId } ?: pack.qualities.first()
    val needed: List<ModelSpec> = listOf(pack.live, quality.refiner)

    fun state(spec: ModelSpec) = states[spec.id] ?: InstallState.Missing
    val missing = needed.filter { state(it) != InstallState.Installed }
    val downloading = needed.mapNotNull { state(it) as? InstallState.Downloading }
    val failure = needed.firstNotNullOfOrNull { state(it) as? InstallState.Failed }
    val ready = missing.isEmpty()

    Column(Modifier.fillMaxSize().background(colors.surface).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!firstRun) {
                IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
            }
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Box(
                Modifier.size(96.dp).background(colors.primaryContainer, MaterialShapes.SoftBurst.toShape()),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.GraphicEq, null, tint = colors.onPrimaryContainer, modifier = Modifier.size(44.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text(if (firstRun) "Transcribe as you speak" else "Transcription", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(12.dp))
            Text(
                "Sotto Recorder writes down what it hears while you record, and it does it on this phone. " +
                    "That needs a speech model, downloaded once. After that nothing leaves the device and no connection is needed.",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
            )

            Spacer(Modifier.height(28.dp))
            Text("${pack.name} · quality", style = MaterialTheme.typography.titleSmall, color = colors.primary)
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (option in pack.qualities) {
                    val bytes = pack.live.source.downloadBytes + option.refiner.source.downloadBytes
                    QualityCard(
                        quality = option,
                        selected = option.id == quality.id,
                        recommended = option.id == app.settings.recommendedQuality(),
                        installed = state(pack.live) == InstallState.Installed && state(option.refiner) == InstallState.Installed,
                        sizeLabel = formatBytes(bytes),
                        enabled = downloading.isEmpty(),
                        onSelect = { app.settings.setQuality(option.id) },
                        // The big second-pass model is the part worth reclaiming; the live model is shared.
                        onRemove = { app.models.delete(option.refiner) }.takeIf { state(option.refiner) == InstallState.Installed },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CloudOff, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    "Recordings and transcripts are stored only in this app. The download is the only time it goes online.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            if (!firstRun) {
                val plainName by app.settings.plainName.collectAsState()
                val context = LocalContext.current
                Spacer(Modifier.height(28.dp))
                Text("App", style = MaterialTheme.typography.titleSmall, color = colors.primary)
                Spacer(Modifier.height(12.dp))
                SheetGroup(
                    listOf(
                        SheetRow(
                            Icons.Rounded.Badge,
                            "Show as \"Recorder\"",
                            detail = "Use the plain name on the home screen",
                            trailing = { Switch(checked = plainName, onCheckedChange = null) },
                            onClick = { app.settings.setPlainName(!plainName) },
                        ),
                        SheetRow(
                            Icons.Rounded.Favorite,
                            "Support Sotto Recorder",
                            detail = "Free and ad-free. A donation is welcome, never needed.",
                            onClick = { open(context, DONATE_URL) },
                        ),
                        SheetRow(
                            Icons.Rounded.Info,
                            "Open-source licenses",
                            detail = "Version ${BuildConfig.VERSION_NAME}, and what it is built from",
                            onClick = onLicenses,
                        ),
                    ),
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            if (downloading.isNotEmpty()) {
                val done = needed.sumOf { spec ->
                    when (val s = state(spec)) {
                        is InstallState.Downloading -> s.doneBytes
                        InstallState.Installed -> spec.source.downloadBytes
                        else -> 0L
                    }
                }
                val total = needed.sumOf { it.source.downloadBytes }
                LinearWavyProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Downloading  ${formatBytes(done)} of ${formatBytes(total)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { needed.forEach(app.models::cancel) }) { Text("Pause") }
                }
            } else if (ready) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = colors.primary)
                    Spacer(Modifier.width(10.dp))
                    Text("Ready. New recordings will be transcribed.", style = MaterialTheme.typography.bodyLarge)
                }
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                    Text(if (firstRun) "Start recording" else "Done", style = MaterialTheme.typography.titleMedium)
                }
            } else {
                failure?.let {
                    Text(it.message, style = MaterialTheme.typography.bodyMedium, color = colors.error, modifier = Modifier.padding(bottom = 10.dp))
                }
                Button(onClick = { missing.forEach(app.models::install) }, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                    Icon(Icons.Rounded.Download, null)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        (if (failure != null) "Try again" else "Download") + "  ·  " + formatBytes(missing.sumOf { it.source.downloadBytes }),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (firstRun) {
                    TextButton(onClick = onDone, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp)) {
                        Text("Not now, just record audio")
                    }
                }
            }
        }
    }
}

@Composable
private fun QualityCard(
    quality: Quality,
    selected: Boolean,
    recommended: Boolean,
    installed: Boolean,
    sizeLabel: String,
    enabled: Boolean,
    onSelect: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(if (selected) colors.secondaryContainer else colors.surfaceContainer, label = "container")
    Surface(
        onClick = onSelect,
        enabled = enabled,
        shape = RoundedCornerShape(24.dp),
        color = container,
        border = if (selected) BorderStroke(2.dp, colors.primary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 8.dp, end = 20.dp, top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(quality.title, style = MaterialTheme.typography.titleMedium)
                    if (recommended) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "For this phone",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onTertiaryContainer,
                            modifier = Modifier.background(colors.tertiaryContainer, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
                Text(quality.summary, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                Text(
                    if (installed) "Downloaded  ·  $sizeLabel" else "$sizeLabel download",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (installed) colors.primary else colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove, enabled = enabled) {
                    Icon(Icons.Rounded.DeleteOutline, "Remove the ${quality.title} download", tint = colors.onSurfaceVariant)
                }
            }
        }
    }
}

private const val DONATE_URL = "https://buymeacoffee.com/PimpinPumpkin"

private fun open(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No browser installed to open the page", Toast.LENGTH_SHORT).show()
    }
}
