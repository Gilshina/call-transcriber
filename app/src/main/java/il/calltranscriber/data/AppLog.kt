package il.calltranscriber.data

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small persistent log, written synchronously so that the last lines survive a crash.
 * Shown (and copyable) in Settings so problems can be diagnosed without a computer.
 */
object AppLog {
    private const val MAX_BYTES = 200_000L
    private const val KEEP_BYTES = 100_000

    @Volatile
    private var logFile: File? = null

    fun init(context: Context) {
        if (logFile == null) logFile = File(context.applicationContext.filesDir, "app.log")
    }

    fun i(tag: String, msg: String) = write("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = write("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = write("E", tag, msg, t)

    @Synchronized
    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        when (level) {
            "E" -> Log.e(tag, msg, t)
            "W" -> Log.w(tag, msg, t)
            else -> Log.i(tag, msg)
        }
        val f = logFile ?: return
        try {
            rotate(f)
            val sb = StringBuilder()
            sb.append(SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(Date()))
                .append(' ').append(level).append('/').append(tag).append(": ").append(msg).append('\n')
            if (t != null) sb.append(Log.getStackTraceString(t)).append('\n')
            f.appendText(sb.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            // Logging must never take the app down.
        }
    }

    private fun rotate(f: File) {
        if (f.length() <= MAX_BYTES) return
        val tail = f.readText(Charsets.UTF_8).takeLast(KEEP_BYTES)
        f.writeText(tail.substringAfter('\n', tail), Charsets.UTF_8)
    }

    /** The newest [maxChars] characters of the log. */
    @Synchronized
    fun read(maxChars: Int = 15_000): String {
        val f = logFile ?: return ""
        return try {
            if (f.isFile) f.readText(Charsets.UTF_8).takeLast(maxChars) else ""
        } catch (e: Exception) {
            ""
        }
    }

    @Synchronized
    fun clear() {
        try {
            logFile?.delete()
        } catch (e: Exception) {
            // ignore
        }
    }
}
