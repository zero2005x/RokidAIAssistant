package com.example.rokidphone

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.rokidphone.data.ApiSettings
import com.example.rokidphone.data.AiProvider
import com.example.rokidphone.data.DecisionBackend
import com.example.rokidphone.data.SettingsRepository
import com.example.rokidphone.service.ai.AiServiceFactory
import com.example.rokidphone.service.ai.ChatErrorSource
import com.example.rokidphone.service.ai.LlmDecisionClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Opt-in paid network checks. Credentials remain inside the target application's encrypted store. */
@RunWith(AndroidJUnit4::class)
class ProviderConnectivitySmokeTest {
    private fun settings(): ApiSettings {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyProviders") == "true")
        return SettingsRepository.getInstance(InstrumentationRegistry.getInstrumentation().targetContext).getSettings()
    }

    @Test fun geminiChatWithStoredKey() = runBlocking { verifyChat(AiProvider.GEMINI, settings()) }
    @Test fun openaiChatWithStoredKey() = runBlocking { verifyChat(AiProvider.OPENAI, settings()) }

    private suspend fun verifyChat(provider: AiProvider, stored: ApiSettings) {
        assumeTrue(stored.isProviderConfigured(provider))
        val service = AiServiceFactory.createService(stored.copy(aiProvider = provider, systemPrompt = "", maxTokens = 1024)
            .withModelForProvider(provider, stored.getModelIdForProvider(provider)))
        val answer = service.chat("Reply only with the number: 7 + 5.")
        assertNull("$provider: ${(service as? ChatErrorSource)?.lastChatError}", (service as? ChatErrorSource)?.lastChatError)
        assertTrue("$provider did not return the expected synthetic answer", answer.contains("12"))
    }

    @Test fun geminiStructuredClassification() = runBlocking { verifyDecision(DecisionBackend.GEMINI, settings()) }
    @Test fun openaiStructuredClassification() = runBlocking { verifyDecision(DecisionBackend.OPENAI, settings()) }

    private suspend fun verifyDecision(backend: DecisionBackend, stored: ApiSettings) {
        assumeTrue(stored.isProviderConfigured(if (backend == DecisionBackend.GEMINI) AiProvider.GEMINI else AiProvider.OPENAI))
        // Separate contract/connectivity check from the production 3.5-second routing budget.
        var httpStatus: Int? = null
        val client = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).addInterceptor { chain ->
            chain.proceed(chain.request()).also { httpStatus = it.code }
        }.build()
        val tier = LlmDecisionClient(client).decide("請比較兩個軟體架構的取捨與多步驟遷移風險。", stored.copy(decisionBackend = backend))
        assertTrue("$backend classification failed (HTTP $httpStatus)", tier in listOf("balanced", "quality"))
    }
}
