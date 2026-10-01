package com.example.rokidaiassistant.services

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Text-to-Speech service
 * 
 * Supports multiple TTS solutions:
 * 1. Edge TTS (Microsoft, free, good quality) - Primary
 * 2. Android System TTS (offline available) - Fallback
 * 3. Google Translate TTS (free, average quality) - Fallback
 */
class TextToSpeechService(
    private val context: Context,
    /** Preferred locale used as voice fallback when script detection is inconclusive.
     *  Pass the user's configured speechLanguage / responseLanguage locale here.
     *  Defaults to null which resolves to EDGE_VOICE_EN as the neutral fallback.
     */
    private val preferredLocale: Locale? = null,
    /** Speech synthesis backend; substituted in tests so no WebSocket is opened. */
    private val edgeTtsClient: EdgeTtsClient = EdgeTtsClient(),
    /** Used only for the Google Translate fallback; substituted in tests. */
    private val httpClient: okhttp3.Call.Factory = defaultHttpClient
) {
    
    companion object {
        private const val TAG = "TextToSpeechService"
        
        // Edge TTS voice options
        const val EDGE_VOICE_XIAOXIAO = "zh-CN-XiaoxiaoNeural"      // Female voice, friendly
        const val EDGE_VOICE_YUNXI = "zh-CN-YunxiNeural"            // Male voice, young
        const val EDGE_VOICE_XIAOYI = "zh-TW-HsiaoChenNeural"       // Taiwan female voice
        const val EDGE_VOICE_YUNYANG = "zh-CN-YunyangNeural"        // Male voice, news anchor style
        const val EDGE_VOICE_SUNHI = "ko-KR-SunHiNeural"            // Korean female voice
        const val EDGE_VOICE_EN = "en-US-JennyNeural"               // English female voice
        const val EDGE_VOICE_NANAMI = "ja-JP-NanamiNeural"          // Japanese female voice
        
        // Default voice — English is a neutral fallback (not Chinese) so mixed-script or
        // undetected text doesn't get read by the wrong voice.
        // TODO: If a device-locale-aware default is preferred, derive this from Locale.getDefault().
        const val DEFAULT_VOICE = EDGE_VOICE_EN

        // One shared client: a per-instance client leaks a connection pool and
        // dispatcher threads for every service that is constructed.
        private val defaultHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }

    private var systemTts: TextToSpeech? = null
    private var isSystemTtsReady = false


    private var currentAudioTrack: AudioTrack? = null
    private var currentMediaPlayer: MediaPlayer? = null
    private var currentMediaFile: File? = null
    
    // TTS completion callback
    private var onCompletionCallback: (() -> Unit)? = null
    
    /**
     * Initialize system TTS (as fallback)
     */
    fun initSystemTts() {
        systemTts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val defaultLocale = Locale.getDefault()
                val result = systemTts?.setLanguage(defaultLocale)
                isSystemTtsReady = result != TextToSpeech.LANG_MISSING_DATA 
                    && result != TextToSpeech.LANG_NOT_SUPPORTED
                Log.d(TAG, "System TTS initialization: ${if (isSystemTtsReady) "success" else "failed"}")
                
                // Set speech rate and pitch
                systemTts?.setSpeechRate(1.0f)
                systemTts?.setPitch(1.0f)
            } else {
                Log.e(TAG, "System TTS initialization failed")
                isSystemTtsReady = false
            }
        }
    }
    
    /**
     * Synthesize speech using Edge TTS
     * 
     * @param text Text to synthesize
     * @param voice Voice role
     * @param rate Speech rate in SSML format (e.g. "+0%", "+50%")
     * @param pitch Pitch in SSML format (e.g. "+0Hz", "-12Hz")
     * @param onComplete Completion callback
     */
    suspend fun speakWithEdgeTts(
        text: String,
        voice: String = selectEdgeVoiceForText(text),
        rate: String = "+0%",
        pitch: String = "+0Hz",
        onComplete: (() -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Edge TTS synthesis: voice=$voice, rate=$rate, pitch=$pitch, text=${text.take(60)}")
            
            // Use Edge TTS WebSocket client
            val result = edgeTtsClient.synthesize(text, voice, rate, pitch)
            
            result.onSuccess { audioData ->
                if (audioData.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        playAudioData(audioData, onComplete)
                    }
                } else {
                    // Fallback to Google Translate TTS
                    Log.w(TAG, "Edge TTS returned empty data, trying Google Translate")
                    val googleAudio = fetchGoogleTranslateTts(text)
                    if (googleAudio != null && googleAudio.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            playAudioData(googleAudio, onComplete)
                        }
                    } else {
                        // Final fallback to system TTS
                        speakWithSystemTts(text, onComplete)
                    }
                }
            }
            
            result.onFailure { error ->
                Log.w(TAG, "Edge TTS failed: ${error.message}, trying fallback")
                // Try Google Translate TTS
                val googleAudio = fetchGoogleTranslateTts(text)
                if (googleAudio != null && googleAudio.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        playAudioData(googleAudio, onComplete)
                    }
                } else {
                    // Final fallback to system TTS
                    speakWithSystemTts(text, onComplete)
                }
            }
            
            Result.success(Unit)
            
        } catch (e: Exception) {
            Log.e(TAG, "Edge TTS failed", e)
            // Fallback to system TTS
            speakWithSystemTts(text, onComplete)
        }
    }
    
    /**
     * Use Google Translate TTS (free, average quality)
     */
    private suspend fun fetchGoogleTranslateTts(text: String): ByteArray? {
        return try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val languageCode = localeToGoogleLanguageCode(detectLocaleForText(text))
            val url = "https://translate.google.com/translate_tts?" +
                "ie=UTF-8&client=tw-ob&tl=$languageCode&q=$encodedText"
            
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()
            
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.bytes()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Google Translate TTS failed", e)
            null
        }
    }
    
    /**
     * Use system TTS
     */
    suspend fun speakWithSystemTts(
        text: String,
        onComplete: (() -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.Main) {
        try {
            if (!isSystemTtsReady || systemTts == null) {
                Log.e(TAG, "System TTS not ready")
                onComplete?.invoke()
                return@withContext Result.failure(IllegalStateException("System TTS not ready"))
            }
            
            Log.d(TAG, "System TTS playing: $text")

            val locale = detectLocaleForText(text)
            val languageResult = systemTts?.setLanguage(locale)
            if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                systemTts?.setLanguage(Locale.getDefault())
            } else {
                // Explicitly set a native voice to avoid non-native accent
                val nativeVoice = systemTts?.voices?.firstOrNull { 
                    it.locale.language == locale.language && !it.isNetworkConnectionRequired 
                }
                if (nativeVoice != null) {
                    systemTts?.voice = nativeVoice
                }
            }
            
            val utteranceId = "tts_${System.currentTimeMillis()}"
            
            systemTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "TTS playback started")
                }
                
                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "TTS playback completed")
                    onComplete?.invoke()
                }
                
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "TTS playback error")
                    onComplete?.invoke()
                }
                
                override fun onError(utteranceId: String?, errorCode: Int) {
                    Log.e(TAG, "TTS playback error: $errorCode")
                    onComplete?.invoke()
                }
            })
            
            val params = android.os.Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }
            
            systemTts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            Result.success(Unit)
            
        } catch (e: Exception) {
            Log.e(TAG, "System TTS failed", e)
            onComplete?.invoke()
            Result.failure(e)
        }
    }
    
    /**
     * Smart TTS service selection and playback
     */
    suspend fun speak(
        text: String,
        onComplete: (() -> Unit)? = null
    ): Result<Unit> {
        if (text.isBlank()) {
            onComplete?.invoke()
            return Result.success(Unit)
        }
        
        // Try Edge TTS first
        val result = speakWithEdgeTts(text, voice = selectEdgeVoiceForText(text), onComplete = onComplete)
        if (result.isSuccess) {
            return result
        }
        
        // Fallback to system TTS
        return speakWithSystemTts(text, onComplete)
    }
    
    /**
     * Play audio data (MP3)
     */
    private fun playAudioData(audioData: ByteArray, onComplete: (() -> Unit)?) {
        var tempFile: File? = null
        var mediaPlayer: MediaPlayer? = null
        try {
            stopMediaPlayer()

            // Save MP3 to temp file and play
            val createdFile = File.createTempFile("tts_", ".mp3", context.cacheDir)
            tempFile = createdFile
            FileOutputStream(createdFile).use { it.write(audioData) }

            mediaPlayer = MediaPlayer()
            currentMediaPlayer = mediaPlayer
            currentMediaFile = createdFile
            mediaPlayer.apply {
                setDataSource(createdFile.absolutePath)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .build()
                )
                setOnCompletionListener {
                    Log.d(TAG, "Audio playback completed")
                    finishMediaPlayer(it, createdFile)
                    onComplete?.invoke()
                }
                setOnErrorListener { player, what, extra ->
                    Log.e(TAG, "Audio playback error: what=$what, extra=$extra")
                    finishMediaPlayer(player, createdFile)
                    onComplete?.invoke()
                    true
                }
                prepare()
                start()
            }
            
            Log.d(TAG, "Started audio playback")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play audio", e)
            mediaPlayer?.let { finishMediaPlayer(it, tempFile) }
                ?: tempFile?.delete()
            onComplete?.invoke()
        }
    }

    private fun finishMediaPlayer(player: MediaPlayer, audioFile: File?) {
        if (currentMediaPlayer === player) {
            currentMediaPlayer = null
        }
        if (currentMediaFile == audioFile) {
            currentMediaFile = null
        }
        runCatching { player.release() }
            .onFailure { Log.w(TAG, "Failed to release MediaPlayer", it) }
        if (audioFile?.exists() == true && !audioFile.delete()) {
            Log.w(TAG, "Failed to delete temp audio file: ${audioFile.absolutePath}")
        }
    }

    private fun stopMediaPlayer() {
        val player = currentMediaPlayer
        val audioFile = currentMediaFile
        currentMediaPlayer = null
        currentMediaFile = null
        if (player != null) {
            player.setOnCompletionListener(null)
            player.setOnErrorListener(null)
            runCatching {
                if (player.isPlaying) player.stop()
                player.release()
            }.onFailure { Log.w(TAG, "Failed to stop MediaPlayer", it) }
        }
        if (audioFile?.exists() == true && !audioFile.delete()) {
            Log.w(TAG, "Failed to delete temp audio file: ${audioFile.absolutePath}")
        }
    }
    
    /**
     * Stop current playback
     */
    fun stop() {
        try {
            systemTts?.stop()
            currentAudioTrack?.stop()
            currentAudioTrack?.release()
            currentAudioTrack = null
            stopMediaPlayer()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop playback", e)
        }
    }
    
    /**
     * Release resources
     */
    fun release() {
        try {
            systemTts?.stop()
            systemTts?.shutdown()
            systemTts = null
            isSystemTtsReady = false
            
            currentAudioTrack?.release()
            currentAudioTrack = null
            stopMediaPlayer()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release resources", e)
        }
    }

    private fun detectLocaleForText(text: String): Locale {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Locale.getDefault()

        return when {
            trimmed.any { it in '\uAC00'..'\uD7AF' } -> Locale.KOREAN
            trimmed.any { it in '\u4E00'..'\u9FFF' } -> Locale.TRADITIONAL_CHINESE
            trimmed.any { it in '\u3040'..'\u30FF' } -> Locale.JAPANESE
            else -> Locale.getDefault()
        }
    }

    /**
     * Select the best Edge TTS voice for [text] based on detected script.
     *
     * If [voiceOverride] is not blank it is returned immediately, bypassing detection.
     *
     * Detection order:
     *  1. Korean Hangul  → Korean voice
     *  2. CJK Unified    → Chinese voice
     *  3. Hiragana/Katakana → Japanese voice
     *  4. Latin alphabet → English voice
     *  5. Undetected     → derived from [preferredLocale], then English as neutral fallback
     *
     * @param voiceOverride  Explicit voice name from user settings. If not blank, returned as-is.
     * @param preferredLocale  Caller-supplied locale (e.g. from ApiSettings.speechLanguage).
     *                         Defaults to the locale stored in this instance.
     */
    fun selectEdgeVoiceForText(
        text: String,
        voiceOverride: String = "",
        preferredLocale: Locale? = this.preferredLocale
    ): String {
        // Honour explicit user override first
        if (voiceOverride.isNotBlank()) return voiceOverride

        return when {
            text.any { it in '\uAC00'..'\uD7AF' } -> EDGE_VOICE_SUNHI
            text.any { it in '\u4E00'..'\u9FFF' } -> EDGE_VOICE_XIAOXIAO
            text.any { it in '\u3040'..'\u30FF' } -> EDGE_VOICE_NANAMI
            text.any { it in 'A'..'Z' || it in 'a'..'z' } -> EDGE_VOICE_EN
            // Nothing detected — honour the caller's preferred language, then fall back to English.
            // English is a safer neutral fallback than Chinese for mixed/unknown content.
            else -> when (preferredLocale?.language) {
                "ko" -> EDGE_VOICE_SUNHI
                "ja" -> EDGE_VOICE_NANAMI
                "zh" -> EDGE_VOICE_XIAOXIAO
                else -> EDGE_VOICE_EN
            }
        }
    }

    private fun localeToGoogleLanguageCode(locale: Locale): String {
        return when (locale.language) {
            Locale.KOREAN.language -> "ko-KR"
            Locale.JAPANESE.language -> "ja-JP"
            Locale.CHINESE.language -> "zh-TW"
            else -> "en-US"
        }
    }
}
