package com.example.rokidphone.data.db

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private const val TAG = "RecordingRepository"

/**
 * Recording state for UI
 */
sealed class RecordingState {
    object Idle : RecordingState()
    data class Recording(val source: RecordingSource, val startTime: Long, val durationMs: Long = 0) : RecordingState()
    object Stopping : RecordingState()
    data class Error(val message: String) : RecordingState()
}

/**
 * Repository for managing audio recordings
 */
class RecordingRepository private constructor(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val recordingDao = AppDatabase.getInstance(context).recordingDao()
    private val recordingsDir = File(context.filesDir, "recordings").apply { mkdirs() }
    
    // Recording state
    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()
    
    // Current recording
    private var mediaRecorder: MediaRecorder? = null
    private var currentRecordingFile: File? = null
    private var currentRecordingId: String? = null
    private var recordingStartTime: Long = 0
    
    // Duration update job
    private var durationUpdateJob: kotlinx.coroutines.Job? = null
    
    companion object {
        @Volatile
        private var instance: RecordingRepository? = null
        
        fun getInstance(context: Context, scope: CoroutineScope): RecordingRepository {
            return instance ?: synchronized(this) {
                instance ?: RecordingRepository(context.applicationContext, scope).also { instance = it }
            }
        }
    }
    
    // ==================== Data Access ====================
    
    /**
     * Get all recordings as Flow
     */
    fun getAllRecordings(): Flow<List<RecordingEntity>> = recordingDao.getAllRecordings()
    
    /**
     * Get favorite recordings
     */
    fun getFavoriteRecordings(): Flow<List<RecordingEntity>> = recordingDao.getFavoriteRecordings()
    
    /**
     * Get recordings by source
     */
    fun getRecordingsBySource(source: RecordingSource): Flow<List<RecordingEntity>> = 
        recordingDao.getRecordingsBySource(source)
    
    /**
     * Get recording by ID
     */
    suspend fun getRecordingById(id: String): RecordingEntity? = recordingDao.getRecordingById(id)
    
    /**
     * Get recording by ID as Flow
     */
    fun getRecordingByIdFlow(id: String): Flow<RecordingEntity?> = recordingDao.getRecordingByIdFlow(id)
    
    /**
     * Search recordings
     */
    fun searchRecordings(query: String): Flow<List<RecordingEntity>> = recordingDao.searchRecordings(query)
    
    // ==================== Recording Control ====================
    
    /**
     * Start recording from phone microphone
     */
    suspend fun startPhoneRecording(): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (_recordingState.value is RecordingState.Recording) {
                return@withContext Result.failure(Exception("Already recording"))
            }
            
            val recordingId = UUID.randomUUID().toString()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "REC_${timestamp}_$recordingId.m4a"
            val outputFile = File(recordingsDir, fileName)
            
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioChannels(1)
                setAudioEncodingBitRate(64000)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }
            
            currentRecordingFile = outputFile
            currentRecordingId = recordingId
            recordingStartTime = System.currentTimeMillis()
            
            _recordingState.value = RecordingState.Recording(
                source = RecordingSource.PHONE,
                startTime = recordingStartTime
            )
            
            // Start duration update
            startDurationUpdate()
            
            Log.d(TAG, "Started phone recording: $recordingId")
            Result.success(recordingId)
            
        } catch (e: SecurityException) {
            Log.e(TAG, "Microphone permission denied", e)
            _recordingState.value = RecordingState.Error("Microphone permission required")
            Result.failure(Exception("Microphone permission required. Please grant the permission in Settings."))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start phone recording", e)
            _recordingState.value = RecordingState.Error(e.message ?: "Failed to start recording")
            Result.failure(e)
        }
    }
    
    /**
     * Request glasses to start recording
     * Returns recording ID if request was sent successfully
     */
    suspend fun startGlassesRecording(): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (_recordingState.value is RecordingState.Recording) {
                return@withContext Result.failure(Exception("Already recording"))
            }
            
            val recordingId = UUID.randomUUID().toString()
            currentRecordingId = recordingId
            recordingStartTime = System.currentTimeMillis()
            
            _recordingState.value = RecordingState.Recording(
                source = RecordingSource.GLASSES,
                startTime = recordingStartTime
            )
            
            // Start duration update
            startDurationUpdate()
            
            // Note: Actual glasses recording is handled via Bluetooth message
            // The ServiceBridge will be used to send the command
            Log.d(TAG, "Started glasses recording request: $recordingId")
            Result.success(recordingId)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start glasses recording", e)
            _recordingState.value = RecordingState.Error(e.message ?: "Failed to start recording")
            Result.failure(e)
        }
    }
    
    /**
     * Pause current recording (requires API 24+)
     * Note: MediaRecorder pause/resume is only available on API 24+
     */
    suspend fun pauseRecording() = withContext(Dispatchers.IO) {
        try {
            val state = _recordingState.value
            if (state !is RecordingState.Recording) {
                return@withContext
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && state.source == RecordingSource.PHONE) {
                mediaRecorder?.pause()
                Log.d(TAG, "Recording paused")
            } else {
                Log.w(TAG, "Pause not supported on this device or recording source")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pause recording", e)
        }
    }
    
    /**
     * Stop current recording
     */
    suspend fun stopRecording(): Result<RecordingEntity?> = withContext(Dispatchers.IO) {
        try {
            val state = _recordingState.value
            if (state !is RecordingState.Recording) {
                return@withContext Result.failure(Exception("Not recording"))
            }
            
            _recordingState.value = RecordingState.Stopping
            durationUpdateJob?.cancel()
            
            val recording = when (state.source) {
                RecordingSource.PHONE -> stopPhoneRecordingInternal()
                RecordingSource.GLASSES -> stopGlassesRecordingInternal()
            }
            
            _recordingState.value = RecordingState.Idle
            
            // Only save phone recordings to database here
            // Glasses recordings are saved by saveGlassesRecording() when audio data arrives via Bluetooth
            if (recording != null && recording.source == RecordingSource.PHONE) {
                recordingDao.insert(recording)
                Log.d(TAG, "Phone recording saved: ${recording.id}")
            } else if (recording != null && recording.source == RecordingSource.GLASSES) {
                Log.d(TAG, "Glasses recording stopped (ID: ${recording.id}), waiting for audio data via Bluetooth")
            }
            
            Result.success(recording)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording", e)
            _recordingState.value = RecordingState.Error(e.message ?: "Failed to stop recording")
            Result.failure(e)
        }
    }
    
    private fun stopPhoneRecordingInternal(): RecordingEntity? {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaRecorder", e)
        }
        mediaRecorder = null
        
        val file = currentRecordingFile ?: return null
        val id = currentRecordingId ?: return null
        val duration = System.currentTimeMillis() - recordingStartTime
        
        currentRecordingFile = null
        currentRecordingId = null
        
        if (!file.exists()) {
            Log.w(TAG, "Recording file not found: ${file.absolutePath}")
            return null
        }
        
        val title = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        
        return RecordingEntity(
            id = id,
            title = "Recording $title",
            filePath = file.absolutePath,
            source = RecordingSource.PHONE,
            status = RecordingStatus.COMPLETED,
            durationMs = duration,
            fileSizeBytes = file.length(),
            sampleRate = 16000,
            channels = 1
        )
    }
    
    private fun stopGlassesRecordingInternal(): RecordingEntity? {
        // For glasses recording, the actual audio data is received via Bluetooth
        // This creates a placeholder entry that will be updated when audio is received
        val id = currentRecordingId ?: return null
        val duration = System.currentTimeMillis() - recordingStartTime
        
        currentRecordingId = null
        
        val title = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        
        return RecordingEntity(
            id = id,
            title = "Glasses Recording $title",
            filePath = "", // Will be updated when audio data is received
            source = RecordingSource.GLASSES,
            status = RecordingStatus.COMPLETED,
            durationMs = duration
        )
    }
    
    private fun startDurationUpdate() {
        durationUpdateJob?.cancel()
        durationUpdateJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(100)
                val state = _recordingState.value
                if (state is RecordingState.Recording) {
                    val duration = System.currentTimeMillis() - state.startTime
                    _recordingState.value = state.copy(durationMs = duration)
                } else {
                    break
                }
            }
        }
    }
    
    // ==================== Recording Management ====================
    
    /**
     * Update recording title
     */
    suspend fun updateTitle(id: String, title: String) {
        recordingDao.updateTitle(id, title)
    }
    
    /**
     * Update recording notes
     */
    suspend fun updateNotes(id: String, notes: String?) {
        recordingDao.updateNotes(id, notes)
    }
    
    /**
     * Toggle favorite status
     */
    suspend fun toggleFavorite(id: String) {
        val recording = recordingDao.getRecordingById(id) ?: return
        recordingDao.updateFavorite(id, !recording.isFavorite)
    }
    
    /**
     * Update transcription result
     */
    suspend fun updateTranscript(id: String, transcript: String) {
        recordingDao.updateTranscript(id, transcript)
    }
    
    /**
     * Save glasses audio data as a recording
     * Called when glasses sends voice data via Bluetooth
     * @param audioData PCM audio data from glasses
     * @param transcript The transcription result (optional)
     * @param aiResponse The AI response (optional)
     * @return The saved RecordingEntity
     */
    suspend fun saveGlassesRecording(
        audioData: ByteArray,
        transcript: String? = null,
        aiResponse: String? = null,
        providerId: String? = null,
        modelId: String? = null
    ): RecordingEntity? = withContext(Dispatchers.IO) {
        try {
            val recordingId = UUID.randomUUID().toString()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "GLASSES_${timestamp}_$recordingId.wav"
            val outputFile = File(recordingsDir, fileName)
            
            // Convert PCM to WAV and save
            val wavData = pcmToWav(audioData)
            outputFile.writeBytes(wavData)
            
            // Estimate duration based on audio data size
            // PCM 16-bit mono at 16kHz = 2 bytes per sample, 16000 samples per second
            val durationMs = (audioData.size.toLong() * 1000) / (16000 * 2)
            
            val title = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
            
            val recording = RecordingEntity(
                id = recordingId,
                title = "Glasses Recording $title",
                filePath = outputFile.absolutePath,
                source = RecordingSource.GLASSES,
                status = RecordingStatus.COMPLETED,
                durationMs = durationMs,
                fileSizeBytes = outputFile.length(),
                sampleRate = 16000,
                channels = 1,
                transcript = transcript,
                aiResponse = aiResponse,
                providerId = providerId,
                modelId = modelId
            )
            
            recordingDao.insert(recording)
            Log.d(TAG, "Saved glasses recording: $recordingId, duration: ${durationMs}ms, size: ${audioData.size} bytes")
            
            recording
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save glasses recording", e)
            null
        }
    }
    
    /**
     * Convert PCM audio to WAV format
     */
    private fun pcmToWav(pcmData: ByteArray, sampleRate: Int = 16000, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val totalSize = 36 + dataSize
        
        val output = java.io.ByteArrayOutputStream()
        
        // RIFF header
        output.write("RIFF".toByteArray())
        output.write(intToBytes(totalSize, 4))
        output.write("WAVE".toByteArray())
        
        // fmt chunk
        output.write("fmt ".toByteArray())
        output.write(intToBytes(16, 4))  // chunk size
        output.write(intToBytes(1, 2))   // audio format (PCM)
        output.write(intToBytes(channels, 2))
        output.write(intToBytes(sampleRate, 4))
        output.write(intToBytes(byteRate, 4))
        output.write(intToBytes(blockAlign, 2))
        output.write(intToBytes(bitsPerSample, 2))
        
        // data chunk
        output.write("data".toByteArray())
        output.write(intToBytes(dataSize, 4))
        output.write(pcmData)
        
        return output.toByteArray()
    }
    
    /**
     * Convert int to little-endian bytes
     */
    private fun intToBytes(value: Int, numBytes: Int): ByteArray {
        val bytes = ByteArray(numBytes)
        for (i in 0 until numBytes) {
            bytes[i] = (value shr (8 * i) and 0xFF).toByte()
        }
        return bytes
    }
    
    /**
     * Update AI response
     */
    suspend fun updateAiResponse(id: String, response: String, providerId: String?, modelId: String?) {
        recordingDao.updateAiResponse(id, response, providerId, modelId)
    }
    
    /**
     * Mark recording as error
     */
    suspend fun markError(id: String, errorMessage: String) {
        recordingDao.updateError(id, errorMessage = errorMessage)
    }
    
    /**
     * Delete recording
     */
    suspend fun deleteRecording(id: String) = withContext(Dispatchers.IO) {
        val recording = recordingDao.getRecordingById(id)
        if (recording != null) {
            // Delete file
            if (recording.filePath.isNotBlank()) {
                File(recording.filePath).delete()
            }
            // Delete from database
            recordingDao.deleteById(id)
            Log.d(TAG, "Deleted recording: $id")
        }
    }
    
    /**
     * Delete multiple recordings
     */
    suspend fun deleteRecordings(ids: List<String>) = withContext(Dispatchers.IO) {
        ids.forEach { id ->
            val recording = recordingDao.getRecordingById(id)
            if (recording != null && recording.filePath.isNotBlank()) {
                File(recording.filePath).delete()
            }
        }
        recordingDao.deleteByIds(ids)
        Log.d(TAG, "Deleted ${ids.size} recordings")
    }
    
    /**
     * Get recording file
     */
    fun getRecordingFile(recording: RecordingEntity): File? {
        if (recording.filePath.isBlank()) return null
        val file = File(recording.filePath)
        return if (file.exists()) file else null
    }
    
    /**
     * Get statistics
     */
    suspend fun getStatistics(): RecordingStatistics = withContext(Dispatchers.IO) {
        RecordingStatistics(
            totalCount = recordingDao.getRecordingCount(),
            totalDurationMs = recordingDao.getTotalDurationMs() ?: 0
        )
    }
    
    /**
     * Release resources
     */
    fun release() {
        durationUpdateJob?.cancel()
        try {
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaRecorder", e)
        }
        mediaRecorder = null
    }
}

/**
 * Recording statistics
 */
data class RecordingStatistics(
    val totalCount: Int,
    val totalDurationMs: Long
)
