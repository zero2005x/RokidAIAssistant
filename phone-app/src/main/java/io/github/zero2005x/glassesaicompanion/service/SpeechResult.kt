package io.github.zero2005x.glassesaicompanion.service

import android.content.Context

/**
 * Speech error codes for localization support
 */
enum class SpeechErrorCode {
    AUDIO_TOO_SHORT,
    UNABLE_TO_RECOGNIZE,
    UPLOAD_FAILED,
    CREATE_TRANSCRIPT_FAILED,
    TRANSCRIPTION_TIMEOUT,
    TRANSCRIPTION_ERROR,
    SERVICE_UNAVAILABLE,
    NO_SPEECH_DETECTED,
    NOT_SUPPORTED,
    PROVIDER_NOT_SUPPORTED,
    RECOGNITION_FAILED,
    NETWORK_ERROR,
    UNKNOWN;
    
    /**
     * Get the string resource ID for this error code
     */
    fun getStringResId(): Int {
        return when (this) {
            AUDIO_TOO_SHORT -> io.github.zero2005x.glassesaicompanion.R.string.stt_error_audio_too_short
            UNABLE_TO_RECOGNIZE -> io.github.zero2005x.glassesaicompanion.R.string.stt_error_unable_to_recognize
            UPLOAD_FAILED -> io.github.zero2005x.glassesaicompanion.R.string.stt_error_upload_failed
            CREATE_TRANSCRIPT_FAILED -> io.github.zero2005x.glassesaicompanion.R.string.stt_error_create_transcript_failed
            TRANSCRIPTION_TIMEOUT -> io.github.zero2005x.glassesaicompanion.R.string.stt_error_transcription_timeout
            TRANSCRIPTION_ERROR -> io.github.zero2005x.glassesaicompanion.R.string.stt_error_transcription_error
            SERVICE_UNAVAILABLE -> io.github.zero2005x.glassesaicompanion.R.string.stt_error_service_unavailable
            NO_SPEECH_DETECTED -> io.github.zero2005x.glassesaicompanion.R.string.stt_error_no_speech_detected
            NOT_SUPPORTED -> io.github.zero2005x.glassesaicompanion.R.string.stt_error_not_supported
            PROVIDER_NOT_SUPPORTED -> io.github.zero2005x.glassesaicompanion.R.string.stt_error_provider_not_supported
            RECOGNITION_FAILED -> io.github.zero2005x.glassesaicompanion.R.string.stt_error_recognition_failed
            NETWORK_ERROR -> io.github.zero2005x.glassesaicompanion.R.string.stt_error_transcription_error
            UNKNOWN -> io.github.zero2005x.glassesaicompanion.R.string.stt_error_transcription_error
        }
    }
    
    /**
     * Check if this error code uses a format string (has %s placeholder).
     * Must stay in sync with the resource definitions in strings.xml —
     * NETWORK_ERROR and UNKNOWN map to the format string `stt_error_transcription_error`,
     * so they require a detail argument too.
     */
    fun requiresDetail(): Boolean = when (this) {
        TRANSCRIPTION_ERROR, PROVIDER_NOT_SUPPORTED, RECOGNITION_FAILED,
        NETWORK_ERROR, UNKNOWN -> true
        else -> false
    }
}

/**
 * Speech recognition result
 */
sealed class SpeechResult {
    data class Success(val text: String) : SpeechResult()
    data class Error(
        val message: String,
        val errorCode: SpeechErrorCode? = null,
        val errorDetail: String? = null
    ) : SpeechResult() {
        /** Derived from [errorCode] so contradictory states are impossible. */
        val isNetworkError: Boolean
            get() = errorCode == SpeechErrorCode.NETWORK_ERROR

        /**
         * Get localized error message using Context
         */
        fun getLocalizedMessage(context: Context): String {
            return when {
                errorCode != null -> {
                    val resId = errorCode.getStringResId()
                    if (errorCode.requiresDetail()) {
                        // Fall back to `message` so the message never ends with a
                        // dangling "...: " when no detail was supplied.
                        context.getString(resId, errorDetail ?: message)
                    } else {
                        context.getString(resId)
                    }
                }
                else -> message
            }
        }
    }
}
