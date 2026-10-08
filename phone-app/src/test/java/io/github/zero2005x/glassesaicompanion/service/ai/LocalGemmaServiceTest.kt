package io.github.zero2005x.glassesaicompanion.service.ai

import io.github.zero2005x.glassesaicompanion.data.AiProvider
import io.github.zero2005x.glassesaicompanion.service.SpeechErrorCode
import io.github.zero2005x.glassesaicompanion.service.SpeechResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * [LocalGemmaService] contract: text chat delegates to the engine; every
 * unsupported capability degrades gracefully (never throws); no engine means a
 * clear, actionable message instead of a silent cloud fallback.
 */
class LocalGemmaServiceTest {

    private class FakeEngine(
        override val modelId: String = "gemma-3n-E2B-it",
        private val chunks: List<String> = listOf("Hello", " world")
    ) : LocalInferenceEngine {
        var resetCount = 0
        override suspend fun generate(prompt: String): String = chunks.joinToString("")
        override fun generateStream(prompt: String): Flow<String> = flow {
            chunks.forEach { emit(it) }
        }
        override fun reset() { resetCount++ }
    }

    @Test
    fun `provider identity is LOCAL_GEMMA`() {
        assertThat(LocalGemmaService("gemma-3n-E2B-it").provider).isEqualTo(AiProvider.LOCAL_GEMMA)
    }

    @Test
    fun `chat delegates to the engine when present`() = runTest {
        val service = LocalGemmaService("gemma-3n-E2B-it", engine = FakeEngine())
        assertThat(service.chat("hi")).isEqualTo("Hello world")
    }

    @Test
    fun `chat without engine returns actionable message rather than throwing`() = runTest {
        val service = LocalGemmaService("gemma-3n-E2B-it", engine = null)
        val reply = service.chat("hi")
        assertThat(reply).contains("on-device model")
    }

    @Test
    fun `streamChat maps engine tokens to delta and completed events`() = runTest {
        val service = LocalGemmaService("gemma-3n-E2B-it", engine = FakeEngine())
        val events = service.streamChat("hi").toList()

        val deltas = events.filterIsInstance<AiStreamEvent.TextDelta>().map { it.text }
        assertThat(deltas).containsExactly("Hello", " world").inOrder()
        val completed = events.filterIsInstance<AiStreamEvent.Completed>().single()
        assertThat(completed.fullText).isEqualTo("Hello world")
    }

    @Test
    fun `streamChat without engine emits an error event`() = runTest {
        val service = LocalGemmaService("gemma-3n-E2B-it", engine = null)
        val events = service.streamChat("hi").toList()
        assertThat(events.filterIsInstance<AiStreamEvent.Error>()).hasSize(1)
    }

    @Test
    fun `transcribe is unsupported and reported as NOT_SUPPORTED`() = runTest {
        val service = LocalGemmaService("gemma-3n-E2B-it")
        val result = service.transcribe(ByteArray(10))
        assertThat(result).isInstanceOf(SpeechResult.Error::class.java)
        assertThat((result as SpeechResult.Error).errorCode).isEqualTo(SpeechErrorCode.NOT_SUPPORTED)
    }

    @Test
    fun `analyzeImage returns a clear text-only message`() = runTest {
        val service = LocalGemmaService("gemma-3n-E2B-it")
        assertThat(service.analyzeImage(ByteArray(10))).contains("text-only")
    }

    @Test
    fun `clearHistory resets the engine`() {
        val engine = FakeEngine()
        LocalGemmaService("gemma-3n-E2B-it", engine = engine).clearHistory()
        assertThat(engine.resetCount).isEqualTo(1)
    }
}
