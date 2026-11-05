package il.calltranscriber.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import il.calltranscriber.core.RecordingMatcher
import kotlinx.coroutines.delay

data class RecordingRef(
    val uri: Uri,
    val displayName: String,
    val relativePath: String,
    val dateMillis: Long,
    val sizeBytes: Long,
) {
    /** Same file seen through MediaStore or the folder picker gets the same key. */
    val key: String get() = RecordingSource.keyFor(displayName, sizeBytes)
}

/** Finds audio files on the phone: via MediaStore, and optionally via a folder the user picked. */
object RecordingSource {
    private const val TAG = "RecordingSource"
    private val AUDIO_EXTENSIONS = setOf("m4a", "mp3", "amr", "3ga", "aac", "wav", "ogg", "opus", "mp4", "3gp", "flac")

    fun keyFor(displayName: String, sizeBytes: Long) = "$displayName|$sizeBytes"

    fun queryMediaStore(context: Context, limit: Int = 300): List<RecordingRef> {
        val out = ArrayList<RecordingRef>()
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.SIZE,
        )
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.Audio.Media.DATE_ADDED} DESC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        try {
            context.contentResolver.query(collection, projection, args, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val pathCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    out.add(
                        RecordingRef(
                            uri = android.content.ContentUris.withAppendedId(collection, id),
                            displayName = c.getString(nameCol) ?: continue,
                            relativePath = c.getString(pathCol).orEmpty(),
                            dateMillis = c.getLong(dateCol) * 1000L,
                            sizeBytes = c.getLong(sizeCol),
                        ),
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "no permission to read audio files", e)
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore query failed", e)
        }
        return out
    }

    /** Lists audio files directly inside the folder the user granted access to. */
    fun queryTree(context: Context, treeUri: Uri): List<RecordingRef> {
        val out = ArrayList<RecordingRef>()
        try {
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            context.contentResolver.query(children, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val mime = c.getString(4).orEmpty()
                    val name = c.getString(1) ?: continue
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    if (name.substringAfterLast('.', "").lowercase() !in AUDIO_EXTENSIONS) continue
                    out.add(
                        RecordingRef(
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0)),
                            displayName = name,
                            relativePath = "(תיקייה שנבחרה)/",
                            dateMillis = c.getLong(2),
                            sizeBytes = c.getLong(3),
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "folder query failed", e)
        }
        return out
    }

    /** Everything we might transcribe, newest first, without duplicates. */
    fun queryAll(context: Context, settings: AppSettings): List<RecordingRef> {
        val tree = settings.treeUri?.let { queryTree(context, Uri.parse(it)) }.orEmpty()
        return (tree + queryMediaStore(context))
            .distinctBy { it.displayName }
            .sortedByDescending { it.dateMillis }
    }

    /** New call recordings that were added after auto-transcription was switched on. */
    fun findNewCalls(context: Context, settings: AppSettings): List<RecordingRef> {
        val matcher = RecordingMatcher(settings.matchPattern)
        val since = settings.enabledSinceMillis - 60_000L // tolerate small clock differences
        val treeNames = settings.treeUri?.let { queryTree(context, Uri.parse(it)) }.orEmpty()
        val fromTree = treeNames // everything in the explicitly chosen folder counts
        val fromMedia = queryMediaStore(context).filter { matcher.matches(it.relativePath, it.displayName) }
        return (fromTree + fromMedia)
            .distinctBy { it.displayName }
            .filter { it.dateMillis >= since && !settings.isProcessed(it.key) }
            .sortedBy { it.dateMillis }
    }

    fun sizeOf(context: Context, uri: Uri): Long = try {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    } catch (e: Exception) {
        -1L
    }

    /**
     * Samsung may still be writing the file when the call ends. Wait until its
     * size stops changing; returns the ref with the final size, or null if it
     * never became readable.
     */
    suspend fun settle(context: Context, ref: RecordingRef): RecordingRef? {
        var last = sizeOf(context, ref.uri)
        repeat(15) {
            delay(2_000)
            val now = sizeOf(context, ref.uri)
            if (now > 0 && now == last) return ref.copy(sizeBytes = now)
            last = now
        }
        return if (last > 0) ref.copy(sizeBytes = last) else null
    }
}
