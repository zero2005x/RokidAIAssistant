package com.example.rokidaiassistant.services

import com.example.rokidaiassistant.data.Constants
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
class AudioBufferManagerTest {

    private val manager = AudioBufferManager()

    /** Little-endian 16-bit PCM, the format the glasses stream. */
    private fun pcm(vararg samples: Int): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putShort(it.toShort()) }
        return bytes
    }

    private fun samplesOf(data: ByteArray): List<Int> {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return List(buffer.remaining()) { buffer.get().toInt() }
    }

    /** Loud enough to clear the silence threshold, and long enough to be valid. */
    private fun loudAudio(sampleCount: Int) = pcm(*IntArray(sampleCount) { 8_000 })

    @Test
    fun `audio is only buffered between start and stop`() {
        manager.write(pcm(1, 2))
        assertThat(manager.getAudioData()).isEmpty()
        assertThat(manager.isRecording()).isFalse()

        manager.startRecording()
        assertThat(manager.isRecording()).isTrue()
        manager.write(pcm(1, 2))
        assertThat(samplesOf(manager.getAudioData())).containsExactly(1, 2).inOrder()

        manager.stopRecording()
        assertThat(manager.isRecording()).isFalse()
        manager.write(pcm(3))
        assertThat(samplesOf(manager.getAudioData())).containsExactly(1, 2).inOrder()
    }

    @Test
    fun `a new recording discards what the previous one captured`() {
        manager.startRecording()
        manager.write(pcm(1, 2, 3))

        manager.startRecording()

        assertThat(manager.getAudioData()).isEmpty()
    }

    @Test
    fun `only the requested slice of a write is buffered`() {
        manager.startRecording()

        manager.write(pcm(1, 2, 3, 4), offset = 2, length = 4)

        assertThat(samplesOf(manager.getAudioData())).containsExactly(2, 3).inOrder()
    }

    @Test
    fun `duration is derived from the buffered byte count`() {
        manager.startRecording()
        // One second of 16-bit mono at the configured sample rate.
        manager.write(ByteArray(Constants.AUDIO_SAMPLE_RATE * 2))

        assertThat(manager.getDurationMs()).isEqualTo(1_000)
    }

    @Test
    fun `audio shorter than half a second is not usable`() {
        manager.startRecording()
        manager.write(loudAudio(Constants.AUDIO_SAMPLE_RATE / 8)) // ~125ms

        assertThat(manager.hasValidAudio()).isFalse()
    }

    @Test
    fun `long but silent audio is not usable`() {
        manager.startRecording()
        manager.write(pcm(*IntArray(Constants.AUDIO_SAMPLE_RATE) { 10 })) // ~1s of near-silence

        assertThat(manager.getDurationMs()).isAtLeast(500)
        assertThat(manager.hasValidAudio()).isFalse()
    }

    @Test
    fun `long audio above the silence threshold is usable`() {
        manager.startRecording()
        manager.write(loudAudio(Constants.AUDIO_SAMPLE_RATE))

        assertThat(manager.hasValidAudio()).isTrue()
    }

    @Test
    fun `an empty buffer is neither long enough nor audible`() {
        assertThat(manager.getDurationMs()).isEqualTo(0)
        assertThat(manager.hasValidAudio()).isFalse()
    }

    @Test
    fun `reset clears the buffer and leaves recording stopped`() {
        manager.startRecording()
        manager.write(pcm(1, 2))

        manager.reset()

        assertThat(manager.getAudioData()).isEmpty()
        assertThat(manager.isRecording()).isFalse()
    }

    @Test
    fun `quiet audio is amplified towards the target level`() {
        val quiet = pcm(2_000, -2_000, 1_000)

        val normalized = manager.normalize(quiet)

        // 30000 / 2000 = 15x, applied to every sample.
        assertThat(samplesOf(normalized)).containsExactly(30_000, -30_000, 15_000).inOrder()
    }

    @Test
    fun `audio that is already loud, too quiet, or empty is left alone`() {
        // Peak 30000 already: the factor is below the 1.1 threshold.
        val loud = pcm(30_000, -12_000)
        assertThat(manager.normalize(loud)).isSameInstanceAs(loud)

        // Peak under 1000: treated as too quiet to be worth amplifying.
        val tooQuiet = pcm(500, -400)
        assertThat(manager.normalize(tooQuiet)).isSameInstanceAs(tooQuiet)

        val empty = ByteArray(0)
        assertThat(manager.normalize(empty)).isSameInstanceAs(empty)
    }

    @Test
    fun `normalisation never overflows the sample range`() {
        val normalized = manager.normalize(pcm(1_100, -32_768, 32_767))

        assertThat(samplesOf(normalized).min()).isAtLeast(-32_768)
        assertThat(samplesOf(normalized).max()).isAtMost(32_767)
    }

    @Test
    fun `leading and trailing silence is trimmed with a little padding kept`() {
        val samples = IntArray(1_000) { 0 }
        samples[400] = 9_000
        samples[600] = 9_000

        val trimmed = manager.trimSilence(pcm(*samples))

        // Kept from 300 up to 700: 100 samples of padding on each side.
        assertThat(samplesOf(trimmed)).hasSize(400)
        assertThat(samplesOf(trimmed).count { it != 0 }).isEqualTo(2)
    }

    @Test
    fun `audio that is short or entirely silent is returned untrimmed`() {
        val short = pcm(*IntArray(100) { 9_000 })
        assertThat(manager.trimSilence(short)).isSameInstanceAs(short)

        // Nothing clears the threshold, so the whole span is kept.
        val silent = pcm(*IntArray(1_000) { 0 })
        assertThat(manager.trimSilence(silent)).isEqualTo(silent)
    }
}
