package com.animeko.web.api

import com.animeko.web.config.AppConfig
import com.animeko.web.http.Fetcher
import com.animeko.web.http.HttpResult
import com.animeko.web.http.StreamResult
import com.animeko.web.module
import com.animeko.web.proxy.ProxySessionStore
import com.animeko.web.testDirectory
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProxyRepresentationRegressionTest {
    private class Body(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        val closes = AtomicInteger()
        override fun close() { closes.incrementAndGet(); super.close() }
    }
    private class Stub(private val status: Int, private val bytes: ByteArray, private val responseHeaders: Map<String, List<String>>) : Fetcher {
        val body = Body(bytes)
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        override suspend fun fetchStream(url: String, headers: Map<String, String>, followRedirects: Boolean): StreamResult {
            requests.add(url to LinkedHashMap(headers))
            return StreamResult(status, responseHeaders, body)
        }
        override suspend fun fetch(url: String, headers: Map<String, String>, followRedirects: Boolean): HttpResult = error("unused")
        override suspend fun post(url: String, body: String, headers: Map<String, String>, contentType: String): HttpResult = error("unused")
    }
    private fun cfg() = AppConfig(
        authEnabled = false, allowPrivateTargets = true, frontendDir = "missing", dataDir = testDirectory("animeko-proxy-repr"),
        subscriptionAutoImport = emptyList(), seedSubscriptionUrl = null,
    )
    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(bytes) } }.toByteArray()

    @Test
    fun `gzip upstream playlist is decoded before bounded rewriting even when identity was requested`() = testApplication {
        val packed = gzip("#EXTM3U\n#EXTINF:10,\nsegment.ts\n".toByteArray())
        val stub = Stub(200, packed, mapOf("content-type" to listOf("application/vnd.apple.mpegurl"), "content-encoding" to listOf("gzip"), "content-length" to listOf(packed.size.toString())))
        application { module(cfg(), stub) }
        val response = client.get("/api/proxy") { parameter("url", "https://media.test/master.m3u8") }
        assertEquals(HttpStatusCode.OK, response.status)
        val text = response.bodyAsText()
        assertTrue(text.startsWith("#EXTM3U"), "compressed bytes were parsed as UTF-8: ${text.take(80)}")
        assertTrue("/api/proxy?url=" in text)
        assertTrue("segment.ts" in java.net.URLDecoder.decode(text, "UTF-8"))
        assertFalse(response.headers[HttpHeaders.ContentEncoding] == "gzip", "rewritten plain bytes cannot retain upstream gzip header")
        response.headers[HttpHeaders.ContentLength]?.let { assertEquals(response.bodyAsBytes().size.toLong(), it.toLong()) }
        assertEquals("identity", stub.requests.single().second[HttpHeaders.AcceptEncoding])
        assertEquals(1, stub.body.closes.get())
    }

    @Test
    fun `compressed binary representation preserves encoding length and exact entity bytes`() = testApplication {
        val packed = gzip("<svg xmlns=\"http://www.w3.org/2000/svg\"><text>fixture</text></svg>".toByteArray())
        val stub = Stub(200, packed, mapOf("content-type" to listOf("image/svg+xml"), "content-encoding" to listOf("gzip"), "content-length" to listOf(packed.size.toString())))
        application { module(cfg(), stub) }
        val response = client.get("/api/proxy") {
            parameter("url", "https://media.test/image.svg")
            header(HttpHeaders.AcceptEncoding, "gzip")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("gzip", response.headers[HttpHeaders.ContentEncoding])
        assertEquals(packed.size.toString(), response.headers[HttpHeaders.ContentLength])
        assertContentEquals(packed, response.bodyAsBytes())
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertEquals("sandbox", response.headers["Content-Security-Policy"])
        assertEquals(1, stub.body.closes.get())
    }

    @Test
    fun `already encoded text entity is not recompressed when the client also accepts gzip`() = testApplication {
        val random = java.util.Random(0)
        val text = ByteArray(8_192) { (32 + random.nextInt(95)).toByte() }
        val packed = gzip(text)
        val stub = Stub(200, packed, mapOf("content-type" to listOf("text/plain"), "content-encoding" to listOf("gzip"), "content-length" to listOf(packed.size.toString())))
        application { module(cfg(), stub) }
        val response = client.get("/api/proxy") { parameter("url", "https://media.test/resource.txt"); header(HttpHeaders.AcceptEncoding, "gzip") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("gzip", response.headers[HttpHeaders.ContentEncoding])
        assertContentEquals(packed, response.bodyAsBytes(), "proxy compression must not wrap an already encoded upstream entity again")
    }

    @Test
    fun `playlist decompression is limited by decoded bytes rather than compressed size`() = testApplication {
        val text = "#EXTM3U\n" + "# harmless comment\n".repeat(300_000)
        val packed = gzip(text.toByteArray())
        assertTrue(packed.size < 4 * 1024 * 1024)
        val stub = Stub(200, packed, mapOf("content-type" to listOf("application/vnd.apple.mpegurl"), "content-encoding" to listOf("gzip")))
        application { module(cfg(), stub) }
        val response = client.get("/api/proxy") { parameter("url", "https://media.test/master.m3u8") }
        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertTrue("上限" in response.bodyAsText())
        assertEquals(1, stub.body.closes.get())
    }

    @Test
    fun `m3u8 in a query or intermediate directory cannot turn binary range bytes into a playlist`() = testApplication {
        val bytes = byteArrayOf(0, 1, 127, -1, -128, 10)
        val stub = Stub(206, bytes, mapOf("content-type" to listOf("video/mp4"), "content-range" to listOf("bytes 2-7/100"), "accept-ranges" to listOf("bytes"), "content-length" to listOf(bytes.size.toString())))
        application { module(cfg(), stub) }
        for (url in listOf("https://media.test/video.mp4?referer=index.m3u8", "https://media.test/folder.m3u8/video.mp4")) {
            val response = client.get("/api/proxy") { parameter("url", url); header(HttpHeaders.Range, "bytes=2-7") }
            assertEquals(HttpStatusCode.PartialContent, response.status, url)
            assertContentEquals(bytes, response.bodyAsBytes(), url)
            assertEquals("bytes 2-7/100", response.headers[HttpHeaders.ContentRange])
            // Reset the fixture body for the second independent entity response.
            stub.body.reset()
        }
    }

    @Test
    fun `explicit binary MIME overrides a misleading playlist path suffix`() = testApplication {
        val bytes = byteArrayOf(0, -1, -128, 1)
        val stub = Stub(200, bytes, mapOf("content-type" to listOf("video/mp2t")))
        application { module(cfg(), stub) }
        val response = client.get("/api/proxy") { parameter("url", "https://media.test/not-a-playlist.m3u8") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertContentEquals(bytes, response.bodyAsBytes())
        assertEquals("video/mp2t", response.headers[HttpHeaders.ContentType])
    }

    @Test
    fun `playlist MIME identifies extensionless HLS without consulting query text`() = testApplication {
        val stub = Stub(200, "#EXTM3U\nsegment.ts\n".toByteArray(), mapOf("content-type" to listOf("application/vnd.apple.mpegurl")))
        application { module(cfg(), stub) }
        val response = client.get("/api/proxy") { parameter("url", "https://media.test/manifest?format=video.mp4") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue("/api/proxy?url=" in response.bodyAsText())
    }

    @Test
    fun `partial playlist representation is forwarded as 206 without rewriting incomplete text`() = testApplication {
        val bytes = "EXTM".toByteArray()
        val stub = Stub(206, bytes, mapOf("content-type" to listOf("application/vnd.apple.mpegurl"), "content-range" to listOf("bytes 1-4/100"), "content-length" to listOf(bytes.size.toString())))
        application { module(cfg(), stub) }
        val response = client.get("/api/proxy") { parameter("url", "https://media.test/master.m3u8"); header(HttpHeaders.Range, "bytes=1-4") }
        assertEquals(HttpStatusCode.PartialContent, response.status)
        assertContentEquals(bytes, response.bodyAsBytes())
        assertEquals("bytes 1-4/100", response.headers[HttpHeaders.ContentRange])
    }

    @Test
    fun `complete plain HLS still uses gzip after rewriting when the client accepts it`() = testApplication {
        val prefix = "#EXTM3U\n" + "# complete playlist comment\n".repeat(400)
        val stub = Stub(200, (prefix + "segment.ts\n").toByteArray(), mapOf(
            "content-type" to listOf("application/vnd.apple.mpegurl"),
        ))
        application { module(cfg(), stub) }
        val response = client.get("/api/proxy") {
            parameter("url", "https://media.test/master.m3u8")
            header(HttpHeaders.AcceptEncoding, "gzip")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("gzip", response.headers[HttpHeaders.ContentEncoding])
        assertEquals(null, response.headers[HttpHeaders.ContentRange])
        val decoded = GZIPInputStream(ByteArrayInputStream(response.bodyAsBytes())).use { it.readBytes().toString(Charsets.UTF_8) }
        assertTrue(decoded.startsWith(prefix))
        assertTrue("/api/proxy?url=" in decoded)
        assertTrue("https://media.test/segment.ts" in java.net.URLDecoder.decode(decoded, "UTF-8"))
        assertEquals(1, stub.body.closes.get())
    }

    @Test
    fun `partial HLS bytes cannot be gzip compressed under an unchanged content range`() = testApplication {
        val bytes = "# partial playlist comment\n".repeat(400).toByteArray()
        val range = "bytes 20-${19 + bytes.size}/${bytes.size + 100}"
        val stub = Stub(206, bytes, mapOf(
            "content-type" to listOf("application/vnd.apple.mpegurl"),
            "content-range" to listOf(range), "accept-ranges" to listOf("bytes"),
            "content-length" to listOf(bytes.size.toString()),
        ))
        application { module(cfg(), stub) }
        val response = client.get("/api/proxy") {
            parameter("url", "https://media.test/master.m3u8")
            header(HttpHeaders.Range, "bytes=20-${19 + bytes.size}")
            header(HttpHeaders.AcceptEncoding, "gzip")
        }
        assertEquals(HttpStatusCode.PartialContent, response.status)
        assertEquals(range, response.headers[HttpHeaders.ContentRange])
        assertEquals(null, response.headers[HttpHeaders.ContentEncoding], "gzip would invalidate the original byte offsets")
        assertContentEquals(bytes, response.bodyAsBytes())
        assertEquals(bytes.size.toString(), response.headers[HttpHeaders.ContentLength])
        assertEquals("bytes", response.headers[HttpHeaders.AcceptRanges])
        assertEquals("identity", stub.requests.single().second[HttpHeaders.AcceptEncoding])
        assertEquals("bytes=20-${19 + bytes.size}", stub.requests.single().second[HttpHeaders.Range])
        assertEquals(1, stub.body.closes.get())
    }

    @Test
    fun `HTTPS playlist HTTP children never recover the source cookie on a new request`() = testApplication {
        val sessions = ProxySessionStore()
        val sid = sessions.put("https://media.test/master.m3u8", "UA", "https://media.test/watch", "secret=x")
        val stub = Stub(200, "#EXTM3U\nhttp://media.test/segment.ts\n".toByteArray(), mapOf("content-type" to listOf("application/vnd.apple.mpegurl")))
        application { module(cfg(), stub, sessions) }
        val playlist = client.get("/api/proxy") { parameter("url", "https://media.test/master.m3u8"); parameter("sid", sid) }.bodyAsText()
        assertEquals("secret=x", stub.requests.single().second["Cookie"])
        val child = playlist.lines().last { it.isNotBlank() && !it.startsWith('#') }
        stub.body.reset()
        assertEquals(HttpStatusCode.OK, client.get(child).status)
        assertFalse("Cookie" in stub.requests.last().second, "new grant restored an HTTPS source cookie to HTTP")
    }
}
