package io.github.zero2005x.glassesaicompanion.service.ai

import io.github.zero2005x.glassesaicompanion.ai.catalog.ProviderErrorKind
import io.github.zero2005x.glassesaicompanion.data.AiProvider
import io.github.zero2005x.glassesaicompanion.service.SpeechResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AiServiceProviderTest {
    private fun service(reply: suspend (String) -> String) = object : AiServiceProvider {
        override val provider = AiProvider.GEMINI
        override suspend fun chat(userMessage: String) = reply(userMessage)
        override suspend fun transcribe(pcmAudioData: ByteArray, languageCode: String): SpeechResult =
            error("Unexpected transcription")
        override suspend fun analyzeImage(imageData: ByteArray, prompt: String): String =
            error("Unexpected image analysis")
        override fun clearHistory() = Unit
    }

    @Test
    fun `compatibility stream forwards prompt and emits answer before completion`() = runTest {
        val provider = service { prompt ->
            assertEquals("question", prompt)
            "answer"
        }
        assertEquals(
            listOf(AiStreamEvent.TextDelta("answer"), AiStreamEvent.Completed("answer")),
            provider.streamChat("question").toList()
        )
    }

    @Test
    fun `chat failures produce one error without a completion event`() = runTest {
        listOf(IllegalStateException("offline"), IllegalStateException()).forEach { failure ->
            val events = service { throw failure }.streamChat("question").toList()
            assertEquals(listOf(AiStreamEvent.Error(ProviderErrorKind.UNKNOWN,
                failure.message ?: "chat failed")), events)
        }
    }

    @Test
    fun `cancellation propagates without becoming a provider error`() = runTest {
        val cancellation = CancellationException("user cancelled")
        val events = mutableListOf<AiStreamEvent>()
        try {
            service { throw cancellation }.streamChat("question").toList(events)
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
        assertTrue(events.isEmpty())
    }

    @Test
    fun `encoded audio is explicitly unsupported by default`() = runTest {
        try {
            service { "unused" }.transcribeAudioFile(byteArrayOf(1), "audio/mp4")
            fail("Expected unsupported encoded audio")
        } catch (actual: UnsupportedOperationException) {
            assertTrue(actual.message.orEmpty().contains("audio/mp4"))
        }
    }
}
