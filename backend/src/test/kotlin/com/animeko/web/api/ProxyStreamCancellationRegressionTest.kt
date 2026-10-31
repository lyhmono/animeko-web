package com.animeko.web.api

import com.animeko.web.config.AppConfig
import com.animeko.web.http.Fetcher
import com.animeko.web.http.HttpResult
import com.animeko.web.http.StreamResult
import com.animeko.web.module
import com.animeko.web.testDirectory
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real Netty sockets; the deliberately blocking upstream InputStream makes close observable. */
class ProxyStreamCancellationRegressionTest {
    private class StalledBody : InputStream() {
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val closes = AtomicInteger()
        @Volatile var readThread = ""
        override fun read(): Int = read(ByteArray(1), 0, 1)
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            readThread = Thread.currentThread().name
            entered.countDown()
            try {
                closed.await()
                throw IOException("fixture stream closed")
            } finally { exited.countDown() }
        }
        override fun close() { closes.incrementAndGet(); closed.countDown() }
        fun cleanup() { if (closed.count > 0) close() }
    }

    private class FixtureFetcher(private val body: StalledBody, private val contentType: String) : Fetcher {
        override suspend fun fetchStream(url: String, headers: Map<String, String>, followRedirects: Boolean) =
            StreamResult(200, mapOf("content-type" to listOf(contentType)), body)
        override suspend fun fetch(url: String, headers: Map<String, String>, followRedirects: Boolean): HttpResult = error("unused fixture method")
        override suspend fun post(url: String, body: String, headers: Map<String, String>, contentType: String): HttpResult = error("unused fixture method")
    }

    private class Server(body: StalledBody, contentType: String, timeoutMs: Long) : AutoCloseable {
        private val cfg = AppConfig(
            authEnabled = false, allowPrivateTargets = true, proxyTimeoutMs = timeoutMs,
            dataDir = testDirectory("animeko-proxy-close"), frontendDir = "missing",
            subscriptionAutoImport = emptyList(), seedSubscriptionUrl = null,
        )
        private val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { module(cfg, FixtureFetcher(body, contentType)) }.start(wait = false)
        val port = runBlocking { server.engine.resolvedConnectors().single().port }
        fun path(media: String): String = "/api/proxy?url=" + URLEncoder.encode(media, "UTF-8")
        fun uri(media: String): URI = URI("http://127.0.0.1:$port" + path(media))
        override fun close() { server.stop(0, 1_000) }
    }

    @Test
    fun `stalled finite playlist times out closes upstream and frees its blocking read`() {
        val body = StalledBody()
        var status: Int? = null
        var automaticallyClosed = false
        var readerExited = false
        Server(body, "application/vnd.apple.mpegurl", 100).use { server ->
            val future = HttpClient.newHttpClient().sendAsync(
                HttpRequest.newBuilder(server.uri("https://media.test/master.m3u8")).timeout(Duration.ofSeconds(4)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
            try {
                assertTrue(body.entered.await(3, TimeUnit.SECONDS), "fixture read never started")
                status = runCatching { future.get(2, TimeUnit.SECONDS).statusCode() }.getOrNull()
                automaticallyClosed = body.closed.await(500, TimeUnit.MILLISECONDS)
                readerExited = body.exited.await(500, TimeUnit.MILLISECONDS)
            } finally { body.cleanup(); future.cancel(true) }
        }
        assertNotNull(status, "finite playlist body ignored proxyTimeoutMs after headers arrived")
        assertEquals(502, status)
        assertTrue(automaticallyClosed, "timeout must actively close the upstream InputStream")
        assertTrue(readerExited, "closing must actually unblock the read task")
        assertEquals(1, body.closes.get())
        assertTrue(body.readThread.contains("DefaultDispatcher"), "blocking playlist reads must use IO, not a Netty request thread: ${body.readThread}")
    }

    private fun disconnect(contentType: String, media: String) {
        val body = StalledBody()
        var automaticallyClosed = false
        var readerExited = false
        Server(body, contentType, 60_000).use { server ->
            try {
                Socket("127.0.0.1", server.port).use { client ->
                    client.getOutputStream().write(("GET ${server.path(media)} HTTP/1.1\r\nHost: 127.0.0.1:${server.port}\r\nConnection: close\r\n\r\n").toByteArray())
                    client.getOutputStream().flush()
                    assertTrue(body.entered.await(3, TimeUnit.SECONDS), "upstream body must be reached before disconnect")
                }
                automaticallyClosed = body.closed.await(1_500, TimeUnit.MILLISECONDS)
                readerExited = body.exited.await(500, TimeUnit.MILLISECONDS)
            } finally { body.cleanup() }
        }
        assertTrue(automaticallyClosed, "downstream socket disconnected but upstream read remained blocked: $contentType")
        assertTrue(readerExited, "cancelled body reader remained alive: $contentType")
        assertEquals(1, body.closes.get())
    }

    @Test
    fun `disconnect during playlist read actively closes the upstream body`() =
        disconnect("application/vnd.apple.mpegurl", "https://media.test/master.m3u8")

    @Test
    fun `disconnect during unlimited video stream actively closes the upstream body`() =
        disconnect("video/mp4", "https://media.test/movie.mp4")
}
