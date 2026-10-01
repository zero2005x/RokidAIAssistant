package com.example.rokidglasses.service

import com.google.ai.client.generativeai.Chat
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.GenerateContentResponse
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * Conversation handling in [GeminiAIService]: the history it sends, the reply it
 * returns, and what it does when the model call fails. The model itself is
 * substituted, so no request leaves the test.
 */
@RunWith(RobolectricTestRunner::class)
class GeminiAIServiceTest {

    private lateinit var chat: Chat
    private val historySlot = slot<List<Content>>()

    private fun reply(text: String?): GenerateContentResponse =
        mockk<GenerateContentResponse>(relaxed = true).also {
            every { it.text } returns text
        }

    @Before
    fun setUp() {
        chat = mockk(relaxed = true)
        mockkConstructor(GenerativeModel::class)
        every { anyConstructed<GenerativeModel>().startChat(capture(historySlot)) } returns chat
        coEvery { chat.sendMessage(any<String>()) } returns reply("Hello there.")
    }

    @After
    fun tearDown() = unmockkAll()

    private fun service(systemPrompt: String = "", ack: String = "") =
        GeminiAIService(apiKey = "gemini-key", systemPrompt = systemPrompt, systemPromptAck = ack)

    /** The text of each history turn, in order. */
    private fun sentHistory(): List<String> =
        historySlot.captured.flatMap { content ->
            content.parts.mapNotNull {
                (it as? com.google.ai.client.generativeai.type.TextPart)?.text
            }
        }

    @Test
    fun `a reply is returned to the caller`() = runBlocking {
        assertThat(service().chat("Hi")).isEqualTo("Hello there.")
    }

    @Test
    fun `a model that returns no text falls back to an apology`() = runBlocking {
        coEvery { chat.sendMessage(any<String>()) } returns reply(null)

        assertThat(service().chat("Hi")).contains("Sorry")
    }

    @Test
    fun `a failing model is reported rather than thrown`() = runBlocking {
        coEvery { chat.sendMessage(any<String>()) } throws IOException("no network")

        val answer = service().chat("Hi")

        assertThat(answer).contains("Sorry")
        assertThat(answer).contains("no network")
    }

    @Test
    fun `the first turn carries no history`() = runBlocking {
        service().chat("Hi")

        assertThat(sentHistory()).isEmpty()
    }

    @Test
    fun `a system prompt is replayed as a primed exchange`() = runBlocking {
        service(systemPrompt = "You are terse.", ack = "Understood.").chat("Hi")

        assertThat(sentHistory()).containsExactly("You are terse.", "Understood.").inOrder()
    }

    @Test
    fun `a system prompt without an acknowledgement is not sent`() = runBlocking {
        service(systemPrompt = "You are terse.", ack = "").chat("Hi")
        assertThat(sentHistory()).isEmpty()

        service(systemPrompt = "", ack = "Understood.").chat("Hi")
        assertThat(sentHistory()).isEmpty()
    }

    @Test
    fun `each turn is added to the history of the next`() = runBlocking {
        val service = service()

        service.chat("First question")
        service.chat("Second question")

        assertThat(sentHistory())
            .containsExactly("First question", "Hello there.").inOrder()
    }

    @Test
    fun `history is kept after the system prompt`() = runBlocking {
        val service = service(systemPrompt = "You are terse.", ack = "Understood.")

        service.chat("First question")
        service.chat("Second question")

        assertThat(sentHistory()).containsExactly(
            "You are terse.", "Understood.", "First question", "Hello there."
        ).inOrder()
    }

    @Test
    fun `only the last ten turns are replayed`() = runBlocking {
        val service = service()

        repeat(12) { service.chat("question $it") }
        service.chat("final")

        // Ten turns of two entries each; the earliest questions have been dropped.
        val history = sentHistory()
        assertThat(history).hasSize(20)
        assertThat(history).doesNotContain("question 0")
        assertThat(history).doesNotContain("question 1")
        assertThat(history.first()).isEqualTo("question 2")
    }

    @Test
    fun `clearing the history starts the conversation over`() = runBlocking {
        val service = service()
        service.chat("First question")

        service.clearHistory()
        service.chat("Fresh start")

        assertThat(sentHistory()).isEmpty()
    }
}
