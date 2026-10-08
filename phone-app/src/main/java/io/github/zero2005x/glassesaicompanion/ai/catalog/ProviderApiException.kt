package io.github.zero2005x.glassesaicompanion.ai.catalog

import io.github.zero2005x.glassesaicompanion.data.log.LogRedactor

/**
 * Classified provider error. [message] is always sanitized: it never contains
 * API keys, Authorization headers or request secrets.
 */
enum class ProviderErrorKind {
    INVALID_API_KEY,
    PERMISSION_DENIED,
    MODEL_UNAVAILABLE,
    MODEL_DEPRECATED,
    RATE_LIMIT,
    QUOTA_EXCEEDED,
    REGION_MISMATCH,
    INVALID_REQUEST,
    UNSUPPORTED_IMAGE,
    CONTEXT_TOO_LONG,
    NETWORK_UNAVAILABLE,
    TIMEOUT,
    SERVICE_UNAVAILABLE,
    CANCELLED,
    UNKNOWN
}

class ProviderApiException(
    val kind: ProviderErrorKind,
    val httpStatus: Int? = null,
    val providerErrorCode: String? = null,
    val retryAfterMs: Long? = null,
    safeMessage: String
) : Exception(safeMessage) {

    val isRetryable: Boolean
        get() = when (kind) {
            ProviderErrorKind.RATE_LIMIT,
            ProviderErrorKind.SERVICE_UNAVAILABLE,
            ProviderErrorKind.NETWORK_UNAVAILABLE,
            ProviderErrorKind.TIMEOUT -> true
            else -> false
        }

    companion object {
        private const val MAX_MESSAGE_LENGTH = 500

        /** Upper clamp for Retry-After so absurd/overflowing values cannot stall retries. */
        private const val MAX_RETRY_AFTER_SECONDS = 3600L

        /** Remove anything that looks like a credential from an arbitrary message. */
        fun sanitize(raw: String?): String {
            val nonNull = raw ?: return "Unknown provider error"
            if (nonNull.isBlank()) return "Unknown provider error"
            return LogRedactor.redact(nonNull).take(MAX_MESSAGE_LENGTH)
        }

        fun fromHttpStatus(
            status: Int,
            responseBody: String?,
            retryAfterHeader: String? = null
        ): ProviderApiException {
            // Parse the body once and share it between code/message extraction.
            val parsedBody = parseJsonObject(responseBody)
            val providerCode = extractProviderCode(parsedBody)
            val rawMessage = extractProviderMessage(parsedBody)
            val kind = when (status) {
                401 -> ProviderErrorKind.INVALID_API_KEY
                403 -> when {
                    rawMessage?.contains("region", ignoreCase = true) == true -> ProviderErrorKind.REGION_MISMATCH
                    rawMessage?.contains("permission", ignoreCase = true) == true -> ProviderErrorKind.PERMISSION_DENIED
                    rawMessage?.contains("country", ignoreCase = true) == true -> ProviderErrorKind.REGION_MISMATCH
                    else -> ProviderErrorKind.PERMISSION_DENIED
                }
                404 -> ProviderErrorKind.MODEL_UNAVAILABLE
                400 -> when {
                    rawMessage?.contains("deprecated", ignoreCase = true) == true -> ProviderErrorKind.MODEL_DEPRECATED
                    (rawMessage?.contains("context", ignoreCase = true) == true &&
                        rawMessage.contains("length", ignoreCase = true)) ||
                        rawMessage?.contains("maximum context", ignoreCase = true) == true ||
                        rawMessage?.contains("too many tokens", ignoreCase = true) == true ->
                        ProviderErrorKind.CONTEXT_TOO_LONG
                    rawMessage?.contains("image", ignoreCase = true) == true -> ProviderErrorKind.UNSUPPORTED_IMAGE
                    else -> ProviderErrorKind.INVALID_REQUEST
                }
                413 -> ProviderErrorKind.UNSUPPORTED_IMAGE
                429 -> when {
                    rawMessage?.contains("quota", ignoreCase = true) == true -> ProviderErrorKind.QUOTA_EXCEEDED
                    else -> ProviderErrorKind.RATE_LIMIT
                }
                408 -> ProviderErrorKind.TIMEOUT
                in 500..599 -> ProviderErrorKind.SERVICE_UNAVAILABLE
                else -> ProviderErrorKind.UNKNOWN
            }
            val retryAfterMs = retryAfterHeader?.trim()?.toLongOrNull()
                ?.coerceIn(0L, MAX_RETRY_AFTER_SECONDS)
                ?.times(1000L)
            return ProviderApiException(
                kind = kind,
                httpStatus = status,
                providerErrorCode = providerCode,
                retryAfterMs = retryAfterMs,
                safeMessage = buildMessage(kind, status, providerCode, rawMessage)
            )
        }

        private fun buildMessage(
            kind: ProviderErrorKind,
            status: Int,
            providerCode: String?,
            rawMessage: String?
        ): String {
            val base = when (kind) {
                ProviderErrorKind.INVALID_API_KEY -> "Invalid API key"
                ProviderErrorKind.PERMISSION_DENIED -> "Permission denied"
                ProviderErrorKind.MODEL_UNAVAILABLE -> "Model unavailable"
                ProviderErrorKind.MODEL_DEPRECATED -> "Model deprecated"
                ProviderErrorKind.RATE_LIMIT -> "Rate limit reached"
                ProviderErrorKind.QUOTA_EXCEEDED -> "Quota exceeded"
                ProviderErrorKind.REGION_MISMATCH -> "Region mismatch"
                ProviderErrorKind.INVALID_REQUEST -> "Invalid request parameter"
                ProviderErrorKind.UNSUPPORTED_IMAGE -> "Unsupported image"
                ProviderErrorKind.CONTEXT_TOO_LONG -> "Context too long"
                ProviderErrorKind.NETWORK_UNAVAILABLE -> "Network unavailable"
                ProviderErrorKind.TIMEOUT -> "Request timed out"
                ProviderErrorKind.SERVICE_UNAVAILABLE -> "Provider service unavailable"
                ProviderErrorKind.CANCELLED -> "Request cancelled"
                ProviderErrorKind.UNKNOWN -> "Provider error"
            }
            val detail = sanitize(rawMessage)
            val codePart = providerCode?.let { " [$it]" } ?: ""
            return "$base (HTTP $status$codePart): $detail"
        }

        private fun parseJsonObject(body: String?): org.json.JSONObject? {
            if (body.isNullOrBlank()) return null
            return try {
                org.json.JSONObject(body)
            } catch (_: Exception) {
                null
            }
        }

        private fun extractProviderMessage(json: org.json.JSONObject?): String? {
            if (json == null) return null
            return try {
                json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: json.optString("message").takeIf { it.isNotBlank() }
                    ?: json.optString("error_msg").takeIf { it.isNotBlank() }
                    // Restrict to actual string values: optString("error") would
                    // splice the whole error object into the message via toString().
                    ?: (json.opt("error") as? String)?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }

        /** String/numeric scalar field or null for absent/null/object values. */
        private fun scalarField(json: org.json.JSONObject, key: String): String? {
            if (json.isNull(key)) return null
            return when (val value = json.opt(key)) {
                is String -> value.takeIf { it.isNotBlank() }
                is Number -> value.toString()
                else -> null
            }
        }

        private fun extractProviderCode(json: org.json.JSONObject?): String? {
            if (json == null) return null
            return try {
                json.optJSONObject("error")?.let { scalarField(it, "code") }
                    ?: scalarField(json, "code")
                    ?: scalarField(json, "error_code")?.takeIf { it != "0" }
            } catch (_: Exception) {
                null
            }
        }
    }
}
