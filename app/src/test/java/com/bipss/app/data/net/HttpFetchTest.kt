package com.bipss.app.data.net

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class HttpFetchTest {

    private val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()

    private fun request(server: ServerSocket) =
        Request.Builder().url("http://127.0.0.1:${server.localPort}/").build()

    @Test
    fun `a time limit cuts short a call the server never answers`() {
        ServerSocket(0).use { server ->
            val held = java.util.Collections.synchronizedList(mutableListOf<Socket>())
            thread(isDaemon = true) { runCatching { while (true) held += server.accept() } }

            val started = System.nanoTime()
            val result = runBlocking { withTimeoutOrNull(300) { client.fetchText(request(server)) } }
            val tookMs = (System.nanoTime() - started) / 1_000_000

            // A blocking execute() would sit out the 60 s read timeout here.
            assertNull(result)
            assertTrue("took $tookMs ms", tookMs < 3_000)
            synchronized(held) { held.forEach { runCatching { it.close() } } }
        }
    }

    @Test
    fun `the status and the whole body come back`() {
        ServerSocket(0).use { server ->
            thread(isDaemon = true) {
                runCatching {
                    server.accept().use { s ->
                        val input = s.getInputStream().bufferedReader()
                        while (input.readLine().orEmpty().isNotEmpty()) { /* request headers */ }
                        s.getOutputStream().write(
                            "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 4\r\nConnection: close\r\n\r\nbusy".toByteArray()
                        )
                        s.getOutputStream().flush()
                    }
                }
            }
            val answer = runBlocking { withTimeoutOrNull(5_000) { client.fetchText(request(server)) } }
            assertEquals(503, answer?.code)
            assertEquals("busy", answer?.body)
            assertTrue(answer?.isSuccessful == false)
        }
    }
}
