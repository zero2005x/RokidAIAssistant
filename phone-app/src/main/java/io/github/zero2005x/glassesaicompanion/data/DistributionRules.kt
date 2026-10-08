package io.github.zero2005x.glassesaicompanion.data

import io.github.zero2005x.glassesaicompanion.BuildConfig
import io.github.zero2005x.glassesaicompanion.service.stt.SttProvider
import java.net.URI

/**
 * What a given distribution channel of the app is allowed to offer.
 *
 * The same code base ships through two channels:
 * - **GitHub** (sideloaded APK): everything the project supports.
 * - **Google Play**: a curated set of providers that have an official, documented API,
 *   on-device text-to-speech only, no donation link and no permission-gated system tools.
 *
 * The rules are a plain class (not read straight from `BuildConfig`) so that unit tests can
 * exercise both channels regardless of which flavor is being built.
 */
class DistributionRules(val isPlay: Boolean) {

    /** AI providers selectable in the Play build. */
    private val playAiProviders = setOf(
        AiProvider.GEMINI,
        AiProvider.OPENAI,
        AiProvider.ANTHROPIC,
        AiProvider.CUSTOM
    )

    /** Speech-to-text providers selectable in the Play build. */
    private val playSttProviders = setOf(
        SttProvider.GEMINI,
        SttProvider.OPENAI_WHISPER,
        SttProvider.GROQ_WHISPER
    )

    /** Text-to-speech engines selectable in the Play build (no unofficial endpoints). */
    private val playTtsProviders = setOf(TtsProvider.SYSTEM_TTS)

    /** Engine used on a fresh install. */
    val defaultTtsProvider: TtsProvider =
        if (isPlay) TtsProvider.SYSTEM_TTS else TtsProvider.EDGE_TTS

    /** Whether the in-app donation link is shown. */
    val showDonationLink: Boolean = !isPlay

    /**
     * Calendar and contact tools need runtime permissions that are not declared in the
     * manifest, so they cannot work; the Play build does not advertise them to the model.
     */
    val permissionGatedToolsEnabled: Boolean = !isPlay

    fun aiProviders(): List<AiProvider> =
        if (isPlay) AiProvider.entries.filter { it in playAiProviders } else AiProvider.entries.toList()

    fun sttProviders(candidates: List<SttProvider>): List<SttProvider> =
        if (isPlay) candidates.filter { it in playSttProviders } else candidates

    fun ttsProviders(): List<TtsProvider> =
        if (isPlay) TtsProvider.entries.filter { it in playTtsProviders } else TtsProvider.entries.toList()

    /**
     * Replace stored selections this channel does not offer with the channel defaults.
     * Always a no-op for GitHub; protects the Play build from stale or restored settings.
     */
    fun coerce(settings: ApiSettings): ApiSettings {
        if (!isPlay) return settings
        return settings.copy(
            aiProvider = if (settings.aiProvider in playAiProviders) settings.aiProvider else AiProvider.GEMINI,
            sttProvider = if (settings.sttProvider in playSttProviders) settings.sttProvider else SttProvider.GEMINI,
            ttsProvider = if (settings.ttsProvider in playTtsProviders) settings.ttsProvider else defaultTtsProvider
        )
    }
}

/** The rules of the channel this build was produced for. */
val distribution: DistributionRules = DistributionRules(BuildConfig.PLAY_DISTRIBUTION)

/**
 * A custom endpoint must use HTTPS. Plain HTTP is accepted only for loopback hosts
 * (a server running on this very phone, e.g. Ollama in Termux), because the app's network
 * security configuration blocks cleartext traffic to every other host anyway.
 */
fun isAllowedEndpointUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (trimmed.startsWith("https://", ignoreCase = true)) {
        return runCatching { URI(trimmed).host }.getOrNull()?.isNotBlank() == true
    }
    if (!trimmed.startsWith("http://", ignoreCase = true)) return false
    val host = runCatching { URI(trimmed).host }.getOrNull()?.lowercase() ?: return false
    return host == "localhost" || host == "127.0.0.1"
}
