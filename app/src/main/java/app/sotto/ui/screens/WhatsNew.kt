package app.sotto.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.sotto.BuildConfig

/**
 * The changes in this build, as plain lines. Release builds carry them in an asset that CI writes
 * from the commit log, so the app can show them without going online. Dev builds have none.
 */
object WhatsNew {
    private const val SEEN = "whatsnew_seen_code"

    fun notes(context: Context): List<String>? = runCatching {
        context.assets.open("whatsnew.txt").bufferedReader().readLines().map { it.removePrefix("- ").trim() }.filter { it.isNotEmpty() }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** True once per update. Never on a fresh install, when everything is new and none of it is news. */
    fun shouldShow(context: Context): Boolean {
        val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
        val seen = prefs.getInt(SEEN, -1)
        prefs.edit().putInt(SEEN, BuildConfig.VERSION_CODE).apply()
        return seen in 0 until BuildConfig.VERSION_CODE && notes(context) != null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewSheet(notes: List<String>, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("What's new", style = MaterialTheme.typography.headlineSmall)
            Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                for (note in notes) {
                    Text(note, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 12.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Got it") }
        }
    }
}
