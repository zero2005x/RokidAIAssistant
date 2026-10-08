package io.github.zero2005x.glassesaicompanion.service.ai

import io.github.zero2005x.glassesaicompanion.ai.catalog.ProviderApiException
import io.github.zero2005x.glassesaicompanion.testutil.MockWebServerRule
import io.github.zero2005x.glassesaicompanion.testutil.TestFixtures
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImageFailureTest {
    @get:Rule val server = MockWebServerRule()

    @Test fun geminiPhotoAuthenticationFailureIsNotRetriedAndPreservesCause() = runBlocking {
        server.server.enqueue(MockResponse(code = 401, body = """{"error":{"message":"Unsupported authentication","code":401}}"""))
        val service = GeminiService("key", baseUrl = server.baseUrl)
        service.analyzeImage(TestFixtures.createTestJpeg(), "describe")
        assertTrue(service.lastChatError!!.contains("HTTP 401"))
        assertTrue(service.lastChatError!!.contains("Unsupported authentication"))
        assertEquals(1, server.server.requestCount)
    }

    @Test fun openaiPhotoAuthenticationFailureDoesNotFallBackToAnotherApi() = runBlocking {
        server.server.enqueue(MockResponse(code = 401, body = """{"error":{"message":"Incorrect API key","code":"invalid_api_key"}}"""))
        val service = OpenAiCompatibleService("key", server.baseUrl, "gpt-6-luna", useResponsesApi = true)
        service.analyzeImage(TestFixtures.createTestJpeg(), "describe")
        assertTrue(service.lastChatError!!.contains("HTTP 401"))
        assertEquals(1, server.server.requestCount)
    }

    @Test fun geminiRefusesDataThatIsNotAnImageWithoutCallingTheApi() = runBlocking {
        val service = GeminiService("key", baseUrl = server.baseUrl)

        val answer = service.analyzeImage(ByteArray(16), "describe")

        assertTrue(answer.startsWith("Sorry, unable to analyze this image:"))
        assertNotNull(service.lastChatError)
        assertEquals(0, server.server.requestCount)
    }

    @Test fun geminiPhotoWaitsAsLongAsTheServerAsksThenSucceeds() = runBlocking {
        server.server.enqueue(MockResponse(code = 503, body = """{"error":{"message":"overloaded"}}""",
            headers = okhttp3.Headers.headersOf("Retry-After", "0")))
        server.server.enqueue(MockResponse(code = 200, body = TestFixtures.MockResponses.geminiChatSuccess("a red bicycle")))
        val service = GeminiService("key", baseUrl = server.baseUrl)

        val answer = service.analyzeImage(TestFixtures.createTestJpeg(), "describe")

        assertEquals("a red bicycle", answer)
        assertEquals(2, server.server.requestCount)
    }

    @Test fun newGoogleAuthKeysAreRedactedFromErrors() {
        assertEquals("credential *** rejected", ProviderApiException.sanitize("credential AQ.example_auth-key rejected"))
    }
}
