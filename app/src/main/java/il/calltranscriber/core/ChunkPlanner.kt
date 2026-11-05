package il.calltranscriber.core

import kotlin.math.abs

/**
 * Splits long audio into chunks for Whisper, cutting at the quietest point near
 * each nominal boundary so words are not sliced in half. Chunking only bounds
 * memory and gives progress; Whisper itself works on 30 s windows internally.
 */
object ChunkPlanner {
    private const val FRAME_MS = 100

    /**
     * @return boundaries `[0, b1, ..., samples.size]`; chunk i is `[b[i], b[i+1])`.
     */
    fun plan(
        samples: ShortArray,
        sampleRate: Int = 16_000,
        targetChunkSec: Int = 600,
        searchSec: Int = 20,
    ): List<Int> {
        val total = samples.size
        val bounds = ArrayList<Int>()
        bounds.add(0)
        val target = targetChunkSec * sampleRate
        val search = searchSec * sampleRate
        var pos = 0
        while (total - pos > target + search) {
            val nominal = pos + target
            val cut = quietestPoint(samples, nominal - search, nominal + search, sampleRate)
            bounds.add(cut)
            pos = cut
        }
        bounds.add(total)
        return bounds
    }

    /** Start of the lowest-energy 100 ms frame within [from, to), as a sample index. */
    fun quietestPoint(samples: ShortArray, from: Int, to: Int, sampleRate: Int): Int {
        val frame = sampleRate * FRAME_MS / 1000
        val lo = from.coerceAtLeast(0)
        val hi = to.coerceAtMost(samples.size - frame)
        if (hi <= lo) return (from + to) / 2
        var bestStart = lo
        var bestEnergy = Long.MAX_VALUE
        var start = lo
        while (start <= hi) {
            var energy = 0L
            for (i in start until start + frame) energy += abs(samples[i].toInt())
            if (energy < bestEnergy) {
                bestEnergy = energy
                bestStart = start
            }
            start += frame
        }
        return bestStart + frame / 2
    }
}
