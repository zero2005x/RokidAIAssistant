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
}
