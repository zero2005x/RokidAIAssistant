package io.github.zero2005x.glassesaicompanion.data

/** The decision service is separate from the generative AI provider. */
enum class DecisionBackend { JEV, LAYA, GEMINI, OPENAI }

/** A user-pinned chat model for one difficulty tier. */
data class RoutingModel(val provider: AiProvider, val modelId: String)
