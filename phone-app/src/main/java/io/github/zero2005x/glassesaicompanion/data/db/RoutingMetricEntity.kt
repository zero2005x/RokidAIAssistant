package io.github.zero2005x.glassesaicompanion.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One row per answered question, kept only on this phone to judge decision routing on real use.
 * Nothing in it leaves the device except through the user's own CSV export.
 */
@Entity(tableName = "routing_metrics", indices = [Index("received_at")])
data class RoutingMetricEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** When the question was received. */
    @ColumnInfo(name = "received_at")
    val receivedAt: Long,

    /** One of [RoutingMetricSource]. */
    @ColumnInfo(name = "source")
    val source: String,

    @ColumnInfo(name = "routing_enabled")
    val routingEnabled: Boolean,

    /** Null while routing is off. */
    @ColumnInfo(name = "decision_backend")
    val decisionBackend: String?,

    @ColumnInfo(name = "decision_latency_ms")
    val decisionLatencyMs: Long?,

    /** Tier the decision backend chose; null when it gave no usable decision. */
    @ColumnInfo(name = "tier")
    val tier: String?,

    /** `selected`, `selected_llm`, `uncertain`, `empty_slot`, `unconfigured_slot`, `fallback`, `failed` or `disabled`. */
    @ColumnInfo(name = "reason_code")
    val reasonCode: String,

    @ColumnInfo(name = "provider")
    val provider: String,

    @ColumnInfo(name = "model_id")
    val modelId: String,

    /** Question received to answer sent. */
    @ColumnInfo(name = "total_ms")
    val totalMs: Long,

    @ColumnInfo(name = "answer_chars")
    val answerChars: Int,

    /** An estimate: the glasses lay the text out themselves. Null when nothing was sent to them. */
    @ColumnInfo(name = "glasses_page_count")
    val glassesPageCount: Int?,

    /** A new question arrived within 30 s of this answer; a hint, not ground truth. */
    @ColumnInfo(name = "asked_again_within_30s", defaultValue = "0")
    val askedAgainWithin30s: Boolean = false,

    /** Filled in by the streaming work; always null until then. */
    @ColumnInfo(name = "time_to_first_text_ms")
    val timeToFirstTextMs: Long? = null
)

object RoutingMetricSource {
    const val GLASSES_VOICE = "glasses_voice"
    const val PHONE_CHAT = "phone_chat"
    const val RECORDING = "recording"
}

@Dao
interface RoutingMetricDao {
    @Insert
    suspend fun insert(metric: RoutingMetricEntity): Long

    /** Marks earlier answers that were still fresh (answered within [windowMs]) when a new question arrived. */
    @Query(
        "UPDATE routing_metrics SET asked_again_within_30s = 1 " +
            "WHERE received_at < :newReceivedAt AND received_at + total_ms >= :newReceivedAt - :windowMs"
    )
    suspend fun markAskedAgain(newReceivedAt: Long, windowMs: Long): Int

    @Query("DELETE FROM routing_metrics WHERE received_at < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("SELECT * FROM routing_metrics ORDER BY received_at ASC, id ASC")
    suspend fun getAll(): List<RoutingMetricEntity>
}
