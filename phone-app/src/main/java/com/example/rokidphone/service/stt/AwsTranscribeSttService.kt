package com.example.rokidphone.service.stt

import android.util.Log
import com.example.rokidphone.service.SpeechErrorCode
import com.example.rokidphone.service.SpeechResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.CRC32
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume

/**
 * AWS Transcribe 即時串流語音辨識服務
 *
 * API 文件: https://docs.aws.amazon.com/transcribe/latest/dg/streaming-websocket.html
 *
 * 功能:
 * - 透過 WebSocket 進行即時串流語音辨識
 * - 使用 AWS Signature V4 預簽章 URL 認證
 * - 使用 Event Stream 二進位編碼傳輸音訊資料
 * - 支援多語言辨識
 *
 * 認證: AWS Access Key ID + Secret Access Key (Signature V4 預簽章)
 */
class AwsTranscribeSttService(
    private val accessKeyId: String,
    private val secretAccessKey: String,
    private val region: String = "us-east-1"
) : BaseSttService() {

    companion object {
        private const val TAG = "AwsTranscribeSTT"
        private const val SERVICE = "transcribe"
        private const val ALGORITHM = "AWS4-HMAC-SHA256"
        private const val RECOGNITION_TIMEOUT_MS = 60_000L
        private const val AUDIO_CHUNK_SIZE = 8192 // 每次傳送的音訊塊大小
    }

    override val provider = SttProvider.AWS_TRANSCRIBE

    // ========== AWS Signature V4 工具方法 ==========

    private fun getSignatureKey(key: String, dateStamp: String, regionName: String, serviceName: String): ByteArray {
        val kDate = hmacSHA256("AWS4$key".toByteArray(), dateStamp)
        val kRegion = hmacSHA256(kDate, regionName)
        val kService = hmacSHA256(kRegion, serviceName)
        return hmacSHA256(kService, "aws4_request")
    }

    private fun hmacSHA256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(data: String): String = sha256Hex(data.toByteArray(Charsets.UTF_8))

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    /**
     * 建立預簽章 WebSocket URL
     * 按照 AWS Transcribe Streaming WebSocket 文件要求的格式產生
     */
    private fun buildPresignedWebSocketUrl(languageCode: String): String {
        val host = "transcribestreaming.$region.amazonaws.com"
        val endpoint = "wss://$host:8443"
        val path = "/stream-transcription-websocket"

        val now = Date()
        val dateFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val dateStampFormat = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val amzDate = dateFormat.format(now)
        val dateStamp = dateStampFormat.format(now)

        val credentialScope = "$dateStamp/$region/$SERVICE/aws4_request"

        // 將語言代碼映射為 AWS 格式
        val awsLangCode = mapLanguageCode(languageCode)

        // 查詢參數（必須按字母順序排列）
        val queryParams = sortedMapOf(
            "X-Amz-Algorithm" to ALGORITHM,
            "X-Amz-Credential" to URLEncoder.encode("$accessKeyId/$credentialScope", "UTF-8"),
            "X-Amz-Date" to amzDate,
            "X-Amz-Expires" to "300",
            "X-Amz-SignedHeaders" to "host",
            "language-code" to awsLangCode,
            "media-encoding" to "pcm",
            "sample-rate" to SAMPLE_RATE.toString()
        )

        val canonicalQueryString = queryParams.entries.joinToString("&") { (k, v) -> "$k=$v" }

        // 建立規範請求
        val canonicalHeaders = "host:$host\n"
        val signedHeaders = "host"
        val payloadHash = sha256Hex("")

        val canonicalRequest = listOf(
            "GET",
            path,
            canonicalQueryString,
            canonicalHeaders,
            signedHeaders,
            payloadHash
        ).joinToString("\n")

        // 建立待簽名字串
        val stringToSign = listOf(
            ALGORITHM,
            amzDate,
            credentialScope,
            sha256Hex(canonicalRequest)
        ).joinToString("\n")

        // 計算簽名
        val signingKey = getSignatureKey(secretAccessKey, dateStamp, region, SERVICE)
        val signature = bytesToHex(hmacSHA256(signingKey, stringToSign))

        return "$endpoint$path?$canonicalQueryString&X-Amz-Signature=$signature"
    }

    /**
     * 將通用語言代碼映射為 AWS Transcribe 支援的格式
     */
    private fun mapLanguageCode(languageCode: String): String {
        return when {
            languageCode.startsWith("zh-TW") || languageCode.startsWith("zh-Hant") -> "zh-TW"
            languageCode.startsWith("zh") -> "zh-CN"
            languageCode.startsWith("en") -> "en-US"
            languageCode.startsWith("ja") -> "ja-JP"
            languageCode.startsWith("ko") -> "ko-KR"
            languageCode.startsWith("fr") -> "fr-FR"
            languageCode.startsWith("de") -> "de-DE"
            languageCode.startsWith("es") -> "es-US"
            languageCode.startsWith("pt") -> "pt-BR"
            else -> languageCode
        }
    }

    // ========== Event Stream 編碼/解碼 ==========

    /**
     * 將音訊資料編碼為 AWS Event Stream 格式的 AudioEvent 訊息
     *
     * Event Stream 格式:
     * - 前言: total_byte_length (4) + headers_byte_length (4) + prelude_crc (4)
     * - 標頭: key-value 對
     * - 酬載: 音訊資料
     * - 訊息 CRC (4)
     */
    private fun encodeAudioEvent(audioChunk: ByteArray): ByteArray {
        val headers = buildEventStreamHeaders(
            mapOf(
                ":message-type" to "event",
                ":event-type" to "AudioEvent",
                ":content-type" to "application/octet-stream"
            )
        )

        val headersLength = headers.size
        val payloadLength = audioChunk.size
        // 前言 (12) + 標頭 + 酬載 + 訊息 CRC (4)
        val totalLength = 12 + headersLength + payloadLength + 4

        val output = ByteArrayOutputStream()
        val dos = DataOutputStream(output)

        // 前言
        dos.writeInt(totalLength)
        dos.writeInt(headersLength)

        // 計算前言 CRC
        val preludeBytes = ByteBuffer.allocate(8)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(totalLength)
            .putInt(headersLength)
            .array()
        val preludeCrc = CRC32().apply { update(preludeBytes) }
        dos.writeInt(preludeCrc.value.toInt())

        // 標頭
        dos.write(headers)

        // 酬載
        dos.write(audioChunk)

        // 計算訊息 CRC (涵蓋前言 CRC 之前的所有內容)
        dos.flush()
        val messageBytes = output.toByteArray()
        val messageCrc = CRC32().apply { update(messageBytes) }
        dos.writeInt(messageCrc.value.toInt())

        dos.flush()
        return output.toByteArray()
    }

    /**
     * 建立 Event Stream 標頭的位元組序列
     */
    private fun buildEventStreamHeaders(headers: Map<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        val dos = DataOutputStream(output)

        for ((name, value) in headers) {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            dos.writeByte(nameBytes.size)
            dos.write(nameBytes)
            dos.writeByte(7) // type 7 = String
            dos.writeShort(value.length)
            dos.write(value.toByteArray(Charsets.UTF_8))
        }

        dos.flush()
        return output.toByteArray()
    }

    /**
     * 從 Event Stream 二進位回應中解析轉錄文字
     */
    private fun parseEventStreamResponse(data: ByteArray): String? {
        return try {
            if (data.size < 16) return null

            val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val totalLength = buffer.int
            val headersLength = buffer.int
            val preludeCrc = buffer.int

            // 跳過標頭
            val headersBytes = ByteArray(headersLength)
            buffer.get(headersBytes)

            // 讀取酬載
            val payloadLength = totalLength - 12 - headersLength - 4
            if (payloadLength <= 0) return null

            val payload = ByteArray(payloadLength)
            buffer.get(payload)

            val payloadStr = String(payload, Charsets.UTF_8)

            // 解析 JSON 酬載
            val json = JSONObject(payloadStr)
            val transcript = json.optJSONObject("Transcript")
            val results = transcript?.optJSONArray("Results")
            if (results != null && results.length() > 0) {
                val result = results.getJSONObject(0)
                val isPartial = result.optBoolean("IsPartial", true)
                if (!isPartial) {
                    val alternatives = result.optJSONArray("Alternatives")
                    if (alternatives != null && alternatives.length() > 0) {
                        return alternatives.getJSONObject(0).optString("Transcript", "")
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse event stream response: ${e.message}")
            null
        }
    }

    // ========== 語音辨識實作 ==========

    override suspend fun transcribe(audioData: ByteArray, languageCode: String): SpeechResult {
        return withContext(Dispatchers.IO) {
            try {
                if (isAudioTooShort(audioData)) {
                    return@withContext SpeechResult.Error(
                        message = "Audio too short",
                        errorCode = SpeechErrorCode.AUDIO_TOO_SHORT
                    )
                }

                Log.d(TAG, "AWS Transcribe streaming: ${audioData.size} bytes, language: $languageCode")

                val presignedUrl = buildPresignedWebSocketUrl(languageCode)
                Log.d(TAG, "Connecting to AWS Transcribe WebSocket")

                withTimeout(RECOGNITION_TIMEOUT_MS) {
                    suspendCancellableCoroutine<SpeechResult> { continuation ->
                        var finalTranscript = StringBuilder()
                        var hasError = false
                        var errorMessage = ""

                        val request = Request.Builder().url(presignedUrl).build()

                        val webSocket = client.newWebSocket(request, object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                Log.d(TAG, "WebSocket connected to AWS Transcribe")

                                // 分塊傳送音訊資料（Event Stream 編碼）
                                var offset = 0
                                while (offset < audioData.size) {
                                    val chunkSize = minOf(AUDIO_CHUNK_SIZE, audioData.size - offset)
                                    val chunk = audioData.copyOfRange(offset, offset + chunkSize)
                                    val eventFrame = encodeAudioEvent(chunk)
                                    webSocket.send(eventFrame.toByteString())
                                    offset += chunkSize
                                }

                                // 傳送空的 AudioEvent 表示音訊結束
                                val endFrame = encodeAudioEvent(ByteArray(0))
                                webSocket.send(endFrame.toByteString())
                                Log.d(TAG, "Audio data sent, waiting for transcription...")
                            }

                            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                                try {
                                    val text = parseEventStreamResponse(bytes.toByteArray())
                                    if (!text.isNullOrEmpty()) {
                                        finalTranscript.append(text)
                                        Log.d(TAG, "Transcript segment: $text")
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Error parsing response", e)
                                }
                            }

                            override fun onMessage(webSocket: WebSocket, text: String) {
                                Log.d(TAG, "Text message: $text")
                                // AWS 通常使用二進位訊息，但錯誤可能以文字傳送
                                try {
                                    val json = JSONObject(text)
                                    if (json.has("Message")) {
                                        hasError = true
                                        errorMessage = json.optString("Message", "Unknown error")
                                        Log.e(TAG, "AWS error: $errorMessage")
                                    }
                                } catch (_: Exception) {}
                            }

                            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                                Log.d(TAG, "WebSocket closed: $code - $reason")
                                if (continuation.isActive) {
                                    val result = finalTranscript.toString().trim()
                                    if (hasError) {
                                        continuation.resume(SpeechResult.Error(
                                            message = "AWS Transcribe error: $errorMessage",
                                            errorCode = SpeechErrorCode.RECOGNITION_FAILED
                                        ))
                                    } else if (result.isNotEmpty()) {
                                        continuation.resume(SpeechResult.Success(result))
                                    } else {
                                        continuation.resume(SpeechResult.Error(
                                            message = "No speech detected",
                                            errorCode = SpeechErrorCode.NO_SPEECH_DETECTED
                                        ))
                                    }
                                }
                            }

                            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                                Log.e(TAG, "WebSocket failure", t)
                                if (continuation.isActive) {
                                    val msg = response?.body?.string()?.let {
                                        try { JSONObject(it).optString("Message", t.message ?: "Unknown error") } catch (_: Exception) { t.message }
                                    } ?: t.message ?: "Unknown error"
                                    continuation.resume(SpeechResult.Error(
                                        message = "Connection error: $msg",
                                        errorCode = SpeechErrorCode.NETWORK_ERROR
                                    ))
                                }
                            }
                        })

                        continuation.invokeOnCancellation {
                            webSocket.close(1000, "Cancelled")
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.e(TAG, "AWS Transcribe timed out")
                SpeechResult.Error(
                    message = "AWS Transcribe recognition timeout",
                    errorCode = SpeechErrorCode.TRANSCRIPTION_TIMEOUT
                )
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                SpeechResult.Error(
                    message = "AWS Transcribe error: ${e.message}",
                    errorCode = SpeechErrorCode.TRANSCRIPTION_ERROR
                )
            }
        }
    }

    override suspend fun validateCredentials(): SttValidationResult {
        return withContext(Dispatchers.IO) {
            try {
                if (accessKeyId.isBlank() || secretAccessKey.isBlank()) {
                    return@withContext SttValidationResult.Invalid(SttValidationError.INVALID_CREDENTIALS)
                }

                // 透過呼叫 AWS STS GetCallerIdentity 來驗證憑證
                val host = "sts.$region.amazonaws.com"
                val now = Date()
                val dateFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val dateStampFormat = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val amzDate = dateFormat.format(now)
                val dateStamp = dateStampFormat.format(now)

                val body = "Action=GetCallerIdentity&Version=2011-06-15"
                val contentType = "application/x-www-form-urlencoded"
                val credentialScope = "$dateStamp/$region/sts/aws4_request"

                val canonicalHeaders = "content-type:$contentType\nhost:$host\nx-amz-date:$amzDate\n"
                val signedHeaders = "content-type;host;x-amz-date"
                val payloadHash = sha256Hex(body)

                val canonicalRequest = "POST\n/\n\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
                val stringToSign = "$ALGORITHM\n$amzDate\n$credentialScope\n${sha256Hex(canonicalRequest)}"

                val signingKey = getSignatureKey(secretAccessKey, dateStamp, region, "sts")
                val signature = bytesToHex(hmacSHA256(signingKey, stringToSign))

                val authHeader = "$ALGORITHM Credential=$accessKeyId/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"

                val request = Request.Builder()
                    .url("https://$host/")
                    .addHeader("Content-Type", contentType)
                    .addHeader("X-Amz-Date", amzDate)
                    .addHeader("Authorization", authHeader)
                    .post(body.toRequestBody(contentType.toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> SttValidationResult.Valid
                        response.code == 403 || response.code == 401 ->
                            SttValidationResult.Invalid(SttValidationError.INVALID_CREDENTIALS)
                        else ->
                            SttValidationResult.Invalid(mapHttpStatusToError(response.code))
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                SttValidationResult.Invalid(SttValidationError.TIMEOUT)
            } catch (e: Exception) {
                Log.e(TAG, "Credential validation failed", e)
                SttValidationResult.Invalid(SttValidationError.NETWORK_ERROR)
            }
        }
    }

    override fun supportsStreaming(): Boolean = true
}
