package com.example.rokidcommon.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Integration tests for phone <-> glasses protocol message flow.
 * Uses Robolectric because JSON path relies on android.util.Base64.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PhoneGlassesProtocolIntegrationTest {

    @Test
    fun `handshake then heartbeat exchange can roundtrip through json`() {
        // Test: Handshake and heartbeat messages can be correctly parsed in bidirectional JSON transmission
        val glassesHandshake = Message.handshake("Rokid-Glasses")
        val phoneReceivedHandshake = Message.fromJson(glassesHandshake.toJson())

        assertThat(phoneReceivedHandshake).isNotNull()
        assertThat(phoneReceivedHandshake!!.type).isEqualTo(MessageType.HANDSHAKE)
        assertThat(phoneReceivedHandshake.payload).isEqualTo("Rokid-Glasses")

        val phoneHeartbeat = Message.heartbeat()
        val glassesReceivedHeartbeat = Message.fromJson(phoneHeartbeat.toJson())

        assertThat(glassesReceivedHeartbeat).isNotNull()
        assertThat(glassesReceivedHeartbeat!!.type).isEqualTo(MessageType.HEARTBEAT)
    }

    @Test
    fun `voice pipeline messages preserve order and payload semantics`() {
        // Test: VOICE_START -> VOICE_DATA -> VOICE_END sequence and data remain consistent
        val audioChunk = byteArrayOf(1, 3, 5, 7)

        val outbound = listOf(
            Message.voiceStart(),
            Message.voiceData(audioChunk),
            Message.voiceEnd()
        )

        val inbound = outbound.map { Message.fromBytes(it.toBytes()) }

        assertThat(inbound.all { it != null }).isTrue()
        assertThat(inbound[0]!!.type).isEqualTo(MessageType.VOICE_START)
        assertThat(inbound[1]!!.type).isEqualTo(MessageType.VOICE_DATA)
        assertThat(inbound[1]!!.binaryData).isEqualTo(audioChunk)
        assertThat(inbound[2]!!.type).isEqualTo(MessageType.VOICE_END)
    }

    @Test
    fun `phone ai status and response messages can be consumed by glasses ui side`() {
        // Test: AI_PROCESSING / AI_RESPONSE_TEXT / AI_ERROR messages sent from phone can be parsed by glasses
        val outbound = listOf(
            Message.aiProcessing("Thinking..."),
            Message.aiResponseText("Answer from phone"),
            Message.aiError("temporary failure")
        )

        val inbound = outbound.map { Message.fromJson(it.toJson()) }

        assertThat(inbound[0]!!.type).isEqualTo(MessageType.AI_PROCESSING)
        assertThat(inbound[0]!!.payload).isEqualTo("Thinking...")

        assertThat(inbound[1]!!.type).isEqualTo(MessageType.AI_RESPONSE_TEXT)
        assertThat(inbound[1]!!.payload).isEqualTo("Answer from phone")

        assertThat(inbound[2]!!.type).isEqualTo(MessageType.AI_ERROR)
        assertThat(inbound[2]!!.payload).isEqualTo("temporary failure")
    }

    @Test
    fun `remote record control commands and live transcription roundtrip correctly`() {
        // Test: Remote recording control and live transcription messages can be transmitted across devices
        val start = Message(type = MessageType.REMOTE_RECORD_START, payload = "rec-123")
        val stop = Message(type = MessageType.REMOTE_RECORD_STOP)
        val liveText = Message(type = MessageType.LIVE_TRANSCRIPTION, payload = "hello world")

        val parsedStart = Message.fromJson(start.toJson())
        val parsedStop = Message.fromJson(stop.toJson())
        val parsedLive = Message.fromJson(liveText.toJson())

        assertThat(parsedStart!!.type).isEqualTo(MessageType.REMOTE_RECORD_START)
        assertThat(parsedStart.payload).isEqualTo("rec-123")

        assertThat(parsedStop!!.type).isEqualTo(MessageType.REMOTE_RECORD_STOP)
        assertThat(parsedStop.payload).isNull()

        assertThat(parsedLive!!.type).isEqualTo(MessageType.LIVE_TRANSCRIPTION)
        assertThat(parsedLive.payload).isEqualTo("hello world")
    }

    @Test
    fun `capture photo request and analysis result messages remain compatible`() {
        // Test: Photo capture request and analysis result messages maintain compatibility at the protocol layer
        val capture = Message(type = MessageType.CAPTURE_PHOTO)
        val result = Message(type = MessageType.PHOTO_ANALYSIS_RESULT, payload = "a cat on the table")

        val captureParsed = Message.fromJson(capture.toJson())
        val resultParsed = Message.fromJson(result.toJson())

        assertThat(captureParsed!!.type).isEqualTo(MessageType.CAPTURE_PHOTO)

        assertThat(resultParsed!!.type).isEqualTo(MessageType.PHOTO_ANALYSIS_RESULT)
        assertThat(resultParsed.payload).contains("cat")
    }
}