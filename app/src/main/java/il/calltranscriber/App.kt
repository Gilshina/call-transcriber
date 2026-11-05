package il.calltranscriber

import android.app.Application
import il.calltranscriber.data.AppLog
import il.calltranscriber.data.ExitInfoReporter
import il.calltranscriber.data.Notifications
import il.calltranscriber.work.Scheduler

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)

        // Record uncaught Java exceptions (with stack trace) before the process dies.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            AppLog.e("CRASH", "Uncaught exception in thread ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }

        AppLog.i("App", "process started, version ${packageManager.getPackageInfo(packageName, 0).versionName}")
        Notifications.ensureChannels(this)
        Scheduler.schedulePeriodic(this)

        // Why did the previous process end? (native crashes cannot be caught in-process)
        Thread { ExitInfoReporter.report(this) }.start()
    }
}
