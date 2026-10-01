package com.example.rokidphone.service.ai

import android.util.Base64
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * The Gemini Live API protocol as [GeminiLiveService] speaks it, driven through a
 * fake WebSocket factory so no connection is opened.
 */
@RunWith(RobolectricTestRunner::class)
class GeminiLiveServiceTest {

    private val socket = mockk<WebSocket>(relaxed = true)
    private val sent = mutableListOf<String>()
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private lateinit var listener: WebSocketListener
    private lateinit var request: Request
    private lateinit var service: GeminiLiveService

    private val states = mutableListOf<GeminiLiveService.ConnectionState>()

    private fun build(apiKey: String = "fixture-key", systemPrompt: String = ""): GeminiLiveService {
        val factory = object : WebSocket.Factory {
            override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                this@GeminiLiveServiceTest.request = request
                this@GeminiLiveServiceTest.listener = listener
                return socket
            }
        }
        return GeminiLiveService(
            apiKey = apiKey, systemPrompt = systemPrompt, scope = scope, webSocketFactory = factory
        ).apply { onConnectionStateChanged = { states += it } }
    }

    /** Connects and completes setup, leaving the service READY. */
    private fun connectAndSetUp(tools: List<JSONObject>? = null) {
        service.connect(tools)
        listener.onOpen(socket, mockk(relaxed = true))
        listener.onMessage(socket, JSONObject().put("setupComplete", JSONObject()).toString())
    }

    private fun serverContent(build: JSONObject.() -> Unit) {
        listener.onMessage(
            socket,
            JSONObject().put("serverContent", JSONObject().apply(build)).toString()
        )
    }

    private fun lastSent() = JSONObject(sent.last())

    @Before
    fun setUp() {
        every { socket.send(any<String>()) } answers { sent += firstArg<String>(); true }
        service = build()
    }

    @After
    fun tearDown() {
        scope.cancel()
        unmockkAll()
    }

    @Test
    fun `connecting opens the keyed endpoint and announces the setup`() {
        val tools = listOf(JSONObject().put("function_declarations", JSONArray()))

        connectAndSetUp(tools)

        assertThat(request.url.queryParameter("key")).isEqualTo("fixture-key")
        assertThat(request.url.host).isEqualTo("generativelanguage.googleapis.com")
        assertThat(states).containsExactly(
            GeminiLiveService.ConnectionState.CONNECTING,
            GeminiLiveService.ConnectionState.SETTING_UP,
            GeminiLiveService.ConnectionState.READY
        ).inOrder()
        assertThat(service.connectionState.value).isEqualTo(GeminiLiveService.ConnectionState.READY)

        val setup = JSONObject(sent.first()).getJSONObject("setup")
        assertThat(setup.getString("model")).startsWith("models/")
        assertThat(setup.getJSONObject("generationConfig").getJSONArray("responseModalities")
            .getString(0)).isEqualTo("AUDIO")
        assertThat(setup.getJSONArray("tools").length()).isEqualTo(1)
        assertThat(setup.has("systemInstruction")).isFalse()

        val realtime = setup.getJSONObject("realtimeInputConfig")
        assertThat(realtime.has("inputAudioTranscription")).isTrue()
        assertThat(realtime.has("outputAudioTranscription")).isTrue()
        val vad = realtime.getJSONObject("automaticActivityDetection")
        assertThat(vad.getBoolean("disabled")).isFalse()
        assertThat(vad.getString("startOfSpeechSensitivity")).isEqualTo("START_SENSITIVITY_HIGH")
        assertThat(vad.getInt("silenceDurationMs")).isEqualTo(500)
    }

    @Test
    fun `a system prompt and custom VAD settings reach the setup message`() {
        service = build(systemPrompt = "Be brief")
        service.updateVadSettings(
            startSensitivity = GeminiLiveService.StartOfSpeechSensitivity.START_SENSITIVITY_LOW,
            endSensitivity = GeminiLiveService.EndOfSpeechSensitivity.END_SENSITIVITY_HIGH,
            silenceDurationMs = 900,
            activityHandling = GeminiLiveService.ActivityHandling.NO_INTERRUPT
        )

        connectAndSetUp()

        val setup = JSONObject(sent.first()).getJSONObject("setup")
        assertThat(
            setup.getJSONObject("systemInstruction").getJSONArray("parts")
                .getJSONObject(0).getString("text")
        ).isEqualTo("Be brief")
        val realtime = setup.getJSONObject("realtimeInputConfig")
        assertThat(realtime.getString("activityHandling")).isEqualTo("NO_INTERRUPT")
        val vad = realtime.getJSONObject("automaticActivityDetection")
        assertThat(vad.getString("startOfSpeechSensitivity")).isEqualTo("START_SENSITIVITY_LOW")
        assertThat(vad.getString("endOfSpeechSensitivity")).isEqualTo("END_SENSITIVITY_HIGH")
        assertThat(vad.getInt("silenceDurationMs")).isEqualTo(900)
        assertThat(setup.has("tools")).isFalse()
    }

    @Test
    fun `a missing api key is refused before any socket is opened`() {
        service = build(apiKey = " ")

        service.connect()

        assertThat(service.connectionState.value).isEqualTo(GeminiLiveService.ConnectionState.ERROR)
        assertThat(service.errorMessage.value).isEqualTo("API Key is not configured")
        assertThat(states).containsExactly(GeminiLiveService.ConnectionState.ERROR)
    }

    @Test
    fun `a connection already in progress is not opened twice`() {
        connectAndSetUp()
        val before = states.size

        service.connect()

        assertThat(states).hasSize(before)
    }

    @Test
    fun `audio and video are only sent once the session is ready`() {
        service.connect()
        service.sendAudio(byteArrayOf(1, 2))
        service.sendVideoFrame(byteArrayOf(3, 4))
        assertThat(sent).isEmpty()

        listener.onOpen(socket, mockk(relaxed = true))
        listener.onMessage(socket, JSONObject().put("setupComplete", JSONObject()).toString())
        sent.clear()

        service.sendAudio(byteArrayOf(1, 2))
        var chunk = lastSent().getJSONObject("realtimeInput").getJSONArray("mediaChunks")
            .getJSONObject(0)
        assertThat(chunk.getString("mimeType")).isEqualTo("audio/pcm;rate=16000")
        assertThat(Base64.decode(chunk.getString("data"), Base64.NO_WRAP))
            .isEqualTo(byteArrayOf(1, 2))

        service.sendVideoFrame(byteArrayOf(3, 4))
        chunk = lastSent().getJSONObject("realtimeInput").getJSONArray("mediaChunks").getJSONObject(0)
        assertThat(chunk.getString("mimeType")).isEqualTo("image/jpeg")
        assertThat(Base64.decode(chunk.getString("data"), Base64.NO_WRAP))
            .isEqualTo(byteArrayOf(3, 4))
    }

    @Test
    fun `tool responses and a manual end of turn need a live connection`() {
        service.connect()
        service.sendToolResponse("t1", JSONObject().put("ok", true))
        service.endOfTurn()
        assertThat(sent).isEmpty()

        connectAndSetUp()
        sent.clear()

        service.sendToolResponse("t1", JSONObject().put("ok", true))
        val response = lastSent().getJSONObject("toolResponse").getJSONArray("functionResponses")
            .getJSONObject(0)
        assertThat(response.getString("id")).isEqualTo("t1")
        assertThat(response.getJSONObject("response").getBoolean("ok")).isTrue()

        service.endOfTurn()
        assertThat(lastSent().getJSONObject("clientContent").getBoolean("turnComplete")).isTrue()
    }

    @Test
    fun `audio parts are decoded and handed to the caller`() {
        val received = mutableListOf<ByteArray>()
        service.onAudioReceived = { received += it }
        connectAndSetUp()

        serverContent {
            put("modelTurn", JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("inlineData", JSONObject()
                    .put("mimeType", "audio/pcm;rate=24000")
                    .put("data", Base64.encodeToString(byteArrayOf(7, 8), Base64.NO_WRAP))))
                // A text part, and a non-audio attachment, are not audio.
                .put(JSONObject().put("text", "spoken words"))
                .put(JSONObject().put("inlineData", JSONObject()
                    .put("mimeType", "image/png").put("data", "AAAA")))))
        }

        assertThat(received.single()).isEqualTo(byteArrayOf(7, 8))
    }

    @Test
    fun `snake case server fields are understood as well`() {
        val received = mutableListOf<ByteArray>()
        service.onAudioReceived = { received += it }
        val spoken = mutableListOf<String>()
        service.onInputTranscription = { spoken += it }
        connectAndSetUp()

        serverContent {
            put("model_turn", JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("inline_data", JSONObject()
                    .put("mime_type", "audio/pcm")
                    .put("data", Base64.encodeToString(byteArrayOf(9), Base64.NO_WRAP))))))
            put("input_transcription", JSONObject().put("text", "hello"))
        }

        assertThat(received.single()).isEqualTo(byteArrayOf(9))
        assertThat(spoken).containsExactly("hello")
    }

    @Test
    fun `turn completion, interruption and transcriptions are reported`() {
        var completed = 0
        var interrupted = 0
        val spoken = mutableListOf<String>()
        val answered = mutableListOf<String>()
        service.onTurnComplete = { completed++ }
        service.onInterrupted = { interrupted++ }
        service.onInputTranscription = { spoken += it }
        service.onOutputTranscription = { answered += it }
        connectAndSetUp()

        serverContent { put("turnComplete", true) }
        assertThat(completed).isEqualTo(1)

        // An interruption short-circuits: nothing else in that frame is processed.
        serverContent {
            put("interrupted", true)
            put("turnComplete", true)
        }
        assertThat(interrupted).isEqualTo(1)
        assertThat(completed).isEqualTo(1)

        serverContent {
            put("inputTranscription", JSONObject().put("text", "what is this?"))
            put("outputTranscription", JSONObject().put("text", "a bicycle"))
        }
        assertThat(spoken).containsExactly("what is this?")
        assertThat(answered).containsExactly("a bicycle")

        // Empty transcriptions are not reported.
        serverContent {
            put("inputTranscription", JSONObject().put("text", ""))
            put("outputTranscription", JSONObject().put("text", ""))
        }
        assertThat(spoken).hasSize(1)
        assertThat(answered).hasSize(1)
    }

    @Test
    fun `complete tool calls are published and incomplete ones are dropped`() {
        val calls = mutableListOf<List<GeminiLiveService.ToolCall>>()
        service.onToolCall = { calls += it }
        connectAndSetUp()

        listener.onMessage(socket, JSONObject().put("toolCall", JSONObject().put(
            "functionCalls", JSONArray()
                .put(JSONObject().put("id", "t1").put("name", "check_schedule")
                    .put("args", JSONObject().put("day", "today")))
                .put(JSONObject().put("id", "").put("name", "nameless"))
                .put(JSONObject().put("id", "t3"))
        )).toString())

        val published = calls.single()
        assertThat(published.map { it.id }).containsExactly("t1")
        assertThat(published.single().name).isEqualTo("check_schedule")
        assertThat(published.single().args.getString("day")).isEqualTo("today")

        // A batch with nothing usable publishes nothing at all.
        listener.onMessage(socket, JSONObject().put("toolCall", JSONObject().put(
            "function_calls", JSONArray().put(JSONObject().put("name", "nameless"))
        )).toString())
        assertThat(calls).hasSize(1)
    }

    @Test
    fun `messages that carry nothing actionable are ignored`() {
        connectAndSetUp()

        listener.onMessage(socket, "not json")
        listener.onMessage(socket, JSONObject().put("somethingElse", 1).toString())
        listener.onMessage(socket, JSONObject().put("toolCall", JSONObject()).toString())
        listener.onMessage(
            socket,
            JSONObject().put("toolCallCancellation", JSONObject()
                .put("ids", JSONArray().put("t1"))).toString()
        )
        listener.onMessage(socket, JSONObject().put("toolCallCancellation", JSONObject()).toString())

        assertThat(service.connectionState.value).isEqualTo(GeminiLiveService.ConnectionState.READY)
    }

    @Test
    fun `a closed socket returns the service to disconnected`() {
        connectAndSetUp()

        listener.onClosing(socket, 1000, "bye")
        listener.onClosed(socket, 1000, "bye")

        assertThat(service.connectionState.value)
            .isEqualTo(GeminiLiveService.ConnectionState.DISCONNECTED)
        assertThat(states.last()).isEqualTo(GeminiLiveService.ConnectionState.DISCONNECTED)
    }

    @Test
    fun `a transport failure is reported with its cause`() {
        connectAndSetUp()

        listener.onFailure(socket, IOException("connection reset"), null)

        assertThat(service.connectionState.value).isEqualTo(GeminiLiveService.ConnectionState.ERROR)
        assertThat(service.errorMessage.value).isEqualTo("connection reset")
    }

    @Test
    fun `frames from a socket the service has replaced are ignored`() {
        connectAndSetUp()
        val stale = mockk<WebSocket>(relaxed = true)
        var completed = 0
        service.onTurnComplete = { completed++ }

        listener.onOpen(stale, mockk(relaxed = true))
        listener.onMessage(
            stale,
            JSONObject().put("serverContent", JSONObject().put("turnComplete", true)).toString()
        )
        listener.onClosed(stale, 1000, "bye")
        listener.onFailure(stale, IOException("gone"), null)

        verify { stale.cancel() }
        assertThat(completed).isEqualTo(0)
        assertThat(service.connectionState.value).isEqualTo(GeminiLiveService.ConnectionState.READY)
    }

    @Test
    fun `disconnecting closes the socket and releasing also ends the scope`() {
        connectAndSetUp()

        service.disconnect()

        verify { socket.close(1000, "Client disconnect") }
        assertThat(service.connectionState.value)
            .isEqualTo(GeminiLiveService.ConnectionState.DISCONNECTED)

        // Sending after a disconnect is a no-op rather than a crash.
        sent.clear()
        service.sendAudio(byteArrayOf(1))
        service.endOfTurn()
        assertThat(sent).isEmpty()

        service.release()
        assertThat(scope.coroutineContext[Job]!!.isActive).isFalse()
    }
}
