package io.github.zero2005x.glassesaicompanion.data.db

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private const val TAG = "RecordingRepository"
private const val GLASSES_SAMPLE_RATE = 16000
private const val GLASSES_BYTES_PER_SAMPLE = 2

/**
 * Recording state for UI
 */
sealed class RecordingState {
    object Idle : RecordingState()
    data class Recording(val source: RecordingSource, val startTime: Long, val durationMs: Long = 0) : RecordingState()
    data class Paused(val source: RecordingSource, val startTime: Long, val durationMs: Long) : RecordingState()
    object Stopping : RecordingState()
    data class Error(val message: String) : RecordingState()
}

/**
 * Repository for managing audio recordings
 */
class RecordingRepository private constructor(
    private val context: Context
) {
    // Process-wide singleton must own its scope: capturing a caller's scope (e.g. a
    // viewModelScope passed on the first getInstance call) breaks the duration ticker
    // forever once that scope is cancelled.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val recordingDao = AppDatabase.getInstance(context).recordingDao()
    private val recordingsDir = File(context.filesDir, "recordings").apply { mkdirs() }
    
    // Recording state
    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()
    
    // Guards start/stop state transitions against concurrent calls
    private val recordingMutex = Mutex()

    // Current recording (mutated from IO threads; @Volatile for visibility)
    @Volatile private var mediaRecorder: MediaRecorder? = null
    @Volatile private var currentRecordingFile: File? = null
    @Volatile private var currentRecordingId: String? = null
    @Volatile private var recordingStartTime: Long = 0
    @Volatile private var activeSegmentStartTime: Long = 0
    @Volatile private var accumulatedDurationMs: Long = 0
    
    // Duration update job
    private var durationUpdateJob: kotlinx.coroutines.Job? = null
    
    companion object {
        @Volatile
        private var instance: RecordingRepository? = null
        
        fun getInstance(context: Context): RecordingRepository {
            return instance ?: synchronized(this) {
                instance ?: RecordingRepository(context.applicationContext).also { instance = it }
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
    fun searchRecordings(query: String): Flow<List<RecordingEntity>> =
        recordingDao.searchRecordings(RecordingQueryEscaper.escapeLikeQuery(query))
    
    // ==================== Recording Control ====================
    
    /**
     * Start recording from phone microphone
     */
    suspend fun startPhoneRecording(): Result<String> = withContext(Dispatchers.IO) {
        recordingMutex.withLock {
            var newRecorder: MediaRecorder? = null
            try {
                if (_recordingState.value !is RecordingState.Idle &&
                    _recordingState.value !is RecordingState.Error
                ) {
                    return@withLock Result.failure(Exception("Already recording"))
                }

                val recordingId = UUID.randomUUID().toString()
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val outputFile = File(recordingsDir, "REC_${timestamp}_$recordingId.m4a")
                currentRecordingFile = outputFile
                currentRecordingId = recordingId

                newRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(context)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }
                newRecorder.apply {
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
                mediaRecorder = newRecorder
                newRecorder = null

                recordingStartTime = System.currentTimeMillis()
                activeSegmentStartTime = recordingStartTime
                accumulatedDurationMs = 0
                _recordingState.value = RecordingState.Recording(
                    source = RecordingSource.PHONE,
                    startTime = recordingStartTime
                )
                startDurationUpdate()

                Log.d(TAG, "Started phone recording: $recordingId")
                Result.success(recordingId)
            } catch (e: CancellationException) {
                safelyRelease(newRecorder)
                cleanupFailedRecording()
                throw e
            } catch (e: SecurityException) {
                safelyRelease(newRecorder)
                cleanupFailedRecording()
                Log.e(TAG, "Microphone permission denied", e)
                _recordingState.value = RecordingState.Error("Microphone permission required")
                Result.failure(Exception("Microphone permission required. Please grant the permission in Settings."))
            } catch (e: Exception) {
                safelyRelease(newRecorder)
                cleanupFailedRecording()
                Log.e(TAG, "Failed to start phone recording", e)
                _recordingState.value = RecordingState.Error(e.message ?: "Failed to start recording")
                Result.failure(e)
            }
        }
    }
    
    /**
     * Request glasses to start recording
     * Returns recording ID if request was sent successfully
     */
    suspend fun startGlassesRecording(): Result<String> = withContext(Dispatchers.IO) {
        recordingMutex.withLock {
            try {
                if (_recordingState.value !is RecordingState.Idle &&
                    _recordingState.value !is RecordingState.Error
                ) {
                    return@withLock Result.failure(Exception("Already recording"))
                }

                val recordingId = UUID.randomUUID().toString()
                currentRecordingId = recordingId
                recordingStartTime = System.currentTimeMillis()
                activeSegmentStartTime = recordingStartTime
                accumulatedDurationMs = 0

                _recordingState.value = RecordingState.Recording(
                    source = RecordingSource.GLASSES,
                    startTime = recordingStartTime
                )
                startDurationUpdate()

                Log.d(TAG, "Started glasses recording request: $recordingId")
                Result.success(recordingId)
            } catch (e: CancellationException) {
                cleanupRecordingState(deleteFile = false)
                throw e
            } catch (e: Exception) {
                cleanupRecordingState(deleteFile = false)
                Log.e(TAG, "Failed to start glasses recording", e)
                _recordingState.value = RecordingState.Error(e.message ?: "Failed to start recording")
                Result.failure(e)
            }
        }
    }
    
    /**
     * Pause current recording (requires API 24+)
     * Note: MediaRecorder pause/resume is only available on API 24+
     */
    suspend fun pauseRecording() = withContext(Dispatchers.IO) {
        recordingMutex.withLock {
            try {
                when (val state = _recordingState.value) {
                    is RecordingState.Recording -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                            state.source == RecordingSource.PHONE
                        ) {
                            mediaRecorder?.pause()
                            accumulatedDurationMs += System.currentTimeMillis() - activeSegmentStartTime
                            durationUpdateJob?.cancel()
                            _recordingState.value = RecordingState.Paused(
                                source = state.source,
                                startTime = state.startTime,
                                durationMs = accumulatedDurationMs
                            )
                            Log.d(TAG, "Recording paused")
                        } else {
                            Log.w(TAG, "Pause not supported on this device or recording source")
                        }
                    }
                    is RecordingState.Paused -> {
                        mediaRecorder?.resume()
                        activeSegmentStartTime = System.currentTimeMillis()
                        _recordingState.value = RecordingState.Recording(
                            source = state.source,
                            startTime = state.startTime,
                            durationMs = state.durationMs
                        )
                        startDurationUpdate()
                        Log.d(TAG, "Recording resumed")
                    }
                    else -> Unit
                }
            } catch (e: CancellationException) {
                cleanupFailedRecording()
                throw e
            } catch (e: Exception) {
                cleanupFailedRecording()
                Log.e(TAG, "Failed to pause or resume recording", e)
                _recordingState.value = RecordingState.Error(e.message ?: "Failed to pause recording")
            }
        }
    }
    
    /**
     * Stop current recording
     */
    suspend fun stopRecording(): Result<RecordingEntity?> = withContext(Dispatchers.IO) {
        recordingMutex.withLock {
            try {
                val state = _recordingState.value
                val source = when (state) {
                    is RecordingState.Recording -> state.source
                    is RecordingState.Paused -> state.source
                    else -> return@withLock Result.failure(Exception("Not recording"))
                }
                val duration = when (state) {
                    is RecordingState.Recording -> accumulatedDurationMs +
                        (System.currentTimeMillis() - activeSegmentStartTime)
                    is RecordingState.Paused -> state.durationMs
                    else -> 0
                }

                _recordingState.value = RecordingState.Stopping
                durationUpdateJob?.cancel()

                val recording = when (source) {
                    RecordingSource.PHONE -> stopPhoneRecordingInternal(duration)
                    RecordingSource.GLASSES -> stopGlassesRecordingInternal(duration)
                }

                if (recording != null && recording.source == RecordingSource.PHONE) {
                    try {
                        recordingDao.insert(recording)
                    } catch (e: Exception) {
                        deleteIncompleteRecording(File(recording.filePath))
                        throw e
                    }
                    Log.d(TAG, "Phone recording saved: ${recording.id}")
                } else if (recording != null && recording.source == RecordingSource.GLASSES) {
                    Log.d(TAG, "Glasses recording stopped (ID: ${recording.id}), waiting for audio data via Bluetooth")
                }

                cleanupRecordingState(deleteFile = false)
                _recordingState.value = RecordingState.Idle
                Result.success(recording)
            } catch (e: CancellationException) {
                cleanupFailedRecording()
                throw e
            } catch (e: Exception) {
                cleanupFailedRecording()
                Log.e(TAG, "Failed to stop recording", e)
                _recordingState.value = RecordingState.Error(e.message ?: "Failed to stop recording")
                Result.failure(e)
            }
        }
    }
    
    private fun stopPhoneRecordingInternal(duration: Long): RecordingEntity? {
        val recorder = mediaRecorder
        mediaRecorder = null
        try {
            recorder?.stop()
        } catch (e: Exception) {
            // stop() threw (recording too short / invalid state): the file may be
            // truncated/corrupt — do not report success for it.
            Log.e(TAG, "Error stopping MediaRecorder", e)
            throw e
        } finally {
            // Always release, even when stop() throws, or the microphone stays held.
            try {
                recorder?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing MediaRecorder", e)
            }
        }
        
        val file = currentRecordingFile ?: return null
        val id = currentRecordingId ?: return null
        
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
    
    private fun stopGlassesRecordingInternal(duration: Long): RecordingEntity? {
        // For glasses recording, the actual audio data is received via Bluetooth
        // This creates a placeholder entry that will be updated when audio is received
        val id = currentRecordingId ?: return null
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
                    val duration = accumulatedDurationMs +
                        (System.currentTimeMillis() - activeSegmentStartTime)
                    // If stopRecording() has already published Stopping/Idle, do not
                    // resurrect this stale Recording state from the ticker.
                    _recordingState.compareAndSet(state, state.copy(durationMs = duration))
                } else {
                    break
                }
            }
        }
    }

    private fun safelyRelease(recorder: MediaRecorder?) {
        try {
            recorder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release MediaRecorder", e)
        }
    }

    private fun deleteIncompleteRecording(file: File?) {
        if (file != null && file.exists() && !file.delete()) {
            Log.w(TAG, "Failed to delete incomplete recording: ${file.absolutePath}")
        }
    }

    private fun cleanupFailedRecording() {
        safelyRelease(mediaRecorder)
        mediaRecorder = null
        cleanupRecordingState(deleteFile = true)
    }

    private fun cleanupRecordingState(deleteFile: Boolean) {
        durationUpdateJob?.cancel()
        durationUpdateJob = null
        if (deleteFile) {
            currentRecordingFile?.let { file ->
                if (file.exists() && !file.delete()) {
                    Log.w(TAG, "Failed to delete incomplete recording: ${file.absolutePath}")
                }
            }
        }
        currentRecordingFile = null
        currentRecordingId = null
        recordingStartTime = 0
        activeSegmentStartTime = 0
        accumulatedDurationMs = 0
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
     * @param recordingId The ID returned by startGlassesRecording(), so the persisted row
     *        correlates with the ID handed to the caller at start; a new UUID is generated
     *        when the caller has none.
     * @return The saved RecordingEntity
     */
    suspend fun saveGlassesRecording(
        audioData: ByteArray,
        transcript: String? = null,
        aiResponse: String? = null,
        providerId: String? = null,
        modelId: String? = null,
        recordingId: String? = null
    ): RecordingEntity? = withContext(Dispatchers.IO) {
        var outputFile: File? = null
        try {
            val newRecordingId = recordingId ?: UUID.randomUUID().toString()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "GLASSES_${timestamp}_$newRecordingId.wav"
            val file = File(recordingsDir, fileName)
            outputFile = file
            
            // Convert PCM to WAV and save
            val wavData = WavEncoder.pcmToWav(audioData)
            file.writeBytes(wavData)
            
            // Estimate duration based on audio data size
            // PCM 16-bit mono at 16kHz = 2 bytes per sample, 16000 samples per second
            val durationMs = (audioData.size.toLong() * 1000) / (GLASSES_SAMPLE_RATE * GLASSES_BYTES_PER_SAMPLE)
            
            val title = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
            
            val recording = RecordingEntity(
                id = newRecordingId,
                title = "Glasses Recording $title",
                filePath = file.absolutePath,
                source = RecordingSource.GLASSES,
                status = RecordingStatus.COMPLETED,
                durationMs = durationMs,
                fileSizeBytes = file.length(),
                sampleRate = GLASSES_SAMPLE_RATE,
                channels = 1,
                transcript = transcript,
                aiResponse = aiResponse,
                providerId = providerId,
                modelId = modelId
            )
            
            recordingDao.insert(recording)
            Log.d(TAG, "Saved glasses recording: $newRecordingId, duration: ${durationMs}ms, size: ${audioData.size} bytes")
            
            recording
        } catch (e: CancellationException) {
            deleteIncompleteRecording(outputFile)
            throw e
        } catch (e: Exception) {
            deleteIncompleteRecording(outputFile)
            Log.e(TAG, "Failed to save glasses recording", e)
            null
        }
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
            if (recording.filePath.isNotBlank() && !File(recording.filePath).delete()) {
                Log.w(TAG, "Failed to delete recording file: ${recording.filePath}")
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
            if (recording != null && recording.filePath.isNotBlank() && !File(recording.filePath).delete()) {
                Log.w(TAG, "Failed to delete recording file: ${recording.filePath}")
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
        safelyRelease(mediaRecorder)
        mediaRecorder = null
        // Reset state: otherwise observers keep seeing a bogus "recording in progress"
        // and the current-recording fields point at an orphaned partial file.
        currentRecordingFile = null
        currentRecordingId = null
        recordingStartTime = 0
        activeSegmentStartTime = 0
        accumulatedDurationMs = 0
        _recordingState.value = RecordingState.Idle
    }
}

/**
 * Recording statistics
 */
data class RecordingStatistics(
    val totalCount: Int,
    val totalDurationMs: Long
)

internal object RecordingQueryEscaper {
    /** Escape LIKE wildcards ('%', '_') and the escape char ('\') in user input. */
    fun escapeLikeQuery(query: String): String =
        query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}

internal object WavEncoder {
    fun pcmToWav(
        pcmData: ByteArray,
        sampleRate: Int = GLASSES_SAMPLE_RATE,
        channels: Int = 1,
        bitsPerSample: Int = 16
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val totalSize = 36 + dataSize

        val output = java.io.ByteArrayOutputStream()

        output.write("RIFF".toByteArray())
        output.write(intToBytes(totalSize, 4))
        output.write("WAVE".toByteArray())

        output.write("fmt ".toByteArray())
        output.write(intToBytes(16, 4))
        output.write(intToBytes(1, 2))
        output.write(intToBytes(channels, 2))
        output.write(intToBytes(sampleRate, 4))
        output.write(intToBytes(byteRate, 4))
        output.write(intToBytes(blockAlign, 2))
        output.write(intToBytes(bitsPerSample, 2))

        output.write("data".toByteArray())
        output.write(intToBytes(dataSize, 4))
        output.write(pcmData)

        return output.toByteArray()
    }

    internal fun intToBytes(value: Int, numBytes: Int): ByteArray {
        val bytes = ByteArray(numBytes)
        for (i in 0 until numBytes) {
            bytes[i] = (value shr (8 * i) and 0xFF).toByte()
        }
        return bytes
    }
}
