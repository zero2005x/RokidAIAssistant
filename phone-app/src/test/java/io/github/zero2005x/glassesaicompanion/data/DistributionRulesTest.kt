package io.github.zero2005x.glassesaicompanion.data

import com.google.common.truth.Truth.assertThat
import io.github.zero2005x.glassesaicompanion.service.ai.ToolDeclarations
import io.github.zero2005x.glassesaicompanion.service.stt.SttProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Both distribution channels are exercised on purpose, whichever flavor is being built,
 * so the Google Play restrictions are always covered.
 */
@RunWith(RobolectricTestRunner::class)
class DistributionRulesTest {

    private val play = DistributionRules(isPlay = true)
    private val github = DistributionRules(isPlay = false)

    // ==================== Providers ====================

    @Test
    fun `play offers only providers with an official API`() {
        assertThat(play.aiProviders()).containsExactly(
            AiProvider.GEMINI,
            AiProvider.OPENAI,
            AiProvider.ANTHROPIC,
            AiProvider.CUSTOM
        )
    }

    @Test
    fun `play excludes Gemini Live and AnythingLLM and the other providers`() {
        assertThat(play.aiProviders()).containsNoneOf(
            AiProvider.GEMINI_LIVE,
            AiProvider.ANYTHINGLLM,
            AiProvider.DEEPSEEK,
            AiProvider.LOCAL_GEMMA
        )
    }

    @Test
    fun `github keeps every provider`() {
        assertThat(github.aiProviders()).containsExactlyElementsIn(AiProvider.entries)
    }

    @Test
    fun `play speech providers are Gemini and the two Whisper services`() {
        val all = SttProvider.entries.toList()
        assertThat(play.sttProviders(all)).containsExactly(
            SttProvider.GEMINI,
            SttProvider.OPENAI_WHISPER,
            SttProvider.GROQ_WHISPER
        )
        assertThat(github.sttProviders(all)).containsExactlyElementsIn(all)
    }

    @Test
    fun `play offers system TTS only and github keeps all engines`() {
        assertThat(play.ttsProviders()).containsExactly(TtsProvider.SYSTEM_TTS)
        assertThat(github.ttsProviders()).containsExactlyElementsIn(TtsProvider.entries)
    }

    @Test
    fun `default TTS engine differs per channel`() {
        assertThat(play.defaultTtsProvider).isEqualTo(TtsProvider.SYSTEM_TTS)
        assertThat(github.defaultTtsProvider).isEqualTo(TtsProvider.EDGE_TTS)
    }

    // ==================== Channel switches ====================

    @Test
    fun `donation link and permission-gated tools are github only`() {
        assertThat(play.showDonationLink).isFalse()
        assertThat(play.permissionGatedToolsEnabled).isFalse()
        assertThat(github.showDonationLink).isTrue()
        assertThat(github.permissionGatedToolsEnabled).isTrue()
    }

    // ==================== coerce ====================

    @Test
    fun `coerce replaces selections the play channel does not offer`() {
        val stale = ApiSettings(
            aiProvider = AiProvider.GEMINI_LIVE,
            sttProvider = SttProvider.DEEPGRAM,
            ttsProvider = TtsProvider.EDGE_TTS
        )

        val coerced = play.coerce(stale)

        assertThat(coerced.aiProvider).isEqualTo(AiProvider.GEMINI)
        assertThat(coerced.sttProvider).isEqualTo(SttProvider.GEMINI)
        assertThat(coerced.ttsProvider).isEqualTo(TtsProvider.SYSTEM_TTS)
    }

    @Test
    fun `coerce keeps allowed selections and never touches credentials`() {
        val settings = ApiSettings(
            aiProvider = AiProvider.ANTHROPIC,
            sttProvider = SttProvider.GROQ_WHISPER,
            ttsProvider = TtsProvider.SYSTEM_TTS,
            anthropicApiKey = "sk-keep-me"
        )

        assertThat(play.coerce(settings)).isEqualTo(settings)
    }

    @Test
    fun `coerce is a no-op for github`() {
        val settings = ApiSettings(
            aiProvider = AiProvider.GEMINI_LIVE,
            sttProvider = SttProvider.DEEPGRAM,
            ttsProvider = TtsProvider.EDGE_TTS
        )

        assertThat(github.coerce(settings)).isSameInstanceAs(settings)
    }

    // ==================== Tool declarations ====================

    @Test
    fun `permission-gated tools are not declared when disabled`() {
        val names = declaredNames(ToolDeclarations.enabledDeclarations(permissionGatedToolsEnabled = false))

        assertThat(names).containsExactly("execute", "search", "make_call")
    }

    @Test
    fun `make_call has no contact_name parameter when contacts are unavailable`() {
        val makeCall = ToolDeclarations.enabledDeclarations(permissionGatedToolsEnabled = false)
            .map { it.getJSONArray("function_declarations").getJSONObject(0) }
            .first { it.getString("name") == "make_call" }

        val properties = makeCall.getJSONObject("parameters").getJSONObject("properties")

        assertThat(properties.has("phone_number")).isTrue()
        assertThat(properties.has("contact_name")).isFalse()
    }

    @Test
    fun `all tools are declared when permission-gated tools are enabled`() {
        val names = declaredNames(ToolDeclarations.enabledDeclarations(permissionGatedToolsEnabled = true))

        assertThat(names).containsExactly("execute", "search", "check_schedule", "make_call")
    }

    private fun declaredNames(declarations: List<org.json.JSONObject>): List<String> =
        declarations.map { it.getJSONArray("function_declarations").getJSONObject(0).getString("name") }

    // ==================== Custom endpoint URLs ====================

    @Test
    fun `https endpoints are allowed`() {
        assertThat(isAllowedEndpointUrl("https://example.com/v1/")).isTrue()
        assertThat(isAllowedEndpointUrl("  HTTPS://api.example.com:8443/v1  ")).isTrue()
    }

    @Test
    fun `plain http is allowed for loopback only`() {
        assertThat(isAllowedEndpointUrl("http://localhost:11434/v1/")).isTrue()
        assertThat(isAllowedEndpointUrl("http://127.0.0.1:11434/v1/")).isTrue()

        assertThat(isAllowedEndpointUrl("http://192.168.1.20:11434/v1/")).isFalse()
        assertThat(isAllowedEndpointUrl("http://example.com/v1/")).isFalse()
        assertThat(isAllowedEndpointUrl("http://localhost.evil.example/v1/")).isFalse()
    }

    @Test
    fun `non http schemes and garbage are rejected`() {
        assertThat(isAllowedEndpointUrl("")).isFalse()
        assertThat(isAllowedEndpointUrl("ftp://example.com")).isFalse()
        assertThat(isAllowedEndpointUrl("example.com/v1")).isFalse()
        assertThat(isAllowedEndpointUrl("https://")).isFalse()
    }

    @Test
    fun `userinfo cannot smuggle a loopback host onto a remote server`() {
        assertThat(isAllowedEndpointUrl("http://localhost@evil.example/v1/")).isFalse()
    }
}
