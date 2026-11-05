package il.calltranscriber.whisper

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import il.calltranscriber.core.PcmBuffer
import il.calltranscriber.core.Resampler
import java.io.IOException
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

/** Decodes any audio file Android can play (m4a/AAC, AMR, MP3, ...) to 16 kHz mono 16-bit PCM. */
object AudioDecoder {
    const val TARGET_RATE = 16_000
    private const val TIMEOUT_US = 10_000L

    private fun MediaFormat.intOr(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default

    /**
     * @param isCancelled polled between codec buffers; when it returns true a
     *        [CancellationException] is thrown.
     */
    fun decodeTo16kMono(context: Context, uri: Uri, isCancelled: () -> Boolean = { false }): ShortArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)

            var track = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                if (mime != null && mime.startsWith("audio/")) {
                    track = i
                    break
                }
            }
            if (track < 0) throw IOException("בקובץ אין ערוץ שמע")
            extractor.selectTrack(track)

            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IOException("סוג קובץ לא מזוהה")
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            var sampleRate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, 16_000)
            var channels = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, 1).coerceAtLeast(1)
            var floatPcm = false
            var resampler = Resampler(sampleRate, TARGET_RATE)
            val out = PcmBuffer(TARGET_RATE * 120)
            var mono = FloatArray(16_384)

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idleTicks = 0

            while (!outputDone) {
                if (isCancelled()) throw CancellationException("cancelled")

                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = decoder.getInputBuffer(inIndex) ?: throw IOException("no input buffer")
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex >= 0 -> {
                        idleTicks = 0
                        if (info.size > 0) {
                            val buf = decoder.getOutputBuffer(outIndex) ?: throw IOException("no output buffer")
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.order(ByteOrder.nativeOrder())
                            val frames: Int
                            if (floatPcm) {
                                val fb = buf.asFloatBuffer()
                                frames = fb.remaining() / channels
                                if (mono.size < frames) mono = FloatArray(frames)
                                for (f in 0 until frames) {
                                    var sum = 0f
                                    for (c in 0 until channels) sum += fb.get()
                                    mono[f] = sum / channels
                                }
                            } else {
                                val sb = buf.asShortBuffer()
                                frames = sb.remaining() / channels
                                if (mono.size < frames) mono = FloatArray(frames)
                                for (f in 0 until frames) {
                                    var sum = 0f
                                    for (c in 0 until channels) sum += sb.get() / 32768f
                                    mono[f] = sum / channels
                                }
                            }
                            resampler.process(mono, frames) { out.append(it) }
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }

                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = decoder.outputFormat
                        floatPcm = f.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) ==
                            AudioFormat.ENCODING_PCM_FLOAT
                        channels = f.intOr(MediaFormat.KEY_CHANNEL_COUNT, channels).coerceAtLeast(1)
                        val newRate = f.intOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        if (newRate != sampleRate) {
                            sampleRate = newRate
                            resampler = Resampler(sampleRate, TARGET_RATE)
                        }
                    }

                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // Guard against decoders that never signal end-of-stream.
                        if (inputDone && ++idleTicks > 300) outputDone = true
                    }
                }
            }

            if (out.size == 0) throw IOException("לא הצלחתי לפענח אודיו מהקובץ")
            return out.toShortArray()
        } finally {
            try {
                codec?.stop()
            } catch (e: Exception) {
                // stop() throws if the codec never started; release() below still cleans up.
            }
            codec?.release()
            extractor.release()
        }
    }
}
