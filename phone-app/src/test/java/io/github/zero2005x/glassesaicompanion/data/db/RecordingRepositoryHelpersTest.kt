package io.github.zero2005x.glassesaicompanion.data.db

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecordingRepositoryHelpersTest {

    @Test
    fun `escapeLikeQuery escapes wildcard and escape characters`() {
        assertThat(RecordingQueryEscaper.escapeLikeQuery("""100%_done\ready"""))
            .isEqualTo("""100\%\_done\\ready""")
    }

    @Test
    fun `intToBytes encodes little endian integers`() {
        assertThat(WavEncoder.intToBytes(0x12345678.toInt(), 4).toList())
            .containsExactly(0x78.toByte(), 0x56.toByte(), 0x34.toByte(), 0x12.toByte())
            .inOrder()
    }

    @Test
    fun `pcmToWav writes riff header fmt chunk and payload`() {
        val pcm = byteArrayOf(1, 2, 3, 4)

        val wav = WavEncoder.pcmToWav(pcm, sampleRate = 16_000, channels = 1, bitsPerSample = 16)

        assertThat(String(wav.copyOfRange(0, 4))).isEqualTo("RIFF")
        assertThat(String(wav.copyOfRange(8, 12))).isEqualTo("WAVE")
        assertThat(String(wav.copyOfRange(12, 16))).isEqualTo("fmt ")
        assertThat(String(wav.copyOfRange(36, 40))).isEqualTo("data")
        assertThat(wav.copyOfRange(44, wav.size).toList()).containsExactlyElementsIn(pcm.toList()).inOrder()
        assertThat(wav.size).isEqualTo(44 + pcm.size)
    }
}
