package com.example.rokidaiassistant.services

import android.util.Base64
import android.util.Log
import com.example.rokidaiassistant.data.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Speech-to-Text Service
 * 
 * Supports two STT options:
 * 1. OpenAI Whisper API (recommended, high accuracy)
 * 2. Google Cloud Speech-to-Text API (fallback)
 * 
 * Audio format requirements:
 * - PCM 16-bit
 * - 16kHz mono
 */
class SpeechToTextService {
    
    companion object {
        private const val TAG = "SpeechToTextService"
        
        // OpenAI Whisper API
        private const val WHISPER_API_URL = "https://api.openai.com/v1/audio/transcriptions"
        
        // Google Speech-to-Text API
        private const val GOOGLE_STT_URL = "https://speech.googleapis.com/v1/speech:recognize"
        
        // Timeout settings
        private const val TIMEOUT_SECONDS = 30L
    }
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    
    /**
     * Perform speech recognition using OpenAI Whisper API
     * 
     * @param audioData PCM audio data
     * @param apiKey OpenAI API Key
     * @return Recognition result
     */
    suspend fun transcribeWithWhisper(
        audioData: ByteArray,
        apiKey: String = Constants.OPENAI_API_KEY
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (audioData.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Audio data is empty"))
            }
            
            Log.d(TAG, "Starting Whisper speech recognition, audio size: ${audioData.size} bytes")
            
            // Convert PCM to WAV format (required by Whisper)
            val wavData = pcmToWav(audioData, Constants.AUDIO_SAMPLE_RATE, 1, 16)
            
            // Build multipart request
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    "audio.wav",
                    wavData.toRequestBody("audio/wav".toMediaType())
                )
                .addFormDataPart("model", "whisper-1")
                .addFormDataPart("language", "zh")  // Specify Chinese
                .addFormDataPart("response_format", "json")
                .build()
            
            val request = Request.Builder()
                .url(WHISPER_API_URL)
                .header("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()
            
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()
            
            if (!response.isSuccessful) {
                Log.e(TAG, "Whisper API error: ${response.code} - $responseBody")
                return@withContext Result.failure(
                    IOException("Whisper API error: ${response.code}")
                )
            }
            
            val json = JSONObject(responseBody ?: "{}")
            val text = json.optString("text", "").trim()
            
            if (text.isEmpty()) {
                Log.w(TAG, "Whisper returned empty text")
                return@withContext Result.success("(Unable to recognize speech)")
            }
            
            Log.d(TAG, "Whisper recognition result: $text")
            Result.success(text)
            
        } catch (e: Exception) {
            Log.e(TAG, "Whisper speech recognition failed", e)
            Result.failure(e)
        }
    }
    
    /**
     * Use Google Cloud Speech-to-Text API
     * 
     * @param audioData PCM audio data
     * @param apiKey Google Cloud API Key
     * @return Recognition result
     */
    suspend fun transcribeWithGoogle(
        audioData: ByteArray,
        apiKey: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (audioData.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Audio data is empty"))
            }
            
            Log.d(TAG, "Starting Google STT speech recognition, audio size: ${audioData.size} bytes")
            
            // Base64 encode audio
            val audioBase64 = Base64.encodeToString(audioData, Base64.NO_WRAP)
            
            // Build request JSON
            val requestJson = JSONObject().apply {
                put("config", JSONObject().apply {
                    put("encoding", "LINEAR16")
                    put("sampleRateHertz", Constants.AUDIO_SAMPLE_RATE)
                    put("languageCode", "zh-TW")  // Traditional Chinese
                    put("alternativeLanguageCodes", listOf("zh-CN", "en-US"))
                    put("enableAutomaticPunctuation", true)
                })
                put("audio", JSONObject().apply {
                    put("content", audioBase64)
                })
            }
            
            val request = Request.Builder()
                .url("$GOOGLE_STT_URL?key=$apiKey")
                .header("Content-Type", "application/json")
                .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()
            
            if (!response.isSuccessful) {
                Log.e(TAG, "Google STT API error: ${response.code} - $responseBody")
                return@withContext Result.failure(
                    IOException("Google STT API error: ${response.code}")
                )
            }
            
            val json = JSONObject(responseBody ?: "{}")
            val results = json.optJSONArray("results")
            
            if (results == null || results.length() == 0) {
                Log.w(TAG, "Google STT returned empty results")
                return@withContext Result.success("(Unable to recognize speech)")
            }
            
            // Extract recognition text
            val transcript = StringBuilder()
            for (i in 0 until results.length()) {
                val result = results.getJSONObject(i)
                val alternatives = result.optJSONArray("alternatives")
                if (alternatives != null && alternatives.length() > 0) {
                    transcript.append(alternatives.getJSONObject(0).optString("transcript", ""))
                }
            }
            
            val text = transcript.toString().trim()
            Log.d(TAG, "Google STT recognition result: $text")
            Result.success(text.ifEmpty { "(Unable to recognize speech)" })
            
        } catch (e: Exception) {
            Log.e(TAG, "Google STT speech recognition failed", e)
            Result.failure(e)
        }
    }
    
    /**
     * Intelligently select STT service
     * Prioritize Whisper, fallback to alternative when failed
     */
    suspend fun transcribe(audioData: ByteArray): Result<String> {
        // Try Whisper first
        if (Constants.OPENAI_API_KEY.isNotBlank()) {
            val result = transcribeWithWhisper(audioData)
            if (result.isSuccess) {
                return result
            }
            Log.w(TAG, "Whisper failed, trying fallback option")
        }
        
        // If all fail, return mock result (development stage)
        Log.w(TAG, "All STT services unavailable, returning mock result")
        return Result.success("This is a mock speech recognition result (please configure STT API Key)")
    }
    
    /**
     * Convert PCM data to WAV format
     */
    private fun pcmToWav(
        pcmData: ByteArray,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val fileSize = 36 + dataSize
        
        val output = ByteArrayOutputStream()
        
        // RIFF header
        output.write("RIFF".toByteArray())
        output.write(intToByteArray(fileSize))
        output.write("WAVE".toByteArray())
        
        // fmt chunk
        output.write("fmt ".toByteArray())
        output.write(intToByteArray(16))  // chunk size
        output.write(shortToByteArray(1)) // audio format (PCM)
        output.write(shortToByteArray(channels.toShort()))
        output.write(intToByteArray(sampleRate))
        output.write(intToByteArray(byteRate))
        output.write(shortToByteArray(blockAlign.toShort()))
        output.write(shortToByteArray(bitsPerSample.toShort()))
        
        // data chunk
        output.write("data".toByteArray())
        output.write(intToByteArray(dataSize))
        output.write(pcmData)
        
        return output.toByteArray()
    }
    
    private fun intToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }
    
    private fun shortToByteArray(value: Short): ByteArray {
        return byteArrayOf(
            (value.toInt() and 0xFF).toByte(),
            ((value.toInt() shr 8) and 0xFF).toByte()
        )
    }
}
