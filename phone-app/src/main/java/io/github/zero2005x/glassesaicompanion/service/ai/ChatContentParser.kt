package io.github.zero2005x.glassesaicompanion.service.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Utility to safely extract plain text from chat response content fields
 * which may be formatted as either a String or a JSONArray of content blocks.
 */
object ChatContentParser {

    /**
     * Extracts text from [content], which can be a String or a JSONArray.
     * When [content] is a JSONArray, filters for text blocks and concatenates their contents.
     * Preserves whitespace (important for streaming deltas).
     */
    fun extractText(content: Any?): String? {
        return when (content) {
            is String -> content.takeIf { it.isNotEmpty() }
            is JSONArray -> {
                val sb = StringBuilder()
                for (i in 0 until content.length()) {
                    val item = content.opt(i)
                    when (item) {
                        is String -> sb.append(item)
                        is JSONObject -> {
                            val type = item.optString("type", "")
                            if (type == "text" || type.isEmpty()) {
                                val text = item.opt("text") as? String ?: ""
                                if (text.isNotEmpty()) {
                                    sb.append(text)
                                }
                            }
                        }
                    }
                }
                sb.toString().takeIf { it.isNotEmpty() }
            }
            else -> null
        }
    }
}
