package io.github.zero2005x.glassesaicompanion.data

import io.github.zero2005x.glassesaicompanion.data.db.RecordingSource
import io.github.zero2005x.glassesaicompanion.data.db.RecordingState

/**
 * Phone-microphone recording only runs while the app is visible.
 *
 * Android 14+ restricts microphone capture from the background, and the app deliberately does
 * not declare a `microphone` foreground service. When the app leaves the foreground, an active
 * phone recording is stopped (and saved), and the user is told why.
 *
 * Recordings that come from the glasses microphone arrive over Bluetooth and are not affected.
 */
object ForegroundRecordingPolicy {

    /** True when [state] is a phone-microphone recording that must stop when the app is hidden. */
    fun shouldStopWhenBackgrounded(state: RecordingState): Boolean = when (state) {
        is RecordingState.Recording -> state.source == RecordingSource.PHONE
        is RecordingState.Paused -> state.source == RecordingSource.PHONE
        else -> false
    }
}
