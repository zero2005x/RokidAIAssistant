package com.example.rokidphone.service.ai

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LiveAudioManagerTest {
    private val context = mockk<Context>(relaxed = true)
    private val audio = mockk<AudioManager>(relaxed = true)
    private val track = mockk<AudioTrack>(relaxed = true)
    private val scope = TestScope()
    private lateinit var manager: LiveAudioManager
    private val errors = mutableListOf<String>()

    @Before
    fun setUp() {
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns audio
        every { context.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_GRANTED
        mockkStatic(AudioRecord::class, AudioTrack::class, AcousticEchoCanceler::class, NoiseSuppressor::class)
        mockkConstructor(AudioRecord::class, AudioTrack.Builder::class)
        every { AudioRecord.getMinBufferSize(any(), any(), any()) } returns 1024
        every { anyConstructed<AudioRecord>().state } returns AudioRecord.STATE_INITIALIZED
        every { anyConstructed<AudioRecord>().recordingState } returns AudioRecord.RECORDSTATE_RECORDING
        every { anyConstructed<AudioRecord>().audioSessionId } returns 123
        every { anyConstructed<AudioRecord>().startRecording() } just Runs
        every { anyConstructed<AudioRecord>().stop() } just Runs
        every { anyConstructed<AudioRecord>().release() } just Runs
        every { anyConstructed<AudioRecord>().read(any<ByteArray>(), any(), any()) } returns -3
        every { AcousticEchoCanceler.isAvailable() } returns false
        every { NoiseSuppressor.isAvailable() } returns false
        every { AudioTrack.getMinBufferSize(any(), any(), any()) } returns 1024
        every { anyConstructed<AudioTrack.Builder>().setAudioAttributes(any()) } answers { self as AudioTrack.Builder }
        every { anyConstructed<AudioTrack.Builder>().setAudioFormat(any()) } answers { self as AudioTrack.Builder }
        every { anyConstructed<AudioTrack.Builder>().setBufferSizeInBytes(any()) } answers { self as AudioTrack.Builder }
        every { anyConstructed<AudioTrack.Builder>().setTransferMode(any()) } answers { self as AudioTrack.Builder }
        every { anyConstructed<AudioTrack.Builder>().build() } returns track
        manager = LiveAudioManager(context, scope)
        manager.onError = { errors += it }
    }

    @After
    fun tearDown() {
        manager.release()
        scope.runCurrent()
        unmockkAll()
    }

    @Test
    fun `permission and unsupported devices reject recording before allocating hardware`() {
        every { context.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_DENIED
        assertThat(manager.hasRecordPermission()).isFalse()
        assertThat(manager.startRecording()).isFalse()
        every { context.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_GRANTED
        every { AudioRecord.getMinBufferSize(any(), any(), any()) } returns 0
        assertThat(manager.startRecording()).isFalse()
        assertThat(errors).hasSize(2)
        verify(exactly = 0) { anyConstructed<AudioRecord>().startRecording() }
    }

    @Test
    fun `initialization and start failures release the recorder`() {
        every { anyConstructed<AudioRecord>().state } returns AudioRecord.STATE_UNINITIALIZED
        assertThat(manager.startRecording()).isFalse()
        every { anyConstructed<AudioRecord>().state } returns AudioRecord.STATE_INITIALIZED
        every { anyConstructed<AudioRecord>().startRecording() } throws IllegalStateException("busy")
        assertThat(manager.startRecording()).isFalse()
        verify(exactly = 2) { anyConstructed<AudioRecord>().release() }
        assertThat(errors.last()).contains("busy")
    }

    @Test
    fun `recording emits only bytes read and releases all effects after a read failure`() {
        val echo = mockk<AcousticEchoCanceler>(relaxed = true)
        val noise = mockk<NoiseSuppressor>(relaxed = true)
        every { AcousticEchoCanceler.isAvailable() } returns true
        every { NoiseSuppressor.isAvailable() } returns true
        every { AcousticEchoCanceler.create(123) } returns echo
        every { NoiseSuppressor.create(123) } returns noise
        var reads = 0
        every { anyConstructed<AudioRecord>().read(any<ByteArray>(), any(), any()) } answers {
            if (reads++ == 0) { firstArg<ByteArray>()[0] = 42; 1 } else -3
        }
        val chunks = mutableListOf<ByteArray>()
        manager.onAudioChunk = { chunks += it }
        assertThat(manager.startRecording()).isTrue()
        assertThat(manager.startRecording()).isTrue()
        assertThat(manager.state.value).isEqualTo(LiveAudioManager.State.RECORDING)
        manager.pauseRecording()
        scope.runCurrent()
        assertThat(chunks).isEmpty()
        manager.resumeRecording()
        scope.advanceTimeBy(10)
        scope.runCurrent()
        assertThat(chunks.single()).isEqualTo(byteArrayOf(42))
        assertThat(errors.single()).contains("Audio read failed: -3")
        verify(exactly = 1) { anyConstructed<AudioRecord>().startRecording() }
        verify { echo.release(); noise.release(); anyConstructed<AudioRecord>().release() }
        assertThat(manager.state.value).isEqualTo(LiveAudioManager.State.IDLE)
    }

    @Test
    fun `effect and cleanup failures do not prevent remaining resources being released`() {
        every { AcousticEchoCanceler.isAvailable() } returns true
        every { NoiseSuppressor.isAvailable() } returns true
        every { AcousticEchoCanceler.create(123) } throws IllegalStateException("unavailable")
        every { NoiseSuppressor.create(123) } throws IllegalStateException("unavailable")
        every { anyConstructed<AudioRecord>().stop() } throws IllegalStateException("stopped")
        every { anyConstructed<AudioRecord>().release() } throws IllegalStateException("released")
        assertThat(manager.startRecording()).isTrue()
        manager.stopRecording()
        manager.stopRecording()
        assertThat(manager.state.value).isEqualTo(LiveAudioManager.State.IDLE)
        verify { audio.mode = AudioManager.MODE_NORMAL }
    }

    @Test
    fun `recording cancellation releases resources without reporting an audio error`() {
        every { anyConstructed<AudioRecord>().read(any<ByteArray>(), any(), any()) } throws CancellationException("cancelled")
        manager.startRecording()
        scope.runCurrent()
        assertThat(errors).isEmpty()
        verify { anyConstructed<AudioRecord>().release() }
    }

    @Test
    fun `playback writes queued PCM and restores microphone after finishing`() {
        manager.pauseRecording()
        manager.startRecording()
        val pcm = byteArrayOf(3, 4)
        manager.playAudio(pcm)
        assertThat(manager.startPlayback()).isTrue()
        assertThat(manager.state.value).isEqualTo(LiveAudioManager.State.BOTH)
        assertThat(manager.isModelSpeaking.value).isTrue()
        scope.runCurrent()
        verify(exactly = 1) { track.play() }
        verify { track.write(pcm, 0, 2) }
        var completed = 0
        manager.onPlaybackComplete = { completed++ }
        manager.finishPlayback()
        scope.runCurrent()
        assertThat(completed).isEqualTo(1)
        assertThat(manager.isModelSpeaking.value).isFalse()
        assertThat(manager.isRecordingPaused.value).isFalse()
        verify { track.stop(); track.release() }
    }

    @Test
    fun `invalid output buffer and playback exceptions report errors without queuing audio`() {
        for (code in listOf(AudioTrack.ERROR, AudioTrack.ERROR_BAD_VALUE)) {
            every { AudioTrack.getMinBufferSize(any(), any(), any()) } returns code
            manager.playAudio(byteArrayOf(1))
        }
        every { AudioTrack.getMinBufferSize(any(), any(), any()) } returns 1024
        every { track.play() } throws IllegalStateException("busy")
        every { track.stop() } throws IllegalStateException("stopped")
        assertThat(manager.startPlayback()).isFalse()
        assertThat(errors).hasSize(3)
        verify { track.release() }
        verify(exactly = 0) { track.write(any<ByteArray>(), any(), any()) }
    }

    @Test
    fun `audio focus result and release are delegated to the audio manager`() {
        every { audio.requestAudioFocus(any<AudioFocusRequest>()) } returns AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        assertThat(manager.requestAudioFocus()).isTrue()
        every { audio.requestAudioFocus(any<AudioFocusRequest>()) } returns AudioManager.AUDIOFOCUS_REQUEST_FAILED
        assertThat(manager.requestAudioFocus()).isFalse()
        manager.abandonAudioFocus()
        verify { audio.abandonAudioFocusRequest(any()) }
        val noAudio = LiveAudioManager(mockk(relaxed = true), TestScope())
        assertThat(noAudio.requestAudioFocus()).isFalse()
        noAudio.release()
    }
}
