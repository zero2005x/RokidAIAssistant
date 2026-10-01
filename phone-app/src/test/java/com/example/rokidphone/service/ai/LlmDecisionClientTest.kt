package com.example.rokidphone.service.ai

import com.example.rokidphone.data.ApiSettings
import com.example.rokidphone.data.DecisionBackend
import com.example.rokidphone.testutil.MockWebServerRule
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LlmDecisionClientTest {
    @get:Rule val serverRule = MockWebServerRule()
    private fun client() = LlmDecisionClient(OkHttpClient(), serverRule.server.url("/models").toString().trimEnd('/'),
        serverRule.server.url("/v1/responses").toString())
    private val decision = """{"tier":"quality","certainty":"clear"}"""
    private fun openai(text: String = decision, status: String = "completed") = JSONObject().put("status", status)
        .put("output", JSONArray().put(JSONObject().put("type", "message")
            .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", text))))).toString()

    @Test fun openaiUsesResponsesSchemaAndExistingKeyWithoutClaimingProbability() = runBlocking {
        serverRule.server.enqueue(MockResponse(body = openai()))
        assertEquals("quality", client().decide("比較兩個方案的風險", ApiSettings(decisionBackend = DecisionBackend.OPENAI, openaiApiKey = " existing ")))
        val request = serverRule.server.takeRequest()
        assertEquals("Bearer existing", request.headers["Authorization"])
        val body = JSONObject(request.body.readUtf8())
        assertFalse(body.getBoolean("store"))
        assertEquals("比較兩個方案的風險", body.getString("input"))
        assertTrue(body.getJSONObject("text").getJSONObject("format").getBoolean("strict"))
        assertFalse(body.toString().contains("probabilities"))
    }

    @Test fun geminiUsesKeyHeaderAndRejectsTruncatedOutputs() = runBlocking {
        val candidate = JSONObject().put("finishReason", "STOP").put("content", JSONObject()
            .put("parts", JSONArray().put(JSONObject().put("text", decision))))
        serverRule.server.enqueue(MockResponse(body = JSONObject().put("candidates", JSONArray().put(candidate)).toString()))
        val settings = ApiSettings(decisionBackend = DecisionBackend.GEMINI, geminiApiKey = "key")
        assertEquals("quality", client().decide("難題", settings))
        val request = serverRule.server.takeRequest()
        assertEquals("key", request.headers["x-goog-api-key"])
        assertFalse(request.path.toString().contains("key="))
        assertEquals("application/json", JSONObject(request.body.readUtf8()).getJSONObject("generationConfig").getString("responseMimeType"))
        candidate.put("finishReason", "MAX_TOKENS")
        serverRule.server.enqueue(MockResponse(body = JSONObject().put("candidates", JSONArray().put(candidate)).toString()))
        assertNull(client().decide("難題", settings))
    }

    @Test fun uncertainIncompleteOrActionInjectedOutputsCannotSelectModel() = runBlocking {
        for (text in listOf("""{"tier":"fast","certainty":"uncertain"}""",
            """{"tier":"fast","certainty":"clear","action":"capture_photo"}""",
            """{"tier":"invented","certainty":"clear"}""")) {
            serverRule.server.enqueue(MockResponse(body = openai(text)))
            assertNull(client().decide("Question", ApiSettings(decisionBackend = DecisionBackend.OPENAI, openaiApiKey = "key")))
        }
        serverRule.server.enqueue(MockResponse(body = openai(status = "incomplete")))
        assertNull(client().decide("Question", ApiSettings(decisionBackend = DecisionBackend.OPENAI, openaiApiKey = "key")))
    }

    @Test fun routingStoresClassificationReasonWithoutConfidencePercent() = runBlocking {
        serverRule.server.enqueue(MockResponse(body = openai()))
        val settings = ApiSettings(geminiApiKey = "primary", openaiApiKey = "key", decisionRoutingEnabled = true,
            decisionBackend = DecisionBackend.OPENAI,
            qualityRoutingModel = com.example.rokidphone.data.RoutingModel(com.example.rokidphone.data.AiProvider.GEMINI, "gemini-3.8-flash"))
        val primary = object : AiServiceProvider by GeminiService("primary") {
            override suspend fun chat(message: String) = "answer"
        }
        val result = DecisionRouter(openaiDecisionEndpoint = serverRule.server.url("/").toString()).reply("question", settings, primaryService = primary)
        assertEquals("selected_llm", JSONObject(result.reason).getString("code"))
    }
}
