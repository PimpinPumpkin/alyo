package app.alyo.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.alyo.MainActivity
import app.alyo.R
import app.alyo.AlyoApp
import app.alyo.ui.formatClock
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps recording (and the transcript's second pass) alive when the app is not on screen, and
 * shows the ongoing notification with pause and stop. All the actual work is in [RecorderController].
 */
class RecordingService : LifecycleService() {
    private val recorder get() = (application as AlyoApp).recorder
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PAUSE -> recorder.setPaused(true)
            ACTION_RESUME -> recorder.setPaused(false)
            ACTION_STOP -> lifecycleScope.launch { recorder.stop() }
        }
        if (!started) {
            started = true
            createChannel()
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, build("Recording", null, paused = false, recording = true), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } catch (e: SecurityException) {
                // The system refuses a microphone service without the permission, or when started
                // from the background. The UI asks first, so this is a last line of defense.
                Log.e(TAG, "Not allowed to record", e)
                lifecycleScope.launch { recorder.discard() }
                stopSelf()
                return START_NOT_STICKY
            }
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "alyo:recording")
                .apply { acquire(MAX_WAKE_MS) }
            observe()
        }
        return START_NOT_STICKY
    }

    private fun observe() {
        lifecycleScope.launch {
            recorder.busy.collectLatest { busy -> if (!busy) shutDown() }
        }
        lifecycleScope.launch {
            recorder.session.collectLatest { session ->
                if (session == null) {
                    notify(build("Finishing transcript", "Your recording is saved", paused = false, recording = false))
                    return@collectLatest
                }
                // Whole seconds only: the notification does not need 25 updates a second.
                val seconds = session.elapsedMs.map { it / 1000 }.distinctUntilChanged()
                combine(seconds, session.paused, session.title) { s, paused, title -> Triple(s, paused, title) }.collectLatest { (s, paused, title) ->
                    notify(build(if (paused) "Paused" else "Recording", "$title  ·  ${formatClock(s * 1000)}", paused, recording = true))
                }
            }
        }
    }

    private fun shutDown() {
        wakeLock?.takeIf { it.isHeld }?.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun notify(notification: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun build(title: String, text: String?, paused: Boolean, recording: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (recording) {
            builder.color = 0xFFEA4335.toInt()
            builder.addAction(0, if (paused) "Resume" else "Pause", action(if (paused) ACTION_RESUME else ACTION_PAUSE))
            builder.addAction(0, "Stop", action(ACTION_STOP))
        }
        return builder.build()
    }

    private fun action(name: String): PendingIntent = PendingIntent.getService(
        this, name.hashCode(),
        Intent(this, RecordingService::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while Alyo is recording"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private companion object {
        const val TAG = "RecordingService"
        const val CHANNEL = "recording"
        const val NOTIFICATION_ID = 1
        const val ACTION_PAUSE = "app.alyo.PAUSE"
        const val ACTION_RESUME = "app.alyo.RESUME"
        const val ACTION_STOP = "app.alyo.STOP"
        const val MAX_WAKE_MS = 12 * 60 * 60 * 1000L
    }
}
