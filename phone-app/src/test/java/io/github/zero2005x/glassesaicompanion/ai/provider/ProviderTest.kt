package io.github.zero2005x.glassesaicompanion.ai.provider

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for Provider model classes:
 * ChatMessage (custom equals/hashCode), MessageRole, GenerationOptions,
 * GenerationResult, MessageChunk, FinishReason, TokenUsage,
 * TranscriptionResult, ValidationResult.
 *
 * Provider 模型類別單元測試：
 * ChatMessage（自定義 equals/hashCode）、MessageRole、GenerationOptions、
 * GenerationResult、MessageChunk、FinishReason、TokenUsage、
 * TranscriptionResult、ValidationResult。
 */
class ProviderTest {

    // ==================== MessageRole Tests 訊息角色測試 ====================

    @Test
    fun `MessageRole has exactly 4 values`() {
        // MessageRole 列舉應有 4 個值
        assertThat(MessageRole.entries).hasSize(4)
    }

    @Test
    fun `MessageRole contains expected values`() {
        val names = MessageRole.entries.map { it.name }
        assertThat(names).containsExactly("SYSTEM", "USER", "ASSISTANT", "TOOL")
    }

    // ==================== ChatMessage equals/hashCode Tests 等值測試 ====================

    @Test
    fun `ChatMessage equals returns true for identical messages without imageData`() {
        // 無圖片資料的相同訊息應相等
        val msg1 = ChatMessage(MessageRole.USER, "hello")
        val msg2 = ChatMessage(MessageRole.USER, "hello")
        assertThat(msg1).isEqualTo(msg2)
    }

    @Test
    fun `ChatMessage equals returns false for different content`() {
        val msg1 = ChatMessage(MessageRole.USER, "hello")
        val msg2 = ChatMessage(MessageRole.USER, "world")
        assertThat(msg1).isNotEqualTo(msg2)
    }

    @Test
    fun `ChatMessage equals returns false for different roles`() {
        val msg1 = ChatMessage(MessageRole.USER, "hello")
        val msg2 = ChatMessage(MessageRole.ASSISTANT, "hello")
        assertThat(msg1).isNotEqualTo(msg2)
    }

    @Test
    fun `ChatMessage equals with same ByteArray content returns true`() {
        // 相同 ByteArray 內容的訊息應相等（contentEquals）
        val bytes1 = byteArrayOf(1, 2, 3)
        val bytes2 = byteArrayOf(1, 2, 3)
        val msg1 = ChatMessage(MessageRole.USER, "img", imageData = bytes1)
        val msg2 = ChatMessage(MessageRole.USER, "img", imageData = bytes2)
        assertThat(msg1).isEqualTo(msg2)
    }

    @Test
    fun `ChatMessage equals with different ByteArray content returns false`() {
        val msg1 = ChatMessage(MessageRole.USER, "img", imageData = byteArrayOf(1, 2, 3))
        val msg2 = ChatMessage(MessageRole.USER, "img", imageData = byteArrayOf(4, 5, 6))
        assertThat(msg1).isNotEqualTo(msg2)
    }

    @Test
    fun `ChatMessage equals returns false when one has imageData and other does not`() {
        // 一個有圖片、一個沒有應不相等
        val msg1 = ChatMessage(MessageRole.USER, "img", imageData = byteArrayOf(1))
        val msg2 = ChatMessage(MessageRole.USER, "img", imageData = null)
        assertThat(msg1).isNotEqualTo(msg2)
    }

    @Test
    fun `ChatMessage equals returns false when other has imageData and this does not`() {
        val msg1 = ChatMessage(MessageRole.USER, "img", imageData = null)
        val msg2 = ChatMessage(MessageRole.USER, "img", imageData = byteArrayOf(1))
        assertThat(msg1).isNotEqualTo(msg2)
    }

    @Test
    fun `ChatMessage equals with both null imageData returns true`() {
        val msg1 = ChatMessage(MessageRole.USER, "text", imageData = null)
        val msg2 = ChatMessage(MessageRole.USER, "text", imageData = null)
        assertThat(msg1).isEqualTo(msg2)
    }

    @Test
    fun `ChatMessage equals with same instance returns true`() {
        // 同一實例應相等
        val msg = ChatMessage(MessageRole.USER, "hello")
        @Suppress("ReplaceCallWithBinaryOperator")
        assertThat(msg.equals(msg)).isTrue()
    }

    @Test
    fun `ChatMessage equals with different type returns false`() {
        val msg = ChatMessage(MessageRole.USER, "hello")
        @Suppress("ReplaceCallWithBinaryOperator")
        assertThat(msg.equals("not a ChatMessage")).isFalse()
    }

    @Test
    fun `ChatMessage equals with different name returns false`() {
        val msg1 = ChatMessage(MessageRole.TOOL, "result", name = "func1")
        val msg2 = ChatMessage(MessageRole.TOOL, "result", name = "func2")
        assertThat(msg1).isNotEqualTo(msg2)
    }

    @Test
    fun `ChatMessage hashCode is consistent for equal messages with imageData`() {
        // 相等訊息的 hashCode 應一致
        val msg1 = ChatMessage(MessageRole.USER, "img", imageData = byteArrayOf(10, 20))
        val msg2 = ChatMessage(MessageRole.USER, "img", imageData = byteArrayOf(10, 20))
        assertThat(msg1.hashCode()).isEqualTo(msg2.hashCode())
    }

    @Test
    fun `ChatMessage hashCode is consistent for messages without imageData`() {
        val msg1 = ChatMessage(MessageRole.ASSISTANT, "response")
        val msg2 = ChatMessage(MessageRole.ASSISTANT, "response")
        assertThat(msg1.hashCode()).isEqualTo(msg2.hashCode())
    }

    // ==================== GenerationOptions Tests 生成選項測試 ====================

    @Test
    fun `GenerationOptions has correct defaults`() {
        // 生成選項預設值驗證
        val opts = GenerationOptions()
        assertThat(opts.temperature).isEqualTo(0.7f)
        assertThat(opts.maxTokens).isEqualTo(4096)
        assertThat(opts.topP).isEqualTo(1.0f)
        assertThat(opts.frequencyPenalty).isEqualTo(0.0f)
        assertThat(opts.presencePenalty).isEqualTo(0.0f)
        assertThat(opts.stopSequences).isEmpty()
    }

    @Test
    fun `GenerationOptions can be customized`() {
        val opts = GenerationOptions(
            temperature = 0.9f,
            maxTokens = 2048,
            stopSequences = listOf("END", "STOP")
        )
        assertThat(opts.temperature).isEqualTo(0.9f)
        assertThat(opts.maxTokens).isEqualTo(2048)
        assertThat(opts.stopSequences).containsExactly("END", "STOP")
    }

    // ==================== GenerationResult Tests 生成結果測試 ====================

    @Test
    fun `GenerationResult Success has correct values`() {
        val result = GenerationResult.Success(
            text = "Hello world",
            finishReason = FinishReason.STOP,
            usage = TokenUsage(10, 5, 15)
        )
        assertThat(result.text).isEqualTo("Hello world")
        assertThat(result.finishReason).isEqualTo(FinishReason.STOP)
        assertThat(result.usage?.totalTokens).isEqualTo(15)
    }

    @Test
    fun `GenerationResult Success default finishReason is STOP`() {
        val result = GenerationResult.Success(text = "ok")
        assertThat(result.finishReason).isEqualTo(FinishReason.STOP)
        assertThat(result.usage).isNull()
    }

    @Test
    fun `GenerationResult Error has correct values`() {
        val result = GenerationResult.Error(
            message = "Rate limit",
            code = "429",
            retryable = true
        )
        assertThat(result.message).isEqualTo("Rate limit")
        assertThat(result.code).isEqualTo("429")
        assertThat(result.retryable).isTrue()
    }

    @Test
    fun `GenerationResult Error defaults`() {
        val result = GenerationResult.Error(message = "fail")
        assertThat(result.code).isNull()
        assertThat(result.retryable).isFalse()
    }

    @Test
    fun `GenerationResult Success is instance of GenerationResult`() {
        // 密封類別繼承測試
        val result: GenerationResult = GenerationResult.Success(text = "ok")
        assertThat(result).isInstanceOf(GenerationResult.Success::class.java)
    }

    @Test
    fun `GenerationResult Error is instance of GenerationResult`() {
        val result: GenerationResult = GenerationResult.Error(message = "err")
        assertThat(result).isInstanceOf(GenerationResult.Error::class.java)
    }

    // ==================== FinishReason Tests ====================

    @Test
    fun `FinishReason has exactly 5 values`() {
        assertThat(FinishReason.entries).hasSize(5)
    }

    @Test
    fun `FinishReason contains expected values`() {
        val names = FinishReason.entries.map { it.name }
        assertThat(names).containsExactly("STOP", "LENGTH", "CONTENT_FILTER", "TOOL_CALLS", "ERROR")
    }

    // ==================== Model Tests 模型資料測試 ====================

    @Test
    fun `Model has correct defaults`() {
        val model = Model(id = "gpt-4", name = "GPT-4")
        assertThat(model.description).isEmpty()
        assertThat(model.contextLength).isNull()
        assertThat(model.supportsVision).isFalse()
        assertThat(model.supportsAudio).isFalse()
        assertThat(model.supportsFunctionCalling).isFalse()
    }

    @Test
    fun `Model with all fields set`() {
        val model = Model(
            id = "gemini-pro",
            name = "Gemini Pro",
            description = "Google's flagship model",
            contextLength = 128000,
            supportsVision = true,
            supportsAudio = true,
            supportsFunctionCalling = true
        )
        assertThat(model.id).isEqualTo("gemini-pro")
        assertThat(model.contextLength).isEqualTo(128000)
        assertThat(model.supportsVision).isTrue()
        assertThat(model.supportsAudio).isTrue()
    }

    // ==================== MessageChunk Tests ====================

    @Test
    fun `MessageChunk defaults`() {
        val chunk = MessageChunk(text = "partial")
        assertThat(chunk.isComplete).isFalse()
        assertThat(chunk.finishReason).isNull()
        assertThat(chunk.error).isNull()
    }

    @Test
    fun `MessageChunk final chunk has finishReason`() {
        val chunk = MessageChunk(text = "", isComplete = true, finishReason = FinishReason.STOP)
        assertThat(chunk.isComplete).isTrue()
        assertThat(chunk.finishReason).isEqualTo(FinishReason.STOP)
    }

    // ==================== TokenUsage Tests ====================

    @Test
    fun `TokenUsage stores all fields`() {
        val usage = TokenUsage(promptTokens = 100, completionTokens = 50, totalTokens = 150)
        assertThat(usage.promptTokens).isEqualTo(100)
        assertThat(usage.completionTokens).isEqualTo(50)
        assertThat(usage.totalTokens).isEqualTo(150)
    }

    // ==================== TranscriptionOptions Tests ====================

    @Test
    fun `TranscriptionOptions has correct defaults`() {
        val opts = TranscriptionOptions()
        assertThat(opts.language).isEqualTo("zh-TW")
        assertThat(opts.prompt).isEmpty()
    }

    // ==================== TranscriptionResult Tests 語音轉文字結果測試 ====================

    @Test
    fun `TranscriptionResult Success stores text and metadata`() {
        val result = TranscriptionResult.Success(text = "你好", language = "zh", duration = 2.5f)
        assertThat(result.text).isEqualTo("你好")
        assertThat(result.language).isEqualTo("zh")
        assertThat(result.duration).isEqualTo(2.5f)
    }

    @Test
    fun `TranscriptionResult Success defaults`() {
        val result = TranscriptionResult.Success(text = "hello")
        assertThat(result.language).isNull()
        assertThat(result.duration).isNull()
    }

    @Test
    fun `TranscriptionResult Error stores message and code`() {
        val result = TranscriptionResult.Error(message = "Audio too short", code = "400")
        assertThat(result.message).isEqualTo("Audio too short")
        assertThat(result.code).isEqualTo("400")
    }

    @Test
    fun `TranscriptionResult Error code defaults to null`() {
        val result = TranscriptionResult.Error(message = "fail")
        assertThat(result.code).isNull()
    }

    // ==================== ValidationResult Tests 驗證結果測試 ====================

    @Test
    fun `ValidationResult Valid is singleton`() {
        // Valid 應為單例物件
        val v1 = ValidationResult.Valid
        val v2 = ValidationResult.Valid
        assertThat(v1).isSameInstanceAs(v2)
    }

    @Test
    fun `ValidationResult Invalid stores reason and field`() {
        val result = ValidationResult.Invalid(reason = "API key required", field = "apiKey")
        assertThat(result.reason).isEqualTo("API key required")
        assertThat(result.field).isEqualTo("apiKey")
    }

    @Test
    fun `ValidationResult Invalid field defaults to null`() {
        val result = ValidationResult.Invalid(reason = "Invalid config")
        assertThat(result.field).isNull()
    }

    @Test
    fun `ValidationResult subtypes are instances of sealed class`() {
        val valid: ValidationResult = ValidationResult.Valid
        val invalid: ValidationResult = ValidationResult.Invalid(reason = "bad")
        assertThat(valid).isInstanceOf(ValidationResult.Valid::class.java)
        assertThat(invalid).isInstanceOf(ValidationResult.Invalid::class.java)
    }
}
