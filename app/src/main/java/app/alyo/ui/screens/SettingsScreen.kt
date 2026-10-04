package app.alyo.ui.screens

import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import app.alyo.data.Feature
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Height
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import app.alyo.R
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.Image
import app.alyo.export.LibraryProvider
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.FolderShared
import android.provider.DocumentsContract
import app.alyo.data.Settings
import app.alyo.data.Backup
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
import app.alyo.BuildConfig
import app.alyo.AlyoApp
import app.alyo.asr.InstallState
import app.alyo.asr.works
import app.alyo.asr.ModelCatalog
import app.alyo.asr.ModelKind
import app.alyo.asr.ModelSource
import app.alyo.asr.ModelSpec
import app.alyo.ui.components.AlyoMark
import app.alyo.ui.components.SheetGroup
import app.alyo.ui.components.SheetRow
import app.alyo.ui.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** First run: one screen, no scrolling. Pick a quality, get the model, go. */
@Composable
fun SetupScreen(app: AlyoApp, onDone: (finishInVoiceTyping: Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val features by app.settings.features.collectAsState()
    val states by app.models.states.collectAsState()
    val downloading = states.values.any { it is InstallState.Downloading }
    // First what the app is for, then the model those features need.
    var chosen by rememberSaveable { mutableStateOf(false) }
    BackHandler(chosen) { chosen = false }
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.surface).statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.TopCenter) {
        val screen = maxHeight
        val tall = screen >= 660.dp
        // The choices are what the page is for, so they are measured first and get the room they
        // need. The logo and name take whatever is left above them, at a size that fits it, and
        // go without when nothing is left. The page only scrolls if the choices alone are taller
        // than the screen.
        SubcomposeLayout(Modifier.widthIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) { constraints ->
            val height = screen.roundToPx()
            val rest = subcompose("rest") {
                Column {
                val heading = if (tall) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall
                val body = if (tall) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium
                if (!chosen) {
                    Column {
                        Text("What would you like it for?", style = heading)
                        Spacer(Modifier.height(if (tall) 8.dp else 4.dp))
                        Text("Pick any or all. You can change this at any time, and what you leave off stays out of your way.", style = body, color = colors.onSurfaceVariant)
                        Spacer(Modifier.height(if (tall) 20.dp else 12.dp))
                        SheetGroup(
                            Feature.entries.map { feature ->
                                SheetRow(
                                    feature.icon,
                                    feature.title,
                                    detail = feature.summary,
                                    trailing = { Switch(checked = feature in features, onCheckedChange = null) },
                                    onClick = { app.settings.setFeature(feature, feature !in features) },
                                )
                            },
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { chosen = true }, enabled = features.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(60.dp)) {
                            Text("Continue", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                } else {
                    // Voice typing has two more steps of its own (the microphone, the keyboard list),
                    // so that is where its setup carries on.
                    val finish = { onDone(Feature.VOICE_TYPING in features) }
                    Column {
                        Text("One download", style = heading)
                        Spacer(Modifier.height(if (tall) 8.dp else 4.dp))
                        Text(
                            if (app.settings.hearing) "Speech is turned into text on this phone, never sent anywhere. It needs a speech model first."
                            else "The voice is made on this phone, and nothing you have read to you is sent anywhere. It needs a voice first.",
                            style = body,
                            color = colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(if (tall) 20.dp else 12.dp))
                        if (app.settings.hearing) ModelChooser(app, onReady = finish, readyLabel = "Done") else VoiceDownload(app, onReady = finish)
                        TextButton(onClick = finish, modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 4.dp)) {
                            Text(if (downloading) "Carry on while it downloads" else "Not now")
                        }
                    }
                }
                }
            }.first().measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            val free = (height - rest.height).coerceAtLeast(0)
            val logo = subcompose("logo") { Lockup(free.toDp()) }.firstOrNull()
                ?.measure(Constraints(minWidth = constraints.maxWidth, maxWidth = constraints.maxWidth, maxHeight = free))
            val total = maxOf(height, rest.height)
            layout(constraints.maxWidth, total) {
                logo?.place(0, (free - logo.height) / 2)
                rest.place(0, total - rest.height)
            }
        }
    }
}

/** The mark and the name, at the largest of three sizes that fits in [room]. Nothing at all if even the smallest does not. */
@Composable
private fun Lockup(room: Dp) {
    when {
        room >= 300.dp -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            AlyoMark((room * 0.42f).coerceIn(104.dp, 176.dp))
            Text("Alyo", style = MaterialTheme.typography.displayLarge.copy(fontSize = 64.sp, lineHeight = 72.sp))
        }
        room >= 176.dp -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            AlyoMark(84.dp)
            Text("Alyo", style = MaterialTheme.typography.displayMedium.copy(fontSize = 44.sp, lineHeight = 52.sp))
        }
        room >= 72.dp -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            AlyoMark(52.dp)
            Spacer(Modifier.width(4.dp))
            Text("Alyo", style = MaterialTheme.typography.displaySmall.copy(fontSize = 38.sp, lineHeight = 46.sp))
        }
    }
}

@Composable
fun SettingsScreen(app: AlyoApp, onDone: () -> Unit, onLicenses: () -> Unit, onWhatsNew: (() -> Unit)?, onFeature: (Feature) -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val features by app.settings.features.collectAsState()
    val recorder = Feature.RECORDER in features
    val recorderIcon by app.settings.recorderIcon.collectAsState()
    val stopAfter by app.settings.stopAfterSeconds.collectAsState()
    val spoken by app.settings.spokenPunctuation.collectAsState()
    val pausePeriods by app.settings.pausePeriods.collectAsState()
    val keepWarm by app.settings.keepWarmMinutes.collectAsState()
    val tallPanel by app.settings.tallPanel.collectAsState()
    val autoTitle by app.settings.autoTitle.collectAsState()
    val sharePicker by app.settings.shareWithPicker.collectAsState()
    val theme by app.settings.theme.collectAsState()

    Column(Modifier.fillMaxSize().background(colors.surface).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
        }
        Column(Modifier.weight(1f).widthIn(max = 640.dp).align(Alignment.CenterHorizontally).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            SectionTitle("Features")
            // The switch turns a feature on or off; the row itself opens its page.
            SheetGroup(
                Feature.entries.map { feature ->
                    val on = feature in features
                    SheetRow(
                        feature.icon,
                        feature.title,
                        detail = when {
                            !on -> feature.summary
                            feature == Feature.VOICE_TYPING -> "Set up, and a place to try it"
                            feature == Feature.READ_ALOUD -> "Voices, speed, and a place to try it"
                            else -> feature.summary
                        },
                        trailing = { Switch(checked = on, onCheckedChange = { app.settings.setFeature(feature, it) }) },
                        onClick = { if (on) onFeature(feature) else app.settings.setFeature(feature, true) },
                    )
                },
            )

            if (app.settings.hearing) {
                SectionTitle("Speech model")
                ModelChooser(app, onReady = null, readyLabel = "")
            }

            if (Feature.VOICE_TYPING in features) {
                SectionTitle("Voice typing")
                SheetGroup(
                    listOf(
                        SheetRow(
                            Icons.Rounded.Timer,
                            "Stop listening",
                            detail = if (stopAfter == 0) "Only when Done is tapped" else "After $stopAfter seconds of silence",
                            onClick = { app.settings.setStopAfterSeconds(Settings.STOP_AFTER_CHOICES.let { it[(it.indexOf(stopAfter) + 1) % it.size] }) },
                        ),
                        SheetRow(
                            Icons.Rounded.Notes,
                            "Spoken punctuation",
                            detail = "Say comma, period, question mark or new line. Backspace right away puts a word back.",
                            trailing = { Switch(checked = spoken, onCheckedChange = null) },
                            onClick = { app.settings.setSpokenPunctuation(!spoken) },
                        ),
                        SheetRow(
                            Icons.Rounded.Notes,
                            "Period at every pause",
                            detail = if (pausePeriods) "Every pause ends the sentence" else "A pause alone does not end the sentence. A period goes in when you say it, or when you go on after a long pause.",
                            trailing = { Switch(checked = pausePeriods, onCheckedChange = null) },
                            onClick = { app.settings.setPausePeriods(!pausePeriods) },
                        ),
                        SheetRow(
                            Icons.Rounded.Height,
                            "Keyboard-height panel",
                            detail = if (tallPanel) "As tall as a keyboard, so the app stays put when you switch to one to fix a word" else "A slim bar that leaves more of the app in view",
                            trailing = { Switch(checked = tallPanel, onCheckedChange = null) },
                            onClick = { app.settings.setTallPanel(!tallPanel) },
                        ),
                        SheetRow(
                            Icons.Rounded.Bolt,
                            "Keep ready",
                            detail = when (keepWarm) {
                                Settings.KEEP_WARM_ALWAYS -> "The model stays in memory until the phone needs the room, so it is ready when you are"
                                0 -> "The model is unloaded as soon as you finish. Every start waits for it."
                                else -> "The model stays in memory for $keepWarm min after you finish"
                            },
                            onClick = { app.settings.setKeepWarmMinutes(Settings.KEEP_WARM_CHOICES.let { it[(it.indexOf(keepWarm) + 1) % it.size] }) },
                        ),
                    ),
                )
            }

            if (recorder) {
            SectionTitle("Summaries (experimental)")
            SummaryModelChooser(app)

            SectionTitle("Recordings")
            LibraryFolderRow(app)
            Spacer(Modifier.height(8.dp))
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
                            speakerState.works -> "On. Speakers are labeled when a recording finishes."
                            speakerState is InstallState.Downloading -> "Downloading the voice model, ${(speakerState.fraction * 100).toInt()}%"
                            speakerState is InstallState.Failed -> "Download failed. Tap twice to try again."
                            else -> "Needs the voice model, which downloads with the speech model."
                        },
                        trailing = { Switch(checked = speakerLabels, onCheckedChange = null) },
                        onClick = {
                            app.settings.setSpeakerLabels(!speakerLabels)
                            // Turning it on fetches the 30 MB voice model if it is not here yet.
                            if (!speakerLabels && speakerState != InstallState.Installed) app.models.install(ModelCatalog.speakers)
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
            }

            SectionTitle("App")
            SheetGroup(
                listOfNotNull(
                    SheetRow(
                        Icons.Rounded.Contrast,
                        "Theme",
                        detail = when (theme) {
                            Settings.THEME_LIGHT -> "Light"
                            Settings.THEME_DARK -> "Dark"
                            else -> "Same as the phone"
                        },
                        onClick = {
                            val order = listOf(Settings.THEME_SYSTEM, Settings.THEME_LIGHT, Settings.THEME_DARK)
                            app.settings.setTheme(order[(order.indexOf(theme) + 1) % order.size])
                        },
                    ),
                    if (recorder) SheetRow(
                        Icons.Rounded.Apps,
                        "Separate Recorder icon",
                        detail = if (recorderIcon) "\"Recorder\" opens the recordings and \"Alyo\" opens the front page" else "One icon. It opens the recordings.",
                        trailing = { Switch(checked = recorderIcon, onCheckedChange = null) },
                        onClick = { app.settings.setRecorderIcon(!recorderIcon) },
                    ) else null,
                    if (recorder) SheetRow(
                        Icons.Rounded.FolderShared,
                        "Show in the file picker",
                        detail = "Other apps can open a recording you pick, and only that one",
                        trailing = { Switch(checked = sharePicker, onCheckedChange = null) },
                        onClick = {
                            app.settings.setShareWithPicker(!sharePicker)
                            // Tell the system picker its list of places has changed.
                            context.contentResolver.notifyChange(DocumentsContract.buildRootsUri(context.packageName + LibraryProvider.AUTHORITY_SUFFIX), null)
                        },
                    ) else null,
                    onWhatsNew?.let { SheetRow(Icons.Rounded.NewReleases, "What's new", detail = "Version ${BuildConfig.VERSION_NAME}", onClick = it) },
                    SheetRow(Icons.Rounded.Favorite, "Support Alyo", detail = "Free and ad-free. A donation is welcome, never needed.", onClick = { open(context, DONATE_URL) }),
                    SheetRow(Icons.Rounded.Info, "Open-source licenses", onClick = onLicenses),
                ),
            )
        }
    }
}

/** Save every recording to one file, and bring them back from it. */
@Composable
private fun BackupRows(app: AlyoApp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        working = "Backing up"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)!!.use { out -> Backup.write(app.store.folder, out, audioOf = { app.store.localAudio(it) }) { done, total -> working = "Backing up $done of $total" } } }
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
                runCatching { context.contentResolver.openInputStream(uri)!!.use { Backup.restore(app.store.folder, it) }.also { app.store.load(); app.library.syncAll() } }
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
                onClick = { save.launch("alyo-backup-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".zip") },
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
private fun ModelChooser(app: AlyoApp, onReady: (() -> Unit)?, readyLabel: String) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val states by app.models.states.collectAsState()
    val qualityId by app.settings.quality.collectAsState()
    val languageCode by app.settings.language.collectAsState()
    val pack = ModelCatalog.language(languageCode)
    val quality = pack.qualities.firstOrNull { it.id == qualityId } ?: pack.qualities.first()
    var choosingLanguage by remember { mutableStateOf(false) }
    val speakerLabels by app.settings.speakerLabels.collectAsState()
    // The small voice model rides along with the speech models, so speaker labels work from the
    // first recording rather than being something to discover and turn on later.
    val features by app.settings.features.collectAsState()
    val voiceId by app.settings.voice.collectAsState()
    // Everything the chosen features need rides on the one button: the voice model for speaker
    // labels with the recorder, and the reading voice with read aloud.
    val extras: List<ModelSpec> = listOfNotNull(
        ModelCatalog.speakers.takeIf { speakerLabels && Feature.RECORDER in features },
        ModelCatalog.voices.firstOrNull { it.id == voiceId }?.takeIf { Feature.READ_ALOUD in features },
    )
    val needed: List<ModelSpec> = listOfNotNull(pack.live, quality.refiner) + extras
    fun state(spec: ModelSpec) = states[spec.id] ?: InstallState.Missing
    val missing = needed.filter { state(it) != InstallState.Installed }
    val downloading = needed.any { state(it) is InstallState.Downloading }
    val failure = needed.firstNotNullOfOrNull { state(it) as? InstallState.Failed }
    // Nothing is missing outright: each model wanted is here in an earlier version.
    val updating = missing.isNotEmpty() && missing.all { state(it) == InstallState.Outdated }
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
            listOf(
                SheetRow(Icons.Rounded.Translate, "Language", detail = pack.name, enabled = !downloading) { choosingLanguage = true },
            ),
        )
        Spacer(Modifier.height(8.dp))
        SheetGroup(
            pack.qualities.map { option ->
                val installed = state(pack.live).works && (option.refiner == null || state(option.refiner).works)
                val size = formatBytes(pack.live.source.downloadBytes + (option.refiner?.source?.downloadBytes ?: 0L) + extras.sumOf { it.source.downloadBytes })
                SheetRow(
                    icon = if (option.id == "best") Icons.Rounded.HighQuality else Icons.Rounded.Speed,
                    label = option.title,
                    detail = (if (installed) "Ready" else size) + "  ·  " + option.summary,
                    enabled = !downloading,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // With no second pass of its own, a quality's download is the live model.
                            val removable = option.refiner ?: pack.live
                            if (state(removable).works && onReady == null) {
                                IconButton(onClick = { app.models.delete(removable) }) {
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
                if (updating) {
                    Text("A newer model is available. The one you have keeps working until it is replaced.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                }
                Button(onClick = { missing.forEach(app.models::install) }, modifier = Modifier.fillMaxWidth().height(60.dp)) {
                    Icon(Icons.Rounded.Download, null)
                    Spacer(Modifier.width(10.dp))
                    val verb = when {
                        failure != null -> "Try again"
                        updating -> "Update"
                        else -> "Download"
                    }
                    Text(verb + "  ·  " + formatBytes(missing.sumOf { it.source.downloadBytes }), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        if (!downloading && !importing && missing.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import from files instead") }
                TextButton(onClick = { showSources = true }) { Text("Where to get the files") }
            }
        }
    }

    if (choosingLanguage) {
        AlertDialog(
            onDismissRequest = { choosingLanguage = false },
            title = { Text("Language spoken") },
            text = {
                Column {
                    for (option in ModelCatalog.languages) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { app.settings.setLanguage(option.code); choosingLanguage = false }.padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option.code == pack.code, onClick = null)
                            Spacer(Modifier.width(16.dp))
                            Text(option.name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    Text("Each language has its own speech model. Recordings you already made keep their transcripts.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { choosingLanguage = false }) { Text("Close") } },
        )
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

/** The one download for someone who chose only read aloud: the voice for their language. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VoiceDownload(app: AlyoApp, onReady: () -> Unit) {
    val states by app.models.states.collectAsState()
    val voiceId by app.settings.voice.collectAsState()
    val voice = ModelCatalog.voices.firstOrNull { it.id == voiceId } ?: ModelCatalog.voices.first()
    val state = states[voice.id] ?: InstallState.Missing
    Column {
        SheetGroup(listOf(SheetRow(Icons.Rounded.RecordVoiceOver, voice.title, detail = voice.summary + ". More voices are in the app.", onClick = { })))
        Spacer(Modifier.height(12.dp))
        when {
            state is InstallState.Downloading -> {
                LinearWavyProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
                Text("${formatBytes(state.doneBytes)} of ${formatBytes(state.totalBytes)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.works -> Button(onClick = onReady, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text("Done", style = MaterialTheme.typography.titleMedium) }
            else -> Button(onClick = { app.models.install(voice) }, modifier = Modifier.fillMaxWidth().height(60.dp)) {
                Icon(Icons.Rounded.Download, null)
                Spacer(Modifier.width(10.dp))
                Text((if (state is InstallState.Failed) "Try again" else "Download") + "  ·  " + formatBytes(voice.source.downloadBytes), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Where recordings are kept, and the choice of somewhere else. */
@Composable
private fun LibraryFolderRow(app: AlyoApp) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val location by app.settings.libraryLocation.collectAsState()
    val status by app.library.status.collectAsState()
    var choosing by remember { mutableStateOf(false) }

    fun move(to: String) {
        choosing = false
        scope.launch {
            val found = app.library.moveTo(to)
            if (found > 0) Toast.makeText(context, if (found == 1) "Found 1 recording in the folder" else "Found $found recordings in the folder", Toast.LENGTH_LONG).show()
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Keep the permission across restarts; without this the folder is lost at the next launch.
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        move(uri.toString())
    }

    SheetGroup(
        listOf(
            SheetRow(
                Icons.Rounded.Folder,
                "Recordings folder",
                // Read again when the location changes.
                detail = status ?: location.let { app.library.label } + if (location == Settings.LIBRARY_PRIVATE) ". Deleted if Alyo is uninstalled." else ". Stays if Alyo is uninstalled.",
                enabled = status == null,
            ) { choosing = true },
        ),
    )

    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Keep recordings in") },
            text = {
                Column {
                    val options = listOf(
                        Triple("Documents/Alyo", "On the phone. Any file manager can see them.", Settings.LIBRARY_DEFAULT),
                        Triple("A folder you choose", "Anywhere, including an SD card. Also how to pick up recordings from an earlier install.", "pick"),
                        Triple("Inside the app", "Hidden from other apps, and deleted if Alyo is uninstalled.", Settings.LIBRARY_PRIVATE),
                    )
                    for ((title, detail, value) in options) {
                        val selected = if (value == "pick") location != Settings.LIBRARY_DEFAULT && location != Settings.LIBRARY_PRIVATE else location == value
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { if (value == "pick") { choosing = false; picker.launch(null) } else if (!selected) move(value) else choosing = false }.padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(if (value == "pick" && selected) app.library.label else title, style = MaterialTheme.typography.bodyLarge)
                                Text(detail, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                            }
                        }
                    }
                    Text("Recordings you already have are moved.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text("Close") } },
        )
    }
}

/** Which language model writes summaries, and getting it onto the phone. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SummaryModelChooser(app: AlyoApp) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val states by app.models.states.collectAsState()
    val chosenId by app.settings.summarizer.collectAsState()
    fun state(spec: ModelSpec) = states[spec.id] ?: InstallState.Missing
    // The imported slot only appears once something has been imported into it.
    val options = ModelCatalog.summarizers + listOfNotNull(ModelCatalog.importedSummarizer.takeIf { state(it).works })
    // Until the user picks one, the choice shown is the one that would actually be used: whatever
    // is installed, and failing that the better model as the one to get.
    val chosen = options.firstOrNull { it.id == chosenId } ?: options.firstOrNull { state(it).works } ?: ModelCatalog.summarizers.last()
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
                    detail = (if (state(option).works) "Ready" else formatBytes(size)) + "  ·  " + option.summary,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (state(option).works) {
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
                Button(onClick = { app.models.install(chosen) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Icon(Icons.Rounded.Download, null)
                    Spacer(Modifier.width(10.dp))
                    Text((if (chosenState == InstallState.Outdated) "Update" else "Download") + "  ·  " + formatBytes(chosen.source.downloadBytes), style = MaterialTheme.typography.titleMedium)
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
