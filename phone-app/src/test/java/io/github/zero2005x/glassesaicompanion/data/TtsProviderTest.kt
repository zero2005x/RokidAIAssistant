package io.github.zero2005x.glassesaicompanion.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TtsProviderTest {

    // ==================== fromName ====================

    @Test
    fun `fromName - exact match returns correct provider`() {
        // 測試：精確名稱應回傳對應的 TtsProvider
        assertThat(TtsProvider.fromName("EDGE_TTS")).isEqualTo(TtsProvider.EDGE_TTS)
        assertThat(TtsProvider.fromName("SYSTEM_TTS")).isEqualTo(TtsProvider.SYSTEM_TTS)
        assertThat(TtsProvider.fromName("GOOGLE_TRANSLATE_TTS")).isEqualTo(TtsProvider.GOOGLE_TRANSLATE_TTS)
    }

    @Test
    fun `fromName - case insensitive matching`() {
        // 測試：大小寫不敏感應正確解析
        assertThat(TtsProvider.fromName("edge_tts")).isEqualTo(TtsProvider.EDGE_TTS)
        assertThat(TtsProvider.fromName("Edge_Tts")).isEqualTo(TtsProvider.EDGE_TTS)
        assertThat(TtsProvider.fromName("system_tts")).isEqualTo(TtsProvider.SYSTEM_TTS)
        assertThat(TtsProvider.fromName("google_translate_tts")).isEqualTo(TtsProvider.GOOGLE_TRANSLATE_TTS)
    }

    @Test
    fun `fromName - null returns EDGE_TTS`() {
        // 測試：null 輸入應預設回傳 EDGE_TTS
        assertThat(TtsProvider.fromName(null)).isEqualTo(TtsProvider.EDGE_TTS)
    }

    @Test
    fun `fromName - blank string returns EDGE_TTS`() {
        // 測試：空白字串應預設回傳 EDGE_TTS
        assertThat(TtsProvider.fromName("")).isEqualTo(TtsProvider.EDGE_TTS)
        assertThat(TtsProvider.fromName("   ")).isEqualTo(TtsProvider.EDGE_TTS)
    }

    @Test
    fun `fromName - unrecognised name returns EDGE_TTS`() {
        // 測試：無法識別的名稱應預設回傳 EDGE_TTS
        assertThat(TtsProvider.fromName("INVALID_PROVIDER")).isEqualTo(TtsProvider.EDGE_TTS)
        assertThat(TtsProvider.fromName("amazon_polly")).isEqualTo(TtsProvider.EDGE_TTS)
    }

    // ==================== entries / enum properties ====================

    @Test
    fun `entries contains exactly three providers`() {
        // 測試：TtsProvider 應有三個列舉值
        assertThat(TtsProvider.entries).hasSize(3)
    }

    @Test
    fun `each provider has non-zero resource ids`() {
        // 測試：每個 provider 的顯示名稱和描述資源 ID 皆不為 0
        TtsProvider.entries.forEach { provider ->
            assertThat(provider.displayNameResId).isNotEqualTo(0)
            assertThat(provider.descriptionResId).isNotEqualTo(0)
        }
    }

    @Test
    fun `name property round-trips through fromName`() {
        // 測試：name 屬性透過 fromName 應能正確反向解析
        TtsProvider.entries.forEach { provider ->
            assertThat(TtsProvider.fromName(provider.name)).isEqualTo(provider)
        }
    }
}
