import il.calltranscriber.whisper.NativeWhisper;
import il.calltranscriber.whisper.WhisperCallback;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Exercises the JNI bridge with whisper.cpp's tiny random-weight test model: load, transcribe with
 * greedy/beam, with/without VAD, repeated calls, cancellation, bad input. Output text is garbage by
 * design; the point is that nothing crashes and the callbacks fire.
 */
public class Smoke {
    static int progressCalls, segmentCalls;

    static final WhisperCallback CB = new WhisperCallback() {
        @Override public void onProgress(int percent) { progressCalls++; }
        @Override public void onSegment(long startMs, long endMs, byte[] utf8Text) {
            segmentCalls++;
            new String(utf8Text, java.nio.charset.StandardCharsets.UTF_8);
        }
    };

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) { System.out.println("SMOKE FAILED"); System.exit(1); }
    }

    /** 16-bit PCM mono WAV -> float[-1,1]; finds the "data" chunk. */
    static float[] readWav(String path) throws Exception {
        byte[] b = Files.readAllBytes(Paths.get(path));
        int pos = 12;
        while (pos + 8 <= b.length) {
            String id = new String(b, pos, 4, "US-ASCII");
            int len = ByteBuffer.wrap(b, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (id.equals("data")) {
                int n = Math.min(len, b.length - pos - 8) / 2;
                float[] out = new float[n];
                ByteBuffer bb = ByteBuffer.wrap(b, pos + 8, n * 2).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < n; i++) out[i] = bb.getShort() / 32768f;
                return out;
            }
            pos += 8 + len;
        }
        throw new IllegalStateException("no data chunk");
    }

    public static void main(String[] a) throws Exception {
        String model = a[0], vad = a[1];
        float[] jfk = readWav(a[2]);
        System.out.println("audio: " + jfk.length / 16000.0 + " s");

        NativeWhisper w = new NativeWhisper();
        System.out.println("system info: " + w.nSystemInfo());

        check(w.nInit("/nonexistent/model.bin") == 0, "loading a missing model returns 0 instead of crashing");
        long h = w.nInit(model);
        check(h != 0, "model loads");

        for (int beam : new int[]{1, 5}) {
            for (String v : new String[]{"", vad}) {
                progressCalls = 0; segmentCalls = 0;
                int rc = w.nTranscribe(h, jfk, jfk.length, "he", 4, beam, v, CB);
                System.out.println("      beam=" + beam + " vad=" + !v.isEmpty() + " rc=" + rc
                        + " progress=" + progressCalls + " segments=" + segmentCalls);
                check(rc == 0, "transcribe ok (beam=" + beam + ", vad=" + !v.isEmpty() + ")");
            }
        }

        // ~70 s: several 30 s windows, and the same context reused for a second call.
        float[] longAudio = new float[jfk.length * 6];
        for (int i = 0; i < 6; i++) System.arraycopy(jfk, 0, longAudio, i * jfk.length, jfk.length);
        for (int run = 0; run < 2; run++) {
            int rc = w.nTranscribe(h, longAudio, longAudio.length, "he", 4, 1, vad, CB);
            check(rc == 0, "long audio, run " + (run + 1));
        }

        check(w.nTranscribe(h, new float[16000], 16000, "he", 2, 1, "", CB) == 0, "1 s of silence");

        // Cancellation: a cancelled context must return promptly with a non-zero status, not crash.
        w.nCancel(h);
        int rc = w.nTranscribe(h, longAudio, longAudio.length, "he", 2, 1, "", CB);
        System.out.println("      after cancel rc=" + rc);
        check(rc != 0, "cancelled transcription returns an error status");

        w.nFree(h);
        w.nCancel(0);
        w.nFree(0);
        check(w.nTranscribe(0, jfk, jfk.length, "he", 2, 1, "", CB) < 0, "invalid handle is rejected");
        System.out.println("SMOKE OK");
    }
}
