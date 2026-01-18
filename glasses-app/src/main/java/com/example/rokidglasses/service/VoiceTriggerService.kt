package com.example.rokidglasses.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Voice Trigger Service
 * Listens for Rokid system voice wake events
 */
class VoiceTriggerService : Service() {
    
    companion object {
        private const val TAG = "VoiceTriggerService"
        const val ACTION_VOICE_COMMAND = "com.rokid.action.VOICE_COMMAND"
        const val EXTRA_COMMAND = "command"
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { handleVoiceCommand(it) }
        return START_NOT_STICKY
    }
    
    private fun handleVoiceCommand(intent: Intent) {
        val command = intent.getStringExtra(EXTRA_COMMAND)
        Log.d(TAG, "Received voice command: $command")
        
        when (command?.lowercase()) {
            "hey rokid", "ni hao ruo qi" -> {
                // Wake up AI assistant
                startMainActivity()
            }
            else -> {
                // Other voice commands
            }
        }
    }
    
    private fun startMainActivity() {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        launchIntent?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(it)
        }
    }
}
