package com.example.rokidphone.service.stt

import io.mockk.every
import io.mockk.mockk
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.ByteString

/** In-memory transport: unexpected HTTP or WebSocket traffic fails the test. */
internal class OfflineSttTransport(service: BaseSttService) {
    val client = mockk<OkHttpClient>()
    val socket = mockk<WebSocket>(relaxed = true)
    val sentText = mutableListOf<String>()
    val sentBinary = mutableListOf<ByteString>()
    val requests = mutableListOf<Request>()
    var http: (Request) -> Response = { error("Unexpected HTTP request: ${it.url.host}") }
    var events: (WebSocketListener) -> Unit = { error("Unexpected WebSocket request") }

    init {
        BaseSttService::class.java.getDeclaredField("client\$delegate").apply { isAccessible = true }
            .set(service, lazyOf(client))
        every { socket.send(any<String>()) } answers { sentText += firstArg<String>(); true }
        every { socket.send(any<ByteString>()) } answers { sentBinary += firstArg<ByteString>(); true }
        every { client.newCall(any()) } answers {
            val request = firstArg<Request>()
            requests += request
            mockk<Call>().also { call -> every { call.execute() } answers { http(request) } }
        }
        every { client.newWebSocket(any(), any()) } answers {
            val request = firstArg<Request>()
            requests += request
            val listener = secondArg<WebSocketListener>()
            listener.onOpen(socket, response(request, 101, ""))
            events(listener)
            socket
        }
    }

    fun response(request: Request, code: Int = 200, body: String = "{}") = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("offline fixture")
        .body(body.toResponseBody()).build()
}
