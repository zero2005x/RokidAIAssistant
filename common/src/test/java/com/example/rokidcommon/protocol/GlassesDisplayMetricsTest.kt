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
}
