package com.example.rokidphone.service.ai

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for ToolCallModels.kt:
 * - ToolCallStatus enum
 * - GeminiFunctionCall (data class + companion)
 * - GeminiToolCall (data class + companion)
 * - ToolResult (factory methods + toResponseJson)
 * - ToolDeclarations (allDeclarations)
 */
@RunWith(RobolectricTestRunner::class)
class ToolCallModelsTest {

    // ==================== ToolCallStatus ====================

    @Test
    fun `ToolCallStatus - has all five expected values`() {
        val values = ToolCallStatus.values()
        assertThat(values).asList().containsExactly(
            ToolCallStatus.PENDING,
            ToolCallStatus.IN_PROGRESS,
            ToolCallStatus.COMPLETED,
            ToolCallStatus.FAILED,
            ToolCallStatus.CANCELLED
        )
    }

    @Test
    fun `GeminiFunctionCall - default status is PENDING`() {
        val call = GeminiFunctionCall(id = "1", name = "execute", args = JSONObject())
        assertThat(call.status).isEqualTo(ToolCallStatus.PENDING)
    }

    // ==================== GeminiFunctionCall.getTaskDescription ====================

    @Test
    fun `getTaskDescription - returns null when task key absent`() {
        val call = GeminiFunctionCall(id = "1", name = "execute", args = JSONObject())
        assertThat(call.getTaskDescription()).isNull()
    }

    @Test
    fun `getTaskDescription - returns null when task is blank`() {
        val args = JSONObject().apply { put("task", "   ") }
        val call = GeminiFunctionCall(id = "1", name = "execute", args = args)
        assertThat(call.getTaskDescription()).isNull()
    }

    @Test
    fun `getTaskDescription - returns task string when present and non-blank`() {
        val args = JSONObject().apply { put("task", "turn on lights") }
        val call = GeminiFunctionCall(id = "1", name = "execute", args = args)
        assertThat(call.getTaskDescription()).isEqualTo("turn on lights")
    }

    @Test
    fun `getTaskDescription - returns trimmed task if present`() {
        val args = JSONObject().apply { put("task", "search weather") }
        val call = GeminiFunctionCall(id = "2", name = "execute", args = args)
        assertThat(call.getTaskDescription()).isEqualTo("search weather")
    }

    // ==================== GeminiFunctionCall.fromServiceToolCall ====================

    @Test
    fun `fromServiceToolCall - maps id name and args correctly`() {
        val serviceCall = GeminiLiveService.ToolCall(
            id = "abc-123",
            name = "search",
            args = JSONObject().apply { put("query", "weather today") }
        )
        val result = GeminiFunctionCall.fromServiceToolCall(serviceCall)
        assertThat(result.id).isEqualTo("abc-123")
        assertThat(result.name).isEqualTo("search")
        assertThat(result.args.getString("query")).isEqualTo("weather today")
    }

    @Test
    fun `fromServiceToolCall - status defaults to PENDING`() {
        val serviceCall = GeminiLiveService.ToolCall(
            id = "x",
            name = "execute",
            args = JSONObject()
        )
        val result = GeminiFunctionCall.fromServiceToolCall(serviceCall)
        assertThat(result.status).isEqualTo(ToolCallStatus.PENDING)
    }

    // ==================== GeminiToolCall.fromServiceToolCalls ====================

    @Test
    fun `fromServiceToolCalls - converts non-empty list correctly`() {
        val toolCalls = listOf(
            GeminiLiveService.ToolCall(id = "1", name = "search", args = JSONObject()),
            GeminiLiveService.ToolCall(id = "2", name = "execute", args = JSONObject())
        )
        val result = GeminiToolCall.fromServiceToolCalls(toolCalls)
        assertThat(result.calls).hasSize(2)
        assertThat(result.calls[0].id).isEqualTo("1")
        assertThat(result.calls[1].id).isEqualTo("2")
    }

    @Test
    fun `fromServiceToolCalls - empty list produces empty calls`() {
        val result = GeminiToolCall.fromServiceToolCalls(emptyList())
        assertThat(result.calls).isEmpty()
    }

    // ==================== ToolResult factory methods ====================

    @Test
    fun `success with JSONObject - sets success true and merges data`() {
        val data = JSONObject().apply { put("count", 5) }
        val result = ToolResult.success("call-1", data)
        assertThat(result.toolCallId).isEqualTo("call-1")
        assertThat(result.success).isTrue()
        assertThat(result.result.getInt("count")).isEqualTo(5)
        assertThat(result.errorMessage).isNull()
    }

    @Test
    fun `success with JSONObject empty - sets success true with empty result`() {
        val result = ToolResult.success("call-x")
        assertThat(result.success).isTrue()
        assertThat(result.toolCallId).isEqualTo("call-x")
    }

    @Test
    fun `success with message string - creates result with message field`() {
        val result = ToolResult.success("call-2", "Done!")
        assertThat(result.success).isTrue()
        assertThat(result.result.getString("message")).isEqualTo("Done!")
    }

    @Test
    fun `failure factory - sets success false and errorMessage`() {
        val result = ToolResult.failure("call-3", "Oops: something went wrong")
        assertThat(result.toolCallId).isEqualTo("call-3")
        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).isEqualTo("Oops: something went wrong")
    }

    // ==================== ToolResult.toResponseJson ====================

    @Test
    fun `toResponseJson - success status is success`() {
        val data = JSONObject().apply { put("message", "ok"); put("count", 3) }
        val result = ToolResult.success("id", data)
        val json = result.toResponseJson()
        assertThat(json.getString("status")).isEqualTo("success")
    }

    @Test
    fun `toResponseJson - success merges all data fields into top-level`() {
        val data = JSONObject().apply { put("message", "ok"); put("count", 3) }
        val result = ToolResult.success("id", data)
        val json = result.toResponseJson()
        assertThat(json.getString("message")).isEqualTo("ok")
        assertThat(json.getInt("count")).isEqualTo(3)
    }

    @Test
    fun `toResponseJson - failure status is error`() {
        val result = ToolResult.failure("id", "Permission denied")
        val json = result.toResponseJson()
        assertThat(json.getString("status")).isEqualTo("error")
    }

    @Test
    fun `toResponseJson - failure includes error message`() {
        val result = ToolResult.failure("id", "Permission denied")
        val json = result.toResponseJson()
        assertThat(json.getString("error")).isEqualTo("Permission denied")
    }

    @Test
    fun `toResponseJson - failure with null errorMessage uses Unknown error`() {
        val result = ToolResult(toolCallId = "id", success = false, errorMessage = null)
        val json = result.toResponseJson()
        assertThat(json.getString("error")).isEqualTo("Unknown error")
    }

    // ==================== ToolDeclarations ====================

    @Test
    fun `allDeclarations - returns exactly 4 tool declarations`() {
        val declarations = ToolDeclarations.allDeclarations()
        assertThat(declarations).hasSize(4)
    }

    @Test
    fun `allDeclarations - each entry has non-empty function_declarations array`() {
        val declarations = ToolDeclarations.allDeclarations()
        for (decl in declarations) {
            assertThat(decl.has("function_declarations")).isTrue()
            assertThat(decl.getJSONArray("function_declarations").length()).isGreaterThan(0)
        }
    }

    @Test
    fun `allDeclarations - contains execute search check_schedule make_call`() {
        val declarations = ToolDeclarations.allDeclarations()
        val names = declarations.flatMap { decl ->
            val arr = decl.getJSONArray("function_declarations")
            (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }
        }
        assertThat(names).containsAtLeast("execute", "search", "check_schedule", "make_call")
    }

    @Test
    fun `allDeclarations - execute tool has task parameter in required list`() {
        val declarations = ToolDeclarations.allDeclarations()
        val executeTool = declarations
            .map { it.getJSONArray("function_declarations").getJSONObject(0) }
            .first { it.getString("name") == "execute" }
        val required = executeTool.getJSONObject("parameters").getJSONArray("required")
        val requiredList = (0 until required.length()).map { required.getString(it) }
        assertThat(requiredList).contains("task")
    }

    @Test
    fun `allDeclarations - each tool declaration has a non-empty description`() {
        val declarations = ToolDeclarations.allDeclarations()
        for (decl in declarations) {
            val arr = decl.getJSONArray("function_declarations")
            for (i in 0 until arr.length()) {
                val tool = arr.getJSONObject(i)
                assertThat(tool.getString("description")).isNotEmpty()
            }
        }
    }
}
