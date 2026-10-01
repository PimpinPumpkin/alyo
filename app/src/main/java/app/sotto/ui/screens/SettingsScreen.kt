package app.sotto.ui.screens

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import app.sotto.BuildConfig
import app.sotto.SottoApp
import app.sotto.asr.InstallState
import app.sotto.asr.ModelCatalog
import app.sotto.asr.ModelKind
import app.sotto.asr.ModelSource
import app.sotto.asr.ModelSpec
import app.sotto.ui.components.SheetGroup
import app.sotto.ui.components.SheetRow
import app.sotto.ui.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** First run: one screen, no scrolling. Pick a quality, get the model, go. */
@Composable
fun SetupScreen(app: SottoApp, onDone: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(colors.surface).statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
        Spacer(Modifier.weight(1f))
        Text("Transcribe as you speak", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(10.dp))
        Text(
            "Speech is turned into text on this phone, never sent anywhere. It needs a speech model first.",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        ModelChooser(app, onReady = onDone, readyLabel = "Start recording")
        TextButton(onClick = onDone, modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp)) {
            Text("Not now, just record audio")
        }
    }
}

@Composable
fun SettingsScreen(app: SottoApp, onDone: () -> Unit, onLicenses: () -> Unit, onWhatsNew: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val plainName by app.settings.plainName.collectAsState()
    val autoTitle by app.settings.autoTitle.collectAsState()

    Column(Modifier.fillMaxSize().background(colors.surface).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            SectionTitle("Transcription")
            ModelChooser(app, onReady = null, readyLabel = "")

            SectionTitle("Summaries")
            SummaryModelChooser(app)

            SectionTitle("Recordings")
            SheetGroup(
                listOf(
                    SheetRow(
                        Icons.Rounded.AutoAwesome,
                        "Name recordings by topic",
                        detail = "Adds what was talked about to the title",
                        trailing = { Switch(checked = autoTitle, onCheckedChange = null) },
                        onClick = { app.settings.setAutoTitle(!autoTitle) },
                    ),
                ),
            )

            SectionTitle("App")
            SheetGroup(
                listOfNotNull(
                    SheetRow(
                        Icons.Rounded.Badge,
                        "Show as \"Recorder\"",
                        detail = "Use the plain name on the home screen",
                        trailing = { Switch(checked = plainName, onCheckedChange = null) },
                        onClick = { app.settings.setPlainName(!plainName) },
                    ),
                    onWhatsNew?.let { SheetRow(Icons.Rounded.NewReleases, "What's new", detail = "Version ${BuildConfig.VERSION_NAME}", onClick = it) },
                    SheetRow(Icons.Rounded.Favorite, "Support Sotto Recorder", detail = "Free and ad-free. A donation is welcome, never needed.", onClick = { open(context, DONATE_URL) }),
                    SheetRow(Icons.Rounded.Info, "Open-source licenses", onClick = onLicenses),
                ),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, top = 20.dp, bottom = 10.dp))
}

/**
 * The quality choice as two thin rows, and under them whatever the chosen one still needs:
 * a download button, progress, or nothing.
 *
 * @param onReady when given, a button with [readyLabel] appears once the models are in place
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ModelChooser(app: SottoApp, onReady: (() -> Unit)?, readyLabel: String) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val states by app.models.states.collectAsState()
    val qualityId by app.settings.quality.collectAsState()
    val pack = app.settings.pack
    val quality = pack.qualities.firstOrNull { it.id == qualityId } ?: pack.qualities.first()
    val needed: List<ModelSpec> = listOf(pack.live, quality.refiner)
    fun state(spec: ModelSpec) = states[spec.id] ?: InstallState.Missing
    val missing = needed.filter { state(it) != InstallState.Installed }
    val downloading = needed.any { state(it) is InstallState.Downloading }
    val failure = needed.firstNotNullOfOrNull { state(it) as? InstallState.Failed }
    var importing by remember { mutableStateOf(false) }
    var showSources by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val results = withContext(Dispatchers.IO) {
                uris.map { uri ->
                    runCatching { context.contentResolver.openInputStream(uri)!!.use(app.models::importArchive).title }
                        .getOrElse { "Not imported: ${it.message}" }
                }
            }
            importing = false
            Toast.makeText(context, results.joinToString("\n") { if (it.startsWith("Not")) it else "Imported $it" }, Toast.LENGTH_LONG).show()
        }
    }

    Column {
        SheetGroup(
            pack.qualities.map { option ->
                val installed = state(pack.live) == InstallState.Installed && state(option.refiner) == InstallState.Installed
                val size = formatBytes(pack.live.source.downloadBytes + option.refiner.source.downloadBytes)
                SheetRow(
                    icon = if (option.id == "best") Icons.Rounded.HighQuality else Icons.Rounded.Speed,
                    label = option.title + if (option.id == app.settings.recommendedQuality()) "  ·  suits this phone" else "",
                    detail = (if (installed) "Ready" else size) + "  ·  " + option.summary,
                    enabled = !downloading,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (state(option.refiner) == InstallState.Installed && onReady == null) {
                                IconButton(onClick = { app.models.delete(option.refiner) }) {
                                    Icon(Icons.Rounded.DeleteOutline, "Remove the ${option.title} download", tint = colors.onSurfaceVariant)
                                }
                            }
                            RadioButton(selected = option.id == quality.id, onClick = null)
                        }
                    },
                    onClick = { app.settings.setQuality(option.id) },
                )
            },
        )
        Spacer(Modifier.height(12.dp))

        when {
            downloading || importing -> {
                val done = needed.sumOf { spec ->
                    when (val s = state(spec)) {
                        is InstallState.Downloading -> s.doneBytes
                        InstallState.Installed -> spec.source.downloadBytes
                        else -> 0L
                    }
                }
                val total = needed.sumOf { it.source.downloadBytes }
                if (importing) LinearWavyProgressIndicator(Modifier.fillMaxWidth()) else LinearWavyProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (importing) "Importing" else "${formatBytes(done)} of ${formatBytes(total)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (!importing) TextButton(onClick = { needed.forEach(app.models::cancel) }) { Text("Pause") }
                }
            }
            missing.isEmpty() -> if (onReady != null) {
                Button(onClick = onReady, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text(readyLabel, style = MaterialTheme.typography.titleMedium) }
            }
            else -> {
                failure?.let { Text(it.message, style = MaterialTheme.typography.bodyMedium, color = colors.error, modifier = Modifier.padding(bottom = 8.dp)) }
                if (BuildConfig.NETWORK) {
                    Button(onClick = { missing.forEach(app.models::install) }, modifier = Modifier.fillMaxWidth().height(60.dp)) {
                        Icon(Icons.Rounded.Download, null)
                        Spacer(Modifier.width(10.dp))
                        Text((if (failure != null) "Try again" else "Download") + "  ·  " + formatBytes(missing.sumOf { it.source.downloadBytes }), style = MaterialTheme.typography.titleMedium)
                    }
                } else {
                    Button(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth().height(60.dp)) {
                        Icon(Icons.Rounded.FolderOpen, null)
                        Spacer(Modifier.width(10.dp))
                        Text("Import model files", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        if (!downloading && !importing && missing.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                if (BuildConfig.NETWORK) TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import from files instead") }
                TextButton(onClick = { showSources = true }) { Text("Where to get the files") }
            }
        }
    }

    if (showSources) {
        val links = missing.map(ModelCatalog::archiveUrl)
        AlertDialog(
            onDismissRequest = { showSources = false },
            title = { Text("Model files") },
            text = {
                Column {
                    Text("Download these on any device, move them to this phone, then import them. They are the archives the sherpa-onnx project publishes.")
                    Spacer(Modifier.height(12.dp))
                    for (spec in missing) {
                        Text("${spec.archive}.tar.bz2", style = MaterialTheme.typography.bodySmall, color = colors.primary, modifier = Modifier.padding(bottom = 8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Model files", links.joinToString("\n")))
                    showSources = false
                }) { Text("Copy links") }
            },
            dismissButton = { TextButton(onClick = { showSources = false }) { Text("Close") } },
        )
    }
}

/** Which language model writes summaries, and getting it onto the phone. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SummaryModelChooser(app: SottoApp) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val states by app.models.states.collectAsState()
    val chosenId by app.settings.summarizer.collectAsState()
    fun state(spec: ModelSpec) = states[spec.id] ?: InstallState.Missing
    // The imported slot only appears once something has been imported into it.
    val options = ModelCatalog.summarizers + listOfNotNull(ModelCatalog.importedSummarizer.takeIf { state(it) == InstallState.Installed })
    val chosen = options.firstOrNull { it.id == chosenId } ?: options.first()
    val chosenState = state(chosen)
    var importing by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)!!.use(app.models::importArchive) }
            }
            importing = false
            result.onSuccess { if (it.kind == ModelKind.CHAT_GGUF) app.settings.setSummarizer(it.id) }
            Toast.makeText(context, result.fold({ "Imported ${it.title}" }, { "Not imported: ${it.message}" }), Toast.LENGTH_LONG).show()
        }
    }

    Column {
        SheetGroup(
            options.map { option ->
                val size = (option.source as? ModelSource.Files)?.downloadBytes ?: 0L
                SheetRow(
                    icon = Icons.Rounded.AutoAwesome,
                    label = option.title,
                    detail = (if (state(option) == InstallState.Installed) "Ready" else formatBytes(size)) + "  ·  " + option.summary,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (state(option) == InstallState.Installed) {
                                IconButton(onClick = { app.models.delete(option) }) { Icon(Icons.Rounded.DeleteOutline, "Remove ${option.title}", tint = colors.onSurfaceVariant) }
                            }
                            RadioButton(selected = option.id == chosen.id, onClick = null)
                        }
                    },
                    onClick = { app.settings.setSummarizer(option.id) },
                )
            },
        )
        Spacer(Modifier.height(12.dp))
        when {
            importing -> LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            chosenState is InstallState.Downloading -> {
                LinearWavyProgressIndicator(progress = { chosenState.fraction }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${formatBytes(chosenState.doneBytes)} of ${formatBytes(chosenState.totalBytes)}", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = { app.models.cancel(chosen) }) { Text("Pause") }
                }
            }
            chosenState != InstallState.Installed -> {
                (chosenState as? InstallState.Failed)?.let { Text(it.message, style = MaterialTheme.typography.bodyMedium, color = colors.error, modifier = Modifier.padding(bottom = 8.dp)) }
                if (BuildConfig.NETWORK) {
                    Button(onClick = { app.models.install(chosen) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Icon(Icons.Rounded.Download, null)
                        Spacer(Modifier.width(10.dp))
                        Text("Download  ·  " + formatBytes(chosen.source.downloadBytes), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        if (!importing) {
            TextButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Import a GGUF model from files")
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
