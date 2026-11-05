package il.calltranscriber.whisper;

/** Java twin of NativeWhisper.kt: same package, class and method signatures as the JNI functions. */
public class NativeWhisper {
    public native long nInit(String modelPath);

    public native void nFree(long handle);

    public native void nCancel(long handle);

    public native int nTranscribe(long handle, float[] samples, int nSamples, String language,
                                  int threads, int beamSize, String vadModelPath, WhisperCallback callback);

    public native String nSystemInfo();

    static {
        System.loadLibrary("calltranscriber");
    }
}
