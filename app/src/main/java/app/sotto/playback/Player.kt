package app.sotto.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player.Listener
import androidx.media3.common.Player.STATE_ENDED
import androidx.media3.common.Player.STATE_READY
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.Player.COMMAND_SEEK_BACK
import androidx.media3.common.Player.COMMAND_SEEK_FORWARD
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import app.sotto.MainActivity
import java.io.File

/**
 * Plays one recording. State is exposed as Compose state; everything must be called on the main thread.
 *
 * [positionMs] is deliberately not observable state: it changes every frame, so the UI samples it
 * from a frame loop instead of being recomposed by it.
 *
 * @param background publish a media session, so playback carries on with the app in the
 *   background and can be controlled from the lock screen and notification. Off for previews.
 */
class Player(private val context: Context, background: Boolean = false) {
    private val exo = ExoPlayer.Builder(context)
        .setSeekBackIncrementMs(5_000)
        .setSeekForwardIncrementMs(10_000)
        .build()
        .apply {
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                /* handleAudioFocus = */ true,
            )
            setHandleAudioBecomingNoisy(true)
        }

    private val session: MediaSession? = if (!background) null else {
        // A single recording has no previous or next track. Hiding those commands makes the
        // system controls offer back 5 s and forward 10 s in their place.
        val commands = object : ForwardingPlayer(exo) {
            private val hidden = intArrayOf(
                COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            )
            override fun getAvailableCommands(): androidx.media3.common.Player.Commands = super.getAvailableCommands().buildUpon().removeAll(*hidden).build()
            override fun isCommandAvailable(command: Int): Boolean = command !in hidden && super.isCommandAvailable(command)
        }
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        MediaSession.Builder(context, commands)
            .setId("sotto-" + System.nanoTime())
            .setSessionActivity(open)
            .setMediaButtonPreferences(
                listOf(
                    CommandButton.Builder(CommandButton.ICON_SKIP_BACK_5).setPlayerCommand(COMMAND_SEEK_BACK).setDisplayName("Back 5 seconds").build(),
                    CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_10).setPlayerCommand(COMMAND_SEEK_FORWARD).setDisplayName("Forward 10 seconds").build(),
                ),
            )
            .build()
            .also { PlaybackService.attach(context, it) }
    }

    var isPlaying by mutableStateOf(false)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var speed by mutableFloatStateOf(1f)
        private set

    // ExoPlayer only refreshes its reported position about once a second for audio. Between
    // refreshes the clock is carried forward here, so anything following playback moves smoothly.
    private var reported = 0L
    private var reportedAt = 0L

    val positionMs: Long
        get() {
            val raw = exo.currentPosition
            val now = SystemClock.elapsedRealtime()
            if (raw != reported || !exo.isPlaying) {
                reported = raw
                reportedAt = now
            }
            val carried = if (exo.isPlaying) ((now - reportedAt) * speed).toLong().coerceAtMost(MAX_CARRY_MS) else 0L
            return (raw + carried).coerceIn(0, maxOf(durationMs, 0))
        }

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

    /** @param title shown on the lock screen and in the notification */
    fun load(file: File, title: String = "") {
        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(file))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist("Sotto Recorder").build())
            .build()
        exo.setMediaItem(item)
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

    fun release() {
        session?.let {
            PlaybackService.detach(context, it)
            it.release()
        }
        exo.release()
    }

    private companion object {
        const val MAX_CARRY_MS = 1500L
    }
}
