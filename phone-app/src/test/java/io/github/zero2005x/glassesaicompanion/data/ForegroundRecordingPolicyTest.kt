package io.github.zero2005x.glassesaicompanion.data

import com.google.common.truth.Truth.assertThat
import io.github.zero2005x.glassesaicompanion.data.db.RecordingSource
import io.github.zero2005x.glassesaicompanion.data.db.RecordingState
import org.junit.Test

class ForegroundRecordingPolicyTest {

    @Test
    fun `an active phone recording stops when the app is hidden`() {
        val state = RecordingState.Recording(source = RecordingSource.PHONE, startTime = 1L)

        assertThat(ForegroundRecordingPolicy.shouldStopWhenBackgrounded(state)).isTrue()
    }

    @Test
    fun `a paused phone recording stops when the app is hidden`() {
        val state = RecordingState.Paused(source = RecordingSource.PHONE, startTime = 1L, durationMs = 500L)

        assertThat(ForegroundRecordingPolicy.shouldStopWhenBackgrounded(state)).isTrue()
    }

    @Test
    fun `a glasses recording is not affected because audio arrives over Bluetooth`() {
        val recording = RecordingState.Recording(source = RecordingSource.GLASSES, startTime = 1L)
        val paused = RecordingState.Paused(source = RecordingSource.GLASSES, startTime = 1L, durationMs = 5L)

        assertThat(ForegroundRecordingPolicy.shouldStopWhenBackgrounded(recording)).isFalse()
        assertThat(ForegroundRecordingPolicy.shouldStopWhenBackgrounded(paused)).isFalse()
    }

    @Test
    fun `idle stopping and error states are left alone`() {
        assertThat(ForegroundRecordingPolicy.shouldStopWhenBackgrounded(RecordingState.Idle)).isFalse()
        assertThat(ForegroundRecordingPolicy.shouldStopWhenBackgrounded(RecordingState.Stopping)).isFalse()
        assertThat(ForegroundRecordingPolicy.shouldStopWhenBackgrounded(RecordingState.Error("x"))).isFalse()
    }
}
