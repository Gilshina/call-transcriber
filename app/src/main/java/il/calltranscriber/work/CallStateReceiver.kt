package il.calltranscriber.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import il.calltranscriber.data.AppSettings

/**
 * Android does not let apps listen in on a call, so this receiver only learns
 * *that* a call ended. Samsung's own recorder has by then saved the audio, and
 * we go and look for it.
 */
class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val settings = AppSettings(context)
        when (state) {
            TelephonyManager.EXTRA_STATE_OFFHOOK -> settings.lastOffHookMillis = System.currentTimeMillis()
            TelephonyManager.EXTRA_STATE_IDLE -> {
                // IDLE after RINGING only (missed call) has no recording, so require a prior OFFHOOK.
                if (settings.lastOffHookMillis == 0L) return
                settings.lastOffHookMillis = 0L
                if (settings.autoEnabled) Scheduler.scanNow(context, fromCall = true)
            }
        }
    }
}
