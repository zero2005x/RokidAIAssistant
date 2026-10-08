package io.github.zero2005x.glassesaicompanion.service.ai

/** Separates a failed chat from legacy services' human-readable fallback strings. */
interface ChatErrorSource {
    val lastChatError: String?
}
