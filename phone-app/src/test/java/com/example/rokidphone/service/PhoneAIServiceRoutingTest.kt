package com.example.rokidphone.service

import com.example.rokidcommon.protocol.GlassesDisplayMetrics
import com.example.rokidcommon.protocol.Message
import com.example.rokidcommon.protocol.MessageType
import com.example.rokidphone.R
import com.example.rokidphone.ai.catalog.ProviderApiException
import com.example.rokidphone.data.AiProvider
import com.example.rokidphone.data.ApiSettings
import com.example.rokidphone.data.AvailableModels
import com.example.rokidphone.data.RoutingReason
import com.example.rokidphone.data.SettingsRepository
import com.example.rokidphone.data.db.ConversationRepository
import com.example.rokidphone.data.db.MessageRole
import com.example.rokidphone.data.db.RecordingRepository
import com.example.rokidphone.service.ai.AiServiceProvider
import com.example.rokidphone.service.ai.ChatErrorSource
import com.example.rokidphone.service.ai.RoutedReply
import com.example.rokidphone.service.cxr.CxrMobileManager
import com.example.rokidphone.service.photo.PhotoData
import com.example.rokidphone.service.photo.PhotoRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import com.example.rokidphone.data.db.Message as StoredMessage

/**
 * How [PhoneAIService] routes a question and reports what went wrong. The service is
 * built without `onCreate` (so nothing touches a radio, a database or the Rokid SDK) and
 * its collaborators are put in place directly; the private entry points are then driven
 * the way the glasses and the recorder drive them.
 */
@RunWith(RobolectricTestRunner::class)
class PhoneAIServiceRoutingTest {

    private lateinit var service: PhoneAIService
    private lateinit var bluetooth: BluetoothSppManager
    private val toGlasses = mutableListOf<Message>()
    private var settings = ApiSettings(geminiApiKey = "key")
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)

    @Before
    fun setUp() {
        service = Robolectric.buildService(PhoneAIService::class.java).get()
        mockkObject(SettingsRepository.Companion)
        every { SettingsRepository.getInstance(any()) } returns settingsRepository
        every { settingsRepository.getSettings() } answers { settings }
        bluetooth = mockk(relaxed = true)
        coEvery { bluetooth.sendMessage(capture(toGlasses)) } returns true
        set("bluetoothManager", bluetooth)
        ServiceBridge.reset()
    }

    @After
    fun tearDown() {
        unmockkAll()
        ServiceBridge.reset()
    }

    // ==================== Reflection helpers ====================

    private fun set(name: String, value: Any?) {
        PhoneAIService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> get(name: String): T =
        PhoneAIService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service) as T

    private fun method(name: String) = PhoneAIService::class.java.declaredMethods
        .first { it.name == name }.apply { isAccessible = true }

    /** Invokes a private `suspend` function: the compiled method takes the continuation last. */
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> call(name: String, vararg args: Any?): T =
        suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
            try {
                method(name).invoke(service, *arrayOf(*args, continuation))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as T

    /** Invokes a private function that is not `suspend`. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> callPlain(name: String, vararg args: Any?): T = try {
        method(name).invoke(service, *args) as T
    } catch (e: InvocationTargetException) {
        throw e.targetException
    }

    private fun CoroutineScope.collectConversation(into: MutableList<Message>): Job =
        launch(Dispatchers.Unconfined) { ServiceBridge.conversationFlow.collect { into += it } }

    private fun stored(index: Int, role: MessageRole, content: String, hasImage: Boolean = false) = StoredMessage(
        id = "m$index", conversationId = "voice-1", role = role, content = content,
        createdAt = index.toLong(), tokenCount = null, modelId = null, hasImage = hasImage,
        imagePath = null, finishReason = null, errorMessage = null
    )

    private fun photo() = PhotoData(
        id = "p1", filePath = "photo.jpg", timestamp = 0, width = 1, height = 1, sizeBytes = 3, transferTimeMs = 0
    )

    // ==================== Display metrics from the glasses ====================

    @Test
    fun `display metrics from the glasses switch the camera to SPP and drop the legacy link`() = runBlocking {
        val cxr = mockk<CxrMobileManager>(relaxed = true)
        val pending = Job()
        set("cxrManager", cxr)
        set("pendingCxrInit", pending)
        val metrics = GlassesDisplayMetrics(480, 640, 1.5f, 1.2f)

        call<Unit>("handleGlassesMessage", Message(type = MessageType.DISPLAY_METRICS, payload = metrics.toJson()))

        verify { settingsRepository.updateGlassesDisplayMetrics(metrics) }
        verify { cxr.disconnectBluetooth() }
        assertThat(pending.isCancelled).isTrue()
        assertThat(get<Boolean>("companionCamera")).isTrue()
    }

    @Test
    fun `metrics from glasses without the SPP camera are stored but keep the legacy link`() = runBlocking {
        val cxr = mockk<CxrMobileManager>(relaxed = true)
        val pending = Job()
        set("cxrManager", cxr)
        set("pendingCxrInit", pending)
        val legacy = """{"widthPx":480,"heightPx":640,"density":1.5,"fontScale":1.2}"""

        call<Unit>("handleGlassesMessage", Message(type = MessageType.DISPLAY_METRICS, payload = legacy))

        verify { settingsRepository.updateGlassesDisplayMetrics(GlassesDisplayMetrics(480, 640, 1.5f, 1.2f)) }
        verify(exactly = 0) { cxr.disconnectBluetooth() }
        assertThat(pending.isCancelled).isFalse()
        assertThat(get<Boolean>("companionCamera")).isFalse()
    }

    @Test
    fun `unreadable metrics change nothing`() = runBlocking {
        call<Unit>("handleGlassesMessage", Message(type = MessageType.DISPLAY_METRICS, payload = "{}"))
        call<Unit>("handleGlassesMessage", Message(type = MessageType.DISPLAY_METRICS, payload = null))

        verify(exactly = 0) { settingsRepository.updateGlassesDisplayMetrics(any()) }
        assertThat(get<Boolean>("companionCamera")).isFalse()
    }

    // ==================== Context handed to the router ====================

    @Test
    fun `a spoken question is answered with the recent voice conversation as context`() = runBlocking {
        val prompts = mutableListOf<String>()
        val repo = mockk<ConversationRepository>(relaxed = true)
        val turns = (1..14).map { stored(it, if (it % 2 == 1) MessageRole.USER else MessageRole.ASSISTANT, "turn $it") }
        coEvery { repo.getMessagesForConversationSync("voice-1") } returns
            turns.take(10) +
            stored(15, MessageRole.SYSTEM, "internal note") +
            stored(16, MessageRole.USER, "image turn", hasImage = true) +
            turns.drop(10) +
            // The question itself is already stored by the time it is answered.
            stored(17, MessageRole.USER, "what now")
        set("aiService", Answering("answer") { prompts += it })
        set("conversationRepository", repo)
        set("currentVoiceConversationId", "voice-1")

        val reply = call<RoutedReply>("replyToTranscript", "what now", settings)

        assertThat(reply.text).isEqualTo("answer")
        assertThat(reply.error).isNull()
        val prompt = prompts.single()
        assertThat(prompt).contains("user: turn 9")
        assertThat(prompt).contains("assistant: turn 14")
        // Only the most recent turns are used, and system notes and images are not context.
        assertThat(prompt).doesNotContain("turn 8")
        assertThat(prompt).doesNotContain("internal note")
        assertThat(prompt).doesNotContain("image turn")
        assertThat(prompt).endsWith("Current question: what now")
    }

    @Test
    fun `a question with no conversation yet is sent on its own`() = runBlocking<Unit> {
        val prompts = mutableListOf<String>()
        set("aiService", Answering("answer") { prompts += it })

        call<RoutedReply>("replyToTranscript", "first question", settings)

        assertThat(prompts).containsExactly("first question")
    }

    @Test
    fun `a different last message is kept as history instead of being dropped`() = runBlocking {
        val prompts = mutableListOf<String>()
        val repo = mockk<ConversationRepository>(relaxed = true)
        coEvery { repo.getMessagesForConversationSync("voice-1") } returns listOf(
            stored(1, MessageRole.ASSISTANT, "a reply"),
            stored(2, MessageRole.USER, "an unrelated question")
        )
        set("aiService", Answering("answer") { prompts += it })
        set("conversationRepository", repo)
        set("currentVoiceConversationId", "voice-1")

        call<RoutedReply>("replyToTranscript", "what now", settings)

        assertThat(prompts.single()).contains("user: an unrelated question")
        assertThat(prompts.single()).contains("assistant: a reply")
    }

    // ==================== Spoken questions ====================

    @Test
    fun `a spoken question is answered, shown, saved and spoken`() = runBlocking {
        val repo = mockk<ConversationRepository>(relaxed = true)
        val recordings = mockk<RecordingRepository>(relaxed = true)
        val tts = mockk<TextToSpeechService>(relaxed = true)
        val speech = mockk<AiServiceProvider>(relaxed = true)
        coEvery { speech.transcribe(any(), any()) } returns SpeechResult.Success("what time is it")
        set("aiService", Answering("**bold** answer"))
        set("speechService", speech)
        set("conversationRepository", repo)
        set("recordingRepository", recordings)
        set("ttsService", tts)
        set("currentVoiceConversationId", "voice-1")
        val seen = mutableListOf<Message>()
        val collector = collectConversation(seen)
        val modelId = settings.getCurrentModelId()

        call<Unit>("processVoiceData", ByteArray(2_000))
        collector.cancel()

        assertThat(toGlasses.map { it.type }).containsAtLeast(
            MessageType.AI_PROCESSING, MessageType.USER_TRANSCRIPT,
            MessageType.AI_PROCESSING, MessageType.AI_RESPONSE_TEXT
        ).inOrder()
        assertThat(toGlasses.last().payload).isEqualTo("bold answer")
        assertThat(seen.map { it.type }).containsAtLeast(
            MessageType.USER_TRANSCRIPT, MessageType.AI_RESPONSE_TEXT
        ).inOrder()
        coVerify { repo.addUserMessage("voice-1", "what time is it", any()) }
        // Routing is off, so no routing reason is stored with the answer.
        coVerify {
            repo.addAssistantMessage(eq("voice-1"), eq("bold answer"), eq(modelId), any(), any(), isNull())
        }
        coVerify {
            recordings.saveGlassesRecording(
                any(), eq("what time is it"), eq("bold answer"), eq("GEMINI"), eq(modelId), any()
            )
        }
        verify { tts.speak("bold answer", any()) }
    }

    @Test
    fun `with routing on the stored answer carries the routing reason`() = runBlocking {
        settings = settings.copy(decisionRoutingEnabled = true)
        val repo = mockk<ConversationRepository>(relaxed = true)
        val speech = mockk<AiServiceProvider>(relaxed = true)
        coEvery { speech.transcribe(any(), any()) } returns SpeechResult.Success("explain gravity")
        set("aiService", Answering("answer"))
        set("speechService", speech)
        set("conversationRepository", repo)
        set("currentVoiceConversationId", "voice-1")

        call<Unit>("processVoiceData", ByteArray(2_000))

        // No decision key is configured, so the routing stays uncertain and the primary answers.
        coVerify {
            repo.addAssistantMessage(
                eq("voice-1"), eq("answer"), any(), any(), any(),
                match { it != null && JSONObject(it).getString("code") == "uncertain" }
            )
        }
    }

    @Test
    fun `a failed spoken question is reported and never stored as an answer`() = runBlocking {
        val repo = mockk<ConversationRepository>(relaxed = true)
        val speech = mockk<AiServiceProvider>(relaxed = true)
        coEvery { speech.transcribe(any(), any()) } returns SpeechResult.Success("what time is it")
        set("aiService", Failing(IOException("upstream down")))
        set("speechService", speech)
        set("conversationRepository", repo)
        set("currentVoiceConversationId", "voice-1")
        val seen = mutableListOf<Message>()
        val collector = collectConversation(seen)

        call<Unit>("processVoiceData", ByteArray(2_000))
        collector.cancel()

        val expected = RoutingReason.failure(service, "upstream down")
        assertThat(toGlasses.last().type).isEqualTo(MessageType.AI_ERROR)
        assertThat(toGlasses.last().payload).isEqualTo(expected)
        assertThat(seen.last().type).isEqualTo(MessageType.AI_ERROR)
        assertThat(seen.last().payload).isEqualTo(expected)
        coVerify(exactly = 0) {
            repo.addAssistantMessage(any(), any(), any(), any(), any(), any())
        }
    }

    // ==================== Phone recordings ====================

    private fun recordingFile(): File =
        File.createTempFile("recording", ".wav").apply { writeBytes(ByteArray(2_000)); deleteOnExit() }

    @Test
    fun `a recording is transcribed, answered, saved and pushed to the glasses`() = runBlocking {
        val repo = mockk<ConversationRepository>(relaxed = true)
        val recordings = mockk<RecordingRepository>(relaxed = true)
        val tts = mockk<TextToSpeechService>(relaxed = true)
        val speech = mockk<AiServiceProvider>(relaxed = true)
        coEvery { recordings.getRecordingById(any()) } returns null
        coEvery { speech.transcribe(any(), any()) } returns SpeechResult.Success("remember the milk")
        set("aiService", Answering("Sure, noted."))
        set("speechService", speech)
        set("conversationRepository", repo)
        set("recordingRepository", recordings)
        set("ttsService", tts)
        set("currentVoiceConversationId", "voice-1")
        val modelId = settings.getCurrentModelId()

        call<Unit>("processPhoneRecording", "rec-1", recordingFile().absolutePath)

        coVerify { recordings.updateTranscript("rec-1", "remember the milk") }
        coVerify { recordings.updateAiResponse("rec-1", "Sure, noted.", "GEMINI", modelId) }
        assertThat(toGlasses.map { it.type }).containsAtLeast(
            MessageType.USER_TRANSCRIPT, MessageType.AI_RESPONSE_TEXT
        ).inOrder()
        coVerify {
            repo.addAssistantMessage(eq("voice-1"), eq("Sure, noted."), eq(modelId), any(), any(), isNull())
        }
        verify { tts.speak("Sure, noted.", any()) }
    }

    @Test
    fun `a recording whose question fails is marked with the reason`() = runBlocking {
        val recordings = mockk<RecordingRepository>(relaxed = true)
        val speech = mockk<AiServiceProvider>(relaxed = true)
        coEvery { recordings.getRecordingById(any()) } returns null
        coEvery { speech.transcribe(any(), any()) } returns SpeechResult.Success("remember the milk")
        set("aiService", Failing(IOException("upstream down")))
        set("speechService", speech)
        set("recordingRepository", recordings)

        call<Unit>("processPhoneRecording", "rec-2", recordingFile().absolutePath)

        val expected = RoutingReason.failure(service, "upstream down")
        coVerify { recordings.markError("rec-2", expected) }
        coVerify(exactly = 0) { recordings.updateAiResponse(any(), any(), any(), any()) }
        assertThat(toGlasses.last().type).isEqualTo(MessageType.AI_ERROR)
        assertThat(toGlasses.last().payload).isEqualTo(expected)
    }

    // ==================== Photo analysis ====================

    private fun photoRepository(): PhotoRepository {
        val repo = mockk<PhotoRepository>(relaxed = true)
        coEvery { repo.getPhotoBytes(any()) } returns byteArrayOf(1, 2, 3)
        return repo
    }

    @Test
    fun `a photo with no AI service is reported to the glasses and the phone`() = runBlocking {
        set("photoRepository", photoRepository())
        val seen = mutableListOf<Message>()
        val collector = collectConversation(seen)

        call<Unit>("analyzePhotoWithAI", photo())
        collector.cancel()

        val unavailable = service.getString(R.string.ai_analysis_unavailable)
        val expected = service.getString(
            R.string.photo_analysis_failed, ProviderApiException.sanitize(unavailable)
        )
        assertThat(toGlasses.map { it.type }).containsExactly(MessageType.AI_PROCESSING, MessageType.AI_ERROR).inOrder()
        assertThat(toGlasses.last().payload).isEqualTo(expected)
        assertThat(seen.last().type).isEqualTo(MessageType.AI_ERROR)
        assertThat(seen.last().payload).isEqualTo(expected)
    }

    @Test
    fun `a provider error or an empty description is an error and not an answer`() = runBlocking {
        set("photoRepository", photoRepository())

        set("aiService", Vision("Image analysis failed.", lastChatError = "Invalid API key (HTTP 401)"))
        call<Unit>("analyzePhotoWithAI", photo())
        assertThat(toGlasses.last().type).isEqualTo(MessageType.AI_ERROR)
        assertThat(toGlasses.last().payload).contains("401")

        toGlasses.clear()
        set("aiService", Vision("   "))
        call<Unit>("analyzePhotoWithAI", photo())
        assertThat(toGlasses.last().type).isEqualTo(MessageType.AI_ERROR)
        assertThat(toGlasses.last().payload).contains("empty_response")
        assertThat(toGlasses.map { it.type }).doesNotContain(MessageType.PHOTO_ANALYSIS_RESULT)
    }

    @Test
    fun `a described photo is cleaned, sent to the glasses, shown and spoken`() = runBlocking {
        val tts = mockk<TextToSpeechService>(relaxed = true)
        val data = photo()
        set("photoRepository", photoRepository())
        set("aiService", Vision("**A red bicycle** leaning on a wall."))
        set("ttsService", tts)
        val seen = mutableListOf<Message>()
        val collector = collectConversation(seen)

        call<Unit>("analyzePhotoWithAI", data)
        collector.cancel()

        assertThat(toGlasses.last().type).isEqualTo(MessageType.PHOTO_ANALYSIS_RESULT)
        assertThat(toGlasses.last().payload).isEqualTo("A red bicycle leaning on a wall.")
        assertThat(data.analysisResult).isEqualTo("A red bicycle leaning on a wall.")
        assertThat(seen.last().type).isEqualTo(MessageType.AI_RESPONSE_TEXT)
        verify { tts.speak("A red bicycle leaning on a wall.", any()) }
    }

    // ==================== Model fallbacks ====================

    @Test
    fun `the speech fallback model follows the provider`() {
        assertThat(callPlain<String?>("getSttFallbackModelId", AiProvider.GEMINI)).isEqualTo("gemini-3.8-flash")
        assertThat(callPlain<String?>("getSttFallbackModelId", AiProvider.OPENAI)).isEqualTo("gpt-5-mini")
        assertThat(callPlain<String?>("getSttFallbackModelId", AiProvider.GROQ)).isEqualTo("openai/gpt-oss-120b")
        // Any other provider uses the first model the app lists for it.
        assertThat(callPlain<String?>("getSttFallbackModelId", AiProvider.ANTHROPIC))
            .isEqualTo(AvailableModels.getModelsForProvider(AiProvider.ANTHROPIC).firstOrNull()?.id)
    }

    // ==================== Test doubles ====================

    private class Answering(
        private val answer: String,
        private val onChat: (String) -> Unit = {}
    ) : AiServiceProvider {
        override val provider = AiProvider.GEMINI
        override suspend fun transcribe(pcmAudioData: ByteArray, languageCode: String): SpeechResult =
            SpeechResult.Error("unsupported")
        override suspend fun chat(userMessage: String): String { onChat(userMessage); return answer }
        override suspend fun analyzeImage(imageData: ByteArray, prompt: String): String = "unsupported"
        override fun clearHistory() = Unit
    }

    private class Failing(private val failure: Exception) : AiServiceProvider {
        override val provider = AiProvider.GEMINI
        override suspend fun transcribe(pcmAudioData: ByteArray, languageCode: String): SpeechResult =
            SpeechResult.Error("unsupported")
        override suspend fun chat(userMessage: String): String = throw failure
        override suspend fun analyzeImage(imageData: ByteArray, prompt: String): String = "unsupported"
        override fun clearHistory() = Unit
    }

    private class Vision(
        private val description: String,
        override val lastChatError: String? = null
    ) : AiServiceProvider, ChatErrorSource {
        override val provider = AiProvider.GEMINI
        override suspend fun transcribe(pcmAudioData: ByteArray, languageCode: String): SpeechResult =
            SpeechResult.Error("unsupported")
        override suspend fun chat(userMessage: String): String = "unused"
        override suspend fun analyzeImage(imageData: ByteArray, prompt: String): String = description
        override fun clearHistory() = Unit
    }
}
