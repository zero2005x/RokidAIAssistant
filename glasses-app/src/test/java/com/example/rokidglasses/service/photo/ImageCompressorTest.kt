package com.example.rokidglasses.service.photo

import android.graphics.Bitmap
import com.example.rokidcommon.protocol.photo.PhotoTransferConstants
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

/**
 * Downscaling, JPEG encoding and rotation in [ImageCompressor].
 */
@RunWith(RobolectricTestRunner::class)
class ImageCompressorTest {

    private fun bitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    /** JPEG bytes that decode back to a bitmap of the given size. */
    private fun encoded(width: Int, height: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap(width, height).compress(Bitmap.CompressFormat.JPEG, 90, stream)
        return stream.toByteArray()
    }

    private fun sampleSize(actualW: Int, actualH: Int, targetW: Int, targetH: Int): Int =
        ImageCompressor::class.java
            .getDeclaredMethod(
                "calculateInSampleSize",
                Int::class.java, Int::class.java, Int::class.java, Int::class.java
            )
            .apply { isAccessible = true }
            .invoke(ImageCompressor, actualW, actualH, targetW, targetH) as Int

    private fun scale(source: Bitmap, targetW: Int, targetH: Int): Bitmap =
        ImageCompressor::class.java
            .getDeclaredMethod("scaleBitmap", Bitmap::class.java, Int::class.java, Int::class.java)
            .apply { isAccessible = true }
            .invoke(ImageCompressor, source, targetW, targetH) as Bitmap

    // ==================== Sample size ====================

    @Test
    fun `an image at or below the target is not subsampled`() {
        assertThat(sampleSize(1280, 720, 1280, 720)).isEqualTo(1)
        assertThat(sampleSize(640, 480, 1280, 720)).isEqualTo(1)
    }

    @Test
    fun `the sample size doubles while both halves still cover the target`() {
        // 2560x1440 halves to 1280x720, which still covers the target exactly.
        assertThat(sampleSize(2560, 1440, 1280, 720)).isEqualTo(2)
        assertThat(sampleSize(5120, 2880, 1280, 720)).isEqualTo(4)
        // Oversized on one axis only: halving would drop below the target height.
        assertThat(sampleSize(4000, 720, 1280, 720)).isEqualTo(1)
    }

    // ==================== Scaling ====================

    @Test
    fun `a bitmap larger than the target is scaled down keeping its aspect ratio`() {
        val scaled = scale(bitmap(1920, 1080), 1280, 720)

        assertThat(scaled.width).isEqualTo(1280)
        assertThat(scaled.height).isEqualTo(720)
    }

    @Test
    fun `the limiting axis decides the scale`() {
        // A tall image: height is the binding constraint.
        val tall = scale(bitmap(1000, 4000), 1280, 720)
        assertThat(tall.height).isEqualTo(720)
        assertThat(tall.width).isEqualTo(180)

        // A wide image: width binds instead.
        val wide = scale(bitmap(4000, 1000), 1280, 720)
        assertThat(wide.width).isEqualTo(1280)
        assertThat(wide.height).isEqualTo(320)
    }

    @Test
    fun `a bitmap that already fits is returned untouched`() {
        val small = bitmap(640, 480)

        assertThat(scale(small, 1280, 720)).isSameInstanceAs(small)
        // Exactly the target size still counts as fitting.
        val exact = bitmap(1280, 720)
        assertThat(scale(exact, 1280, 720)).isSameInstanceAs(exact)
    }

    // ==================== Compression ====================

    @Test
    fun `compressing for transfer downscales to the 720p target`() = runBlocking {
        val compressed = ImageCompressor.compressForTransfer(encoded(1920, 1080))

        assertThat(compressed).isNotEmpty()
        assertThat(ImageCompressor.getImageDimensions(compressed))
            .isEqualTo(PhotoTransferConstants.TARGET_IMAGE_WIDTH to PhotoTransferConstants.TARGET_IMAGE_HEIGHT)
    }

    @Test
    fun `an explicit target size and quality are honoured`() = runBlocking {
        val compressed = ImageCompressor.compressForTransfer(
            encoded(1920, 1080), targetWidth = 640, targetHeight = 360, quality = 50
        )

        assertThat(ImageCompressor.getImageDimensions(compressed)).isEqualTo(640 to 360)
    }

    @Test
    fun `an image already below the target keeps its own dimensions`() = runBlocking {
        val compressed = ImageCompressor.compressForTransfer(encoded(320, 240))

        assertThat(ImageCompressor.getImageDimensions(compressed)).isEqualTo(320 to 240)
    }

    @Test
    fun `a bitmap can be compressed without decoding it first`() = runBlocking {
        val compressed = ImageCompressor.compressBitmap(bitmap(1920, 1080))

        assertThat(compressed).isNotEmpty()
        assertThat(ImageCompressor.getImageDimensions(compressed)).isEqualTo(1280 to 720)

        // A bitmap that already fits is encoded at its own size.
        val asIs = ImageCompressor.compressBitmap(bitmap(800, 600))
        assertThat(ImageCompressor.getImageDimensions(asIs)).isEqualTo(800 to 600)
    }

    // ==================== Rotation ====================

    @Test
    fun `no rotation returns the original bytes untouched`() = runBlocking {
        val original = encoded(640, 480)

        assertThat(ImageCompressor.rotateImage(original, 0)).isSameInstanceAs(original)
    }

    @Test
    fun `a quarter turn swaps width and height`() = runBlocking {
        val rotated = ImageCompressor.rotateImage(encoded(640, 480), 90)

        assertThat(ImageCompressor.getImageDimensions(rotated)).isEqualTo(480 to 640)
    }

    @Test
    fun `a half turn keeps the dimensions`() = runBlocking {
        val rotated = ImageCompressor.rotateImage(encoded(640, 480), 180)

        assertThat(ImageCompressor.getImageDimensions(rotated)).isEqualTo(640 to 480)
    }

    // ==================== Helpers ====================

    @Test
    fun `dimensions are read without decoding the pixels`() {
        assertThat(ImageCompressor.getImageDimensions(encoded(1920, 1080)))
            .isEqualTo(1920 to 1080)
    }

    @Test
    fun `transfer time scales with the payload at the assumed throughput`() {
        // 50 bytes per millisecond.
        assertThat(ImageCompressor.estimateTransferTimeMs(50_000)).isEqualTo(1_000)
        assertThat(ImageCompressor.estimateTransferTimeMs(0)).isEqualTo(0)
        assertThat(ImageCompressor.estimateTransferTimeMs(PhotoTransferConstants.MAX_COMPRESSED_SIZE))
            .isEqualTo(4_096)
    }
}
