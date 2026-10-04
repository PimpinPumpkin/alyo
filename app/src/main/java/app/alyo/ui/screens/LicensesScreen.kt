package app.alyo.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.alyo.BuildConfig

private class Credit(val name: String, val by: String, val license: String, val use: String, val url: String)

/**
 * Everything Alyo is built from. The speech models are CC BY 4.0, which makes naming their
 * authors a condition of using them, so this page is not optional.
 */
private val CREDITS = listOf(
    Credit("Parakeet TDT 0.6B v2, v3 and 110M", "NVIDIA", "CC BY 4.0", "The speech models that write the finished transcript", "https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3"),
    Credit("Supertonic 3 voices", "Supertone Inc.", "OpenRAIL-M", "The more natural voices that read aloud", "https://huggingface.co/Supertone/supertonic-3"),
    Credit("Place-name pronunciations", "Wikipedia and its contributors", "CC BY-SA 4.0", "How the voices say about 3,500 towns and cities", "https://en.wikipedia.org/wiki/Help:IPA/English"),
    Credit("Piper voices", "The Piper and Open Home Foundation contributors, and each voice's speaker", "Varies by voice, shown where voices are chosen", "The voices that read aloud", "https://huggingface.co/rhasspy/piper-voices"),
    Credit("eSpeak NG", "The eSpeak NG authors", "GPL 3.0", "Turns spelling into sounds for the voices", "https://github.com/espeak-ng/espeak-ng"),
    Credit("Kroko ASR", "Banafo", "CC BY-SA 4.0", "The live speech models for German, French and Spanish", "https://huggingface.co/Banafo/Kroko-ASR"),
    Credit("FastConformer streaming transducer", "NVIDIA", "CC BY 4.0", "The speech model behind the live text", "https://huggingface.co/nvidia/stt_en_fastconformer_hybrid_large_streaming_multi"),
    Credit("CED tiny", "Heinrich Dinkel and Xiaomi", "Apache 2.0", "Tells music, applause and laughter from speech", "https://github.com/RicherMans/CED"),
    Credit("pyannote segmentation 3.0", "Hervé Bredin and CNRS", "MIT", "Finds where the speaker changes", "https://huggingface.co/pyannote/segmentation-3.0"),
    Credit("TitaNet Large", "NVIDIA", "CC BY 4.0", "Tells one voice from another, for speaker labels", "https://huggingface.co/nvidia/speakerverification_en_titanet_large"),
    Credit("sherpa-onnx", "Xiaomi Corporation and the k2-fsa contributors", "Apache 2.0", "Runs the speech models, and converted them to the form used here", "https://github.com/k2-fsa/sherpa-onnx"),
    Credit("Qwen2.5 0.5B and 1.5B Instruct", "Alibaba Cloud", "Apache 2.0", "The language models that write summaries", "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct"),
    Credit("llama.cpp", "The ggml authors", "MIT", "Runs the summary model", "https://github.com/ggml-org/llama.cpp"),
    Credit("ONNX Runtime", "Microsoft", "MIT", "The engine underneath sherpa-onnx", "https://github.com/microsoft/onnxruntime"),
    Credit("Google Sans Flex", "Google", "SIL Open Font License 1.1", "The typeface", "https://github.com/google/fonts/tree/main/ofl/googlesansflex"),
    Credit("Jetpack Compose, Material 3, AndroidX", "The Android Open Source Project", "Apache 2.0", "The interface", "https://developer.android.com/jetpack/androidx"),
    Credit("Media3 ExoPlayer", "The Android Open Source Project", "Apache 2.0", "Playback and lock screen controls", "https://github.com/androidx/media"),
    Credit("Material Icons", "Google", "Apache 2.0", "Icons", "https://fonts.google.com/icons"),
    Credit("Kotlin, kotlinx.coroutines, kotlinx.serialization", "JetBrains", "Apache 2.0", "The language and its libraries", "https://kotlinlang.org"),
    Credit("Apache Commons Compress", "The Apache Software Foundation", "Apache 2.0", "Unpacks downloaded speech models", "https://commons.apache.org/proper/commons-compress/"),
)

@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().background(colors.surface).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Open-source licenses", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            item {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) {
                    Text("Alyo ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Free software under the GNU General Public License, version 3. You may use, study, share and change it; " +
                            "changed versions you pass on must stay under the same license. It comes with no warranty.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(20.dp))
                    Text("Built with", style = MaterialTheme.typography.titleSmall, color = colors.primary)
                }
            }
            items(CREDITS) { credit ->
                Surface(
                    onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(credit.url))) },
                    shape = RoundedCornerShape(24.dp),
                    color = colors.surfaceContainer,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                        Text(credit.name, style = MaterialTheme.typography.titleMedium)
                        Text("${credit.by}  ·  ${credit.license}", style = MaterialTheme.typography.bodyMedium, color = colors.primary)
                        Text(credit.use, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                }
            }
            item {
                Text(
                    "Alyo is not affiliated with or endorsed by Google or NVIDIA.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                )
            }
        }
    }
}
