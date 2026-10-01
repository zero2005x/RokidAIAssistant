package com.example.rokidcommon.protocol

import org.json.JSONObject

/** Application viewport metrics, not the optical field of view of the glasses. */
data class GlassesDisplayMetrics(val widthPx: Int, val heightPx: Int, val density: Float, val fontScale: Float) {
    fun toJson(): String = JSONObject().put("widthPx", widthPx).put("heightPx", heightPx)
        .put("density", density).put("fontScale", fontScale).put("cameraTransport", "spp").toString()

    companion object {
        fun fromJson(raw: String?): GlassesDisplayMetrics? = runCatching {
            val obj = JSONObject(raw ?: return null)
            val width = obj.getDouble("widthPx")
            val height = obj.getDouble("heightPx")
            val density = obj.getDouble("density")
            val fontScale = obj.getDouble("fontScale")
            if (width !in 1.0..16384.0 || height !in 1.0..16384.0 || width % 1 != 0.0 || height % 1 != 0.0 ||
                density !in 0.1..10.0 || fontScale !in 0.1..10.0) return null
            GlassesDisplayMetrics(width.toInt(), height.toInt(), density.toFloat(), fontScale.toFloat())
        }.getOrNull()
    }
}

object GlassesDisplayLayout {
    const val BASE_FONT_SP = 22
    const val LINE_HEIGHT_SP = 30
    const val PADDING_DP = 6
    const val HEADER_FRACTION = 0.25f
    const val HINT_FRACTION = 0.20f
}
