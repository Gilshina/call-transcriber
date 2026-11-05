package il.calltranscriber.work

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** All the ways work gets queued. TranscribeWorker serializes itself, so plain enqueue is fine. */
object Scheduler {
    const val KEY_MODE = "mode"
    const val MODE_SCAN = "scan"
    const val MODE_SINGLE = "single"
    const val KEY_FROM_CALL = "from_call"
    const val KEY_ATTEMPT = "attempt"
    const val KEY_URI = "uri"
    const val KEY_NAME = "name"
    const val KEY_DATE = "date"
    const val KEY_URL = "url"
    const val KEY_FILE = "file"

    private const val PERIODIC_NAME = "periodic-scan"
    private const val DOWNLOAD_NAME = "model-download"

    /**
     * Look for new call recordings and transcribe them. Expedited so that it
     * starts right away even when the app is in the background after a call.
     */
    fun scanNow(context: Context, fromCall: Boolean, attempt: Int = 0) {
        val request = OneTimeWorkRequestBuilder<TranscribeWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(
                workDataOf(
                    KEY_MODE to MODE_SCAN,
                    KEY_FROM_CALL to fromCall,
                    KEY_ATTEMPT to attempt,
                ),
            )
            .build()
        WorkManager.getInstance(context.applicationContext).enqueue(request)
    }

    /** Transcribe one specific file regardless of whether it was transcribed before. */
    fun transcribeFile(context: Context, uri: Uri, displayName: String, dateMillis: Long) {
        val request = OneTimeWorkRequestBuilder<TranscribeWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(
                workDataOf(
                    KEY_MODE to MODE_SINGLE,
                    KEY_URI to uri.toString(),
                    KEY_NAME to displayName,
                    KEY_DATE to dateMillis,
                ),
            )
            .build()
        WorkManager.getInstance(context.applicationContext).enqueue(request)
    }

    /** Safety net: also look every ~15 minutes, in case the call-ended broadcast was missed. */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<TranscribeWorker>(15, TimeUnit.MINUTES)
            .setInputData(workDataOf(KEY_MODE to MODE_SCAN, KEY_FROM_CALL to false))
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun downloadModel(context: Context, url: String, fileName: String) {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(KEY_URL to url, KEY_FILE to fileName))
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(DOWNLOAD_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelDownload(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(DOWNLOAD_NAME)
    }
}
