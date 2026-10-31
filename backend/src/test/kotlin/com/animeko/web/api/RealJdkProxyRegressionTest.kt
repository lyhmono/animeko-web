package com.animeko.web.api

import com.animeko.web.config.AppConfig
import com.animeko.web.module
import com.animeko.web.testDirectory
import com.sun.net.httpserver.HttpServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Full loopback chain: real JDK HttpClient -> stalled HTTP socket -> Netty proxy -> real downstream socket. */
class RealJdkProxyRegressionTest {
    private fun headers(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        var tail = ""
        while (bytes.size() < 16 * 1024) {
            val next = input.read()
            check(next >= 0) { "EOF before HTTP headers" }
            bytes.write(next)
            tail = (tail + next.toChar()).takeLast(4)
            if (tail == "\r\n\r\n") return bytes.toString(Charsets.ISO_8859_1)
        }
        error("fixture header budget exceeded")
    }

    private inner class StalledUpstream(private val contentType: String) : AutoCloseable {
        private val listener = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        private val socket = AtomicReference<Socket>()
        val headersSent = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        @Volatile var requestedHeaders = ""
        val url = "http://127.0.0.1:${listener.localPort}/resource"
        private val worker = thread(name = "fixture-stalled-http", isDaemon = true) {
            try {
                listener.accept().use { peer ->
                    socket.set(peer)
                    requestedHeaders = headers(peer.getInputStream())
                    peer.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: 100000\r\n\r\n").toByteArray())
                    peer.getOutputStream().write(if (contentType.contains("mpegurl")) '#'.code else 'X'.code)
                    peer.getOutputStream().flush()
                    headersSent.countDown()
                    // No more body bytes and no upstream EOF. Only an active client close ends this fixture.
                    while (peer.getInputStream().read() != -1) { }
                }
            } catch (_: Exception) {
                // Cancellation may close/reset the socket rather than send a clean FIN.
            } finally { disconnected.countDown() }
        }
        override fun close() {
            socket.get()?.close(); listener.close(); worker.join(3_000)
        }
    }

    private class Proxy(private val timeout: Long) : AutoCloseable {
        private val cfg = AppConfig(
            authEnabled = false, allowPrivateTargets = true, proxyTimeoutMs = timeout,
            requestTimeoutMs = 3_000, bangumiTimeoutMs = 3_000,
            dataDir = testDirectory("animeko-real-jdk-proxy"), frontendDir = "missing",
            subscriptionAutoImport = emptyList(), seedSubscriptionUrl = null,
        )
        private val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { module(cfg) }.start(wait = false)
        val port = runBlocking { server.engine.resolvedConnectors().single().port }
        fun path(url: String) = "/api/proxy?url=" + URLEncoder.encode(url, "UTF-8")
        fun uri(url: String) = URI("http://127.0.0.1:$port" + path(url))
        override fun close() { server.stop(0, 1_000) }
    }

    @Test
    fun `real JDK stalled playlist body reaches deadline and closes the upstream TCP connection`() {
        StalledUpstream("application/vnd.apple.mpegurl").use { upstream ->
            Proxy(500).use { proxy ->
                val request = HttpClient.newHttpClient().sendAsync(
                    HttpRequest.newBuilder(proxy.uri(upstream.url)).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofByteArray(),
                )
                assertTrue(upstream.headersSent.await(3, TimeUnit.SECONDS), "real upstream did not send headers")
                assertEquals(502, request.get(3, TimeUnit.SECONDS).statusCode())
                assertTrue(upstream.disconnected.await(1_500, TimeUnit.MILLISECONDS), "JDK body deadline did not actively close its TCP exchange")
                assertTrue(upstream.requestedHeaders.lines().any { it.equals("Accept-Encoding: identity", true) }, upstream.requestedHeaders)
            }
        }
    }

    @Test
    fun `real JDK binary read is released by downstream disconnect without a media total deadline`() {
        StalledUpstream("video/mp4").use { upstream ->
            Proxy(10_000).use { proxy ->
                Socket("127.0.0.1", proxy.port).use { client ->
                    client.soTimeout = 3_000
                    client.getOutputStream().write(("GET ${proxy.path(upstream.url)} HTTP/1.1\r\nHost: 127.0.0.1:${proxy.port}\r\nConnection: close\r\n\r\n").toByteArray())
                    client.getOutputStream().flush()
                    assertTrue(upstream.headersSent.await(3, TimeUnit.SECONDS))
                    // A one-byte body may be buffered by Netty. Wait for the actual JDK body
                    // reader instead of depending on response-header flushing of that tiny chunk.
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                    var reading = false
                    while (!reading && System.nanoTime() < deadline) {
                        reading = Thread.getAllStackTraces().values.any { frames ->
                            frames.any { it.className.contains("ResponseSubscribers\$HttpResponseInputStream") && it.methodName == "read" }
                        }
                        if (!reading) Thread.sleep(5)
                    }
                    assertTrue(reading, "real JDK body read must be active before downstream disconnect")
                }
                assertTrue(upstream.disconnected.await(1_500, TimeUnit.MILLISECONDS), "downstream left but real JDK upstream socket stayed open")
            }
        }
    }

    @Test
    fun `real JDK gzip playlist bytes and headers remain playable through Netty rewrite`() {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write("#EXTM3U\n#EXTINF:5,\nsegment.ts\n".toByteArray()) }
        val packed = out.toByteArray()
        val encoding = AtomicReference<String>()
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.createContext("/master.m3u8") { exchange ->
            encoding.set(exchange.requestHeaders.getFirst("Accept-Encoding"))
            exchange.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
            exchange.responseHeaders.add("Content-Encoding", "gzip")
            exchange.sendResponseHeaders(200, packed.size.toLong())
            exchange.responseBody.use { it.write(packed) }
            exchange.close()
        }
        upstream.start()
        try {
            Proxy(3_000).use { proxy ->
                val response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(proxy.uri("http://127.0.0.1:${upstream.address.port}/master.m3u8")).GET().build(), HttpResponse.BodyHandlers.ofString())
                assertEquals(200, response.statusCode())
                assertTrue(response.body().startsWith("#EXTM3U"), response.body())
                assertTrue("/api/proxy?url=" in response.body())
                assertEquals("identity", encoding.get())
                assertTrue(response.headers().firstValue("Content-Encoding").isEmpty, "rewritten decoded body retained gzip header")
            }
        } finally { upstream.stop(0) }
    }
}
