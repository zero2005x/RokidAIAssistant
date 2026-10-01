package com.example.rokidphone.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exercises [ConversationRepository] against a real in-memory Room database so the
 * DAO queries (LIKE escaping, counter transactions, ordering) are verified together
 * with the repository's mapping.
 */
@RunWith(RobolectricTestRunner::class)
class ConversationRepositoryTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: ConversationRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        mockkObject(AppDatabase.Companion)
        every { AppDatabase.getInstance(any()) } returns database
        repository = ConversationRepository::class.java
            .getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(context)
    }

    @After
    fun tearDown() {
        database.close()
        unmockkAll()
    }

    private suspend fun newConversation(title: String = "New Conversation") =
        repository.createConversation(providerId = "openai", modelId = "gpt-4", title = title)

    @Test
    fun `a created conversation is readable by id, by flow and in the active list`() = runTest {
        val created = repository.createConversation(
            providerId = "openai", modelId = "gpt-4", title = "Trip plan", systemPrompt = "Be brief"
        )

        assertThat(created.title).isEqualTo("Trip plan")
        assertThat(created.systemPrompt).isEqualTo("Be brief")
        assertThat(created.messageCount).isEqualTo(0)
        assertThat(created.isArchived).isFalse()
        assertThat(created.isPinned).isFalse()
        assertThat(repository.getConversationById(created.id)).isEqualTo(created)
        assertThat(repository.getConversationByIdFlow(created.id).first()).isEqualTo(created)
        assertThat(repository.getAllConversations().first().map { it.id }).containsExactly(created.id)
        assertThat(repository.getConversationById("missing")).isNull()
    }

    @Test
    fun `title archive and pin updates are persisted`() = runTest {
        val conversation = newConversation()

        repository.updateConversationTitle(conversation.id, "Renamed")
        assertThat(repository.getConversationById(conversation.id)!!.title).isEqualTo("Renamed")

        repository.pinConversation(conversation.id)
        assertThat(repository.getConversationById(conversation.id)!!.isPinned).isTrue()
        repository.unpinConversation(conversation.id)
        assertThat(repository.getConversationById(conversation.id)!!.isPinned).isFalse()

        repository.archiveConversation(conversation.id)
        assertThat(repository.getAllConversations().first()).isEmpty()
        assertThat(repository.getArchivedConversations().first().map { it.id })
            .containsExactly(conversation.id)

        repository.unarchiveConversation(conversation.id)
        assertThat(repository.getArchivedConversations().first()).isEmpty()
        assertThat(repository.getAllConversations().first()).hasSize(1)
    }

    @Test
    fun `search treats LIKE wildcards in user input as literal characters`() = runTest {
        val literal = newConversation("100% discount")
        newConversation("100 percent discount")
        val underscore = newConversation("draft_one")
        newConversation("draftXone")

        assertThat(repository.searchConversations("100%").first().map { it.id })
            .containsExactly(literal.id)
        assertThat(repository.searchConversations("draft_").first().map { it.id })
            .containsExactly(underscore.id)
        assertThat(repository.searchConversations("discount").first()).hasSize(2)
    }

    @Test
    fun `deleting conversations removes single rows and clears the archive`() = runTest {
        val kept = newConversation("kept")
        val removed = newConversation("removed")
        repository.deleteConversation(removed.id)
        assertThat(repository.getAllConversations().first().map { it.id }).containsExactly(kept.id)

        val archived = newConversation("archived")
        repository.archiveConversation(archived.id)
        repository.deleteAllArchivedConversations()
        assertThat(repository.getArchivedConversations().first()).isEmpty()
        assertThat(repository.getConversationById(kept.id)).isNotNull()
    }

    @Test
    fun `adding messages bumps the conversation counter and preserves insertion order`() = runTest {
        val conversation = newConversation()

        val user = repository.addUserMessage(conversation.id, "What is this?", imagePath = "/img.jpg")
        val assistant = repository.addAssistantMessage(
            conversation.id, "A photo.", modelId = "gpt-4", tokenCount = 12, finishReason = "stop"
        )

        assertThat(user.role).isEqualTo(MessageRole.USER)
        assertThat(user.hasImage).isTrue()
        assertThat(user.imagePath).isEqualTo("/img.jpg")
        assertThat(assistant.role).isEqualTo(MessageRole.ASSISTANT)
        assertThat(assistant.modelId).isEqualTo("gpt-4")
        assertThat(assistant.hasImage).isFalse()

        // Insertion order holds even though both messages are written in the same
        // millisecond: ordering comes from the per-conversation seq, not created_at.
        assertThat(repository.getMessagesForConversation(conversation.id).first().map { it.id })
            .containsExactly(user.id, assistant.id).inOrder()
        assertThat(repository.getMessagesForConversationSync(conversation.id)).hasSize(2)
        assertThat(repository.getMessageCount(conversation.id)).isEqualTo(2)
        assertThat(repository.getTotalTokenCount(conversation.id)).isEqualTo(12)
        assertThat(repository.getConversationById(conversation.id)!!.messageCount).isEqualTo(2)
    }

    @Test
    fun `paging returns a window of the conversation history`() = runTest {
        val conversation = newConversation()
        repeat(5) { repository.addUserMessage(conversation.id, "message $it") }

        // All five are written in the same millisecond, so this also pins down that
        // paging walks them in insertion order rather than an arbitrary one.
        assertThat(repository.getMessagesPaged(conversation.id, page = 0, pageSize = 2)
            .map { it.content }).containsExactly("message 0", "message 1").inOrder()
        assertThat(repository.getMessagesPaged(conversation.id, page = 1, pageSize = 2)
            .map { it.content }).containsExactly("message 2", "message 3").inOrder()
        assertThat(repository.getMessagesPaged(conversation.id, page = 2, pageSize = 2)
            .map { it.content }).containsExactly("message 4")
        assertThat(repository.getMessagesPaged(conversation.id, page = 9, pageSize = 2)).isEmpty()
    }

    @Test
    fun `streaming updates rewrite content and a missing id is a no-op`() = runTest {
        val conversation = newConversation()
        val message = repository.addAssistantMessage(conversation.id, "par")

        repository.updateMessageContent(message.id, "partial answer", tokenCount = 7, finishReason = "stop")
        val updated = repository.getMessagesForConversationSync(conversation.id).single()
        assertThat(updated.content).isEqualTo("partial answer")
        assertThat(updated.tokenCount).isEqualTo(7)
        assertThat(updated.finishReason).isEqualTo("stop")

        // A vanished message must not throw: streaming can outlive a deleted row.
        repository.updateMessageContent("missing-id", "ignored")
        assertThat(repository.getMessageCount(conversation.id)).isEqualTo(1)
    }

    @Test
    fun `deleting and clearing messages keeps the conversation counter consistent`() = runTest {
        val conversation = newConversation()
        val first = repository.addUserMessage(conversation.id, "first")
        repository.addUserMessage(conversation.id, "second")

        repository.deleteMessage(first.id)
        assertThat(repository.getConversationById(conversation.id)!!.messageCount).isEqualTo(1)
        // Deleting the same message twice must not push the counter negative.
        repository.deleteMessage(first.id)
        assertThat(repository.getConversationById(conversation.id)!!.messageCount).isEqualTo(1)

        repository.clearConversationMessages(conversation.id)
        assertThat(repository.getMessagesForConversationSync(conversation.id)).isEmpty()
        assertThat(repository.getConversationById(conversation.id)!!.messageCount).isEqualTo(0)
        assertThat(repository.getTotalTokenCount(conversation.id)).isEqualTo(0)
    }

    @Test
    fun `auto generated titles use the first user message and truncate long ones`() = runTest {
        val short = newConversation()
        repository.addAssistantMessage(short.id, "assistant speaks first")
        repository.addUserMessage(short.id, "hello\nthere")
        repository.autoGenerateTitle(short.id)
        assertThat(repository.getConversationById(short.id)!!.title).isEqualTo("hello there")

        val long = newConversation()
        repository.addUserMessage(long.id, "x".repeat(80))
        repository.autoGenerateTitle(long.id)
        assertThat(repository.getConversationById(long.id)!!.title).isEqualTo("x".repeat(50) + "...")

        // No user message: the existing title is left alone.
        val empty = newConversation("untouched")
        repository.addAssistantMessage(empty.id, "only assistant")
        repository.autoGenerateTitle(empty.id)
        assertThat(repository.getConversationById(empty.id)!!.title).isEqualTo("untouched")
    }

    @Test
    fun `today's voice session is the most recent matching conversation`() = runTest {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        assertThat(repository.findTodayVoiceSession()).isNull()

        newConversation("Voice Session 2020-01-01")
        newConversation("Notes $today")
        val english = newConversation("Voice Session $today")
        val chinese = newConversation("語音對話 $today")
        repository.updateConversationTitle(chinese.id, "語音對話 $today")

        val found = repository.findTodayVoiceSession()
        assertThat(found).isNotNull()
        assertThat(found!!.id).isAnyOf(english.id, chinese.id)
        assertThat(found.title).contains(today)
    }

    @Test
    fun `an unknown persisted role degrades to USER instead of crashing collectors`() = runTest {
        val conversation = newConversation()
        database.messageDao().insertMessage(
            MessageEntity(
                id = "legacy", conversationId = conversation.id, role = "tool", content = "legacy row"
            )
        )
        database.messageDao().insertMessage(
            MessageEntity(
                id = "upper", conversationId = conversation.id, role = "ASSISTANT", content = "shouty role"
            )
        )

        val roles = repository.getMessagesForConversationSync(conversation.id).associate { it.id to it.role }
        assertThat(roles["legacy"]).isEqualTo(MessageRole.USER)
        assertThat(roles["upper"]).isEqualTo(MessageRole.ASSISTANT)
    }
}
