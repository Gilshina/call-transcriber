package il.calltranscriber.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import il.calltranscriber.core.ChunkPlanner
import il.calltranscriber.core.RecordingNameParser
import il.calltranscriber.core.Segment
import il.calltranscriber.core.TranscriptFormatter
import il.calltranscriber.core.TranscriptMeta
import il.calltranscriber.data.AppSettings
import il.calltranscriber.data.AppState
import il.calltranscriber.data.ModelStore
import il.calltranscriber.data.Notifications
import il.calltranscriber.data.RecordingRef
import il.calltranscriber.data.RecordingSource
import il.calltranscriber.data.TranscriptEntry
import il.calltranscriber.data.TranscriptStore
import il.calltranscriber.whisper.AudioDecoder
import il.calltranscriber.whisper.WhisperEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Finds call recordings (scan mode) or takes one given file (single mode),
 * decodes them, runs Whisper in Hebrew and stores + announces the transcript.
 */
class TranscribeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private var lastReportedPercent = -2

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("מתמלל שיחה…", null)

    private fun foregroundInfo(text: String, percent: Int?) = ForegroundInfo(
        Notifications.ID_WORK,
        Notifications.progress(applicationContext, "תמלול שיחות", text, percent),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    override suspend fun doWork(): Result = lock.withLock { runLocked() }

    private suspend fun runLocked(): Result {
        val ctx = applicationContext
        val settings = AppSettings(ctx)
        val mode = inputData.getString(Scheduler.KEY_MODE) ?: Scheduler.MODE_SCAN
        val fromCall = inputData.getBoolean(Scheduler.KEY_FROM_CALL, false)
        val attempt = inputData.getInt(Scheduler.KEY_ATTEMPT, 0)
        val single = mode == Scheduler.MODE_SINGLE

        val items: List<RecordingRef>
        if (single) {
            items = listOfNotNull(singleRef())
        } else {
            if (!settings.autoEnabled) return Result.success()
            // Give the Phone app a moment to finish writing and MediaStore to index the file.
            if (fromCall) delay(if (attempt == 0) SETTLE_FIRST_MS else SETTLE_RETRY_MS)
            items = RecordingSource.findNewCalls(ctx, settings)
            if (items.isEmpty()) {
                if (fromCall && attempt < MAX_CALL_RETRIES) Scheduler.scanNow(ctx, fromCall = true, attempt = attempt + 1)
                return Result.success()
            }
        }
        if (items.isEmpty()) return Result.success()

        val models = ModelStore(ctx)
        val modelFile = models.resolveActive(settings)
        if (modelFile == null) {
            Notifications.error(ctx, "יש הקלטה לתמלל, אבל אין מודל תמלול מותקן. פתחו את האפליקציה ← הגדרות והורידו מודל.")
            return Result.success()
        }

        try {
            setForeground(foregroundInfo("טוען מודל תמלול…", null))
        } catch (e: Exception) {
            // Android 12+ may refuse foreground-service starts from the background; keep going without it.
            Log.w(TAG, "could not start as foreground work", e)
        }
        publish("תמלול שיחות", "טוען מודל תמלול…", -1)

        val engine = try {
            withContext(Dispatchers.Default) { WhisperEngine.load(modelFile.path) }
        } catch (e: Exception) {
            Notifications.error(ctx, "טעינת המודל נכשלה (${modelFile.name}). נסו להוריד אותו מחדש.")
            AppState.job.value = null
            return Result.success()
        }

        val finished = AtomicBoolean(false)
        try {
            coroutineScope {
                // whisper_full() blocks its thread, so cancellation has to reach it through the abort flag.
                val watcher = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        if (!finished.get()) engine.cancel()
                    }
                }
                try {
                    for (item in items) {
                        processOne(engine, settings, models, item, single)
                    }
                } finally {
                    finished.set(true)
                    watcher.cancel()
                }
            }
            return Result.success()
        } finally {
            engine.close()
            AppState.job.value = null
            Notifications.cancel(ctx, Notifications.ID_WORK)
        }
    }

    private suspend fun processOne(
        engine: WhisperEngine,
        settings: AppSettings,
        models: ModelStore,
        first: RecordingRef,
        single: Boolean,
    ) {
        val ctx = applicationContext
        val parsed = RecordingNameParser.parse(first.displayName)
        val title = parsed.contact ?: RecordingNameParser.stripExtension(first.displayName)
        lastReportedPercent = -2
        publish(title, "ממתין לסיום כתיבת הקובץ…", -1)

        var settledKey: String? = null
        try {
            val ref = RecordingSource.settle(ctx, first)
            if (ref == null) {
                Notifications.error(ctx, "לא הצלחתי לקרוא את ההקלטה \"$title\".")
                return
            }
            settledKey = ref.key
            if (!single) {
                if (settings.isProcessed(ref.key)) return
                if (settings.incrementAttempts(ref.key) > MAX_ATTEMPTS) {
                    settings.markProcessed(ref.key)
                    Notifications.error(ctx, "התמלול של \"$title\" נעצר שוב ושוב, לכן דילגתי עליו. אפשר לנסות ידנית מתוך האפליקציה.")
                    return
                }
            }

            publish(title, "מפענח אודיו…", -1)
            val pcm = decode(ref.uri)
            val durationMs = pcm.size * 1000L / AudioDecoder.TARGET_RATE
            if (durationMs < MIN_AUDIO_MS) {
                settings.markProcessed(ref.key)
                return
            }

            val startedAt = SystemClock.elapsedRealtime()
            val bounds = ChunkPlanner.plan(pcm, AudioDecoder.TARGET_RATE)
            val chunkCount = bounds.size - 1
            val vadPath = if (settings.useVad && models.hasVad()) models.vadFile().path else null
            val raw = ArrayList<Segment>()

            for (i in 0 until chunkCount) {
                currentCoroutineContext().ensureActive()
                val from = bounds[i]
                val to = bounds[i + 1]
                val samples = FloatArray(to - from) { pcm[from + it] / 32768f }
                val offsetMs = from * 1000L / AudioDecoder.TARGET_RATE
                val ok = withContext(Dispatchers.Default) {
                    engine.transcribeChunk(
                        samples = samples,
                        language = "he",
                        threads = settings.threads,
                        beamSize = settings.beamSize,
                        vadModelPath = vadPath,
                        segmentSink = { s, e, t -> raw.add(Segment(offsetMs + s, offsetMs + e, t)) },
                        progressSink = { p -> publish(title, "מתמלל…", (i * 100 + p) / chunkCount) },
                    )
                }
                if (!ok) {
                    currentCoroutineContext().ensureActive()
                    throw IllegalStateException("מנוע התמלול נכשל")
                }
            }

            val segments = TranscriptFormatter.tidy(raw)
            val callTime = parsed.timestampMillis ?: ref.dateMillis
            val meta = TranscriptMeta(
                contact = parsed.contact,
                callTimeMillis = callTime,
                audioDurationMs = durationMs,
                sourceName = ref.displayName,
                modelName = engine.modelName,
                processingMs = SystemClock.elapsedRealtime() - startedAt,
            )
            val text = TranscriptFormatter.build(meta, segments, settings.timestamps)

            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ROOT).format(Date(callTime))
            val fileName = "${stamp}_${TranscriptFormatter.safeFileFragment(parsed.contact)}.txt"
            val snippet = segments.take(10).joinToString(" ") { it.text }.take(180).ifBlank { "(לא זוהה דיבור)" }
            val entry = TranscriptEntry(
                id = UUID.randomUUID().toString(),
                fileName = fileName,
                contact = parsed.contact,
                callTimeMillis = callTime,
                createdMillis = System.currentTimeMillis(),
                durationMs = durationMs,
                wordCount = TranscriptFormatter.wordCount(segments),
                snippet = snippet,
                sourceKey = ref.key,
            )
            val store = TranscriptStore(ctx)
            store.add(entry, text)
            store.publishToDocuments(fileName, text)
            settings.markProcessed(ref.key)
            AppState.bumpData()
            Notifications.result(ctx, entry, title)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "transcription failed for ${first.displayName}", e)
            if (!single) settings.markProcessed(settledKey ?: first.key)
            Notifications.error(ctx, "התמלול של \"$title\" נכשל: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            cleanupInbox(first.uri)
        }
    }

    /** Decode, retrying once: right after a call the file may still be getting finalized. */
    private suspend fun decode(uri: Uri): ShortArray {
        val jobContext = currentCoroutineContext()
        return withContext(Dispatchers.IO) {
            try {
                AudioDecoder.decodeTo16kMono(applicationContext, uri) { !jobContext.isActive }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "first decode attempt failed, retrying", e)
                delay(DECODE_RETRY_MS)
                AudioDecoder.decodeTo16kMono(applicationContext, uri) { !jobContext.isActive }
            }
        }
    }

    private fun singleRef(): RecordingRef? {
        val uri = inputData.getString(Scheduler.KEY_URI) ?: return null
        return RecordingRef(
            uri = Uri.parse(uri),
            displayName = inputData.getString(Scheduler.KEY_NAME) ?: "audio",
            relativePath = "",
            dateMillis = inputData.getLong(Scheduler.KEY_DATE, System.currentTimeMillis()),
            sizeBytes = 0,
        )
    }

    /** Files shared/picked into the app are copied to filesDir/inbox first; remove the copy afterwards. */
    private fun cleanupInbox(uri: Uri) {
        if (uri.scheme != "file") return
        val path = uri.path ?: return
        val inbox = File(applicationContext.filesDir, "inbox").path
        if (path.startsWith(inbox)) File(path).delete()
    }

    private fun publish(title: String, stage: String, percent: Int) {
        if (percent == lastReportedPercent && percent >= 0) return
        lastReportedPercent = percent
        AppState.job.value = AppState.JobInfo(title, stage, percent)
        val text = if (percent >= 0) "$stage $percent%" else stage
        Notifications.update(
            applicationContext,
            Notifications.ID_WORK,
            Notifications.progress(applicationContext, title, text, percent),
        )
    }

    companion object {
        private const val TAG = "TranscribeWorker"
        private const val SETTLE_FIRST_MS = 8_000L
        private const val SETTLE_RETRY_MS = 30_000L
        private const val DECODE_RETRY_MS = 20_000L
        private const val MAX_CALL_RETRIES = 3
        private const val MAX_ATTEMPTS = 3
        private const val MIN_AUDIO_MS = 1_500L

        /** One transcription at a time per process; later jobs wait their turn and re-scan afterwards. */
        private val lock = Mutex()
    }
}
