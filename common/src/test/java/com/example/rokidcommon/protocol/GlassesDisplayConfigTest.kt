package com.example.rokidcommon.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GlassesDisplayConfigTest {
    @Test
    fun unsafeViewportIsClampedAndRoundTrips() {
        val config = GlassesDisplayConfig(
            fontSizeSp = 80,
            font = GlassesFont.MONOSPACE,
            widthPercent = 150,
            heightPercent = 94,
            leftPercent = 70,
            topPercent = 90
        ).normalized()
        assertEquals(36, config.fontSizeSp)
        assertEquals(94, config.widthPercent)
        assertEquals(3, config.leftPercent)
        assertEquals(3, config.topPercent)
        assertEquals(config, GlassesDisplayConfig.fromJson(config.toJson()))
    }

    @Test
    fun malformedConfigIsIgnored() {
        assertNull(GlassesDisplayConfig.fromJson("{not-json}"))
    }

    @Test
    fun missingOrIncompleteConfigIsIgnored() {
        assertNull(GlassesDisplayConfig.fromJson(null))
        assertNull(GlassesDisplayConfig.fromJson(""))
        assertNull(GlassesDisplayConfig.fromJson("   "))
        assertNull(GlassesDisplayConfig.fromJson("{}"))
        // A font name this build does not know about.
        assertNull(GlassesDisplayConfig.fromJson(
            """{"fontSizeSp":22,"font":"COMIC","widthPercent":88,"heightPercent":78,"leftPercent":6,"topPercent":11}"""))
    }

    @Test
    fun theDefaultsAreAComfortableCentredViewport() {
        val defaults = GlassesDisplayConfig()

        assertEquals(22, defaults.fontSizeSp)
        assertEquals(GlassesFont.SYSTEM, defaults.font)
        assertEquals(88, defaults.widthPercent)
        assertEquals(78, defaults.heightPercent)
        // Already inside the safe area, so normalising changes nothing.
        assertEquals(defaults, defaults.normalized())
    }
}
