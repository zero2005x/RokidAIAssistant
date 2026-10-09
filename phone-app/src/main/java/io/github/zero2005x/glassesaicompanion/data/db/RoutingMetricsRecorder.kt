package io.github.zero2005x.glassesaicompanion.data.db

import io.github.zero2005x.glassesaicompanion.data.ApiSettings
import io.github.zero2005x.glassesaicompanion.service.ai.DecisionRouter
import io.github.zero2005x.glassesaicompanion.service.ai.RoutedReply
import java.util.concurrent.TimeUnit

/** Writes one [RoutingMetricEntity] per question and keeps the table to [RETENTION_DAYS] days. */
class RoutingMetricsRecorder(
    private val dao: RoutingMetricDao,
    private val clock: () -> Long = System::currentTimeMillis
) {
    /**
     * @param receivedAt when the question arrived
     * @param answeredAt when the answer was sent; falls back to now
     * @param sentToGlasses whether the answer was pushed to the glasses (decides the page estimate)
     */
    suspend fun record(
        source: String,
        settings: ApiSettings,
        reply: RoutedReply,
        receivedAt: Long,
        answerChars: Int,
        sentToGlasses: Boolean,
        answeredAt: Long = clock()
    ) {
        val metric = toMetric(source, settings, reply, receivedAt, answeredAt, answerChars, sentToGlasses)
        dao.markAskedAgain(receivedAt, ASKED_AGAIN_WINDOW_MS)
        dao.insert(metric)
        pruneExpired()
    }

    suspend fun pruneExpired(): Int = dao.deleteOlderThan(clock() - TimeUnit.DAYS.toMillis(RETENTION_DAYS))

    companion object {
        const val RETENTION_DAYS = 60L
        const val ASKED_AGAIN_WINDOW_MS = 30_000L

        /** The glasses' own fallback budget per page; their measured layout can differ. */
        const val GLASSES_CHARS_PER_PAGE_ESTIMATE = 120

        fun toMetric(
            source: String,
            settings: ApiSettings,
            reply: RoutedReply,
            receivedAt: Long,
            answeredAt: Long,
            answerChars: Int,
            sentToGlasses: Boolean
        ) = RoutingMetricEntity(
            receivedAt = receivedAt,
            source = source,
            routingEnabled = settings.decisionRoutingEnabled,
            decisionBackend = settings.decisionBackend.name.takeIf { settings.decisionRoutingEnabled },
            decisionLatencyMs = reply.decisionMs,
            tier = reply.tier,
            reasonCode = reply.code.ifBlank { DecisionRouter.CODE_FAILED },
            provider = reply.provider.name,
            modelId = reply.modelId,
            totalMs = (answeredAt - receivedAt).coerceAtLeast(0),
            answerChars = answerChars,
            glassesPageCount = estimatePages(answerChars).takeIf { sentToGlasses && reply.error == null }
        )

        fun estimatePages(chars: Int): Int =
            ((chars + GLASSES_CHARS_PER_PAGE_ESTIMATE - 1) / GLASSES_CHARS_PER_PAGE_ESTIMATE).coerceAtLeast(1)
    }
}
