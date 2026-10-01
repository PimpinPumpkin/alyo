package app.sotto.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.sotto.MainActivity
import app.sotto.SottoApp
import kotlinx.coroutines.launch

/**
 * The quick settings tile. Tapping it while idle opens the app already recording; tapping it
 * while recording stops and saves. Starting has to go through the activity: Android only lets the
 * microphone be opened by an app that is on screen.
 */
class RecordTile : TileService() {
    private val app get() = application as SottoApp

    override fun onStartListening() = refresh()

    override fun onClick() {
        if (app.recorder.session.value != null) {
            app.scope.launch { app.recorder.stop() }
            qsTile?.apply { state = Tile.STATE_INACTIVE; updateTile() }
            return
        }
        val intent = Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_RECORD).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh() {
        qsTile?.apply {
            state = if (app.recorder.session.value != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }
}
