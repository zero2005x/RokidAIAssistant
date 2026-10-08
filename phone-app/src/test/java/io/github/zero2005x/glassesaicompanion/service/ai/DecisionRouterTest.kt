package io.github.zero2005x.glassesaicompanion.service.ai

import io.github.zero2005x.glassesaicompanion.data.AiProvider
import io.github.zero2005x.glassesaicompanion.data.ApiSettings
import io.github.zero2005x.glassesaicompanion.data.RoutingModel
import io.github.zero2005x.glassesaicompanion.service.SpeechResult
import io.github.zero2005x.glassesaicompanion.testutil.MockWebServerRule
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DecisionRouterTest {
    @get:Rule val serverRule = MockWebServerRule()

    @Test
    fun confidentDecisionSelectsCrossProviderQualitySlotAndKeepsContext() = runBlocking {
        serverRule.server.enqueue(MockResponse(body = """{
            "answers":{"difficulty":{"type":"choice","choice":"quality",
            "probabilities":{"fast":0.03,"balanced":0.07,"quality":0.90},"confidence":0.82}}
        }"""))
        var selectedSettings: ApiSettings? = null
        var sentText = ""
        val router = DecisionRouter(
            jevEndpoint = serverRule.server.url("/v1/systemone").toString(),
            serviceFactory = { selected ->
                selectedSettings = selected
                FakeService(selected.aiProvider) { sentText = it }
            }
        )
        val settings = ApiSettings(
            aiProvider = AiProvider.GEMINI,
            geminiApiKey = "primary",
            jevApiKey = "decision-key",
            decisionRoutingEnabled = true,
            qualityRoutingModel = RoutingModel(AiProvider.ANTHROPIC, "claude-sonnet-5-5"),
            anthropicApiKey = "quality-key"
        )
        val result = router.reply("Explain this proof", settings,
            history = listOf("user" to "Earlier question", "assistant" to "Earlier answer"))

        assertEquals(AiProvider.ANTHROPIC, result.provider)
        assertEquals("claude-sonnet-5-5", result.modelId)
        assertEquals("claude-sonnet-5-5", selectedSettings?.getCurrentModelId())
        assertTrue(sentText.contains("Earlier question"))
        assertTrue(result.reason.contains("quality"))
        val request = serverRule.server.takeRequest()
        assertEquals("Bearer decision-key", request.headers["Authorization"])
        assertEquals("Explain this proof", JSONObject(request.body.readUtf8()).getString("state"))
    }

    @Test
    fun uncertainDecisionFallsBackToPrimary() = runBlocking {
        serverRule.server.enqueue(MockResponse(body = """{
            "answers":{"difficulty":{"type":"choice","choice":"fast",
            "probabilities":{"fast":0.40,"balanced":0.35,"quality":0.25},"confidence":0.10}}
        }"""))
        val router = DecisionRouter(
            jevEndpoint = serverRule.server.url("/v1/systemone").toString(),
            serviceFactory = { FakeService(it.aiProvider) {} }
        )
        val settings = ApiSettings(
            aiProvider = AiProvider.GEMINI,
            geminiApiKey = "primary",
            jevApiKey = "decision-key",
            decisionRoutingEnabled = true,
            fastRoutingModel = RoutingModel(AiProvider.ANTHROPIC, "claude-haiku-4-5"),
            anthropicApiKey = "fast-key"
        )
        val result = router.reply("Hello", settings)
        assertEquals(AiProvider.GEMINI, result.provider)
        assertEquals("uncertain", JSONObject(result.reason).getString("code"))
    }

    @Test
    fun fastSlotRejectsMediumConfidenceButQualitySlotAcceptsIt() = runBlocking {
        val settings = ApiSettings(geminiApiKey = "primary", anthropicApiKey = "other", jevApiKey = "decision",
            decisionRoutingEnabled = true,
            fastRoutingModel = RoutingModel(AiProvider.ANTHROPIC, "fast"),
            qualityRoutingModel = RoutingModel(AiProvider.ANTHROPIC, "quality"))
        val router = DecisionRouter(jevEndpoint = serverRule.server.url("/").toString(),
            serviceFactory = { FakeService(it.aiProvider) {} })
        serverRule.server.enqueue(MockResponse(body = decision("fast", 0.60, 0.40)))
        assertEquals(AiProvider.GEMINI, router.reply("simple", settings).provider)
        serverRule.server.enqueue(MockResponse(body = decision("quality", 0.60, 0.40)))
        assertEquals(AiProvider.ANTHROPIC, router.reply("complex", settings).provider)
    }

    @Test
    fun existingPrimaryIsReseededAfterRoutedTurn() = runBlocking {
        val service = HistoryService()
        val router = DecisionRouter()
        val settings = ApiSettings(geminiApiKey = "primary")
        router.reply("first", settings, listOf("user" to "old"), service)
        router.reply("next", settings, listOf("user" to "routed question", "assistant" to "other model answer"), service)
        assertEquals(listOf("user" to "routed question", "assistant" to "other model answer"), service.seen)
    }

    @Test
    fun bothModelsFailPreservesPrimaryErrorAndNeverReturnsItAsAnswer() = runBlocking {
        serverRule.server.enqueue(MockResponse(body = decision("quality", 0.90, 0.85)))
        val router = DecisionRouter(jevEndpoint = serverRule.server.url("/").toString(), serviceFactory = { settings ->
            ErrorService(settings.aiProvider, if (settings.aiProvider == AiProvider.GEMINI) "Invalid API key (HTTP 401)" else "Quota exceeded")
        })
        val result = router.reply("question", ApiSettings(geminiApiKey = "primary", anthropicApiKey = "other",
            jevApiKey = "decision", decisionRoutingEnabled = true,
            qualityRoutingModel = RoutingModel(AiProvider.ANTHROPIC, "quality")))
        assertTrue(result.text.isEmpty())
        assertTrue(result.error!!.contains("Invalid API key"))
    }

    @Test
    fun slowDecisionHasTotalBudgetEvenWithInjectedClient() = runBlocking {
        serverRule.server.enqueue(MockResponse(body = decision("fast", 0.9, 0.85)).newBuilder()
            .headersDelay(6, java.util.concurrent.TimeUnit.SECONDS).build())
        val router = DecisionRouter(jevEndpoint = serverRule.server.url("/").toString(),
            serviceFactory = { FakeService(it.aiProvider) {} })
        val start = System.nanoTime()
        val result = router.reply("question", ApiSettings(geminiApiKey = "primary", jevApiKey = "decision", decisionRoutingEnabled = true))
        assertEquals("uncertain", JSONObject(result.reason).getString("code"))
        assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 5000)
    }

    @Test
    fun unloadedLocalModelReturnsErrorInsteadOfAnAnswer() = runBlocking {
        val result = DecisionRouter().reply("question", ApiSettings(aiProvider = AiProvider.LOCAL_GEMMA),
            primaryService = LocalGemmaService("gemma-test"))
        assertTrue(result.text.isEmpty())
        assertTrue(result.error!!.contains("No on-device model"))
    }

    @Test
    fun layaUrlRejectsPublicHttpAndEmbeddedCredentials() {
        assertTrue(DecisionRouter.isAllowedLayaUrl("http://192.168.1.20:8000"))
        assertTrue(DecisionRouter.isAllowedLayaUrl("https://example.org"))
        assertTrue(!DecisionRouter.isAllowedLayaUrl("http://example.org"))
        assertTrue(!DecisionRouter.isAllowedLayaUrl("http://172.32.1.1"))
        assertTrue(!DecisionRouter.isAllowedLayaUrl("https://user:pass@example.org"))
    }

    private fun decision(choice: String, probability: Double, confidence: Double): String {
        val probabilities = JSONObject()
        listOf("fast", "balanced", "quality").forEach { probabilities.put(it, if (it == choice) probability else (1 - probability) / 2) }
        return JSONObject().put("answers", JSONObject().put("difficulty", JSONObject()
            .put("choice", choice).put("probabilities", probabilities).put("confidence", confidence))).toString()
    }

    private class HistoryService : BaseAiService("test", "test", ""), AiServiceProvider {
        override val provider = AiProvider.GEMINI
        var seen = emptyList<Pair<String, String>>()
        override suspend fun chat(userMessage: String): String { seen = conversationHistory.toList(); return "answer" }
        override suspend fun transcribe(pcmAudioData: ByteArray, languageCode: String) = SpeechResult.Error("unsupported")
        override suspend fun analyzeImage(imageData: ByteArray, prompt: String) = "unsupported"
    }

    private class ErrorService(override val provider: AiProvider, private val detail: String) :
        BaseAiService("test", "test", ""), AiServiceProvider {
        override suspend fun chat(userMessage: String): String { lastChatError = detail; return detail }
        override suspend fun transcribe(pcmAudioData: ByteArray, languageCode: String) = SpeechResult.Error("unsupported")
        override suspend fun analyzeImage(imageData: ByteArray, prompt: String) = "unsupported"
    }

    private class FakeService(
        override val provider: AiProvider,
        private val onChat: (String) -> Unit
    ) : AiServiceProvider {
        override suspend fun transcribe(pcmAudioData: ByteArray, languageCode: String): SpeechResult =
            SpeechResult.Error("unsupported")
        override suspend fun chat(userMessage: String): String {
            onChat(userMessage)
            return "response"
        }
        override suspend fun analyzeImage(imageData: ByteArray, prompt: String): String = "unsupported"
        override fun clearHistory() = Unit
    }
}
