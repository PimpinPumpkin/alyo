package app.sotto

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import app.sotto.asr.InstallState
import app.sotto.data.RecordingMeta
import app.sotto.data.Settings
import app.sotto.ui.screens.CropScreen
import app.sotto.ui.screens.ShareSheet
import app.sotto.ui.screens.HomeScreen
import app.sotto.ui.screens.LicensesScreen
import app.sotto.ui.screens.PlayerScreen
import app.sotto.ui.screens.RecordScreen
import app.sotto.ui.screens.SettingsScreen
import app.sotto.ui.screens.SetupScreen
import app.sotto.ui.screens.WhatsNew
import app.sotto.ui.screens.WhatsNewSheet
import app.sotto.ui.theme.SottoTheme
import java.io.File

class MainActivity : ComponentActivity() {
    private val debugWav = mutableStateOf<File?>(null)
    private val debugDark = mutableStateOf<Boolean?>(null)
    // Set when the app is opened by the widget, the quick settings tile or the launcher shortcut.
    private val recordRequested = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        readDebugIntent(intent)
        if (savedInstanceState == null) readAction(intent)
        setContent {
            val choice by (application as SottoApp).settings.theme.collectAsState()
            val dark = debugDark.value ?: when (choice) {
                Settings.THEME_LIGHT -> false
                Settings.THEME_DARK -> true
                else -> isSystemInDarkTheme()
            }
            // The status and navigation bar icons have to follow the app's theme, not the phone's,
            // or a light app on a dark phone gets white icons on a white bar.
            LaunchedEffect(dark) {
                val bars = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            SottoTheme(dark = dark) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    App(application as SottoApp, debugWav.value, recordRequested.value, onRecordConsumed = { recordRequested.value = false }) { debugWav.value = null }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readDebugIntent(intent)
        readAction(intent)
    }

    private fun readAction(intent: Intent?) {
        if (intent?.action == ACTION_RECORD) {
            intent.action = null
            recordRequested.value = true
        }
    }

    companion object {
        /** Open the app and start recording straight away. */
        const val ACTION_RECORD = "app.sotto.action.RECORD"
    }

    /**
     * Debug builds only: `adb shell am start -n app.sotto/.MainActivity --es wav <path>` records
     * from a WAV file as though it were the microphone, so the pipeline can be tested hands-free,
     * and `--es theme light|dark` shows the other theme without touching the phone's own setting.
     */
    private fun readDebugIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG && !BuildConfig.TEST_HOOKS) return
        // A release build cannot be reached with run-as, so its test audio goes in the app's
        // external files folder, which has to exist (and be the app's own) before adb can fill it.
        getExternalFilesDir(null)
        intent?.getStringExtra("theme")?.let { debugDark.value = it == "dark" }
        val path = intent?.getStringExtra("wav") ?: return
        intent.removeExtra("wav")
        debugWav.value = File(path).takeIf { it.canRead() }
    }
}

/** Screens are plain strings so the back stack survives rotation and process death as-is. */
private object Routes {
    const val HOME = "home"
    const val RECORD = "record"
    const val SETUP = "setup"
    const val SETTINGS = "settings"
    const val LICENSES = "licenses"
    fun player(id: String, autoplay: Boolean = false, find: String = "") = "player/$id/$autoplay/${Uri.encode(find)}"
    fun crop(id: String) = "crop/$id"
}

@Composable
private fun App(app: SottoApp, debugWav: File?, recordRequested: Boolean, onRecordConsumed: () -> Unit, onDebugWavConsumed: () -> Unit) {
    val context = LocalContext.current
    val stack: SnapshotStateList<String> = rememberSaveable(
        saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() }),
    ) { mutableListOf(Routes.HOME).toMutableStateList() }
    var forward by remember { mutableStateOf(true) }
    var exporting by remember { mutableStateOf<RecordingMeta?>(null) }
    val notes = remember { WhatsNew.notes(context) }
    var showNotes by remember { mutableStateOf(WhatsNew.shouldShow(context)) }
    val session by app.recorder.session.collectAsState()

    fun push(route: String) {
        forward = true
        if (stack.lastOrNull() != route) stack.add(route)
    }

    fun pop() {
        forward = false
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    fun replaceTop(route: String) {
        forward = true
        stack[stack.lastIndex] = route
    }

    // First launch: offer the speech model before anything else.
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
        val modelMissing = app.models.usable(app.settings.pack.live) == null
        if (modelMissing && !prefs.getBoolean("setup_seen", false) && stack.size == 1) {
            prefs.edit { putBoolean("setup_seen", true) }
            push(Routes.SETUP)
        }
    }

    // Arriving while a recording is running (e.g. from the notification) lands on the record screen.
    LaunchedEffect(session != null) {
        if (session != null && Routes.RECORD !in stack) {
            // Always directly above the library, so minimizing lands where the "Recording" pill is.
            forward = true
            stack.clear()
            stack.add(Routes.HOME)
            stack.add(Routes.RECORD)
        }
        if (session == null && stack.lastOrNull() == Routes.RECORD) pop()
    }

    // What to record from once permission comes back: null is the microphone.
    var pendingSource by remember { mutableStateOf<File?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) {
            app.recorder.start(pendingSource)
        } else {
            Toast.makeText(context, "Sotto Recorder needs the microphone to record", Toast.LENGTH_LONG).show()
        }
    }

    fun startRecording(wav: File? = null) {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val allGranted = needed.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        // Ask for notifications alongside the microphone the first time, but never block on them.
        if (micGranted && (allGranted || context.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("asked_notifications", false))) {
            app.recorder.start(wav)
        } else {
            pendingSource = wav
            context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit { putBoolean("asked_notifications", true) }
            permissions.launch(needed.toTypedArray())
        }
    }

    LaunchedEffect(recordRequested) {
        if (recordRequested) {
            if (session == null) startRecording()
            onRecordConsumed()
        }
    }

    LaunchedEffect(debugWav) {
        if (debugWav != null && session == null) {
            startRecording(debugWav)
            onDebugWavConsumed()
        }
    }

    BackHandler(enabled = stack.size > 1) { pop() }

    AnimatedContent(
        targetState = stack.last(),
        transitionSpec = {
            val sheet = targetState == Routes.RECORD || initialState == Routes.RECORD
            when {
                // The record screen rises from the button and drops back into it.
                sheet && forward -> (slideInVertically(spring(0.85f, 380f)) { it / 3 } + fadeIn()) togetherWith fadeOut()
                sheet -> fadeIn() togetherWith (slideOutVertically { it / 3 } + fadeOut())
                forward -> (fadeIn() + scaleIn(initialScale = 0.94f)) togetherWith fadeOut()
                else -> fadeIn() togetherWith (fadeOut() + scaleOut(targetScale = 0.94f))
            }
        },
        modifier = Modifier,
        label = "screen",
    ) { route ->
        val parts = route.split("/")
        when (parts[0]) {
            Routes.RECORD -> session?.let { active ->
                RecordScreen(app, active, onMinimize = ::pop)
            }
            "player" -> PlayerScreen(
                app = app,
                id = parts[1],
                autoplay = parts.getOrNull(2) == "true",
                find = Uri.decode(parts.getOrNull(3).orEmpty()),
                onBack = ::pop,
                onCrop = { push(Routes.crop(parts[1])) },
                onSettings = { push(Routes.SETTINGS) },
            )
            "crop" -> CropScreen(app, parts[1]) { copy ->
                pop()
                // Land on the new copy, with the original one step back.
                if (copy != null) push(Routes.player(copy))
            }
            Routes.SETUP -> SetupScreen(app, onDone = ::pop)
            Routes.SETTINGS -> SettingsScreen(app, onDone = ::pop, onLicenses = { push(Routes.LICENSES) }, onWhatsNew = notes?.let { { showNotes = true } })
            Routes.LICENSES -> LicensesScreen(onBack = ::pop)
            else -> HomeScreen(
                app = app,
                onOpen = { id, autoplay, find -> push(Routes.player(id, autoplay, find)) },
                onRecord = { startRecording() },
                onReturnToRecording = { push(Routes.RECORD) },
                onSettings = { push(Routes.SETTINGS) },
                onShare = { exporting = it },
                onCrop = { push(Routes.crop(it.id)) },
            )
        }
    }

    exporting?.let { ShareSheet(app, it, onDismiss = { exporting = null }) }
    if (showNotes) notes?.let { WhatsNewSheet(it, onDismiss = { showNotes = false }) }
}
