package app.alyo.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import androidx.core.content.edit
import app.alyo.asr.LanguagePack
import app.alyo.asr.ModelCatalog
import app.alyo.asr.ModelSpec
import app.alyo.asr.Quality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    // Until a language is chosen it follows the phone's, so a German phone starts in German.
    private val _language = MutableStateFlow(prefs.getString(LANGUAGE, null) ?: ModelCatalog.languageFor(java.util.Locale.getDefault()).code)
    val language: StateFlow<String> = _language

    fun setLanguage(code: String) {
        prefs.edit().putString(LANGUAGE, code).apply()
        _language.value = code
    }

    /**
     * The second-pass model to use: the chosen quality's if it is installed, otherwise any other
     * that is. Null when the chosen quality has none by design, or nothing is installed.
     */
    fun refiner(models: app.alyo.asr.ModelManager): ModelSpec? {
        val wanted = chosenQuality.refiner ?: return null
        return models.usable(wanted) ?: pack.qualities.firstNotNullOfOrNull { it.refiner?.let(models::usable) }
    }

    /** What transcribing needs with the current choices: the live model, and the second pass if the quality has one. */
    val neededModels: List<ModelSpec> get() = listOfNotNull(pack.live, chosenQuality.refiner)

    /** Everything to download for the features in use: the speech models, and the voice model if recordings are to be labeled by speaker. */
    val downloads: List<ModelSpec>
        get() = (if (hearing) neededModels else emptyList()) +
            listOfNotNull(ModelCatalog.speakers.takeIf { speakerLabels.value && Feature.RECORDER in features.value }, voiceSpec.takeIf { Feature.READ_ALOUD in features.value })

    /** True when a feature that turns speech into text is on, so the speech models are needed. */
    val hearing: Boolean get() = Feature.RECORDER in features.value || Feature.VOICE_TYPING in features.value

    private val _quality = MutableStateFlow(prefs.getString(QUALITY, null) ?: recommendedQuality(context))
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

    private val _libraryLocation = MutableStateFlow(prefs.getString(LIBRARY, LIBRARY_DEFAULT)!!)
    /**
     * Where recordings are kept: [LIBRARY_DEFAULT] (Documents/Alyo on the phone), [LIBRARY_PRIVATE]
     * (inside the app, gone when it is uninstalled), or the URI of a folder the user picked.
     */
    val libraryLocation: StateFlow<String> = _libraryLocation

    fun setLibraryLocation(location: String) {
        prefs.edit { putString(LIBRARY, location) }
        _libraryLocation.value = location
    }

    private val _stopAfterSeconds = MutableStateFlow(prefs.getInt(STOP_AFTER, 3))
    /** Voice typing stops listening after this many seconds of silence. Zero keeps listening until Done is tapped. */
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

    private val _voice = MutableStateFlow(prefs.getString(VOICE, null) ?: ModelCatalog.voiceFor(java.util.Locale.getDefault()).id)
    /** The voice that reads aloud, by model id. Starts as the first one for the phone's language. */
    val voice: StateFlow<String> = _voice
    val voiceSpec: ModelSpec get() = ModelCatalog.voices.firstOrNull { it.id == _voice.value } ?: ModelCatalog.voices.first()

    fun setVoice(id: String) {
        prefs.edit { putString(VOICE, id) }
        _voice.value = id
    }

    private val _speechRate = MutableStateFlow(prefs.getFloat(SPEECH_RATE, 1f))
    /** How fast Alyo reads, on top of whatever speed the app asking for speech wants. 1 is natural. */
    val speechRate: StateFlow<Float> = _speechRate

    fun setSpeechRate(rate: Float) {
        prefs.edit { putFloat(SPEECH_RATE, rate) }
        _speechRate.value = rate
    }

    private val appContext = context.applicationContext
    private val packages = context.packageManager
    private val packageName = context.packageName

    private val _features = MutableStateFlow(prefs.getStringSet(FEATURES, emptySet())!!.mapNotNull { name -> Feature.entries.firstOrNull { it.name == name } }.toSet())
    /** The parts of the app the user has turned on. Empty until they have chosen. */
    val features: StateFlow<Set<Feature>> = _features

    /** True once the user has been through the first-run choice of features. */
    val onboarded: Boolean get() = prefs.getBoolean(ONBOARDED, false)

    fun setOnboarded() = prefs.edit { putBoolean(ONBOARDED, true) }

    /**
     * Turns a part of the app on or off. Off means gone as far as the phone is concerned: the
     * keyboard leaves the keyboard list, the recorder's icon, tile and widget disappear. Launchers
     * can take a few seconds to notice.
     */
    fun setFeature(feature: Feature, on: Boolean) {
        val now = if (on) _features.value + feature else _features.value - feature
        prefs.edit { putStringSet(FEATURES, now.map { it.name }.toSet()) }
        _features.value = now
        for (component in feature.components) enable(component, on)
        if (feature == Feature.RECORDER) {
            // The icon of its own only makes sense while there is a recorder to open.
            if (!on) setRecorderIcon(false)
            recordShortcut(on)
        }
    }

    private fun enable(component: String, on: Boolean) = packages.setComponentEnabledSetting(
        ComponentName(packageName, "app.alyo$component"),
        if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.DONT_KILL_APP,
    )

    private val _recorderIcon = MutableStateFlow(
        packages.getComponentEnabledSetting(ComponentName(packageName, "app.alyo.RecorderLauncher")) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
    )
    /**
     * Whether the recorder has a home screen icon of its own. Off by default: one icon, which
     * opens the recordings. On, "Recorder" opens the recordings and "Alyo" opens the front page.
     */
    val recorderIcon: StateFlow<Boolean> = _recorderIcon

    fun setRecorderIcon(on: Boolean) {
        enable(".RecorderLauncher", on)
        _recorderIcon.value = on
    }

    /** "New recording" in the menu that a long press on the icon brings up, only while there is a recorder. */
    private fun recordShortcut(on: Boolean) {
        val manager = appContext.getSystemService(android.content.pm.ShortcutManager::class.java) ?: return
        if (!on) {
            manager.removeDynamicShortcuts(listOf("record"))
            return
        }
        val shortcut = android.content.pm.ShortcutInfo.Builder(appContext, "record")
            .setShortLabel(appContext.getString(app.alyo.R.string.shortcut_record))
            .setIcon(android.graphics.drawable.Icon.createWithResource(appContext, app.alyo.R.drawable.ic_shortcut_record))
            .setIntent(android.content.Intent(app.alyo.MainActivity.ACTION_RECORD, null, appContext, app.alyo.MainActivity::class.java))
            .build()
        runCatching { manager.dynamicShortcuts = listOf(shortcut) }
    }

    /**
     * The lighter model is the default: a third of the download, and close enough that most people
     * will never want the other. Someone who already has the large one keeps using it.
     */
    private fun recommendedQuality(context: Context): String =
        if (java.io.File(context.filesDir, "models/en-parakeet-tdt-0.6b-v2").isDirectory) "best" else "light"

    companion object {
        const val LIBRARY_DEFAULT = "default"
        const val LIBRARY_PRIVATE = "private"
        private const val LIBRARY = "library"
        val STOP_AFTER_CHOICES = listOf(2, 3, 5, 10, 0)
        val KEEP_WARM_CHOICES = listOf(0, 1, 5, 15)
        private const val STOP_AFTER = "stop_after_seconds"
        private const val SPOKEN_PUNCTUATION = "spoken_punctuation"
        private const val KEEP_WARM = "keep_warm_minutes"
        val SPEECH_RATES = listOf(0.8f, 1f, 1.2f, 1.5f, 2f)
        private const val VOICE = "voice"
        private const val SPEECH_RATE = "speech_rate"
        private const val FEATURES = "features"
        private const val ONBOARDED = "onboarded"

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

/**
 * A part of the app that can be switched on or off as a whole. [components] are the manifest
 * entries (all disabled until the feature is chosen) that make it visible to the rest of the phone.
 */
enum class Feature(val title: String, val summary: String, val components: List<String>) {
    RECORDER(
        "Recorder",
        "Record and get a transcript as you talk, with speaker labels, search and summaries.",
        listOf(".service.RecordTile", ".service.RecordWidget", ".export.LibraryProvider"),
    ),
    VOICE_TYPING(
        "Voice typing",
        "Dictate into any app. The microphone key on your keyboard opens it.",
        listOf(".ime.AlyoIme", ".recognition.AlyoRecognitionService", ".recognition.RecognizeActivity"),
    ),
    READ_ALOUD(
        "Read aloud",
        "A voice for the phone. Reads text you share or select, and speaks for any app that talks.",
        listOf(".tts.AlyoTtsService", ".tts.CheckVoiceData", ".tts.InstallVoiceData", ".tts.SampleText", ".tts.ReadAloudActivity"),
    ),
}
