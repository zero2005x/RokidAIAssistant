package com.example.rokidglasses.service.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Verifies [GlassesCameraManager.normalizeOrientation] applies the EXIF rotation
 * to the pixel data so that downstream consumers receive an upright bitmap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class GlassesCameraManagerOrientationTest {

    private fun newManager(): GlassesCameraManager {
        val context: Context = mockk(relaxed = true)
        return GlassesCameraManager(context)
    }

    @Test
    fun `normalizeOrientation - EXIF ROTATE_90 produces bytes whose decoded bitmap has swapped dimensions`() = runBlocking {
        val manager = newManager()

        // Build a 200x100 landscape JPEG with EXIF orientation = ROTATE_90.
        val original = createJpegWithExifOrientation(
            width = 200,
            height = 100,
            orientation = ExifInterface.ORIENTATION_ROTATE_90
        )

        // Sanity check: the original bytes still decode to 200x100 (EXIF doesn't move pixels).
        val originalOpts = BitmapFactory.Options()
        BitmapFactory.decodeByteArray(original, 0, original.size, originalOpts)
        assertThat(originalOpts.outWidth).isEqualTo(200)
        assertThat(originalOpts.outHeight).isEqualTo(100)

        val rotated = manager.normalizeOrientation(original)

        val rotatedOpts = BitmapFactory.Options()
        BitmapFactory.decodeByteArray(rotated, 0, rotated.size, rotatedOpts)
        // After rotation the width and height should be swapped relative to the input.
        assertThat(rotatedOpts.outWidth).isEqualTo(100)
        assertThat(rotatedOpts.outHeight).isEqualTo(200)
    }

    @Test
    fun `normalizeOrientation - EXIF ORIENTATION_NORMAL returns input bytes unchanged`() = runBlocking {
        val manager = newManager()

        val original = createJpegWithExifOrientation(
            width = 80,
            height = 40,
            orientation = ExifInterface.ORIENTATION_NORMAL
        )

        val result = manager.normalizeOrientation(original)

        // Same reference returned (no rotation work performed).
        assertThat(result).isSameInstanceAs(original)
    }

    /** Encode a solid-color bitmap to JPEG and stamp an EXIF orientation tag onto it. */
    private fun createJpegWithExifOrientation(
        width: Int,
        height: Int,
        orientation: Int
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos)
        bitmap.recycle()

        val tempDir = File(System.getProperty("java.io.tmpdir"), "rokid-orient-test").apply { mkdirs() }
        val file = File.createTempFile("orient-", ".jpg", tempDir)
        file.writeBytes(baos.toByteArray())

        val exif = ExifInterface(file.absolutePath)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
        exif.saveAttributes()

        val bytes = file.readBytes()
        file.delete()
        return bytes
    }
}
