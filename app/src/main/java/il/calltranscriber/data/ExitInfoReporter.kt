package il.calltranscriber.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import java.util.Date

/**
 * Android remembers why previous processes of this app ended (crash, native crash, killed for
 * memory, ...). Copy anything new into [AppLog] so it shows up in the in-app diagnostics.
 */
object ExitInfoReporter {
    private const val TAG = "EXIT"

    private val INTERESTING = Regex(
        "SIG[A-Z]+|abort|assert|\\.so|whisper|ggml|calltranscriber|fatal|jni|java\\.|::|check failed",
        RegexOption.IGNORE_CASE,
    )

    fun report(context: Context) {
        try {
            val settings = AppSettings(context)
            val am = context.getSystemService(ActivityManager::class.java)
            val infos = am.getHistoricalProcessExitReasons(context.packageName, 0, 10)
                .sortedBy { it.timestamp }
            val seen = settings.lastExitTimestamp
            var newest = seen
            for (info in infos) {
                if (info.timestamp <= seen) continue
                newest = maxOf(newest, info.timestamp)
                AppLog.w(
                    TAG,
                    "previous process ended: reason=${reasonName(info.reason)} status=${info.status} " +
                        "importance=${info.importance} at=${Date(info.timestamp)} desc=${info.description}",
                )
                if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) logNativeTrace(info)
            }
            settings.lastExitTimestamp = newest
        } catch (e: Exception) {
            AppLog.w(TAG, "could not read exit reasons", e)
        }
    }

    private fun logNativeTrace(info: ApplicationExitInfo) {
        try {
            val bytes = info.traceInputStream?.use { it.readBytes() } ?: return
            // The native tombstone is a binary protobuf; pull out the readable strings that matter
            // (signal name, abort message, library and function names).
            val found = LinkedHashSet<String>()
            val sb = StringBuilder()
            fun flush() {
                if (sb.length >= 5) {
                    val s = sb.toString()
                    if (INTERESTING.containsMatchIn(s)) found.add(s.take(200))
                }
                sb.setLength(0)
            }
            for (b in bytes) {
                val c = b.toInt() and 0xFF
                if (c in 32..126) sb.append(c.toChar()) else flush()
            }
            flush()
            AppLog.w(TAG, "native crash trace (${bytes.size} bytes), key strings:\n" + found.take(60).joinToString("\n") { "  $it" })
        } catch (e: Exception) {
            AppLog.w(TAG, "could not read native crash trace", e)
        }
    }

    private fun reasonName(reason: Int) = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH (Java exception)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "UNKNOWN($reason)"
    }
}
