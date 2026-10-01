package com.example.rokidphone.service.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PhotoRepositoryTest {

    @get:Rule val temporary = TemporaryFolder()

    private val context = mockk<Context>(relaxed = true)
    private val job = Job()
    private val scope = CoroutineScope(job + Dispatchers.Unconfined)
    private lateinit var photoDir: File

    private fun photo(bytes: ByteArray = byteArrayOf(1, 2, 3), timestamp: Long = 1_700_000_000_000) =
        ReceivedPhoto(data = bytes, timestamp = timestamp, transferTimeMs = 42)

    private fun bitmap(width: Int = 640, height: Int = 480) = mockk<Bitmap>(relaxed = true) {
        every { this@mockk.width } returns width
        every { this@mockk.height } returns height
    }

    /**
     * The constructor loads the on-disk history on a background dispatcher; joining it
     * keeps that load from racing the photos a test stores afterwards.
     */
    private suspend fun repository(): PhotoRepository =
        PhotoRepository(context, scope).also { job.children.forEach { child -> child.join() } }

    @Before
    fun setUp() {
        every { context.filesDir } returns temporary.root
        photoDir = File(temporary.root, "glasses_photos")
        mockkStatic(BitmapFactory::class)
        every { BitmapFactory.decodeByteArray(any(), any(), any()) } returns bitmap()
        every { BitmapFactory.decodeFile(any(), any()) } returns bitmap()
    }

    @After
    fun tearDown() {
        job.cancel()
        unmockkAll()
    }

    @Test
    fun `a received photo is decoded, stored and published to current and history`() = runTest {
        val repository = repository()

        val stored = repository.processReceivedPhoto(photo())

        assertThat(stored).isNotNull()
        assertThat(stored!!.width).isEqualTo(640)
        assertThat(stored.height).isEqualTo(480)
        assertThat(stored.sizeBytes).isEqualTo(3)
        assertThat(stored.transferTimeMs).isEqualTo(42)
        assertThat(File(stored.filePath).readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(File(stored.filePath).parentFile).isEqualTo(photoDir)
        assertThat(repository.currentPhoto.value).isEqualTo(stored)
        assertThat(repository.photoHistory.value).containsExactly(stored)
        assertThat(repository.getPhotoBytes(stored)).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `undecodable data is rejected without writing a file`() = runTest {
        val repository = repository()
        every { BitmapFactory.decodeByteArray(any(), any(), any()) } returns null

        assertThat(repository.processReceivedPhoto(photo())).isNull()
        assertThat(repository.currentPhoto.value).isNull()
        assertThat(repository.photoHistory.value).isEmpty()
        assertThat(photoDir.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `newest photos come first and the oldest are pruned past the retention limit`() = runTest {
        val repository = repository()

        val stored = (1..52).map { repository.processReceivedPhoto(photo(byteArrayOf(it.toByte())))!! }

        assertThat(repository.photoHistory.value).hasSize(50)
        assertThat(repository.photoHistory.value.first()).isEqualTo(stored.last())
        // The two oldest were dropped from history and deleted from disk.
        assertThat(repository.photoHistory.value.map { it.id }).containsNoneOf(stored[0].id, stored[1].id)
        assertThat(File(stored[0].filePath).exists()).isFalse()
        assertThat(File(stored[1].filePath).exists()).isFalse()
        assertThat(File(stored.last().filePath).exists()).isTrue()
    }

    @Test
    fun `missing files are reported as null rather than throwing`() = runTest {
        val repository = repository()
        val absent = PhotoData("id", File(temporary.root, "absent.jpg").path, 0, 1, 1, 1, 0)

        assertThat(repository.getPhotoBytes(absent)).isNull()
        assertThat(repository.getBitmap(absent)).isNull()
        assertThat(repository.getBitmap(absent, maxSize = 100)).isNull()
    }

    @Test
    fun `bitmaps are sub-sampled when the caller asks for a maximum dimension`() = runTest {
        val repository = repository()
        val stored = repository.processReceivedPhoto(photo())!!
        val options = mutableListOf<BitmapFactory.Options>()
        every { BitmapFactory.decodeFile(any(), capture(options)) } answers {
            secondArg<BitmapFactory.Options>().outWidth = 1000
            secondArg<BitmapFactory.Options>().outHeight = 500
            bitmap()
        }

        assertThat(repository.getBitmap(stored, maxSize = 250)).isNotNull()
        assertThat(options.last().inSampleSize).isEqualTo(4)
        assertThat(options.last().inJustDecodeBounds).isFalse()

        // Without a limit the file is decoded as it is, with no bounds pass.
        options.clear()
        assertThat(repository.getBitmap(stored)).isNotNull()
        assertThat(options.single().inSampleSize).isEqualTo(0)
    }

    @Test
    fun `deleting removes the file, the history entry and the current selection`() = runTest {
        val repository = repository()
        val first = repository.processReceivedPhoto(photo(byteArrayOf(1)))!!
        val second = repository.processReceivedPhoto(photo(byteArrayOf(2)))!!

        repository.deletePhoto(first)
        assertThat(File(first.filePath).exists()).isFalse()
        assertThat(repository.photoHistory.value.map { it.id }).containsExactly(second.id)
        assertThat(repository.currentPhoto.value).isEqualTo(second)

        repository.deletePhoto(second)
        assertThat(repository.currentPhoto.value).isNull()
        // Deleting an already removed photo is a no-op, not a crash.
        repository.deletePhoto(second)
        assertThat(repository.photoHistory.value).isEmpty()
    }

    @Test
    fun `clearAll empties the directory and both state flows`() = runTest {
        val repository = repository()
        repository.processReceivedPhoto(photo(byteArrayOf(1)))
        repository.processReceivedPhoto(photo(byteArrayOf(2)))

        repository.clearAll()

        assertThat(photoDir.listFiles().orEmpty()).isEmpty()
        assertThat(repository.photoHistory.value).isEmpty()
        assertThat(repository.currentPhoto.value).isNull()
    }

    @Test
    fun `existing files on disk are restored into history newest first`() = runTest {
        photoDir.mkdirs()
        File(photoDir, "photo_old.jpg").apply { writeBytes(byteArrayOf(1)); setLastModified(1_000) }
        File(photoDir, "photo_new.jpg").apply { writeBytes(byteArrayOf(1, 2)); setLastModified(9_000) }
        File(photoDir, "notes.txt").writeText("not a photo")
        every { BitmapFactory.decodeFile(any(), any()) } answers {
            secondArg<BitmapFactory.Options>().outWidth = 8
            secondArg<BitmapFactory.Options>().outHeight = 4
            null
        }

        val history = repository().photoHistory.value

        assertThat(history.map { it.id }).containsExactly("photo_new", "photo_old").inOrder()
        assertThat(history.first().sizeBytes).isEqualTo(2)
        assertThat(history.first().width).isEqualTo(8)
        assertThat(history.first().height).isEqualTo(4)
        assertThat(history.first().transferTimeMs).isEqualTo(0)
    }

    @Test
    fun `photo metadata is formatted for display`() {
        val base = PhotoData("id", "/p.jpg", 0, 1920, 1080, 512, 0)
        assertThat(base.formattedDimensions).isEqualTo("1920x1080")
        assertThat(base.formattedSize).isEqualTo("512 B")
        assertThat(base.copy(sizeBytes = 2048).formattedSize).isEqualTo("2 KB")
        assertThat(base.copy(sizeBytes = 3 * 1024 * 1024 / 2).formattedSize).isEqualTo("1.5 MB")
        assertThat(base.formattedTimestamp).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")
    }
}
