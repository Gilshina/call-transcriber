package il.calltranscriber.whisper;

/** Java twin of the Kotlin interface, so the JNI library can be exercised on a plain JVM in CI. */
public interface WhisperCallback {
    void onProgress(int percent);

    void onSegment(long startMs, long endMs, byte[] utf8Text);
}
