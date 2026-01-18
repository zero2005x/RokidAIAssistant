package com.example.rokidaiassistant.services

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Microsoft Edge TTS Client
 * 
 * Uses Edge browser's TTS WebSocket API
 * Free, high-quality Chinese speech synthesis
 * 
 * Supported voices:
 * - zh-CN-XiaoxiaoNeural (female, friendly and lively)
 * - zh-CN-YunxiNeural (male, young)
 * - zh-CN-YunyangNeural (male, news anchor)
 * - zh-TW-HsiaoChenNeural (Taiwan female)
 * - zh-TW-YunJheNeural (Taiwan male)
 */
class EdgeTtsClient {
    
    companion object {
        private const val TAG = "EdgeTtsClient"
        
        // Edge TTS WebSocket endpoint
        private const val WSS_URL = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
        
        // HTTP endpoint (for getting voice list)
        private const val VOICES_URL = "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/voices/list"
        
        // User Agent
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0"
        
        // Default voices
        const val VOICE_XIAOXIAO = "zh-CN-XiaoxiaoNeural"
        const val VOICE_YUNXI = "zh-CN-YunxiNeural"
        const val VOICE_HSIAO_CHEN = "zh-TW-HsiaoChenNeural"
        
        // Timeout duration
        private const val TIMEOUT_SECONDS = 30L
    }
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    
    /**
     * Synthesize speech
     * 
     * @param text Text to synthesize
     * @param voice Voice name
     * @param rate Speech rate (-50% ~ +100%)
     * @param pitch Pitch (-50Hz ~ +50Hz)
     * @return MP3 audio data
     */
    suspend fun synthesize(
        text: String,
        voice: String = VOICE_XIAOXIAO,
        rate: String = "+0%",
        pitch: String = "+0Hz",
        volume: String = "+0%"
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting speech synthesis: voice=$voice, text=${text.take(50)}...")
            
            val audioData = ByteArrayOutputStream()
            val latch = CountDownLatch(1)
            var error: Exception? = null
            
            // Establish WebSocket connection
            val requestId = generateRequestId()
            val wsUrl = "$WSS_URL?TrustedClientToken=${getTrustedToken()}&ConnectionId=$requestId"
            
            val request = Request.Builder()
                .url(wsUrl)
                .header("User-Agent", USER_AGENT)
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .build()
            
            val webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket connection successful")
                    
                    // Send configuration message
                    val configMessage = buildConfigMessage(requestId)
                    webSocket.send(configMessage)
                    
                    // Send SSML message
                    val ssmlMessage = buildSsmlMessage(requestId, text, voice, rate, pitch, volume)
                    webSocket.send(ssmlMessage)
                }
                
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    // Process binary audio data
                    val data = bytes.toByteArray()
                    
                    // Find "Path:audio" marker
                    val headerEnd = findHeaderEnd(data)
                    if (headerEnd > 0 && headerEnd < data.size) {
                        audioData.write(data, headerEnd, data.size - headerEnd)
                    }
                }
                
                override fun onMessage(webSocket: WebSocket, text: String) {
                    // Process text message
                    if (text.contains("Path:turn.end")) {
                        Log.d(TAG, "Received end signal")
                        webSocket.close(1000, "Completed")
                        latch.countDown()
                    }
                }
                
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket error", t)
                    error = Exception("WebSocket error: ${t.message}")
                    latch.countDown()
                }
                
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closed: code=$code, reason=$reason")
                    if (latch.count > 0) latch.countDown()
                }
            })
            
            // Wait for completion
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                webSocket.cancel()
                return@withContext Result.failure(Exception("Speech synthesis timeout"))
            }
            
            error?.let {
                return@withContext Result.failure(it)
            }
            
            val result = audioData.toByteArray()
            Log.d(TAG, "Speech synthesis complete, size: ${result.size} bytes")
            
            if (result.isEmpty()) {
                return@withContext Result.failure(Exception("Synthesis result is empty"))
            }
            
            Result.success(result)
            
        } catch (e: Exception) {
            Log.e(TAG, "Speech synthesis failed", e)
            Result.failure(e)
        }
    }
    
    /**
     * Generate request ID
     */
    private fun generateRequestId(): String {
        return UUID.randomUUID().toString().replace("-", "")
    }
    
    /**
     * Get trusted token
     */
    private fun getTrustedToken(): String {
        // Edge TTS uses a fixed token
        return "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    }
    
    /**
     * Build configuration message
     */
    private fun buildConfigMessage(requestId: String): String {
        val timestamp = getTimestamp()
        return """
            X-Timestamp:$timestamp
            Content-Type:application/json; charset=utf-8
            Path:speech.config

            {"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}
        """.trimIndent()
    }
    
    /**
     * Build SSML message
     */
    private fun buildSsmlMessage(
        requestId: String,
        text: String,
        voice: String,
        rate: String,
        pitch: String,
        volume: String
    ): String {
        val timestamp = getTimestamp()
        val escapedText = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
        
        val ssml = """
            <speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>
                <voice name='$voice'>
                    <prosody pitch='$pitch' rate='$rate' volume='$volume'>
                        $escapedText
                    </prosody>
                </voice>
            </speak>
        """.trimIndent()
        
        return """
            X-RequestId:$requestId
            Content-Type:application/ssml+xml
            X-Timestamp:$timestamp
            Path:ssml

            $ssml
        """.trimIndent()
    }
    
    /**
     * Get timestamp
     */
    private fun getTimestamp(): String {
        val sdf = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT'Z", Locale.US)
        return sdf.format(Date())
    }
    
    /**
     * Find audio data start position
     */
    private fun findHeaderEnd(data: ByteArray): Int {
        // Find the first 0x00 0x82 sequence after "Path:audio"
        val pathAudio = "Path:audio".toByteArray()
        
        for (i in 0 until data.size - pathAudio.size) {
            var found = true
            for (j in pathAudio.indices) {
                if (data[i + j] != pathAudio[j]) {
                    found = false
                    break
                }
            }
            if (found) {
                // Found "Path:audio", skip until data start is found
                for (k in (i + pathAudio.size) until data.size - 1) {
                    if (data[k] == 0x00.toByte() && data[k + 1] == 0x82.toByte()) {
                        return k + 2
                    }
                }
                // If specific marker not found, return header end position
                return i + pathAudio.size + 2
            }
        }
        
        return -1
    }
    
    /**
     * Get available voices list
     */
    suspend fun getVoices(): Result<List<VoiceInfo>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$VOICES_URL?trustedclienttoken=${getTrustedToken()}")
                .header("User-Agent", USER_AGENT)
                .build()
            
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string()
            
            if (!response.isSuccessful || body.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("Failed to get voices list"))
            }
            
            // Parse JSON array
            val voices = mutableListOf<VoiceInfo>()
            val jsonArray = org.json.JSONArray(body)
            
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                voices.add(VoiceInfo(
                    name = obj.getString("Name"),
                    shortName = obj.getString("ShortName"),
                    locale = obj.getString("Locale"),
                    gender = obj.getString("Gender"),
                    friendlyName = obj.optString("FriendlyName", "")
                ))
            }
            
            // Filter Chinese voices
            val chineseVoices = voices.filter { it.locale.startsWith("zh-") }
            Log.d(TAG, "Found ${chineseVoices.size} Chinese voices")
            
            Result.success(chineseVoices)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get voices list", e)
            Result.failure(e)
        }
    }
    
    /**
     * Voice information
     */
    data class VoiceInfo(
        val name: String,
        val shortName: String,
        val locale: String,
        val gender: String,
        val friendlyName: String
    )
}
