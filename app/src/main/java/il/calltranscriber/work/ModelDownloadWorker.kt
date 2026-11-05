package il.calltranscriber.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import il.calltranscriber.data.AppSettings
import il.calltranscriber.data.AppState
import il.calltranscriber.data.ModelStore
import il.calltranscriber.data.Notifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Downloads a model file with resume support (the files are large and phone connections flaky). */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private class HttpStatusException(val code: Int) : IOException("שגיאת שרת HTTP $code")

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("מוריד מודל…", null)

    private fun foregroundInfo(text: String, percent: Int?) = ForegroundInfo(
        Notifications.ID_DOWNLOAD,
        Notifications.progress(applicationContext, "הורדת מודל תמלול", text, percent),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val url = inputData.getString(Scheduler.KEY_URL) ?: return Result.failure()
        val fileName = inputData.getString(Scheduler.KEY_FILE) ?: return Result.failure()
        val store = ModelStore(ctx)
        val part = File(store.dir, "$fileName.part")
        val target = File(store.dir, fileName)

        try {
            setForeground(foregroundInfo("מוריד $fileName…", null))
        } catch (e: Exception) {
            Log.w(TAG, "could not start as foreground work", e)
        }
        AppState.download.value = AppState.DownloadInfo(fileName, part.length(), 0)

        try {
            withContext(Dispatchers.IO) { download(url, part, fileName) }

            if (!ModelStore.isValidGgml(part)) {
                part.delete()
                throw IOException("הקובץ שהורד אינו מודל תקין (ייתכן שהכתובת שגויה)")
            }
            target.delete()
            if (!part.renameTo(target)) throw IOException("לא הצלחתי לשמור את הקובץ")

            val settings = AppSettings(ctx)
            val isVad = fileName == ModelStore.VAD_FILE_NAME
            if (!isVad && (settings.activeModel == null || !File(store.dir, settings.activeModel!!).isFile)) {
                settings.activeModel = fileName
            }
            AppState.download.value = AppState.DownloadInfo(fileName, target.length(), target.length(), done = true)
            AppState.bumpData()
            return Result.success()
        } catch (e: CancellationException) {
            AppState.download.value = null
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "download failed", e)
            val retryable = e !is HttpStatusException && runAttemptCount < MAX_RETRIES
            val bytes = part.length()
            AppState.download.value = AppState.DownloadInfo(
                fileName, bytes, AppState.download.value?.total ?: 0,
                error = if (retryable) "נקטע, מנסה שוב… (${e.message})" else (e.message ?: "ההורדה נכשלה"),
            )
            if (!retryable) Notifications.error(ctx, "הורדת המודל נכשלה: ${e.message}")
            return if (retryable) Result.retry() else Result.failure()
        } finally {
            Notifications.cancel(ctx, Notifications.ID_DOWNLOAD)
        }
    }

    private suspend fun download(startUrl: String, part: File, fileName: String) {
        var existing = if (part.isFile) part.length() else 0L
        var url = URL(startUrl)
        var redirects = 0

        while (true) {
            currentCoroutineContext().ensureActive()
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false // handled by hand so the Range header survives cross-host redirects
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "CallTranscriber/0.1")
            if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")

            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: throw IOException("הפניה ללא כתובת")
                    if (++redirects > MAX_REDIRECTS) throw IOException("יותר מדי הפניות")
                    url = URL(url, location)
                    continue
                }
                if (code == 416 && existing > 0) { // our partial file does not fit; start over
                    part.delete()
                    existing = 0
                    continue
                }
                if (code != 200 && code != 206) throw HttpStatusException(code)

                val append = code == 206 && existing > 0
                if (!append) existing = 0
                val total = totalLength(conn, code, existing)

                var written = existing
                var lastUi = 0L
                conn.inputStream.use { input ->
                    FileOutputStream(part, append).use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastUi > 400) {
                                lastUi = now
                                report(fileName, written, total)
                            }
                        }
                    }
                }
                report(fileName, written, total)
                if (total > 0 && written < total) throw IOException("ההורדה נקטעה ($written מתוך $total)")
                return
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun totalLength(conn: HttpURLConnection, code: Int, existing: Long): Long {
        if (code == 206) {
            // "Content-Range: bytes 100-999/1000"
            val total = conn.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
            if (total != null) return total
            val len = conn.contentLengthLong
            return if (len > 0) existing + len else 0L
        }
        return conn.contentLengthLong.coerceAtLeast(0L)
    }

    private fun report(fileName: String, bytes: Long, total: Long) {
        AppState.download.value = AppState.DownloadInfo(fileName, bytes, total)
        val percent = if (total > 0) (bytes * 100 / total).toInt() else -1
        val text = if (total > 0) "${bytes / 1_000_000} / ${total / 1_000_000} MB" else "${bytes / 1_000_000} MB"
        Notifications.update(
            applicationContext,
            Notifications.ID_DOWNLOAD,
            Notifications.progress(applicationContext, "הורדת מודל תמלול", text, percent),
        )
    }

    companion object {
        private const val TAG = "ModelDownload"
        private const val MAX_REDIRECTS = 8
        private const val MAX_RETRIES = 4
    }
}
