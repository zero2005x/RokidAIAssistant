package io.github.zero2005x.glassesaicompanion.service.ai

import io.github.zero2005x.glassesaicompanion.data.AiProvider
import org.json.JSONObject

/**
 * Perplexity Sonar service.
 *
 * Sonar chat completions are OpenAI-compatible, but responses carry typed
 * metadata — `citations` (URL list) and `search_results` (title+url) — that
 * must never be dropped. Until the UI renders them natively they are
 * appended to the answer as a readable source list, and streaming emits
 * [AiStreamEvent.Citation] events.
 *
 * Note: `/v1/models` on api.perplexity.ai reflects the Agent API, not the
 * Sonar chat models, so the Sonar catalog is fallback-driven (verified
 * 2026-08-02) plus manual model entry.
 */
class PerplexityService(
    apiKey: String,
    baseUrl: String = "https://api.perplexity.ai/",
    modelId: String,
    systemPrompt: String = "",
    temperature: Float = 0.7f,
    maxTokens: Int = 2048,
    topP: Float = 1.0f
) : OpenAiCompatibleService(
    apiKey = apiKey,
    baseUrl = baseUrl,
    modelId = modelId,
    systemPrompt = systemPrompt,
    providerType = AiProvider.PERPLEXITY,
    temperature = temperature,
    maxTokens = maxTokens,
    topP = topP
) {

    override fun augmentResponseText(fullJson: JSONObject, text: String): String {
        val sources = mutableListOf<String>()

        fullJson.optJSONArray("search_results")?.let { results ->
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val title = item.optString("title").ifBlank { null }
                val url = item.optString("url").ifBlank { null } ?: continue
                sources += if (title != null) "$title — $url" else url
            }
        }

        if (sources.isEmpty()) {
            fullJson.optJSONArray("citations")?.let { citations ->
                for (i in 0 until citations.length()) {
                    citations.optString(i).takeIf { it.isNotBlank() }?.let { sources += it }
                }
            }
        }

        if (sources.isEmpty()) return text
        val formatted = sources.mapIndexed { index, s -> "${index + 1}. $s" }
        return text + "\n\nSources:\n" + formatted.joinToString("\n")
    }
}
