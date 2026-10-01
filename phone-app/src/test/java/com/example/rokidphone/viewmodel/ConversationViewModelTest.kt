package com.example.rokidphone.viewmodel

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.rokidcommon.protocol.MessageType
import com.example.rokidphone.ai.provider.ProviderManager
import com.example.rokidphone.data.AiProvider
import com.example.rokidphone.data.ApiSettings
import com.example.rokidphone.data.SettingsRepository
import com.example.rokidphone.data.db.Conversation
import com.example.rokidphone.data.db.ConversationRepository
import com.example.rokidphone.data.db.Message
import com.example.rokidphone.data.db.MessageRole
import com.example.rokidphone.service.ServiceBridge
import com.example.rokidphone.service.ai.AiServiceProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ConversationViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val repository = mockk<ConversationRepository>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val providerManager = mockk<ProviderManager>(relaxed = true)
    private val aiService = mockk<AiServiceProvider>(relaxed = true)
    private val allConversations = MutableStateFlow(emptyList<Conversation>())
    /** Unconfined so a collector registers synchronously before any emission. */
    private val collectors = CoroutineScope(UnconfinedTestDispatcher())
    private var settings = ApiSettings(
        aiProvider = AiProvider.OPENAI, openaiApiKey = "sk-1", aiModelId = "gpt-4",
        systemPrompt = "be brief"
    )
    private lateinit var application: Application

    private fun conversation(
        id: String, isArchived: Boolean = false, isPinned: Boolean = false, title: String = id
    ) = Conversation(
        id = id, title = title, providerId = "OPENAI", modelId = "gpt-4", systemPrompt = "",
        createdAt = 0, updatedAt = 0, messageCount = 0, isArchived = isArchived, isPinned = isPinned
    )

    private fun message(id: String, role: MessageRole, content: String) = Message(
        id = id, conversationId = "c1", role = role, content = content, createdAt = 0,
        tokenCount = null, modelId = null, hasImage = false, imagePath = null,
        finishReason = null, errorMessage = null
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        application = ApplicationProvider.getApplicationContext()
        mockkObject(
            ConversationRepository.Companion, SettingsRepository.Companion, ProviderManager.Companion
        )
        every { ConversationRepository.getInstance(any()) } returns repository
        every { SettingsRepository.getInstance(any()) } returns settingsRepository
        every { ProviderManager.getInstance(any()) } returns providerManager
        every { settingsRepository.getSettings() } answers { settings }
        every { repository.getAllConversations() } returns allConversations
        coEvery { providerManager.getActiveService() } returns aiService
        coEvery { repository.getMessageCount(any()) } returns 4
        coEvery { repository.createConversation(any(), any(), any(), any()) } answers {
            conversation("c-new", title = thirdArg())
        }
    }

    @After
    fun tearDown() {
        collectors.cancel()
        ServiceBridge.reset()
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun viewModel() = ConversationViewModel(application)

    /** conversations / currentConversation / currentMessages are WhileSubscribed. */
    private fun keepHot(vararg flows: StateFlow<*>) {
        flows.forEach { flow -> collectors.launch { flow.collect { } } }
    }

    @Test
    fun `the conversation list is published from the repository`() = scope.runTest {
        allConversations.value = listOf(conversation("c1"), conversation("c2"))
        val model = viewModel()
        keepHot(model.conversations)
        advanceUntilIdle()

        assertThat(model.conversations.value.map { it.id }).containsExactly("c1", "c2").inOrder()
    }

    @Test
    fun `selecting a conversation loads its detail and messages, and closing clears them`() =
        scope.runTest {
            val detail = MutableStateFlow<Conversation?>(conversation("c1"))
            val messages = MutableStateFlow(listOf(message("m1", MessageRole.USER, "hi")))
            every { repository.getConversationByIdFlow("c1") } returns detail
            every { repository.getMessagesForConversation("c1") } returns messages
            val model = viewModel()
            keepHot(model.currentConversation, model.currentMessages)
            advanceUntilIdle()
            assertThat(model.currentConversation.value).isNull()
            assertThat(model.currentMessages.value).isEmpty()

            model.selectConversation(conversation("c1"))
            advanceUntilIdle()
            assertThat(model.currentConversationId.value).isEqualTo("c1")
            assertThat(model.currentConversation.value!!.id).isEqualTo("c1")
            assertThat(model.currentMessages.value.map { it.content }).containsExactly("hi")

            model.closeCurrentConversation()
            advanceUntilIdle()
            assertThat(model.currentConversation.value).isNull()
            assertThat(model.currentMessages.value).isEmpty()

            // The id overload selects the same conversation.
            model.selectConversation("c1")
            advanceUntilIdle()
            assertThat(model.currentConversationId.value).isEqualTo("c1")
        }

    @Test
    fun `a new conversation is created from the active provider settings`() = scope.runTest {
        val model = viewModel()

        model.createNewConversation()
        advanceUntilIdle()

        coVerify { repository.createConversation("OPENAI", "gpt-4", "New Conversation", "be brief") }
        assertThat(model.currentConversationId.value).isEqualTo("c-new")
    }

    @Test
    fun `a failed create is reported and leaves nothing selected`() = scope.runTest {
        coEvery { repository.createConversation(any(), any(), any(), any()) } throws
            IOException("db offline")
        val model = viewModel()

        model.createNewConversation()
        advanceUntilIdle()

        assertThat(model.uiState.value.error).isEqualTo("Failed to create conversation: db offline")
        assertThat(model.currentConversationId.value).isNull()
        model.clearError()
        assertThat(model.uiState.value.error).isNull()
    }

    @Test
    fun `input text is tracked and blank input sends nothing`() = scope.runTest {
        val model = viewModel()

        model.updateInputText("   ")
        assertThat(model.inputText.value).isEqualTo("   ")
        model.sendMessage()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.addUserMessage(any(), any(), any()) }
    }

    @Test
    fun `sending without a key is refused before anything is persisted`() = scope.runTest {
        settings = settings.copy(openaiApiKey = "")
        val model = viewModel()

        model.updateInputText("hello")
        model.sendMessage()
        advanceUntilIdle()

        assertThat(model.uiState.value.error).contains("API key not configured")
        assertThat(model.uiState.value.isLoading).isFalse()
        // The text is kept so the user does not lose it.
        assertThat(model.inputText.value).isEqualTo("hello")
        coVerify(exactly = 0) { repository.addUserMessage(any(), any(), any()) }
    }

    @Test
    fun `sending with no conversation open creates one titled from the message`() = scope.runTest {
        coEvery { aiService.chat("hello there") } returns "hi"
        val model = viewModel()

        model.updateInputText("hello there")
        model.sendMessage()
        advanceUntilIdle()

        coVerify { repository.createConversation("OPENAI", "gpt-4", "hello there", "be brief") }
        coVerify { repository.addUserMessage("c-new", "hello there", any()) }
        coVerify { repository.addAssistantMessage("c-new", "hi", "gpt-4", any(), any()) }
        assertThat(model.inputText.value).isEmpty()
        assertThat(model.uiState.value.isLoading).isFalse()
    }

    @Test
    fun `a long first message is truncated into the conversation title`() = scope.runTest {
        coEvery { aiService.chat(any()) } returns "ok"
        val model = viewModel()

        model.updateInputText("x".repeat(80))
        model.sendMessage()
        advanceUntilIdle()

        coVerify { repository.createConversation(any(), any(), "x".repeat(50), any()) }
    }

    @Test
    fun `the answer is persisted and the title generated for a fresh conversation`() = scope.runTest {
        coEvery { repository.getMessageCount("c1") } returns 2
        coEvery { aiService.chat("question") } returns "answer"
        val model = viewModel()
        model.selectConversation("c1")

        model.updateInputText("question")
        model.sendMessage()
        advanceUntilIdle()

        coVerify { repository.addAssistantMessage("c1", "answer", "gpt-4", any(), any()) }
        coVerify { repository.autoGenerateTitle("c1") }
    }

    @Test
    fun `an established conversation keeps the title it already has`() = scope.runTest {
        coEvery { repository.getMessageCount("c1") } returns 6
        coEvery { aiService.chat(any()) } returns "answer"
        val model = viewModel()
        model.selectConversation("c1")

        model.updateInputText("question")
        model.sendMessage()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.autoGenerateTitle(any()) }
    }

    @Test
    fun `the answer is pushed to the glasses only when the setting is on`() = scope.runTest {
        val pushed = mutableListOf<com.example.rokidcommon.protocol.Message>()
        collectors.launch { ServiceBridge.sendToGlassesFlow.collect { pushed += it } }
        coEvery { aiService.chat(any()) } returns "**bold** answer"
        val model = viewModel()
        model.selectConversation("c1")

        model.updateInputText("question")
        model.sendMessage()
        advanceUntilIdle()

        assertThat(pushed.map { it.type })
            .containsExactly(MessageType.AI_PROCESSING, MessageType.AI_RESPONSE_TEXT).inOrder()
        // Markdown is flattened for the glasses display.
        assertThat(pushed.last().payload).isEqualTo("bold answer")

        pushed.clear()
        settings = settings.copy(pushChatToGlasses = false)
        model.updateInputText("another")
        model.sendMessage()
        advanceUntilIdle()
        assertThat(pushed).isEmpty()
    }

    @Test
    fun `an unconfigured provider stops the exchange after the user message`() = scope.runTest {
        coEvery { providerManager.getActiveService() } returns null
        val model = viewModel()
        model.selectConversation("c1")

        model.updateInputText("question")
        model.sendMessage()
        advanceUntilIdle()

        assertThat(model.uiState.value.error).isEqualTo("AI service not configured")
        assertThat(model.uiState.value.isLoading).isFalse()
        coVerify { repository.addUserMessage("c1", "question", any()) }
        coVerify(exactly = 0) { repository.addAssistantMessage(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a provider failure is reported and the spinner is cleared`() = scope.runTest {
        coEvery { aiService.chat(any()) } throws IOException("upstream down")
        val model = viewModel()
        model.selectConversation("c1")

        model.updateInputText("question")
        model.sendMessage()
        advanceUntilIdle()

        assertThat(model.uiState.value.error).isEqualTo("Failed to send: upstream down")
        assertThat(model.uiState.value.isLoading).isFalse()
    }

    @Test
    fun `clearing, deleting, archiving and pinning are delegated to the repository`() = scope.runTest {
        val model = viewModel()
        model.selectConversation("c1")
        advanceUntilIdle()

        model.clearCurrentConversation()
        advanceUntilIdle()
        coVerify { repository.clearConversationMessages("c1") }

        model.archiveConversation(conversation("c1"))
        advanceUntilIdle()
        coVerify { repository.archiveConversation("c1") }
        model.archiveConversation(conversation("c1", isArchived = true))
        advanceUntilIdle()
        coVerify { repository.unarchiveConversation("c1") }

        model.pinConversation(conversation("c1"))
        advanceUntilIdle()
        coVerify { repository.pinConversation("c1") }
        model.pinConversation(conversation("c1", isPinned = true))
        advanceUntilIdle()
        coVerify { repository.unpinConversation("c1") }

        // Deleting the open conversation closes it.
        model.deleteConversation(conversation("c1"))
        advanceUntilIdle()
        coVerify { repository.deleteConversation("c1") }
        assertThat(model.currentConversationId.value).isNull()
    }

    @Test
    fun `deleting a conversation that is not open leaves the selection alone`() = scope.runTest {
        val model = viewModel()
        model.selectConversation("c1")

        model.deleteConversation(conversation("other"))
        advanceUntilIdle()

        assertThat(model.currentConversationId.value).isEqualTo("c1")
    }

    @Test
    fun `clearing with nothing open does nothing`() = scope.runTest {
        val model = viewModel()

        model.clearCurrentConversation()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.clearConversationMessages(any()) }
    }

    @Test
    fun `repository failures on the list operations are reported`() = scope.runTest {
        coEvery { repository.clearConversationMessages(any()) } throws IOException("busy")
        coEvery { repository.deleteConversation(any()) } throws IOException("locked")
        coEvery { repository.archiveConversation(any()) } throws IOException("locked")
        coEvery { repository.pinConversation(any()) } throws IOException("locked")
        val model = viewModel()
        model.selectConversation("c1")

        model.clearCurrentConversation()
        advanceUntilIdle()
        assertThat(model.uiState.value.error).isEqualTo("Failed to clear messages: busy")

        model.deleteConversation(conversation("c1"))
        advanceUntilIdle()
        assertThat(model.uiState.value.error).isEqualTo("Failed to delete conversation: locked")

        model.archiveConversation(conversation("c2"))
        advanceUntilIdle()
        assertThat(model.uiState.value.error).isEqualTo("Operation failed: locked")

        model.pinConversation(conversation("c2"))
        advanceUntilIdle()
        assertThat(model.uiState.value.error).isEqualTo("Operation failed: locked")
    }

    @Test
    fun `an export writes the transcript with a header and every role`() = scope.runTest {
        coEvery { repository.getMessagesForConversationSync("c1") } returns listOf(
            message("m1", MessageRole.USER, "what is this?"),
            message("m2", MessageRole.ASSISTANT, "a bicycle"),
            message("m3", MessageRole.SYSTEM, "be brief")
        )
        val model = viewModel()

        model.exportConversation(conversation("c1", title = "Trip plan")) { }
        advanceUntilIdle()

        val exported = File(application.cacheDir, "exports").listFiles().orEmpty()
        assertThat(exported).hasLength(1)
        val content = exported.single().readText()
        assertThat(exported.single().name).matches("chat_\\d{8}_\\d{6}\\.txt")
        assertThat(content).contains("Conversation: Trip plan")
        assertThat(content).contains("Model: gpt-4")
        assertThat(content).contains("Messages: 3")
        assertThat(content).contains("You")
        assertThat(content).contains("AI")
        assertThat(content).contains("System")
        assertThat(content).contains("what is this?")
        assertThat(content).contains("a bicycle")
    }

    @Test
    fun `a failed export is reported`() = scope.runTest {
        coEvery { repository.getMessagesForConversationSync(any()) } throws IOException("db offline")
        val model = viewModel()
        val intents = mutableListOf<Intent>()

        model.exportConversation(conversation("c1"), { intents += it })
        advanceUntilIdle()

        assertThat(model.uiState.value.error).isEqualTo("Export failed: db offline")
        assertThat(intents).isEmpty()
    }

    @Test
    fun `exporting the current conversation needs one to be open and present`() = scope.runTest {
        val model = viewModel()

        // Nothing open.
        model.exportCurrentConversation { }
        advanceUntilIdle()
        coVerify(exactly = 0) { repository.getMessagesForConversationSync(any()) }

        // Open, but the row is gone.
        model.selectConversation("c1")
        coEvery { repository.getConversationById("c1") } returns null
        model.exportCurrentConversation { }
        advanceUntilIdle()
        coVerify(exactly = 0) { repository.getMessagesForConversationSync(any()) }

        coEvery { repository.getConversationById("c1") } returns conversation("c1")
        coEvery { repository.getMessagesForConversationSync("c1") } returns emptyList()
        model.exportCurrentConversation { }
        advanceUntilIdle()
        coVerify { repository.getMessagesForConversationSync("c1") }
    }
}
