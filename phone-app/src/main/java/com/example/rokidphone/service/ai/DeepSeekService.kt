package com.example.rokidphone.service.ai

import com.example.rokidphone.data.AiProvider
import org.json.JSONObject

/**
 * DeepSeek adapter.
 *
 * DeepSeek is OpenAI-wire-compatible, so the heavy lifting stays in
 * [OpenAiCompatibleService]. This thin subclass only handles two quirks:
 *
 * 1. `deepseek-reasoner` and `deepseek-v3.2-speciale` reject the `temperature`
 *    field; it is stripped from the request body.
 * 2. Those same reasoner models return a separate `reasoning_content` field
 *    on the assistant message. We expose it via [lastReasoningContent] as a
 *    preview of the chain-of-thought but deliberately do **not** concatenate
 *    it into the final answer stored in history — AR voice replies should
 *    read back the polished `content` only.
 */
class DeepSeekService(
    apiKey: String,
    baseUrl: String = "https://api.deepseek.com/",
    modelId: String,
    systemPrompt: String = "",
    temperature: Float = 0.7f,
    maxTokens: Int = 2048,
    topP: Float = 1.0f,
    frequencyPenalty: Float = 0.0f,
    presencePenalty: Float = 0.0f
) : OpenAiCompatibleService(
    apiKey = apiKey,
    baseUrl = baseUrl,
    modelId = modelId,
    systemPrompt = systemPrompt,
    providerType = AiProvider.DEEPSEEK,
    temperature = temperature,
    maxTokens = maxTokens,
    topP = topP,
    frequencyPenalty = frequencyPenalty,
    presencePenalty = presencePenalty
) {

    /**
     * Most recent `reasoning_content` returned by a reasoner model, or null if
     * the last response had none (e.g. `deepseek-chat`). UI layers can surface
     * this as a "thinking…" preview separate from the main answer.
     */
    @Volatile
    var lastReasoningContent: String? = null
        private set

    override fun postProcessRequestJson(json: JSONObject) {
        if (isReasonerModel(modelId)) {
            // DeepSeek reasoner/speciale reject `temperature` (and related
            // sampling params) — strip anything that might have been set.
            json.remove("temperature")
            json.remove("top_p")
            json.remove("frequency_penalty")
            json.remove("presence_penalty")
        }
    }

    override fun onAssistantMessage(messageObj: JSONObject): String? {
        lastReasoningContent = if (isReasonerModel(modelId)) {
            messageObj.optString("reasoning_content", "").takeIf { it.isNotBlank() }
        } else {
            null
        }
        // Return null to keep default behaviour: history/return value use `content` only,
        // so reasoning_content never leaks into the final answer.
        return null
    }

    companion object {
        /**
         * Models that emit `reasoning_content` and reject `temperature`.
         * Keep in sync with [com.example.rokidphone.data.AIModel.DeepSeek.isReasoner].
         */
        fun isReasonerModel(modelId: String): Boolean =
            modelId == "deepseek-reasoner" || modelId == "deepseek-v3.2-speciale"
    }
}
