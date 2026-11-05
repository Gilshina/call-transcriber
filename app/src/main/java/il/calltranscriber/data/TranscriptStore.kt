package il.calltranscriber.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class TranscriptEntry(
    val id: String,
    val fileName: String,
    val contact: String?,
    val callTimeMillis: Long,
    val createdMillis: Long,
    val durationMs: Long,
    val wordCount: Int,
    val snippet: String,
    val sourceKey: String,
)

/**
 * Transcripts are kept in the app's private storage (list + viewer inside the
 * app) and also copied to Documents/CallTranscripts so they show up as plain
 * .txt files in "My Files".
 */
class TranscriptStore(context: Context) {
    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "transcripts").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")

    @Synchronized
    fun list(): List<TranscriptEntry> {
        if (!indexFile.isFile) return emptyList()
        return try {
            val arr = JSONArray(indexFile.readText(Charsets.UTF_8))
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
                .sortedByDescending { it.callTimeMillis }
        } catch (e: Exception) {
            Log.w(TAG, "index unreadable", e)
            emptyList()
        }
    }

    @Synchronized
    fun add(entry: TranscriptEntry, text: String) {
        File(dir, entry.id + ".txt").writeText(text, Charsets.UTF_8)
        writeIndex(list().filter { it.id != entry.id } + entry)
    }

    @Synchronized
    fun readText(id: String): String? {
        val f = File(dir, "$id.txt")
        return if (f.isFile) f.readText(Charsets.UTF_8) else null
    }

    @Synchronized
    fun delete(id: String) {
        File(dir, "$id.txt").delete()
        writeIndex(list().filter { it.id != id })
    }

    /** Copies the text into Documents/CallTranscripts. Returns the new file's Uri, or null on failure. */
    fun publishToDocuments(fileName: String, text: String): Uri? {
        return try {
            val resolver = appContext.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/CallTranscripts")
            }
            val uri = resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: return null
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            uri
        } catch (e: Exception) {
            Log.w(TAG, "could not copy transcript to Documents", e)
            null
        }
    }

    private fun writeIndex(entries: List<TranscriptEntry>) {
        val arr = JSONArray()
        entries.forEach { arr.put(toJson(it)) }
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(arr.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(indexFile)) {
            indexFile.writeText(arr.toString(), Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun toJson(e: TranscriptEntry) = JSONObject().apply {
        put("id", e.id)
        put("fileName", e.fileName)
        put("contact", e.contact ?: JSONObject.NULL)
        put("callTime", e.callTimeMillis)
        put("created", e.createdMillis)
        put("durationMs", e.durationMs)
        put("words", e.wordCount)
        put("snippet", e.snippet)
        put("sourceKey", e.sourceKey)
    }

    private fun fromJson(o: JSONObject) = TranscriptEntry(
        id = o.getString("id"),
        fileName = o.getString("fileName"),
        contact = if (o.isNull("contact")) null else o.getString("contact"),
        callTimeMillis = o.getLong("callTime"),
        createdMillis = o.getLong("created"),
        durationMs = o.getLong("durationMs"),
        wordCount = o.getInt("words"),
        snippet = o.optString("snippet"),
        sourceKey = o.optString("sourceKey"),
    )

    companion object {
        private const val TAG = "TranscriptStore"
    }
}
