package app.sotto.ui.screens

import app.sotto.export.LibraryProvider
import androidx.compose.material.icons.rounded.FolderShared
import android.provider.DocumentsContract
import app.sotto.data.Settings
import app.sotto.data.Backup
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Archive
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
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
    Box(Modifier.fillMaxSize().background(colors.surface), contentAlignment = Alignment.BottomCenter) {
    // Pinned to the bottom when there is room; scrolls when there is not (a phone on its side).
    Column(
        Modifier.fillMaxHeight().widthIn(max = 640.dp).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
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
}

@Composable
fun SettingsScreen(app: SottoApp, onDone: () -> Unit, onLicenses: () -> Unit, onWhatsNew: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val plainName by app.settings.plainName.collectAsState()
    val autoTitle by app.settings.autoTitle.collectAsState()
    val sharePicker by app.settings.shareWithPicker.collectAsState()

    Column(Modifier.fillMaxSize().background(colors.surface).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
        }
        Column(Modifier.weight(1f).widthIn(max = 640.dp).align(Alignment.CenterHorizontally).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            SectionTitle("Transcription")
            ModelChooser(app, onReady = null, readyLabel = "")

            SectionTitle("Summaries (experimental)")
            SummaryModelChooser(app)

            SectionTitle("Recordings")
            val speakerLabels by app.settings.speakerLabels.collectAsState()
            val states by app.models.states.collectAsState()
            val speakerState = states[ModelCatalog.speakers.id] ?: InstallState.Missing
            SheetGroup(
                listOf(
                    SheetRow(
                        Icons.Rounded.RecordVoiceOver,
                        "Label speakers",
                        detail = when {
                            !speakerLabels -> "Tell voices apart in the transcript"
                            speakerState == InstallState.Installed -> "On. Speakers are labeled when a recording finishes."
                            speakerState is InstallState.Downloading -> "Downloading the voice model, ${(speakerState.fraction * 100).toInt()}%"
                            speakerState is InstallState.Failed -> "Download failed. Tap twice to try again."
                            BuildConfig.NETWORK -> "Downloading the voice model"
                            else -> "Needs the voice model: import 3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx"
                        },
                        trailing = { Switch(checked = speakerLabels, onCheckedChange = null) },
                        onClick = {
                            app.settings.setSpeakerLabels(!speakerLabels)
                            // Turning it on fetches the 30 MB voice model if it is not here yet.
                            if (!speakerLabels && BuildConfig.NETWORK && speakerState != InstallState.Installed) app.models.install(ModelCatalog.speakers)
                        },
                    ),
                    SheetRow(
                        Icons.Rounded.AutoAwesome,
                        "Name recordings by topic",
                        detail = "Adds what was talked about to the title",
                        trailing = { Switch(checked = autoTitle, onCheckedChange = null) },
                        onClick = { app.settings.setAutoTitle(!autoTitle) },
                    ),
                ),
            )

            SectionTitle("Backup")
            BackupRows(app)

            SectionTitle("App")
            SheetGroup(
                listOfNotNull(
                    SheetRow(
                        Icons.Rounded.FolderShared,
                        "Show in the file picker",
                        detail = "Other apps can open a recording you pick, and only that one",
                        trailing = { Switch(checked = sharePicker, onCheckedChange = null) },
                        onClick = {
                            app.settings.setShareWithPicker(!sharePicker)
                            // Tell the system picker its list of places has changed.
                            context.contentResolver.notifyChange(DocumentsContract.buildRootsUri(context.packageName + LibraryProvider.AUTHORITY_SUFFIX), null)
                        },
                    ),
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

/** Save every recording to one file, and bring them back from it. */
@Composable
private fun BackupRows(app: SottoApp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        working = "Backing up"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)!!.use { out -> Backup.write(app.store.folder, out) { done, total -> working = "Backing up $done of $total" } } }
            }
            working = null
            Toast.makeText(context, result.fold({ "Backed up $it recordings" }, { "Backup failed: ${it.message}" }), Toast.LENGTH_LONG).show()
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        working = "Restoring"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)!!.use { Backup.restore(app.store.folder, it) }.also { app.store.load() } }
            }
            working = null
            Toast.makeText(context, result.fold({ if (it == 0) "Nothing new in that backup" else "Restored $it recordings" }, { "Restore failed: ${it.message}" }), Toast.LENGTH_LONG).show()
        }
    }
    SheetGroup(
        listOf(
            SheetRow(
                Icons.Rounded.Archive,
                "Back up recordings",
                detail = working ?: "Audio, transcripts and titles, as one zip file",
                enabled = working == null,
                onClick = { save.launch("sotto-backup-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".zip") },
            ),
            SheetRow(
                Icons.Rounded.Unarchive,
                "Restore from a backup",
                detail = "Adds recordings this phone does not have",
                enabled = working == null,
                onClick = { open.launch(arrayOf("application/zip", "application/octet-stream")) },
            ),
        ),
    )
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
                    label = option.title,
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
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
