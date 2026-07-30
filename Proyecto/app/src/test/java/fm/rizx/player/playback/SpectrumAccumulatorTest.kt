package fm.rizx.player.playback

import fm.rizx.player.domain.usecase.AutoEqCurves
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** The measurement behind "a curve for *this* song" — the only part of the audio tap that has real math. */
class SpectrumAccumulatorTest {

    private val sampleRate = 44_100

    private fun tone(hz: Double, amplitude: Float = 0.5f) = FloatArray(SpectrumAccumulator.FFT_SIZE) {
        (amplitude * sin(2.0 * PI * hz * it / sampleRate)).toFloat()
    }

    private fun accumulator() = SpectrumAccumulator().apply { sampleRateHz = sampleRate }

    private fun measure(frame: FloatArray, frames: Int = SpectrumAccumulator.MIN_FRAMES): FloatArray? {
        val acc = accumulator()
        repeat(frames) { acc.addFrame(frame) }
        return acc.measurement()
    }

    @Test
    fun `silence is skipped rather than averaged in`() {
        val acc = accumulator()
        repeat(SpectrumAccumulator.MIN_FRAMES * 2) { assertTrue(!acc.addFrame(FloatArray(SpectrumAccumulator.FFT_SIZE))) }

        assertEquals(0, acc.frames)
        assertNull("silence must not produce a measurement", acc.measurement())
    }

    @Test
    fun `a short listen is no answer at all`() {
        // Half the frames it asks for: one chorus must not be allowed to define a song.
        assertNull(measure(tone(125.0), frames = SpectrumAccumulator.MIN_FRAMES / 2))
    }

    @Test
    fun `a tone lands on its own anchor`() {
        val bass = measure(tone(125.0))!!
        val treble = measure(tone(4_000.0))!!

        assertEquals(125, AutoEqCurves.ANCHORS_HZ[bass.indices.maxBy { bass[it] }])
        assertEquals(4_000, AutoEqCurves.ANCHORS_HZ[treble.indices.maxBy { treble[it] }])
    }

    @Test
    fun `the measurement describes balance, not loudness`() {
        val loud = measure(tone(1_000.0, amplitude = 0.9f))!!
        val quiet = measure(tone(1_000.0, amplitude = 0.05f))!!

        // Mean-zero by construction, so a quiet song and a loud one with the same balance agree — which is
        // what lets the correction be compared against a fixed reference at all.
        assertEquals(0f, loud.sum(), 0.01f)
        assertEquals(loud.indices.maxBy { loud[it] }, quiet.indices.maxBy { quiet[it] })
        // Within a dB, not exact: the log floor that keeps an empty band out of log10(0) lifts the bands
        // with no energy in them, and it lifts them relatively more when the whole song is quiet.
        loud.forEachIndexed { i, value -> assertEquals(value, quiet[i], 1.5f) }
    }

    @Test
    fun `one measurement per anchor, and reset starts the next song clean`() {
        val acc = accumulator()
        repeat(SpectrumAccumulator.MIN_FRAMES) { acc.addFrame(tone(125.0)) }
        assertEquals(AutoEqCurves.ANCHORS_HZ.size, acc.measurement()!!.size)

        acc.reset()

        assertEquals(0, acc.frames)
        assertNull(acc.measurement())
    }

    @Test
    fun `a frame of the wrong size is refused, not misread`() {
        assertTrue(!accumulator().addFrame(FloatArray(64) { 0.5f }))
    }
}
