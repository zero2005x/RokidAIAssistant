package com.example.rokidphone.service.ai

import com.example.rokidphone.testutil.MockWebServerRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import org.json.JSONObject
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [ToolCallRouter] dispatches on its own scope, so these tests run it on
 * Dispatchers.Unconfined and await the result flows rather than the scheduler.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ToolCallRouterTest {

    @get:Rule val http = MockWebServerRule()

    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val router = ToolCallRouter(scope)

    @After
    fun tearDown() {
        router.release()
        scope.cancel()
    }

    private fun call(id: String, name: String, args: JSONObject = JSONObject()) =
        GeminiLiveService.ToolCall(id = id, name = name, args = args)

    @Test
    fun `a registered handler receives the call and its result is published`() = runTest {
        val received = CompletableDeferred<GeminiFunctionCall>()
        router.registerHandler("execute") { functionCall ->
            received.complete(functionCall)
            ToolResult.success(functionCall.id, JSONObject().put("done", true))
        }
        val results = async(start = CoroutineStart.UNDISPATCHED) { router.toolResults.first() }
        val statuses = async(start = CoroutineStart.UNDISPATCHED) {
            router.statusUpdates.take(2).toList()
        }

        router.handleToolCalls(listOf(call("t1", "execute", JSONObject().put("task", "wave"))))

        val handled = withTimeout(5_000) { received.await() }
        assertThat(handled.id).isEqualTo("t1")
        assertThat(handled.name).isEqualTo("execute")
        assertThat(handled.getTaskDescription()).isEqualTo("wave")

        val result = withTimeout(5_000) { results.await() }
        assertThat(result.success).isTrue()
        assertThat(result.result.getBoolean("done")).isTrue()
        assertThat(withTimeout(5_000) { statuses.await() }).containsExactly(
            "t1" to ToolCallStatus.IN_PROGRESS, "t1" to ToolCallStatus.COMPLETED
        ).inOrder()
        assertThat(router.hasInFlightCalls()).isFalse()
        assertThat(router.getInFlightCount()).isEqualTo(0)
    }

    @Test
    fun `a handler that fails is reported as a failed status`() = runTest {
        router.registerHandler("execute") { ToolResult.failure(it.id, "nothing to do") }
        val results = async(start = CoroutineStart.UNDISPATCHED) { router.toolResults.first() }
        val statuses = async(start = CoroutineStart.UNDISPATCHED) {
            router.statusUpdates.take(2).toList()
        }

        router.handleToolCalls(listOf(call("t1", "execute")))

        val result = withTimeout(5_000) { results.await() }
        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).isEqualTo("nothing to do")
        assertThat(withTimeout(5_000) { statuses.await() }.last())
            .isEqualTo("t1" to ToolCallStatus.FAILED)
    }

    @Test
    fun `a handler that throws becomes a failure result`() = runTest {
        router.registerHandler("execute") { throw IllegalStateException("handler blew up") }
        val results = async(start = CoroutineStart.UNDISPATCHED) { router.toolResults.first() }

        router.handleToolCalls(listOf(call("t1", "execute")))

        val result = withTimeout(5_000) { results.await() }
        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).isEqualTo("handler blew up")
    }

    @Test
    fun `unhandled execute and search calls get an acknowledgement`() = runTest {
        val results = async(start = CoroutineStart.UNDISPATCHED) {
            router.toolResults.take(3).toList()
        }

        router.handleToolCalls(
            listOf(
                call("t1", "execute", JSONObject().put("task", "turn on the light")),
                call("t2", "search", JSONObject().put("query", "weather")),
                call("t3", "teleport")
            )
        )

        val published = withTimeout(5_000) { results.await() }.associateBy { it.toolCallId }
        assertThat(published["t1"]!!.success).isTrue()
        assertThat(published["t1"]!!.result.getString("message")).contains("turn on the light")
        assertThat(published["t2"]!!.success).isTrue()
        assertThat(published["t2"]!!.result.getString("query")).isEqualTo("weather")
        assertThat(published["t3"]!!.success).isFalse()
        assertThat(published["t3"]!!.errorMessage).contains("Unknown function: teleport")
    }

    @Test
    fun `an execute call with no task still acknowledges something`() = runTest {
        val results = async(start = CoroutineStart.UNDISPATCHED) { router.toolResults.first() }

        router.handleToolCalls(listOf(call("t1", "execute", JSONObject().put("task", "  "))))

        val result = withTimeout(5_000) { results.await() }
        assertThat(result.result.getString("message")).contains("Unknown task")
    }

    @Test
    fun `a configured gateway receives the call and its answer is returned`() = runBlocking {
        http.server.enqueue(
            MockResponse.Builder().code(200).body("""{"temperature":21}""").build()
        )
        router.gatewayUrl = http.baseUrl
        val results = async(start = CoroutineStart.UNDISPATCHED) { router.toolResults.first() }

        router.handleToolCalls(
            listOf(call("t1", "get_weather", JSONObject().put("city", "Taipei")))
        )

        val result = withTimeout(5_000) { results.await() }
        assertThat(result.success).isTrue()
        assertThat(result.result.getInt("temperature")).isEqualTo(21)

        val request = http.server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        val body = JSONObject(request.body.readUtf8())
        assertThat(body.getString("tool_call_id")).isEqualTo("t1")
        assertThat(body.getString("function_name")).isEqualTo("get_weather")
        assertThat(body.getJSONObject("arguments").getString("city")).isEqualTo("Taipei")
    }

    @Test
    fun `a gateway error status and an unreachable gateway both fail the call`() = runBlocking {
        http.server.enqueue(MockResponse.Builder().code(503).body("busy").build())
        router.gatewayUrl = http.baseUrl
        var results = async(start = CoroutineStart.UNDISPATCHED) { router.toolResults.first() }

        router.handleToolCalls(listOf(call("t1", "get_weather")))
        var result = withTimeout(5_000) { results.await() }
        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).contains("Gateway returned 503")

        router.gatewayUrl = "http://127.0.0.1:1/unreachable"
        results = async(start = CoroutineStart.UNDISPATCHED) { router.toolResults.first() }
        router.handleToolCalls(listOf(call("t2", "get_weather")))
        result = withTimeout(5_000) { results.await() }
        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).contains("Remote execution failed")
    }

    @Test
    fun `a local handler takes precedence over the gateway`() = runTest {
        router.gatewayUrl = http.baseUrl
        router.registerHandler("get_weather") { ToolResult.success(it.id, JSONObject().put("local", true)) }
        val results = async(start = CoroutineStart.UNDISPATCHED) { router.toolResults.first() }

        router.handleToolCalls(listOf(call("t1", "get_weather")))

        assertThat(withTimeout(5_000) { results.await() }.result.getBoolean("local")).isTrue()
        assertThat(http.server.requestCount).isEqualTo(0)
    }

    @Test
    fun `cancelling an in-flight call reports it as cancelled`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        router.registerHandler("execute") {
            started.complete(Unit)
            release.await()
            ToolResult.success(it.id)
        }
        val results = async(start = CoroutineStart.UNDISPATCHED) { router.toolResults.first() }
        val statuses = async(start = CoroutineStart.UNDISPATCHED) {
            router.statusUpdates.take(2).toList()
        }

        router.handleToolCalls(listOf(call("t1", "execute")))
        withTimeout(5_000) { started.await() }
        assertThat(router.hasInFlightCalls()).isTrue()

        router.cancelToolCall("t1")

        val result = withTimeout(5_000) { results.await() }
        assertThat(result.success).isFalse()
        assertThat(result.errorMessage).isEqualTo("Tool call cancelled")
        assertThat(withTimeout(5_000) { statuses.await() }.last())
            .isEqualTo("t1" to ToolCallStatus.CANCELLED)
        release.complete(Unit)
    }

    @Test
    fun `cancelling an unknown or finished call is a no-op`() = runTest {
        router.registerHandler("execute") { ToolResult.success(it.id) }
        val results = async(start = CoroutineStart.UNDISPATCHED) { router.toolResults.first() }
        router.handleToolCalls(listOf(call("t1", "execute")))
        withTimeout(5_000) { results.await() }

        router.cancelToolCall("t1")
        router.cancelToolCall("never-existed")

        assertThat(router.hasInFlightCalls()).isFalse()
    }

    @Test
    fun `a cancellation notice cancels every named call`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        router.registerHandler("execute") {
            started.complete(Unit)
            release.await()
            ToolResult.success(it.id)
        }
        val results = async(start = CoroutineStart.UNDISPATCHED) {
            router.toolResults.take(2).toList()
        }

        router.handleToolCalls(listOf(call("t1", "execute"), call("t2", "execute")))
        withTimeout(5_000) { started.await() }

        router.cancelToolCalls(GeminiToolCallCancellation(listOf("t1", "t2")))

        val published = withTimeout(5_000) { results.await() }
        assertThat(published.map { it.errorMessage }.toSet()).containsExactly("Tool call cancelled")
        release.complete(Unit)
    }

    @Test
    fun `cancelAll empties the in-flight register`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        router.registerHandler("execute") {
            started.complete(Unit)
            release.await()
            ToolResult.success(it.id)
        }

        router.handleToolCalls(listOf(call("t1", "execute"), call("t2", "execute")))
        withTimeout(5_000) { started.await() }
        assertThat(router.getInFlightCount()).isEqualTo(2)

        router.cancelAll()

        assertThat(router.hasInFlightCalls()).isFalse()
        release.complete(Unit)
    }
}
