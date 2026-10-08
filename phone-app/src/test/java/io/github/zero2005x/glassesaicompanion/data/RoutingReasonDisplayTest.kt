package io.github.zero2005x.glassesaicompanion.data

import android.content.Context
import io.github.zero2005x.glassesaicompanion.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Every stored routing reason code, and the provider failures, as the user sees them. */
@RunWith(RobolectricTestRunner::class)
class RoutingReasonDisplayTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    @Test
    fun everyCodeIsRenderedFromItsOwnString() {
        val fast = string(R.string.routing_fast)
        val balanced = string(R.string.routing_balanced)
        val quality = string(R.string.routing_quality)

        val cases = mapOf(
            RoutingReason.encode("disabled") to string(R.string.routing_reason_disabled),
            RoutingReason.encode("uncertain") to string(R.string.routing_reason_uncertain),
            RoutingReason.encode("empty_slot", tier = "fast") to
                string(R.string.routing_reason_empty_slot, fast),
            RoutingReason.encode("unconfigured_slot", tier = "balanced") to
                string(R.string.routing_reason_unconfigured_slot, balanced),
            RoutingReason.encode("selected_llm", "GEMINI", "quality") to
                string(R.string.routing_reason_selected_llm, "GEMINI", quality),
            RoutingReason.encode("selected", "JEV", "fast", 82) to
                string(R.string.routing_reason_selected, "JEV", fast, 82),
            RoutingReason.encode("fallback", model = "ANTHROPIC / claude-sonnet-5-5") to
                string(R.string.routing_reason_fallback, "ANTHROPIC / claude-sonnet-5-5"),
            RoutingReason.encode("failed") to string(R.string.routing_reason_failed),
            RoutingReason.encode("not-a-known-code") to string(R.string.routing_reason_failed)
        )

        for ((stored, expected) in cases) {
            assertEquals(stored, expected, RoutingReason.display(context, stored))
        }
    }

    @Test
    fun anUnknownOrBlankTierIsShownAsQuality() {
        val unknown = RoutingReason.display(context, RoutingReason.encode("empty_slot", tier = "mystery"))
        val blank = RoutingReason.display(context, RoutingReason.encode("empty_slot"))
        val quality = string(R.string.routing_reason_empty_slot, string(R.string.routing_quality))

        assertEquals(quality, unknown)
        assertEquals(quality, blank)
        assertNotEquals(quality,
            RoutingReason.display(context, RoutingReason.encode("empty_slot", tier = "fast")))
    }

    @Test
    fun reasonsStoredByEarlierBuildsAreShownVerbatim() {
        assertEquals("Simple question, used the fast model",
            RoutingReason.display(context, "Simple question, used the fast model"))
        // Valid JSON from a different schema version is not interpreted either.
        val future = """{"routingVersion":2,"code":"selected","tier":"fast"}"""
        assertEquals(future, RoutingReason.display(context, future))
        assertEquals("""{"code":"selected"}""", RoutingReason.display(context, """{"code":"selected"}"""))
    }

    @Test
    fun providerFailuresKeepTheirReasonAndTranslateTheKnownOnes() {
        assertEquals(
            string(R.string.routing_error, string(R.string.api_key_not_configured)),
            RoutingReason.failure(context, "not_configured")
        )
        assertEquals(
            string(R.string.routing_error, string(R.string.stt_error_service_unavailable)),
            RoutingReason.failure(context, "empty_response")
        )
        val raw = RoutingReason.failure(context, "Invalid API key (HTTP 401)")
        assertEquals(string(R.string.routing_error, "Invalid API key (HTTP 401)"), raw)
        assertTrue(raw.contains("HTTP 401"))
    }
}
