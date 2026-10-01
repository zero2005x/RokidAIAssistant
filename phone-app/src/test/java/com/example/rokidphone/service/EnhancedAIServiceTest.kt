package com.example.rokidphone.service

import android.content.Context
import com.example.rokidphone.ai.provider.ProviderManager
import com.example.rokidphone.data.AiProvider
import com.example.rokidphone.data.ApiSettings
import com.example.rokidphone.data.SettingsRepository
import com.example.rokidphone.data.db.Conversation
import com.example.rokidphone.data.db.ConversationRepository
import com.example.rokidphone.service.ai.AiServiceProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class EnhancedAIServiceTest {

    private val context = mockk<Context>(relaxed = true)
    private val providerManager = mockk<ProviderManager>(relaxed = true)
    private val conversations = mockk<ConversationRepository>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val aiService = mockk<AiServiceProvider>(relaxed = true)
    private lateinit var service: EnhancedAIService

    private fun conversation(id: String) = Conversation(
        id = id, title = "t", providerId = "p", modelId = "m", systemPrompt = "",
        createdAt = 0, updatedAt = 0, messageCount = 0, isArchived = false, isPinned = false
    )

    @Before
    fun setUp() {
        mockkObject(
            ProviderManager.Companion, ConversationRepository.Companion, SettingsRepository.Companion
        )
        every { ProviderManager.getInstance(any()) } returns providerManager
        every { ConversationRepository.getInstance(any()) } returns conversations
        every { SettingsRepository.getInstance(any()) } returns settingsRepository
        every { settingsRepository.getSettings() } returns
            ApiSettings(aiProvider = AiProvider.OPENAI, aiModelId = "gpt-4", systemPrompt = "be brief")
        coEvery { providerManager.getActiveService() } returns aiService
        coEvery { conversations.getMessageCount(any()) } returns 2
        service = EnhancedAIService(context)
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `a text message is persisted with its answer and titles a fresh conversation`() = runTest {
        coEvery { aiService.chat("hello") } returns "hi there"

        val result = service.sendMessage("c1", "hello")

        assertThat(result.getOrNull()).isEqualTo("hi there")
        coVerify { conversations.addUserMessage("c1", "hello", null) }
        coVerify { conversations.addAssistantMessage("c1", "hi there", "gpt-4", any(), any()) }
        coVerify { conversations.autoGenerateTitle("c1") }
    }

    @Test
    fun `an established conversation keeps the title it already has`() = runTest {
        coEvery { conversations.getMessageCount("c1") } returns 3
        coEvery { aiService.chat(any()) } returns "answer"

        service.sendMessage("c1", "hello")

        coVerify(exactly = 0) { conversations.autoGenerateTitle(any()) }
    }

    @Test
    fun `an image message is routed to vision analysis`() = runTest {
        val image = byteArrayOf(1, 2, 3)
        coEvery { aiService.analyzeImage(image, "what is this?") } returns "a cat"

        assertThat(service.sendMessage("c1", "what is this?", image).getOrNull()).isEqualTo("a cat")
        coVerify(exactly = 0) { aiService.chat(any()) }
    }

    @Test
    fun `an unconfigured provider fails before any message is persisted`() = runTest {
        coEvery { providerManager.getActiveService() } returns null

        val result = service.sendMessage("c1", "hello")

        assertThat(result.exceptionOrNull()!!).hasMessageThat().contains("not configured")
        coVerify(exactly = 0) { conversations.addUserMessage(any(), any(), any()) }
    }

    @Test
    fun `a provider error is returned as a failed result`() = runTest {
        coEvery { aiService.chat(any()) } throws IOException("upstream down")

        val result = service.sendMessage("c1", "hello")

        assertThat(result.exceptionOrNull()).hasMessageThat().isEqualTo("upstream down")
    }

    @Test
    fun `streaming emits start then the completed answer exactly once`() = runTest {
        coEvery { aiService.chat("hello") } returns "streamed answer"

        val emissions = service.sendMessageStream("c1", "hello").toList()

        assertThat(emissions).containsExactly(
            StreamResult.Started, StreamResult.Completed("streamed answer")
        ).inOrder()
        coVerify { conversations.addAssistantMessage("c1", "streamed answer", "gpt-4", any(), any()) }
    }

    @Test
    fun `streaming reports an unconfigured provider and upstream errors as events`() = runTest {
        coEvery { providerManager.getActiveService() } returns null
        assertThat(service.sendMessageStream("c1", "hello").toList())
            .containsExactly(StreamResult.Started, StreamResult.Error("AI service not configured"))
            .inOrder()

        coEvery { providerManager.getActiveService() } returns aiService
        coEvery { aiService.chat(any()) } throws IOException("upstream down")
        assertThat(service.sendMessageStream("c1", "hello").toList())
            .containsExactly(StreamResult.Started, StreamResult.Error("upstream down")).inOrder()
    }

    @Test
    fun `quick chat, image analysis and transcription bypass history`() = runTest {
        coEvery { aiService.chat("ping") } returns "pong"
        coEvery { aiService.analyzeImage(any(), any()) } returns "a photo"
        coEvery { aiService.transcribe(any(), any()) } returns SpeechResult.Success("spoken words")

        assertThat(service.quickChat("ping").getOrNull()).isEqualTo("pong")
        assertThat(service.analyzeImage(byteArrayOf(1)).getOrNull()).isEqualTo("a photo")
        assertThat(service.transcribe(byteArrayOf(1)).getOrNull()).isEqualTo("spoken words")
        coVerify(exactly = 0) { conversations.addUserMessage(any(), any(), any()) }
    }

    @Test
    fun `every helper reports a missing provider and upstream failures`() = runTest {
        coEvery { providerManager.getActiveService() } returns null
        assertThat(service.quickChat("ping").exceptionOrNull()).hasMessageThat().contains("not configured")
        assertThat(service.analyzeImage(byteArrayOf(1)).exceptionOrNull())
            .hasMessageThat().contains("not configured")
        assertThat(service.transcribe(byteArrayOf(1)).exceptionOrNull())
            .hasMessageThat().contains("not configured")

        coEvery { providerManager.getActiveService() } returns aiService
        coEvery { aiService.chat(any()) } throws IOException("boom")
        coEvery { aiService.analyzeImage(any(), any()) } throws IOException("boom")
        coEvery { aiService.transcribe(any(), any()) } returns SpeechResult.Error("no speech detected")

        assertThat(service.quickChat("ping").exceptionOrNull()).hasMessageThat().isEqualTo("boom")
        assertThat(service.analyzeImage(byteArrayOf(1)).exceptionOrNull())
            .hasMessageThat().isEqualTo("boom")
        assertThat(service.transcribe(byteArrayOf(1)).exceptionOrNull())
            .hasMessageThat().isEqualTo("no speech detected")
    }

    @Test
    fun `a new conversation is created from the active provider settings`() = runTest {
        coEvery { conversations.createConversation(any(), any(), any(), any()) } returns conversation("c9")

        assertThat(service.startNewConversation("Trip").getOrNull()).isEqualTo("c9")
        coVerify { conversations.createConversation("OPENAI", "gpt-4", "Trip", "be brief") }

        coEvery { conversations.createConversation(any(), any(), any(), any()) } throws IOException("db down")
        assertThat(service.startNewConversation().exceptionOrNull()).hasMessageThat().isEqualTo("db down")
    }

    @Test
    fun `the most recent conversation is reused instead of leaking an empty one`() = runTest {
        every { conversations.getAllConversations() } returns flowOf(listOf(conversation("c1"), conversation("c2")))
        assertThat(service.getOrCreateConversation()).isEqualTo("c1")
        coVerify(exactly = 0) { conversations.createConversation(any(), any(), any(), any()) }

        every { conversations.getAllConversations() } returns flowOf(emptyList())
        coEvery { conversations.createConversation(any(), any(), any(), any()) } returns conversation("new")
        assertThat(service.getOrCreateConversation()).isEqualTo("new")
    }

    @Test
    fun `invalidating the cache is delegated to the provider manager`() {
        service.invalidateCache()
        io.mockk.verify { providerManager.invalidateCache() }
    }
}
