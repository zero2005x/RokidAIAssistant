package com.example.rokidphone.testutil

/**
 * Shared test data factory.
 * Provides reusable test data (audio samples, JPEG images, mock API responses).
 */
object TestFixtures {

    // ==================== Audio Fixtures ====================

    /** Generate silent PCM audio of the given duration (16 kHz, 16-bit, mono). */
    fun createTestPcmAudio(durationMs: Int = 1000): ByteArray {
        val sampleRate = 16000
        val bytesPerSample = 2
        val numSamples = (sampleRate * durationMs) / 1000
        return ByteArray(numSamples * bytesPerSample)
    }

    /** Audio that is too short — should trigger AUDIO_TOO_SHORT error. */
    fun createTooShortAudio(): ByteArray = ByteArray(100)

    /** Empty audio. */
    fun createEmptyAudio(): ByteArray = ByteArray(0)

    // ==================== Image Fixtures ====================

    /** Minimal valid JPEG (1x1 white pixel). */
    fun createTestJpeg(): ByteArray = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
        0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
        0xFF.toByte(), 0xDB.toByte(), 0x00, 0x43, 0x00, 0x08,
        0x06, 0x06, 0x07, 0x06, 0x05, 0x08, 0x07, 0x07,
        0x07, 0x09, 0x09, 0x08, 0x0A, 0x0C, 0x14, 0x0D,
        0x0C, 0x0B, 0x0B, 0x0C, 0x19, 0x12, 0x13, 0x0F,
        0x14, 0x1D, 0x1A, 0x1F, 0x1E, 0x1D, 0x1A, 0x1C,
        0x1C, 0x20, 0x24, 0x2E, 0x27, 0x20, 0x22, 0x2C,
        0x23, 0x1C, 0x1C, 0x28, 0x37, 0x29, 0x2C, 0x30,
        0x31, 0x34, 0x34, 0x34, 0x1F, 0x27, 0x39, 0x3D,
        0x38, 0x32, 0x3C, 0x2E, 0x33, 0x34, 0x32,
        0xFF.toByte(), 0xC0.toByte(), 0x00, 0x0B, 0x08, 0x00,
        0x01, 0x00, 0x01, 0x01, 0x01, 0x11, 0x00,
        0xFF.toByte(), 0xC4.toByte(), 0x00, 0x1F, 0x00, 0x00,
        0x01, 0x05, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
        0x09, 0x0A, 0x0B,
        0xFF.toByte(), 0xDA.toByte(), 0x00, 0x08, 0x01, 0x01,
        0x00, 0x00, 0x3F, 0x00, 0x7B, 0x40,
        0xFF.toByte(), 0xD9.toByte()
    )

    // ==================== Mock API Responses ====================

    object MockResponses {

        // ---------- Gemini ----------
        fun geminiChatSuccess(text: String = "Hello!"): String = """
            {
              "candidates": [{
                "content": {
                  "parts": [{"text": "$text"}],
                  "role": "model"
                },
                "finishReason": "STOP"
              }]
            }
        """.trimIndent()

        fun geminiTranscribeSuccess(text: String = "你好世界"): String = """
            {
              "candidates": [{
                "content": {
                  "parts": [{"text": "$text"}],
                  "role": "model"
                },
                "finishReason": "STOP"
              }]
            }
        """.trimIndent()

        fun geminiError(code: Int = 400, message: String = "Bad Request"): String = """
            {
              "error": {
                "code": $code,
                "message": "$message",
                "status": "INVALID_ARGUMENT"
              }
            }
        """.trimIndent()

        // ---------- OpenAI / OpenAI-Compatible ----------
        fun openAiChatSuccess(text: String = "Hello!"): String = """
            {
              "id": "chatcmpl-test123",
              "object": "chat.completion",
              "created": 1700000000,
              "model": "gpt-4",
              "choices": [{
                "index": 0,
                "message": {"role": "assistant", "content": "$text"},
                "finish_reason": "stop"
              }],
              "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
            }
        """.trimIndent()

        fun openAiWhisperSuccess(text: String = "你好世界"): String = """
            {"text": "$text"}
        """.trimIndent()

        fun openAiError(
            code: String = "invalid_api_key",
            message: String = "Incorrect API key"
        ): String = """
            {
              "error": {
                "message": "$message",
                "type": "invalid_request_error",
                "param": null,
                "code": "$code"
              }
            }
        """.trimIndent()

        // ---------- Anthropic ----------
        fun anthropicChatSuccess(text: String = "Hello!"): String = """
            {
              "id": "msg_test123",
              "type": "message",
              "role": "assistant",
              "content": [{"type": "text", "text": "$text"}],
              "model": "claude-sonnet-4-5",
              "stop_reason": "end_turn",
              "usage": {"input_tokens": 10, "output_tokens": 5}
            }
        """.trimIndent()

        fun anthropicError(
            type: String = "authentication_error",
            message: String = "Invalid API key"
        ): String = """
            {
              "type": "error",
              "error": {"type": "$type", "message": "$message"}
            }
        """.trimIndent()

        // ---------- Baidu ERNIE ----------
        fun baiduTokenSuccess(accessToken: String = "test-access-token"): String = """
            {
              "access_token": "$accessToken",
              "expires_in": 2592000,
              "refresh_token": "test-refresh-token",
              "scope": "public",
              "session_key": "",
              "session_secret": ""
            }
        """.trimIndent()

        fun baiduChatSuccess(text: String = "Hello!"): String = """
            {
              "id": "as-test123",
              "result": "$text",
              "is_truncated": false,
              "need_clear_history": false,
              "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
            }
        """.trimIndent()

        fun baiduError(errorCode: Int = 110, errorMsg: String = "Access token invalid"): String = """
            {"error_code": $errorCode, "error_msg": "$errorMsg"}
        """.trimIndent()

        // ---------- Deepgram STT ----------
        fun deepgramTranscribeSuccess(text: String = "hello world"): String = """
            {
              "results": {
                "channels": [{
                  "alternatives": [{
                    "transcript": "$text",
                    "confidence": 0.98
                  }]
                }]
              }
            }
        """.trimIndent()

        // ---------- AssemblyAI STT ----------
        fun assemblyAiUploadSuccess(uploadUrl: String = "https://cdn.assemblyai.com/upload/test"): String = """
            {"upload_url": "$uploadUrl"}
        """.trimIndent()

        fun assemblyAiTranscriptCreated(id: String = "test-id-123"): String = """
            {"id": "$id", "status": "queued"}
        """.trimIndent()

        fun assemblyAiTranscriptCompleted(
            id: String = "test-id-123",
            text: String = "hello world"
        ): String = """
            {"id": "$id", "status": "completed", "text": "$text"}
        """.trimIndent()

        fun assemblyAiTranscriptProcessing(id: String = "test-id-123"): String = """
            {"id": "$id", "status": "processing"}
        """.trimIndent()

        // ---------- Google Cloud STT ----------
        fun googleCloudSttSuccess(text: String = "hello world"): String = """
            {
              "results": [{
                "alternatives": [{
                  "transcript": "$text",
                  "confidence": 0.95
                }]
              }]
            }
        """.trimIndent()

        // ---------- Azure Speech STT ----------
        fun azureSttSuccess(text: String = "hello world"): String = """
            {
              "RecognitionStatus": "Success",
              "DisplayText": "$text",
              "Offset": 0,
              "Duration": 10000000
            }
        """.trimIndent()

        // ---------- Generic empty/error ----------
        fun emptyJsonObject(): String = "{}"

        fun malformedJson(): String = "{ this is not valid JSON !!!"
    }
}
