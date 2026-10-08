package io.github.zero2005x.glassesaicompanion.service.ai

import android.util.Log
import io.github.zero2005x.glassesaicompanion.data.AiProvider
import io.github.zero2005x.glassesaicompanion.data.ApiSettings
import io.github.zero2005x.glassesaicompanion.data.DecisionBackend
import io.github.zero2005x.glassesaicompanion.data.RoutingModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import io.github.zero2005x.glassesaicompanion.ai.catalog.ProviderApiException
import io.github.zero2005x.glassesaicompanion.data.RoutingReason

data class RoutedReply(
    val text: String,
    val provider: AiProvider,
    val modelId: String,
    val reason: String,
    val error: String? = null
)

/** A Jev-compatible decision layer. Only text is sent to the decision endpoint. */
class DecisionRouter(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(3500, TimeUnit.MILLISECONDS)
        .build(),
    private val jevEndpoint: String = "https://api.typesafe.ai/v1/systemone",
    private val geminiDecisionBase: String = "https://generativelanguage.googleapis.com/v1beta/models",
    private val openaiDecisionEndpoint: String = "https://api.openai.com/v1/responses",
    private val serviceFactory: (ApiSettings) -> AiServiceProvider = AiServiceFactory::createService,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    // Also enforce the budget on injected clients, and prevent a LAN endpoint redirecting to HTTP elsewhere.
    private val decisionClient = client.newBuilder().callTimeout(3500, TimeUnit.MILLISECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    suspend fun reply(
        question: String,
        settings: ApiSettings,
        history: List<Pair<String, String>> = emptyList(),
        primaryService: AiServiceProvider? = null
    ): RoutedReply {
        val primary = RoutingModel(settings.aiProvider, settings.getCurrentModelId())
        val decision = if (settings.decisionRoutingEnabled) decide(question, settings) else null
        val slot = when (decision?.first) {
            "fast" -> settings.fastRoutingModel
            "balanced" -> settings.balancedRoutingModel
            "quality" -> settings.qualityRoutingModel
            else -> null
        }
        val selected = slot?.takeIf { usable(it, settings) } ?: primary
        val reason = when {
            !settings.decisionRoutingEnabled -> RoutingReason.encode("disabled")
            decision == null -> RoutingReason.encode("uncertain")
            slot == null -> RoutingReason.encode("empty_slot", tier = decision.first)
            selected == primary && !usable(slot, settings) ->
                RoutingReason.encode("unconfigured_slot", tier = decision.first)
            settings.decisionBackend in setOf(DecisionBackend.GEMINI, DecisionBackend.OPENAI) ->
                RoutingReason.encode("selected_llm", settings.decisionBackend.name, decision.first)
            else -> RoutingReason.encode("selected", settings.decisionBackend.name, decision.first,
                (decision.second * 100).toInt())
        }
        val text = tryChat(selected, question, settings, history,
            if (selected == primary) primaryService else null)
        if (text.error == null) return RoutedReply(text.text.orEmpty(), selected.provider, selected.modelId, reason)
        if (selected != primary) {
            val fallback = tryChat(primary, question, settings, history, primaryService)
            if (fallback.error == null) {
                return RoutedReply(fallback.text.orEmpty(), primary.provider, primary.modelId,
                    RoutingReason.encode("fallback", model = "${selected.provider.name} / ${selected.modelId}"))
            }
            return RoutedReply("", primary.provider, primary.modelId, RoutingReason.encode("failed"), fallback.error)
        }
        return RoutedReply("", primary.provider, primary.modelId, RoutingReason.encode("failed"), text.error)
    }

    private data class ChatAttempt(val text: String? = null, val error: String? = null)

    private suspend fun tryChat(
        model: RoutingModel,
        question: String,
        settings: ApiSettings,
        history: List<Pair<String, String>>,
        existingService: AiServiceProvider?
    ): ChatAttempt {
        if (!usable(model, settings) && existingService == null) return ChatAttempt(error = "not_configured")
        return try {
            val service = existingService ?: serviceFactory(
                settings.copy(aiProvider = model.provider)
                    .withModelForProvider(model.provider, model.modelId)
            )
            val response = if (service is BaseAiService) {
                service.seedHistory(history)
                service.chat(question)
            } else if (history.isNotEmpty()) {
                service.clearHistory()
                val context = history.takeLast(6).joinToString("\n") { "${it.first}: ${it.second}" }
                service.chat("Previous conversation:\n$context\n\nCurrent question: $question")
            } else {
                service.clearHistory()
                service.chat(question)
            }
            val serviceError = (service as? ChatErrorSource)?.lastChatError
            if (serviceError != null || response.isBlank() || response.startsWith("Error:", ignoreCase = true)) {
                ChatAttempt(error = serviceError ?: response.ifBlank { "empty_response" })
            } else ChatAttempt(text = response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("DecisionRouter", "Chat failed for ${model.provider}: ${e.javaClass.simpleName}")
            ChatAttempt(error = ProviderApiException.sanitize(e.message).ifBlank { e.javaClass.simpleName })
        }
    }

    private fun usable(model: RoutingModel, settings: ApiSettings): Boolean =
        model.modelId.isNotBlank() &&
            model.provider != AiProvider.GEMINI_LIVE &&
            settings.isProviderConfigured(model.provider)

    /** The tier to answer with and the confidence behind it, or null when the choice is uncertain. */
    private suspend fun decide(question: String, settings: ApiSettings): Pair<String, Double>? =
        withContext(ioDispatcher) {
            when (settings.decisionBackend) {
                DecisionBackend.GEMINI, DecisionBackend.OPENAI -> decideWithLlm(question, settings)
                DecisionBackend.JEV, DecisionBackend.LAYA -> decideWithSystemOne(question, settings)
            }
        }

    /** A structured classification by the user's own Gemini or OpenAI model; it carries no probability. */
    private suspend fun decideWithLlm(question: String, settings: ApiSettings): Pair<String, Double>? = try {
        LlmDecisionClient(decisionClient, geminiDecisionBase, openaiDecisionEndpoint, ioDispatcher)
            .decide(question, settings)?.let { it to 0.0 }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("DecisionRouter", "LLM decision failed: ${e.javaClass.simpleName}, HTTP ${(e as? ProviderApiException)?.httpStatus}")
        null
    }

    /** Jev and Laya speak the same System One protocol; only the endpoint and the key differ. */
    private fun decideWithSystemOne(question: String, settings: ApiSettings): Pair<String, Double>? {
        val (endpoint, key) = systemOneTarget(settings) ?: return null
        return try {
            decisionClient.newCall(systemOneRequest(endpoint, key, question)).execute().use { response ->
                if (response.isSuccessful) parseSystemOneAnswer(JSONObject(response.body.string())) else null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("DecisionRouter", "Decision request failed: ${e.javaClass.simpleName}")
            null
        }
    }

    /** Where to ask and with which key, or null when the backend is not usable. */
    private fun systemOneTarget(settings: ApiSettings): Pair<String, String>? = when (settings.decisionBackend) {
        DecisionBackend.JEV -> settings.jevApiKey.takeIf { it.isNotBlank() }?.let { jevEndpoint to it }
        DecisionBackend.LAYA -> {
            val base = settings.layaBaseUrl.trim().trimEnd('/')
            if (isAllowedLayaUrl(base)) "$base/v1/systemone" to settings.layaApiKey else null
        }
        else -> null
    }

    private fun systemOneRequest(endpoint: String, key: String, question: String): Request {
        val criteria = JSONObject()
            .put("fast", "Simple factual, short or routine question that needs little synthesis")
            .put("balanced", "Moderate question requiring explanation or several reasoning steps")
            .put("quality", "Complex, ambiguous, high-stakes or multi-step question where answer quality matters most")
        val body = JSONObject()
            .put("state", question.take(MAX_QUESTION_CHARS))
            .put("model", "jev-latest")
            .put("questions", JSONObject().put("difficulty", JSONObject()
                .put("type", "choice")
                .put("instructions", "Choose the model tier needed to answer this user's question accurately. When uncertain, prefer quality over speed.")
                .put("criteria", criteria)))
        val builder = Request.Builder().url(endpoint).post(body.toString().toRequestBody(jsonMediaType))
        if (key.isNotBlank()) builder.header("Authorization", "Bearer $key")
        return builder.build()
    }

    private fun parseSystemOneAnswer(response: JSONObject): Pair<String, Double>? {
        val answer = response.getJSONObject("answers").getJSONObject("difficulty")
        val choice = answer.getString("choice").lowercase(Locale.ROOT)
        val probabilities = answer.getJSONObject("probabilities")
        val probability = probabilities.getDouble(choice)
        val confidence = answer.optDouble("confidence", 0.0)
        val values = TIERS.map { probabilities.getDouble(it) }
        return if (isUsableDecision(choice, probability, values, confidence)) choice to confidence else null
    }

    /**
     * Rejects anything that is not a proper probability distribution, is not clearly led by the
     * chosen tier, or lacks the confidence that tier needs (the fast tier asks for more than quality).
     */
    private fun isUsableDecision(choice: String, probability: Double, values: List<Double>, confidence: Double): Boolean {
        val threshold = if (choice == "quality") QUALITY_CONFIDENCE else OTHER_CONFIDENCE
        return choice in TIERS &&
            values.all { it.isFinite() && it in 0.0..1.0 } &&
            abs(values.sum() - 1.0) <= PROBABILITY_SUM_TOLERANCE &&
            probability >= values.max() && probability >= MIN_TIER_PROBABILITY &&
            confidence.isFinite() && confidence in threshold..1.0
    }

    companion object {
        private val TIERS = listOf("fast", "balanced", "quality")
        private const val MAX_QUESTION_CHARS = 4000
        private const val QUALITY_CONFIDENCE = 0.30
        private const val OTHER_CONFIDENCE = 0.50
        private const val MIN_TIER_PROBABILITY = 0.55
        private const val PROBABILITY_SUM_TOLERANCE = 0.02

        fun isAllowedLayaUrl(base: String): Boolean {
            val url = base.toHttpUrlOrNull() ?: return false
            if (url.username.isNotEmpty() || url.password.isNotEmpty() ||
                url.query != null || url.fragment != null) return false
            if (url.isHttps) return true
            val host = url.host
            if (host == "localhost" || host == "::1") return true
            val octets = host.split('.').map { it.toIntOrNull() }
            return octets.size == 4 && octets.all { it != null && it in 0..255 } &&
                (octets[0] == 10 || octets[0] == 127 ||
                    (octets[0] == 192 && octets[1] == 168) ||
                    (octets[0] == 172 && octets[1]!! in 16..31))
        }
    }
}
