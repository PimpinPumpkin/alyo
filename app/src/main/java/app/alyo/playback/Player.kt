package app.alyo.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import app.alyo.MainActivity
import java.io.File
import kotlin.math.abs

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
            .setId("alyo-" + System.nanoTime())
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

    // ExoPlayer's reported position is not a smooth clock for audio: it can sit still for a
    // quarter of a second and then jump, and when it does move it lands a few milliseconds off
    // where steady time would put it. Drawn directly, a playhead stutters. So the UI is given a
    // clock of its own that runs at the playback speed and is only nudged toward what the player
    // reports, a twelfth of the difference at a time. A large difference means a real jump (a seek,
    // a stall), and then it snaps.
    private var anchorMs = 0L
    private var anchorAt = 0L
    private var lastReported = -1L

    val positionMs: Long get() = positionAt(System.nanoTime())

    /**
     * The position as of [frameNanos], the timestamp of the frame about to be drawn. Asking "where
     * will playback be when this frame is shown" rather than "where is it now" matters: frame
     * callbacks arrive a variable few milliseconds after their frame's time, and a clock read at
     * the moment of the call carries that wobble straight into the playhead.
     */
    fun positionAt(frameNanos: Long): Long {
        val reported = exo.currentPosition
        val now = frameNanos / 1_000_000
        if (!exo.isPlaying) {
            anchorMs = reported
            anchorAt = now
            lastReported = reported
            return reported.coerceIn(0, maxOf(durationMs, 0))
        }
        var smooth = anchorMs + ((now - anchorAt) * speed).toLong()
        // Only a fresh report says anything; a repeated one is the player not having looked yet.
        if (reported != lastReported) {
            lastReported = reported
            val error = reported - smooth
            smooth = if (abs(error) > SNAP_MS) reported else smooth + error / 12
        }
        anchorMs = smooth
        anchorAt = now
        return smooth.coerceIn(0, maxOf(durationMs, 0))
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
    fun load(file: File, title: String = "") = load(Uri.fromFile(file), title)

    /** @param uri a file, or a document in the recordings folder */
    fun load(uri: Uri, title: String = "") {
        val item = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist("Alyo").build())
            .build()
        exo.setMediaItem(item)
        exo.prepare()
    }

    fun play() = exo.play()
    fun pause() = exo.pause()
    fun toggle() = if (exo.isPlaying) exo.pause() else exo.play()
    fun seekTo(ms: Long) {
        val target = ms.coerceIn(0, maxOf(durationMs, 0))
        exo.seekTo(target)
        anchorMs = target
        anchorAt = System.nanoTime() / 1_000_000
        seekCount++
    }
    fun seekBy(deltaMs: Long) = seekTo(positionMs + deltaMs)

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
        const val SNAP_MS = 400L
    }
}
