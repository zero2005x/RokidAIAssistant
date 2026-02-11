package com.example.rokidphone.service.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Tool Call Data Models
 *
 * Functionality:
 * - Defines data structures related to Gemini Live API Function Calling.
 * - Includes models for Tool declarations, Tool Call requests, and Tool Call results.
 * - Supports the `execute` universal tool declaration (similar to VisionClaw's OpenClawBridge design).
 *
 * Interaction with existing architecture:
 * - Used by [ToolCallRouter] to route and process tool calls.
 * - Used by [GeminiLiveSession] to send tool declarations and responses.
 * - [GeminiLiveService.ToolCall] is the base data structure at the WebSocket layer, this file provides a higher-level wrapper.
 *
 * Android specific notes:
 * - Uses org.json (Android built-in) rather than third-party JSON libraries.
 * - All JSONObject operations need to be thread-safe.
 */

// ========== Tool Call Status ==========

/**
 * Execution status of a Tool Call
 */
enum class ToolCallStatus {
    PENDING,      // Waiting for execution
    IN_PROGRESS,  // In progress
    COMPLETED,    // Completed
    FAILED,       // Failed
    CANCELLED     // Cancelled
}

// ========== Tool Call Request Models ==========

/**
 * Wrapped Function Call request
 *
 * @param id       Unique ID assigned by Gemini
 * @param name     Function name (e.g., "execute")
 * @param args     Function arguments (JSON format)
 * @param status   Current execution status
 */
data class GeminiFunctionCall(
    val id: String,
    val name: String,
    val args: JSONObject,
    val status: ToolCallStatus = ToolCallStatus.PENDING
) {
    /**
     * Get the task parameter from the execute tool
     * @return task description string, or null if not present
     */
    fun getTaskDescription(): String? {
        return args.optString("task", null)
    }

    /**
     * Convert from GeminiLiveService.ToolCall
     */
    companion object {
        fun fromServiceToolCall(toolCall: GeminiLiveService.ToolCall): GeminiFunctionCall {
            return GeminiFunctionCall(
                id = toolCall.id,
                name = toolCall.name,
                args = toolCall.args
            )
        }
    }
}

/**
 * A batch of Tool Call requests (the model may issue multiple calls simultaneously)
 *
 * @param calls List of function calls
 */
data class GeminiToolCall(
    val calls: List<GeminiFunctionCall>
) {
    companion object {
        /**
         * Convert from a list of GeminiLiveService.ToolCall
         */
        fun fromServiceToolCalls(toolCalls: List<GeminiLiveService.ToolCall>): GeminiToolCall {
            return GeminiToolCall(
                calls = toolCalls.map { GeminiFunctionCall.fromServiceToolCall(it) }
            )
        }
    }
}

/**
 * Tool Call cancellation notification
 *
 * @param ids List of cancelled tool call IDs
 */
data class GeminiToolCallCancellation(
    val ids: List<String>
)

// ========== Tool Call Result Models ==========

/**
 * Tool execution result
 *
 * @param toolCallId Corresponding Tool Call ID
 * @param success    Whether execution was successful
 * @param result     Execution result (JSON format)
 * @param errorMessage Error message (if failed)
 */
data class ToolResult(
    val toolCallId: String,
    val success: Boolean,
    val result: JSONObject = JSONObject(),
    val errorMessage: String? = null
) {
    /**
     * Convert to JSON format required by Gemini API
     */
    fun toResponseJson(): JSONObject {
        return JSONObject().apply {
            put("status", if (success) "success" else "error")
            if (success) {
                // Merge all key-value pairs from result into the response
                for (key in result.keys()) {
                    put(key, result.get(key))
                }
            } else {
                put("error", errorMessage ?: "Unknown error")
            }
        }
    }

    companion object {
        /**
         * Create a success result
         */
        fun success(toolCallId: String, data: JSONObject = JSONObject()): ToolResult {
            return ToolResult(
                toolCallId = toolCallId,
                success = true,
                result = data
            )
        }

        /**
         * Create a success result (with message)
         */
        fun success(toolCallId: String, message: String): ToolResult {
            return ToolResult(
                toolCallId = toolCallId,
                success = true,
                result = JSONObject().apply {
                    put("message", message)
                }
            )
        }

        /**
         * Create a failure result
         */
        fun failure(toolCallId: String, errorMessage: String): ToolResult {
            return ToolResult(
                toolCallId = toolCallId,
                success = false,
                errorMessage = errorMessage
            )
        }
    }
}

// ========== Tool Declarations ==========

/**
 * Tool declaration utility
 *
 * Manages tool (function) declarations sent to Gemini.
 * Currently supports a universal `execute` tool that can perform arbitrary tasks.
 *
 * Design inspired by VisionClaw's OpenClawBridge:
 * - Single `execute` function declaration
 * - Uses `task` parameter to describe the task to execute
 * - Routes to local or remote gateway for processing
 */
object ToolDeclarations {

    /**
     * Get all tool declarations (in JSON format, can be directly sent to Gemini setup message)
     *
     * @return List of tool declaration JSON objects
     */
    fun allDeclarations(): List<JSONObject> {
        return listOf(
            executeToolDeclaration(),
            searchToolDeclaration()
        )
    }

    /**
     * execute — Universal task execution tool
     *
     * The model can use this tool to request execution of arbitrary tasks,
     * with ToolCallRouter deciding how to route and process them.
     */
    private fun executeToolDeclaration(): JSONObject {
        return JSONObject().apply {
            put("function_declarations", JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "execute")
                    put("description",
                        "Execute an arbitrary task or action based on the provided description. " +
                        "This is a general-purpose tool that can handle various operations " +
                        "such as device control, information lookup, app operations, etc."
                    )
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("task", JSONObject().apply {
                                put("type", "string")
                                put("description",
                                    "A clear description of the task to execute. " +
                                    "Include relevant details and parameters."
                                )
                            })
                            put("category", JSONObject().apply {
                                put("type", "string")
                                put("description",
                                    "Optional category to help route the task: " +
                                    "'device_control', 'information', 'communication', 'system', 'custom'"
                                )
                                put("enum", JSONArray().apply {
                                    put("device_control")
                                    put("information")
                                    put("communication")
                                    put("system")
                                    put("custom")
                                })
                            })
                        })
                        put("required", JSONArray().apply {
                            put("task")
                        })
                    })
                })
            })
        }
    }

    /**
     * search — Web search tool
     *
     * Allows the model to request web information searches.
     */
    private fun searchToolDeclaration(): JSONObject {
        return JSONObject().apply {
            put("function_declarations", JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "search")
                    put("description",
                        "Search the web for real-time information. " +
                        "Use this when the user asks about current events, " +
                        "specific facts, or anything that requires up-to-date information."
                    )
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("query", JSONObject().apply {
                                put("type", "string")
                                put("description", "The search query string")
                            })
                        })
                        put("required", JSONArray().apply {
                            put("query")
                        })
                    })
                })
            })
        }
    }
}