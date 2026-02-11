package com.example.rokidphone.service.ai

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Tool Call Router
 *
 * Functionality:
 * - Receives tool call requests from Gemini Live API.
 * - Parses function calls and routes them to appropriate handlers.
 * - Supports both local processing and remote gateway (HTTP POST) modes.
 * - Manages in-flight tool calls and supports cancellation mechanism.
 * - Returns execution results to GeminiLiveService.
 *
 * Interaction with existing architecture:
 * - Created and managed by [GeminiLiveSession].
 * - Uses data models from [ToolCallModels].
 * - Returns results via [GeminiLiveService.sendToolResponse].
 *
 * Android-specific considerations:
 * - All HTTP requests execute on Dispatchers.IO.
 * - Uses ConcurrentHashMap to manage in-flight tasks for thread safety.
 * - OkHttpClient uses reasonable timeout settings to avoid ANR.
 */
class ToolCallRouter(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "ToolCallRouter"

        // Remote gateway default timeout (seconds)
        private const val GATEWAY_TIMEOUT_SECONDS = 30L

        // Default remote gateway URL (can be overridden by external configuration)
        private const val DEFAULT_GATEWAY_URL = ""
    }

    // ========== Configuration ==========

    /**
     * Remote gateway URL (HTTP POST endpoint)
     * Empty string means no remote gateway, local processing only
     */
    var gatewayUrl: String = DEFAULT_GATEWAY_URL

    // ========== HTTP Client ==========

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(GATEWAY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(GATEWAY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(GATEWAY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    // ========== In-flight Tasks ==========

    /**
     * Tracks all in-flight tool call jobs (for cancellation mechanism)
     * Key: tool call ID, Value: running Job
     */
    private val inFlightCalls = ConcurrentHashMap<String, Job>()

    // ========== Event Flows ==========

    /**
     * Tool execution result event flow
     * GeminiLiveSession collects this flow and returns results to WebSocket
     */
    private val _toolResults = MutableSharedFlow<ToolResult>(extraBufferCapacity = 64)
    val toolResults = _toolResults.asSharedFlow()

    /**
     * Tool execution status change event flow (for UI display)
     */
    private val _statusUpdates = MutableSharedFlow<Pair<String, ToolCallStatus>>(extraBufferCapacity = 64)
    val statusUpdates = _statusUpdates.asSharedFlow()

    // ========== Local Handler Registration ==========

    /**
     * Local tool handler mapping
     * Key: function name, Value: handler function
     */
    private val localHandlers = ConcurrentHashMap<String, suspend (GeminiFunctionCall) -> ToolResult>()

    /**
     * Register local tool handler
     *
     * @param functionName Function name (must match declaration in ToolDeclarations)
     * @param handler      Handler function that receives GeminiFunctionCall and returns ToolResult
     */
    fun registerHandler(functionName: String, handler: suspend (GeminiFunctionCall) -> ToolResult) {
        localHandlers[functionName] = handler
        Log.d(TAG, "Registered local handler for: $functionName")
    }

    // ========== Tool Call Processing ==========

    /**
     * Process a group of tool call requests
     *
     * @param toolCalls List of tool calls received from GeminiLiveService
     */
    fun handleToolCalls(toolCalls: List<GeminiLiveService.ToolCall>) {
        val geminiToolCall = GeminiToolCall.fromServiceToolCalls(toolCalls)
        
        for (call in geminiToolCall.calls) {
            Log.d(TAG, "Processing tool call: ${call.name} (id=${call.id})")
            executeToolCall(call)
        }
    }

    /**
     * Execute a single tool call
     *
     * @param call Function call to execute
     */
    private fun executeToolCall(call: GeminiFunctionCall) {
        val job = scope.launch {
            try {
                // Update status to in progress
                _statusUpdates.emit(call.id to ToolCallStatus.IN_PROGRESS)

                // Route to appropriate handler
                val result = routeToolCall(call)

                // Send result
                _toolResults.emit(result)

                // Update status
                val finalStatus = if (result.success) ToolCallStatus.COMPLETED else ToolCallStatus.FAILED
                _statusUpdates.emit(call.id to finalStatus)

                Log.d(TAG, "Tool call completed: ${call.name} (id=${call.id}, success=${result.success})")

            } catch (e: CancellationException) {
                Log.d(TAG, "Tool call cancelled: ${call.name} (id=${call.id})")
                _statusUpdates.emit(call.id to ToolCallStatus.CANCELLED)

                // Return result even if cancelled (to avoid model waiting forever)
                _toolResults.emit(ToolResult.failure(call.id, "Tool call cancelled"))

            } catch (e: Exception) {
                Log.e(TAG, "Tool call failed: ${call.name} (id=${call.id})", e)
                _statusUpdates.emit(call.id to ToolCallStatus.FAILED)
                _toolResults.emit(ToolResult.failure(call.id, e.message ?: "Unknown error"))
            } finally {
                inFlightCalls.remove(call.id)
            }
        }

        inFlightCalls[call.id] = job
    }

    /**
     * Route tool call to appropriate handler
     *
     * Routing priority:
     * 1. Locally registered handler
     * 2. Remote gateway (HTTP POST)
     * 3. Default response (no handler)
     */
    private suspend fun routeToolCall(call: GeminiFunctionCall): ToolResult {
        // 1. Try local handler
        val localHandler = localHandlers[call.name]
        if (localHandler != null) {
            Log.d(TAG, "Routing to local handler: ${call.name}")
            return localHandler(call)
        }

        // 2. Try remote gateway
        if (gatewayUrl.isNotBlank()) {
            Log.d(TAG, "Routing to remote gateway: ${call.name}")
            return executeRemotely(call)
        }

        // 3. Default handling (when no handler exists)
        Log.w(TAG, "No handler found for: ${call.name}, using default response")
        return handleDefault(call)
    }

    /**
     * Execute tool call via remote gateway
     *
     * Sends HTTP POST to gatewayUrl, body in JSON format:
     * {
     *   "tool_call_id": "...",
     *   "function_name": "...",
     *   "arguments": { ... }
     * }
     */
    private suspend fun executeRemotely(call: GeminiFunctionCall): ToolResult {
        return withContext(Dispatchers.IO) {
            try {
                val requestBody = JSONObject().apply {
                    put("tool_call_id", call.id)
                    put("function_name", call.name)
                    put("arguments", call.args)
                }.toString()

                val request = Request.Builder()
                    .url(gatewayUrl)
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .build()

                val response = httpClient.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: "{}"
                    val responseJson = JSONObject(responseBody)

                    ToolResult.success(call.id, responseJson)
                } else {
                    ToolResult.failure(
                        call.id,
                        "Gateway returned ${response.code}: ${response.message}"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Remote execution failed for ${call.name}", e)
                ToolResult.failure(call.id, "Remote execution failed: ${e.message}")
            }
        }
    }

    /**
     * Default handling (fallback when no corresponding handler exists)
     */
    private fun handleDefault(call: GeminiFunctionCall): ToolResult {
        return when (call.name) {
            "execute" -> {
                val task = call.getTaskDescription() ?: "Unknown task"
                Log.d(TAG, "Default execute handler: $task")
                ToolResult.success(
                    call.id,
                    JSONObject().apply {
                        put("message", "Task acknowledged: $task")
                        put("note", "No specific handler registered. Task logged for processing.")
                    }
                )
            }
            "search" -> {
                val query = call.args.optString("query", "")
                Log.d(TAG, "Default search handler: $query")
                ToolResult.success(
                    call.id,
                    JSONObject().apply {
                        put("message", "Search functionality not yet configured.")
                        put("query", query)
                    }
                )
            }
            else -> {
                ToolResult.failure(
                    call.id,
                    "Unknown function: ${call.name}. No handler registered."
                )
            }
        }
    }

    // ========== Cancellation Mechanism ==========

    /**
     * Cancel specified tool call
     *
     * @param toolCallId Tool call ID to cancel
     */
    fun cancelToolCall(toolCallId: String) {
        val job = inFlightCalls[toolCallId]
        if (job != null && job.isActive) {
            Log.d(TAG, "Cancelling tool call: $toolCallId")
            job.cancel()
        } else {
            Log.w(TAG, "Tool call not found or already completed: $toolCallId")
        }
    }

    /**
     * Cancel multiple tool calls (handles Gemini's toolCallCancellation message)
     *
     * @param cancellation Cancellation notification object
     */
    fun cancelToolCalls(cancellation: GeminiToolCallCancellation) {
        Log.d(TAG, "Cancelling ${cancellation.ids.size} tool call(s)")
        for (id in cancellation.ids) {
            cancelToolCall(id)
        }
    }

    /**
     * Cancel all in-flight tool calls
     */
    fun cancelAll() {
        Log.d(TAG, "Cancelling all ${inFlightCalls.size} in-flight tool calls")
        for ((id, job) in inFlightCalls) {
            if (job.isActive) {
                job.cancel()
            }
        }
        inFlightCalls.clear()
    }

    // ========== Resource Release ==========

    /**
     * Release all resources
     */
    fun release() {
        Log.d(TAG, "Releasing ToolCallRouter resources")
        cancelAll()
        localHandlers.clear()
        scope.cancel()
    }

    // ========== Utility Methods ==========

    /**
     * Get the current number of in-flight tool calls
     */
    fun getInFlightCount(): Int = inFlightCalls.size

    /**
     * Check if there are any in-flight tool calls
     */
    fun hasInFlightCalls(): Boolean = inFlightCalls.isNotEmpty()
}