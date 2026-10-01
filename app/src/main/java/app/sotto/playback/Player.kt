package app.sotto.playback

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player.Listener
import androidx.media3.common.Player.STATE_ENDED
import androidx.media3.common.Player.STATE_READY
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

/**
 * Plays one recording. State is exposed as Compose state; everything must be called on the main thread.
 *
 * [positionMs] is deliberately not observable state: it changes every frame, so the UI samples it
 * from a frame loop instead of being recomposed by it.
 */
class Player(context: Context) {
    private val exo = ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
            /* handleAudioFocus = */ true,
        )
        setHandleAudioBecomingNoisy(true)
    }

    var isPlaying by mutableStateOf(false)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var speed by mutableFloatStateOf(1f)
        private set

    val positionMs: Long get() = exo.currentPosition.coerceIn(0, maxOf(durationMs, 0))

    /** Bumped on every jump, so the UI knows to re-read [positionMs] even while paused. */
    var seekCount by mutableIntStateOf(0)
        private set

    init {
        exo.addListener(object : Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == STATE_READY) durationMs = exo.duration.coerceAtLeast(0)
                if (state == STATE_ENDED) {
                    exo.pause()
                    seekTo(0)
                }
            }
        })
    }

    fun load(file: File, positionMs: Long = 0) {
        exo.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)), positionMs)
        exo.prepare()
    }

    fun play() = exo.play()
    fun pause() = exo.pause()
    fun toggle() = if (exo.isPlaying) exo.pause() else exo.play()
    fun seekTo(ms: Long) {
        exo.seekTo(ms.coerceIn(0, maxOf(durationMs, 0)))
        seekCount++
    }
    fun seekBy(deltaMs: Long) = seekTo(exo.currentPosition + deltaMs)

    fun changeSpeed(value: Float) {
        speed = value
        exo.playbackParameters = PlaybackParameters(value)
    }

    fun release() = exo.release()
}
