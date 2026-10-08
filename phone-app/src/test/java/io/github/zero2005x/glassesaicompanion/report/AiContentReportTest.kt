package io.github.zero2005x.glassesaicompanion.report

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AiContentReportTest {

    private fun report(
        reason: ReportReason = ReportReason.HARMFUL,
        note: String = "rude",
        assistant: String = "the response",
        user: String? = null
    ) = AiContentReport(
        reason = reason,
        note = note,
        assistantContent = assistant,
        userContent = user,
        provider = "GEMINI",
        model = "gemini-2.5-flash",
        appVersion = "1.2.0 (1)",
        distribution = "play",
        locale = "zh-TW",
        androidSdk = 35,
        createdAtMillis = 1_700_000_000_000L
    )

    @Test
    fun `json carries the reported response and the technical context`() {
        val json = JSONObject(report().toJson())

        assertThat(json.getInt("schema")).isEqualTo(AiContentReport.SCHEMA_VERSION)
        assertThat(json.getString("reason")).isEqualTo("harmful_or_offensive")
        assertThat(json.getString("note")).isEqualTo("rude")
        assertThat(json.getString("assistantContent")).isEqualTo("the response")
        assertThat(json.getString("provider")).isEqualTo("GEMINI")
        assertThat(json.getString("model")).isEqualTo("gemini-2.5-flash")
        assertThat(json.getString("appVersion")).isEqualTo("1.2.0 (1)")
        assertThat(json.getString("distribution")).isEqualTo("play")
        assertThat(json.getString("locale")).isEqualTo("zh-TW")
        assertThat(json.getInt("androidSdk")).isEqualTo(35)
        assertThat(json.getLong("createdAt")).isEqualTo(1_700_000_000_000L)
    }

    @Test
    fun `the user's own message is left out unless they chose to include it`() {
        assertThat(JSONObject(report(user = null).toJson()).has("userContent")).isFalse()

        val withPrompt = JSONObject(report(user = "my question").toJson())
        assertThat(withPrompt.getString("userContent")).isEqualTo("my question")
    }

    @Test
    fun `the payload has exactly the documented fields and nothing identifying`() {
        val keys = JSONObject(report(user = "q").toJson()).keys().asSequence().toSet()

        assertThat(keys).containsExactly(
            "schema", "reason", "note", "assistantContent", "userContent", "provider", "model",
            "appVersion", "distribution", "locale", "androidSdk", "createdAt"
        )
    }

    @Test
    fun `free text fields are capped`() {
        val huge = "x".repeat(AiContentReport.MAX_CONTENT_CHARS * 3)
        val json = JSONObject(
            report(note = "n".repeat(AiContentReport.MAX_NOTE_CHARS * 3), assistant = huge, user = huge).toJson()
        )

        assertThat(json.getString("note")).hasLength(AiContentReport.MAX_NOTE_CHARS)
        assertThat(json.getString("assistantContent")).hasLength(AiContentReport.MAX_CONTENT_CHARS)
        assertThat(json.getString("userContent")).hasLength(AiContentReport.MAX_CONTENT_CHARS)
    }

    @Test
    fun `every reason has a distinct stable wire name`() {
        val names = ReportReason.entries.map { it.wireName }

        assertThat(names).containsNoDuplicates()
        assertThat(names).containsExactly(
            "harmful_or_offensive",
            "sexual_content",
            "inaccurate_or_misleading",
            "privacy_or_personal_data",
            "other"
        )
    }
}