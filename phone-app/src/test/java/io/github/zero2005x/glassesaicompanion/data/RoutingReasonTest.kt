package io.github.zero2005x.glassesaicompanion.data

import android.content.res.Configuration
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class RoutingReasonTest {
    @Test
    fun samePersistedCodeIsTranslatedAtDisplayTime() {
        val base = RuntimeEnvironment.getApplication()
        val raw = RoutingReason.encode("selected", "JEV", "quality", 82)
        val english = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.US) })
        val chinese = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.TAIWAN) })
        val en = RoutingReason.display(english, raw)
        val zh = RoutingReason.display(chinese, raw)
        assertNotEquals(en, zh)
        assertTrue(en.contains("82"))
        assertTrue(zh.contains("高品質"))
    }
}
