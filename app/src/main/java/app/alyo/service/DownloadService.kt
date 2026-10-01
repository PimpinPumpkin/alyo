package app.alyo.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.alyo.MainActivity
import app.alyo.R
import app.alyo.AlyoApp
import app.alyo.asr.InstallState
import app.alyo.ui.formatBytes
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Keeps a model download alive when the app is left or the screen sleeps. The download itself
 * lives in ModelManager; this only holds the phone awake, keeps Wi-Fi up, and shows progress. It
 * stops itself when there is nothing left to fetch.
 */
class DownloadService : LifecycleService() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var started = false

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (started) return START_NOT_STICKY
        started = true
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Model downloads", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, build("Working", null, null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) {
            // Started from the background (a download resumed at launch by something other than
            // the user opening the app). The download still runs; it just is not protected.
            Log.w(TAG, "Could not go foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "alyo:download").apply { acquire(MAX_MS) }
        @Suppress("DEPRECATION") // The replacement constant needs API 29's low-latency mode, which is the wrong trade here.
        wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "alyo:download").apply { acquire() }

        val app = application as AlyoApp
        val work = app.models.states.map { states -> states.values.filterIsInstance<InstallState.Downloading>() }
        lifecycleScope.launch {
            work.collectLatest { downloads ->
                if (downloads.isEmpty()) {
                    release()
                    ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        lifecycleScope.launch {
            // Once a second is plenty for a progress bar, and the system throttles faster updates anyway.
            work.sample(1000).collectLatest { downloads ->
                if (downloads.isEmpty()) return@collectLatest
                val done = downloads.sumOf { it.doneBytes }
                val total = downloads.sumOf { it.totalBytes }
                val notification = build("Downloading the speech model", "${formatBytes(done)} of ${formatBytes(total)}", if (total > 0) done.toFloat() / total else null)
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
            }
        }
        return START_NOT_STICKY
    }

    private fun release() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    /** @param fraction 0 to 1, or null for work whose end cannot be measured */
    private fun build(title: String, text: String?, fraction: Float?): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_alyo)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(1000, ((fraction ?: 0f) * 1000).toInt(), fraction == null)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "DownloadService"
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 2
        private const val MAX_MS = 3 * 60 * 60 * 1000L

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }
                .onFailure { Log.w(TAG, "Could not start", it) }
        }
    }
}
