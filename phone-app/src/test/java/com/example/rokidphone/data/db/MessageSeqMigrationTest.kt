package com.example.rokidphone.data.db

import android.content.ContentValues
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The version 2 to 3 migration, which adds the per-conversation `seq` column.
 *
 * An upgrade must not appear to reshuffle anyone's history, so existing rows are
 * backfilled with the order the app was already displaying (created_at, then id).
 * Opening the database through Room afterwards also proves the migrated schema
 * matches what Room expects, which is what catches a mismatched column definition.
 */
@RunWith(RobolectricTestRunner::class)
class MessageSeqMigrationTest {

    private lateinit var context: Context
    private lateinit var databaseFile: File

    private companion object {
        const val DB_NAME = "migration-test.db"

        /** The `messages` table exactly as version 2 created it. */
        const val CREATE_MESSAGES_V2 = """
            CREATE TABLE IF NOT EXISTS `messages` (
                `id` TEXT NOT NULL, `conversation_id` TEXT NOT NULL, `role` TEXT NOT NULL,
                `content` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `token_count` INTEGER,
                `model_id` TEXT, `has_image` INTEGER NOT NULL, `image_path` TEXT,
                `finish_reason` TEXT, `error_message` TEXT, `metadata` TEXT,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`conversation_id`) REFERENCES `conversations`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """

        const val CREATE_CONVERSATIONS_V2 = """
            CREATE TABLE IF NOT EXISTS `conversations` (
                `id` TEXT NOT NULL, `title` TEXT NOT NULL, `provider_id` TEXT NOT NULL,
                `model_id` TEXT NOT NULL, `system_prompt` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL,
                `message_count` INTEGER NOT NULL, `is_archived` INTEGER NOT NULL,
                `is_pinned` INTEGER NOT NULL, `metadata` TEXT, PRIMARY KEY(`id`)
            )
        """

        /**
         * Version 2 also has `recordings`, added by MIGRATION_1_2. Room validates
         * every table on open, so the fixture has to include it even though this
         * migration does not touch it.
         */
        const val CREATE_RECORDINGS_V2 =
            """CREATE TABLE IF NOT EXISTS `recordings` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `file_path` TEXT NOT NULL, `source` TEXT NOT NULL, `status` TEXT NOT NULL, `duration_ms` INTEGER NOT NULL, `file_size_bytes` INTEGER NOT NULL, `sample_rate` INTEGER NOT NULL, `channels` INTEGER NOT NULL, `transcript` TEXT, `ai_response` TEXT, `provider_id` TEXT, `model_id` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `transcribed_at` INTEGER, `analyzed_at` INTEGER, `error_message` TEXT, `is_favorite` INTEGER NOT NULL, `notes` TEXT, PRIMARY KEY(`id`))"""
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseFile = context.getDatabasePath(DB_NAME)
        databaseFile.parentFile?.mkdirs()
        databaseFile.delete()
    }

    @After
    fun tearDown() {
        databaseFile.delete()
    }

    /** Opens a raw version 2 database and lets [seed] populate it. */
    private fun createVersion2Database(seed: (SupportSQLiteDatabase) -> Unit) {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(CREATE_CONVERSATIONS_V2)
                        db.execSQL(CREATE_MESSAGES_V2)
                        db.execSQL(CREATE_RECORDINGS_V2)
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_conversation_id` ON `messages` (`conversation_id`)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase.use { seed(it) }
        helper.close()
    }

    private fun SupportSQLiteDatabase.insertConversation(id: String) {
        insert(
            "conversations", 0,
            ContentValues().apply {
                put("id", id); put("title", "Chat"); put("provider_id", "openai")
                put("model_id", "gpt-4"); put("system_prompt", ""); put("created_at", 1L)
                put("updated_at", 1L); put("message_count", 0); put("is_archived", 0)
                put("is_pinned", 0)
            }
        )
    }

    private fun SupportSQLiteDatabase.insertMessage(
        id: String,
        conversationId: String,
        content: String,
        createdAt: Long
    ) {
        insert(
            "messages", 0,
            ContentValues().apply {
                put("id", id); put("conversation_id", conversationId); put("role", "user")
                put("content", content); put("created_at", createdAt); put("has_image", 0)
            }
        )
    }

    /** Runs the migration by opening the database through Room, then reads it back. */
    private fun migrateAndRead(conversationId: String): List<Pair<String, Long>> {
        val database = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        return try {
            runBlocking {
                database.messageDao().getMessagesForConversationSync(conversationId)
                    .map { it.content to it.seq }
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `existing messages keep the order they were already displayed in`() {
        createVersion2Database { db ->
            db.insertConversation("c1")
            // Inserted out of order on purpose; created_at is what decided the display.
            db.insertMessage("m-third", "c1", "third", createdAt = 300)
            db.insertMessage("m-first", "c1", "first", createdAt = 100)
            db.insertMessage("m-second", "c1", "second", createdAt = 200)
        }

        val migrated = migrateAndRead("c1")

        assertThat(migrated.map { it.first }).containsExactly("first", "second", "third").inOrder()
        assertThat(migrated.map { it.second }).containsExactly(1L, 2L, 3L).inOrder()
    }

    @Test
    fun `messages sharing a timestamp are numbered by the old id tiebreaker`() {
        createVersion2Database { db ->
            db.insertConversation("c1")
            // All in the same millisecond: version 2 ordered these by id, so the
            // backfill must reproduce that rather than invent a new order.
            db.insertMessage("bbb", "c1", "b", createdAt = 100)
            db.insertMessage("aaa", "c1", "a", createdAt = 100)
            db.insertMessage("ccc", "c1", "c", createdAt = 100)
        }

        val migrated = migrateAndRead("c1")

        assertThat(migrated.map { it.first }).containsExactly("a", "b", "c").inOrder()
        assertThat(migrated.map { it.second }).containsExactly(1L, 2L, 3L).inOrder()
    }

    @Test
    fun `each conversation is numbered from one independently`() {
        createVersion2Database { db ->
            db.insertConversation("c1")
            db.insertConversation("c2")
            db.insertMessage("m1", "c1", "c1 first", createdAt = 100)
            db.insertMessage("m2", "c2", "c2 first", createdAt = 110)
            db.insertMessage("m3", "c1", "c1 second", createdAt = 120)
            db.insertMessage("m4", "c2", "c2 second", createdAt = 130)
        }

        assertThat(migrateAndRead("c1").map { it.second }).containsExactly(1L, 2L).inOrder()
        assertThat(migrateAndRead("c2").map { it.second }).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun `the shared instance registers both migrations`() {
        // getInstance is the path the app actually uses; it must carry every
        // migration, or an upgrade from version 1 falls back to a destructive open.
        val first = AppDatabase.getInstance(context)
        val second = AppDatabase.getInstance(context)

        assertThat(first).isSameInstanceAs(second)
        val configured = first.openHelper.writableDatabase.version
        assertThat(configured).isEqualTo(3)
        first.close()
    }

    @Test
    fun `an empty database migrates cleanly`() {
        createVersion2Database { db -> db.insertConversation("c1") }

        assertThat(migrateAndRead("c1")).isEmpty()
    }

    @Test
    fun `a message added after the upgrade continues the numbering`() {
        createVersion2Database { db ->
            db.insertConversation("c1")
            db.insertMessage("m1", "c1", "before upgrade", createdAt = 100)
            db.insertMessage("m2", "c1", "also before", createdAt = 200)
        }

        val database = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                val next = database.messageDao().getMaxSeq("c1") + 1
                database.messageDao().insertMessage(
                    MessageEntity(
                        id = "m3",
                        conversationId = "c1",
                        role = "user",
                        content = "after upgrade",
                        createdAt = 50, // Deliberately older than the migrated rows.
                        seq = next
                    )
                )

                val ordered = database.messageDao().getMessagesForConversationSync("c1")
                // Ordering follows seq, so the new message stays last despite its
                // earlier created_at.
                assertThat(ordered.map { it.content })
                    .containsExactly("before upgrade", "also before", "after upgrade").inOrder()
                assertThat(ordered.map { it.seq }).containsExactly(1L, 2L, 3L).inOrder()
            }
        } finally {
            database.close()
        }
    }
}
