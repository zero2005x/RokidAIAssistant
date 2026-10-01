package com.example.rokidphone.service.stt

import android.util.Base64
import com.example.rokidphone.service.SpeechErrorCode
import com.example.rokidphone.service.SpeechResult
import com.google.common.truth.Truth.assertThat
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Request signing and response parsing for [IflytekSttService], driven through the
 * offline HTTP transport. The sibling IflytekSttServiceTest covers provider metadata.
 */
@RunWith(RobolectricTestRunner::class)
class IflytekRequestTest {

    private val service = IflytekSttService("app-1", "key-1", "secret-1")
    private val transport = OfflineSttTransport(service)
    private val audio = ByteArray(32_000) { (it % 127).toByte() }

    @After fun cleanup() = unmockkAll()

    private fun transcript(vararg words: String): String {
        val ws = JSONArray()
        words.forEach { word ->
            ws.put(JSONObject().put("cw", JSONArray().put(JSONObject().put("w", word))))
        }
        return JSONObject()
            .put("code", 0)
            .put("data", JSONObject().put("result", JSONObject().put("ws", ws)))
            .toString()
    }

    private fun sentBody(): JSONObject {
        val buffer = Buffer()
        transport.requests.last().body!!.writeTo(buffer)
        return JSONObject(buffer.readUtf8())
    }

    @Test
    fun `word segments are joined and the request is signed`() = runBlocking {
        transport.http = { transport.response(it, body = transcript("你好", "世界")) }

        assertThat(service.transcribe(audio, "zh-TW")).isEqualTo(SpeechResult.Success("你好世界"))

        val request = transport.requests.single()
        assertThat(request.url.host).isEqualTo("iat-api.xfyun.cn")
        assertThat(request.header("Date")).isNotEmpty()
        val authorization = String(Base64.decode(request.header("Authorization"), Base64.NO_WRAP))
        assertThat(authorization).contains("api_key=\"key-1\"")
        assertThat(authorization).contains("algorithm=\"hmac-sha256\"")

        val body = sentBody()
        assertThat(body.getJSONObject("common").getString("app_id")).isEqualTo("app-1")
        assertThat(body.getJSONObject("business").getString("language")).isEqualTo("zh_cn")
        assertThat(body.getJSONObject("data").getInt("status")).isEqualTo(2)
        assertThat(Base64.decode(body.getJSONObject("data").getString("audio"), Base64.NO_WRAP))
            .isEqualTo(audio)
    }

    @Test
    fun `the request language follows the caller and defaults to Chinese`() = runBlocking {
        transport.http = { transport.response(it, body = transcript("hi")) }

        service.transcribe(audio, "en-GB")
        assertThat(sentBody().getJSONObject("business").getString("language")).isEqualTo("en_us")

        service.transcribe(audio, "ko-KR")
        assertThat(sentBody().getJSONObject("business").getString("language")).isEqualTo("zh_cn")
    }

    @Test
    fun `audio shorter than the minimum is rejected before any request`() = runBlocking {
        val result = service.transcribe(ByteArray(8), "zh") as SpeechResult.Error

        assertThat(result.errorCode).isEqualTo(SpeechErrorCode.AUDIO_TOO_SHORT)
        verify(exactly = 0) { transport.client.newCall(any()) }
    }

    @Test
    fun `unusable responses are reported as unrecognised speech`() = runBlocking {
        val unusable = listOf(
            JSONObject().put("code", 10105).put("message", "illegal app").toString(),
            JSONObject().put("code", 0).toString(),                                   // no data
            JSONObject().put("code", 0).put("data", JSONObject()).toString(),          // no result
            JSONObject().put("code", 0).put(
                "data", JSONObject().put("result", JSONObject())
            ).toString(),                                                              // no word segments
            transcript("", " "),                                                       // blank words
            "not json"
        )
        for (body in unusable) {
            transport.http = { transport.response(it, body = body) }
            val result = service.transcribe(audio, "zh") as SpeechResult.Error
            assertThat(result.errorCode).isEqualTo(SpeechErrorCode.UNABLE_TO_RECOGNIZE)
        }

        transport.http = { transport.response(it, code = 500, body = "boom") }
        assertThat((service.transcribe(audio, "zh") as SpeechResult.Error).errorCode)
            .isEqualTo(SpeechErrorCode.UNABLE_TO_RECOGNIZE)
    }

    @Test
    fun `a transport failure is retried and then reported as unrecognised`() = runBlocking {
        var attempts = 0
        transport.http = { attempts++; throw IOException("dns failure") }

        val result = service.transcribe(audio, "zh") as SpeechResult.Error

        assertThat(attempts).isEqualTo(BaseSttService.MAX_RETRIES)
        assertThat(result.errorCode).isEqualTo(SpeechErrorCode.UNABLE_TO_RECOGNIZE)
    }

    @Test
    fun `validation maps the documented iFLYTEK result codes`() = runBlocking {
        val expected = mapOf(
            0 to null, 10160 to null,
            10105 to SttValidationError.INVALID_CREDENTIALS,
            10106 to SttValidationError.INVALID_CREDENTIALS,
            10107 to SttValidationError.INVALID_CREDENTIALS,
            10114 to SttValidationError.RATE_LIMITED,
            99999 to SttValidationError.UNKNOWN
        )
        for ((code, error) in expected) {
            transport.http = { transport.response(it, body = JSONObject().put("code", code).toString()) }
            val result = service.validateCredentials()
            if (error == null) {
                assertThat(result).isEqualTo(SttValidationResult.Valid)
            } else {
                assertThat((result as SttValidationResult.Invalid).error).isEqualTo(error)
            }
        }
    }

    @Test
    fun `validation maps transport problems to their own errors`() = runBlocking {
        transport.http = { transport.response(it, code = 401, body = "{}") }
        assertThat(service.validateCredentials()).isInstanceOf(SttValidationResult.Invalid::class.java)

        transport.http = { throw SocketTimeoutException("slow") }
        assertThat((service.validateCredentials() as SttValidationResult.Invalid).error)
            .isEqualTo(SttValidationError.TIMEOUT)

        transport.http = { throw IOException("offline") }
        assertThat((service.validateCredentials() as SttValidationResult.Invalid).error)
            .isEqualTo(SttValidationError.NETWORK_ERROR)

        transport.http = { throw IllegalStateException("unexpected") }
        assertThat((service.validateCredentials() as SttValidationResult.Invalid).error)
            .isEqualTo(SttValidationError.UNKNOWN)
    }
}
