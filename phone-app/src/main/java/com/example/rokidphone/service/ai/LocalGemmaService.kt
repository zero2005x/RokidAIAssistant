package com.example.rokidphone.service.ai

import com.example.rokidphone.ai.catalog.ProviderErrorKind
import com.example.rokidphone.data.AiProvider
import com.example.rokidphone.service.SpeechErrorCode
import com.example.rokidphone.service.SpeechResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Pluggable on-device inference backend.
 *
 * Implementations wrap a concrete engine (MediaPipe LLM Inference, llama.cpp,
 * ...). The engine is intentionally decoupled from [LocalGemmaService] so the
 * service — and its capability/degradation contract — can be unit-tested
 * without any native library, and so a backend can be swapped without touching
 * the factory or the provider registry.
 */
interface LocalInferenceEngine {
    /** Human-readable model identifier currently loaded. */
    val modelId: String

    /** Blocking-style single-shot generation. */
    suspend fun generate(prompt: String): String

    /**
     * Streaming generation. Emits incremental text fragments as the model
     * decodes tokens. The default bridges to [generate] for engines without a
     * token callback.
     */
    fun generateStream(prompt: String): Flow<String> = flow { emit(generate(prompt)) }

    /** Reset any conversational state held by the engine. */
    fun reset() {}
}

/**
 * On-device Gemma service.
 *
 * Implements the full [AiServiceProvider] contract for the local provider.
 * Text chat is delegated to a [LocalInferenceEngine] when one is available;
 * every capability the local text model cannot serve (speech-to-text, image
 * understanding) degrades gracefully with a clear, localized-safe message
 * rather than throwing.
 *
 * When no engine is wired (no model installed, or the native backend is not
 * present in this build), [chat]/[streamChat] return a clear, actionable
 * message instead of a cloud fallback, so the user stays in control of whether
 * anything leaves the device.
 */
class LocalGemmaService(
    private val modelId: String,
    @Suppress("unused") private val systemPrompt: String = "",
    private val engine: LocalInferenceEngine? = null
) : AiServiceProvider, ChatErrorSource {

    override val provider: AiProvider = AiProvider.LOCAL_GEMMA
    override var lastChatError: String? = null
        private set

    override suspend fun transcribe(pcmAudioData: ByteArray, languageCode: String): SpeechResult =
        SpeechResult.Error(
            message = "On-device Gemma does not support speech recognition",
            errorCode = SpeechErrorCode.NOT_SUPPORTED
        )

    override suspend fun chat(userMessage: String): String {
        lastChatError = null
        val engine = engine ?: return modelUnavailableMessage().also { lastChatError = it }
        return engine.generate(userMessage)
    }

    override fun streamChat(userMessage: String): Flow<AiStreamEvent> = flow {
        val engine = engine
        if (engine == null) {
            emit(
                AiStreamEvent.Error(
                    kind = ProviderErrorKind.MODEL_UNAVAILABLE,
                    message = modelUnavailableMessage()
                )
            )
            return@flow
        }
        val builder = StringBuilder()
        engine.generateStream(userMessage).collect { delta ->
            builder.append(delta)
            emit(AiStreamEvent.TextDelta(delta))
        }
        emit(AiStreamEvent.Completed(builder.toString()))
    }

    override suspend fun analyzeImage(imageData: ByteArray, prompt: String): String =
        "On-device Gemma ($modelId) is a text-only model and cannot analyze images"

    override fun clearHistory() {
        engine?.reset()
    }

    private fun modelUnavailableMessage(): String =
        "No on-device model is loaded. Install a Gemma model file (.task/.gguf) " +
            "for on-device inference, or switch to a cloud provider in Settings."
}
