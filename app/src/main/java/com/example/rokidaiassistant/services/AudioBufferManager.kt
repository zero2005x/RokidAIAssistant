package com.example.rokidaiassistant.services

import android.util.Log
import com.example.rokidaiassistant.data.Constants
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Audio Buffer Manager
 * 
 * Features:
 * 1. Collect audio data from glasses
 * 2. Audio data preprocessing (normalization, silence detection)
 * 3. Support VAD (Voice Activity Detection)
 */
class AudioBufferManager {
    
    companion object {
        private const val TAG = "AudioBufferManager"
        
        // Silence threshold (16-bit PCM)
        private const val SILENCE_THRESHOLD = 500
        
        // Minimum valid audio duration (500ms)
        private const val MIN_AUDIO_DURATION_MS = 500
        
        // Maximum audio duration (30s)
        private const val MAX_AUDIO_DURATION_MS = 30000
    }
    
    private val buffer = ByteArrayOutputStream()
    private var isRecording = false
    private var recordStartTime = 0L
    
    /**
     * Start recording
     */
    fun startRecording() {
        buffer.reset()
        isRecording = true
        recordStartTime = System.currentTimeMillis()
        Log.d(TAG, "Start recording")
    }
    
    /**
     * Stop recording
     */
    fun stopRecording() {
        isRecording = false
        val duration = System.currentTimeMillis() - recordStartTime
        Log.d(TAG, "Stop recording, duration: ${duration}ms, size: ${buffer.size()} bytes")
    }
    
    /**
     * Write audio data
     */
    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        if (!isRecording) return
        
        // Check if maximum duration exceeded
        val duration = System.currentTimeMillis() - recordStartTime
        if (duration > MAX_AUDIO_DURATION_MS) {
            Log.w(TAG, "Audio exceeded maximum duration, stopping recording")
            stopRecording()
            return
        }
        
        buffer.write(data, offset, length)
    }
    
    /**
     * Get audio data
     */
    fun getAudioData(): ByteArray {
        return buffer.toByteArray()
    }
    
    /**
     * Get recording duration (milliseconds)
     */
    fun getDurationMs(): Long {
        val bytesPerSecond = Constants.AUDIO_SAMPLE_RATE * 2  // 16-bit = 2 bytes
        return (buffer.size() * 1000L) / bytesPerSecond
    }
    
    /**
     * Check if there is valid audio
     */
    fun hasValidAudio(): Boolean {
        val duration = getDurationMs()
        if (duration < MIN_AUDIO_DURATION_MS) {
            Log.d(TAG, "Audio too short: ${duration}ms")
            return false
        }
        
        // Check if all silence
        val audioData = buffer.toByteArray()
        if (isSilent(audioData)) {
            Log.d(TAG, "Audio is all silence")
            return false
        }
        
        return true
    }
    
    /**
     * Detect if audio is silence
     */
    private fun isSilent(audioData: ByteArray): Boolean {
        if (audioData.isEmpty()) return true
        
        val shortBuffer = ByteBuffer.wrap(audioData)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        
        var sumAbs = 0L
        val samples = shortBuffer.remaining()
        
        while (shortBuffer.hasRemaining()) {
            sumAbs += kotlin.math.abs(shortBuffer.get().toInt())
        }
        
        val avgAbs = sumAbs / samples
        return avgAbs < SILENCE_THRESHOLD
    }
    
    /**
     * Normalize audio
     * Adjust volume to appropriate range
     */
    fun normalize(audioData: ByteArray): ByteArray {
        if (audioData.isEmpty()) return audioData
        
        val shortBuffer = ByteBuffer.wrap(audioData)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        
        // Find maximum amplitude
        var maxAbs = 0
        val samples = shortBuffer.remaining()
        val shorts = ShortArray(samples)
        
        for (i in 0 until samples) {
            shorts[i] = shortBuffer.get()
            val abs = kotlin.math.abs(shorts[i].toInt())
            if (abs > maxAbs) maxAbs = abs
        }
        
        // Calculate normalization factor
        if (maxAbs < 1000) return audioData  // Volume too low, skip processing
        
        val targetMax = 30000  // Target maximum amplitude
        val factor = targetMax.toFloat() / maxAbs
        
        if (factor < 1.1f) return audioData  // Already loud enough
        
        // Apply normalization
        val result = ByteArray(audioData.size)
        val resultBuffer = ByteBuffer.wrap(result)
            .order(ByteOrder.LITTLE_ENDIAN)
        
        for (sample in shorts) {
            val normalized = (sample * factor).toInt().coerceIn(-32768, 32767)
            resultBuffer.putShort(normalized.toShort())
        }
        
        Log.d(TAG, "Audio normalization complete, factor: $factor")
        return result
    }
    
    /**
     * Trim silence parts
     * Remove silence from beginning and end
     */
    fun trimSilence(audioData: ByteArray): ByteArray {
        if (audioData.size < 1000) return audioData
        
        val shortBuffer = ByteBuffer.wrap(audioData)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        
        val samples = shortBuffer.remaining()
        val shorts = ShortArray(samples)
        
        for (i in 0 until samples) {
            shorts[i] = shortBuffer.get()
        }
        
        // Find start position
        var start = 0
        for (i in shorts.indices) {
            if (kotlin.math.abs(shorts[i].toInt()) > SILENCE_THRESHOLD) {
                start = maxOf(0, i - 100)  // Keep some leading
                break
            }
        }
        
        // Find end position
        var end = shorts.size
        for (i in shorts.indices.reversed()) {
            if (kotlin.math.abs(shorts[i].toInt()) > SILENCE_THRESHOLD) {
                end = minOf(shorts.size, i + 100)  // Keep some trailing
                break
            }
        }
        
        if (start >= end) return audioData
        
        val trimmedLength = (end - start) * 2  // 2 bytes per sample
        val result = ByteArray(trimmedLength)
        val resultBuffer = ByteBuffer.wrap(result)
            .order(ByteOrder.LITTLE_ENDIAN)
        
        for (i in start until end) {
            resultBuffer.putShort(shorts[i])
        }
        
        Log.d(TAG, "Silence trimming complete, ${audioData.size} -> ${result.size} bytes")
        return result
    }
    
    /**
     * Reset buffer
     */
    fun reset() {
        buffer.reset()
        isRecording = false
    }
    
    /**
     * Is recording in progress
     */
    fun isRecording(): Boolean = isRecording
}
