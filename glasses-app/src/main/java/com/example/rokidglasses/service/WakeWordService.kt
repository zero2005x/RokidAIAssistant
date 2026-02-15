package com.example.rokidglasses.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.rokidglasses.MainActivity
import com.example.rokidglasses.R
import kotlinx.coroutines.*
import kotlin.math.abs

/**
 * Voice wake word service
 * Continuously listens to microphone, detects "Hi Rokid" wake word
 * Uses simple volume threshold detection, launches MainActivity when sound is detected
 */
class WakeWordService : Service() {
    
    companion object {
        private const val TAG = "WakeWordService"
        private const val CHANNEL_ID = "wake_word_channel"
        private const val NOTIFICATION_ID = 2001
        
        // Audio settings
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        
        // Wake detection settings
        private const val SILENCE_THRESHOLD = 2000      // Silence threshold
        private const val WAKE_THRESHOLD = 5000         // Wake threshold (louder sound)
        private const val WAKE_DURATION_MS = 500L       // Required duration
        private const val COOLDOWN_MS = 3000L           // Cooldown time after wake
        
        var isRunning = false
            private set
    }
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var audioRecord: AudioRecord? = null
    private var isListening = false
    private var lastWakeTime = 0L
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        isRunning = true
        startListening()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        stopListening()
        serviceScope.cancel()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.wake_word_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.wake_word_channel_description)
                setShowBadge(false)
            }
            
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.wake_word_notification_title))
            .setContentText(getString(R.string.wake_word_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    private fun startListening() {
        if (isListening) return
        
        serviceScope.launch {
            try {
                val bufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT
                )
                
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize * 2
                )
                
                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord initialization failed")
                    return@launch
                }
                
                audioRecord?.startRecording()
                isListening = true
                
                val buffer = ShortArray(bufferSize / 2)
                var loudStartTime = 0L
                
                Log.d(TAG, "Wake word detection started")
                
                while (isActive && isListening) {
                    val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    
                    if (readCount > 0) {
                        // Calculate volume (RMS)
                        val amplitude = calculateAmplitude(buffer, readCount)
                        
                        if (amplitude > WAKE_THRESHOLD) {
                            val now = System.currentTimeMillis()
                            
                            if (loudStartTime == 0L) {
                                loudStartTime = now
                            } else if (now - loudStartTime >= WAKE_DURATION_MS) {
                                // Check cooldown time
                                if (now - lastWakeTime >= COOLDOWN_MS) {
                                    Log.d(TAG, "Wake word detected! Amplitude: $amplitude")
                                    lastWakeTime = now
                                    triggerWakeUp()
                                }
                                loudStartTime = 0L
                            }
                        } else if (amplitude < SILENCE_THRESHOLD) {
                            loudStartTime = 0L
                        }
                    }
                    
                    delay(50) // Small delay to avoid excessive CPU usage
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "No microphone permission", e)
            } catch (e: Exception) {
                Log.e(TAG, "Error in wake word detection", e)
            }
        }
    }
    
    private fun calculateAmplitude(buffer: ShortArray, count: Int): Int {
        var sum = 0L
        for (i in 0 until count) {
            sum += abs(buffer[i].toInt())
        }
        return (sum / count).toInt()
    }
    
    private fun stopListening() {
        isListening = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }
    }
    
    private fun triggerWakeUp() {
        Log.d(TAG, "Triggering wake up - launching MainActivity")
        
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("wake_up", true)
        }
        startActivity(intent)
    }
}
