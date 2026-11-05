package il.calltranscriber.core

import kotlin.math.floor

/**
 * Streaming linear-interpolation resampler for mono float audio. Call
 * [process] repeatedly with consecutive blocks; state is carried across calls.
 *
 * Linear interpolation is adequate here: call audio is band-limited well below
 * the 8 kHz Nyquist of the 16 kHz Whisper input.
 */
class Resampler(inRate: Int, outRate: Int) {
    private val ratio = inRate.toDouble() / outRate.toDouble()
    private var nextOutPos = 0.0   // position of next output sample, in global input-sample coordinates
    private var consumed = 0L      // global index of input[0] of the next block
    private var last = 0f          // last sample of the previous block

    init {
        require(inRate > 0 && outRate > 0) { "sample rates must be positive" }
    }

    fun process(input: FloatArray, n: Int, sink: (Float) -> Unit) {
        if (n <= 0) return
        val base = consumed
        while (true) {
            val i0 = floor(nextOutPos).toLong()
            val i1 = i0 + 1
            if (i1 > base + n - 1) break
            val frac = (nextOutPos - i0).toFloat()
            val a = if (i0 < base) last else input[(i0 - base).toInt()]
            val b = input[(i1 - base).toInt()]
            sink(a + (b - a) * frac)
            nextOutPos += ratio
        }
        last = input[n - 1]
        consumed += n
    }
}

/** Growable 16-bit PCM buffer. */
class PcmBuffer(initialCapacity: Int = 1 shl 16) {
    private var data = ShortArray(initialCapacity)
    var size = 0
        private set

    fun append(sample: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        val scaled = (sample * 32767f).toInt().coerceIn(-32768, 32767)
        data[size++] = scaled.toShort()
    }

    fun toShortArray(): ShortArray = data.copyOf(size)
}
