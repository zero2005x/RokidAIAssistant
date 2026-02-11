package com.example.rokidphone.service.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live Mode Bidirectional Audio Manager
 *
 * Functionality:
 * - Uses AudioRecord for continuous recording (16kHz, 16-bit, mono).
 * - Splits audio into 100ms chunks to send to GeminiLiveService.
 * - Uses AudioTrack to play PCM audio returned by Gemini (24kHz).
 * - Implements echo cancellation: pauses microphone input when the AI is speaking.
 * - Uses Android AEC (Acoustic Echo Canceler) hardware acceleration.
 *
 * Integration with existing architecture:
 * - Coordinated by GeminiLiveSession, works in conjunction with GeminiLiveService.
 * - Does not affect the existing STT-first recording flow (GlassesViewModel recording).
 *
 * Android specific handling:
 * - Requires RECORD_AUDIO permission.
 * - Requires MODIFY_AUDIO_SETTINGS permission (for audio focus).
 * - Uses AudioManager.MODE_IN_COMMUNICATION to enable AEC.
 * - Handles audio focus to avoid conflicts with other apps.
 */
class LiveAudioManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "LiveAudioManager"

        // Input Audio Settings (16kHz, matches Gemini Live API requirements)
        private const val INPUT_SAMPLE_RATE = 16000
        private const val INPUT_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val INPUT_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val INPUT_CHUNK_DURATION_MS = 100  // 100ms audio chunk

        // Output Audio Settings (24kHz, matches Gemini Live API response format)
        private const val OUTPUT_SAMPLE_RATE = 24000
        private const val OUTPUT_CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val OUTPUT_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // Calculate input chunk size (bytes)
        // 16kHz * 2 bytes * 100ms = 3200 bytes
        private const val INPUT_CHUNK_SIZE = INPUT_SAMPLE_RATE * 2 * INPUT_CHUNK_DURATION_MS / 1000
    }

    // ========== State Management ==========

    /**
     * Audio Manager State
     */
    enum class State {
        IDLE,        // Idle
        RECORDING,   // Recording
        PLAYING,     // Playing
        BOTH,        // Both recording and playing
        ERROR        // Error
    }

    private val _state = MutableStateFlow(State.IDLE)
    val state = _state.asStateFlow()

    /**
     * Whether the AI is speaking (used for echo cancellation)
     */
    private val _isModelSpeaking = MutableStateFlow(false)
    val isModelSpeaking = _isModelSpeaking.asStateFlow()

    /**
     * Whether recording is paused (automatically paused during echo cancellation)
     */
    private val _isRecordingPaused = MutableStateFlow(false)
    val isRecordingPaused = _isRecordingPaused.asStateFlow()

    // ========== Audio Devices ==========

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var audioManager: AudioManager? = null

    // ========== Control Flags ==========

    private val isRecording = AtomicBoolean(false)
    private val isPlaying = AtomicBoolean(false)

    // ========== Playback Queue ==========

    private val playbackQueue = LinkedBlockingQueue<ByteArray>()
    private var playbackJob: Job? = null

    // ========== Callbacks ==========

    /**
     * Audio recording data callback (one chunk per 100ms)
     */
    var onAudioChunk: ((ByteArray) -> Unit)? = null

    /**
     * Playback complete callback
     */
    var onPlaybackComplete: (() -> Unit)? = null

    /**
     * Error callback
     */
    var onError: ((String) -> Unit)? = null

    // ========== Initialization ==========

    init {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    /**
     * Check if record permission is granted
     */
    fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    // ========== Recording Control ==========

    /**
     * Start recording
     * Automatically splits audio into 100ms chunks and emits them via the onAudioChunk callback.
     */
    fun startRecording(): Boolean {
        if (!hasRecordPermission()) {
            Log.e(TAG, "No recording permission")
            onError?.invoke("No recording permission. Please grant microphone access.")
            return false
        }

        if (isRecording.get()) {
            Log.w(TAG, "Already recording")
            return true
        }

        try {
            // Set communication mode to enable AEC
            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION

            // Calculate minimum buffer size
            val minBufferSize = AudioRecord.getMinBufferSize(
                INPUT_SAMPLE_RATE,
                INPUT_CHANNEL_CONFIG,
                INPUT_AUDIO_FORMAT
            )

            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "Unable to get valid buffer size")
                onError?.invoke("Audio device not supported")
                return false
            }

            // Use a larger buffer to ensure stability
            val bufferSize = maxOf(minBufferSize * 2, INPUT_CHUNK_SIZE * 4)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,  // Use voice communication mode to enable system AEC
                INPUT_SAMPLE_RATE,
                INPUT_CHANNEL_CONFIG,
                INPUT_AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                onError?.invoke("Microphone initialization failed")
                releaseAudioRecord()
                return false
            }

            // Enable hardware acoustic echo cancellation (if available)
            setupAcousticEchoCanceler()

            // Enable noise suppression (if available)
            setupNoiseSuppressor()

            audioRecord?.startRecording()
            isRecording.set(true)
            updateState()

            // Start recording loop
            startRecordingLoop()

            Log.d(TAG, "Started recording (${INPUT_SAMPLE_RATE}Hz, buffer=$bufferSize)")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            onError?.invoke("Failed to start recording: ${e.message}")
            releaseAudioRecord()
            return false
        }
    }

    /**
     * Stop recording
     */
    fun stopRecording() {
        if (!isRecording.get()) {
            return
        }

        Log.d(TAG, "Stopping recording")
        isRecording.set(false)
        releaseAudioRecord()
        updateState()

        // Restore normal mode
        audioManager?.mode = AudioManager.MODE_NORMAL
    }

    /**
     * Pause recording (for echo cancellation)
     * Does not release AudioRecord, only stops reading data.
     */
    fun pauseRecording() {
        _isRecordingPaused.value = true
        Log.d(TAG, "Recording paused (echo cancellation)")
    }

    /**
     * Resume recording
     */
    fun resumeRecording() {
        _isRecordingPaused.value = false
        Log.d(TAG, "Recording resumed")
    }

    /**
     * Recording loop coroutine
     */
    private fun startRecordingLoop() {
        scope.launch {
            val buffer = ByteArray(INPUT_CHUNK_SIZE)

            while (isRecording.get()) {
                // If paused (AI is speaking), skip reading but keep the loop running
                if (_isRecordingPaused.value) {
                    delay(10)
                    continue
                }

                val audioRecord = this@LiveAudioManager.audioRecord ?: break

                val bytesRead = audioRecord.read(buffer, 0, buffer.size)

                if (bytesRead > 0) {
                    // Copy data and emit via callback
                    val chunk = buffer.copyOf(bytesRead)
                    onAudioChunk?.invoke(chunk)
                } else if (bytesRead < 0) {
                    Log.e(TAG, "Failed to read audio: $bytesRead")
                    break
                }
            }

            Log.d(TAG, "Recording loop ended")
        }
    }

    /**
     * Setup hardware acoustic echo canceller
     */
    private fun setupAcousticEchoCanceler() {
        val audioSessionId = audioRecord?.audioSessionId ?: return

        if (AcousticEchoCanceler.isAvailable()) {
            try {
                acousticEchoCanceler = AcousticEchoCanceler.create(audioSessionId)
                acousticEchoCanceler?.enabled = true
                Log.d(TAG, "Hardware AEC enabled")
            } catch (e: Exception) {
                Log.w(TAG, "Unable to enable hardware AEC", e)
            }
        } else {
            Log.w(TAG, "This device does not support hardware AEC")
        }
    }

    /**
     * Setup noise suppression
     */
    private fun setupNoiseSuppressor() {
        val audioSessionId = audioRecord?.audioSessionId ?: return

        if (NoiseSuppressor.isAvailable()) {
            try {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)
                noiseSuppressor?.enabled = true
                Log.d(TAG, "Noise suppression enabled")
            } catch (e: Exception) {
                Log.w(TAG, "Unable to enable noise suppression", e)
            }
        } else {
            Log.w(TAG, "This device does not support noise suppression")
        }
    }

    /**
     * Release AudioRecord resources
     */
    private fun releaseAudioRecord() {
        try {
            acousticEchoCanceler?.release()
            acousticEchoCanceler = null

            noiseSuppressor?.release()
            noiseSuppressor = null

            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release AudioRecord", e)
        }
    }

    // ========== Playback Control ==========

    /**
     * Start playback
     * Audio data can be added via playAudio() after calling this.
     */
    fun startPlayback(): Boolean {
        if (isPlaying.get()) {
            Log.w(TAG, "Already playing")
            return true
        }

        try {
            val minBufferSize = AudioTrack.getMinBufferSize(
                OUTPUT_SAMPLE_RATE,
                OUTPUT_CHANNEL_CONFIG,
                OUTPUT_AUDIO_FORMAT
            )

            if (minBufferSize == AudioTrack.ERROR || minBufferSize == AudioTrack.ERROR_BAD_VALUE) {
                Log.e(TAG, "Unable to get valid playback buffer size")
                onError?.invoke("Audio playback device not supported")
                return false
            }

            // Use a larger buffer to ensure smooth playback
            val bufferSize = minBufferSize * 4

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(OUTPUT_AUDIO_FORMAT)
                        .setSampleRate(OUTPUT_SAMPLE_RATE)
                        .setChannelMask(OUTPUT_CHANNEL_CONFIG)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            isPlaying.set(true)
            _isModelSpeaking.value = true
            updateState()

            // Pause recording (echo cancellation)
            pauseRecording()

            // Start playback loop
            startPlaybackLoop()

            Log.d(TAG, "Started playback (${OUTPUT_SAMPLE_RATE}Hz)")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start playback", e)
            onError?.invoke("Failed to start playback: ${e.message}")
            releaseAudioTrack()
            return false
        }
    }

    /**
     * Add audio data to playback queue
     */
    fun playAudio(pcmData: ByteArray) {
        if (!isPlaying.get()) {
            // Automatically start playback
            if (!startPlayback()) {
                return
            }
        }

        playbackQueue.offer(pcmData)
    }

    /**
     * Stop playback
     */
    fun stopPlayback() {
        if (!isPlaying.get()) {
            return
        }

        Log.d(TAG, "Stopping playback")
        isPlaying.set(false)
        playbackQueue.clear()
        _isModelSpeaking.value = false
        releaseAudioTrack()
        updateState()

        // Resume recording
        resumeRecording()
    }

    /**
     * Finish playback (wait for queue to empty)
     */
    fun finishPlayback() {
        // Automatically stops after queue is empty
        scope.launch {
            // Wait for queue to empty, max wait 5 seconds
            var waitCount = 0
            while (playbackQueue.isNotEmpty() && waitCount < 500) {
                delay(10)
                waitCount++
            }

            if (isPlaying.get()) {
                stopPlayback()
                onPlaybackComplete?.invoke()
            }
        }
    }

    /**
     * Playback loop coroutine
     */
    private fun startPlaybackLoop() {
        playbackJob?.cancel()
        playbackJob = scope.launch {
            while (isPlaying.get()) {
                val data = playbackQueue.poll()

                if (data != null) {
                    audioTrack?.write(data, 0, data.size)
                } else {
                    // Queue is empty, wait briefly
                    delay(10)
                }
            }

            Log.d(TAG, "Playback loop ended")
        }
    }

    /**
     * Release AudioTrack resources
     */
    private fun releaseAudioTrack() {
        playbackJob?.cancel()
        playbackJob = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release AudioTrack", e)
        }
    }

    // ========== State Update ==========

    /**
     * Update state
     */
    private fun updateState() {
        _state.value = when {
            isRecording.get() && isPlaying.get() -> State.BOTH
            isRecording.get() -> State.RECORDING
            isPlaying.get() -> State.PLAYING
            else -> State.IDLE
        }
    }

    // ========== Audio Focus ==========

    /**
     * Request audio focus
     */
    fun requestAudioFocus(): Boolean {
        val focusRequest = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAcceptsDelayedFocusGain(false)
            .build()

        val result = audioManager?.requestAudioFocus(focusRequest)
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /**
     * Abandon audio focus
     */
    fun abandonAudioFocus() {
        val focusRequest = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .build()
        audioManager?.abandonAudioFocusRequest(focusRequest)
    }

    // ========== Resource Release ==========

    /**
     * Release all resources
     */
    fun release() {
        Log.d(TAG, "Releasing LiveAudioManager resources")

        stopRecording()
        stopPlayback()
        abandonAudioFocus()

        // Restore normal audio mode
        audioManager?.mode = AudioManager.MODE_NORMAL

        scope.cancel()
    }
}