package com.example.rokidphone.data.db

import android.content.Context
import android.media.MediaRecorder
import io.mockk.Runs
import io.mockk.just
import io.mockk.mockkConstructor
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class RecordingRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dao = mockk<RecordingDao>(relaxed = true)
    private lateinit var repository: RecordingRepository

    @Before
    fun setUp() {
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns temporary.root
        val database = mockk<AppDatabase>()
        every { database.recordingDao() } returns dao
        mockkObject(AppDatabase.Companion)
        every { AppDatabase.getInstance(context) } returns database
        repository = RecordingRepository::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)
    }

    @After
    fun tearDown() {
        repository.release()
        unmockkAll()
    }

    @Test
    fun `glasses audio keeps requested identity metadata duration and wav payload`() = runTest {
        val pcm = ByteArray(32_000) { (it % 127).toByte() }
        val recording = repository.saveGlassesRecording(
            pcm, transcript = "transcript", aiResponse = "answer", providerId = "provider",
            modelId = "model", recordingId = "requested-id"
        )!!
        assertThat(recording.id).isEqualTo("requested-id")
        assertThat(recording.durationMs).isEqualTo(1000L)
        assertThat(recording.transcript).isEqualTo("transcript")
        assertThat(recording.aiResponse).isEqualTo("answer")
        assertThat(recording.providerId).isEqualTo("provider")
        assertThat(recording.modelId).isEqualTo("model")
        val wav = File(recording.filePath).readBytes()
        assertThat(String(wav.copyOfRange(0, 4), Charsets.US_ASCII)).isEqualTo("RIFF")
        assertThat(wav.copyOfRange(44, wav.size)).isEqualTo(pcm)
        coVerify(exactly = 1) { dao.insert(recording) }
    }

    @Test
    fun `cancelled database insert removes incomplete audio and propagates cancellation`() = runTest {
        coEvery { dao.insert(any()) } throws CancellationException("cancelled save")
        val failure = runCatching { repository.saveGlassesRecording(byteArrayOf(1, 2)) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(File(temporary.root, "recordings").listFiles()).isEmpty()
    }

    @Test
    fun `failed database insert removes incomplete audio and reports no saved recording`() = runTest {
        coEvery { dao.insert(any()) } throws IOException("storage unavailable")
        assertThat(repository.saveGlassesRecording(byteArrayOf(1, 2))).isNull()
        assertThat(File(temporary.root, "recordings").listFiles()).isEmpty()
    }

    @Test
    fun `failed cleanup is logged without swallowing cancellation`() = runTest {
        coEvery { dao.insert(any()) } answers {
            val file = File(firstArg<RecordingEntity>().filePath)
            check(file.delete())
            check(file.mkdir())
            File(file, "occupied").writeText("another writer")
            throw CancellationException("cancelled save")
        }
        val failure = runCatching { repository.saveGlassesRecording(byteArrayOf(1, 2)) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(CancellationException::class.java)

        assertThat(ShadowLog.getLogsForTag("RecordingRepository")
            .any { it.msg.startsWith("Failed to delete incomplete recording:") }).isTrue()
    }

    @Test
    fun `search escapes SQL wildcards before reaching the DAO`() = runTest {
        every { dao.searchRecordings(any()) } returns flowOf(emptyList())
        assertThat(repository.searchRecordings("""100%_done\ready""").first()).isEmpty()
        verify { dao.searchRecordings("""100\%\_done\\ready""") }
    }

    private fun fakeRecorder() {
        mockkConstructor(MediaRecorder::class)
        every { anyConstructed<MediaRecorder>().setAudioSource(any()) } just Runs
        every { anyConstructed<MediaRecorder>().setOutputFormat(any()) } just Runs
        every { anyConstructed<MediaRecorder>().setAudioEncoder(any()) } just Runs
        every { anyConstructed<MediaRecorder>().setAudioSamplingRate(any()) } just Runs
        every { anyConstructed<MediaRecorder>().setAudioChannels(any()) } just Runs
        every { anyConstructed<MediaRecorder>().setAudioEncodingBitRate(any()) } just Runs
        every { anyConstructed<MediaRecorder>().setOutputFile(any<String>()) } answers {
            File(firstArg<String>()).writeBytes(byteArrayOf(1, 2))
        }
        every { anyConstructed<MediaRecorder>().prepare() } just Runs
        every { anyConstructed<MediaRecorder>().start() } just Runs
        every { anyConstructed<MediaRecorder>().pause() } just Runs
        every { anyConstructed<MediaRecorder>().resume() } just Runs
        every { anyConstructed<MediaRecorder>().stop() } just Runs
        every { anyConstructed<MediaRecorder>().release() } just Runs
    }

    @Test
    fun `phone recording pauses resumes and persists its file on stop`() = runTest {
        fakeRecorder()
        val id = repository.startPhoneRecording().getOrThrow()
        assertThat(repository.startPhoneRecording().isFailure).isTrue()
        repository.pauseRecording()
        assertThat(repository.recordingState.value).isInstanceOf(RecordingState.Paused::class.java)
        repository.pauseRecording()
        assertThat(repository.recordingState.value).isInstanceOf(RecordingState.Recording::class.java)
        val recording = repository.stopRecording().getOrThrow()!!
        assertThat(recording.id).isEqualTo(id)
        assertThat(recording.source).isEqualTo(RecordingSource.PHONE)
        assertThat(File(recording.filePath).exists()).isTrue()
        assertThat(repository.recordingState.value).isEqualTo(RecordingState.Idle)
        coVerify { dao.insert(recording) }
        verify { anyConstructed<MediaRecorder>().release() }
        assertThat(repository.stopRecording().isFailure).isTrue()
    }

    @Test
    fun `failed phone database insert deletes audio and returns failure`() = runTest {
        fakeRecorder()
        coEvery { dao.insert(any()) } throws IOException("database full")
        repository.startPhoneRecording().getOrThrow()
        val result = repository.stopRecording()
        assertThat(result.exceptionOrNull()!!.message).isEqualTo("database full")
        assertThat(File(temporary.root, "recordings").listFiles()).isEmpty()
        assertThat(repository.recordingState.value).isInstanceOf(RecordingState.Error::class.java)
    }

    @Test
    fun `recorder start errors release microphone resources and incomplete files`() = runTest {
        fakeRecorder()
        every { anyConstructed<MediaRecorder>().start() } throws SecurityException("denied")
        assertThat(repository.startPhoneRecording().exceptionOrNull()!!.message).contains("permission")
        assertThat(File(temporary.root, "recordings").listFiles()).isEmpty()
        every { anyConstructed<MediaRecorder>().start() } throws IOException("device unavailable")
        assertThat(repository.startPhoneRecording().exceptionOrNull()!!.message).isEqualTo("device unavailable")
        verify(atLeast = 2) { anyConstructed<MediaRecorder>().release() }
    }

    @Test
    fun `cancellation while starting recorder is propagated after resource cleanup`() = runTest {
        fakeRecorder()
        every { anyConstructed<MediaRecorder>().start() } throws CancellationException("cancel start")
        val failure = runCatching { repository.startPhoneRecording() }.exceptionOrNull()
        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(File(temporary.root, "recordings").listFiles()).isEmpty()
        verify { anyConstructed<MediaRecorder>().release() }
    }

    @Test
    fun `stop failure never persists a truncated phone recording`() = runTest {
        fakeRecorder()
        repository.startPhoneRecording().getOrThrow()
        every { anyConstructed<MediaRecorder>().stop() } throws IllegalStateException("too short")
        assertThat(repository.stopRecording().isFailure).isTrue()
        coVerify(exactly = 0) { dao.insert(any()) }
        assertThat(File(temporary.root, "recordings").listFiles()).isEmpty()
        verify { anyConstructed<MediaRecorder>().release() }
    }

    @Test
    fun `glasses recording preserves identity while awaiting audio and release resets state`() = runTest {
        val id = repository.startGlassesRecording().getOrThrow()
        assertThat(repository.startGlassesRecording().isFailure).isTrue()
        repository.pauseRecording()
        assertThat(repository.recordingState.value).isInstanceOf(RecordingState.Recording::class.java)
        val record = repository.stopRecording().getOrThrow()!!
        assertThat(record.id).isEqualTo(id)
        assertThat(record.source).isEqualTo(RecordingSource.GLASSES)
        assertThat(record.filePath).isEmpty()
        coVerify(exactly = 0) { dao.insert(any()) }
        repository.startGlassesRecording().getOrThrow()
        repository.release()
        assertThat(repository.recordingState.value).isEqualTo(RecordingState.Idle)
    }
}
