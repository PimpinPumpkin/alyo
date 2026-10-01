package app.alyo.playback

import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.alyo.R

/**
 * Keeps playback going with the app in the background and puts play, pause and skip on the lock
 * screen and in the notification shade. It owns nothing itself: [Player] creates the session and
 * hands it over with [attach], and the service exists for as long as that player does.
 */
class PlaybackService : MediaSessionService() {
    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_stat_alyo) })
        instance = this
        current?.let(::addSession)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = current

    /** Swiped out of recents: there is no screen left to come back to, so stop rather than play on unseen. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        current?.player?.pause()
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var current: MediaSession? = null
        @Volatile private var instance: PlaybackService? = null

        fun attach(context: Context, session: MediaSession) {
            current = session
            instance?.addSession(session) ?: context.startService(Intent(context, PlaybackService::class.java))
        }

        fun detach(context: Context, session: MediaSession) {
            if (current !== session) return
            current = null
            instance?.removeSession(session)
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}
