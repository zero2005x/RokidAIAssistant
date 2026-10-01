package com.example.rokidphone.service.ai

import com.example.rokidphone.data.AiProvider
import com.example.rokidphone.data.ApiSettings
import com.example.rokidphone.data.DecisionBackend
import com.example.rokidphone.data.RoutingModel
import com.example.rokidphone.service.SpeechResult
import com.example.rokidphone.testutil.MockWebServerRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The slot selection, fallback, unconfigured and decision-validation branches of
 * [DecisionRouter] that the happy-path tests in [DecisionRouterTest] do not reach.
 */
@RunWith(RobolectricTestRunner::class)
class DecisionRouterBranchesTest {
    @get:Rule val serverRule = MockWebServerRule()

    private fun router(factory: (ApiSettings) -> AiServiceProvider = { Replying(it.aiProvider, "response") }) =
        DecisionRouter(
            jevEndpoint = serverRule.server.url("/v1/systemone").toString(),
            geminiDecisionBase = serverRule.server.url("/models").toString().trimEnd('/'),
            serviceFactory = factory
        )

    private fun routed(vararg slots: Pair<String, RoutingModel>) = ApiSettings(
        geminiApiKey = "primary", anthropicApiKey = "other", jevApiKey = "decision",
        decisionRoutingEnabled = true,
        fastRoutingModel = slots.firstOrNull { it.first == "fast" }?.second,
        balancedRoutingModel = slots.firstOrNull { it.first == "balanced" }?.second,
        qualityRoutingModel = slots.firstOrNull { it.first == "quality" }?.second
    )

    private fun decision(choice: String, probability: Double, confidence: Double): String {
        val probabilities = JSONObject()
        listOf("fast", "balanced", "quality").forEach {
            probabilities.put(it, if (it == choice) probability else (1 - probability) / 2)
        }
        return JSONObject().put("answers", JSONObject().put("difficulty", JSONObject()
            .put("choice", choice).put("probabilities", probabilities).put("confidence", confidence))).toString()
    }

    private fun code(reply: RoutedReply) = JSONObject(reply.reason).getString("code")

    // ==================== Slot selection ====================

    @Test
    fun fastAndBalancedSlotsAreUsedWhenTheDecisionIsConfident() = runBlocking {
        val settings = routed(
            "fast" to RoutingModel(AiProvider.ANTHROPIC, "fast-model"),
            "balanced" to RoutingModel(AiProvider.ANTHROPIC, "balanced-model")
        )

        serverRule.server.enqueue(MockResponse(body = decision("fast", 0.90, 0.85)))
        val fast = router().reply("what is 2 + 2", settings)
        assertEquals("fast-model", fast.modelId)
        assertEquals("selected", code(fast))
        assertEquals("fast", JSONObject(fast.reason).getString("tier"))

        serverRule.server.enqueue(MockResponse(body = decision("balanced", 0.90, 0.85)))
        val balanced = router().reply("explain recursion", settings)
        assertEquals("balanced-model", balanced.modelId)
        assertEquals("balanced", JSONObject(balanced.reason).getString("tier"))
    }

    @Test
    fun aConfidentDecisionWithNoSlotKeepsThePrimaryAndSaysWhy() = runBlocking {
        serverRule.server.enqueue(MockResponse(body = decision("quality", 0.90, 0.85)))

        val result = router().reply("hard question", routed())

        assertEquals(AiProvider.GEMINI, result.provider)
        assertEquals("empty_slot", code(result))
        assertEquals("quality", JSONObject(result.reason).getString("tier"))
    }

    @Test
    fun aSlotThatCannotRunIsReportedAsUnconfigured() = runBlocking {
        val unusable = listOf(
            RoutingModel(AiProvider.OPENAI, "gpt-6-luna"),      // provider has no key
            RoutingModel(AiProvider.ANTHROPIC, "  "),           // no model chosen
            RoutingModel(AiProvider.GEMINI_LIVE, "live-model")  // never a chat model
        )
        for (slot in unusable) {
            serverRule.server.enqueue(MockResponse(body = decision("quality", 0.90, 0.85)))

            val result = router().reply("hard question", routed("quality" to slot))

            assertEquals(AiProvider.GEMINI, result.provider)
            assertEquals("unconfigured_slot", code(result))
            assertEquals("response", result.text)
        }
    }

    // ==================== Fallback and failure ====================

    @Test
    fun aFailingRoutedModelFallsBackToThePrimaryAndRecordsIt() = runBlocking {
        serverRule.server.enqueue(MockResponse(body = decision("quality", 0.90, 0.85)))
        val router = router { settings ->
            if (settings.aiProvider == AiProvider.ANTHROPIC) Failing(settings.aiProvider, "Quota exceeded")
            else Replying(settings.aiProvider, "primary answer")
        }

        val result = router.reply("question",
            routed("quality" to RoutingModel(AiProvider.ANTHROPIC, "claude-sonnet-5-5")))

        assertEquals("primary answer", result.text)
        assertEquals(AiProvider.GEMINI, result.provider)
        assertEquals("fallback", code(result))
        assertTrue(JSONObject(result.reason).getString("model").contains("claude-sonnet-5-5"))
        assertNull(result.error)
    }

    @Test
    fun aPrimaryThatIsNotConfiguredAndHasNoServiceReportsIt() = runBlocking {
        val result = router().reply("question", ApiSettings(aiProvider = AiProvider.GEMINI))

        assertEquals("", result.text)
        assertEquals("not_configured", result.error)
        assertEquals("failed", code(result))
    }

    @Test
    fun liveAudioIsNeverUsedAsAChatModel() = runBlocking {
        val result = router().reply("question",
            ApiSettings(aiProvider = AiProvider.GEMINI_LIVE, geminiApiKey = "key"))

        assertEquals("not_configured", result.error)
    }

    @Test
    fun blankAndErrorPrefixedAnswersAreTreatedAsFailures() = runBlocking {
        val settings = ApiSettings(geminiApiKey = "primary")

        assertEquals("empty_response",
            router { Replying(it.aiProvider, "") }.reply("q", settings).error)
        assertEquals("empty_response",
            router { Replying(it.aiProvider, "   ") }.reply("q", settings).error)
        assertEquals("Error: boom",
            router { Replying(it.aiProvider, "Error: boom") }.reply("q", settings).error)
    }

    @Test
    fun aServiceErrorWinsOverItsPlaceholderAnswer() = runBlocking {
        val result = router { Failing(it.aiProvider, "Invalid API key (HTTP 401)") }
            .reply("q", ApiSettings(geminiApiKey = "primary"))

        assertEquals("Invalid API key (HTTP 401)", result.error)
        assertEquals("", result.text)
    }

    @Test
    fun anExceptionIsReportedWithItsSanitisedMessage() = runBlocking {
        val result = router { Throwing(it.aiProvider, IllegalStateException("upstream down")) }
            .reply("q", ApiSettings(geminiApiKey = "primary"))

        assertEquals("upstream down", result.error)
    }

    @Test
    fun cancellationIsNeverSwallowed() = runBlocking {
        try {
            router { Throwing(it.aiProvider, CancellationException("stop")) }
                .reply("q", ApiSettings(geminiApiKey = "primary"))
            fail("cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals("stop", expected.message)
        }
    }

    @Test
    fun servicesThatCannotBeSeededGetTheRecentTurnsInThePrompt() = runBlocking {
        var prompt = ""
        val result = router { settings -> Replying(settings.aiProvider, "ok") { text -> prompt = text } }.reply(
            "now", ApiSettings(geminiApiKey = "primary"),
            history = (1..8).map { (if (it % 2 == 0) "assistant" else "user") to "turn $it" }
        )

        assertEquals("ok", result.text)
        assertTrue(prompt.startsWith("Previous conversation:"))
        assertTrue(prompt.contains("Current question: now"))
        // Only the six most recent turns are inlined.
        assertFalse(prompt.contains("turn 2"))
        assertTrue(prompt.contains("assistant: turn 8"))
    }

    // ==================== Decision backends ====================

    @Test
    fun layaUsesTheConfiguredLanEndpointAndOnlySendsAKeyWhenOneIsSet() = runBlocking {
        // A literal loopback address: the host name MockWebServer reports can vary by machine.
        val base = "http://127.0.0.1:${serverRule.server.port}/"
        val slot = "quality" to RoutingModel(AiProvider.ANTHROPIC, "claude-sonnet-5-5")
        for (key in listOf("", "laya-key")) {
            serverRule.server.enqueue(MockResponse(body = decision("quality", 0.90, 0.85)))
            val settings = routed(slot).copy(decisionBackend = DecisionBackend.LAYA,
                layaBaseUrl = base, layaApiKey = key)

            val result = router().reply("question", settings)

            assertEquals("claude-sonnet-5-5", result.modelId)
            val request = serverRule.server.takeRequest()
            assertEquals("/v1/systemone", request.path)
            assertEquals(if (key.isEmpty()) null else "Bearer $key", request.headers["Authorization"])
        }
    }

    @Test
    fun layaOnAPublicPlainHttpAddressNeverMakesARequest() = runBlocking {
        val settings = routed().copy(decisionBackend = DecisionBackend.LAYA,
            layaBaseUrl = "http://example.org", layaApiKey = "k")

        val result = router().reply("question", settings)

        assertEquals("uncertain", code(result))
        assertEquals(0, serverRule.server.requestCount)
    }

    @Test
    fun jevWithoutAKeyNeverMakesARequest() = runBlocking {
        val result = router().reply("question", routed().copy(jevApiKey = ""))

        assertEquals("uncertain", code(result))
        assertEquals(0, serverRule.server.requestCount)
    }

    @Test
    fun aDecisionServiceErrorLeavesTheChoiceUncertain() = runBlocking {
        serverRule.server.enqueue(MockResponse(code = 500, body = "{}"))

        assertEquals("uncertain", code(router().reply("question", routed())))
    }

    @Test
    fun unusableDecisionsAreRejected() = runBlocking {
        val rejected = listOf(
            // The chosen tier has no probability entry.
            """{"answers":{"difficulty":{"choice":"medium","probabilities":
                {"fast":0.2,"balanced":0.3,"quality":0.5},"confidence":0.9}}}""",
            // A probability outside 0..1.
            """{"answers":{"difficulty":{"choice":"fast","probabilities":
                {"fast":1.5,"balanced":-0.25,"quality":-0.25},"confidence":0.9}}}""",
            // Probabilities that do not sum to one.
            """{"answers":{"difficulty":{"choice":"fast","probabilities":
                {"fast":0.5,"balanced":0.3,"quality":0.3},"confidence":0.9}}}""",
            // The chosen tier is not the most likely one.
            """{"answers":{"difficulty":{"choice":"balanced","probabilities":
                {"fast":0.5,"balanced":0.3,"quality":0.2},"confidence":0.9}}}""",
            // The chosen tier is the most likely one but below 0.55.
            """{"answers":{"difficulty":{"choice":"fast","probabilities":
                {"fast":0.5,"balanced":0.3,"quality":0.2},"confidence":0.9}}}""",
            // Confidence that is not a number.
            """{"answers":{"difficulty":{"choice":"fast","probabilities":
                {"fast":0.9,"balanced":0.05,"quality":0.05},"confidence":"NaN"}}}""",
            // Fast needs at least 0.5 confidence; quality at least 0.3; nothing above 1.
            decision("fast", 0.9, 0.40),
            decision("quality", 0.9, 0.20),
            decision("quality", 0.9, 1.5),
            // No confidence at all.
            """{"answers":{"difficulty":{"choice":"fast","probabilities":
                {"fast":0.9,"balanced":0.05,"quality":0.05}}}}""",
            "not json"
        )
        for (body in rejected) {
            serverRule.server.enqueue(MockResponse(body = body))

            val result = router().reply("question",
                routed("fast" to RoutingModel(AiProvider.ANTHROPIC, "fast-model"),
                    "balanced" to RoutingModel(AiProvider.ANTHROPIC, "balanced-model"),
                    "quality" to RoutingModel(AiProvider.ANTHROPIC, "quality-model")))

            assertEquals(body, "uncertain", code(result))
            assertEquals(AiProvider.GEMINI, result.provider)
        }
    }

    @Test
    fun aQualityDecisionAtTheLowerThresholdIsAccepted() = runBlocking {
        serverRule.server.enqueue(MockResponse(body = decision("quality", 0.90, 0.30)))

        val result = router().reply("question",
            routed("quality" to RoutingModel(AiProvider.ANTHROPIC, "quality-model")))

        assertEquals("quality-model", result.modelId)
    }

    @Test
    fun anLlmDecisionBackendThatFailsLeavesTheChoiceUncertain() = runBlocking {
        serverRule.server.enqueue(MockResponse(code = 500, body = """{"error":{"message":"down"}}"""))
        val settings = routed().copy(decisionBackend = DecisionBackend.GEMINI)

        assertEquals("uncertain", code(router().reply("question", settings)))

        // No key for the decision model means no request at all.
        val before = serverRule.server.requestCount
        val keyless = settings.copy(geminiApiKey = "", aiProvider = AiProvider.ANTHROPIC)
        assertEquals("uncertain", code(router().reply("question", keyless)))
        assertEquals(before, serverRule.server.requestCount)
    }

    @Test
    fun anLlmDecisionBackendThatAnswersSelectsTheSlot() = runBlocking {
        val candidate = JSONObject().put("finishReason", "STOP").put("content", JSONObject()
            .put("parts", JSONArray().put(JSONObject()
                .put("text", """{"tier":"quality","certainty":"clear"}"""))))
        serverRule.server.enqueue(MockResponse(
            body = JSONObject().put("candidates", JSONArray().put(candidate)).toString()))
        val settings = routed("quality" to RoutingModel(AiProvider.ANTHROPIC, "quality-model"))
            .copy(decisionBackend = DecisionBackend.GEMINI)

        val result = router().reply("question", settings)

        assertEquals("quality-model", result.modelId)
        assertEquals("selected_llm", code(result))
    }

    // ==================== Laya address rules ====================

    @Test
    fun layaAddressesAreLimitedToHttpsAndPrivateNetworks() {
        val allowed = listOf(
            "https://laya.example.org", "http://localhost:8000", "http://[::1]:8000",
            "http://127.0.0.1:8000", "http://10.1.2.3", "http://192.168.0.5:8000",
            "http://172.16.0.1", "http://172.31.255.255"
        )
        val refused = listOf(
            "", "not a url", "http://example.org", "http://1.2.3", "http://300.1.1.1",
            "http://a.b.c.d", "http://192.169.0.5", "http://172.15.0.1", "http://172.32.0.1",
            "http://192.168.1.2?token=1", "https://laya.example.org#frag",
            "https://user@laya.example.org", "https://user:secret@laya.example.org"
        )

        allowed.forEach { assertTrue(it, DecisionRouter.isAllowedLayaUrl(it)) }
        refused.forEach { assertFalse(it, DecisionRouter.isAllowedLayaUrl(it)) }
    }

    // ==================== Test doubles ====================

    private class Replying(
        override val provider: AiProvider,
        private val answer: String,
        private val onChat: (String) -> Unit = {}
    ) : AiServiceProvider {
        override suspend fun transcribe(pcmAudioData: ByteArray, languageCode: String): SpeechResult =
            SpeechResult.Error("unsupported")
        override suspend fun chat(userMessage: String): String { onChat(userMessage); return answer }
        override suspend fun analyzeImage(imageData: ByteArray, prompt: String): String = "unsupported"
        override fun clearHistory() = Unit
    }

    private class Throwing(
        override val provider: AiProvider,
        private val failure: Throwable
    ) : AiServiceProvider {
        override suspend fun transcribe(pcmAudioData: ByteArray, languageCode: String): SpeechResult =
            SpeechResult.Error("unsupported")
        override suspend fun chat(userMessage: String): String = throw failure
        override suspend fun analyzeImage(imageData: ByteArray, prompt: String): String = "unsupported"
        override fun clearHistory() = Unit
    }

    private class Failing(override val provider: AiProvider, private val detail: String) :
        BaseAiService("test", "test", ""), AiServiceProvider {
        override suspend fun chat(userMessage: String): String { lastChatError = detail; return detail }
        override suspend fun transcribe(pcmAudioData: ByteArray, languageCode: String) =
            SpeechResult.Error("unsupported")
        override suspend fun analyzeImage(imageData: ByteArray, prompt: String) = "unsupported"
    }
}
