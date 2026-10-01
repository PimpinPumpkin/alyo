package app.alyo.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings as SystemSettings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.alyo.AlyoApp
import app.alyo.asr.InstallState
import app.alyo.asr.ModelSpec
import app.alyo.asr.works
import app.alyo.ui.formatBytes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where the needed models stand, taken together. */
class ModelProgress(val ready: Boolean, val downloading: Boolean, val doneBytes: Long, val totalBytes: Long, val failure: String?)

fun modelProgress(needed: List<ModelSpec>, states: Map<String, InstallState>): ModelProgress {
    var done = 0L
    var total = 0L
    var downloading = false
    var failure: String? = null
    for (spec in needed) {
        total += spec.source.downloadBytes
        when (val state = states[spec.id]) {
            is InstallState.Downloading -> {
                downloading = true
                done += state.doneBytes
            }
            is InstallState.Failed -> failure = state.message
            InstallState.Installed -> done += spec.source.downloadBytes
            else -> Unit
        }
    }
    return ModelProgress(needed.all { states[it.id].works }, downloading, done, total, failure)
}

private fun keyboardEnabled(context: Context): Boolean =
    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).enabledInputMethodList.any { it.packageName == context.packageName }

/** The app's one real screen: three steps to get going, then somewhere to try it. */
@Composable
fun HomeScreen(app: AlyoApp, onSettings: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val states by app.models.states.collectAsState()
    val quality by app.settings.quality.collectAsState()
    val progress = remember(states, quality) { modelProgress(app.settings.neededModels, states) }

    var microphone by remember { mutableStateOf(app.hasMicrophone) }
    var keyboard by remember { mutableStateOf(keyboardEnabled(context)) }
    // Both are changed outside the app (a permission sheet, the system's keyboard list), so look again on return.
    LifecycleResumeEffect(Unit) {
        microphone = app.hasMicrophone
        keyboard = keyboardEnabled(context)
        onPauseOrDispose { }
    }
    val askMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { microphone = it }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val ready = progress.ready && microphone && keyboard

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Alyo", style = MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings") }
        }
        Text(
            if (ready) "Ready. Tap the microphone key on your keyboard and start talking." else "Voice typing that keeps up with you. Three steps and it is yours.",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 20.dp),
        )

        AnimatedVisibility(ready) { TryIt() }

        Step(
            number = 1,
            title = "Speech model",
            body = when {
                progress.ready -> "On this phone. Everything you say stays here."
                progress.downloading -> "${formatBytes(progress.doneBytes)} of ${formatBytes(progress.totalBytes)}"
                progress.failure != null -> progress.failure
                else -> "A ${formatBytes(progress.totalBytes)} download, once. After that Alyo never goes online."
            },
            done = progress.ready,
            progress = if (progress.downloading && progress.totalBytes > 0) progress.doneBytes.toFloat() / progress.totalBytes else null,
        ) {
            if (progress.downloading) {
                FilledTonalButton(onClick = { app.settings.neededModels.forEach(app.models::cancel) }) { Text("Pause") }
            } else {
                Button(onClick = {
                    if (android.os.Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    app.settings.neededModels.forEach(app.models::install)
                }) { Text(if (progress.failure != null || progress.doneBytes > 0) "Resume" else "Download") }
            }
        }
        Step(
            number = 2,
            title = "Microphone",
            body = if (microphone) "Allowed." else "Alyo listens only while its panel is open.",
            done = microphone,
        ) {
            Button(onClick = { askMicrophone.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Allow") }
        }
        Step(
            number = 3,
            title = "Keyboard",
            body = if (keyboard) "Turned on. Your keyboard's microphone key now opens Alyo." else "Turn on Alyo voice typing in the phone's keyboard list. Your own keyboard stays as it is.",
            done = keyboard,
        ) {
            Button(onClick = { context.startActivity(Intent(SystemSettings.ACTION_INPUT_METHOD_SETTINGS)) }) { Text("Open keyboard list") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Step(number: Int, title: String, body: String, done: Boolean, progress: Float? = null, action: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceBright, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).background(if (done) colors.primary else colors.secondaryContainer, if (done) MaterialShapes.Cookie9Sided.toShape() else CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (done) Icon(Icons.Rounded.Check, "Done", tint = colors.onPrimary, modifier = Modifier.size(22.dp))
                    else Text("$number", style = MaterialTheme.typography.titleMedium, color = colors.onSecondaryContainer)
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
            if (progress != null) {
                Spacer(Modifier.height(16.dp))
                LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            if (!done) {
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { action() }
            }
        }
    }
}

/** A text box to dictate into, so the first try does not have to happen in a real conversation. */
@Composable
private fun TryIt() {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    Surface(color = colors.surfaceBright, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text("Try it", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Say something") },
                minLines = 4,
                shape = RoundedCornerShape(20.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = colors.surfaceContainerHigh,
                    unfocusedContainerColor = colors.surfaceContainerHigh,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "No microphone key on your keyboard? Switch to Alyo by hand.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                FilledTonalButton(onClick = {
                    focus.requestFocus()
                    keyboard?.show()
                    scope.launch {
                        // The system only lists voice input in the picker once a keyboard is up.
                        delay(350)
                        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
                    }
                }) {
                    Icon(Icons.Rounded.Mic, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Switch")
                }
            }
        }
    }
}
