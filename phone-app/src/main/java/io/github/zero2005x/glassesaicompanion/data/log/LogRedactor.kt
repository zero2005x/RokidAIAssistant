package io.github.zero2005x.glassesaicompanion.data.log

/**
 * Removes secrets from text before it is logged, shown in the log viewer or exported.
 *
 * One implementation is shared by the log manager and by provider error handling, so a
 * credential format that is added here is covered everywhere.
 */
object LogRedactor {

    private const val MASK = "***"

    private class Rule(val regex: Regex, val replacement: (MatchResult) -> String)

    /**
     * Order matters: the scheme rules (`Bearer`, `Basic`) run before the generic
     * `Authorization:` header rule, which then only has to mask what is left of the value.
     */
    private val rules = listOf(
        Rule(Regex("Bearer\\s+[A-Za-z0-9._\\-]+")) { MASK },
        Rule(Regex("Basic\\s+[A-Za-z0-9+/=]+")) { MASK },
        // OpenAI style, including sk-proj-... and Anthropic sk-ant-...
        Rule(Regex("sk-[A-Za-z0-9._\\-]+")) { MASK },
        // Google API keys
        Rule(Regex("AIza[0-9A-Za-z_\\-]+")) { MASK },
        // Google API keys in the newer AQ. format
        Rule(Regex("AQ\\.[0-9A-Za-z._\\-]+")) { MASK },
        // JWTs (header.payload.signature)
        Rule(Regex("eyJ[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]*")) { MASK },
        // Credentials passed in a query string or form body: keep the parameter name.
        // This is a redaction pattern, not a credential.
        Rule(
            Regex(
                "\\b(?:api[_-]?key|apikey|key|access[_-]?token|token|secret|password)=[a-z0-9._\\-]+",
                RegexOption.IGNORE_CASE
            )
        ) { m -> m.value.substringBefore('=') + "=" + MASK },
        // JSON credential fields: "api_key"/"secret"/"password"/...: "..."
        // Keep the field name and mask the value; an empty value has nothing to mask.
        Rule(
            Regex(
                "\\\"(?:api[_-]?key|secret(?:[_-]?key)?|access[_-]?token|token|password)\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"",
                RegexOption.IGNORE_CASE
            )
        ) { m ->
            val value = m.groupValues[1]
            if (value.isEmpty()) m.value else m.value.substringBeforeLast(value) + MASK + "\""
        },
        // Header style: x-goog-api-key: value, x-api-key: value, Authorization: value
        Rule(
            Regex(
                "(x-goog-api-key|x-api-key|api-key|proxy-authorization|authorization)\\s*[:=]\\s*[^\\s,;\\\"']+",
                RegexOption.IGNORE_CASE
            )
        ) { m -> m.groupValues[1] + ": " + MASK }
    )

    /** Mask every credential-looking value in [raw]. Everything else is left untouched. */
    fun redact(raw: String): String {
        var out = raw
        for (rule in rules) {
            out = rule.regex.replace(out) { m -> rule.replacement(m) }
        }
        return out
    }

    /**
     * A short, single-line, credential-free excerpt of [raw] for log statements that need to
     * mention a server answer (for example an error body) without copying it wholesale.
     */
    fun snippet(raw: String?, maxChars: Int = 200): String {
        if (raw.isNullOrBlank()) return "(empty)"
        val oneLine = redact(raw).replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= maxChars) oneLine else oneLine.take(maxChars) + "…"
    }
}
