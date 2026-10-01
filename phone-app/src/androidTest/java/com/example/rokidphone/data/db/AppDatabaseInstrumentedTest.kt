package com.example.rokidphone.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseInstrumentedTest {

    private lateinit var db: AppDatabase
    private lateinit var conversationDao: ConversationDao
    private lateinit var messageDao: MessageDao
    private lateinit var recordingDao: RecordingDao

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        conversationDao = db.conversationDao()
        messageDao = db.messageDao()
        recordingDao = db.recordingDao()
    }

    @After
    fun tearDown() {
        // Guard: if setup() threw before assigning db, an unguarded close would mask the real cause.
        if (::db.isInitialized) db.close()
    }

    @Test
    fun insertAndQueryConversationAndMessage_flowAndSync() = runTest {
        // 測試：Conversation / Message 的寫入與查詢（Flow + Sync）
        val conversation = ConversationEntity(
            id = "conv-1",
            title = "Test Conversation",
            providerId = "OPENAI",
            modelId = "gpt-test"
        )
        conversationDao.insertConversation(conversation)

        // These bypass the repository, which is what normally assigns seq, so set it
        // here: conversations are ordered by seq rather than by created_at.
        val messageA = MessageEntity(
            id = "msg-1",
            conversationId = conversation.id,
            role = "user",
            content = "hello",
            createdAt = 1L,
            seq = 1L
        )
        val messageB = MessageEntity(
            id = "msg-2",
            conversationId = conversation.id,
            role = "assistant",
            content = "hi",
            createdAt = 2L,
            seq = 2L
        )
        messageDao.insertMessages(listOf(messageA, messageB))

        val allConversations = conversationDao.getAllConversationsSync()
        assertEquals(1, allConversations.size)
        assertEquals("Test Conversation", allConversations.first().title)

        // Fail fast with a clear message if the Room flow never emits.
        val messagesFlow = withTimeout(5_000) {
            messageDao.getMessagesForConversation(conversation.id).first()
        }
        val messagesSync = messageDao.getMessagesForConversationSync(conversation.id)
        assertEquals(2, messagesFlow.size)
        assertEquals(2, messagesSync.size)
        assertEquals("msg-1", messagesSync[0].id)
        assertEquals("msg-2", messagesSync[1].id)
    }

    @Test
    fun archiveAndPinConversation_queriesReflectState() = runTest {
        // 測試：封存與置頂狀態應由對應查詢反映
        val conversation = ConversationEntity(
            id = "conv-archive",
            title = "Archive me",
            providerId = "GEMINI",
            modelId = "gemini-2.5-flash"
        )
        conversationDao.insertConversation(conversation)

        conversationDao.setPinned(conversation.id, true, updatedAt = 10L)
        conversationDao.setArchived(conversation.id, true, updatedAt = 20L)

        val active = conversationDao.getAllConversationsSync()
        val archived = conversationDao.getArchivedConversations().first()

        assertEquals(0, active.size)
        assertEquals(1, archived.size)
        val archivedConversation = archived.first()
        assertTrue(archivedConversation.isPinned)
        assertTrue(archivedConversation.isArchived)
        // Verify the timestamp parameter is actually bound (last write wins: 20L).
        assertEquals(20L, archivedConversation.updatedAt)
    }

    @Test
    fun deleteConversation_cascadesMessages() = runTest {
        // 測試：刪除 conversation 應連動刪除 messages
        val conversation = ConversationEntity(
            id = "conv-cascade",
            title = "Cascade",
            providerId = "OPENAI",
            modelId = "gpt"
        )
        conversationDao.insertConversation(conversation)
        messageDao.insertMessage(
            MessageEntity(
                id = "msg-cascade",
                conversationId = conversation.id,
                role = "user",
                content = "will be deleted"
            )
        )
        assertEquals(1, messageDao.getMessageCount(conversation.id))

        conversationDao.deleteConversationById(conversation.id)

        assertEquals(0, messageDao.getMessageCount(conversation.id))
        // Conversation-independent lookup proves the FK cascade really deleted the row.
        assertNull(messageDao.getMessageById("msg-cascade"))
    }

    @Test
    fun recordingDao_updateTranscriptAiResponseAndError() = runTest {
        // 測試：Recording 的轉錄/分析/錯誤更新應正確落地
        val recording = RecordingEntity(
            id = "rec-1",
            title = "recording",
            filePath = "/tmp/a.m4a",
            source = RecordingSource.PHONE,
            status = RecordingStatus.COMPLETED
        )
        recordingDao.insert(recording)

        recordingDao.updateTranscript(id = recording.id, transcript = "hello world")
        val afterTranscript = requireNotNull(recordingDao.getRecordingById(recording.id))
        assertEquals("hello world", afterTranscript.transcript)
        assertEquals(RecordingStatus.TRANSCRIBED, afterTranscript.status)

        recordingDao.updateAiResponse(
            id = recording.id,
            aiResponse = "analysis",
            providerId = "OPENAI",
            modelId = "gpt-5"
        )
        val afterAi = requireNotNull(recordingDao.getRecordingById(recording.id))
        assertEquals("analysis", afterAi.aiResponse)
        assertEquals("OPENAI", afterAi.providerId)
        assertEquals("gpt-5", afterAi.modelId)
        assertEquals(RecordingStatus.ANALYZED, afterAi.status)

        recordingDao.updateError(id = recording.id, errorMessage = "failed")
        val afterError = requireNotNull(recordingDao.getRecordingById(recording.id))
        assertEquals(RecordingStatus.ERROR, afterError.status)
        assertEquals("failed", afterError.errorMessage)
    }

    @Test
    fun recordingDao_searchFavoriteAndDeleteByIds() = runTest {
        // 測試：搜尋、最愛與批次刪除邏輯
        val a = RecordingEntity(
            id = "rec-a",
            title = "daily note",
            filePath = "/tmp/a.m4a",
            transcript = "hello world"
        )
        val b = RecordingEntity(
            id = "rec-b",
            title = "meeting",
            filePath = "/tmp/b.m4a",
            transcript = "project update"
        )
        recordingDao.insert(a)
        recordingDao.insert(b)

        recordingDao.updateFavorite(id = a.id, isFavorite = true, updatedAt = 100L)

        val favorites = recordingDao.getFavoriteRecordings().first()
        assertEquals(1, favorites.size)
        assertEquals("rec-a", favorites.first().id)
        // Verify the timestamp parameter is actually bound.
        assertEquals(100L, favorites.first().updatedAt)

        val search = recordingDao.searchRecordings("hello").first()
        assertEquals(1, search.size)
        assertEquals("rec-a", search.first().id)

        recordingDao.deleteByIds(listOf(a.id, b.id))
        assertNull(recordingDao.getRecordingById(a.id))
        assertNull(recordingDao.getRecordingById(b.id))
    }
}
