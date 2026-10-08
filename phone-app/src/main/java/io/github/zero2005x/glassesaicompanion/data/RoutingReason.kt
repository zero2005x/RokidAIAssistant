package io.github.zero2005x.glassesaicompanion.data

import android.content.Context
import io.github.zero2005x.glassesaicompanion.R
import org.json.JSONObject

/** Stable metadata; translated only when displayed, so changing app language also updates old replies. */
object RoutingReason {
    fun failure(context: Context, raw: String): String {
        val detail = when (raw) {
            "not_configured" -> context.getString(R.string.api_key_not_configured)
            "empty_response" -> context.getString(R.string.stt_error_service_unavailable)
            else -> raw
        }
        return context.getString(R.string.routing_error, detail)
    }
    fun encode(code: String, backend: String = "", tier: String = "", confidence: Int = 0, model: String = ""): String =
        JSONObject().put("routingVersion", 1).put("code", code).put("backend", backend)
            .put("tier", tier).put("confidence", confidence).put("model", model).toString()

    fun display(context: Context, raw: String): String {
        val value = runCatching { JSONObject(raw) }.getOrNull() ?: return raw // Earlier builds stored literal reasons.
        if (value.optInt("routingVersion") != 1) return raw
        val tier = context.getString(when (value.optString("tier")) {
            "fast" -> R.string.routing_fast
            "balanced" -> R.string.routing_balanced
            else -> R.string.routing_quality
        })
        return when (value.optString("code")) {
            "disabled" -> context.getString(R.string.routing_reason_disabled)
            "uncertain" -> context.getString(R.string.routing_reason_uncertain)
            "empty_slot" -> context.getString(R.string.routing_reason_empty_slot, tier)
            "unconfigured_slot" -> context.getString(R.string.routing_reason_unconfigured_slot, tier)
            "selected_llm" -> context.getString(R.string.routing_reason_selected_llm, value.optString("backend"), tier)
            "selected" -> context.getString(R.string.routing_reason_selected,
                value.optString("backend"), tier, value.optInt("confidence"))
            "fallback" -> context.getString(R.string.routing_reason_fallback, value.optString("model"))
            else -> context.getString(R.string.routing_reason_failed)
        }
    }
}
