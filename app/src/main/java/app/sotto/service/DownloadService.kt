package app.sotto.service

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
import app.sotto.MainActivity
import app.sotto.R
import app.sotto.SottoApp
import app.sotto.asr.InstallState
import app.sotto.ui.formatBytes
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Keeps a model download alive when the app is left or the screen sleeps. The download itself
 * lives in [app.sotto.asr.ModelManager]; this only holds the phone awake, keeps Wi-Fi up, and
 * shows progress. It stops itself when nothing is downloading.
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
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, build(0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) {
            // Started from the background (a download resumed at launch by something other than
            // the user opening the app). The download still runs; it just is not protected.
            Log.w(TAG, "Could not go foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sotto:download").apply { acquire(MAX_MS) }
        @Suppress("DEPRECATION") // The replacement constant needs API 29's low-latency mode, which is the wrong trade here.
        wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "sotto:download").apply { acquire() }

        val models = (application as SottoApp).models
        lifecycleScope.launch {
            models.states.map { states -> states.values.filterIsInstance<InstallState.Downloading>() }.collectLatest { active ->
                if (active.isEmpty()) {
                    release()
                    ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        lifecycleScope.launch {
            // Once a second is plenty for a progress bar, and the system throttles faster updates anyway.
            models.states.sample(1000).collectLatest { states ->
                val active = states.values.filterIsInstance<InstallState.Downloading>()
                if (active.isNotEmpty()) {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(active.sumOf { it.doneBytes }, active.sumOf { it.totalBytes }))
                }
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

    private fun build(done: Long, total: Long): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sotto)
            .setContentTitle("Downloading models")
            .setContentText(if (total > 0) "${formatBytes(done)} of ${formatBytes(total)}" else null)
            .setProgress(1000, if (total > 0) (done * 1000 / total).toInt() else 0, total <= 0)
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
