package io.github.zero2005x.glassesaicompanion.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zero2005x.glassesaicompanion.R
import io.github.zero2005x.glassesaicompanion.ai.provider.AnythingLLMProvider
import io.github.zero2005x.glassesaicompanion.ai.provider.ProviderSetting
import io.github.zero2005x.glassesaicompanion.ai.provider.ValidationResult
import io.github.zero2005x.glassesaicompanion.data.*
import io.github.zero2005x.glassesaicompanion.service.ai.AiServiceFactory
import io.github.zero2005x.glassesaicompanion.service.ServiceBridge
import com.example.rokidcommon.protocol.GlassesDisplayConfig
import com.example.rokidcommon.protocol.GlassesFont
import com.example.rokidcommon.protocol.Message
import com.example.rokidcommon.protocol.MessageType
import androidx.compose.ui.text.font.FontFamily
import io.github.zero2005x.glassesaicompanion.service.stt.SttProvider
import io.github.zero2005x.glassesaicompanion.service.stt.SttServiceFactory
import kotlinx.coroutines.launch

private const val URL_SCHEME_HTTP = "http://"
private const val URL_SCHEME_HTTPS = "https://"

private fun isHttpUrl(url: String): Boolean =
    url.startsWith(URL_SCHEME_HTTP) || url.startsWith(URL_SCHEME_HTTPS)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: ApiSettings,
    onSettingsChange: (ApiSettings) -> Unit,
    onBack: () -> Unit,
    onNavigateToLogViewer: () -> Unit = {},
    onNavigateToLlmParameters: () -> Unit = {},
    onNavigateToTtsSettings: () -> Unit = {},
    onTestConnection: (ApiSettings) -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val catalogRepository = remember {
        io.github.zero2005x.glassesaicompanion.ai.catalog.ModelCatalogRepository(
            remote = io.github.zero2005x.glassesaicompanion.ai.catalog.RemoteModelCatalogSource(),
            cache = io.github.zero2005x.glassesaicompanion.ai.catalog.SharedPrefsModelCatalogCache(context.applicationContext),
            localSource = io.github.zero2005x.glassesaicompanion.ai.catalog.LocalModelCatalogSource(
                modelDirProvider = {
                    // App-private model directory (no storage permission needed).
                    java.io.File(context.applicationContext.filesDir, "models/gemma")
                }
            )
        )
    }
    var detailPage by remember { mutableStateOf<String?>(null) }
    androidx.activity.compose.BackHandler(enabled = detailPage != null) { detailPage = null }
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
                title = { Text(stringResource(when (detailPage) {
                    "routing" -> R.string.routing_title
                    "display" -> R.string.glasses_display_title
                    else -> R.string.api_settings
                })) },
                navigationIcon = {
                    IconButton(onClick = { if (detailPage != null) detailPage = null else onBack() }) {
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
            if (detailPage != null) {
                item {
                    if (detailPage == "routing") DecisionRoutingSection(settings, onSettingsChange, catalogRepository)
                    else GlassesDisplaySettingsSection(settings, onSettingsChange)
                }
            } else {
            // Secure storage failure warning (never silently falls back to plaintext)
            item {
                val secureStorageError by SettingsRepository.getInstance(context)
                    .secureStorageError.collectAsState()
                secureStorageError?.let { errorText ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(text = errorText)
                        }
                    }
                }
            }

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
                    
                    // Model selection (per-provider memory; live/cache/fallback/manual catalog)
                    val selectedModelId = settings.getCurrentModelId()
                    val currentModel = AvailableModels.findModel(selectedModelId)
                    SettingsRow(
                        title = stringResource(R.string.ai_model),
                        subtitle = if (settings.aiProvider == AiProvider.CUSTOM)
                            settings.customModelName.ifBlank { "custom" }
                        else
                            currentModel?.displayName ?: selectedModelId,
                        onClick = { showModelDialog = true }
                    )
                }
            }
            
            item {
                SettingsSection(title = stringResource(R.string.routing_display_settings)) {
                    SettingsRow(title = stringResource(R.string.routing_title),
                        subtitle = stringResource(R.string.routing_open_summary), onClick = { detailPage = "routing" })
                    HorizontalDivider()
                    SettingsRow(title = stringResource(R.string.glasses_display_title),
                        subtitle = stringResource(R.string.glasses_display_open_summary), onClick = { detailPage = "display" })
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
                        onTestConnection = { onTestConnection(settings) },
                        protocol = settings.customProtocol,
                        onProtocolChange = { onSettingsChange(settings.copy(customProtocol = it)) },
                        modelsPath = settings.customModelsPath,
                        onModelsPathChange = { onSettingsChange(settings.copy(customModelsPath = it)) },
                        capabilityOverrides = settings.customCapabilityOverrides,
                        onCapabilityOverridesChange = { onSettingsChange(settings.copy(customCapabilityOverrides = it)) }
                    )
                }
            }

            // AnythingLLM Settings (only shown for ANYTHINGLLM provider)
            if (settings.aiProvider == AiProvider.ANYTHINGLLM) {
                item {
                    AnythingLLMProviderSection(
                        serverUrl = settings.anythingllmServerUrl,
                        onServerUrlChange = { onSettingsChange(settings.copy(anythingllmServerUrl = it)) },
                        apiKey = settings.anythingllmApiKey,
                        onApiKeyChange = { onSettingsChange(settings.copy(anythingllmApiKey = it)) },
                        workspaceSlug = settings.anythingllmWorkspaceSlug,
                        onWorkspaceSlugChange = { onSettingsChange(settings.copy(anythingllmWorkspaceSlug = it)) }
                    )
                }
            }

            // API Key settings section (for non-custom, non-AnythingLLM,
            // non-local providers — the on-device provider needs no key)
            if (settings.aiProvider != AiProvider.CUSTOM &&
                settings.aiProvider != AiProvider.ANYTHINGLLM &&
                settings.aiProvider != AiProvider.LOCAL_GEMMA) {
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
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = stringResource(R.string.alibaba_region),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    AlibabaRegions.all.take(3).forEach { region ->
                                        FilterChip(
                                            selected = settings.alibabaRegion == region,
                                            onClick = { onSettingsChange(settings.copy(alibabaRegion = region)) },
                                            label = { Text(region.replaceFirstChar { it.uppercase() }) }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    AlibabaRegions.all.drop(3).forEach { region ->
                                        FilterChip(
                                            selected = settings.alibabaRegion == region,
                                            onClick = { onSettingsChange(settings.copy(alibabaRegion = region)) },
                                            label = { Text(region.replaceFirstChar { it.uppercase() }) }
                                        )
                                    }
                                }
                                if (settings.alibabaRegion == AlibabaRegions.CUSTOM) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = settings.alibabaCustomBaseUrl,
                                        onValueChange = { onSettingsChange(settings.copy(alibabaCustomBaseUrl = it)) },
                                        label = { Text(stringResource(R.string.alibaba_workspace_base_url)) },
                                        placeholder = { Text("https://.../compatible-mode/v1/") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = settings.getCurrentBaseUrl(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                // Qianfan v2: single bearer API key (preferred)
                                ApiKeyField(
                                    label = stringResource(R.string.baidu_qianfan_api_key),
                                    value = settings.baiduQianfanApiKey,
                                    onValueChange = { onSettingsChange(settings.copy(baiduQianfanApiKey = it)) },
                                    isActive = !settings.isBaiduLegacyMode()
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                SettingsRowWithSwitch(
                                    title = stringResource(R.string.baidu_legacy_auth),
                                    subtitle = stringResource(R.string.baidu_legacy_auth_subtitle),
                                    checked = settings.isBaiduLegacyMode(),
                                    onCheckedChange = { onSettingsChange(settings.copy(baiduUseLegacyAuth = it)) }
                                )
                                if (settings.isBaiduLegacyMode()) {
                                    Spacer(modifier = Modifier.height(8.dp))
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
                            }
                            AiProvider.PERPLEXITY -> {
                                ApiKeyField(
                                    label = stringResource(R.string.perplexity_api_key),
                                    value = settings.perplexityApiKey,
                                    onValueChange = { onSettingsChange(settings.copy(perplexityApiKey = it)) },
                                    isActive = true
                                )
                            }
                            AiProvider.MOONSHOT -> {
                                ApiKeyField(
                                    label = stringResource(R.string.moonshot_api_key),
                                    value = settings.moonshotApiKey,
                                    onValueChange = { onSettingsChange(settings.copy(moonshotApiKey = it)) },
                                    isActive = true
                                )
                            }
                            AiProvider.MISTRAL -> {
                                ApiKeyField(
                                    label = stringResource(R.string.mistral_api_key),
                                    value = settings.mistralApiKey,
                                    onValueChange = { onSettingsChange(settings.copy(mistralApiKey = it)) },
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
                        title = stringResource(R.string.llm_parameters),
                        subtitle = stringResource(R.string.llm_parameters_subtitle),
                        onClick = onNavigateToLlmParameters,
                        icon = Icons.Default.Tune
                    )
                    
                    HorizontalDivider()
                    
                    SettingsRow(
                        title = stringResource(R.string.tts_settings_title),
                        subtitle = stringResource(R.string.tts_settings_subtitle),
                        onClick = onNavigateToTtsSettings,
                        icon = Icons.AutoMirrored.Filled.VolumeUp
                    )
                    
                    HorizontalDivider()
                    
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
                    SettingsRowWithSwitch(
                        title = stringResource(R.string.push_chat_to_glasses),
                        subtitle = stringResource(R.string.push_chat_to_glasses_description),
                        checked = settings.pushChatToGlasses,
                        onCheckedChange = { onSettingsChange(settings.copy(pushChatToGlasses = it)) }
                    )
                    SettingsRowWithSwitch(
                        title = stringResource(R.string.push_recording_to_glasses),
                        subtitle = stringResource(R.string.push_recording_to_glasses_description),
                        checked = settings.pushRecordingToGlasses,
                        onCheckedChange = { onSettingsChange(settings.copy(pushRecordingToGlasses = it)) }
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

            // Support section (not shown in the Google Play build)
            if (distribution.showDonationLink) {
                item {
                    SettingsSection(title = "Support") {
                        DonationButton(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            }
        }
    }
    
    // Dialogs
    if (showProviderDialog) {
        ProviderSelectionDialog(
            currentProvider = settings.aiProvider,
            onSelect = { provider ->
                // Restore the model the user last picked for this provider.
                onSettingsChange(settings.copy(
                    aiProvider = provider,
                    aiModelId = settings.getModelIdForProvider(provider)
                ))
                showProviderDialog = false
            },
            onDismiss = { showProviderDialog = false },
            isConfigured = { settings.isProviderConfigured(it) }
        )
    }

    if (showModelDialog) {
        ModelCatalogDialog(
            provider = settings.aiProvider,
            currentModelId = settings.getCurrentModelId(),
            apiKey = settings.getCurrentApiKey(),
            baseUrl = when (settings.aiProvider) {
                AiProvider.CUSTOM -> settings.customBaseUrl
                AiProvider.ALIBABA -> settings.getCurrentBaseUrl()
                else -> null
            },
            repository = catalogRepository,
            onSelect = { modelId ->
                onSettingsChange(settings.withModelForProvider(settings.aiProvider, modelId))
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
                var updatedSettings = settings.copy(
                    speechLanguage = newSpeechLanguage,
                    responseLanguage = newSpeechLanguage
                )
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GlassesDisplaySettingsSection(
    settings: ApiSettings,
    onSettingsChange: (ApiSettings) -> Unit
) {
    var draft by remember(settings.glassesDisplayConfig) {
        mutableStateOf(settings.glassesDisplayConfig.normalized())
    }
    val scope = rememberCoroutineScope()
    SettingsSection(title = stringResource(R.string.glasses_display_title)) {
        Text(stringResource(R.string.glasses_display_description),
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        GlassesDisplayPreview(draft.normalized(), settings.glassesDisplayMetrics)
        Text(stringResource(R.string.glasses_preview_disclaimer), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.glasses_font), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassesFont.entries.forEach { font ->
                FilterChip(
                    selected = draft.font == font,
                    onClick = { draft = draft.copy(font = font) },
                    label = { Text(if (font == GlassesFont.SYSTEM) stringResource(R.string.glasses_font_system) else stringResource(R.string.glasses_font_monospace)) }
                )
            }
        }
        DisplayConfigSlider(stringResource(R.string.glasses_font_size), draft.fontSizeSp, 12..36, "sp") {
            draft = draft.copy(fontSizeSp = it).normalized()
        }
        DisplayConfigSlider(stringResource(R.string.glasses_width), draft.widthPercent, 30..94) {
            draft = draft.copy(widthPercent = it).normalized()
        }
        DisplayConfigSlider(stringResource(R.string.glasses_height), draft.heightPercent, 30..94) {
            draft = draft.copy(heightPercent = it).normalized()
        }
        DisplayConfigSlider(stringResource(R.string.glasses_left), draft.leftPercent, 3..(97 - draft.widthPercent)) {
            draft = draft.copy(leftPercent = it).normalized()
        }
        DisplayConfigSlider(stringResource(R.string.glasses_top), draft.topPercent, 3..(97 - draft.heightPercent)) {
            draft = draft.copy(topPercent = it).normalized()
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { draft = GlassesDisplayConfig() }) { Text(stringResource(R.string.glasses_reset)) }
            Button(
                enabled = draft != settings.glassesDisplayConfig,
                onClick = {
                    val applied = draft.normalized()
                    onSettingsChange(settings.copy(glassesDisplayConfig = applied))
                    scope.launch {
                        ServiceBridge.sendToGlasses(Message(
                            type = MessageType.SYSTEM_CONFIG,
                            payload = applied.toJson()
                        ))
                    }
                }
            ) { Text(stringResource(R.string.glasses_apply)) }
        }
    }
}

@Composable
private fun DisplayConfigSlider(label: String, value: Int, range: IntRange, unit: String = "%", onChange: (Int) -> Unit) {
    Text(stringResource(R.string.glasses_slider_value, label, value, unit),
        style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = value.toFloat(),
        onValueChange = { onChange(it.toInt().coerceIn(range)) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun DecisionRoutingSection(
    settings: ApiSettings,
    onSettingsChange: (ApiSettings) -> Unit,
    repository: io.github.zero2005x.glassesaicompanion.ai.catalog.ModelCatalogRepository
) {
    var pickingDecisionModel by remember { mutableStateOf(false) }
    var editingSlot by remember { mutableStateOf<Int?>(null) }
    SettingsSection(title = stringResource(R.string.routing_title)) {
        SettingsRowWithSwitch(
            title = stringResource(R.string.routing_enabled),
            subtitle = stringResource(R.string.routing_description),
            checked = settings.decisionRoutingEnabled,
            onCheckedChange = { onSettingsChange(settings.copy(decisionRoutingEnabled = it)) }
        )
        if (settings.decisionRoutingEnabled) {
            Text(stringResource(R.string.routing_data_disclosure), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            DecisionBackendFields(settings, onSettingsChange) { pickingDecisionModel = true }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            RoutingSlotRows(settings, onSettingsChange) { editingSlot = it }
        }
    }
    if (pickingDecisionModel) {
        DecisionModelDialog(settings, repository, onSettingsChange) { pickingDecisionModel = false }
    }
    editingSlot?.let { slot ->
        RoutingSlotDialogs(slot, settings, repository, onSettingsChange) { editingSlot = null }
    }
}

private fun ApiSettings.decisionLlmProvider(): AiProvider =
    if (decisionBackend == DecisionBackend.GEMINI) AiProvider.GEMINI else AiProvider.OPENAI

private fun ApiSettings.decisionModelFor(provider: AiProvider): String =
    if (provider == AiProvider.GEMINI) decisionGeminiModel else decisionOpenaiModel

/** Slot 0 is the fast tier, 1 the balanced tier, anything else the quality tier. */
private fun ApiSettings.routingSlot(index: Int): RoutingModel? = when (index) {
    0 -> fastRoutingModel
    1 -> balancedRoutingModel
    else -> qualityRoutingModel
}

private fun ApiSettings.withRoutingSlot(index: Int, model: RoutingModel?): ApiSettings = when (index) {
    0 -> copy(fastRoutingModel = model)
    1 -> copy(balancedRoutingModel = model)
    else -> copy(qualityRoutingModel = model)
}

/** Backend choice and the fields that backend needs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DecisionBackendFields(
    settings: ApiSettings,
    onSettingsChange: (ApiSettings) -> Unit,
    onPickDecisionModel: () -> Unit
) {
    Text(stringResource(R.string.routing_backend), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DecisionBackend.entries.forEach { backend ->
            FilterChip(
                selected = settings.decisionBackend == backend,
                onClick = { onSettingsChange(settings.copy(decisionBackend = backend)) },
                label = { Text(backend.name) }
            )
        }
    }
    when (settings.decisionBackend) {
        DecisionBackend.JEV -> ApiKeyField(stringResource(R.string.routing_jev_key), settings.jevApiKey,
            { onSettingsChange(settings.copy(jevApiKey = it)) }, true)
        DecisionBackend.LAYA -> LayaFields(settings, onSettingsChange)
        DecisionBackend.GEMINI, DecisionBackend.OPENAI -> LlmDecisionFields(settings, onPickDecisionModel)
    }
}

@Composable
private fun LayaFields(settings: ApiSettings, onSettingsChange: (ApiSettings) -> Unit) {
    OutlinedTextField(
        value = settings.layaBaseUrl,
        onValueChange = { onSettingsChange(settings.copy(layaBaseUrl = it)) },
        label = { Text(stringResource(R.string.routing_laya_url)) },
        supportingText = { Text(stringResource(R.string.routing_laya_url_hint)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
    if (settings.layaBaseUrl.trim().startsWith(URL_SCHEME_HTTP)) {
        Text(stringResource(R.string.routing_laya_http_warning),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    ApiKeyField(stringResource(R.string.routing_laya_key), settings.layaApiKey,
        { onSettingsChange(settings.copy(layaApiKey = it)) }, true)
}

@Composable
private fun LlmDecisionFields(settings: ApiSettings, onPickDecisionModel: () -> Unit) {
    Text(stringResource(R.string.routing_llm_description), style = MaterialTheme.typography.bodySmall)
    val provider = settings.decisionLlmProvider()
    SettingsRow(title = stringResource(R.string.routing_decision_model),
        subtitle = settings.decisionModelFor(provider),
        onClick = onPickDecisionModel)
    if (!settings.isProviderConfigured(provider)) Text(stringResource(R.string.api_key_not_configured),
        color = MaterialTheme.colorScheme.error)
}

/** One row per difficulty tier, with a button to clear a tier that has a model pinned. */
@Composable
private fun RoutingSlotRows(
    settings: ApiSettings,
    onSettingsChange: (ApiSettings) -> Unit,
    onEdit: (Int) -> Unit
) {
    val names = listOf(
        stringResource(R.string.routing_fast),
        stringResource(R.string.routing_balanced),
        stringResource(R.string.routing_quality)
    )
    names.forEachIndexed { index, name ->
        val slot = settings.routingSlot(index)
        SettingsRow(
            title = name,
            subtitle = slot?.let { "${it.provider.name} / ${it.modelId}" } ?: stringResource(R.string.routing_unset),
            onClick = { onEdit(index) }
        )
        if (slot != null) {
            TextButton(onClick = { onSettingsChange(settings.withRoutingSlot(index, null)) }) {
                Text(stringResource(R.string.routing_clear_slot, name))
            }
        }
    }
}

@Composable
private fun DecisionModelDialog(
    settings: ApiSettings,
    repository: io.github.zero2005x.glassesaicompanion.ai.catalog.ModelCatalogRepository,
    onSettingsChange: (ApiSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val provider = settings.decisionLlmProvider()
    ModelCatalogDialog(
        provider = provider,
        currentModelId = settings.decisionModelFor(provider),
        apiKey = settings.getApiKeyForProvider(provider),
        baseUrl = null,
        repository = repository,
        onSelect = { model ->
            onSettingsChange(
                if (provider == AiProvider.GEMINI) settings.copy(decisionGeminiModel = model)
                else settings.copy(decisionOpenaiModel = model)
            )
            onDismiss()
        },
        onDismiss = onDismiss
    )
}

/** Pick a provider, then one of its models, for the tier at [index]. */
@Composable
private fun RoutingSlotDialogs(
    index: Int,
    settings: ApiSettings,
    repository: io.github.zero2005x.glassesaicompanion.ai.catalog.ModelCatalogRepository,
    onSettingsChange: (ApiSettings) -> Unit,
    onDismiss: () -> Unit
) {
    var pickedProvider by remember(index) { mutableStateOf(settings.routingSlot(index)?.provider ?: settings.aiProvider) }
    var pickingModel by remember(index) { mutableStateOf(false) }
    if (!pickingModel) {
        ProviderSelectionDialog(
            currentProvider = pickedProvider,
            availableProviders = AiProvider.entries.filter { it != AiProvider.GEMINI_LIVE },
            onSelect = { provider ->
                pickedProvider = provider
                pickingModel = true
            },
            onDismiss = onDismiss,
            isConfigured = { settings.isProviderConfigured(it) }
        )
    } else {
        ModelCatalogDialog(
            provider = pickedProvider,
            currentModelId = settings.routingSlot(index)?.modelId ?: settings.getModelIdForProvider(pickedProvider),
            apiKey = settings.getApiKeyForProvider(pickedProvider),
            baseUrl = when (pickedProvider) {
                AiProvider.CUSTOM -> settings.customBaseUrl
                AiProvider.ALIBABA -> settings.copy(aiProvider = pickedProvider).getCurrentBaseUrl()
                else -> null
            },
            repository = repository,
            onSelect = { modelId ->
                onSettingsChange(settings.withRoutingSlot(index, RoutingModel(pickedProvider, modelId)))
                onDismiss()
            },
            onDismiss = onDismiss
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
    onDismiss: () -> Unit,
    isConfigured: (AiProvider) -> Boolean = { false },
    availableProviders: List<AiProvider> = AiProvider.entries
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_ai_provider)) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
            ) {
                // The distribution (Play vs GitHub) decides which providers may be offered at all
                items(availableProviders.filter { it in distribution.aiProviders() }, key = { it.name }) { provider ->
                    val descriptor = io.github.zero2005x.glassesaicompanion.ai.catalog.ProviderRegistry.descriptorFor(provider)
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
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(provider.displayNameResId))
                                if (isConfigured(provider)) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = stringResource(R.string.configured),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                            Text(
                                text = descriptor.protocol.name.replace('_', ' ').lowercase()
                                    .replaceFirstChar { it.uppercase() },
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
 * Catalog-driven model picker.
 *
 * Shows the four-tier catalog (live → cache → fallback → manual) with search,
 * refresh, source/status/capability labels and a manual model-ID entry.
 * The current selection is never removed even when missing from the list.
 */
@Composable
fun ModelCatalogDialog(
    provider: AiProvider,
    currentModelId: String,
    apiKey: String,
    baseUrl: String?,
    repository: io.github.zero2005x.glassesaicompanion.ai.catalog.ModelCatalogRepository,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var snapshot by remember(provider) {
        mutableStateOf(repository.getCachedOrFallback(provider))
    }
    var isLoading by remember(provider) { mutableStateOf(false) }
    var searchQuery by remember(provider) { mutableStateOf("") }
    var manualModelId by remember(provider) { mutableStateOf("") }

    fun refresh(force: Boolean) {
        coroutineScope.launch {
            isLoading = true
            try {
                snapshot = repository.getCatalog(
                    provider = provider,
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    forceRefresh = force
                )
            } finally {
                isLoading = false
            }
        }
    }

    // Initial load: serve cache/fallback instantly, then try a live refresh.
    LaunchedEffect(provider) {
        if (apiKey.isNotBlank()) refresh(force = false)
    }

    val selectionMissing = repository.isSelectionAbsent(snapshot, currentModelId)
    val filteredModels = remember(snapshot, searchQuery) {
        if (searchQuery.isBlank()) snapshot.models
        else snapshot.models.filter {
            it.id.contains(searchQuery, ignoreCase = true) ||
                it.displayName.contains(searchQuery, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.select_model), modifier = Modifier.weight(1f))
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { refresh(force = true) }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.refresh_models)
                        )
                    }
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Catalog source + verification info
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SourceChip(snapshot.source)
                    snapshot.remoteError?.let {
                        Text(
                            text = stringResource(R.string.remote_unavailable),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                if (selectionMissing) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            R.string.model_not_in_catalog,
                            currentModelId,
                            catalogSourceLabel(snapshot.source)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.search_models)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
                )

                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                ) {
                    items(filteredModels, key = { it.id }) { model ->
                        ModelCatalogRow(
                            model = model,
                            isSelected = model.id == currentModelId,
                            onSelect = onSelect
                        )
                    }
                    if (filteredModels.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.no_models_match, searchQuery),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        }
                    }
                }

                // Manual model ID entry — always available, never blocked by the catalog.
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = manualModelId,
                        onValueChange = { manualModelId = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.custom_model_id)) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = { if (manualModelId.isNotBlank()) onSelect(manualModelId.trim()) },
                        enabled = manualModelId.isNotBlank()
                    ) {
                        Text(stringResource(R.string.use_model_id))
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
private fun ModelCatalogRow(
    model: io.github.zero2005x.glassesaicompanion.ai.catalog.ModelInfo,
    isSelected: Boolean,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(model.id) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = isSelected, onClick = { onSelect(model.id) })
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(model.displayName)
                Spacer(modifier = Modifier.width(8.dp))
                StatusChip(model.status)
            }
            if (model.description.isNotBlank()) {
                Text(
                    text = model.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            model.capabilities.maxContextTokens?.let { tokens ->
                val formatted = if (tokens >= 1_000_000L) "${tokens / 1_000_000}M" else "${tokens / 1000}K"
                Text(
                    text = stringResource(R.string.context_tokens, formatted),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Only the capabilities the model actually supports.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                val caps = model.capabilities
                if (caps.imageInput) {
                    CapabilityBadge(
                        icon = Icons.Default.Image,
                        text = stringResource(R.string.supports_vision),
                        isSupported = true
                    )
                }
                if (caps.audioInput) {
                    CapabilityBadge(
                        icon = Icons.Default.Mic,
                        text = stringResource(R.string.supports_audio),
                        isSupported = true
                    )
                }
                if (caps.streaming) {
                    CapabilityBadge(
                        icon = Icons.Default.Stream,
                        text = stringResource(R.string.cap_stream),
                        isSupported = true
                    )
                }
                if (caps.toolCalling) {
                    CapabilityBadge(
                        icon = Icons.Default.Build,
                        text = stringResource(R.string.cap_tools),
                        isSupported = true
                    )
                }
                if (caps.reasoning) {
                    CapabilityBadge(
                        icon = Icons.Default.Psychology,
                        text = stringResource(R.string.cap_reasoning),
                        isSupported = true
                    )
                }
                if (caps.realtime) {
                    CapabilityBadge(
                        icon = Icons.Default.Bolt,
                        text = stringResource(R.string.cap_realtime),
                        isSupported = true
                    )
                }
            }
        }
    }
}

@Composable
private fun catalogSourceLabel(source: io.github.zero2005x.glassesaicompanion.ai.catalog.CatalogSource): String =
    when (source) {
        io.github.zero2005x.glassesaicompanion.ai.catalog.CatalogSource.LIVE -> stringResource(R.string.source_live)
        io.github.zero2005x.glassesaicompanion.ai.catalog.CatalogSource.CACHED -> stringResource(R.string.source_cached)
        io.github.zero2005x.glassesaicompanion.ai.catalog.CatalogSource.FALLBACK -> stringResource(
            R.string.source_fallback,
            io.github.zero2005x.glassesaicompanion.ai.catalog.FallbackModelCatalog.LAST_VERIFIED_DATE
        )
        io.github.zero2005x.glassesaicompanion.ai.catalog.CatalogSource.MANUAL -> stringResource(R.string.source_manual)
    }

@Composable
private fun SourceChip(source: io.github.zero2005x.glassesaicompanion.ai.catalog.CatalogSource) {
    val color = when (source) {
        io.github.zero2005x.glassesaicompanion.ai.catalog.CatalogSource.LIVE -> MaterialTheme.colorScheme.primaryContainer
        io.github.zero2005x.glassesaicompanion.ai.catalog.CatalogSource.CACHED -> MaterialTheme.colorScheme.secondaryContainer
        io.github.zero2005x.glassesaicompanion.ai.catalog.CatalogSource.FALLBACK -> MaterialTheme.colorScheme.tertiaryContainer
        io.github.zero2005x.glassesaicompanion.ai.catalog.CatalogSource.MANUAL -> MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(shape = MaterialTheme.shapes.small, color = color) {
        Text(
            text = catalogSourceLabel(source),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun StatusChip(status: io.github.zero2005x.glassesaicompanion.ai.catalog.ModelStatus) {
    when (status) {
        io.github.zero2005x.glassesaicompanion.ai.catalog.ModelStatus.STABLE -> Unit // no chip for the common case
        io.github.zero2005x.glassesaicompanion.ai.catalog.ModelStatus.PREVIEW -> PreviewBadge()
        io.github.zero2005x.glassesaicompanion.ai.catalog.ModelStatus.DEPRECATED -> SmallStatusChip(
            stringResource(R.string.status_deprecated), MaterialTheme.colorScheme.errorContainer
        )
        io.github.zero2005x.glassesaicompanion.ai.catalog.ModelStatus.LEGACY -> SmallStatusChip(
            stringResource(R.string.status_legacy), MaterialTheme.colorScheme.surfaceVariant
        )
        io.github.zero2005x.glassesaicompanion.ai.catalog.ModelStatus.UNKNOWN -> Unit // unrecognized status: no chip
    }
}

@Composable
private fun SmallStatusChip(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(shape = MaterialTheme.shapes.small, color = color) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/**
 * Badge to indicate a Preview / experimental model
 */
@Composable
private fun PreviewBadge() {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
    ) {
        Text(
            text = stringResource(R.string.preview_badge),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
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
    val implementedProviders = remember { distribution.sttProviders(SttServiceFactory.getImplementedProviders()) }
    
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
    onTestConnection: () -> Unit,
    protocol: String = "chat_completions",
    onProtocolChange: (String) -> Unit = {},
    modelsPath: String = "models",
    onModelsPathChange: (String) -> Unit = {},
    capabilityOverrides: Set<String> = emptySet(),
    onCapabilityOverridesChange: (Set<String>) -> Unit = {}
) {
    var isValidUrl by remember(baseUrl) { 
        mutableStateOf(isAllowedEndpointUrl(baseUrl))
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
                    isValidUrl = isAllowedEndpointUrl(it)
                },
                label = { Text(stringResource(R.string.base_url)) },
                placeholder = { Text(stringResource(R.string.base_url_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = baseUrl.isNotBlank() && !isValidUrl,
                supportingText = {
                    if (baseUrl.isNotBlank() && !isValidUrl) {
                        Text(
                            text = stringResource(
                                if (baseUrl.trim().startsWith(URL_SCHEME_HTTP, ignoreCase = true)) {
                                    R.string.custom_url_https_required
                                } else {
                                    R.string.invalid_url
                                }
                            ),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )

            // Cleartext HTTP warning for local endpoints
            if (baseUrl.startsWith(URL_SCHEME_HTTP)) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.custom_cleartext_warning),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Wire protocol selector
            Text(
                text = stringResource(R.string.custom_protocol),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = protocol == "chat_completions",
                    onClick = { onProtocolChange("chat_completions") },
                    label = { Text(stringResource(R.string.protocol_chat_completions)) }
                )
                FilterChip(
                    selected = protocol == "responses",
                    onClick = { onProtocolChange("responses") },
                    label = { Text(stringResource(R.string.protocol_responses)) }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Models endpoint path
            OutlinedTextField(
                value = modelsPath,
                onValueChange = onModelsPathChange,
                label = { Text(stringResource(R.string.custom_models_path)) },
                placeholder = { Text("models") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Manual capability override: the catalog cannot know what a
            // local endpoint's model supports, so the user declares it.
            SettingsRowWithSwitch(
                title = stringResource(R.string.custom_supports_vision),
                subtitle = stringResource(R.string.custom_supports_vision_subtitle),
                checked = io.github.zero2005x.glassesaicompanion.ai.catalog.ModelCapabilityResolver.OVERRIDE_VISION in capabilityOverrides,
                onCheckedChange = { checked ->
                    val key = io.github.zero2005x.glassesaicompanion.ai.catalog.ModelCapabilityResolver.OVERRIDE_VISION
                    onCapabilityOverridesChange(
                        if (checked) capabilityOverrides + key else capabilityOverrides - key
                    )
                }
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
                            val service = io.github.zero2005x.glassesaicompanion.service.ai.OpenAiCompatibleService(
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
 * AnythingLLM Provider Settings Section
 */
private data class AnythingLlmConnectionState(
    val success: Boolean,
    val message: String? = null
)

@Composable
fun AnythingLLMProviderSection(
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    workspaceSlug: String,
    onWorkspaceSlugChange: (String) -> Unit
) {
    var isValidUrl by remember(serverUrl) {
        mutableStateOf(isHttpUrl(serverUrl))
    }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testSuccess by remember { mutableStateOf<Boolean?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val invalidUrlText = stringResource(R.string.invalid_url)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.anythingllm_provider_settings),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))

            AnythingLlmServerUrlField(
                serverUrl = serverUrl,
                isValidUrl = isValidUrl,
                onServerUrlChange = {
                    onServerUrlChange(it)
                    isValidUrl = isHttpUrl(it)
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            ApiKeyInputField(
                label = stringResource(R.string.anythingllm_api_key),
                value = apiKey,
                onValueChange = onApiKeyChange
            )

            Spacer(modifier = Modifier.height(12.dp))

            AnythingLlmWorkspaceField(
                value = workspaceSlug,
                onValueChange = onWorkspaceSlugChange,
            )

            Spacer(modifier = Modifier.height(16.dp))

            AnythingLlmConnectionButton(
                isTesting = isTesting,
                onClick = {
                    isTesting = true
                    testResult = null
                    testSuccess = null
                    coroutineScope.launch {
                        val result = testAnythingLlmConnection(
                            serverUrl = serverUrl,
                            apiKey = apiKey,
                            workspaceSlug = workspaceSlug,
                            isValidUrl = isValidUrl,
                            invalidUrlText = invalidUrlText
                        )
                        testSuccess = result.success
                        testResult = result.message
                        isTesting = false
                    }
                }
            )

            if (testSuccess != null || testResult != null) {
                AnythingLlmConnectionResult(
                    success = testSuccess == true,
                    message = testResult
                )
            }
        }
    }
}

@Composable
private fun AnythingLlmServerUrlField(
    serverUrl: String,
    isValidUrl: Boolean,
    onServerUrlChange: (String) -> Unit
) {
    OutlinedTextField(
        value = serverUrl,
        onValueChange = onServerUrlChange,
        label = { Text(stringResource(R.string.anythingllm_server_url)) },
        placeholder = { Text(stringResource(R.string.anythingllm_server_url_hint)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = serverUrl.isNotBlank() && !isValidUrl,
        supportingText = {
            if (serverUrl.isNotBlank() && !isValidUrl) {
                Text(stringResource(R.string.invalid_url), color = MaterialTheme.colorScheme.error)
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
    )
}

@Composable
private fun AnythingLlmWorkspaceField(
    value: String,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.anythingllm_workspace_slug)) },
        placeholder = { Text(stringResource(R.string.anythingllm_workspace_slug_hint)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
    )
}

@Composable
private fun AnythingLlmConnectionButton(
    isTesting: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = !isTesting,
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
}

@Composable
private fun AnythingLlmConnectionResult(
    success: Boolean,
    message: String?
) {
    Spacer(modifier = Modifier.height(12.dp))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = if (success) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (success) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                tint = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (success) stringResource(R.string.connection_success) else message.orEmpty(),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

private suspend fun testAnythingLlmConnection(
    serverUrl: String,
    apiKey: String,
    workspaceSlug: String,
    isValidUrl: Boolean,
    invalidUrlText: String
): AnythingLlmConnectionState {
    if (serverUrl.isNotBlank() && !isValidUrl) {
        return AnythingLlmConnectionState(success = false, message = invalidUrlText)
    }

    val providerSetting = ProviderSetting.AnythingLLM(
        serverUrl = serverUrl,
        apiKey = apiKey,
        workspaceSlug = workspaceSlug
    )

    return when (val result = AnythingLLMProvider().validateSetting(providerSetting)) {
        is ValidationResult.Valid -> AnythingLlmConnectionState(success = true)
        is ValidationResult.Invalid -> AnythingLlmConnectionState(success = false, message = result.reason)
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
