package app.alyo.data

import android.app.ActivityManager
import android.content.Context
import androidx.core.content.edit
import app.alyo.asr.LanguagePack
import app.alyo.asr.ModelCatalog
import app.alyo.asr.ModelManager
import app.alyo.asr.ModelSpec
import app.alyo.asr.Quality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    val pack: LanguagePack get() = ModelCatalog.english

    private val _quality = MutableStateFlow(prefs.getString(QUALITY, null) ?: "light")
    val quality: StateFlow<String> = _quality
    val chosenQuality: Quality get() = pack.qualities.firstOrNull { it.id == _quality.value } ?: pack.qualities.first()

    fun setQuality(id: String) {
        prefs.edit { putString(QUALITY, id) }
        _quality.value = id
    }

    /** What dictating needs with the current choices: the live model and the second pass. */
    val neededModels: List<ModelSpec> get() = listOfNotNull(pack.live, chosenQuality.refiner)

    /**
     * The second-pass model to use: the chosen quality's if it is installed, otherwise any other
     * that is. Null when nothing is installed, in which case the live text stands.
     */
    fun refiner(models: ModelManager): ModelSpec? {
        val wanted = chosenQuality.refiner
        return wanted?.let(models::usable) ?: pack.qualities.firstNotNullOfOrNull { it.refiner?.let(models::usable) }
    }

    private val _stopAfterSeconds = MutableStateFlow(prefs.getInt(STOP_AFTER, 3))
    /** Stop listening after this many seconds of silence. Zero keeps listening until Done is tapped. */
    val stopAfterSeconds: StateFlow<Int> = _stopAfterSeconds

    fun setStopAfterSeconds(seconds: Int) {
        prefs.edit { putInt(STOP_AFTER, seconds) }
        _stopAfterSeconds.value = seconds
    }

    private val _spokenPunctuation = MutableStateFlow(prefs.getBoolean(SPOKEN_PUNCTUATION, true))
    /** Turn "comma", "period" and "new line" said out loud into the marks. */
    val spokenPunctuation: StateFlow<Boolean> = _spokenPunctuation

    fun setSpokenPunctuation(on: Boolean) {
        prefs.edit { putBoolean(SPOKEN_PUNCTUATION, on) }
        _spokenPunctuation.value = on
    }

    private val _keepWarmMinutes = MutableStateFlow(prefs.getInt(KEEP_WARM, 5))
    /** How long the speech models stay in memory after a dictation, so the next one starts at once. */
    val keepWarmMinutes: StateFlow<Int> = _keepWarmMinutes

    fun setKeepWarmMinutes(minutes: Int) {
        prefs.edit { putInt(KEEP_WARM, minutes) }
        _keepWarmMinutes.value = minutes
    }

    private val _theme = MutableStateFlow(prefs.getString(THEME, THEME_SYSTEM)!!)
    /** [THEME_SYSTEM] follows the phone; [THEME_LIGHT] and [THEME_DARK] hold the app to one look. */
    val theme: StateFlow<String> = _theme

    fun setTheme(theme: String) {
        prefs.edit { putString(THEME, theme) }
        _theme.value = theme
    }

    companion object {
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        val STOP_AFTER_CHOICES = listOf(2, 3, 5, 10, 0)
        val KEEP_WARM_CHOICES = listOf(0, 1, 5, 15)

        private const val QUALITY = "quality"
        private const val STOP_AFTER = "stop_after_seconds"
        private const val SPOKEN_PUNCTUATION = "spoken_punctuation"
        private const val KEEP_WARM = "keep_warm_minutes"
        private const val THEME = "theme"

        /** Whether this phone has the memory for the large second-pass model. */
        fun canRunBest(context: Context): Boolean {
            val info = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
            return info.totalMem >= 5_500_000_000L
        }
    }
}
