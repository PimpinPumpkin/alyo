package app.alyo.ime

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.alyo.AlyoApp
import app.alyo.asr.AsrEngine
import app.alyo.MainActivity
import app.alyo.data.Settings
import app.alyo.dictation.Dictation
import app.alyo.text.Composer
import app.alyo.text.Editor
import app.alyo.ui.theme.AlyoTheme

/**
 * Alyo as a keyboard: a panel that takes the keyboard's place, listens, and types what it hears
 * into whatever field has focus. It is voice only. The microphone key on an ordinary keyboard
 * switches here, and Done (or a few seconds of silence) switches back.
 */
class AlyoIme : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    private val main = Handler(Looper.getMainLooper())
    private val app get() = application as AlyoApp

    private var dictation: Dictation? = null
    private var composer: Composer? = null
    private var leaving = false

    private val phase = mutableStateOf(PanelPhase.LISTENING)
    private val problem = mutableStateOf<String?>(null)
    private val canUndo = mutableStateOf(false)

    private val editor = object : Editor {
        override fun textBefore(n: Int): String = if (n <= 0) "" else currentInputConnection?.getTextBeforeCursor(n, 0)?.toString().orEmpty()

        override fun setComposing(text: String) {
            val connection = currentInputConnection ?: return
            // Committing nothing over the underlined run is what removes it.
            if (text.isEmpty()) connection.commitText("", 1) else connection.setComposingText(text, 1)
        }

        override fun insert(text: String) {
            currentInputConnection?.commitText(text, 1)
        }

        override fun deleteBefore(n: Int) {
            if (n > 0) currentInputConnection?.deleteSurroundingText(n, 0)
        }

        override fun batch(edits: () -> Unit) {
            val connection = currentInputConnection
            connection?.beginBatchEdit()
            try {
                edits()
            } finally {
                connection?.endBatchEdit()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // The earliest sign that someone is about to dictate: start loading before the panel is even drawn.
        (application as AlyoApp).preloadSpeech(AsrEngine.DICTATION_PAUSE)
        saved.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onCreateInputView(): View {
        window.window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(this)
            decor.setViewTreeSavedStateRegistryOwner(this)
            decor.setViewTreeViewModelStoreOwner(this)
        }
        return ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val choice by app.settings.theme.collectAsState()
                val dark = when (choice) {
                    Settings.THEME_LIGHT -> false
                    Settings.THEME_DARK -> true
                    else -> isSystemInDarkTheme()
                }
                val tall by app.settings.tallPanel.collectAsState()
                AlyoTheme(dark = dark) {
                    DictationPanel(
                        tall = tall,
                        phase = phase.value,
                        problem = problem.value,
                        canUndo = canUndo.value,
                        level = { dictation?.level ?: 0f },
                        onDone = ::done,
                        onUndo = ::undo,
                        onBackspace = ::backspace,
                        onOpenApp = ::openApp,
                    )
                }
            }
        }
    }

    // A voice panel has nothing to show full screen, and is the keyboard even when a hardware one is attached.
    override fun onEvaluateFullscreenMode(): Boolean = false
    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return true
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        if (restarting) {
            composer?.inputRestarted()
            return
        }
        // A different field: what was in flight belonged to the last one.
        if (dictation != null) {
            dictation?.discardTail()
            composer?.reset()
            refresh()
        } else {
            begin()
        }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        // The panel was dismissed. Finish what was said, then hand the keyboard back, so the next
        // field that is tapped gets the ordinary keyboard and not a live microphone.
        if (dictation != null) done() else leave()
    }

    override fun onDestroy() {
        dictation?.cancel()
        dictation = null
        main.removeCallbacksAndMessages(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
        super.onDestroy()
    }

    private fun begin() {
        leaving = false
        problem.value = when {
            !app.hasModel -> "Alyo needs its speech model before it can listen."
            !app.hasMicrophone -> "Alyo needs the microphone permission."
            else -> null
        }
        if (problem.value != null) return
        val composer = Composer(editor, app.settings.spokenPunctuation.value).also { this.composer = it }
        var mine: Dictation? = null
        val listener = object : Dictation.Listener {
            override fun onText(final: Dictation.Sentence?, tail: String) = onMain {
                composer.update(final, tail)
                refresh()
            }

            override fun onState(state: Dictation.State) = onMain {
                when (state) {
                    Dictation.State.LOADING -> phase.value = PanelPhase.WARMING
                    Dictation.State.LISTENING -> phase.value = PanelPhase.LISTENING
                    Dictation.State.FINISHING -> phase.value = PanelPhase.FINISHING
                    Dictation.State.DONE -> {
                        dictation = null
                        if (problem.value == null) leave()
                    }
                }
            }

            override fun onError(message: String) = onMain { problem.value = message }

            /** Runs [block] on the main thread, unless this dictation has been replaced since. */
            private fun onMain(block: () -> Unit) {
                main.post { if (dictation != null && dictation === mine) block() }
            }
        }
        // A model still in memory from the last dictation is ready at once; do not flash the warm-up for it.
        phase.value = if (app.engine.warm) PanelPhase.LISTENING else PanelPhase.WARMING
        // Assigned before start so the listener's first callbacks find it.
        mine = app.newDictation(listener)
        dictation = mine
        mine?.start()
        refresh()
    }

    private fun refresh() {
        canUndo.value = composer?.canUndo == true
    }

    /** A short tick, felt when listening starts and when a button on the panel is used. */
    private fun tick() {
        window.window?.decorView?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        if (dictation != null) tick()
    }

    private fun done() {
        tick()
        val running = dictation
        if (running == null) leave() else running.finish()
    }

    private fun undo() {
        tick()
        if (composer?.undo() == true) dictation?.discardTail()
        refresh()
    }

    private fun backspace() {
        if (composer?.backspace() == true) dictation?.discardTail()
        refresh()
    }

    /** Hands the keyboard back to whichever one was in use before. */
    private fun leave() {
        if (leaving) return
        leaving = true
        if (!switchToPreviousInputMethod()) requestHideSelf(0)
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        leave()
    }
}
