package app.alyo.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Plays the short clip the app carries for each voice, so a voice can be heard before it is
 * downloaded. The clips are made by scripts/voice-samples.py and named after the voice's model id.
 */
class VoicePreview(private val context: Context) {
    private var player: MediaPlayer? = null
    private val main = Handler(Looper.getMainLooper())
    private val _playing = MutableStateFlow<String?>(null)
    /** The id of the voice being played, or null. */
    val playing: StateFlow<String?> = _playing

    /** Plays the clip for [voiceId], or stops it if it is the one playing. */
    fun toggle(voiceId: String) {
        val wasPlaying = _playing.value
        stop()
        if (wasPlaying == voiceId) return
        val clip = try {
            context.assets.openFd("voices/$voiceId.ogg")
        } catch (e: java.io.IOException) {
            return
        }
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            clip.use { setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            setOnCompletionListener { stop() }
            prepare()
            start()
        }
        _playing.value = voiceId
        // Some phones never report the end of a clip this short, so the clock ends it as well.
        val started = player
        main.postDelayed({ if (player === started) stop() }, (started?.duration ?: 0) + END_SLACK_MS)
    }

    fun stop() {
        player?.release()
        player = null
        _playing.value = null
    }

    private companion object {
        const val END_SLACK_MS = 250L
    }
}
