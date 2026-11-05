package il.calltranscriber.core

import java.util.Locale
import java.util.regex.PatternSyntaxException

/**
 * Decides whether an audio file on the phone is a call recording, by testing a
 * user-editable regex against "<relative path>/<file name>".
 *
 * The default matches Samsung's `Recordings/Call/...` folder and file names
 * that start with "Call recording" (English) or contain "שיחה" (Hebrew).
 */
class RecordingMatcher(pattern: String) {
    private val regex: Regex = try {
        Regex(pattern.ifBlank { DEFAULT_PATTERN }, RegexOption.IGNORE_CASE)
    } catch (e: PatternSyntaxException) {
        Regex(DEFAULT_PATTERN, RegexOption.IGNORE_CASE)
    }

    fun matches(relativePath: String?, displayName: String): Boolean {
        val haystack = (relativePath.orEmpty() + "/" + displayName).lowercase(Locale.ROOT)
        return regex.containsMatchIn(haystack)
    }

    companion object {
        const val DEFAULT_PATTERN = "call|שיחה"
    }
}
