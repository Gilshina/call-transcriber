package il.calltranscriber.data

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import il.calltranscriber.R
import il.calltranscriber.ui.MainActivity

object Notifications {
    const val CHANNEL_PROGRESS = "progress"
    const val CHANNEL_RESULT = "result"
    const val ID_WORK = 1001
    const val ID_DOWNLOAD = 1002
    private const val ID_ERROR = 1003

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "התקדמות תמלול", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, "תמלול מוכן", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun progress(context: Context, title: String, text: String, percent: Int?): Notification {
        val b = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_transcript)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(context, null))
        if (percent != null && percent >= 0) b.setProgress(100, percent.coerceAtMost(100), false)
        else b.setProgress(0, 0, true)
        return b.build()
    }

    @SuppressLint("MissingPermission")
    fun update(context: Context, id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted: transcription still works, just silently.
        }
    }

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    fun result(context: Context, entry: TranscriptEntry, contactLabel: String) {
        val n = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_stat_transcript)
            .setContentTitle("התמלול מוכן – $contactLabel")
            .setContentText(entry.snippet)
            .setStyle(NotificationCompat.BigTextStyle().bigText(entry.snippet))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, entry.id))
            .build()
        update(context, 3000 + (entry.id.hashCode() and 0xFFFF), n)
    }

    fun error(context: Context, text: String) {
        val n = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_stat_transcript)
            .setContentTitle("תמלול שיחות")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true) // the same warning can be re-posted by later scans; don't buzz each time
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, null))
            .build()
        update(context, ID_ERROR, n)
    }

    private fun openAppIntent(context: Context, transcriptId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (transcriptId != null) putExtra(MainActivity.EXTRA_TRANSCRIPT_ID, transcriptId)
        }
        return PendingIntent.getActivity(
            context,
            transcriptId?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
