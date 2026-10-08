package io.github.zero2005x.glassesaicompanion.service.ai

import com.google.common.truth.Truth.assertThat
import okio.Buffer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SseParserTest {

    @Test
    fun `readEvents collects event names data lines and done markers`() {
        val source = Buffer().writeUtf8(
            """
            : keepalive
            event: delta
            data: first line
            data: second line
            ignored: value

            data: [DONE]

            """.trimIndent()
        )

        val events = mutableListOf<SseParser.SseEvent>()
        SseParser.readEvents(source) { events += it }

        assertThat(events).hasSize(2)
        assertThat(events[0]).isEqualTo(SseParser.SseEvent.Data("first line\nsecond line", "delta"))
        assertThat(events[1]).isEqualTo(SseParser.SseEvent.Done)
    }

    @Test
    fun `readEvents dispatches final unterminated event at end of stream`() {
        val source = Buffer().writeUtf8(
            """
            data: trailing payload
            event: ignored-after-data
            """.trimIndent()
        )

        val events = mutableListOf<SseParser.SseEvent>()
        SseParser.readEvents(source) { events += it }

        assertThat(events).containsExactly(
            SseParser.SseEvent.Data("trailing payload", "ignored-after-data")
        )
    }
}
