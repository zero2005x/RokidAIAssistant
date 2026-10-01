package com.example.rokidphone.service.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
class ImagePayloadHelperTest {

    @After
    fun tearDown() = unmockkAll()

    private fun header(vararg bytes: Int, padTo: Int = 16) =
        ByteArray(padTo) { index -> if (index < bytes.size) bytes[index].toByte() else 0 }

    private val jpeg = header(0xFF, 0xD8, 0xFF, 0xE0)
    private val png = header(0x89, 0x50, 0x4E, 0x47)
    private val gif = header(0x47, 0x49, 0x46, 0x38)
    private val webp = header(0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x45, 0x42, 0x50)

    @Test
    fun `mime types are detected from magic bytes`() {
        assertThat(ImagePayloadHelper.detectMimeType(jpeg)).isEqualTo("image/jpeg")
        assertThat(ImagePayloadHelper.detectMimeType(png)).isEqualTo("image/png")
        assertThat(ImagePayloadHelper.detectMimeType(gif)).isEqualTo("image/gif")
        assertThat(ImagePayloadHelper.detectMimeType(webp)).isEqualTo("image/webp")
    }

    @Test
    fun `data that is not a supported image is rejected`() {
        assertThat(ImagePayloadHelper.detectMimeType(byteArrayOf(1, 2, 3))).isNull()
        assertThat(ImagePayloadHelper.detectMimeType(header(0x00, 0x01, 0x02, 0x03))).isNull()
        // RIFF container that is not WebP, and a RIFF header too short to tell.
        assertThat(ImagePayloadHelper.detectMimeType(
            header(0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x41, 0x56, 0x49, 0x20)
        )).isNull()
        assertThat(ImagePayloadHelper.detectMimeType(byteArrayOf(0x52, 0x49, 0x46, 0x46))).isNull()
    }

    @Test
    fun `an image within the limit is passed through untouched`() {
        val prepared = ImagePayloadHelper.prepare(png)
        assertThat(prepared.data).isSameInstanceAs(png)
        assertThat(prepared.mimeType).isEqualTo("image/png")
    }

    @Test
    fun `unsupported data raises a provider image error`() {
        val failure = runCatching { ImagePayloadHelper.prepare(byteArrayOf(1, 2, 3, 4)) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(ProviderImageException::class.java)
        assertThat(failure).hasMessageThat().contains("Unsupported image format")
    }

    @Test
    fun `an oversized image is recompressed as JPEG`() {
        mockkStatic(BitmapFactory::class, Bitmap::class)
        val bitmap = mockk<Bitmap>(relaxed = true)
        every { bitmap.width } returns 4000
        every { bitmap.height } returns 3000
        every { BitmapFactory.decodeByteArray(any(), any(), any()) } returns bitmap
        every { bitmap.compress(Bitmap.CompressFormat.JPEG, any(), any()) } answers {
            thirdArg<OutputStream>().write(ByteArray(4)); true
        }

        val prepared = ImagePayloadHelper.prepare(png, maxBytes = 8)

        assertThat(prepared.mimeType).isEqualTo("image/jpeg")
        assertThat(prepared.data).hasLength(4)
    }

    @Test
    fun `repeated shrinking stops after the retry budget and still returns data`() {
        mockkStatic(BitmapFactory::class, Bitmap::class)
        val bitmap = mockk<Bitmap>(relaxed = true)
        val scaled = mockk<Bitmap>(relaxed = true)
        every { bitmap.width } returns 4000
        every { bitmap.height } returns 3000
        every { BitmapFactory.decodeByteArray(any(), any(), any()) } returns bitmap
        every { Bitmap.createScaledBitmap(any(), any(), any(), any()) } returns scaled
        val qualities = mutableListOf<Int>()
        val sizes = mutableListOf<Pair<Int, Int>>()
        every { Bitmap.createScaledBitmap(bitmap, any(), any(), any()) } answers {
            sizes += secondArg<Int>() to thirdArg<Int>(); scaled
        }
        // Never small enough, so every attempt is used.
        for (source in listOf(bitmap, scaled)) {
            every { source.compress(Bitmap.CompressFormat.JPEG, any(), any()) } answers {
                qualities += secondArg<Int>()
                thirdArg<OutputStream>().write(ByteArray(1024)); true
            }
        }

        val prepared = ImagePayloadHelper.prepare(png, maxBytes = 8)

        assertThat(prepared.data).hasLength(1024)
        assertThat(prepared.mimeType).isEqualTo("image/jpeg")
        // Six shrink attempts plus the final forced compression.
        assertThat(qualities).hasSize(7)
        assertThat(qualities.first()).isEqualTo(85)
        assertThat(qualities.last()).isEqualTo(50)
        assertThat(sizes.first()).isEqualTo(2800 to 2100)
        // Intermediate bitmaps are released rather than leaked.
        io.mockk.verify(atLeast = 1) { scaled.recycle() }
    }

    @Test
    fun `an undecodable oversized image is returned unchanged`() {
        mockkStatic(BitmapFactory::class)
        every { BitmapFactory.decodeByteArray(any(), any(), any()) } returns null

        val prepared = ImagePayloadHelper.prepare(png, maxBytes = 1)

        assertThat(prepared.data).isSameInstanceAs(png)
        // The caller is still told JPEG, matching the recompression contract.
        assertThat(prepared.mimeType).isEqualTo("image/jpeg")
    }
}
