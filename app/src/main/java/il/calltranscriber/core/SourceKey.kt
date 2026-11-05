package il.calltranscriber.core

/**
 * Identity of a recording = its file name (Samsung includes the call time to the second).
 *
 * Earlier versions appended "|<size>" to the name. The size reported by MediaStore can differ
 * from the final size of a file that was still being written, which made one recording look new
 * on every scan and caused endless re-transcription. [normalize] maps old keys onto new ones.
 */
object SourceKey {
    fun of(displayName: String): String = displayName

    fun normalize(key: String): String = key.substringBefore('|')

    fun same(a: String, b: String): Boolean = a.isNotEmpty() && b.isNotEmpty() && normalize(a) == normalize(b)
}
