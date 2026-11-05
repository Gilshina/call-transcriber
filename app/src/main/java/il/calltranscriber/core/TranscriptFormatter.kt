package il.calltranscriber.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Segment(val startMs: Long, val endMs: Long, val text: String)

data class TranscriptMeta(
    val contact: String?,
    val callTimeMillis: Long,
    val audioDurationMs: Long,
    val sourceName: String,
    val modelName: String,
    val processingMs: Long,
)

object TranscriptFormatter {
    private val NON_SPEECH_TAG = Regex("""^[\[(♪*].{0,30}[\])♪*]$""")
    private val WHITESPACE = Regex("""\s+""")

    /** "m:ss", or "h:mm:ss" from one hour up. */
    fun formatClock(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0) / 1000)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    fun cleanText(raw: String): String = raw.replace('�', ' ').replace(WHITESPACE, " ").trim()

    /**
     * Normalizes raw Whisper output: trims, drops empty and "[music]"-style
     * non-speech tags, and collapses runs of identical consecutive segments
     * (a classic Whisper hallucination loop) down to one.
     */
    fun tidy(raw: List<Segment>): List<Segment> {
        val out = ArrayList<Segment>()
        for (seg in raw.sortedBy { it.startMs }) {
            val text = cleanText(seg.text)
            if (text.isEmpty() || NON_SPEECH_TAG.matches(text)) continue
            val prev = out.lastOrNull()
            if (prev != null && prev.text == text) {
                out[out.size - 1] = prev.copy(endMs = maxOf(prev.endMs, seg.endMs))
                continue
            }
            out.add(seg.copy(text = text))
        }
        return out
    }

    fun wordCount(segments: List<Segment>): Int =
        segments.sumOf { seg -> seg.text.split(' ').count { it.isNotBlank() } }

    fun build(meta: TranscriptMeta, segments: List<Segment>, withTimestamps: Boolean): String {
        val dateFmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ROOT)
        val sb = StringBuilder()
        sb.append("תמלול שיחה\n")
        sb.append("══════════════════════════════\n")
        sb.append("איש קשר: ").append(meta.contact ?: "לא ידוע").append('\n')
        sb.append("תאריך ושעה: ").append(dateFmt.format(Date(meta.callTimeMillis))).append('\n')
        sb.append("משך ההקלטה: ").append(formatClock(meta.audioDurationMs)).append('\n')
        sb.append("קובץ מקור: ").append(meta.sourceName).append('\n')
        sb.append("מודל: ").append(meta.modelName)
            .append(" | זמן עיבוד: ").append(formatClock(meta.processingMs)).append('\n')
        sb.append("──────────────────────────────\n\n")
        if (segments.isEmpty()) {
            sb.append("(לא זוהה דיבור בהקלטה)\n")
        } else if (withTimestamps) {
            for (seg in segments) {
                sb.append('[').append(formatClock(seg.startMs)).append("] ").append(seg.text).append('\n')
            }
        } else {
            sb.append(segments.joinToString(" ") { it.text }).append('\n')
        }
        return sb.toString()
    }

    /** Safe file-name fragment: keeps letters (incl. Hebrew), digits, '+', '-', '_'. */
    fun safeFileFragment(s: String?, maxLen: Int = 40): String {
        val cleaned = (s ?: "").map { if (it.isLetterOrDigit() || it == '+' || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .trim('_')
        return cleaned.take(maxLen).ifEmpty { "unknown" }
    }
}
