package io.github.zero2005x.glassesaicompanion.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.zero2005x.glassesaicompanion.data.AiProvider
import io.github.zero2005x.glassesaicompanion.data.ApiSettings
import io.github.zero2005x.glassesaicompanion.data.DecisionBackend
import io.github.zero2005x.glassesaicompanion.service.ai.RoutedReply
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class RoutingMetricsTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: RoutingMetricDao
    private var now = 10_000_000_000L
    private lateinit var recorder: RoutingMetricsRecorder

    private val routed = ApiSettings(decisionRoutingEnabled = true, decisionBackend = DecisionBackend.GEMINI)
    private val unrouted = ApiSettings(decisionRoutingEnabled = false)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.routingMetricDao()
        recorder = RoutingMetricsRecorder(dao) { now }
    }

    @After
    fun tearDown() = database.close()

    private fun reply(
        code: String = "selected_llm",
        tier: String? = "fast",
        decisionMs: Long? = 420,
        error: String? = null
    ) = RoutedReply("answer", AiProvider.GEMINI, "gemini-x", "{}", error, code, tier, decisionMs)

    // --- row mapping ---

    @Test
    fun routedAnswerMapsEveryColumn() {
        val metric = RoutingMetricsRecorder.toMetric(
            RoutingMetricSource.GLASSES_VOICE, routed, reply(), receivedAt = 1_000, answeredAt = 3_500,
            answerChars = 250, sentToGlasses = true
        )

        assertThat(metric.receivedAt).isEqualTo(1_000)
        assertThat(metric.source).isEqualTo("glasses_voice")
        assertThat(metric.routingEnabled).isTrue()
        assertThat(metric.decisionBackend).isEqualTo("GEMINI")
        assertThat(metric.decisionLatencyMs).isEqualTo(420)
        assertThat(metric.tier).isEqualTo("fast")
        assertThat(metric.reasonCode).isEqualTo("selected_llm")
        assertThat(metric.provider).isEqualTo("GEMINI")
        assertThat(metric.modelId).isEqualTo("gemini-x")
        assertThat(metric.totalMs).isEqualTo(2_500)
        assertThat(metric.answerChars).isEqualTo(250)
        assertThat(metric.glassesPageCount).isEqualTo(3) // 250 chars at 120 per page
        assertThat(metric.askedAgainWithin30s).isFalse()
        assertThat(metric.timeToFirstTextMs).isNull()
    }

    @Test
    fun routingOffLeavesBackendAndTierEmpty() {
        val metric = RoutingMetricsRecorder.toMetric(
            RoutingMetricSource.PHONE_CHAT, unrouted, reply("disabled", tier = null, decisionMs = null),
            1_000, 2_000, 10, sentToGlasses = false
        )

        assertThat(metric.routingEnabled).isFalse()
        assertThat(metric.decisionBackend).isNull()
        assertThat(metric.decisionLatencyMs).isNull()
        assertThat(metric.tier).isNull()
        assertThat(metric.reasonCode).isEqualTo("disabled")
        assertThat(metric.glassesPageCount).isNull()
    }

    @Test
    fun failedAnswerHasNoPageCountEvenWhenGlassesAreOn() {
        val metric = RoutingMetricsRecorder.toMetric(
            RoutingMetricSource.RECORDING, routed, reply("failed", error = "boom"), 1_000, 2_000, 0, true
        )

        assertThat(metric.reasonCode).isEqualTo("failed")
        assertThat(metric.glassesPageCount).isNull()
    }

    @Test
    fun pageEstimateRoundsUpAndNeverDropsBelowOne() {
        assertThat(RoutingMetricsRecorder.estimatePages(0)).isEqualTo(1)
        assertThat(RoutingMetricsRecorder.estimatePages(120)).isEqualTo(1)
        assertThat(RoutingMetricsRecorder.estimatePages(121)).isEqualTo(2)
    }

    // --- recording, asked-again hint ---

    @Test
    fun recordInsertsOneRow() = runBlocking<Unit> {
        recorder.record(RoutingMetricSource.PHONE_CHAT, routed, reply(), now - 2_000, 40, false)

        val rows = dao.getAll()
        assertThat(rows).hasSize(1)
        assertThat(rows.single().totalMs).isEqualTo(2_000) // answeredAt defaults to the injected clock
    }

    @Test
    fun aNewQuestionWithin30SecondsMarksThePreviousAnswer() = runBlocking<Unit> {
        recorder.record(RoutingMetricSource.PHONE_CHAT, routed, reply(), receivedAt = now, answerChars = 10,
            sentToGlasses = false, answeredAt = now + 4_000)
        recorder.record(RoutingMetricSource.PHONE_CHAT, routed, reply(), receivedAt = now + 20_000, answerChars = 10,
            sentToGlasses = false, answeredAt = now + 22_000)

        val rows = dao.getAll()
        assertThat(rows[0].askedAgainWithin30s).isTrue()
        assertThat(rows[1].askedAgainWithin30s).isFalse()
    }

    @Test
    fun aQuestionAfter30SecondsDoesNotMarkThePreviousAnswer() = runBlocking<Unit> {
        recorder.record(RoutingMetricSource.PHONE_CHAT, routed, reply(), receivedAt = now, answerChars = 10,
            sentToGlasses = false, answeredAt = now + 4_000)
        // Answer sent at now + 4 s; the next question lands 34.001 s later.
        recorder.record(RoutingMetricSource.PHONE_CHAT, routed, reply(), receivedAt = now + 34_001, answerChars = 10,
            sentToGlasses = false, answeredAt = now + 36_000)

        assertThat(dao.getAll().map { it.askedAgainWithin30s }).containsExactly(false, false)
    }

    // --- retention ---

    @Test
    fun rowsOlderThan60DaysAreDeleted() = runBlocking<Unit> {
        val day = TimeUnit.DAYS.toMillis(1)
        for (ageDays in listOf(61L, 60L, 1L)) {
            dao.insert(RoutingMetricsRecorder.toMetric(
                RoutingMetricSource.PHONE_CHAT, routed, reply(), now - ageDays * day, now - ageDays * day + 100,
                5, false
            ))
        }

        val deleted = recorder.pruneExpired()

        assertThat(deleted).isEqualTo(1)
        assertThat(dao.getAll().map { (now - it.receivedAt) / day }).containsExactly(60L, 1L).inOrder()
    }

    @Test
    fun recordingPrunesAsItGoes() = runBlocking<Unit> {
        val old = now - TimeUnit.DAYS.toMillis(90)
        dao.insert(RoutingMetricsRecorder.toMetric(RoutingMetricSource.PHONE_CHAT, routed, reply(), old, old + 1, 5, false))

        recorder.record(RoutingMetricSource.PHONE_CHAT, routed, reply(), now - 1_000, 5, false)

        assertThat(dao.getAll()).hasSize(1)
    }

    // --- CSV ---

    @Test
    fun csvHasTheDocumentedColumnsInOrder() {
        assertThat(RoutingMetricsCsv.build(emptyList()).trimEnd())
            .isEqualTo(
                "time,source,routing_enabled,decision_backend,decision_latency_ms,tier,reason_code," +
                    "provider,model_id,total_ms,answer_chars,glasses_page_count,asked_again_within_30s," +
                    "time_to_first_text_ms"
            )
    }

    @Test
    fun csvRowMatchesTheColumnsAndLeavesUnrecordedCellsEmpty() {
        val metric = RoutingMetricsRecorder.toMetric(
            RoutingMetricSource.RECORDING, unrouted, reply("disabled", null, null), 0, 1_500, 90, false
        )

        val csv = RoutingMetricsCsv.build(listOf(metric)).lines()
        val cells = csv[1].split(",")

        assertThat(cells).hasSize(RoutingMetricsCsv.COLUMNS.size)
        assertThat(cells[0]).isEqualTo("1970-01-01T00:00:00Z")
        assertThat(cells.take(10)).containsExactly(
            "1970-01-01T00:00:00Z", "recording", "false", "", "", "", "disabled", "GEMINI", "gemini-x", "1500"
        ).inOrder()
        assertThat(cells.drop(10)).containsExactly("90", "", "false", "").inOrder()
    }

    @Test
    fun csvQuotesCommasAndNeutralisesFormulaCells() {
        val metric = RoutingMetricsRecorder.toMetric(
            RoutingMetricSource.PHONE_CHAT, routed,
            RoutedReply("a", AiProvider.GEMINI, "=cmd,\"x\"", "{}", null, "selected_llm", "fast", 1), 0, 1, 1, false
        )

        assertThat(RoutingMetricsCsv.line(metric)).contains("\"'=cmd,\"\"x\"\"\"")
    }

    // --- schema ---

    @Test
    fun migration3To4CreatesTheTableRoomExpects() {
        // Opens the freshly built (version 4) in-memory schema through the DAO, and checks the
        // migration SQL produces the same columns.
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(
                ApplicationProvider.getApplicationContext<Context>()
            ).callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, o: Int, n: Int) = Unit
            }).build()
        )
        val db = helper.writableDatabase
        AppDatabase.MIGRATION_3_4.migrate(db)

        fun columns(db: androidx.sqlite.db.SupportSQLiteDatabase) =
            db.query("PRAGMA table_info(routing_metrics)").use { c ->
                generateSequence { if (c.moveToNext()) c.getString(c.getColumnIndexOrThrow("name")) else null }.toList()
            }
        val expected = database.openHelper.writableDatabase.let(::columns)
        assertThat(columns(db)).containsExactlyElementsIn(expected).inOrder()
        helper.close()
    }
}
