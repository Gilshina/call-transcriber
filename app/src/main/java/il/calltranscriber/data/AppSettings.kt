package il.calltranscriber.data

import android.content.Context
import il.calltranscriber.core.RecordingMatcher
import il.calltranscriber.core.SourceKey

/** All user-facing preferences plus the "already transcribed" bookkeeping. */
class AppSettings(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Transcribe new call recordings automatically. */
    var autoEnabled: Boolean
        get() = sp.getBoolean("auto", false)
        set(v) = sp.edit().putBoolean("auto", v).apply()

    /** Only recordings added after this moment are picked up automatically. */
    var enabledSinceMillis: Long
        get() = sp.getLong("enabled_since", Long.MAX_VALUE)
        set(v) = sp.edit().putLong("enabled_since", v).apply()

    var activeModel: String?
        get() = sp.getString("active_model", null)
        set(v) = sp.edit().putString("active_model", v).apply()

    var threads: Int
        get() = sp.getInt("threads", 6)
        set(v) = sp.edit().putInt("threads", v.coerceIn(1, 8)).apply()

    /** 1 = greedy (fast); 5 = beam search (slower, a bit more accurate). */
    var beamSize: Int
        get() = sp.getInt("beam", 1)
        set(v) = sp.edit().putInt("beam", v).apply()

    var useVad: Boolean
        get() = sp.getBoolean("vad", true)
        set(v) = sp.edit().putBoolean("vad", v).apply()

    var timestamps: Boolean
        get() = sp.getBoolean("timestamps", true)
        set(v) = sp.edit().putBoolean("timestamps", v).apply()

    var matchPattern: String
        get() = sp.getString("match", RecordingMatcher.DEFAULT_PATTERN) ?: RecordingMatcher.DEFAULT_PATTERN
        set(v) = sp.edit().putString("match", v).apply()

    /** Optional folder chosen with the system picker (fallback when MediaStore does not list the recordings). */
    var treeUri: String?
        get() = sp.getString("tree_uri", null)
        set(v) = sp.edit().putString("tree_uri", v).apply()

    /** Set when a call goes off-hook so that the following IDLE is known to be a real call end. */
    var lastOffHookMillis: Long
        get() = sp.getLong("last_offhook", 0L)
        set(v) = sp.edit().putLong("last_offhook", v).apply()

    /** When a "call ended" event last queued a scan; used to ignore duplicate broadcasts. */
    var lastScanQueuedMillis: Long
        get() = sp.getLong("last_scan_queued", 0L)
        set(v) = sp.edit().putLong("last_scan_queued", v).apply()

    // ---- bookkeeping ---------------------------------------------------

    @Synchronized
    fun isProcessed(key: String): Boolean {
        val set = sp.getStringSet("processed", emptySet()) ?: return false
        val k = SourceKey.normalize(key)
        return set.any { SourceKey.normalize(it) == k }
    }

    @Synchronized
    fun markProcessed(key: String) {
        val k = SourceKey.normalize(key)
        val set = HashSet(sp.getStringSet("processed", emptySet()) ?: emptySet())
        set.add(k)
        sp.edit().putStringSet("processed", set).remove("attempts_$k").apply()
    }

    @Synchronized
    fun unmarkProcessed(key: String) {
        val k = SourceKey.normalize(key)
        val set = HashSet(sp.getStringSet("processed", emptySet()) ?: emptySet())
        set.removeAll { SourceKey.normalize(it) == k }
        sp.edit().putStringSet("processed", set).remove("attempts_$k").apply()
    }

    /** Counts how often we started on a recording, to stop retry loops if the system keeps killing the job. */
    @Synchronized
    fun incrementAttempts(key: String): Int {
        val k = SourceKey.normalize(key)
        val n = sp.getInt("attempts_$k", 0) + 1
        sp.edit().putInt("attempts_$k", n).apply()
        return n
    }
}
