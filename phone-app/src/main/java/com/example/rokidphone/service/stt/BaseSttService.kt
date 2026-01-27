package com.example.rokidphone.service.stt

import android.util.Log
import com.example.rokidphone.service.SpeechResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.concurrent.TimeUnit

/**
 * Base class for STT service implementations
 * Provides common utilities for audio processing and HTTP calls
 */
abstract class BaseSttService : SttService {
    
    companion object {
        private const val TAG = "BaseSttService"
        
        // Audio format constants
        const val SAMPLE_RATE = 16000
        const val BITS_PER_SAMPLE = 16
        const val CHANNELS = 1
        
        // Retry configuration
        const val MAX_RETRIES = 3
        const val RETRY_DELAY_MS = 1000L
    }
    
    protected val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
    
    /**
     * Convert PCM audio data to WAV format
     * @param pcmData Raw PCM 16-bit, 16kHz, mono audio data
     * @return WAV file bytes
     */
    protected fun pcmToWav(pcmData: ByteArray): ByteArray {
        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8
        
        val output = ByteArrayOutputStream()
        val dos = DataOutputStream(output)
        
        // RIFF header
        dos.writeBytes("RIFF")
        dos.writeInt(Integer.reverseBytes(36 + pcmData.size))
        dos.writeBytes("WAVE")
        
        // fmt subchunk
        dos.writeBytes("fmt ")
        dos.writeInt(Integer.reverseBytes(16))  // Subchunk1Size
        dos.writeShort(java.lang.Short.reverseBytes(1).toInt())  // AudioFormat (PCM = 1)
        dos.writeShort(java.lang.Short.reverseBytes(CHANNELS.toShort()).toInt())
        dos.writeInt(Integer.reverseBytes(SAMPLE_RATE))
        dos.writeInt(Integer.reverseBytes(byteRate))
        dos.writeShort(java.lang.Short.reverseBytes(blockAlign.toShort()).toInt())
        dos.writeShort(java.lang.Short.reverseBytes(BITS_PER_SAMPLE.toShort()).toInt())
        
        // data subchunk
        dos.writeBytes("data")
        dos.writeInt(Integer.reverseBytes(pcmData.size))
        dos.write(pcmData)
        
        dos.flush()
        return output.toByteArray()
    }
    
    /**
     * Execute a request with retry logic
     */
    protected suspend fun <T> executeWithRetry(
        tag: String,
        maxRetries: Int = MAX_RETRIES,
        block: suspend (attempt: Int) -> T?
    ): T? {
        return withContext(Dispatchers.IO) {
            var lastException: Exception? = null
            
            repeat(maxRetries) { attempt ->
                try {
                    val result = block(attempt + 1)
                    if (result != null) {
                        return@withContext result
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Attempt ${attempt + 1} failed: ${e.message}")
                    lastException = e
                    
                    if (attempt < maxRetries - 1) {
                        kotlinx.coroutines.delay(RETRY_DELAY_MS * (attempt + 1))
                    }
                }
            }
            
            if (lastException != null) {
                Log.e(tag, "All $maxRetries attempts failed", lastException)
            }
            null
        }
    }
    
    /**
     * Build multipart form data for audio upload
     */
    protected fun buildMultipartBody(
        boundary: String,
        audioData: ByteArray,
        audioFieldName: String = "file",
        audioFileName: String = "audio.wav",
        audioMimeType: String = "audio/wav",
        additionalFields: Map<String, String> = emptyMap()
    ): ByteArray {
        val output = ByteArrayOutputStream()
        val writer = output.bufferedWriter()
        
        // Audio file field
        writer.write("--$boundary\r\n")
        writer.write("Content-Disposition: form-data; name=\"$audioFieldName\"; filename=\"$audioFileName\"\r\n")
        writer.write("Content-Type: $audioMimeType\r\n\r\n")
        writer.flush()
        output.write(audioData)
        writer.write("\r\n")
        
        // Additional fields
        for ((name, value) in additionalFields) {
            writer.write("--$boundary\r\n")
            writer.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
            writer.write("$value\r\n")
        }
        
        writer.write("--$boundary--\r\n")
        writer.flush()
        
        return output.toByteArray()
    }
    
    /**
     * Check if audio data is too short
     */
    protected fun isAudioTooShort(audioData: ByteArray, minBytes: Int = 1000): Boolean {
        return audioData.size < minBytes
    }
    
    override fun release() {
        // Default implementation does nothing
        // Subclasses can override to release resources
    }
}
