package io.github.zero2005x.glassesaicompanion.service

import android.content.Context
import android.media.MediaPlayer
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TextToSpeechPlaybackTest {
    @get:Rule val temporary = TemporaryFolder()
    private val path = slot<String>()
    private val completion = slot<MediaPlayer.OnCompletionListener>()
    private val error = slot<MediaPlayer.OnErrorListener>()
    private lateinit var service: TextToSpeechService

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkConstructor(MediaPlayer::class)
        every { anyConstructed<MediaPlayer>().setDataSource(capture(path)) } just Runs
        every { anyConstructed<MediaPlayer>().setAudioAttributes(any()) } just Runs
        every { anyConstructed<MediaPlayer>().setOnCompletionListener(capture(completion)) } just Runs
        every { anyConstructed<MediaPlayer>().setOnErrorListener(capture(error)) } just Runs
        every { anyConstructed<MediaPlayer>().prepare() } just Runs
        every { anyConstructed<MediaPlayer>().start() } just Runs
        val context = mockk<Context>(relaxed = true)
        every { context.cacheDir } returns temporary.root
        service = TextToSpeechService(context)
    }

    @After
    fun tearDown() {
        service.shutdown()
        unmockkAll()
        Dispatchers.resetMain()
    }

    private suspend fun checkCleanup(playbackFailed: Boolean, deletionFails: Boolean) {
        val audio = byteArrayOf(1, 2, 3)
        service.playAudioData(audio)
        val file = File(path.captured)
        assertThat(file.readBytes()).isEqualTo(audio)
        verify(exactly = 1) { anyConstructed<MediaPlayer>().start() }
        if (deletionFails) {
            check(file.delete())
            check(file.mkdir())
            File(file, "occupied").writeText("another writer")
        }
        val player = mockk<MediaPlayer>(relaxed = true)
        if (playbackFailed) {
            assertThat(error.captured.onError(player, 1, 0)).isTrue()
        } else {
            completion.captured.onCompletion(player)
        }
        verify(exactly = 1) { player.release() }
        assertThat(file.exists()).isEqualTo(deletionFails)
        if (deletionFails) {
            assertThat(ShadowLog.getLogsForTag("TextToSpeechService")
                .any { it.msg.startsWith("Failed to delete temporary TTS audio") }).isTrue()
        }
    }

    @Test
    fun `completion releases player and deletes temporary audio`() = runTest {
        checkCleanup(playbackFailed = false, deletionFails = false)
    }

    @Test
    fun `playback error releases player deletes audio and reports handled error`() = runTest {
        checkCleanup(playbackFailed = true, deletionFails = false)
    }

    @Test
    fun `completion reports a failed file deletion`() = runTest {
        checkCleanup(playbackFailed = false, deletionFails = true)
    }

    @Test
    fun `playback error still releases player when file deletion fails`() = runTest {
        checkCleanup(playbackFailed = true, deletionFails = true)
    }
}
