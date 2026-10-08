package io.github.zero2005x.glassesaicompanion.report

import org.json.JSONObject

/** Why the user is reporting an AI response. */
enum class ReportReason(val wireName: String) {
    HARMFUL("harmful_or_offensive"),
    SEXUAL("sexual_content"),
    MISLEADING("inaccurate_or_misleading"),
    PRIVACY("privacy_or_personal_data"),
    OTHER("other")
}

/** The AI response being reported, and (optionally) the message it answers. */
data class ReportTarget(
    val assistantContent: String,
    val userContent: String? = null,
    val modelId: String? = null
)

/**
 * What is sent to the developer when the user reports an AI response.
 *
 * Deliberately small: the reported response, the reason, an optional note, the user's message
 * only if they chose to include it, and technical context needed to reproduce the problem.
 * It never contains API keys, device identifiers, account names or other conversations.
 */
data class AiContentReport(
    val reason: ReportReason,
    val note: String,
    val assistantContent: String,
    val userContent: String?,
    val provider: String,
    val model: String,
    val appVersion: String,
    val distribution: String,
    val locale: String,
    val androidSdk: Int,
    val createdAtMillis: Long
) {

    /** The exact JSON body that is posted. Free-text fields are length-capped. */
    fun toJson(): String = JSONObject().apply {
        put("schema", SCHEMA_VERSION)
        put("reason", reason.wireName)
        put("note", note.take(MAX_NOTE_CHARS))
        put("assistantContent", assistantContent.take(MAX_CONTENT_CHARS))
        if (userContent != null) put("userContent", userContent.take(MAX_CONTENT_CHARS))
        put("provider", provider)
        put("model", model)
        put("appVersion", appVersion)
        put("distribution", distribution)
        put("locale", locale)
        put("androidSdk", androidSdk)
        put("createdAt", createdAtMillis)
    }.toString()

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_CONTENT_CHARS = 8_000
        const val MAX_NOTE_CHARS = 1_000
    }
}
