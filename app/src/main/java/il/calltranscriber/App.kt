package il.calltranscriber

import android.app.Application
import il.calltranscriber.data.Notifications
import il.calltranscriber.work.Scheduler

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        Scheduler.schedulePeriodic(this)
    }
}
