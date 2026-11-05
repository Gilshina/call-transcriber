package il.calltranscriber.whisper

import java.io.File

/** Owns one loaded Whisper model. Not thread-safe except for [cancel], which may be called from any thread. */
class WhisperEngine private constructor(
    private val native: NativeWhisper,
    private var handle: Long,
    val modelName: String,
) : AutoCloseable {

    /**
     * Transcribes one chunk of 16 kHz mono float PCM. Segment times passed to
     * [segmentSink] are relative to the start of the chunk.
     * @return true if Whisper finished normally, false if it failed or was cancelled.
     */
    fun transcribeChunk(
        samples: FloatArray,
        language: String,
        threads: Int,
        beamSize: Int,
        vadModelPath: String?,
        segmentSink: (startMs: Long, endMs: Long, text: String) -> Unit,
        progressSink: (percent: Int) -> Unit,
    ): Boolean {
        val h = synchronized(this) { handle }
        check(h != 0L) { "engine already closed" }
        val callback = object : WhisperCallback {
            override fun onProgress(percent: Int) {
                progressSink(percent)
            }

            override fun onSegment(startMs: Long, endMs: Long, utf8Text: ByteArray) {
                segmentSink(startMs, endMs, String(utf8Text, Charsets.UTF_8))
            }
        }
        val rc = native.nTranscribe(
            h, samples, samples.size, language, threads, beamSize, vadModelPath.orEmpty(), callback,
        )
        return rc == 0
    }

    @Synchronized
    fun cancel() {
        if (handle != 0L) native.nCancel(handle)
    }

    @Synchronized
    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) native.nFree(h)
    }

    companion object {
        /** @throws IllegalStateException if the model cannot be loaded. */
        fun load(modelPath: String): WhisperEngine {
            val native = NativeWhisper()
            val handle = native.nInit(modelPath)
            check(handle != 0L) { "טעינת המודל נכשלה: ${File(modelPath).name}" }
            return WhisperEngine(native, handle, File(modelPath).name)
        }

        fun systemInfo(): String = try {
            NativeWhisper().nSystemInfo()
        } catch (t: Throwable) {
            "לא זמין (${t.javaClass.simpleName})"
        }
    }
}
