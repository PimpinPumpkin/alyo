package app.sotto.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import androidx.core.content.edit
import app.sotto.asr.LanguagePack
import app.sotto.asr.ModelCatalog
import app.sotto.asr.Quality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _language = MutableStateFlow(prefs.getString(LANGUAGE, "en")!!)
    val language: StateFlow<String> = _language

    private val _quality = MutableStateFlow(prefs.getString(QUALITY, null) ?: recommendedQuality())
    val quality: StateFlow<String> = _quality

    val pack: LanguagePack get() = ModelCatalog.language(_language.value)
    val chosenQuality: Quality get() = pack.qualities.firstOrNull { it.id == _quality.value } ?: pack.qualities.first()

    fun setQuality(id: String) {
        prefs.edit { putString(QUALITY, id) }
        _quality.value = id
    }

    private val _autoTitle = MutableStateFlow(prefs.getBoolean(AUTO_TITLE, true))
    /** Name new recordings after what was talked about, once the transcript is in. */
    val autoTitle: StateFlow<Boolean> = _autoTitle

    fun setAutoTitle(on: Boolean) {
        prefs.edit { putBoolean(AUTO_TITLE, on) }
        _autoTitle.value = on
    }

    private val _summarizer = MutableStateFlow(prefs.getString(SUMMARIZER, null))
    /** Id of the model the user picked to write summaries, or null if they have not picked: then whichever is installed is used. */
    val summarizer: StateFlow<String?> = _summarizer

    fun setSummarizer(id: String) {
        prefs.edit { putString(SUMMARIZER, id) }
        _summarizer.value = id
    }

    private val _speakerLabels = MutableStateFlow(prefs.getBoolean(SPEAKERS, true))
    /** Tell voices apart and label who is speaking. Needs a small extra model. */
    val speakerLabels: StateFlow<Boolean> = _speakerLabels

    fun setSpeakerLabels(on: Boolean) {
        prefs.edit { putBoolean(SPEAKERS, on) }
        _speakerLabels.value = on
    }

    private val _microphone = MutableStateFlow(prefs.getString(MICROPHONE, MIC_AUTO)!!)
    /**
     * Which microphone to record from: [MIC_AUTO], or a key naming one input (see [micKey]). A
     * remembered microphone that is not connected falls back to automatic for that recording.
     */
    val microphone: StateFlow<String> = _microphone

    fun setMicrophone(choice: String) {
        prefs.edit { putString(MICROPHONE, choice) }
        _microphone.value = choice
    }

    private val _theme = MutableStateFlow(prefs.getString(THEME, THEME_SYSTEM)!!)
    /** [THEME_SYSTEM] follows the phone; [THEME_LIGHT] and [THEME_DARK] hold the app to one look. */
    val theme: StateFlow<String> = _theme

    fun setTheme(theme: String) {
        prefs.edit { putString(THEME, theme) }
        _theme.value = theme
    }

    private val _shareWithPicker = MutableStateFlow(prefs.getBoolean(PICKER, true))
    /** Show the library in the system file picker, so other apps can be handed a recording the user picks. */
    val shareWithPicker: StateFlow<Boolean> = _shareWithPicker

    fun setShareWithPicker(on: Boolean) {
        prefs.edit { putBoolean(PICKER, on) }
        _shareWithPicker.value = on
    }

    private val _searches = MutableStateFlow(prefs.getString(SEARCHES, "")!!.split('\n').filter { it.isNotBlank() })
    /** Recent searches, newest first. */
    val searches: StateFlow<List<String>> = _searches

    fun rememberSearch(query: String) {
        val clean = query.trim()
        if (clean.length < 2) return
        saveSearches((listOf(clean) + _searches.value.filterNot { it.equals(clean, ignoreCase = true) }).take(MAX_SEARCHES))
    }

    fun forgetSearch(query: String) = saveSearches(_searches.value - query)

    private fun saveSearches(list: List<String>) {
        prefs.edit { putString(SEARCHES, list.joinToString("\n")) }
        _searches.value = list
    }

    private val packages = context.packageManager
    private val named = ComponentName(context, "app.sotto.LauncherNamed")
    private val plain = ComponentName(context, "app.sotto.LauncherPlain")

    private val _plainName = MutableStateFlow(packages.getComponentEnabledSetting(plain) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
    /** Whether the launcher shows the app as plain "Recorder" instead of "Sotto". */
    val plainName: StateFlow<Boolean> = _plainName

    /** Swaps which launcher entry exists. Launchers can take a few seconds to notice. */
    fun setPlainName(on: Boolean) {
        fun set(component: ComponentName, enabled: Boolean) = packages.setComponentEnabledSetting(
            component,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        // Enable the new one before disabling the old, so there is never a moment with no icon.
        set(if (on) plain else named, true)
        set(if (on) named else plain, false)
        _plainName.value = on
    }

    /** The best model is the default everywhere. A phone short on memory can pick the lighter one. */
    fun recommendedQuality(): String = "best"

    companion object {
        /** Whatever Android routes to: a wired or USB headset microphone if one is plugged in, else the phone's. */
        const val MIC_AUTO = "auto"

        /** A stable name for an input. Device ids change between connections; its kind and product name do not. */
        fun micKey(device: AudioDeviceInfo): String = "${device.type}:${device.productName}"

        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        private const val THEME = "theme"
        private const val PICKER = "share_with_picker"
        private const val SEARCHES = "searches"
        private const val MAX_SEARCHES = 8

        private const val SPEAKERS = "speaker_labels"
        private const val MICROPHONE = "microphone"
        private const val LANGUAGE = "language"
        private const val QUALITY = "quality"
        private const val AUTO_TITLE = "auto_title"
        private const val SUMMARIZER = "summarizer"
    }
}
