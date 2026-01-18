package com.example.rokidglasses.service

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Speech Recognition Result
 */
sealed class TranscriptionResult {
    data class Success(val text: String) : TranscriptionResult()
    data class Error(val message: String, val isNetworkError: Boolean = false) : TranscriptionResult()
}

/**
 * Speech Recognition Service
 * Uses Google Gemini API for speech-to-text
 */
class SpeechRecognitionService(
    private val apiKey: String  // Gemini API Key
) {
    companion object {
        private const val TAG = "SpeechRecognitionService"
        // Gemini API endpoint
        private const val GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
    
    /**
     * Convert PCM audio data to WAV format
     */
    private fun pcmToWav(pcmData: ByteArray, sampleRate: Int = 16000, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val totalSize = 36 + dataSize
        
        val output = ByteArrayOutputStream()
        
        // RIFF header
        output.write("RIFF".toByteArray())
        output.write(intToBytes(totalSize, 4))
        output.write("WAVE".toByteArray())
        
        // fmt chunk
        output.write("fmt ".toByteArray())
        output.write(intToBytes(16, 4))           // Subchunk1Size
        output.write(intToBytes(1, 2))            // AudioFormat (PCM)
        output.write(intToBytes(channels, 2))     // NumChannels
        output.write(intToBytes(sampleRate, 4))   // SampleRate
        output.write(intToBytes(byteRate, 4))     // ByteRate
        output.write(intToBytes(blockAlign, 2))   // BlockAlign
        output.write(intToBytes(bitsPerSample, 2)) // BitsPerSample
        
        // data chunk
        output.write("data".toByteArray())
        output.write(intToBytes(dataSize, 4))
        output.write(pcmData)
        
        return output.toByteArray()
    }
    
    private fun intToBytes(value: Int, numBytes: Int): ByteArray {
        val bytes = ByteArray(numBytes)
        for (i in 0 until numBytes) {
            bytes[i] = (value shr (8 * i) and 0xFF).toByte()
        }
        return bytes
    }
    
    /**
     * Transcribe speech
     * @param pcmAudioData Audio data in PCM format
     * @return TranscriptionResult containing success text or error message
     */
    suspend fun transcribe(pcmAudioData: ByteArray): TranscriptionResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting transcription, audio size: ${pcmAudioData.size} bytes")
                
                if (pcmAudioData.size < 1000) {
                    Log.w(TAG, "Audio too short, skipping")
                    return@withContext TranscriptionResult.Error("Audio too short")
                }
                
                // Convert to WAV format
                val wavData = pcmToWav(pcmAudioData)
                Log.d(TAG, "WAV data size: ${wavData.size} bytes")
                
                // Convert audio to Base64
                val audioBase64 = Base64.encodeToString(wavData, Base64.NO_WRAP)
                Log.d(TAG, "Base64 length: ${audioBase64.length}")
                
                // Create Gemini API request
                val requestJson = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                // Audio part
                                put(JSONObject().apply {
                                    put("inline_data", JSONObject().apply {
                                        put("mime_type", "audio/wav")
                                        put("data", audioBase64)
                                    })
                                })
                                // Instruction part
                                put(JSONObject().apply {
                                    put("text", "Please transcribe this audio into text. Only output the transcribed text content, do not add any explanation or punctuation notes. If unclear or no sound, respond with 'Unable to recognize'.")
                                })
                            })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("temperature", 0.1)
                        put("maxOutputTokens", 500)
                    })
                }
                
                val request = Request.Builder()
                    .url("$GEMINI_API_URL?key=$apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                
                Log.d(TAG, "Sending request to Gemini API for transcription...")
                
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    Log.d(TAG, "Response code: ${response.code}")
                    
                    if (response.isSuccessful && responseBody != null) {
                        Log.d(TAG, "Response: ${responseBody.take(500)}")
                        
                        val json = JSONObject(responseBody)
                        val candidates = json.optJSONArray("candidates")
                        if (candidates != null && candidates.length() > 0) {
                            val content = candidates.getJSONObject(0).optJSONObject("content")
                            val parts = content?.optJSONArray("parts")
                            if (parts != null && parts.length() > 0) {
                                val text = parts.getJSONObject(0).optString("text", "").trim()
                                Log.d(TAG, "Transcription result: $text")
                                
                                // Check if response indicates unable to recognize
                                if (text.contains("Unable to recognize") || text.contains("no sound") || text.isEmpty()) {
                                    return@withContext TranscriptionResult.Error("Unable to recognize speech")
                                }
                                
                                return@withContext TranscriptionResult.Success(text)
                            }
                        }
                        Log.w(TAG, "No valid transcription in response")
                        return@withContext TranscriptionResult.Error("Unable to parse response")
                    } else {
                        Log.e(TAG, "Gemini API error: ${response.code}")
                        Log.e(TAG, "Error response: $responseBody")
                        return@withContext TranscriptionResult.Error("API Error: ${response.code}")
                    }
                }
            } catch (e: UnknownHostException) {
                Log.e(TAG, "Network error: ${e.message}", e)
                return@withContext TranscriptionResult.Error(
                    "Network connection failed\nPlease check WiFi connection",
                    isNetworkError = true
                )
            } catch (e: java.net.SocketTimeoutException) {
                Log.e(TAG, "Timeout error: ${e.message}", e)
                return@withContext TranscriptionResult.Error(
                    "Connection timeout\nPlease check network status",
                    isNetworkError = true
                )
            } catch (e: Exception) {
                Log.e(TAG, "Transcription error: ${e.message}", e)
                e.printStackTrace()
                return@withContext TranscriptionResult.Error("Error: ${e.message}")
            }
        }
    }
}
