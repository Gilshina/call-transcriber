package il.calltranscriber.core

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class ParsedRecordingName(
    /** Contact name or phone number, if it could be extracted. */
    val contact: String?,
    /** Call start time encoded in the file name, if present. */
    val timestampMillis: Long?,
)

/**
 * Samsung names call recordings like
 *   "Call recording John Smith_240115_143022.m4a"
 *   "Call recording +972501234567_240115_143022.m4a"
 * (YYMMDD_HHMMSS at the end). The prefix is localized, so we strip a few known
 * ones and otherwise keep whatever precedes the timestamp.
 */
object RecordingNameParser {
    private val SAMSUNG = Regex("""^(.*?)_(\d{6})_(\d{6})(?:\s*\(\d+\))?$""")

    private val PREFIXES = listOf(
        "call recording",
        "voice call",
        "הקלטת שיחה",
        "הקלטת שיחות",
        "שיחה",
        "call",
    )

    fun stripExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && name.length - dot <= 6) name.substring(0, dot) else name
    }

    fun parse(displayName: String, timeZone: TimeZone = TimeZone.getDefault()): ParsedRecordingName {
        val base = stripExtension(displayName).trim()
        val m = SAMSUNG.matchEntire(base)
        if (m == null) {
            return ParsedRecordingName(contact = cleanContact(base), timestampMillis = null)
        }
        val contact = cleanContact(m.groupValues[1])
        val ts = parseTimestamp(m.groupValues[2], m.groupValues[3], timeZone)
        return ParsedRecordingName(contact, ts)
    }

    private fun cleanContact(raw: String): String? {
        var s = raw.trim().trim('_', '-', ' ')
        val lower = s.lowercase(Locale.ROOT)
        for (prefix in PREFIXES) {
            // Only strip a whole leading word ("Call recording X"), never part of a name ("Callie").
            if (!lower.startsWith(prefix)) continue
            val atBoundary = lower.length == prefix.length || !lower[prefix.length].isLetterOrDigit()
            if (atBoundary) {
                s = s.substring(prefix.length).trim().trim('_', '-', ' ')
                break
            }
        }
        return s.ifBlank { null }
    }

    private fun parseTimestamp(ymd: String, hms: String, tz: TimeZone): Long? = try {
        val fmt = SimpleDateFormat("yyMMddHHmmss", Locale.ROOT)
        fmt.timeZone = tz
        fmt.isLenient = false
        fmt.parse(ymd + hms)?.time
    } catch (e: java.text.ParseException) {
        null
    }
}
