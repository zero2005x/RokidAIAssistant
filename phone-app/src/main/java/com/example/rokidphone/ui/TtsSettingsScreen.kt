package com.example.rokidphone.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.rokidphone.R
import com.example.rokidphone.data.ApiSettings
import com.example.rokidphone.data.TtsProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pre-defined Edge TTS voice options for the user to choose from.
 * The first entry ("") maps to "Auto-detect" (let the engine pick by script).
 */
private data class EdgeVoiceOption(val voiceName: String, val displayLabel: String)

// Default Edge TTS voice names per language, referenced both in the UI options and
// in the script-based fallback selector below.
private const val VOICE_KO_SUNHI = "ko-KR-SunHiNeural"
private const val VOICE_EN_JENNY = "en-US-JennyNeural"
private const val VOICE_ZH_XIAOXIAO = "zh-CN-XiaoxiaoNeural"
private const val VOICE_JA_NANAMI = "ja-JP-NanamiNeural"

private val EDGE_VOICE_OPTIONS = listOf(
    EdgeVoiceOption("", "Auto-detect"),
    // Korean
    EdgeVoiceOption(VOICE_KO_SUNHI, "Korean — SunHi (Female)"),
    EdgeVoiceOption("ko-KR-InJoonNeural", "Korean — InJoon (Male)"),
    // English
    EdgeVoiceOption(VOICE_EN_JENNY, "English — Jenny (Female)"),
    EdgeVoiceOption("en-US-GuyNeural", "English — Guy (Male)"),
    EdgeVoiceOption("en-GB-SoniaNeural", "English — Sonia (Female, UK)"),
    // Chinese
    EdgeVoiceOption(VOICE_ZH_XIAOXIAO, "Chinese — Xiaoxiao (Female)"),
    EdgeVoiceOption("zh-CN-YunxiNeural", "Chinese — Yunxi (Male)"),
    EdgeVoiceOption("zh-TW-HsiaoChenNeural", "Chinese — HsiaoChen (Female, TW)"),
    // Japanese
    EdgeVoiceOption(VOICE_JA_NANAMI, "Japanese — Nanami (Female)"),
    EdgeVoiceOption("ja-JP-KeitaNeural", "Japanese — Keita (Male)")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsSettingsScreen(
    settings: ApiSettings,
    onSettingsChange: (ApiSettings) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tts_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        onSettingsChange(
                            settings.copy(
                                ttsProvider = TtsProvider.EDGE_TTS,
                                ttsVoiceOverride = "",
                                ttsSpeechRate = 1.0f,
                                ttsPitch = 0.0f,
                                systemTtsSpeechRate = 1.0f,
                                systemTtsPitch = 1.0f
                            )
                        )
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.reset_defaults))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Info card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Text(
                    text = stringResource(R.string.tts_settings_description),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            // ── TTS Engine Selection ────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.tts_engine),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    TtsProvider.entries.forEach { provider ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.ttsProvider == provider,
                                onClick = { onSettingsChange(settings.copy(ttsProvider = provider)) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = stringResource(provider.displayNameResId),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = stringResource(provider.descriptionResId),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // ── Edge TTS settings (visible when EDGE_TTS selected) ───
            AnimatedVisibility(visible = settings.ttsProvider == TtsProvider.EDGE_TTS) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Voice override dropdown
                    EdgeVoiceSelector(
                        selectedVoice = settings.ttsVoiceOverride,
                        onVoiceSelected = { onSettingsChange(settings.copy(ttsVoiceOverride = it)) }
                    )

                    // Speech rate slider
                    ParameterSliderCard(
                        title = stringResource(R.string.tts_speech_rate),
                        description = stringResource(R.string.tts_speech_rate_desc),
                        value = settings.ttsSpeechRate,
                        valueRange = 0.5f..2.0f,
                        steps = 14,
                        valueFormat = "%.1f×",
                        onValueChange = { onSettingsChange(settings.copy(ttsSpeechRate = it)) }
                    )

                    // Pitch slider
                    ParameterSliderCard(
                        title = stringResource(R.string.tts_pitch),
                        description = stringResource(R.string.tts_pitch_desc),
                        value = settings.ttsPitch,
                        valueRange = -0.5f..0.5f,
                        steps = 9,
                        valueFormat = "%+.1f",
                        onValueChange = { onSettingsChange(settings.copy(ttsPitch = it)) }
                    )
                }
            }

            // ── System TTS settings (visible when SYSTEM_TTS selected) ──
            AnimatedVisibility(visible = settings.ttsProvider == TtsProvider.SYSTEM_TTS) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ParameterSliderCard(
                        title = stringResource(R.string.tts_system_rate),
                        description = stringResource(R.string.tts_system_rate_desc),
                        value = settings.systemTtsSpeechRate,
                        valueRange = 0.5f..2.0f,
                        steps = 14,
                        valueFormat = "%.1f×",
                        onValueChange = { onSettingsChange(settings.copy(systemTtsSpeechRate = it)) }
                    )

                    ParameterSliderCard(
                        title = stringResource(R.string.tts_system_pitch),
                        description = stringResource(R.string.tts_system_pitch_desc),
                        value = settings.systemTtsPitch,
                        valueRange = 0.5f..2.0f,
                        steps = 14,
                        valueFormat = "%.1f×",
                        onValueChange = { onSettingsChange(settings.copy(systemTtsPitch = it)) }
                    )
                }
            }

            // ── Test Voice button ────────────────────────────────
            var isTesting by remember { mutableStateOf(false) }
            Button(
                onClick = {
                    if (isTesting) return@Button
                    isTesting = true
                    coroutineScope.launch {
                        try {
                            testTtsVoice(context, settings)
                        } catch (e: Exception) {
                            // rememberCoroutineScope already dispatches on the main thread.
                            Toast.makeText(context, e.message ?: "TTS test failed", Toast.LENGTH_SHORT).show()
                        } finally {
                            isTesting = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isTesting
            ) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isTesting) stringResource(R.string.tts_testing) else stringResource(R.string.tts_test_voice))
            }

            // Note card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Text(
                    text = stringResource(R.string.tts_settings_note),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// ────────────────────────────────────────────────
// Composable helpers
// ────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EdgeVoiceSelector(
    selectedVoice: String,
    onVoiceSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentOption = EDGE_VOICE_OPTIONS.firstOrNull { it.voiceName == selectedVoice }
        ?: EDGE_VOICE_OPTIONS.first()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.tts_voice_override),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.tts_voice_override_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedTextField(
                    value = currentOption.displayLabel,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    EDGE_VOICE_OPTIONS.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.displayLabel) },
                            onClick = {
                                onVoiceSelected(option.voiceName)
                                expanded = false
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ParameterSliderCard(
    title: String,
    description: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueFormat: String,
    onValueChange: (Float) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    String.format(valueFormat, value),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                modifier = Modifier.fillMaxWidth()
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(String.format(valueFormat, valueRange.start), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(String.format(valueFormat, valueRange.endInclusive), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ────────────────────────────────────────────────
// Test helper – plays a localised sample sentence
// ────────────────────────────────────────────────

private suspend fun testTtsVoice(
    context: android.content.Context,
    settings: ApiSettings,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main
) {
    val sampleText = context.getString(R.string.tts_test_sample)

    when (settings.ttsProvider) {
        TtsProvider.EDGE_TTS -> {
            val client = com.example.rokidphone.service.EdgeTtsClient()
            // Resolve voice
            val voice = settings.ttsVoiceOverride.ifBlank {
                // Mimic auto-detection: pick voice based on sample text
                detectEdgeVoice(sampleText, java.util.Locale.getDefault())
            }
            // Format rate & pitch to SSML values
            val rateStr = formatEdgeRate(settings.ttsSpeechRate)
            val pitchStr = formatEdgePitch(settings.ttsPitch)

            val result = client.synthesize(sampleText, voice, rateStr, pitchStr)
            result.onSuccess { audioData ->
                withContext(mainDispatcher) {
                    playTestAudio(context, audioData)
                }
            }
            result.onFailure { throw it }
        }
        TtsProvider.SYSTEM_TTS -> {
            withContext(mainDispatcher) {
                val tts = android.speech.tts.TextToSpeech(context, null)
                tts.setSpeechRate(settings.systemTtsSpeechRate)
                tts.setPitch(settings.systemTtsPitch)
                tts.speak(sampleText, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "test")
            }
        }
        TtsProvider.GOOGLE_TRANSLATE_TTS -> {
            // Use system TTS as a simple test for Google Translate provider
            withContext(mainDispatcher) {
                val tts = android.speech.tts.TextToSpeech(context, null)
                tts.speak(sampleText, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "test")
            }
        }
    }
}

private fun playTestAudio(context: android.content.Context, audioData: ByteArray) {
    val tempFile = java.io.File.createTempFile("tts_test_", ".mp3", context.cacheDir)
    java.io.FileOutputStream(tempFile).use { it.write(audioData) }
    val player = android.media.MediaPlayer().apply {
        setDataSource(tempFile.absolutePath)
        setAudioAttributes(
            android.media.AudioAttributes.Builder()
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                .build()
        )
        setOnCompletionListener { mp ->
            mp.release()
            tempFile.delete()
        }
        prepare()
        start()
    }
}

// ── Shared formatting helpers (also used by the service layer) ──

/** Convert a multiplier (e.g. 1.5) to an Edge TTS rate string like "+50%". */
internal fun formatEdgeRate(multiplier: Float): String {
    val pct = ((multiplier - 1.0f) * 100).toInt()
    return if (pct >= 0) "+${pct}%" else "${pct}%"
}

/** Convert a float offset (e.g. -0.2) to an Edge TTS pitch string like "-12Hz". */
internal fun formatEdgePitch(offset: Float): String {
    val hz = (offset * 60).toInt()            // map ±0.5 → ±30 Hz
    return if (hz >= 0) "+${hz}Hz" else "${hz}Hz"
}

/** Simple script-based voice selection (mirrors TextToSpeechService logic). */
internal fun detectEdgeVoice(text: String, preferredLocale: java.util.Locale?): String {
    return when {
        text.any { it in '\uAC00'..'\uD7AF' } -> VOICE_KO_SUNHI
        text.any { it in '\u4E00'..'\u9FFF' } -> VOICE_ZH_XIAOXIAO
        text.any { it in '\u3040'..'\u30FF' } -> VOICE_JA_NANAMI
        text.any { it in 'A'..'Z' || it in 'a'..'z' } -> VOICE_EN_JENNY
        else -> when (preferredLocale?.language) {
            "ko" -> VOICE_KO_SUNHI
            "ja" -> VOICE_JA_NANAMI
            "zh" -> VOICE_ZH_XIAOXIAO
            else -> VOICE_EN_JENNY
        }
    }
}
