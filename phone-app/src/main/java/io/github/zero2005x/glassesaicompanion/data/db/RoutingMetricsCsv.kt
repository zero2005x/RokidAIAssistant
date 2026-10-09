package io.github.zero2005x.glassesaicompanion.data.db

import java.time.Instant

/** The CSV the user exports from Settings. Empty cells mean "not recorded". */
object RoutingMetricsCsv {
    val COLUMNS = listOf(
        "time", "source", "routing_enabled", "decision_backend", "decision_latency_ms", "tier",
        "reason_code", "provider", "model_id", "total_ms", "answer_chars", "glasses_page_count",
        "asked_again_within_30s", "time_to_first_text_ms"
    )

    fun build(rows: List<RoutingMetricEntity>): String = buildString {
        append(COLUMNS.joinToString(",")).append("\r\n")
        rows.forEach { append(line(it)).append("\r\n") }
    }

    fun line(m: RoutingMetricEntity): String = listOf(
        Instant.ofEpochMilli(m.receivedAt).toString(), m.source, m.routingEnabled,
        m.decisionBackend, m.decisionLatencyMs, m.tier, m.reasonCode, m.provider, m.modelId,
        m.totalMs, m.answerChars, m.glassesPageCount, m.askedAgainWithin30s, m.timeToFirstTextMs
    ).joinToString(",") { escape(it?.toString().orEmpty()) }

    /** RFC 4180 quoting, plus a leading quote on cells a spreadsheet would read as a formula. */
    private fun escape(value: String): String {
        val safe = if (value.firstOrNull() in FORMULA_STARTS) "'$value" else value
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + safe.replace("\"", "\"\"") + "\""
        } else safe
    }

    private val FORMULA_STARTS = setOf('=', '+', '@', '\t')
}
