package com.example.rokidphone.service.ai

import com.example.rokidphone.ai.catalog.ProviderApiException
import com.example.rokidphone.testutil.MockWebServerRule
import com.example.rokidphone.testutil.TestFixtures
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

    @Test fun newGoogleAuthKeysAreRedactedFromErrors() {
        assertEquals("credential *** rejected", ProviderApiException.sanitize("credential AQ.example_auth-key rejected"))
    }
}
