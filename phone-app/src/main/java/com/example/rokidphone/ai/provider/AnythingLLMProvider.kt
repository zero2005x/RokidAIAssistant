package com.example.rokidphone.ai.provider

import com.example.rokidphone.service.ai.NetworkClientFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale

/**
 * AnythingLLM provider adapter implementing Provider<ProviderSetting.AnythingLLM>.
 * Relays text queries to a configured AnythingLLM workspace and surfaces
 * source previews from the workspace's document index when present.
 */
class AnythingLLMProvider(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : Provider<ProviderSetting.AnythingLLM> {

    override val providerId: String = "anythingllm"
    override val displayName: String = "AnythingLLM"

    private val client = NetworkClientFactory.createClient()
    private val jsonMediaType = "application/json".toMediaType()

    override suspend fun listModels(setting: ProviderSetting.AnythingLLM): List<Model> = emptyList()

    override suspend fun validateSetting(setting: ProviderSetting.AnythingLLM): ValidationResult {
        if (!setting.isValid()) {
            return ValidationResult.Invalid(
                reason = "Server URL, API key, and workspace slug are all required",
                field = when {
                    setting.serverUrl.isBlank() -> "serverUrl"
                    setting.apiKey.isBlank() -> "apiKey"
                    else -> "workspaceSlug"
                }
            )
        }

        return withContext(ioDispatcher) {
            try {
                val request = Request.Builder()
                    .url("${setting.serverUrl.trimEnd('/')}/api/v1/auth")
                    .get()
                    .addHeader("Authorization", "Bearer ${setting.apiKey}")
                    .build()

                client.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> ValidationResult.Valid
                        response.code == 401 -> ValidationResult.Invalid(
                            reason = "Invalid API key: authentication failed",
                            field = "apiKey"
                        )
                        else -> ValidationResult.Invalid("Connection failed: HTTP ${response.code}")
                    }
                }
            } catch (e: Exception) {
                ValidationResult.Invalid("Connection failed: ${e.message}")
            }
        }
    }

    override suspend fun generateText(
        setting: ProviderSetting.AnythingLLM,
        messages: List<ChatMessage>,
        options: GenerationOptions
    ): GenerationResult {
        if (!setting.isValid()) {
            return GenerationResult.Error(
                message = "AnythingLLM not configured: server URL, API key, and workspace slug are required",
                code = "invalid_setting"
            )
        }

        return withContext(ioDispatcher) {
            try {
                val userMessage = messages.lastOrNull { it.role == MessageRole.USER }?.content.orEmpty()
                val requestBody = JSONObject()
                    .put("message", userMessage)
                    .put("mode", "chat")
                    .toString()

                val request = Request.Builder()
                    .url("${setting.serverUrl.trimEnd('/')}/api/v1/workspace/${setting.workspaceSlug}/chat")
                    .post(requestBody.toRequestBody(jsonMediaType))
                    .addHeader("Authorization", "Bearer ${setting.apiKey}")
                    .addHeader("Content-Type", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseBody = response.body.string()
                    if (response.isSuccessful) {
                        val json = JSONObject(responseBody)
                        val text = json.optNullableString("textResponse")
                            .ifBlank { json.optNullableString("response") }
                        GenerationResult.Success(
                            text = appendSources(text, json),
                            finishReason = FinishReason.STOP
                        )
                    } else {
                        GenerationResult.Error(
                            message = "Request failed: HTTP ${response.code}",
                            code = response.code.toString(),
                            retryable = response.code >= 500
                        )
                    }
                }
            } catch (e: Exception) {
                GenerationResult.Error(
                    message = "Connection error: ${e.message}",
                    code = "connection_error",
                    retryable = true
                )
            }
        }
    }

    override fun streamText(
        setting: ProviderSetting.AnythingLLM,
        messages: List<ChatMessage>,
        options: GenerationOptions
    ): Flow<MessageChunk> = flow {
        when (val result = generateText(setting, messages, options)) {
            is GenerationResult.Success -> emit(
                MessageChunk(
                    text = result.text,
                    isComplete = true,
                    finishReason = result.finishReason
                )
            )
            is GenerationResult.Error -> emit(
                MessageChunk(
                    text = "",
                    isComplete = true,
                    finishReason = FinishReason.ERROR,
                    error = result.message
                )
            )
        }
    }

    override suspend fun analyzeImage(
        setting: ProviderSetting.AnythingLLM,
        imageData: ByteArray,
        prompt: String,
        options: GenerationOptions
    ): GenerationResult = GenerationResult.Error(
        message = "AnythingLLM does not support image analysis",
        code = "unsupported"
    )

    override suspend fun transcribe(
        setting: ProviderSetting.AnythingLLM,
        audioData: ByteArray,
        options: TranscriptionOptions
    ): TranscriptionResult = TranscriptionResult.Error(
        message = "AnythingLLM does not support speech recognition",
        code = "unsupported"
    )

    /**
     * The current provider result model in this branch has no citation field.
     * Keep source previews attached to the returned text without introducing a new type.
     */
    private fun appendSources(text: String, json: JSONObject): String {
        val sources = json.optJSONArray("sources") ?: return text
        if (sources.length() == 0) return text

        val builder = StringBuilder(text).append("\n\n**Sources:**")
        for (i in 0 until sources.length()) {
            val source = sources.optJSONObject(i) ?: continue
            val title = source.optNullableString("title").ifBlank { "Unknown" }
            val chunkSource = source.optNullableString("chunkSource")
            val url = source.optNullableString("url")
            val score = if (source.has("score") && !source.isNull("score")) source.optDouble("score") else Double.NaN

            builder.append("\n- ").append(title)
            when {
                url.isNotBlank() -> builder.append(" - ").append(url)
                chunkSource.isNotBlank() -> builder.append(" (").append(chunkSource).append(")")
            }
            if (!score.isNaN()) {
                builder.append(" [score: ").append(String.format(Locale.US, "%.2f", score)).append("]")
            }
        }

        return builder.toString()
    }

    private fun JSONObject.optNullableString(name: String): String {
        return if (has(name) && !isNull(name)) optString(name) else ""
    }
}
