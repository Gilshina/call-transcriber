package il.calltranscriber.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import il.calltranscriber.core.TranscriptFormatter
import il.calltranscriber.data.AppState
import il.calltranscriber.data.ModelStore
import il.calltranscriber.data.TranscriptEntry
import il.calltranscriber.whisper.WhisperEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val dateFormat get() = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ROOT)

private fun titleFor(screen: Screen) = when (screen) {
    Screen.Home -> "תמלול שיחות"
    is Screen.Detail -> "תמלול"
    Screen.Recordings -> "הקלטות בטלפון"
    Screen.Settings -> "הגדרות ומודל"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: MainViewModel) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }
    BackHandler(enabled = vm.screen != Screen.Home) { vm.goHome() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(titleFor(vm.screen)) },
                navigationIcon = {
                    if (vm.screen != Screen.Home) TextButton(onClick = { vm.goHome() }) { Text("חזרה") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = vm.screen) {
                Screen.Home -> HomeScreen(vm)
                is Screen.Detail -> DetailScreen(vm, s.id)
                Screen.Recordings -> RecordingsScreen(vm)
                Screen.Settings -> SettingsScreen(vm)
            }
        }
    }
}

// ============================================================================
// Home
// ============================================================================

@Composable
fun HomeScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val currentJob by AppState.job.collectAsState()
    val job = currentJob

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.refresh()
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.transcribeUri(uri)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("תמלול אוטומטי אחרי כל שיחה", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "בסיום שיחה האפליקציה מוצאת את ההקלטה של Samsung ומתמללת אותה בעברית, על הטלפון בלבד.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = vm.autoEnabled, onCheckedChange = { vm.setAuto(it) })
                    }
                    if (vm.activeModelName == null) {
                        Text(
                            "עוד לא הותקן מודל תמלול – בלעדיו אי אפשר לתמלל.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = { vm.screen = Screen.Settings }) { Text("להורדת מודל") }
                    } else {
                        Text("מודל פעיל: ${vm.activeModelName}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (job != null) {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("מתמלל עכשיו: ${job.title}", style = MaterialTheme.typography.titleSmall)
                        Text(job.stage, style = MaterialTheme.typography.bodySmall)
                        if (job.percent >= 0) {
                            LinearProgressIndicator(progress = { job.percent / 100f }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }

        item { SetupCard(vm, onRequestPermissions = { permLauncher.launch(vm.missingRuntimePermissions()) }, ctx = ctx) }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.scanNow() }, modifier = Modifier.weight(1f)) { Text("סרוק עכשיו") }
                    OutlinedButton(onClick = { vm.openRecordings() }, modifier = Modifier.weight(1f)) { Text("הקלטות") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { pickAudio.launch(arrayOf("audio/*")) },
                        modifier = Modifier.weight(1f),
                    ) { Text("תמלל קובץ…") }
                    OutlinedButton(onClick = { vm.screen = Screen.Settings }, modifier = Modifier.weight(1f)) {
                        Text("הגדרות ומודל")
                    }
                }
            }
        }

        item { Text("תמלולים", style = MaterialTheme.typography.titleMedium) }

        if (vm.transcripts.isEmpty()) {
            item {
                Text(
                    "עדיין אין תמלולים. אחרי השיחה הבאה (עם הקלטה אוטומטית פעילה) התמלול יופיע כאן.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        items(vm.transcripts, key = { it.id }) { entry ->
            TranscriptRow(entry) { vm.open(entry.id) }
        }
    }
}

@Composable
private fun SetupCard(vm: MainViewModel, onRequestPermissions: () -> Unit, ctx: Context) {
    val p = vm.perms
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("הגדרות ראשוניות", style = MaterialTheme.typography.titleMedium)
            CheckLine(p.notifications, "הרשאת התראות (להודעה שהתמלול מוכן)")
            CheckLine(p.phone, "זיהוי סיום שיחה (הרשאת מצב טלפון)")
            CheckLine(p.audio, "גישה להקלטות (קבצי שמע)")
            if (!(p.notifications && p.phone && p.audio)) {
                Button(onClick = onRequestPermissions) { Text("אשר הרשאות") }
            }
            CheckLine(p.batteryUnrestricted, "ללא הגבלת סוללה (כדי שהתמלול ירוץ ברקע)")
            if (!p.batteryUnrestricted) {
                OutlinedButton(onClick = { requestBatteryExemption(ctx) }) { Text("אפשר ריצה ברקע") }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("הקלטת שיחות אוטומטית של Samsung", style = MaterialTheme.typography.titleSmall)
            Text(
                "אנדרואיד חוסם מאפליקציות לשמוע את הצד השני בשיחה, ולכן ההקלטה נעשית על ידי אפליקציית הטלפון של Samsung, " +
                    "והאפליקציה הזו מתמללת את הקובץ שנשמר.\n" +
                    "להפעלה: טלפון ← ⋮ ← הגדרות ← הקלטת שיחות ← הקלטה אוטומטית ← כל השיחות.\n" +
                    "אם האפשרות לא קיימת במכשיר, אין לאפליקציה דרך להקליט שיחות רגילות (ראו README).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CheckLine(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (ok) "✓" else "✗",
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleSmall,
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun requestBatteryExemption(ctx: Context) {
    try {
        ctx.startActivity(
            Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${ctx.packageName}"),
            ),
        )
    } catch (e: Exception) {
        ctx.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

@Composable
private fun TranscriptRow(entry: TranscriptEntry, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entry.contact ?: "שיחה", style = MaterialTheme.typography.titleMedium)
            Text(
                "${dateFormat.format(Date(entry.callTimeMillis))} · ${TranscriptFormatter.formatClock(entry.durationMs)} · ${entry.wordCount} מילים",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                entry.snippet,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ============================================================================
// Transcript detail
// ============================================================================

@Composable
fun DetailScreen(vm: MainViewModel, id: String) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val entry = vm.entry(id)
    var text by remember(id) { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(id) { text = vm.loadText(id) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, entry?.fileName ?: "תמלול שיחה")
                        putExtra(Intent.EXTRA_TEXT, text.orEmpty())
                    }
                    ctx.startActivity(Intent.createChooser(send, null))
                },
                enabled = text != null,
            ) { Text("שתף") }
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(text.orEmpty()))
                    vm.message = "הועתק"
                },
                enabled = text != null,
            ) { Text("העתק") }
            OutlinedButton(onClick = { confirmDelete = true }) { Text("מחק") }
        }
        if (entry != null) {
            Text(
                "נשמר גם בקובץ: Documents/CallTranscripts/${entry.fileName}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            SelectionContainer {
                Text(text ?: "טוען…", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("למחוק את התמלול?") },
            text = { Text("העותק בתוך האפליקציה יימחק. אם קיים עותק ב-Documents/CallTranscripts, הוא יישאר.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete(id)
                }) { Text("מחק") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("ביטול") } },
        )
    }
}

// ============================================================================
// Recordings on the phone (also the manual/diagnostic view)
// ============================================================================

@Composable
fun RecordingsScreen(vm: MainViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "קבצי שמע אחרונים בטלפון. קבצים שזוהו כהקלטות שיחה מסומנים. " +
                        "אם ההקלטות של Samsung לא מופיעות כאן, בדקו את ההרשאה או בחרו את התיקייה בהגדרות.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { vm.loadRecordings() }) { Text("רענן") }
            }
        }
        if (vm.recordings.isEmpty()) {
            item { Text("לא נמצאו קבצי שמע.", style = MaterialTheme.typography.bodyMedium) }
        }
        items(vm.recordings, key = { it.ref.uri.toString() }) { row ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(row.ref.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${row.ref.relativePath} · ${dateFormat.format(Date(row.ref.dateMillis))}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        val tags = listOfNotNull(
                            if (row.matched) "זוהה כשיחה" else null,
                            if (row.done) "תומלל" else null,
                        ).joinToString(" · ")
                        if (tags.isNotEmpty()) {
                            Text(tags, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    OutlinedButton(onClick = { vm.transcribe(row.ref) }) { Text(if (row.done) "שוב" else "תמלל") }
                }
            }
        }
    }
}

// ============================================================================
// Settings + model management
// ============================================================================

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val download by AppState.download.collectAsState()
    var customUrl by remember { mutableStateOf("") }

    val importModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModel(uri)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                // The grant still works for this session; scans may need the folder re-picked later.
            }
            vm.setTreeUri(uri)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- models ----
        item { Text("מודל תמלול", style = MaterialTheme.typography.titleLarge) }
        item {
            Text(
                "המודל מורד פעם אחת ורץ על הטלפון. מודל גדול מדויק יותר בעברית אבל איטי יותר.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        val dl = download
        if (dl != null) {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(dl.fileName, style = MaterialTheme.typography.titleSmall)
                        when {
                            dl.done -> Text("ההורדה הסתיימה ✓")
                            dl.error != null -> Text(dl.error, color = MaterialTheme.colorScheme.error)
                            else -> {
                                val pct = if (dl.total > 0) dl.bytes.toFloat() / dl.total else 0f
                                if (dl.total > 0) LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth())
                                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Text("${dl.bytes / 1_000_000} / ${if (dl.total > 0) "${dl.total / 1_000_000}" else "?"} MB")
                                TextButton(onClick = { vm.cancelDownload() }) { Text("בטל הורדה") }
                            }
                        }
                    }
                }
            }
        }

        val installedNames = vm.installedModels.map { it.name }.toSet()
        items(ModelStore.PRESETS, key = { it.fileName }) { preset ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(preset.title, style = MaterialTheme.typography.titleSmall)
                    Text(preset.description, style = MaterialTheme.typography.bodySmall)
                    if (preset.fileName in installedNames) {
                        Text("מותקן ✓", color = MaterialTheme.colorScheme.primary)
                    } else {
                        Button(onClick = { vm.downloadModel(preset.url, preset.fileName) }) { Text("הורד (~${preset.approxMb}MB)") }
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("מודל אחר", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = customUrl,
                        onValueChange = { customUrl = it },
                        label = { Text("כתובת https של קובץ ggml‏ (.bin)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = customUrl.startsWith("https://"),
                            onClick = {
                                val name = customUrl.substringBefore('?').substringAfterLast('/').ifBlank { "custom-model.bin" }
                                vm.downloadModel(customUrl, if (name.endsWith(".bin")) name else "$name.bin")
                            },
                        ) { Text("הורד") }
                        OutlinedButton(onClick = { importModel.launch(arrayOf("*/*")) }) { Text("ייבא מקובץ…") }
                    }
                    vm.importProgress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        if (vm.installedModels.isNotEmpty()) {
            item { Text("מודלים מותקנים", style = MaterialTheme.typography.titleMedium) }
            items(vm.installedModels, key = { it.name }) { file ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = vm.activeModelName == file.name, onClick = { vm.selectModel(file.name) })
                    Column(Modifier.weight(1f)) {
                        Text(file.name, style = MaterialTheme.typography.bodyMedium)
                        Text("${file.length() / 1_000_000} MB", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { vm.deleteModel(file) }) { Text("מחק") }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("זיהוי דיבור (VAD) – מומלץ", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "מודל קטן (~1MB) שמדלג על שקט ומונע ש-Whisper ימציא טקסט בהפסקות.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (vm.hasVad) Text("מותקן ✓", color = MaterialTheme.colorScheme.primary)
                    else Button(onClick = { vm.downloadModel(ModelStore.VAD_URL, ModelStore.VAD_FILE_NAME) }) { Text("הורד") }
                }
            }
        }

        item { HorizontalDivider() }

        // ---- recognition ----
        item { Text("תמלול", style = MaterialTheme.typography.titleLarge) }
        item {
            Column {
                Text("מספר תהליכונים: ${vm.threads}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = vm.threads.toFloat(),
                    onValueChange = { vm.setThreads(it.roundToInt()) },
                    valueRange = 2f..8f,
                    steps = 5,
                )
                Text("יותר תהליכונים = מהיר יותר, עד גבול מסוים (בטלפון בדרך כלל 4–6 הכי טוב).", style = MaterialTheme.typography.bodySmall)
            }
        }
        item { SwitchLine("חיפוש קרן (איטי יותר, מעט מדויק יותר)", vm.beamSize > 1) { vm.setBeam(if (it) 5 else 1) } }
        item { SwitchLine("דלג על שקט (VAD)", vm.useVad && vm.hasVad, enabled = vm.hasVad) { vm.setUseVad(it) } }
        item { SwitchLine("זמנים בתמלול ([0:35] לפני כל משפט)", vm.timestamps) { vm.setTimestamps(it) } }

        item { HorizontalDivider() }

        // ---- recording detection ----
        item { Text("זיהוי הקלטות שיחה", style = MaterialTheme.typography.titleLarge) }
        item {
            OutlinedTextField(
                value = vm.matchPattern,
                onValueChange = { vm.setMatchPattern(it) },
                label = { Text("ביטוי (regex) לנתיב/שם הקובץ") },
                supportingText = { Text("ברירת מחדל: call|שיחה  (תיקיית Recordings/Call של Samsung)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "אם ההקלטות לא נמצאות אוטומטית, אפשר לבחור את התיקייה ידנית (בדרך כלל Recordings/Call בזיכרון הפנימי).",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickFolder.launch(null) }) { Text("בחר תיקייה…") }
                    if (vm.treeUri != null) TextButton(onClick = { vm.setTreeUri(null) }) { Text("נקה בחירה") }
                }
                if (vm.treeUri != null) Text("תיקייה נבחרה ✓", style = MaterialTheme.typography.bodySmall)
            }
        }

        item { HorizontalDivider() }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("מידע טכני", style = MaterialTheme.typography.titleMedium)
                Text(
                    "מעבד: ${WhisperEngine.systemInfo()}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SwitchLine(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
