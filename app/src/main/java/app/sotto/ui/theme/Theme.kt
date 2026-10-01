package app.sotto.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import app.sotto.R

/** Colors Material's scheme has no role for: the record red is the same on every wallpaper. */
@Immutable
data class SottoColors(
    val record: Color,
    val onRecord: Color,
    val recordContainer: Color,
    val dark: Boolean = false,
    /**
     * What the waveform is drawn in for each kind of sound. These are fixed rather than taken from
     * the wallpaper's palette, whose colors are chosen to sit well together and so are too alike
     * to tell speech from music at a glance. Blue for talk, amber for music, green for the rest.
     */
    val speech: Color = Color(0xFF4285F4),
    val music: Color = Color(0xFFF29900),
    val otherSound: Color = Color(0xFF34A853),
) {
    fun sound(kind: String): Color = if (kind == "Music") music else otherSound
}

val LocalSottoColors = staticCompositionLocalOf { SottoColors(Color(0xFFEA4335), Color.White, Color(0xFFFCE8E6)) }

// Fallback palette for Android 10/11, where there is no wallpaper-derived scheme: Google blue.
private val LightFallback = lightColorScheme(
    primary = Color(0xFF0B57D0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF041E49),
    secondary = Color(0xFF00639B),
    secondaryContainer = Color(0xFFC2E7FF),
    onSecondaryContainer = Color(0xFF001D35),
    tertiaryContainer = Color(0xFFC4EED0),
    onTertiaryContainer = Color(0xFF072711),
    background = Color(0xFFF8FAFD),
    surface = Color(0xFFF8FAFD),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F6FC),
    surfaceContainer = Color(0xFFF0F4F9),
    surfaceContainerHigh = Color(0xFFE9EEF6),
    surfaceContainerHighest = Color(0xFFDDE3EA),
    onSurface = Color(0xFF1F1F1F),
    onSurfaceVariant = Color(0xFF444746),
    outline = Color(0xFF747775),
    outlineVariant = Color(0xFFC4C7C5),
)

private val DarkFallback = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF0842A0),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFF7FCFFF),
    secondaryContainer = Color(0xFF004A77),
    onSecondaryContainer = Color(0xFFC2E7FF),
    tertiaryContainer = Color(0xFF0F5223),
    onTertiaryContainer = Color(0xFFC4EED0),
    background = Color(0xFF131314),
    surface = Color(0xFF131314),
    surfaceContainerLowest = Color(0xFF0E0E0E),
    surfaceContainerLow = Color(0xFF1B1B1B),
    surfaceContainer = Color(0xFF1E1F20),
    surfaceContainerHigh = Color(0xFF282A2C),
    surfaceContainerHighest = Color(0xFF333537),
    onSurface = Color(0xFFE3E3E3),
    onSurfaceVariant = Color(0xFFC4C7C5),
    outline = Color(0xFF8E918F),
    outlineVariant = Color(0xFF444746),
)

@OptIn(ExperimentalTextApi::class)
private fun flex(weight: Int, round: Float): FontFamily = FontFamily(
    Font(
        R.font.google_sans_flex,
        weight = FontWeight(weight),
        variationSettings = FontVariation.Settings(
            FontVariation.weight(weight),
            // Roundness axis: 100 is the soft, friendly cut Material 3 Expressive uses for big type.
            FontVariation.Setting("ROND", round),
        ),
    ),
)

private val DisplayRegular = flex(400, 100f)
private val DisplayMedium = flex(500, 100f)
private val TextRegular = flex(400, 0f)
private val TextMedium = flex(500, 0f)

private fun typography(): Typography {
    val base = Typography()
    fun TextStyle.display(medium: Boolean = false) = copy(fontFamily = if (medium) DisplayMedium else DisplayRegular, fontWeight = FontWeight(if (medium) 500 else 400))
    fun TextStyle.text(medium: Boolean = false) = copy(fontFamily = if (medium) TextMedium else TextRegular, fontWeight = FontWeight(if (medium) 500 else 400))
    return Typography(
        displayLarge = base.displayLarge.display(),
        displayMedium = base.displayMedium.display(),
        displaySmall = base.displaySmall.display(),
        headlineLarge = base.headlineLarge.display(),
        headlineMedium = base.headlineMedium.display(),
        headlineSmall = base.headlineSmall.display(),
        titleLarge = base.titleLarge.display(),
        titleMedium = base.titleMedium.display(medium = true),
        titleSmall = base.titleSmall.display(medium = true),
        bodyLarge = base.bodyLarge.text(),
        bodyMedium = base.bodyMedium.text(),
        bodySmall = base.bodySmall.text(),
        labelLarge = base.labelLarge.text(medium = true),
        labelMedium = base.labelMedium.text(medium = true),
        labelSmall = base.labelSmall.text(medium = true),
    )
}

/** The big clock on the record and playback screens: thin, round, tabular so digits do not jitter. */
val TimerFont: FontFamily = flex(300, 100f)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SottoTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkFallback
        else -> LightFallback
    }
    // Coral disc, maroon dot: the same in both themes, as on the original.
    val extra = SottoColors(
        record = Color(0xFFEC6A5E), onRecord = Color(0xFF5F1410), recordContainer = Color(0xFFFCE8E6), dark = dark,
        // Lighter tints on a dark background, where the saturated ones glare.
        speech = if (dark) Color(0xFF8AB4F8) else Color(0xFF1A73E8),
        music = if (dark) Color(0xFFFDD663) else Color(0xFFE37400),
        otherSound = if (dark) Color(0xFF81C995) else Color(0xFF188038),
    )
    CompositionLocalProvider(LocalSottoColors provides extra) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            motionScheme = MotionScheme.expressive(),
            typography = typography(),
            content = content,
        )
    }
}

/**
 * The fill for a speaker's name tag. These are fixed and far apart on purpose, rather than drawn
 * from the theme: the point is to tell voices apart at a glance, and theme colors are picked to
 * go together. All are light enough for [onSpeakerTag] to read on, in either theme.
 */
private val speakerTags = listOf(
    Color(0xFF8AB4F8), Color(0xFFFDD663), Color(0xFFF28B82), Color(0xFF81C995),
    Color(0xFFC58AF9), Color(0xFF78D9EC), Color(0xFFFCAD70), Color(0xFFFF8BCB),
)

fun speakerTag(speaker: Int): Color = speakerTags[speaker.mod(speakerTags.size)]

val onSpeakerTag = Color(0xFF202124)
