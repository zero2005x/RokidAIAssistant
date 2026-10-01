package com.example.rokidaiassistant.services

import com.example.rokidaiassistant.data.Constants
import com.google.ai.client.generativeai.Chat
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.GenerateContentResponse
import com.google.ai.client.generativeai.type.TextPart
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * Conversation and single-shot generation in [GeminiService]. The model is
 * substituted, so no request leaves the test.
 */
@RunWith(RobolectricTestRunner::class)
class GeminiServiceTest {

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
        coEvery { chat.sendMessage(any<String>()) } returns reply("  A tidy answer.  ")
        coEvery {
            anyConstructed<GenerativeModel>().generateContent(any<String>())
        } returns reply("  One-shot answer.  ")
    }

    @After
    fun tearDown() = unmockkAll()

    private fun historyText(): List<String> =
        historySlot.captured.flatMap { content ->
            content.parts.mapNotNull { (it as? TextPart)?.text }
        }

    // ==================== Conversation ====================

    @Test
    fun `a reply is trimmed before it is returned`() = runBlocking {
        val result = GeminiService().sendMessage("Hi")

        assertThat(result.getOrNull()).isEqualTo("A tidy answer.")
    }

    @Test
    fun `an empty reply becomes a spoken apology rather than a failure`() = runBlocking {
        for (empty in listOf(null, "", "   ")) {
            coEvery { chat.sendMessage(any<String>()) } returns reply(empty)

            val result = GeminiService().sendMessage("Hi")

            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrNull()).contains("Sorry")
        }
    }

    @Test
    fun `a failing call is reported as a failure`() = runBlocking {
        coEvery { chat.sendMessage(any<String>()) } throws IOException("no network")

        val result = GeminiService().sendMessage("Hi")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageThat().isEqualTo("no network")
    }

    @Test
    fun `the session is primed with the system prompt`() = runBlocking {
        GeminiService().sendMessage("Hi")

        val history = historyText()
        assertThat(history).hasSize(2)
        assertThat(history[0]).contains(Constants.SYSTEM_PROMPT)
        assertThat(history[1]).isNotEmpty()
    }

    // ==================== Single shot ====================

    @Test
    fun `a one-shot answer is trimmed before it is returned`() = runBlocking {
        val result = GeminiService().generateContent("What is this?")

        assertThat(result.getOrNull()).isEqualTo("One-shot answer.")
    }

    @Test
    fun `a one-shot call with no text falls back to an apology`() = runBlocking {
        coEvery {
            anyConstructed<GenerativeModel>().generateContent(any<String>())
        } returns reply(null)

        val result = GeminiService().generateContent("What is this?")

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).contains("Sorry")
    }

    @Test
    fun `a failing one-shot call is reported as a failure`() = runBlocking {
        coEvery {
            anyConstructed<GenerativeModel>().generateContent(any<String>())
        } throws IOException("quota exceeded")

        val result = GeminiService().generateContent("What is this?")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageThat().isEqualTo("quota exceeded")
    }

    // ==================== Reset ====================

    @Test
    fun `resetting starts a fresh session`() = runBlocking {
        val service = GeminiService()
        service.sendMessage("Hi")

        service.resetConversation()

        // One session at construction, one more after the reset.
        verify(atLeast = 2) { anyConstructed<GenerativeModel>().startChat(any()) }
        // The new session is primed the same way.
        assertThat(historyText()[0]).contains(Constants.SYSTEM_PROMPT)
    }
}
