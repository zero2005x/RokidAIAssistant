package io.github.zero2005x.glassesaicompanion.ui.demo

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.zero2005x.glassesaicompanion.R
import io.github.zero2005x.glassesaicompanion.data.db.Message
import io.github.zero2005x.glassesaicompanion.data.db.MessageRole
import io.github.zero2005x.glassesaicompanion.ui.conversation.ChatScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/** Pre-written replies of the offline demo, shown in order and then repeated. */
private val DEMO_REPLIES = listOf(
    R.string.demo_reply_1,
    R.string.demo_reply_2,
    R.string.demo_reply_3,
    R.string.demo_reply_4
)

private const val DEMO_CONVERSATION_ID = "demo"
private const val DEMO_MODEL_ID = "demo"
private const val DEMO_THINKING_MS = 600L

/**
 * An offline demonstration of the chat screen.
 *
 * It needs no API key and no network: replies are pre-written, nothing is stored and nothing
 * leaves the phone. It exists so anyone (including app reviewers) can see how chat works,
 * including the "report this response" button, before setting up a real AI service.
 */
@Composable
fun DemoChatScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val greeting = stringResource(R.string.demo_greeting)
    val replies = DEMO_REPLIES.map { stringResource(it) }

    var messages by remember { mutableStateOf(listOf(demoMessage(MessageRole.ASSISTANT, greeting))) }
    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var nextReply by remember { mutableIntStateOf(0) }

    ChatScreen(
        conversationTitle = stringResource(R.string.demo_title),
        messages = messages,
        isLoading = loading,
        error = null,
        inputText = input,
        onInputChange = { input = it },
        onSendMessage = {
            val text = input.trim()
            if (text.isNotEmpty() && !loading) {
                messages = messages + demoMessage(MessageRole.USER, text)
                input = ""
                loading = true
                scope.launch {
                    delay(DEMO_THINKING_MS)
                    messages = messages + demoMessage(MessageRole.ASSISTANT, replies[nextReply % replies.size])
                    nextReply++
                    loading = false
                }
            }
        },
        onClearError = {},
        onBack = onBack,
        onClearHistory = {
            messages = listOf(demoMessage(MessageRole.ASSISTANT, greeting))
            nextReply = 0
        },
        onExport = null,
        banner = { DemoBanner() }
    )
}

@Composable
private fun DemoBanner() {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.demo_banner),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

private fun demoMessage(role: MessageRole, content: String) = Message(
    id = UUID.randomUUID().toString(),
    conversationId = DEMO_CONVERSATION_ID,
    role = role,
    content = content,
    createdAt = System.currentTimeMillis(),
    tokenCount = null,
    modelId = if (role == MessageRole.ASSISTANT) DEMO_MODEL_ID else null,
    hasImage = false,
    imagePath = null,
    finishReason = null,
    errorMessage = null
)
