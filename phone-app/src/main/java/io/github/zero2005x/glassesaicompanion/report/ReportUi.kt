package io.github.zero2005x.glassesaicompanion.report

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.zero2005x.glassesaicompanion.BuildConfig
import io.github.zero2005x.glassesaicompanion.R
import io.github.zero2005x.glassesaicompanion.data.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** How long the developer keeps a report, as promised to the user in the dialog. */
const val REPORT_RETENTION_DAYS = 90

/**
 * Opens the report dialog for an AI response. `null` when this build has no report endpoint,
 * in which case screens simply do not show a report button.
 */
val LocalAiReport = staticCompositionLocalOf<((ReportTarget) -> Unit)?> { null }

/**
 * Provides [LocalAiReport] to [content] and shows the report dialog when a screen asks for it,
 * so every screen that displays AI-generated text can offer reporting without extra plumbing.
 */
@Composable
fun AiReportHost(
    client: ReportClient? = remember { ReportConfig.defaultClientOrNull() },
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    content: @Composable () -> Unit
) {
    var target by remember { mutableStateOf<ReportTarget?>(null) }
    val open: ((ReportTarget) -> Unit)? = if (client != null) { t -> target = t } else null

    CompositionLocalProvider(LocalAiReport provides open) { content() }

    val current = target
    if (client != null && current != null) {
        ReportDialog(target = current, client = client, ioDispatcher = ioDispatcher, onDismiss = { target = null })
    }
}

/** A small flag button that reports [target]. Draws nothing when reporting is unavailable. */
@Composable
fun ReportButton(target: ReportTarget, modifier: Modifier = Modifier) {
    val open = LocalAiReport.current ?: return
    IconButton(onClick = { open(target) }, modifier = modifier.size(32.dp)) {
        Icon(
            imageVector = Icons.Default.Flag,
            contentDescription = stringResource(R.string.report_content_description),
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private sealed interface Phase {
    data object Editing : Phase
    data object Sending : Phase
    data object Sent : Phase
    data class Failed(val retryable: Boolean) : Phase
}

@Composable
private fun ReportDialog(
    target: ReportTarget,
    client: ReportClient,
    ioDispatcher: CoroutineDispatcher,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var reason by remember { mutableStateOf<ReportReason?>(null) }
    var note by remember { mutableStateOf("") }
    var includeUserMessage by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf<Phase>(Phase.Editing) }

    val sending = phase is Phase.Sending
    val scrollState = rememberScrollState()

    // The error is shown at the top of the dialog: bring it into view when sending fails
    LaunchedEffect(phase) {
        if (phase is Phase.Failed) scrollState.animateScrollTo(0)
    }

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text(stringResource(R.string.report_title)) },
        text = {
            if (phase is Phase.Sent) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.report_sent_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(stringResource(R.string.report_sent_message))
                }
            } else {
                Column(
                    modifier = Modifier.verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    (phase as? Phase.Failed)?.let { failed ->
                        Text(
                            text = stringResource(
                                if (failed.retryable) R.string.report_failed_retry else R.string.report_failed_final
                            ),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text(
                        text = stringResource(R.string.report_intro),
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Column(modifier = Modifier.selectableGroup()) {
                        reasonOptions.forEach { (option, labelRes) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = reason == option,
                                        enabled = !sending,
                                        role = Role.RadioButton,
                                        onClick = { reason = option }
                                    )
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = reason == option, onClick = null, enabled = !sending)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(labelRes))
                            }
                        }
                    }

                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it.take(AiContentReport.MAX_NOTE_CHARS) },
                        label = { Text(stringResource(R.string.report_note_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !sending,
                        minLines = 2,
                        maxLines = 4
                    )

                    if (target.userContent != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = includeUserMessage,
                                    enabled = !sending,
                                    role = Role.Checkbox,
                                    onClick = { includeUserMessage = !includeUserMessage }
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = includeUserMessage, onCheckedChange = null, enabled = !sending)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.report_include_prompt))
                        }
                    }

                    HorizontalDivider()
                    Text(
                        text = stringResource(R.string.report_what_is_sent),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = stringResource(R.string.report_what_is_sent_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = target.assistantContent.take(PREVIEW_CHARS) +
                                if (target.assistantContent.length > PREVIEW_CHARS) "…" else "",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .padding(8.dp)
                                .heightIn(max = 120.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                    Text(
                        text = stringResource(R.string.report_retention, REPORT_RETENTION_DAYS),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                }
            }
        },
        confirmButton = {
            if (phase is Phase.Sent) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            } else {
                TextButton(
                    enabled = reason != null && !sending && (phase as? Phase.Failed)?.retryable != false,
                    onClick = {
                        val selected = reason ?: return@TextButton
                        phase = Phase.Sending
                        scope.launch {
                            val report = withContext(ioDispatcher) {
                                buildReport(context, target, selected, note, includeUserMessage)
                            }
                            phase = when (val result = client.submit(report)) {
                                ReportResult.Sent -> Phase.Sent
                                is ReportResult.Failed -> Phase.Failed(result.retryable)
                            }
                        }
                    }
                ) {
                    if (sending) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.report_sending))
                    } else {
                        Text(stringResource(R.string.report_send))
                    }
                }
            }
        },
        dismissButton = {
            if (phase !is Phase.Sent) {
                TextButton(enabled = !sending, onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        }
    )
}

private const val PREVIEW_CHARS = 300

private val reasonOptions = listOf(
    ReportReason.HARMFUL to R.string.report_reason_harmful,
    ReportReason.SEXUAL to R.string.report_reason_sexual,
    ReportReason.MISLEADING to R.string.report_reason_misleading,
    ReportReason.PRIVACY to R.string.report_reason_privacy,
    ReportReason.OTHER to R.string.report_reason_other
)

private fun buildReport(
    context: Context,
    target: ReportTarget,
    reason: ReportReason,
    note: String,
    includeUserMessage: Boolean
): AiContentReport {
    val settings = SettingsRepository.getInstance(context).getSettings()
    return AiContentReport(
        reason = reason,
        note = note.trim(),
        assistantContent = target.assistantContent,
        userContent = if (includeUserMessage) target.userContent else null,
        provider = settings.aiProvider.name,
        model = target.modelId ?: settings.getCurrentModelId(),
        appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        distribution = if (BuildConfig.PLAY_DISTRIBUTION) "play" else "github",
        locale = Locale.getDefault().toLanguageTag(),
        androidSdk = Build.VERSION.SDK_INT,
        createdAtMillis = System.currentTimeMillis()
    )
}
