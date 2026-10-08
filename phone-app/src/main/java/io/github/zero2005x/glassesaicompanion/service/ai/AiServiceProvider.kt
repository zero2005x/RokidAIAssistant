package io.github.zero2005x.glassesaicompanion.service.ai

import io.github.zero2005x.glassesaicompanion.ai.catalog.ProviderErrorKind
import io.github.zero2005x.glassesaicompanion.data.AiProvider
import io.github.zero2005x.glassesaicompanion.service.SpeechResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * AI Service Unified Interface
 * All AI service providers must implement this interface
 */
interface AiServiceProvider {

    /**
     * Provider identifier
     */
    val provider: AiProvider

    /**
     * Speech to text
     * @param pcmAudioData PCM format audio data
     * @param languageCode Language code for speech recognition (e.g. "zh-TW", "en-US", "ko-KR")
     * @return Speech recognition result
     */
    suspend fun transcribe(pcmAudioData: ByteArray, languageCode: String = "zh-TW"): SpeechResult

    /**
     * Transcribe pre-encoded audio file (e.g. M4A, MP3, OGG)
     * Unlike transcribe() which expects raw PCM data, this accepts encoded audio with its MIME type.
     * Providers must override this when they support encoded input.
     * @param audioData Encoded audio data
     * @param mimeType MIME type of the audio (e.g. "audio/mp4", "audio/aac")
     * @param languageCode Language code for speech recognition
     * @return Speech recognition result
     */
    suspend fun transcribeAudioFile(audioData: ByteArray, mimeType: String, languageCode: String = "zh-TW"): SpeechResult {
        throw UnsupportedOperationException(
            "Encoded audio transcription is not implemented for $mimeType"
        )
    }

    /**
     * Streaming chat. Emits incremental [AiStreamEvent]s and finishes with
     * [AiStreamEvent.Completed] or [AiStreamEvent.Error].
     *
     * The default implementation is a compatibility wrapper around [chat] for
     * providers without a streaming adapter; streaming-capable services
     * override it with a real SSE/WebSocket implementation.
     */
    fun streamChat(userMessage: String): Flow<AiStreamEvent> = flow {
        val text = try {
            chat(userMessage)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(AiStreamEvent.Error(ProviderErrorKind.UNKNOWN, e.message ?: "chat failed"))
            return@flow
        }
        emit(AiStreamEvent.TextDelta(text))
        emit(AiStreamEvent.Completed(text))
    }

    /**
     * Text chat
     * @param userMessage User message
     * @return AI response
     */
    suspend fun chat(userMessage: String): String

    /**
     * Image understanding
     * @param imageData Image data (JPEG/PNG)
     * @param prompt User prompt
     * @return AI response
     */
    suspend fun analyzeImage(imageData: ByteArray, prompt: String = "Please describe this image"): String

    /**
     * Clear conversation history.
     *
     * Concurrency contract: [chat]/[streamChat] mutate the same conversation
     * state from coroutines, so implementations MUST make clearing safe to
     * call while a request/stream is in flight (e.g. guard history with a
     * mutex); a clear issued mid-stream takes effect for subsequent calls.
     */
    fun clearHistory()
}
