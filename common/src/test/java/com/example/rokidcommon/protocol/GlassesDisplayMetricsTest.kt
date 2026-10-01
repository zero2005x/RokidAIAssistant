package com.example.rokidcommon.protocol

import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class GlassesDisplayMetricsTest {
    @Test fun metricsRoundTripPreservesDensityAndFontScale() {
        val metrics = GlassesDisplayMetrics(480, 640, 1.5f, 1.2f)
        assertEquals(metrics, GlassesDisplayMetrics.fromJson(metrics.toJson()))
    }
    @Test fun untrustedMetricsCannotProduceInvalidLayout() {
        for (bad in listOf("0", "-1", "999999", "1.5")) {
            assertNull(GlassesDisplayMetrics.fromJson("""{"widthPx":$bad,"heightPx":640,"density":1,"fontScale":1}"""))
        }
        assertNull(GlassesDisplayMetrics.fromJson("""{"widthPx":480,"heightPx":640,"density":0,"fontScale":1}"""))
        assertNull(GlassesDisplayMetrics.fromJson("{}"))
    }

    private fun json(width: String = "480", height: String = "640", density: String = "1", fontScale: String = "1") =
        """{"widthPx":$width,"heightPx":$height,"density":$density,"fontScale":$fontScale}"""

    @Test fun everyFieldIsRangeCheckedOnItsOwn() {
        for (bad in listOf("0", "-1", "999999", "640.5")) {
            assertNull("height $bad", GlassesDisplayMetrics.fromJson(json(height = bad)))
        }
        for (bad in listOf("0.05", "11")) {
            assertNull("density $bad", GlassesDisplayMetrics.fromJson(json(density = bad)))
            assertNull("fontScale $bad", GlassesDisplayMetrics.fromJson(json(fontScale = bad)))
        }
    }

    @Test fun theLimitsThemselvesAreAccepted() {
        assertEquals(GlassesDisplayMetrics(1, 1, 0.1f, 0.1f),
            GlassesDisplayMetrics.fromJson(json("1", "1", "0.1", "0.1")))
        assertEquals(GlassesDisplayMetrics(16384, 16384, 10f, 10f),
            GlassesDisplayMetrics.fromJson(json("16384", "16384", "10", "10")))
    }

    @Test fun missingOrUnreadableInputIsIgnored() {
        assertNull(GlassesDisplayMetrics.fromJson(null))
        assertNull(GlassesDisplayMetrics.fromJson(""))
        assertNull(GlassesDisplayMetrics.fromJson("{not json"))
        assertNull(GlassesDisplayMetrics.fromJson("""{"widthPx":"wide","heightPx":640,"density":1,"fontScale":1}"""))
    }
}
