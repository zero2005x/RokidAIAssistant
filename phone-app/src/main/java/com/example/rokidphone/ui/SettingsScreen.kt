package com.example.rokidphone.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.rokidphone.R
import com.example.rokidphone.data.*
import com.example.rokidphone.service.ai.AiServiceFactory
import com.example.rokidphone.service.stt.SttProvider
import com.example.rokidphone.service.stt.SttServiceFactory
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: ApiSettings,
    onSettingsChange: (ApiSettings) -> Unit,
    onBack: () -> Unit,
    onNavigateToLogViewer: () -> Unit = {},
    onTestConnection: (ApiSettings) -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showProviderDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var showSpeechServiceDialog by remember { mutableStateOf(false) }
    var showSystemPromptDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showCustomModelDialog by remember { mutableStateOf(false) }
    var currentLanguage by remember { mutableStateOf(LanguageManager.getCurrentLanguage(context)) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.api_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Language settings section
            item {
                SettingsSection(title = stringResource(R.string.language_settings)) {
                    SettingsRow(
                        title = stringResource(R.string.app_language),
                        subtitle = "${currentLanguage.nativeName} (${currentLanguage.displayName})",
                        onClick = { showLanguageDialog = true }
                    )
                }
            }
            
            // AI service settings section
            item {
                SettingsSection(title = stringResource(R.string.ai_service)) {
                    // AI provider selection
                    SettingsRow(
                        title = stringResource(R.string.ai_provider),
                        subtitle = stringResource(settings.aiProvider.displayNameResId),
                        onClick = { showProviderDialog = true }
                    )
                    
                    HorizontalDivider()
                    
                    // Model selection
                    val currentModel = AvailableModels.findModel(settings.aiModelId)
                    SettingsRow(
                        title = stringResource(R.string.ai_model),
                        subtitle = if (settings.aiProvider == AiProvider.CUSTOM) 
                            settings.customModelName.ifBlank { "custom" }
                        else 
                            currentModel?.displayName ?: settings.aiModelId,
                        onClick = { showModelDialog = true }
                    )
                }
            }
            
            // Custom Provider Settings (only shown for CUSTOM provider)
            if (settings.aiProvider == AiProvider.CUSTOM) {
                item {
                    CustomProviderSection(
                        baseUrl = settings.customBaseUrl,
                        onBaseUrlChange = { onSettingsChange(settings.copy(customBaseUrl = it)) },
                        modelName = settings.customModelName,
                        onModelNameChange = { onSettingsChange(settings.copy(customModelName = it)) },
                        apiKey = settings.customApiKey,
                        onApiKeyChange = { onSettingsChange(settings.copy(customApiKey = it)) },
                        onTestConnection = { onTestConnection(settings) }
                    )
                }
            }
            
            // API Key settings section (for non-custom providers)
            if (settings.aiProvider != AiProvider.CUSTOM) {
                item {
                                SettingsSection(title = stringResource(R.string.api_keys)) {
                        when (settings.aiProvider) {
                            AiProvider.GEMINI -> {
                                ApiKeyField(
                                    label = stringResource(R.string.gemini_api_key),
                                    value = settings.geminiApiKey,
                                    onValueChange = { onSettingsChange(settings.copy(geminiApiKey = it)) },
                                    isActive = true
                                )
                            }
                            AiProvider.OPENAI -> {
                                ApiKeyField(
                                    label = stringResource(R.string.openai_api_key),
                                    value = settings.openaiApiKey,
                                    onValueChange = { onSettingsChange(settings.copy(openaiApiKey = it)) },
                                    isActive = true
                                )
                            }
                            AiProvider.ANTHROPIC -> {
                                ApiKeyField(
                                    label = stringResource(R.string.anthropic_api_key),
                                    value = settings.anthropicApiKey,
                                    onValueChange = { onSettingsChange(settings.copy(anthropicApiKey = it)) },
                                    isActive = true
                                )
                            }
                            AiProvider.DEEPSEEK -> {
                                ApiKeyField(
                                    label = stringResource(R.string.deepseek_api_key),
                                    value = settings.deepseekApiKey,
                                    onValueChange = { onSettingsChange(settings.copy(deepseekApiKey = it)) },
                                    isActive = true
                                )
                            }
                            AiProvider.GROQ -> {
                                ApiKeyField(
                                    label = stringResource(R.string.groq_api_key),
                                    value = settings.groqApiKey,
                                    onValueChange = { onSettingsChange(settings.copy(groqApiKey = it)) },
                                    isActive = true
                                )
                            }
                            AiProvider.XAI -> {
                                ApiKeyField(
                                    label = stringResource(R.string.xai_api_key),
                                    value = settings.xaiApiKey,
                                    onValueChange = { onSettingsChange(settings.copy(xaiApiKey = it)) },
                                    isActive = true
                                )
                            }
                            AiProvider.ALIBABA -> {
                                ApiKeyField(
                                    label = stringResource(R.string.alibaba_api_key),
                                    value = settings.alibabaApiKey,
                                    onValueChange = { onSettingsChange(settings.copy(alibabaApiKey = it)) },
                                    isActive = true
                                )
                            }
                            AiProvider.ZHIPU -> {
                                ApiKeyField(
                                    label = stringResource(R.string.zhipu_api_key),
                                    value = settings.zhipuApiKey,
                                    onValueChange = { onSettingsChange(settings.copy(zhipuApiKey = it)) },
                                    isActive = true
                                )
                            }
                            AiProvider.BAIDU -> {
                                // Baidu requires both API Key and Secret Key
                                ApiKeyField(
                                    label = stringResource(R.string.baidu_api_key),
                                    value = settings.baiduApiKey,
                                    onValueChange = { onSettingsChange(settings.copy(baiduApiKey = it)) },
                                    isActive = true
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                ApiKeyField(
                                    label = stringResource(R.string.baidu_secret_key),
                                    value = settings.baiduSecretKey,
                                    onValueChange = { onSettingsChange(settings.copy(baiduSecretKey = it)) },
                                    isActive = true
                                )
                            }
                            AiProvider.PERPLEXITY -> {
                                ApiKeyField(
                                    label = stringResource(R.string.perplexity_api_key),
                                    value = settings.perplexityApiKey,
                                    onValueChange = { onSettingsChange(settings.copy(perplexityApiKey = it)) },
                                    isActive = true
                                )
                            }
                            AiProvider.GEMINI_LIVE -> {
                                // Gemini Live shares the Gemini API key
                                ApiKeyField(
                                    label = stringResource(R.string.gemini_api_key),
                                    value = settings.geminiApiKey,
                                    onValueChange = { onSettingsChange(settings.copy(geminiApiKey = it)) },
                                    isActive = true
                                )
                            }
                            else -> {}
                        }
                    }
                }
            }
            
            // Speech recognition settings section
            item {
                SettingsSection(title = stringResource(R.string.speech_recognition)) {
                    SettingsRow(
                        title = stringResource(R.string.speech_recognition_service),
                        subtitle = stringResource(settings.sttProvider.displayNameResId),
                        onClick = { showSpeechServiceDialog = true }
                    )
                    
                    // Dynamic credential fields based on selected STT provider
                    SttCredentialsFields(
                        provider = settings.sttProvider,
                        settings = settings,
                        onSettingsChange = onSettingsChange
                    )
                }
            }
            
            // Advanced settings section
            item {
                SettingsSection(title = stringResource(R.string.advanced_settings)) {
                    SettingsRow(
                        title = stringResource(R.string.system_prompt),
                        subtitle = settings.systemPrompt.take(50) + if (settings.systemPrompt.length > 50) "..." else "",
                        onClick = { showSystemPromptDialog = true }
                    )
                }
            }
            
            // Recording settings section
            item {
                SettingsSection(title = stringResource(R.string.recording_settings)) {
                    SettingsRowWithSwitch(
                        title = stringResource(R.string.auto_analyze_recordings),
                        subtitle = stringResource(R.string.auto_analyze_recordings_description),
                        checked = settings.autoAnalyzeRecordings,
                        onCheckedChange = { onSettingsChange(settings.copy(autoAnalyzeRecordings = it)) }
                    )
                }
            }
            
            // Status display
            item {
                val isValid = settings.isValid()
                val statusText = when {
                    isValid -> stringResource(R.string.settings_complete)
                    settings.aiProvider == AiProvider.CUSTOM && settings.customBaseUrl.isBlank() -> 
                        stringResource(R.string.invalid_url)
                    settings.aiProvider == AiProvider.CUSTOM -> 
                        stringResource(R.string.settings_complete)
                    else -> stringResource(R.string.please_enter_api_key, stringResource(settings.aiProvider.displayNameResId))
                }
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isValid) 
                            MaterialTheme.colorScheme.primaryContainer
                        else 
                            MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isValid) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (isValid) 
                                MaterialTheme.colorScheme.primary 
                            else 
                                MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(text = statusText)
                    }
                }
            }
            
            // Developer Tools section
            item {
                SettingsSection(title = stringResource(R.string.developer_tools)) {
                    SettingsRow(
                        title = stringResource(R.string.log_viewer),
                        subtitle = stringResource(R.string.log_viewer_description),
                        onClick = onNavigateToLogViewer,
                        icon = Icons.Default.BugReport
                    )
                }
            }
        }
    }
    
    // Dialogs
    if (showProviderDialog) {
        ProviderSelectionDialog(
            currentProvider = settings.aiProvider,
            onSelect = { provider ->
                onSettingsChange(settings.copy(
                    aiProvider = provider,
                    aiModelId = AvailableModels.getModelsForProvider(provider).firstOrNull()?.id 
                        ?: settings.aiModelId
                ))
                showProviderDialog = false
            },
            onDismiss = { showProviderDialog = false }
        )
    }
    
    if (showModelDialog) {
        ModelSelectionDialog(
            currentModelId = settings.aiModelId,
            models = AvailableModels.getModelsForProvider(settings.aiProvider),
            onSelect = { modelId ->
                onSettingsChange(settings.copy(aiModelId = modelId))
                showModelDialog = false
            },
            onDismiss = { showModelDialog = false }
        )
    }
    
    if (showSpeechServiceDialog) {
        SttProviderSelectionDialog(
            currentProvider = settings.sttProvider,
            onSelect = { provider ->
                onSettingsChange(settings.copy(sttProvider = provider))
                showSpeechServiceDialog = false
            },
            onDismiss = { showSpeechServiceDialog = false }
        )
    }
    
    if (showSystemPromptDialog) {
        SystemPromptDialog(
            currentPrompt = settings.systemPrompt,
            onSave = { prompt ->
                onSettingsChange(settings.copy(systemPrompt = prompt))
                showSystemPromptDialog = false
            },
            onDismiss = { showSystemPromptDialog = false }
        )
    }
    
    if (showLanguageDialog) {
        LanguageSelectionDialog(
            currentLanguage = currentLanguage,
            onSelect = { language ->
                val settingsRepository = SettingsRepository.getInstance(context)
                
                // Check if current system prompt is default (needs update when language changes)
                val isUsingDefaultPrompt = settingsRepository.isUsingDefaultSystemPrompt()
                
                // Get new language's default prompt BEFORE changing the language
                val newDefaultPrompt = settingsRepository.getDefaultSystemPromptForLanguage(language)
                
                // Get the corresponding speech language code
                val newSpeechLanguage = when (language) {
                    AppLanguage.SIMPLIFIED_CHINESE -> "zh-CN"
                    AppLanguage.TRADITIONAL_CHINESE -> "zh-TW"
                    AppLanguage.JAPANESE -> "ja-JP"
                    AppLanguage.KOREAN -> "ko-KR"
                    AppLanguage.FRENCH -> "fr-FR"
                    AppLanguage.SPANISH -> "es-ES"
                    AppLanguage.ITALIAN -> "it-IT"
                    AppLanguage.RUSSIAN -> "ru-RU"
                    AppLanguage.THAI -> "th-TH"
                    AppLanguage.UKRAINIAN -> "uk-UA"
                    AppLanguage.VIETNAMESE -> "vi-VN"
                    AppLanguage.ARABIC -> "ar-SA"
                    else -> "en-US"
                }
                
                // Change the language
                LanguageManager.setLanguage(context, language)
                currentLanguage = language
                
                // Update settings: system prompt and speech language
                var updatedSettings = settings.copy(speechLanguage = newSpeechLanguage)
                if (isUsingDefaultPrompt) {
                    updatedSettings = updatedSettings.copy(systemPrompt = newDefaultPrompt)
                }
                onSettingsChange(updatedSettings)
                
                showLanguageDialog = false
            },
            onDismiss = { showLanguageDialog = false }
        )
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun SettingsRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(24.dp)
                            .padding(end = 0.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Column {
                    Text(
                        text = title, 
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun SettingsRowWithSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
fun ApiKeyField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    isActive: Boolean
) {
    var passwordVisible by remember { mutableStateOf(false) }
    val inUseText = stringResource(R.string.in_use)
    val hideText = stringResource(R.string.hide)
    val showText = stringResource(R.string.show)
    
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            Row {
                if (isActive && value.isNotBlank()) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = inUseText,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (passwordVisible) hideText else showText
                    )
                }
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        )
    )
}

@Composable
fun ProviderSelectionDialog(
    currentProvider: AiProvider,
    onSelect: (AiProvider) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_ai_provider)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                AiProvider.entries.forEach { provider ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(provider) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = provider == currentProvider,
                            onClick = { onSelect(provider) }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(provider.displayNameResId))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun ModelSelectionDialog(
    currentModelId: String,
    models: List<ModelOption>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_model)) },
        text = {
            Column {
                models.forEach { model ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(model.id) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = model.id == currentModelId,
                            onClick = { onSelect(model.id) }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(model.displayName)
                            Text(
                                text = model.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // Show capability badges
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                if (model.supportsAudio) {
                                    CapabilityBadge(
                                        icon = Icons.Default.Mic,
                                        text = stringResource(R.string.supports_audio),
                                        isSupported = true
                                    )
                                } else {
                                    CapabilityBadge(
                                        icon = Icons.Default.MicOff,
                                        text = stringResource(R.string.no_speech_support),
                                        isSupported = false
                                    )
                                }
                                if (model.supportsVision) {
                                    CapabilityBadge(
                                        icon = Icons.Default.Image,
                                        text = stringResource(R.string.supports_vision),
                                        isSupported = true
                                    )
                                } else {
                                    CapabilityBadge(
                                        icon = Icons.Default.HideImage,
                                        text = stringResource(R.string.no_vision_support),
                                        isSupported = false
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * Badge to indicate model capability support
 */
@Composable
private fun CapabilityBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    isSupported: Boolean
) {
    val color = if (isSupported) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline
    }
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = text,
            modifier = Modifier.size(12.dp),
            tint = color
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
fun SttProviderSelectionDialog(
    currentProvider: SttProvider,
    onSelect: (SttProvider) -> Unit,
    onDismiss: () -> Unit
) {
    val implementedProviders = remember { SttServiceFactory.getImplementedProviders() }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_speech_service)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                implementedProviders.forEach { provider ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(provider) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = provider == currentProvider,
                            onClick = { onSelect(provider) }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(stringResource(provider.displayNameResId))
                            Text(
                                text = provider.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * Dynamic credential input fields based on the selected STT provider
 */
@Composable
fun SttCredentialsFields(
    provider: SttProvider,
    settings: ApiSettings,
    onSettingsChange: (ApiSettings) -> Unit
) {
    // Providers that use main AI API keys (no additional credentials needed)
    val noCredentialsNeeded = listOf(
        SttProvider.GEMINI,
        SttProvider.OPENAI_WHISPER,
        SttProvider.GROQ_WHISPER
    )
    
    if (provider in noCredentialsNeeded) {
        // These providers use the main AI API key
        Text(
            text = stringResource(R.string.stt_uses_main_api_key),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        return
    }
    
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.stt_credentials_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        
        when (provider) {
            SttProvider.DEEPGRAM -> {
                ApiKeyInputField(
                    label = stringResource(R.string.stt_api_key),
                    value = settings.deepgramApiKey,
                    onValueChange = { onSettingsChange(settings.copy(deepgramApiKey = it)) }
                )
            }
            
            SttProvider.ASSEMBLYAI -> {
                ApiKeyInputField(
                    label = stringResource(R.string.stt_api_key),
                    value = settings.assemblyaiApiKey,
                    onValueChange = { onSettingsChange(settings.copy(assemblyaiApiKey = it)) }
                )
            }
            
            SttProvider.GOOGLE_CLOUD_STT -> {
                ApiKeyInputField(
                    label = stringResource(R.string.stt_project_id),
                    value = settings.gcpProjectId,
                    onValueChange = { onSettingsChange(settings.copy(gcpProjectId = it)) },
                    isPassword = false
                )
                ApiKeyInputField(
                    label = stringResource(R.string.stt_api_key),
                    value = settings.gcpApiKey,
                    onValueChange = { onSettingsChange(settings.copy(gcpApiKey = it)) }
                )
            }
            
            SttProvider.AZURE_SPEECH -> {
                ApiKeyInputField(
                    label = stringResource(R.string.stt_subscription_key),
                    value = settings.azureSpeechKey,
                    onValueChange = { onSettingsChange(settings.copy(azureSpeechKey = it)) }
                )
                ApiKeyInputField(
                    label = stringResource(R.string.stt_region),
                    value = settings.azureSpeechRegion,
                    onValueChange = { onSettingsChange(settings.copy(azureSpeechRegion = it)) },
                    isPassword = false,
                    placeholder = "eastus, westus2, etc."
                )
            }
            
            SttProvider.AWS_TRANSCRIBE -> {
                ApiKeyInputField(
                    label = stringResource(R.string.stt_access_key),
                    value = settings.awsAccessKeyId,
                    onValueChange = { onSettingsChange(settings.copy(awsAccessKeyId = it)) }
                )
                ApiKeyInputField(
                    label = stringResource(R.string.stt_secret_key),
                    value = settings.awsSecretAccessKey,
                    onValueChange = { onSettingsChange(settings.copy(awsSecretAccessKey = it)) }
                )
                ApiKeyInputField(
                    label = stringResource(R.string.stt_region),
                    value = settings.awsRegion,
                    onValueChange = { onSettingsChange(settings.copy(awsRegion = it)) },
                    isPassword = false,
                    placeholder = "us-east-1"
                )
            }
            
            SttProvider.IBM_WATSON -> {
                ApiKeyInputField(
                    label = stringResource(R.string.stt_api_key),
                    value = settings.ibmApiKey,
                    onValueChange = { onSettingsChange(settings.copy(ibmApiKey = it)) }
                )
                ApiKeyInputField(
                    label = stringResource(R.string.stt_service_url),
                    value = settings.ibmServiceUrl,
                    onValueChange = { onSettingsChange(settings.copy(ibmServiceUrl = it)) },
                    isPassword = false,
                    placeholder = "https://api.us-south.speech-to-text.watson.cloud.ibm.com"
                )
            }
            
            SttProvider.IFLYTEK -> {
                ApiKeyInputField(
                    label = stringResource(R.string.stt_app_id),
                    value = settings.iflytekAppId,
                    onValueChange = { onSettingsChange(settings.copy(iflytekAppId = it)) },
                    isPassword = false
                )
                ApiKeyInputField(
                    label = stringResource(R.string.stt_api_key),
                    value = settings.iflytekApiKey,
                    onValueChange = { onSettingsChange(settings.copy(iflytekApiKey = it)) }
                )
                ApiKeyInputField(
                    label = stringResource(R.string.stt_api_secret),
                    value = settings.iflytekApiSecret,
                    onValueChange = { onSettingsChange(settings.copy(iflytekApiSecret = it)) }
                )
            }
            
            SttProvider.HUAWEI_SIS -> {
                ApiKeyInputField(
                    label = stringResource(R.string.stt_access_key),
                    value = settings.huaweiAk,
                    onValueChange = { onSettingsChange(settings.copy(huaweiAk = it)) }
                )
                ApiKeyInputField(
                    label = stringResource(R.string.stt_secret_key),
                    value = settings.huaweiSk,
                    onValueChange = { onSettingsChange(settings.copy(huaweiSk = it)) }
                )
                ApiKeyInputField(
                    label = stringResource(R.string.stt_project_id),
                    value = settings.huaweiProjectId,
                    onValueChange = { onSettingsChange(settings.copy(huaweiProjectId = it)) },
                    isPassword = false
                )
                ApiKeyInputField(
                    label = stringResource(R.string.stt_region),
                    value = settings.huaweiRegion,
                    onValueChange = { onSettingsChange(settings.copy(huaweiRegion = it)) },
                    isPassword = false,
                    placeholder = "cn-north-4"
                )
            }
            
            SttProvider.VOLCENGINE -> {
                ApiKeyInputField(
                    label = stringResource(R.string.stt_access_key),
                    value = settings.volcengineAk,
                    onValueChange = { onSettingsChange(settings.copy(volcengineAk = it)) }
                )
                ApiKeyInputField(
                    label = stringResource(R.string.stt_secret_key),
                    value = settings.volcangineSk,
                    onValueChange = { onSettingsChange(settings.copy(volcangineSk = it)) }
                )
                ApiKeyInputField(
                    label = stringResource(R.string.stt_app_id),
                    value = settings.volcengineAppId,
                    onValueChange = { onSettingsChange(settings.copy(volcengineAppId = it)) },
                    isPassword = false
                )
            }
            
            SttProvider.ALIBABA_ASR -> {
                ApiKeyInputField(
                    label = stringResource(R.string.stt_access_key),
                    value = settings.aliyunAccessKeyId,
                    onValueChange = { onSettingsChange(settings.copy(aliyunAccessKeyId = it)) }
                )
                ApiKeyInputField(
                    label = stringResource(R.string.stt_secret_key),
                    value = settings.aliyunAccessKeySecret,
                    onValueChange = { onSettingsChange(settings.copy(aliyunAccessKeySecret = it)) }
                )
                ApiKeyInputField(
                    label = stringResource(R.string.stt_app_id),
                    value = settings.aliyunAppKey,
                    onValueChange = { onSettingsChange(settings.copy(aliyunAppKey = it)) },
                    isPassword = false,
                    placeholder = "NLS AppKey"
                )
            }
            
            SttProvider.TENCENT_ASR -> {
                ApiKeyInputField(
                    label = "Secret ID",
                    value = settings.tencentSecretId,
                    onValueChange = { onSettingsChange(settings.copy(tencentSecretId = it)) }
                )
                ApiKeyInputField(
                    label = stringResource(R.string.stt_secret_key),
                    value = settings.tencentSecretKey,
                    onValueChange = { onSettingsChange(settings.copy(tencentSecretKey = it)) }
                )
                ApiKeyInputField(
                    label = stringResource(R.string.stt_app_id),
                    value = settings.tencentAppId,
                    onValueChange = { onSettingsChange(settings.copy(tencentAppId = it)) },
                    isPassword = false
                )
            }
            
            SttProvider.BAIDU_ASR -> {
                ApiKeyInputField(
                    label = stringResource(R.string.stt_api_key),
                    value = settings.baiduAsrApiKey,
                    onValueChange = { onSettingsChange(settings.copy(baiduAsrApiKey = it)) }
                )
                ApiKeyInputField(
                    label = stringResource(R.string.stt_secret_key),
                    value = settings.baiduAsrSecretKey,
                    onValueChange = { onSettingsChange(settings.copy(baiduAsrSecretKey = it)) }
                )
            }
            
            SttProvider.REV_AI -> {
                ApiKeyInputField(
                    label = "Access Token",
                    value = settings.revaiAccessToken,
                    onValueChange = { onSettingsChange(settings.copy(revaiAccessToken = it)) }
                )
            }
            
            SttProvider.SPEECHMATICS -> {
                ApiKeyInputField(
                    label = stringResource(R.string.stt_api_key),
                    value = settings.speechmaticsApiKey,
                    onValueChange = { onSettingsChange(settings.copy(speechmaticsApiKey = it)) }
                )
            }
            
            SttProvider.OTTER_AI -> {
                ApiKeyInputField(
                    label = stringResource(R.string.stt_api_key),
                    value = settings.otteraiApiKey,
                    onValueChange = { onSettingsChange(settings.copy(otteraiApiKey = it)) }
                )
            }
            
            else -> {
                // Fallback for any unhandled providers
                Text(
                    text = "Configure credentials for ${provider.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ApiKeyInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    isPassword: Boolean = true,
    placeholder: String = ""
) {
    var passwordVisible by remember { mutableStateOf(false) }
    
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = if (placeholder.isNotEmpty()) {{ Text(placeholder) }} else null,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (isPassword && !passwordVisible) 
            PasswordVisualTransformation() 
        else 
            VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Text),
        trailingIcon = if (isPassword) {
            {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = if (passwordVisible) "Hide" else "Show"
                    )
                }
            }
        } else null
    )
}

@Composable
fun SystemPromptDialog(
    currentPrompt: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var prompt by remember { mutableStateOf(currentPrompt) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.system_prompt)) },
        text = {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                placeholder = { Text(stringResource(R.string.enter_system_prompt)) }
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(prompt) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun LanguageSelectionDialog(
    currentLanguage: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_language)) },
        text = {
            LazyColumn {
                items(AppLanguage.entries.toList()) { language ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(language) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = language == currentLanguage,
                            onClick = { onSelect(language) }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = language.nativeName,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = language.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * Custom Provider Settings Section
 */
@Composable
fun CustomProviderSection(
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    modelName: String,
    onModelNameChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    onTestConnection: () -> Unit
) {
    var isValidUrl by remember(baseUrl) { 
        mutableStateOf(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))
    }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testSuccess by remember { mutableStateOf<Boolean?>(null) }
    val coroutineScope = rememberCoroutineScope()
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.custom_provider_settings),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))
            
            // Base URL field
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { 
                    onBaseUrlChange(it)
                    isValidUrl = it.startsWith("http://") || it.startsWith("https://")
                },
                label = { Text(stringResource(R.string.base_url)) },
                placeholder = { Text(stringResource(R.string.base_url_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = baseUrl.isNotBlank() && !isValidUrl,
                supportingText = {
                    if (baseUrl.isNotBlank() && !isValidUrl) {
                        Text(
                            text = stringResource(R.string.invalid_url),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Model Name field
            OutlinedTextField(
                value = modelName,
                onValueChange = onModelNameChange,
                label = { Text(stringResource(R.string.model_name)) },
                placeholder = { Text(stringResource(R.string.model_name_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // API Key field (optional for local models)
            var passwordVisible by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = { Text(stringResource(R.string.custom_api_key)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (passwordVisible) stringResource(R.string.hide) else stringResource(R.string.show)
                        )
                    }
                },
                supportingText = {
                    Text(
                        text = stringResource(R.string.api_key_optional),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Test Connection button
            Button(
                onClick = {
                    isTesting = true
                    testResult = null
                    testSuccess = null
                    coroutineScope.launch {
                        try {
                            val service = com.example.rokidphone.service.ai.OpenAiCompatibleService(
                                apiKey = apiKey,
                                baseUrl = baseUrl,
                                modelId = modelName.ifBlank { "llama4" },
                                providerType = AiProvider.CUSTOM
                            )
                            val result = service.testConnection()
                            testSuccess = result.isSuccess
                            testResult = result.getOrElse { it.message ?: "Unknown error" }
                        } catch (e: Exception) {
                            testSuccess = false
                            testResult = e.message ?: "Connection failed"
                        } finally {
                            isTesting = false
                        }
                    }
                },
                enabled = isValidUrl && !isTesting,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.testing_connection))
                } else {
                    Icon(Icons.Default.NetworkCheck, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.test_connection))
                }
            }
            
            // Test result
            testResult?.let { result ->
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (testSuccess == true)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (testSuccess == true) Icons.Default.CheckCircle else Icons.Default.Error,
                            contentDescription = null,
                            tint = if (testSuccess == true)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (testSuccess == true) 
                                stringResource(R.string.connection_success) 
                            else 
                                result,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

/**
 * Custom Model Input Dialog
 */
@Composable
fun CustomModelDialog(
    currentModelName: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var modelName by remember { mutableStateOf(currentModelName) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.custom_model_name)) },
        text = {
            OutlinedTextField(
                value = modelName,
                onValueChange = { modelName = it },
                label = { Text(stringResource(R.string.model_name)) },
                placeholder = { Text(stringResource(R.string.model_name_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(modelName) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
