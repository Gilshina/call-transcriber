package il.calltranscriber.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import il.calltranscriber.core.RecordingMatcher
import il.calltranscriber.data.AppLog
import il.calltranscriber.data.AppSettings
import il.calltranscriber.data.AppState
import il.calltranscriber.data.ModelStore
import il.calltranscriber.data.RecordingRef
import il.calltranscriber.data.RecordingSource
import il.calltranscriber.data.TranscriptEntry
import il.calltranscriber.data.TranscriptStore
import il.calltranscriber.whisper.WhisperEngine
import il.calltranscriber.work.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Screen {
    data object Home : Screen
    data class Detail(val id: String) : Screen
    data object Recordings : Screen
    data object Settings : Screen
}

data class PermState(
    val notifications: Boolean,
    val phone: Boolean,
    val audio: Boolean,
    val batteryUnrestricted: Boolean,
) {
    val allGranted get() = notifications && phone && audio && batteryUnrestricted
}

data class RecordingRow(val ref: RecordingRef, val matched: Boolean, val done: Boolean)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication<Application>().applicationContext
    val settings = AppSettings(app)
    private val store = TranscriptStore(app)
    private val models = ModelStore(app)

    var screen: Screen by mutableStateOf(Screen.Home)

    var transcripts by mutableStateOf<List<TranscriptEntry>>(emptyList())
        private set
    var recordings by mutableStateOf<List<RecordingRow>>(emptyList())
        private set
    var installedModels by mutableStateOf<List<File>>(emptyList())
        private set
    var hasVad by mutableStateOf(false)
        private set
    var perms by mutableStateOf(readPerms())
        private set

    var autoEnabled by mutableStateOf(settings.autoEnabled)
        private set
    var activeModelName by mutableStateOf<String?>(null)
        private set
    var threads by mutableStateOf(settings.threads)
        private set
    var beamSize by mutableStateOf(settings.beamSize)
        private set
    var useVad by mutableStateOf(settings.useVad)
        private set
    var timestamps by mutableStateOf(settings.timestamps)
        private set
    var matchPattern by mutableStateOf(settings.matchPattern)
        private set
    var treeUri by mutableStateOf(settings.treeUri)
        private set

    var message by mutableStateOf<String?>(null)
    var importProgress by mutableStateOf<String?>(null)
        private set
    var selfTestRunning by mutableStateOf(false)
        private set

    init {
        AppState.dataVersion.onEach { reload() }.launchIn(viewModelScope)
    }

    /** Re-read everything that can change outside the UI (permissions, files, workers). */
    fun refresh() {
        perms = readPerms()
        reload()
    }

    private fun reload() {
        viewModelScope.launch {
            val (list, installed, vad, active) = withContext(Dispatchers.IO) {
                Quad(store.list(), models.models(), models.hasVad(), models.resolveActive(settings)?.name)
            }
            transcripts = list
            installedModels = installed
            hasVad = vad
            activeModelName = active
        }
    }

    // ---- navigation ---------------------------------------------------------

    fun open(id: String) {
        screen = Screen.Detail(id)
    }

    fun goHome() {
        screen = Screen.Home
    }

    fun openRecordings() {
        screen = Screen.Recordings
        loadRecordings()
    }

    // ---- permissions ----------------------------------------------------------

    private fun readPerms(): PermState {
        fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
        val pm = ctx.getSystemService(PowerManager::class.java)
        return PermState(
            notifications = granted(Manifest.permission.POST_NOTIFICATIONS),
            phone = granted(Manifest.permission.READ_PHONE_STATE),
            audio = granted(Manifest.permission.READ_MEDIA_AUDIO),
            batteryUnrestricted = pm.isIgnoringBatteryOptimizations(ctx.packageName),
        )
    }

    fun missingRuntimePermissions(): Array<String> = buildList {
        if (!perms.notifications) add(Manifest.permission.POST_NOTIFICATIONS)
        if (!perms.phone) add(Manifest.permission.READ_PHONE_STATE)
        if (!perms.audio) add(Manifest.permission.READ_MEDIA_AUDIO)
    }.toTypedArray()

    // ---- automatic mode ---------------------------------------------------------

    fun setAuto(enabled: Boolean) {
        if (enabled) {
            // Only recordings made from now on are picked up automatically.
            if (!settings.autoEnabled) settings.enabledSinceMillis = System.currentTimeMillis()
        }
        settings.autoEnabled = enabled
        autoEnabled = enabled
    }

    fun scanNow() {
        if (!autoEnabled) {
            message = "הפעילו קודם את התמלול האוטומטי, או השתמשו ב\"הקלטות\" כדי לתמלל הקלטה ספציפית."
            return
        }
        Scheduler.scanNow(ctx, fromCall = false)
        message = "מחפש הקלטות חדשות…"
    }

    // ---- recordings list / manual transcription ---------------------------------

    fun loadRecordings() {
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) {
                val matcher = RecordingMatcher(settings.matchPattern)
                RecordingSource.queryAll(ctx, settings).take(60).map {
                    RecordingRow(
                        ref = it,
                        matched = matcher.matches(it.relativePath, it.displayName),
                        done = settings.isProcessed(it.key),
                    )
                }
            }
            recordings = rows
        }
    }

    fun transcribe(ref: RecordingRef) {
        Scheduler.transcribeFile(ctx, ref.uri, ref.displayName, ref.dateMillis)
        message = "התמלול של \"${ref.displayName}\" התחיל. התוצאה תופיע ברשימה."
    }

    /** Copies a picked/shared file into app storage first, so the grant cannot expire while we work. */
    fun transcribeUri(uri: Uri) {
        viewModelScope.launch {
            try {
                val (file, name) = withContext(Dispatchers.IO) { copyToInbox(uri) }
                Scheduler.transcribeFile(ctx, Uri.fromFile(file), name, System.currentTimeMillis())
                message = "התמלול של \"$name\" התחיל. התוצאה תופיע ברשימה."
            } catch (e: Exception) {
                message = "לא הצלחתי לקרוא את הקובץ: ${e.message}"
            }
        }
    }

    private fun copyToInbox(uri: Uri): Pair<File, String> {
        val resolver = ctx.contentResolver
        var name = "audio"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { name = it }
        }
        val inbox = File(ctx.filesDir, "inbox").apply { mkdirs() }
        val dest = File(inbox, "${System.currentTimeMillis()}_" + name.replace(Regex("[^\\p{L}\\p{N}._-]"), "_"))
        resolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        } ?: throw IllegalStateException("לא ניתן לפתוח את הקובץ")
        return dest to name
    }

    // ---- transcripts -------------------------------------------------------------------

    suspend fun loadText(id: String): String =
        withContext(Dispatchers.IO) { store.readText(id) } ?: "(הקובץ לא נמצא)"

    fun entry(id: String): TranscriptEntry? = transcripts.firstOrNull { it.id == id }

    fun delete(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(id) }
            AppState.bumpData()
            goHome()
        }
    }

    // ---- models & settings ------------------------------------------------------------------

    fun downloadModel(url: String, fileName: String) {
        val clean = url.trim()
        if (!clean.startsWith("https://")) {
            message = "כתובת ההורדה חייבת להתחיל ב-https://"
            return
        }
        Scheduler.downloadModel(ctx, clean, fileName)
    }

    fun cancelDownload() {
        Scheduler.cancelDownload(ctx)
        AppState.download.value = null
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            importProgress = "מעתיק מודל…"
            try {
                val name = withContext(Dispatchers.IO) {
                    var display = "imported-model.bin"
                    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0)?.let { display = it }
                    }
                    if (!display.endsWith(".bin")) display += ".bin"
                    val part = File(models.dir, "$display.part")
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        part.outputStream().use { input.copyTo(it) }
                    } ?: throw IllegalStateException("לא ניתן לפתוח את הקובץ")
                    if (!ModelStore.isValidGgml(part)) {
                        part.delete()
                        throw IllegalStateException("זה לא קובץ מודל של whisper.cpp (ggml)")
                    }
                    val target = File(models.dir, display)
                    target.delete()
                    part.renameTo(target)
                    display
                }
                if (settings.activeModel == null) settings.activeModel = name
                message = "המודל $name יובא בהצלחה."
            } catch (e: Exception) {
                message = "ייבוא המודל נכשל: ${e.message}"
            } finally {
                importProgress = null
                AppState.bumpData()
            }
        }
    }

    fun selectModel(name: String) {
        settings.activeModel = name
        activeModelName = name
    }

    fun deleteModel(file: File) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { file.delete() }
            if (settings.activeModel == file.name) settings.activeModel = null
            AppState.bumpData()
        }
    }

    fun updateThreads(v: Int) {
        settings.threads = v
        threads = settings.threads
    }

    fun setBeam(v: Int) {
        settings.beamSize = v
        beamSize = v
    }

    fun updateUseVad(v: Boolean) {
        settings.useVad = v
        useVad = v
    }

    fun updateTimestamps(v: Boolean) {
        settings.timestamps = v
        timestamps = v
    }

    fun updateMatchPattern(v: String) {
        settings.matchPattern = v
        matchPattern = v
    }

    fun updateTreeUri(uri: Uri?) {
        settings.treeUri = uri?.toString()
        treeUri = settings.treeUri
    }

    /**
     * Loads the active model and transcribes 5 s of noise, to check that the speech engine itself
     * runs on this phone without needing a real call. Progress is written to the diagnostic log, so
     * even a native crash leaves a trail.
     */
    fun runEngineSelfTest() {
        if (selfTestRunning) return
        selfTestRunning = true
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val model = models.resolveActive(settings)
                if (model == null) {
                    message = "אין מודל מותקן לבדיקה."
                    return@launch
                }
                AppLog.i("SelfTest", "loading ${model.name} (${model.length() / 1_000_000} MB)")
                val t0 = SystemClock.elapsedRealtime()
                WhisperEngine.load(model.path).use { engine ->
                    AppLog.i("SelfTest", "model loaded in ${SystemClock.elapsedRealtime() - t0} ms")
                    val rnd = java.util.Random(1)
                    val samples = FloatArray(16_000 * 5) { (rnd.nextGaussian() * 0.05).toFloat() }
                    val vad = if (settings.useVad && models.hasVad()) models.vadFile().path else null
                    var segments = 0
                    val t1 = SystemClock.elapsedRealtime()
                    AppLog.i("SelfTest", "transcribing 5 s of noise (threads=${settings.threads}, beam=${settings.beamSize}, vad=${vad != null})")
                    val ok = engine.transcribeChunk(
                        samples, "he", settings.threads, settings.beamSize, vad,
                        { _, _, _ -> segments++ }, { },
                    )
                    val ms = SystemClock.elapsedRealtime() - t1
                    AppLog.i("SelfTest", "whisper finished: ok=$ok, segments=$segments, ${ms} ms")
                    message = if (ok) "בדיקת המנוע עברה ($ms מילי-שניות ל-5 שניות אודיו)." else "בדיקת המנוע נכשלה – ראו יומן אבחון."
                }
            } catch (t: Throwable) {
                AppLog.e("SelfTest", "self-test failed", t)
                message = "בדיקת המנוע נכשלה: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                selfTestRunning = false
            }
        }
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
