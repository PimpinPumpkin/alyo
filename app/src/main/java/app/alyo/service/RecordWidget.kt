package app.alyo.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import app.alyo.MainActivity
import app.alyo.R

/** A one-cell home screen widget: the record button. It opens the app already recording. */
class RecordWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val open = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_RECORD).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        for (id in ids) {
            manager.updateAppWidget(id, RemoteViews(context.packageName, R.layout.widget_record).apply { setOnClickPendingIntent(R.id.widget_root, open) })
        }
    }
}
