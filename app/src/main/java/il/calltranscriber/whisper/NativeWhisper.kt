package il.calltranscriber.whisper

/** Called from native code while a chunk is being transcribed. */
interface WhisperCallback {
    /** 0..100 for the current chunk. */
    fun onProgress(percent: Int)

    /** Times in milliseconds relative to the start of the chunk; text is raw UTF-8. */
    fun onSegment(startMs: Long, endMs: Long, utf8Text: ByteArray)
}

/**
 * JNI surface of libcalltranscriber.so (see app/src/main/cpp/whisper_jni.cpp).
 * Method names/signatures must stay in sync with the C++ side.
 */
class NativeWhisper {
    external fun nInit(modelPath: String): Long
    external fun nFree(handle: Long)
    external fun nCancel(handle: Long)
    external fun nTranscribe(
        handle: Long,
        samples: FloatArray,
        nSamples: Int,
        language: String,
        threads: Int,
        beamSize: Int,
        vadModelPath: String,
        callback: WhisperCallback,
    ): Int

    external fun nSystemInfo(): String

    companion object {
        init {
            System.loadLibrary("calltranscriber")
        }
    }
}
