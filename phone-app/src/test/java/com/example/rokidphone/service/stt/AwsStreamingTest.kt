package com.example.rokidphone.service.stt

import com.example.rokidphone.service.SpeechErrorCode
import com.example.rokidphone.service.SpeechResult
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.zip.CRC32

@RunWith(RobolectricTestRunner::class)
class AwsStreamingTest {
    private val service = AwsTranscribeSttService("fixture-id", "fixture-secret", ioDispatcher = Dispatchers.Unconfined)
    private val transport = OfflineSttTransport(service)
    private val audio = ByteArray(9000) { (it % 127).toByte() }

    @After fun cleanup() = unmockkAll()

    private fun event(json: String): ByteArray {
        val payload = json.toByteArray()
        val buffer = ByteBuffer.allocate(payload.size + 16)
        buffer.putInt(buffer.capacity()).putInt(0)
        buffer.putInt(CRC32().apply { update(buffer.array(), 0, 8) }.value.toInt())
        buffer.put(payload)
        buffer.putInt(CRC32().apply { update(buffer.array(), 0, buffer.position()) }.value.toInt())
        return buffer.array()
    }

    @Test fun `stream sends valid framed audio and combines only final transcripts`() = runTest {
        transport.events = { listener ->
            for (json in listOf("{}", """{"Transcript":{"Results":[]}}""",
                """{"Transcript":{"Results":[{"IsPartial":true,"Alternatives":[{"Transcript":"ignore"}]}]}}""",
                """{"Transcript":{"Results":[{"IsPartial":false}]}}""",
                """{"Transcript":{"Results":[{"IsPartial":false,"Alternatives":[]}]}}""",
                """{"Transcript":{"Results":[{"IsPartial":false,"Alternatives":[{"Transcript":" hello "}]}]}}""",
                """{"Transcript":{"Results":[{"IsPartial":false,"Alternatives":[{"Transcript":"world "}]}]}}""")) {
                listener.onMessage(transport.socket, event(json).toByteString())
            }
            listener.onMessage(transport.socket, byteArrayOf(0).toByteString())
            listener.onMessage(transport.socket, event("").toByteString())
            listener.onMessage(transport.socket, event("invalid").toByteString())
            listener.onMessage(transport.socket, "not JSON")
            listener.onMessage(transport.socket, "{}")
            listener.onClosed(transport.socket, 1000, "done")
            listener.onClosed(transport.socket, 1000, "duplicate")
            listener.onFailure(transport.socket, IOException("late"), null)
        }
        assertThat(service.transcribe(audio, "zh-TW")).isEqualTo(SpeechResult.Success("hello world"))
        val frames = transport.sentBinary.map { it.toByteArray() }
        assertThat(frames).hasSize(3)
        val payloads = frames.map { bytes ->
            val buffer = ByteBuffer.wrap(bytes)
            assertThat(buffer.int).isEqualTo(bytes.size)
            val headers = buffer.int
            assertThat(buffer.int).isEqualTo(CRC32().apply { update(bytes, 0, 8) }.value.toInt())
            assertThat(ByteBuffer.wrap(bytes, bytes.size - 4, 4).int)
                .isEqualTo(CRC32().apply { update(bytes, 0, bytes.size - 4) }.value.toInt())
            bytes.copyOfRange(12 + headers, bytes.size - 4)
        }
        assertThat(payloads[0] + payloads[1]).isEqualTo(audio)
        assertThat(payloads.last()).isEmpty()
        val url = transport.requests.single().url
        assertThat(url.queryParameter("language-code")).isEqualTo("zh-TW")
        assertThat(url.queryParameter("X-Amz-Signature")).matches("[a-f0-9]{64}")
    }

    @Test fun `server error takes priority over transcript and empty completion is distinguished`() = runTest {
        transport.events = { it.onMessage(transport.socket, """{"Message":"quota exhausted"}"""); it.onClosed(transport.socket, 1000, "done") }
        val failure = service.transcribe(audio, "en") as SpeechResult.Error
        assertThat(failure.errorCode).isEqualTo(SpeechErrorCode.RECOGNITION_FAILED)
        assertThat(failure.message).contains("quota exhausted")
        transport.events = { it.onClosed(transport.socket, 1000, "done") }
        assertThat((service.transcribe(audio, "en") as SpeechResult.Error).errorCode).isEqualTo(SpeechErrorCode.NO_SPEECH_DETECTED)
    }

    @Test fun `connection failures preserve server details with malformed and absent body fallbacks`() = runTest {
        for ((body, expected) in listOf("""{"Message":"denied"}""" to "denied", "{}" to "offline", "invalid" to "offline", null to "offline")) {
            transport.events = { listener ->
                val response = body?.let { transport.response(Request.Builder().url("https://example.test").build(), 403, it) }
                listener.onFailure(transport.socket, IOException("offline"), response)
            }
            val result = service.transcribe(audio, "en") as SpeechResult.Error
            assertThat(result.errorCode).isEqualTo(SpeechErrorCode.NETWORK_ERROR)
            assertThat(result.message).contains(expected)
        }
        transport.events = { it.onFailure(transport.socket, IOException(), null) }
        assertThat((service.transcribe(audio, "en") as SpeechResult.Error).message).contains("Unknown error")
        every { transport.client.newWebSocket(any(), any()) } throws IOException("connection setup")
        assertThat((service.transcribe(audio, "en") as SpeechResult.Error).errorCode).isEqualTo(SpeechErrorCode.TRANSCRIPTION_ERROR)
    }

    @Test fun `unresponsive socket times out and is closed`() = runTest {
        val timed = AwsTranscribeSttService("fixture-id", "fixture-secret", ioDispatcher = StandardTestDispatcher(testScheduler))
        val offline = OfflineSttTransport(timed)
        offline.events = { }
        assertThat((timed.transcribe(audio, "en") as SpeechResult.Error).errorCode).isEqualTo(SpeechErrorCode.TRANSCRIPTION_TIMEOUT)
        verify { offline.socket.close(1000, "Cancelled") }
    }

    @Test fun `credential validation signs STS requests and maps HTTP and network errors`() = runTest {
        transport.http = { transport.response(it) }
        assertThat(service.validateCredentials()).isEqualTo(SttValidationResult.Valid)
        assertThat(transport.requests.single().header("Authorization")).contains("SignedHeaders=content-type;host;x-amz-date")
        for (code in listOf(401, 403, 429, 500)) {
            transport.http = { transport.response(it, code) }
            assertThat(service.validateCredentials()).isInstanceOf(SttValidationResult.Invalid::class.java)
        }
        transport.http = { throw SocketTimeoutException("deadline") }
        assertThat(service.validateCredentials()).isEqualTo(SttValidationResult.Invalid(SttValidationError.TIMEOUT))
        transport.http = { throw IOException("offline") }
        assertThat(service.validateCredentials()).isEqualTo(SttValidationResult.Invalid(SttValidationError.NETWORK_ERROR))
    }
}
