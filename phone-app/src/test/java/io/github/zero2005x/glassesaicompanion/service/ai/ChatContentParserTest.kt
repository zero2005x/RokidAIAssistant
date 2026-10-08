package io.github.zero2005x.glassesaicompanion.service.ai

import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatContentParserTest {

    @Test
    fun `extractText - plain string preserves whitespace`() {
        assertThat(ChatContentParser.extractText("Hello world")).isEqualTo("Hello world")
        assertThat(ChatContentParser.extractText(" world")).isEqualTo(" world")
        assertThat(ChatContentParser.extractText("")).isNull()
    }

    @Test
    fun `extractText - null input returns null`() {
        assertThat(ChatContentParser.extractText(null)).isNull()
    }

    @Test
    fun `extractText - json array with single text block`() {
        val array = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", "Extracted answer")
            })
        }
        assertThat(ChatContentParser.extractText(array)).isEqualTo("Extracted answer")
    }

    @Test
    fun `extractText - json array with thinking and text blocks filters out thinking`() {
        val array = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "thinking")
                put("thinking", "Internal chain of thought")
            })
            put(JSONObject().apply {
                put("type", "text")
                put("text", "Final user visible answer")
            })
        }
        assertThat(ChatContentParser.extractText(array)).isEqualTo("Final user visible answer")
    }

    @Test
    fun `extractText - json array with multiple text blocks concatenates`() {
        val array = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", "Part 1. ")
            })
            put(JSONObject().apply {
                put("type", "text")
                put("text", "Part 2.")
            })
        }
        assertThat(ChatContentParser.extractText(array)).isEqualTo("Part 1. Part 2.")
    }

    @Test
    fun `extractText - raw strings and objects without explicit type are preserved`() {
        val array = JSONArray().apply {
            put("prefix ")
            put(JSONObject().apply { put("text", "body") })
            put(JSONObject().apply {
                put("type", "image")
                put("text", "ignored")
            })
        }

        assertThat(ChatContentParser.extractText(array)).isEqualTo("prefix body")
    }
}
