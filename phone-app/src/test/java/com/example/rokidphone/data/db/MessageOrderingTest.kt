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

/**
 * Message ordering within a conversation.
 *
 * created_at alone cannot order a conversation: a question and a fast reply
 * routinely land in the same millisecond, and ordering used to fall back to the
 * primary key, which is a random UUID — so a reply could render above its
 * question. Ordering now comes from a per-conversation `seq` assigned inside the
 * insert transaction. Every test here writes its messages back to back, which is
 * exactly the same-millisecond case that used to be unstable.
 */
@RunWith(RobolectricTestRunner::class)
class MessageOrderingTest {

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

    private suspend fun newConversation() =
        repository.createConversation(providerId = "openai", modelId = "gpt-4", title = "Chat")

    @Test
    fun `a question and an immediate reply keep their order`() = runTest {
        val conversation = newConversation()

        val question = repository.addUserMessage(conversation.id, "What is this?")
        val answer = repository.addAssistantMessage(conversation.id, "A photo.")

        val ids = repository.getMessagesForConversation(conversation.id).first().map { it.id }
        assertThat(ids).containsExactly(question.id, answer.id).inOrder()
    }

    @Test
    fun `a long burst of messages stays in insertion order`() = runTest {
        val conversation = newConversation()

        // Enough messages that a random tiebreaker would almost certainly reorder
        // at least one pair: 30! orderings, and they share a millisecond or two.
        val expected = (0 until 30).map {
            repository.addUserMessage(conversation.id, "message $it").id
        }

        val actual = repository.getMessagesForConversation(conversation.id).first().map { it.id }
        assertThat(actual).containsExactlyElementsIn(expected).inOrder()
    }

    @Test
    fun `positions are assigned from one upwards without gaps`() = runTest {
        val conversation = newConversation()
        repeat(4) { repository.addUserMessage(conversation.id, "message $it") }

        val positions = database.messageDao()
            .getMessagesForConversationSync(conversation.id).map { it.seq }

        assertThat(positions).containsExactly(1L, 2L, 3L, 4L).inOrder()
    }

    @Test
    fun `each conversation is numbered independently`() = runTest {
        val first = newConversation()
        val second = newConversation()

        repository.addUserMessage(first.id, "first: one")
        repository.addUserMessage(second.id, "second: one")
        repository.addUserMessage(first.id, "first: two")

        assertThat(database.messageDao().getMessagesForConversationSync(first.id).map { it.seq })
            .containsExactly(1L, 2L).inOrder()
        assertThat(database.messageDao().getMessagesForConversationSync(second.id).map { it.seq })
            .containsExactly(1L).inOrder()
    }

    @Test
    fun `the last message is the one most recently added`() = runTest {
        val conversation = newConversation()
        repository.addUserMessage(conversation.id, "What is this?")
        val answer = repository.addAssistantMessage(conversation.id, "A photo.")

        assertThat(database.messageDao().getLastMessage(conversation.id)?.id)
            .isEqualTo(answer.id)
    }

    @Test
    fun `numbering continues after the earlier messages are deleted`() = runTest {
        val conversation = newConversation()
        val first = repository.addUserMessage(conversation.id, "one")
        repository.addUserMessage(conversation.id, "two")

        repository.deleteMessage(first.id)
        val third = repository.addUserMessage(conversation.id, "three")

        // The new message must sort after the survivor, not reuse position 1.
        val remaining = repository.getMessagesForConversation(conversation.id).first()
        assertThat(remaining.map { it.content }).containsExactly("two", "three").inOrder()
        assertThat(database.messageDao().getMessagesForConversationSync(conversation.id).last().seq)
            .isEqualTo(3L)
        assertThat(third.id).isNotEqualTo(first.id)
    }

    @Test
    fun `a conversation with no messages starts numbering at one`() = runTest {
        val conversation = newConversation()

        assertThat(database.messageDao().getMaxSeq(conversation.id)).isEqualTo(0L)

        repository.addUserMessage(conversation.id, "first")

        assertThat(database.messageDao().getMaxSeq(conversation.id)).isEqualTo(1L)
    }
}
