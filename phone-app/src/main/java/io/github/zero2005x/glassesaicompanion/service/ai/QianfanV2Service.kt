package io.github.zero2005x.glassesaicompanion.service.ai

import io.github.zero2005x.glassesaicompanion.data.AiProvider

/**
 * Baidu Qianfan v2 service.
 *
 * Qianfan v2 is OpenAI-compatible (bearer API key, `/v2/chat/completions`,
 * `/v2/models`, SSE streaming, `image_url` vision input), so the wire work
 * stays in [OpenAiCompatibleService]. The legacy API Key + Secret Key OAuth
 * flow remains available through [BaiduService] for existing users until
 * they add a Qianfan API key (see Settings migration).
 */
class QianfanV2Service(
    apiKey: String,
    baseUrl: String = DEFAULT_BASE_URL,
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
    providerType = AiProvider.BAIDU,
    temperature = temperature,
    maxTokens = maxTokens,
    topP = topP,
    frequencyPenalty = frequencyPenalty,
    presencePenalty = presencePenalty
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://qianfan.baidubce.com/v2/"
    }
}
