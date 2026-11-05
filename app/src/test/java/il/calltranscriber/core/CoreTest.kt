package il.calltranscriber.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class RecordingNameParserTest {
    private val tz = TimeZone.getTimeZone("UTC")

    @Test
    fun samsungNameWithContact() {
        val p = RecordingNameParser.parse("Call recording John Smith_240115_143022.m4a", tz)
        assertEquals("John Smith", p.contact)
        // 2024-01-15 14:30:22 UTC
        assertEquals(1705329022000L, p.timestampMillis)
    }

    @Test
    fun samsungNameWithPhoneNumber() {
        val p = RecordingNameParser.parse("Call recording +972501234567_240115_143022.m4a", tz)
        assertEquals("+972501234567", p.contact)
    }

    @Test
    fun hebrewPrefixAndHebrewName() {
        val p = RecordingNameParser.parse("הקלטת שיחה ישראל ישראלי_251105_091500.m4a", tz)
        assertEquals("ישראל ישראלי", p.contact)
        assertTrue(p.timestampMillis != null)
    }

    @Test
    fun duplicateSuffixIsTolerated() {
        val p = RecordingNameParser.parse("Call recording Dana_240115_143022 (1).m4a", tz)
        assertEquals("Dana", p.contact)
    }

    @Test
    fun unknownFormatFallsBackToBaseName() {
        val p = RecordingNameParser.parse("memo.m4a", tz)
        assertEquals("memo", p.contact)
        assertNull(p.timestampMillis)
    }

    @Test
    fun namePrefixedLikeAKeywordIsKept() {
        val p = RecordingNameParser.parse("Call recording Callie_240115_143022.m4a", tz)
        assertEquals("Callie", p.contact)
    }

    @Test
    fun onlyPrefixGivesNullContact() {
        val p = RecordingNameParser.parse("Call recording_240115_143022.m4a", tz)
        assertNull(p.contact)
        assertTrue(p.timestampMillis != null)
    }

    @Test
    fun impossibleDateIsRejected() {
        val p = RecordingNameParser.parse("Call recording X_249999_999999.m4a", tz)
        assertNull(p.timestampMillis)
    }
}

class RecordingMatcherTest {
    @Test
    fun defaultMatchesSamsungCallFolderAndName() {
        val m = RecordingMatcher(RecordingMatcher.DEFAULT_PATTERN)
        assertTrue(m.matches("Recordings/Call/", "anything.m4a"))
        assertTrue(m.matches("Recordings/", "Call recording Bob_240101_101010.m4a"))
        assertTrue(m.matches(null, "הקלטת שיחה דנה_240101_101010.m4a"))
    }

    @Test
    fun defaultIgnoresOtherAudio() {
        val m = RecordingMatcher(RecordingMatcher.DEFAULT_PATTERN)
        assertFalse(m.matches("Recordings/Voice Recorder/", "Voice 001.m4a"))
        assertFalse(m.matches("Music/", "song.mp3"))
    }

    @Test
    fun invalidRegexFallsBackToDefault() {
        val m = RecordingMatcher("([unclosed")
        assertTrue(m.matches("Recordings/Call/", "x.m4a"))
    }
}

class ResamplerTest {
    private fun resampleAll(input: FloatArray, inRate: Int, outRate: Int, blockSize: Int): FloatArray {
        val r = Resampler(inRate, outRate)
        val out = ArrayList<Float>()
        var i = 0
        while (i < input.size) {
            val n = minOf(blockSize, input.size - i)
            r.process(input.copyOfRange(i, i + n), n) { out.add(it) }
            i += n
        }
        return out.toFloatArray()
    }

    private fun sine(freq: Double, rate: Int, seconds: Double) =
        FloatArray((rate * seconds).toInt()) { sin(2 * PI * freq * it / rate).toFloat() }

    @Test
    fun outputLengthMatchesRatio() {
        val input = sine(440.0, 48_000, 2.0)
        val out = resampleAll(input, 48_000, 16_000, 4096)
        assertTrue("length ${out.size}", abs(out.size - 32_000) <= 2)
    }

    @Test
    fun blockSizeDoesNotChangeResult() {
        val input = sine(300.0, 44_100, 1.0)
        val a = resampleAll(input, 44_100, 16_000, 100_000)
        val b = resampleAll(input, 44_100, 16_000, 333)
        assertEquals(a.size, b.size)
        for (i in a.indices) assertEquals(a[i], b[i], 1e-5f)
    }

    @Test
    fun lowFrequencyToneIsPreserved() {
        val input = sine(200.0, 48_000, 1.0)
        val out = resampleAll(input, 48_000, 16_000, 1024)
        val expected = sine(200.0, 16_000, 1.0)
        for (i in 10 until out.size - 10) assertEquals(expected[i], out[i], 0.05f)
    }

    @Test
    fun sameRateIsIdentity() {
        val input = sine(500.0, 16_000, 0.5)
        val out = resampleAll(input, 16_000, 16_000, 777)
        assertTrue(out.size >= input.size - 1)
        for (i in out.indices) assertEquals(input[i], out[i], 1e-5f)
    }

    @Test
    fun pcmBufferClampsAndGrows() {
        val buf = PcmBuffer(2)
        buf.append(2.0f)
        buf.append(-2.0f)
        buf.append(0.5f)
        val arr = buf.toShortArray()
        assertEquals(3, arr.size)
        assertEquals(32767, arr[0].toInt())
        assertEquals(-32768, arr[1].toInt())
        assertEquals(16383, arr[2].toInt())
    }
}

class ChunkPlannerTest {
    @Test
    fun shortAudioIsOneChunk() {
        val bounds = ChunkPlanner.plan(ShortArray(16_000 * 60))
        assertEquals(listOf(0, 16_000 * 60), bounds)
    }

    @Test
    fun cutsInsideTheSilentGapNearTheBoundary() {
        val rate = 16_000
        val total = rate * 1500 // 25 minutes
        val samples = ShortArray(total) { 8000 } // constant "speech"
        // silent gap 2 s long at t = 612 s (inside the +-20 s window around 600 s)
        val gapStart = rate * 612
        for (i in gapStart until gapStart + rate * 2) samples[i] = 0
        val bounds = ChunkPlanner.plan(samples, rate, targetChunkSec = 600, searchSec = 20)
        assertEquals(0, bounds.first())
        assertEquals(total, bounds.last())
        assertTrue(bounds.size >= 3)
        assertTrue("cut at ${bounds[1]}", bounds[1] in gapStart..(gapStart + rate * 2))
        // monotonic
        for (i in 1 until bounds.size) assertTrue(bounds[i] > bounds[i - 1])
    }
}

class TranscriptFormatterTest {
    @Test
    fun clockFormats() {
        assertEquals("0:00", TranscriptFormatter.formatClock(0))
        assertEquals("1:05", TranscriptFormatter.formatClock(65_000))
        assertEquals("1:01:01", TranscriptFormatter.formatClock(3_661_000))
        assertEquals("0:00", TranscriptFormatter.formatClock(-5))
    }

    @Test
    fun tidyDropsEmptyTagsAndCollapsesLoops() {
        val raw = listOf(
            Segment(0, 1000, "  שלום   מה שלומך "),
            Segment(1000, 2000, "[מוזיקה]"),
            Segment(2000, 3000, ""),
            Segment(3000, 4000, "תודה"),
            Segment(4000, 5000, "תודה"),
            Segment(5000, 6000, "תודה"),
        )
        val out = TranscriptFormatter.tidy(raw)
        assertEquals(listOf("שלום מה שלומך", "תודה"), out.map { it.text })
        assertEquals(6000L, out[1].endMs)
    }

    @Test
    fun buildContainsHeaderAndTimestamps() {
        val meta = TranscriptMeta("דנה", 1705329022000L, 125_000, "x.m4a", "model.bin", 61_000)
        val text = TranscriptFormatter.build(meta, listOf(Segment(0, 1000, "שלום"), Segment(70_000, 71_000, "להתראות")), true)
        assertTrue(text.contains("איש קשר: דנה"))
        assertTrue(text.contains("משך ההקלטה: 2:05"))
        assertTrue(text.contains("[0:00] שלום"))
        assertTrue(text.contains("[1:10] להתראות"))
    }

    @Test
    fun buildWithoutTimestampsJoinsText() {
        val meta = TranscriptMeta(null, 0, 1000, "x.m4a", "m", 0)
        val text = TranscriptFormatter.build(meta, listOf(Segment(0, 1, "א"), Segment(1, 2, "ב")), false)
        assertTrue(text.contains("איש קשר: לא ידוע"))
        assertTrue(text.contains("א ב"))
        assertFalse(text.contains("[0:00]"))
    }

    @Test
    fun emptyTranscriptSaysSo() {
        val meta = TranscriptMeta("x", 0, 1000, "x.m4a", "m", 0)
        assertTrue(TranscriptFormatter.build(meta, emptyList(), true).contains("לא זוהה דיבור"))
    }

    @Test
    fun safeFileFragmentKeepsHebrewAndStripsSeparators() {
        assertEquals("ישראל_ישראלי", TranscriptFormatter.safeFileFragment("ישראל ישראלי"))
        assertEquals("a_b", TranscriptFormatter.safeFileFragment("a/b"))
        assertEquals("unknown", TranscriptFormatter.safeFileFragment(null))
        assertEquals("unknown", TranscriptFormatter.safeFileFragment("///"))
    }

    @Test
    fun wordCountCountsWords() {
        assertEquals(3, TranscriptFormatter.wordCount(listOf(Segment(0, 1, "אחת שתיים"), Segment(1, 2, "שלוש"))))
    }
}
