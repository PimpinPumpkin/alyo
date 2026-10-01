package app.sotto.data

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.edit
import app.sotto.asr.LanguagePack
import app.sotto.asr.ModelCatalog
import app.sotto.asr.Quality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val totalRamBytes: Long = ActivityManager.MemoryInfo().also {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
    }.totalMem

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

    private val _summarizer = MutableStateFlow(prefs.getString(SUMMARIZER, null) ?: ModelCatalog.summarizers.first().id)
    /** Id of the model the user wants summaries written by. */
    val summarizer: StateFlow<String> = _summarizer

    fun setSummarizer(id: String) {
        prefs.edit { putString(SUMMARIZER, id) }
        _summarizer.value = id
    }

    private val packages = context.packageManager
    private val named = ComponentName(context, "app.sotto.LauncherNamed")
    private val plain = ComponentName(context, "app.sotto.LauncherPlain")

    private val _plainName = MutableStateFlow(packages.getComponentEnabledSetting(plain) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
    /** Whether the launcher shows the app as plain "Recorder" instead of "Sotto Recorder". */
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

    /** The large model wants about a gigabyte of headroom; phones sold as "6 GB" report roughly 5.5. */
    fun recommendedQuality(): String = if (totalRamBytes >= 5L * 1024 * 1024 * 1024) "best" else "light"

    private companion object {
        const val LANGUAGE = "language"
        const val QUALITY = "quality"
        const val AUTO_TITLE = "auto_title"
        const val SUMMARIZER = "summarizer"
    }
}
