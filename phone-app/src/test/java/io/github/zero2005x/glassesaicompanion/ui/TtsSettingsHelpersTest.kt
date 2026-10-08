package io.github.zero2005x.glassesaicompanion.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

/**
 * Unit tests for the internal helper functions in TtsSettingsScreen.kt:
 *   - formatEdgeRate()
 *   - formatEdgePitch()
 *   - detectEdgeVoice()
 */
class TtsSettingsHelpersTest {

    // ==================== formatEdgeRate ====================

    @Test
    fun `formatEdgeRate - default 1x produces +0 percent`() {
        // 測試：1.0 倍速應產生 "+0%"
        assertThat(formatEdgeRate(1.0f)).isEqualTo("+0%")
    }

    @Test
    fun `formatEdgeRate - faster speed produces positive percent`() {
        // 測試：加速應產生正百分比
        assertThat(formatEdgeRate(1.5f)).isEqualTo("+50%")
        assertThat(formatEdgeRate(2.0f)).isEqualTo("+100%")
    }

    @Test
    fun `formatEdgeRate - slower speed produces negative percent`() {
        // 測試：減速應產生負百分比
        assertThat(formatEdgeRate(0.5f)).isEqualTo("-50%")
        assertThat(formatEdgeRate(0.75f)).isEqualTo("-25%")
    }

    @Test
    fun `formatEdgeRate - moderate speed offset`() {
        // 測試：適度速度偏移（注意浮點數精度）
        // 1.2f - 1.0f = 0.19999… → toInt() = 19
        assertThat(formatEdgeRate(1.25f)).isEqualTo("+25%")
        assertThat(formatEdgeRate(0.75f)).isEqualTo("-25%")
    }

    // ==================== formatEdgePitch ====================

    @Test
    fun `formatEdgePitch - zero offset produces +0Hz`() {
        // 測試：0.0 偏移應產生 "+0Hz"
        assertThat(formatEdgePitch(0.0f)).isEqualTo("+0Hz")
    }

    @Test
    fun `formatEdgePitch - positive offset produces positive Hz`() {
        // 測試：正偏移應產生正 Hz 值
        assertThat(formatEdgePitch(0.5f)).isEqualTo("+30Hz")
        assertThat(formatEdgePitch(0.25f)).isEqualTo("+15Hz")
    }

    @Test
    fun `formatEdgePitch - negative offset produces negative Hz`() {
        // 測試：負偏移應產生負 Hz 值
        assertThat(formatEdgePitch(-0.5f)).isEqualTo("-30Hz")
        assertThat(formatEdgePitch(-0.25f)).isEqualTo("-15Hz")
    }

    @Test
    fun `formatEdgePitch - small offset`() {
        // 測試：小偏移值
        assertThat(formatEdgePitch(0.1f)).isEqualTo("+6Hz")
        assertThat(formatEdgePitch(-0.1f)).isEqualTo("-6Hz")
    }

    // ==================== detectEdgeVoice ====================

    @Test
    fun `detectEdgeVoice - Korean text selects Korean voice`() {
        // 測試：韓語文字應選取韓語語音
        assertThat(detectEdgeVoice("안녕하세요", null)).isEqualTo("ko-KR-SunHiNeural")
    }

    @Test
    fun `detectEdgeVoice - Chinese text selects Chinese voice`() {
        // 測試：中文文字應選取中文語音
        assertThat(detectEdgeVoice("你好世界", null)).isEqualTo("zh-CN-XiaoxiaoNeural")
    }

    @Test
    fun `detectEdgeVoice - Japanese Hiragana selects Japanese voice`() {
        // 測試：平假名應選取日語語音
        assertThat(detectEdgeVoice("こんにちは", null)).isEqualTo("ja-JP-NanamiNeural")
    }

    @Test
    fun `detectEdgeVoice - Japanese Katakana selects Japanese voice`() {
        // 測試：片假名應選取日語語音
        assertThat(detectEdgeVoice("コンニチハ", null)).isEqualTo("ja-JP-NanamiNeural")
    }

    @Test
    fun `detectEdgeVoice - English text selects English voice`() {
        // 測試：英文文字應選取英語語音
        assertThat(detectEdgeVoice("Hello world", null)).isEqualTo("en-US-JennyNeural")
    }

    @Test
    fun `detectEdgeVoice - Korean takes priority over Chinese in mixed text`() {
        // 測試：混合文字中韓語優先於中文
        assertThat(detectEdgeVoice("안녕 你好", null)).isEqualTo("ko-KR-SunHiNeural")
    }

    @Test
    fun `detectEdgeVoice - Chinese takes priority over Japanese in mixed CJK text`() {
        // 測試：混合文字中 CJK 統一碼優先於假名
        assertThat(detectEdgeVoice("世界こんにちは", null)).isEqualTo("zh-CN-XiaoxiaoNeural")
    }

    @Test
    fun `detectEdgeVoice - unknown script with Korean locale selects Korean voice`() {
        // 測試：未知腳本搭配韓語地區應選取韓語語音
        assertThat(detectEdgeVoice("١٢٣", Locale.KOREAN)).isEqualTo("ko-KR-SunHiNeural")
    }

    @Test
    fun `detectEdgeVoice - unknown script with Japanese locale selects Japanese voice`() {
        // 測試：未知腳本搭配日語地區應選取日語語音
        assertThat(detectEdgeVoice("١٢٣", Locale.JAPANESE)).isEqualTo("ja-JP-NanamiNeural")
    }

    @Test
    fun `detectEdgeVoice - unknown script with Chinese locale selects Chinese voice`() {
        // 測試：未知腳本搭配中文地區應選取中文語音
        assertThat(detectEdgeVoice("١٢٣", Locale.CHINESE)).isEqualTo("zh-CN-XiaoxiaoNeural")
    }

    @Test
    fun `detectEdgeVoice - unknown script with null locale defaults to English`() {
        // 測試：未知腳本且無地區資訊應預設英語語音
        assertThat(detectEdgeVoice("١٢٣", null)).isEqualTo("en-US-JennyNeural")
    }

    @Test
    fun `detectEdgeVoice - unknown script with French locale defaults to English`() {
        // 測試：未知腳本搭配不支持的地區應預設英語語音
        assertThat(detectEdgeVoice("١٢٣", Locale.FRENCH)).isEqualTo("en-US-JennyNeural")
    }

    @Test
    fun `detectEdgeVoice - empty text with null locale yields English`() {
        // 測試：空文字且無地區資訊應回傳英語語音
        assertThat(detectEdgeVoice("", null)).isEqualTo("en-US-JennyNeural")
    }

    @Test
    fun `detectEdgeVoice - empty text with Korean locale yields Korean`() {
        // 測試：空文字搭配韓語地區應回傳韓語語音
        assertThat(detectEdgeVoice("", Locale.KOREAN)).isEqualTo("ko-KR-SunHiNeural")
    }
}
