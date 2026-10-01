package app.alyo

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.alyo.data.Settings
import app.alyo.ui.screens.HomeScreen
import app.alyo.ui.screens.LicensesScreen
import app.alyo.ui.screens.SettingsScreen
import app.alyo.ui.theme.AlyoTheme

class MainActivity : ComponentActivity() {
    private val debugDark = mutableStateOf<Boolean?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        readDebugIntent(intent)
        val app = application as AlyoApp
        setContent {
            val choice by app.settings.theme.collectAsState()
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
            AlyoTheme(dark = dark) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    var screen by rememberSaveable { mutableStateOf("home") }
                    BackHandler(screen != "home") { screen = if (screen == "licenses") "settings" else "home" }
                    AnimatedContent(screen, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { now ->
                        when (now) {
                            "settings" -> SettingsScreen(app, onBack = { screen = "home" }, onLicenses = { screen = "licenses" })
                            "licenses" -> LicensesScreen(onBack = { screen = "settings" })
                            else -> HomeScreen(app, onSettings = { screen = "settings" })
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readDebugIntent(intent)
    }

    /** Test builds only: `--es theme light|dark` shows the other theme without touching the phone's own setting. */
    private fun readDebugIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG && !BuildConfig.TEST_HOOKS) return
        when (intent?.getStringExtra("theme")) {
            "light" -> debugDark.value = false
            "dark" -> debugDark.value = true
        }
    }
}
