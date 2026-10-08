package io.github.zero2005x.glassesaicompanion.service.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * Image payload preparation for vision requests.
 *
 * - Detects the real MIME type from magic bytes (never hardcodes image/jpeg).
 * - Validates size against a per-request limit.
 * - Downscales/recompresses oversized photos (e.g. from the Rokid glasses camera).
 */
object ImagePayloadHelper {

    const val DEFAULT_MAX_BYTES = 10 * 1024 * 1024
    private const val MAX_DIMENSION = 1568

    data class PreparedImage(
        val data: ByteArray,
        val mimeType: String
    )

    /** Detect image MIME type from magic bytes; null when unrecognized. */
    fun detectMimeType(data: ByteArray): String? {
        if (data.size < 4) return null
        return when {
            data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte() ->
                "image/jpeg"
            data[0] == 0x89.toByte() && data[1] == 0x50.toByte() &&
                data[2] == 0x4E.toByte() && data[3] == 0x47.toByte() ->
                "image/png"
            data[0] == 0x52.toByte() && data[1] == 0x49.toByte() &&
                data[2] == 0x46.toByte() && data[3] == 0x46.toByte() &&
                data.size > 11 && data[8] == 0x57.toByte() && data[9] == 0x45.toByte() &&
                data[10] == 0x42.toByte() && data[11] == 0x50.toByte() ->
                "image/webp"
            data[0] == 0x47.toByte() && data[1] == 0x49.toByte() && data[2] == 0x46.toByte() ->
                "image/gif"
            else -> null
        }
    }

    /**
     * Validate + (if needed) downscale an image for upload.
     *
     * @throws ProviderImageException when the data is not a supported image
     */
    fun prepare(
        imageData: ByteArray,
        maxBytes: Int = DEFAULT_MAX_BYTES
    ): PreparedImage {
        val mime = detectMimeType(imageData)
            ?: throw ProviderImageException("Unsupported image format (expected JPEG, PNG, WebP or GIF)")
        if (imageData.size <= maxBytes) {
            return PreparedImage(imageData, mime)
        }
        return PreparedImage(downscale(imageData, maxBytes), "image/jpeg")
    }

    /** Reduce dimensions until the recompressed JPEG fits [maxBytes]. */
    private fun downscale(imageData: ByteArray, maxBytes: Int): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
            ?: return imageData
        var width = bitmap.width
        var height = bitmap.height
        var current = bitmap
        var quality = 85
        repeat(6) {
            val out = ByteArrayOutputStream()
            current.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= maxBytes) return out.toByteArray()
            width = (width * 0.7f).toInt().coerceAtLeast(1)
            height = (height * 0.7f).toInt().coerceAtLeast(1)
            if (width > MAX_DIMENSION || height > MAX_DIMENSION || quality > 50) {
                quality = (quality - 10).coerceAtLeast(50)
            }
            val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
            if (current !== bitmap) current.recycle()
            current = scaled
        }
        val out = ByteArrayOutputStream()
        current.compress(Bitmap.CompressFormat.JPEG, 50, out)
        return out.toByteArray()
    }
}

class ProviderImageException(message: String) : Exception(message)
