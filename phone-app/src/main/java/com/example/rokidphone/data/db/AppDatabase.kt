package com.example.rokidphone.data.db

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * App Database
 * Uses Room for conversation history and recording persistence
 */
@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        RecordingEntity::class
    ],
    version = 3,
    exportSchema = true
)
@TypeConverters(Converters::class, RecordingConverters::class)
abstract class AppDatabase : RoomDatabase() {
    
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun recordingDao(): RecordingDao
    
    companion object {
        const val DATABASE_NAME = "rokid_ai_database"
        
        @Volatile
        private var instance: AppDatabase? = null
        
        fun getInstance(context: android.content.Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: androidx.room.Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `recordings` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `file_path` TEXT NOT NULL, `source` TEXT NOT NULL, `status` TEXT NOT NULL, `duration_ms` INTEGER NOT NULL, `file_size_bytes` INTEGER NOT NULL, `sample_rate` INTEGER NOT NULL, `channels` INTEGER NOT NULL, `transcript` TEXT, `ai_response` TEXT, `provider_id` TEXT, `model_id` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `transcribed_at` INTEGER, `analyzed_at` INTEGER, `error_message` TEXT, `is_favorite` INTEGER NOT NULL, `notes` TEXT, PRIMARY KEY(`id`))"""
                )
            }
        }

        /**
         * Gives `messages` an explicit position so a conversation can be ordered
         * without relying on created_at, which ties whenever two messages are written
         * in the same millisecond.
         *
         * Existing rows are backfilled with the order the app was already showing
         * them in (created_at, then id), so no conversation appears to reshuffle on
         * upgrade; only future ties are fixed.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `seq` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    UPDATE `messages` SET `seq` = (
                        SELECT COUNT(*) FROM `messages` AS older
                        WHERE older.`conversation_id` = `messages`.`conversation_id`
                          AND (older.`created_at` < `messages`.`created_at`
                               OR (older.`created_at` = `messages`.`created_at`
                                   AND older.`id` <= `messages`.`id`))
                    )
                    """.trimIndent()
                )
            }
        }
    }
}

/**
 * Type Converters
 */
class Converters {
    @TypeConverter
    fun fromTimestamp(value: Long?): java.util.Date? = value?.let { java.util.Date(it) }

    @TypeConverter
    fun dateToTimestamp(date: java.util.Date?): Long? = date?.time

    // JSON serialization: a comma join is lossy for elements containing ',' or blanks.
    @TypeConverter
    fun fromStringList(value: String?): List<String>? {
        if (value == null) return null
        return try {
            val array = org.json.JSONArray(value)
            List(array.length()) { i -> array.getString(i) }
        } catch (e: org.json.JSONException) {
            android.util.Log.w("Converters", "Failed to parse string list, falling back to legacy comma split", e)
            value.split(",").filter { it.isNotBlank() }
        }
    }

    @TypeConverter
    fun toStringList(list: List<String>?): String? {
        return list?.let { org.json.JSONArray(it).toString() }
    }
}

/**
 * Conversation Entity
 */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey
    val id: String,
    
    @ColumnInfo(name = "title")
    val title: String,
    
    @ColumnInfo(name = "provider_id")
    val providerId: String,
    
    @ColumnInfo(name = "model_id")
    val modelId: String,
    
    @ColumnInfo(name = "system_prompt")
    val systemPrompt: String = "",
    
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
    
    @ColumnInfo(name = "message_count")
    val messageCount: Int = 0,
    
    @ColumnInfo(name = "is_archived")
    val isArchived: Boolean = false,
    
    @ColumnInfo(name = "is_pinned")
    val isPinned: Boolean = false,
    
    @ColumnInfo(name = "metadata")
    val metadata: String? = null
)

/**
 * Message Entity
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversation_id")]
)
data class MessageEntity(
    @PrimaryKey
    val id: String,
    
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    
    @ColumnInfo(name = "role")
    val role: String,  // "user", "assistant", "system"
    
    @ColumnInfo(name = "content")
    val content: String,
    
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    /**
     * Position of this message within its conversation, starting at 1.
     *
     * created_at alone cannot order messages: a question and a fast reply routinely
     * land in the same millisecond, and the previous tiebreaker on the primary key
     * ordered those by a random UUID, so the reply could render above the question.
     * The repository assigns this inside the insert transaction.
     */
    // The default is declared so the column created by MIGRATION_2_3, which needs one
    // for ALTER TABLE ADD COLUMN NOT NULL, matches the schema Room expects.
    @ColumnInfo(name = "seq", defaultValue = "0")
    val seq: Long = 0,

    @ColumnInfo(name = "token_count")
    val tokenCount: Int? = null,
    
    @ColumnInfo(name = "model_id")
    val modelId: String? = null,
    
    @ColumnInfo(name = "has_image")
    val hasImage: Boolean = false,
    
    @ColumnInfo(name = "image_path")
    val imagePath: String? = null,
    
    @ColumnInfo(name = "finish_reason")
    val finishReason: String? = null,
    
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,
    
    @ColumnInfo(name = "metadata")
    val metadata: String? = null
)

/**
 * Conversation DAO
 */
@Dao
interface ConversationDao {
    
    @Query("SELECT * FROM conversations WHERE is_archived = 0 ORDER BY is_pinned DESC, updated_at DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>
    
    @Query("SELECT * FROM conversations WHERE is_archived = 0 ORDER BY is_pinned DESC, updated_at DESC")
    suspend fun getAllConversationsSync(): List<ConversationEntity>
    
    @Query("SELECT * FROM conversations WHERE is_archived = 1 ORDER BY updated_at DESC")
    fun getArchivedConversations(): Flow<List<ConversationEntity>>
    
    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversationById(id: String): ConversationEntity?
    
    @Query("SELECT * FROM conversations WHERE id = :id")
    fun getConversationByIdFlow(id: String): Flow<ConversationEntity?>
    
    // Caller must escape '%', '_' and '\' in `query` before calling.
    // The escape character must be written as "\\": in a Kotlin string "\'" is just an
    // apostrophe, which would emit `ESCAPE ''` and make SQLite reject every search.
    @Query("SELECT * FROM conversations WHERE title LIKE '%' || :query || '%' ESCAPE '\\' ORDER BY updated_at DESC")
    fun searchConversations(query: String): Flow<List<ConversationEntity>>
    
    @Upsert
    suspend fun insertConversation(conversation: ConversationEntity)
    
    @Update
    suspend fun updateConversation(conversation: ConversationEntity)
    
    @Delete
    suspend fun deleteConversation(conversation: ConversationEntity)
    
    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversationById(id: String)
    
    @Query("UPDATE conversations SET is_archived = :archived, updated_at = :updatedAt WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean, updatedAt: Long = System.currentTimeMillis())
    
    @Query("UPDATE conversations SET is_pinned = :pinned, updated_at = :updatedAt WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean, updatedAt: Long = System.currentTimeMillis())
    
    @Query("UPDATE conversations SET title = :title, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, updatedAt: Long = System.currentTimeMillis())
    
    @Query("UPDATE conversations SET message_count = message_count + 1, updated_at = :updatedAt WHERE id = :id")
    suspend fun incrementMessageCount(id: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE conversations SET message_count = MAX(message_count - 1, 0), updated_at = :updatedAt WHERE id = :id")
    suspend fun decrementMessageCount(id: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE conversations SET message_count = 0, updated_at = :updatedAt WHERE id = :id")
    suspend fun resetMessageCount(id: String, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM conversations WHERE is_archived = 0")
    suspend fun getActiveConversationCount(): Int
    
    @Query("DELETE FROM conversations WHERE is_archived = 1")
    suspend fun deleteAllArchived()
}

/**
 * Message DAO
 */
@Dao
interface MessageDao {
    
    // Ordered by seq, which the repository assigns per conversation on insert.
    // created_at is not an ordering key: same-millisecond inserts are routine.
    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY seq ASC")
    fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY seq ASC")
    suspend fun getMessagesForConversationSync(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY seq ASC LIMIT :limit OFFSET :offset")
    suspend fun getMessagesPaged(conversationId: String, limit: Int, offset: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getMessageById(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY seq DESC LIMIT 1")
    suspend fun getLastMessage(conversationId: String): MessageEntity?

    /** Highest position used in this conversation, or 0 when it has no messages yet. */
    @Query("SELECT COALESCE(MAX(seq), 0) FROM messages WHERE conversation_id = :conversationId")
    suspend fun getMaxSeq(conversationId: String): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)
    
    @Update
    suspend fun updateMessage(message: MessageEntity)

    /**
     * Atomic content update for streaming; returns the number of rows updated (0 = no such message).
     */
    @Query("UPDATE messages SET content = :content, token_count = COALESCE(:tokenCount, token_count), finish_reason = COALESCE(:finishReason, finish_reason) WHERE id = :id")
    suspend fun updateContent(id: String, content: String, tokenCount: Int?, finishReason: String?): Int
    
    @Delete
    suspend fun deleteMessage(message: MessageEntity)
    
    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessageById(id: String): Int
    
    @Query("DELETE FROM messages WHERE conversation_id = :conversationId")
    suspend fun deleteMessagesForConversation(conversationId: String): Int
    
    @Query("SELECT COUNT(*) FROM messages WHERE conversation_id = :conversationId")
    suspend fun getMessageCount(conversationId: String): Int
    
    @Query("SELECT SUM(token_count) FROM messages WHERE conversation_id = :conversationId")
    suspend fun getTotalTokenCount(conversationId: String): Int?
}
