package com.example.rokidphone.service.ai

import com.example.rokidphone.ai.catalog.ProviderApiException
import com.example.rokidphone.data.ApiSettings
import com.example.rokidphone.data.DecisionBackend
import com.example.rokidphone.testutil.MockWebServerRule
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The input guards, error mapping and output-shape checks of [LlmDecisionClient]. */
@RunWith(RobolectricTestRunner::class)
class LlmDecisionClientBranchesTest {
    @get:Rule val serverRule = MockWebServerRule()

    private fun client() = LlmDecisionClient(
        OkHttpClient(),
        serverRule.server.url("/models").toString().trimEnd('/'),
        serverRule.server.url("/v1/responses").toString()
    )

    private val openai = ApiSettings(decisionBackend = DecisionBackend.OPENAI, openaiApiKey = "key")
    private val gemini = ApiSettings(decisionBackend = DecisionBackend.GEMINI, geminiApiKey = "key")
    private val clear = """{"tier":"balanced","certainty":"clear"}"""

    private fun message(vararg parts: JSONObject) = JSONObject().put("type", "message")
        .put("content", JSONArray(parts.toList()))

    private fun outputText(text: String) = JSONObject().put("type", "output_text").put("text", text)

    private fun responses(vararg items: JSONObject, error: Any = JSONObject.NULL) =
        JSONObject().put("status", "completed").put("error", error)
            .put("output", JSONArray(items.toList())).toString()

    private fun geminiBody(vararg parts: JSONObject) = JSONObject().put("candidates", JSONArray().put(
        JSONObject().put("finishReason", "STOP")
            .put("content", JSONObject().put("parts", JSONArray(parts.toList()))))).toString()

    // ==================== Guards ====================

    @Test
    fun missingCredentialsOrAnOversizedQuestionNeverReachTheNetwork() = runBlocking {
        assertNull(client().decide("question", openai.copy(openaiApiKey = "  ")))
        assertNull(client().decide("question", gemini.copy(geminiApiKey = "")))
        assertNull(client().decide("question", openai.copy(decisionOpenaiModel = "")))
        assertNull(client().decide("question", gemini.copy(decisionGeminiModel = "  ")))
        assertNull(client().decide("x".repeat(4001), openai))

        assertEquals(0, serverRule.server.requestCount)
    }

    @Test
    fun aQuestionAtTheLimitIsStillClassified() = runBlocking {
        serverRule.server.enqueue(MockResponse(body = responses(message(outputText(clear)))))

        assertEquals("balanced", client().decide("x".repeat(4000), openai))
    }

    // ==================== Errors ====================

    @Test
    fun anHttpErrorIsRaisedAsAProviderException() = runBlocking {
        serverRule.server.enqueue(MockResponse(code = 429, body = """{"error":{"message":"slow down"}}""",
            headers = okhttp3.Headers.headersOf("Retry-After", "7")))

        try {
            client().decide("question", openai)
            fail("a rejected request must not look like a classification")
        } catch (e: ProviderApiException) {
            assertEquals(429, e.httpStatus)
            assertEquals(7_000L, e.retryAfterMs)
        }
    }

    // ==================== Output shapes ====================

    @Test
    fun geminiThinkingPartsAreIgnoredAndTheRemainingTextIsJoined() = runBlocking {
        serverRule.server.enqueue(MockResponse(body = geminiBody(
            JSONObject().put("text", "let me think").put("thought", true),
            JSONObject().put("text", """{"tier":"fast",""" ),
            JSONObject().put("text", """"certainty":"clear"}""")
        )))

        assertEquals("fast", client().decide("question", gemini))
    }

    @Test
    fun openaiOutputThatIsNotACompletedMessageIsRejected() = runBlocking {
        // A reasoning item before the message is skipped, not rejected.
        serverRule.server.enqueue(MockResponse(body = responses(
            JSONObject().put("type", "reasoning"), message(outputText(clear)))))
        assertEquals("balanced", client().decide("question", openai))

        // An error object, even on a completed response.
        serverRule.server.enqueue(MockResponse(body = responses(
            message(outputText(clear)), error = JSONObject().put("message", "boom"))))
        assertNull(client().decide("question", openai))

        // A refusal instead of output text.
        serverRule.server.enqueue(MockResponse(body = responses(
            message(JSONObject().put("type", "refusal").put("refusal", "no")))))
        assertNull(client().decide("question", openai))
    }

    @Test
    fun aTierThatIsNotAStringCannotSelectAModel() = runBlocking {
        serverRule.server.enqueue(MockResponse(body = responses(
            message(outputText("""{"tier":1,"certainty":"clear"}""")))))

        assertNull(client().decide("question", openai))
    }
}
