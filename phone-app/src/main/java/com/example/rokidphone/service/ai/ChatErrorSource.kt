package com.example.rokidphone.service.ai

/** Separates a failed chat from legacy services' human-readable fallback strings. */
interface ChatErrorSource {
    val lastChatError: String?
}
