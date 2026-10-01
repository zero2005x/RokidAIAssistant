package com.example.rokidcommon.protocol

import org.json.JSONObject

enum class GlassesFont { SYSTEM, MONOSPACE }

/** Percentage-based safe viewport, shared by the phone preview and glasses renderer. */
data class GlassesDisplayConfig(
    val fontSizeSp: Int = 22,
    val font: GlassesFont = GlassesFont.SYSTEM,
    val widthPercent: Int = 88,
    val heightPercent: Int = 78,
    val leftPercent: Int = 6,
    val topPercent: Int = 11
) {
    fun normalized(): GlassesDisplayConfig {
        val width = widthPercent.coerceIn(30, 94)
        val height = heightPercent.coerceIn(30, 94)
        return copy(
            fontSizeSp = fontSizeSp.coerceIn(12, 36),
            widthPercent = width,
            heightPercent = height,
            leftPercent = leftPercent.coerceIn(3, 97 - width),
            topPercent = topPercent.coerceIn(3, 97 - height)
        )
    }

    fun toJson(): String = JSONObject()
        .put("fontSizeSp", fontSizeSp)
        .put("font", font.name)
        .put("widthPercent", widthPercent)
        .put("heightPercent", heightPercent)
        .put("leftPercent", leftPercent)
        .put("topPercent", topPercent)
        .toString()

    companion object {
        fun fromJson(raw: String?): GlassesDisplayConfig? = runCatching {
            if (raw.isNullOrBlank()) return@runCatching null
            val obj = JSONObject(raw)
            GlassesDisplayConfig(
                fontSizeSp = obj.getInt("fontSizeSp"),
                font = GlassesFont.valueOf(obj.getString("font")),
                widthPercent = obj.getInt("widthPercent"),
                heightPercent = obj.getInt("heightPercent"),
                leftPercent = obj.getInt("leftPercent"),
                topPercent = obj.getInt("topPercent")
            ).normalized()
        }.getOrNull()
    }
}
