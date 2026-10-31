package com.animeko.web.api

import com.animeko.web.config.AppConfig
import com.animeko.web.http.Fetcher
import com.animeko.web.http.HttpResult
import com.animeko.web.http.StreamResult
import com.animeko.web.module
import com.animeko.web.proxy.ProxySessionStore
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.net.URLEncoder
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * /api/proxy 的流式打桩：按顺序吐出 specs，并记录每一次上游请求的地址与请求头。
 * 这段链路此前没有任何测试（数据源测试里的 fetchStream 直接抛异常），
 * 而 SSRF、重定向、鉴权头透传等问题全部集中在它身上。
 */
private class StreamStubFetcher(
    private val specs: List<Spec>,
    private val html: Map<String, String> = emptyMap(),
) : Fetcher {
    val requests = mutableListOf<Pair<String, Map<String, String>>>()
    /** 每一次 fetchStream 实际收到的 followRedirects 值，见上面的注释 */
    val redirectFlags = mutableListOf<Boolean>()

    data class Spec(
        val status: Int = 200,
        val contentType: String = "application/vnd.apple.mpegurl",
        val body: String = "",
        val headers: Map<String, List<String>> = emptyMap(),
        /** 需要自定义上游流时用这个（例如「给完 8 字节就不再给」的卡死上游） */
        val stream: (() -> java.io.InputStream)? = null,
    )

    override suspend fun fetchStream(
        url: String,
        headers: Map<String, String>,
        followRedirects: Boolean,
    ): StreamResult {
        // 记下调用方到底传了什么：代理的 SSRF 防线是「每一跳自己重新过 UrlGuard」，
        // 而那只成立如果 HTTP 客户端自己不跳。原先这个参数被直接丢掉，
        // 于是「有人把 fetchStream(..., followRedirects = false) 改成默认值」
        // 这类回归在本类里根本测不出来（真实 JdkFetcher 的默认 client 是 NORMAL）。
        redirectFlags.add(followRedirects)
        // 存副本：代理在跨主机重定向后会就地 remove("Cookie")，
        // 直接持有同一个 map 会让第一次请求的记录被改写，断言失去意义。
        requests.add(url to LinkedHashMap(headers))
        val spec = specs[minOf(requests.lastIndex, specs.lastIndex)]
        val merged = LinkedHashMap<String, List<String>>()
        merged["content-type"] = listOf(spec.contentType)
        spec.headers.forEach { (k, v) -> merged[k] = v }
        return StreamResult(spec.status, merged, spec.stream?.invoke() ?: spec.body.byteInputStream())
    }

    val fetchHeaders = mutableListOf<Pair<String, Map<String, String>>>()

    override suspend fun fetch(
        url: String,
        headers: Map<String, String>,
        followRedirects: Boolean,
    ): HttpResult {
        fetchHeaders.add(url to LinkedHashMap(headers))
        val body = html[url] ?: html.entries.firstOrNull { url.startsWith(it.key) }?.value
            ?: throw IllegalStateException("no fixture for $url")
        return HttpResult(200, mapOf("content-type" to listOf("text/html")), body.toByteArray())
    }

    override suspend fun post(
        url: String,
        body: String,
        headers: Map<String, String>,
        contentType: String,
    ): HttpResult = throw UnsupportedOperationException("本测试只覆盖代理链路")
}

private fun hlsPlaylist(segments: String) = StreamStubFetcher.Spec(
    contentType = "application/vnd.apple.mpegurl",
    body = "#EXTM3U\n#EXTINF:10,\n$segments\n",
)

private fun redirect(location: String) = StreamStubFetcher.Spec(
    status = 302,
    contentType = "text/plain",
    headers = mapOf("location" to listOf(location)),
)

/**
 * 像 JDK 那样「关掉再读就抛」的响应体流，并记录是否被关过。
 * `Stub` 默认用 ByteArrayInputStream，而它的 close() 是空操作 ——
 * 用它测「提前关流 / 忘关流」这两类 bug 都会假通过，所以必须自己实现。
 */
private class TrackedStream : java.io.InputStream() {
    var closed = false
        private set

    override fun read(): Int {
        check(!closed) { "Stream is closed" }
        return -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        check(!closed) { "Stream is closed" }
        return -1
    }

    override fun close() { closed = true }
}

private const val UA = "SecretUA/1.0"
private const val REFERER = "https://v.example.com/watch"
private const val COOKIE = "token=***"
private const val MEDIA = "https://cdn.example.com/hls/index.m3u8"

private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

class ProxyRouteTest {

    /**
     * allowPrivate 默认 true：本类用例都带 .example.com 这类假域名，
     * 严格的 UrlGuard 会做真实 DNS 解析；SSRF 用例单独传 false（目标全是字面 IP，不触发解析）。
     */
    private class Harness(
        specs: List<StreamStubFetcher.Spec>,
        allowPrivate: Boolean = true,
        authEnabled: Boolean = false,
        token: String? = null,
        val html: Map<String, String> = emptyMap(),
        proxyTimeoutMs: Long = 120_000,
    ) {
        val stub = StreamStubFetcher(specs, html)
        val sessions = ProxySessionStore()

        /** sid 绑定主机：按真实解析地址登记，只有那台主机能用它 */
        fun sessionFor(url: String): String = sessions.put(url, UA, REFERER, COOKIE)
        val sid: String = sessionFor(MEDIA)
        val cfg = AppConfig(
            dataDir = Files.createTempDirectory("animeko-proxy-test"),
            frontendDir = "definitely-not-exist",
            allowPrivateTargets = allowPrivate,
            authEnabled = authEnabled,
            authToken = token,
            proxyTimeoutMs = proxyTimeoutMs,
        )
    }

    @Test
    fun `segments carry the session id and never the credentials`() = testApplication {
        val h = Harness(listOf(hlsPlaylist("seg1.ts")))
        application { module(h.cfg, h.stub, h.sessions) }
        val resp = client.get("/api/proxy") {
            parameter("url", "https://cdn.example.com/hls/index.m3u8")
            parameter("sid", h.sid)
        }
        val text = resp.bodyAsText()
        assertEquals(HttpStatusCode.OK, resp.status)

        // 凭据从服务端会话库取，上游照样收得到
        val sent = h.stub.requests.single().second
        assertEquals(UA, sent["User-Agent"])
        assertEquals(REFERER, sent["Referer"])
        assertEquals(COOKIE, sent["Cookie"])

        // 但改写出来的分片地址只带 sid，不带任何凭据
        assertTrue(text.contains("sid=${h.sid}"), text)
        // 必须连百分号编码形式与参数名一起查：旧实现写的是 &ua=SecretUA%2F1.0，
        // 只断言 contains(原文) 对 broken 实现也会假通过
        for (leaked in listOf(UA, REFERER, COOKIE, enc(UA), enc(REFERER), enc(COOKIE))) {
            assertFalse(text.contains(leaked), "分片地址泄露了凭据「$leaked」：$text")
        }
        for (key in listOf("ua=", "referer=", "cookie=")) {
            assertFalse(text.contains(key), "分片地址残留凭据参数「$key」：$text")
        }
    }

    @Test
    fun `an upstream refusal on a playlist is not served as a valid 200 playlist`() = testApplication {
        // 回归：HLS 分支以前无论上游回什么都走 respondText(...)，而它默认 200。
        // 源站一个 403 防盗链页会被包装成「一份合法的 m3u8」发给播放器：
        // hls.js 不会报错、也不会切下一条线路或下一个源，用户只看到黑屏。
        val h = Harness(
            listOf(
                StreamStubFetcher.Spec(
                    status = 403,
                    contentType = "text/html",
                    body = "<html>Access Denied</html>",
                ),
            ),
        )
        application { module(h.cfg, h.stub, h.sessions) }
        val resp = client.get("/api/proxy") {
            parameter("url", "https://cdn.example.com/hls/index.m3u8")
            parameter("sid", h.sid)
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status, "上游的拒绝必须原样传给播放器")
        assertTrue(resp.bodyAsText().contains("Access Denied"))
        val ct = resp.contentType()?.toString().orEmpty()
        assertFalse(ct.contains("mpegurl", ignoreCase = true), "错误页不能被标成 m3u8：$ct")
    }

    @Test
    fun `unknown session is reported as gone rather than a silent unauthenticated fetch`() = testApplication {
        val h = Harness(listOf(hlsPlaylist("seg1.ts")))
        application { module(h.cfg, h.stub, h.sessions) }
        val resp = client.get("/api/proxy") {
            parameter("url", "https://cdn.example.com/hls/index.m3u8")
            parameter("sid", "deadbeefdeadbeefdeadbeefdeadbeef")
        }
        assertEquals(HttpStatusCode.Gone, resp.status)
        assertTrue(resp.bodyAsText().contains("PROXY_SESSION_EXPIRED"))
        assertTrue(h.stub.requests.isEmpty(), "会话已失效就不该再打上游")
    }

    @Test
    fun `requests without a session still proxy plain links`() = testApplication {
        val h = Harness(
            listOf(StreamStubFetcher.Spec(contentType = "video/mp4", body = "BYTES")),
        )
        application { module(h.cfg, h.stub, h.sessions) }
        val resp = client.get("/api/proxy") { parameter("url", "https://cdn.example.com/video.mp4") }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals("BYTES", resp.bodyAsText())
        // BN-004 契约更正：identity 是代理的内容编码策略，不是内联源站凭据。
        // 无会话仍不得携带 UA/Referer/Cookie，但显式要求上游 identity。
        assertEquals(mapOf(HttpHeaders.AcceptEncoding to "identity"), h.stub.requests.single().second.filterKeys { it != "Range" })
    }

    @Test
    fun `media url is decoded exactly once`() = testApplication {
        val h = Harness(listOf(StreamStubFetcher.Spec(contentType = "video/mp4", body = "X")))
        application { module(h.cfg, h.stub, h.sessions) }
        val signed = "https://cdn.example.com/v.mp4?sign=abc%2Bdef%2Fghi&exp=1"
        client.get("/api/proxy") { parameter("url", signed) }
        // Ktor 已解码一次；再解一次会把 %2B 变成 +、再把 + 变成空格，直链就废了
        assertEquals(signed, h.stub.requests.single().first)
    }

    @Test
    fun `session header values are passed through unchanged`() {
        // UA/cookie 里含空格、字面 + 和 % 很常见（base64 令牌、带签名的 referer）
        val tricky = ProxySessionStore()
        val target = "https://a.example/v/index.m3u8"
        val sid = tricky.put(target, "Player/1.0 build+7 q=50%", "https://a.example/x?y=1&z=2", "sid=a+b%2Bc")
        val stored = tricky.grant(sid, target).headers
        assertEquals("Player/1.0 build+7 q=50%", stored["User-Agent"])
        assertEquals("https://a.example/x?y=1&z=2", stored["Referer"])
        assertEquals("sid=a+b%2Bc", stored["Cookie"])
    }

    @Test
    fun `range request is forwarded and content-range echoed`() = testApplication {
        val h = Harness(
            listOf(
                StreamStubFetcher.Spec(
                    status = 206,
                    contentType = "video/mp4",
                    body = "0123456789",
                    headers = mapOf(
                        "content-range" to listOf("bytes 0-1/10"),
                        "accept-ranges" to listOf("bytes"),
                    ),
                ),
            ),
        )
        application { module(h.cfg, h.stub, h.sessions) }
        val resp = client.get("/api/proxy") {
            parameter("url", "https://cdn.example.com/video.mp4")
            parameter("sid", h.sid)
            header("Range", "bytes=0-1")
        }
        assertEquals(HttpStatusCode.PartialContent, resp.status)
        assertEquals("bytes 0-1/10", resp.headers["Content-Range"])
        assertEquals("bytes", resp.headers["Accept-Ranges"])
        assertEquals("0123456789", resp.bodyAsText())
        val sent = h.stub.requests.single().second
        assertEquals("bytes=0-1", sent["Range"])
        assertEquals(REFERER, sent["Referer"])
    }

    @Test
    fun `cross host redirect drops cookie without poisoning the session`() = testApplication {
        val h = Harness(
            listOf(
                redirect("https://other-host.example.com/hls/index.m3u8"),
                hlsPlaylist("seg1.ts"),
            ),
        )
        application { module(h.cfg, h.stub, h.sessions) }
        client.get("/api/proxy") {
            parameter("url", "https://cdn.example.com/hls/index.m3u8")
            parameter("sid", h.sid)
        }
        assertEquals(2, h.stub.requests.size)
        assertEquals(COOKIE, h.stub.requests[0].second["Cookie"])
        assertFalse("Cookie" in h.stub.requests[1].second, "跨主机重定向后不该继续携带源站 Cookie")
        // 会话本身不能被这次跳转改坏，否则后续分片会莫名丢凭据
        assertEquals(COOKIE, h.sessions.grant(h.sid, MEDIA).headers["Cookie"])
    }

    /**
     * 主机名含下划线时 `java.net.URI.getHost()` 返回 null（下划线不是合法的 reg-name 字符），
     * 于是「比较两边 host」的实现会得到 null == null —— 两台完全不同的主机被判成同一台，
     * 源站 Cookie 跟着 302 送到第三个域名去了。NAS / 内网机器名带下划线并不罕见。
     */
    @Test
    fun `cross host redirect between URI-unparsable hosts still drops cookie`() = testApplication {
        val from = "https://src_host.example.com/hls/index.m3u8"
        val to = "https://dst_host.example.com/hls/index.m3u8"
        assertNull(java.net.URI(from).host, "用例前提：URI 必须读不出这个 host，否则测不到该分支")
        assertNull(java.net.URI(to).host)
        val h = Harness(listOf(redirect(to), hlsPlaylist("seg1.ts")))
        application { module(h.cfg, h.stub, h.sessions) }
        val sid = h.sessionFor(from)
        val resp = client.get("/api/proxy") {
            parameter("url", from)
            parameter("sid", sid)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(2, h.stub.requests.size)
        assertEquals(COOKIE, h.stub.requests[0].second["Cookie"])
        assertFalse("Cookie" in h.stub.requests[1].second, "跨主机重定向把源站 Cookie 送到了 ${java.net.URI(to).rawAuthority}")
    }

    @Test
    fun `same host redirect keeps cookie and resolves relative location`() = testApplication {
        val h = Harness(
            listOf(
                redirect("/hls/index2.m3u8"),
                hlsPlaylist("seg1.ts"),
            ),
        )
        application { module(h.cfg, h.stub, h.sessions) }
        val resp = client.get("/api/proxy") {
            parameter("url", "https://cdn.example.com/hls/index.m3u8")
            parameter("sid", h.sid)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(2, h.stub.requests.size)
        assertEquals("https://cdn.example.com/hls/index2.m3u8", h.stub.requests[1].first)
        assertEquals(COOKIE, h.stub.requests[1].second["Cookie"])
        // 逐跳校验的前提：每一跳都是**我们自己**发的请求。真实 JdkFetcher 里
        // 默认的 client 是 Redirect.NORMAL，一旦有人把调用点的 followRedirects = false
        // 删掉/改掉，302 → 169.254.169.254 就会在 UrlGuard 看不见的情况下发出去。
        // 这个参数原先在 stub 里被直接丢弃，所以那一格是测不出来的。
        assertEquals(listOf(false, false), h.stub.redirectFlags, "每一跳都必须自己发起")
    }

    /**
     * 3xx 但不带 Location —— 坏 CDN 和「会话过期的源站」都长这样。
     * 没得跟跳就该把这 3xx 如实透传给播放器。循环里原先在判 Location 之前
     * 就 close 了响应体，而 JDK 的响应体流关掉再读会直接抛：
     * 播放器于是收到一个与源站毫无关系的 500。
     */
    @Test
    fun `3xx without location is forwarded instead of read after close`() = testApplication {
        val body = TrackedStream()
        val h = Harness(
            listOf(
                // 304 按定义不带 Location（源站常见：防盗链/会话过期回一个裸 304）
                StreamStubFetcher.Spec(
                    status = 304,
                    contentType = "text/plain",
                    stream = { body },
                ),
            ),
        )
        application { module(h.cfg, h.stub, h.sessions) }
        val resp = client.get("/api/proxy") {
            parameter("url", MEDIA)
            parameter("sid", h.sid)
        }
        // 期望：如实透传这一跳 3xx，而不是关掉响应体后再去读它抛出来的 500
        assertTrue(resp.status.value in 300..399, "expected a forwarded 3xx, got ${resp.status}")
        assertEquals(1, h.stub.requests.size)
        // 而且这个响应体最终必须被关掉：不关就是每个裸 304 漏一条上游连接。
        // 只断言状态码的话，「关掉之后再读」（500）和「压根没关」（泄漏）都测不出来。
        assertTrue(body.closed, "透传 3xx 之后响应体没有被消费/关闭，会漏上游连接")
    }

    /**
     * 302 指向内网被 UrlGuard 拦下时，这一跳永远不会有人再读它的响应体，
     * 必须关掉。否则每个「302 → 127.0.0.1」的探测请求都挂掉一条上游连接 ——
     * 而这类请求恰好是可以批量发的。
     */
    @Test
    fun `blocked redirect closes the body it will never read`() = testApplication {
        val body = TrackedStream()
        // 入口用字面公网 IP：既避开测试环境的真实 DNS，也让 169.254.169.254 这一跳
        // 无论 allowPrivateTargets 怎么配都必然被拦。
        val h = Harness(
            listOf(
                StreamStubFetcher.Spec(
                    status = 302,
                    contentType = "text/plain",
                    headers = mapOf("location" to listOf("http://169.254.169.254/latest/meta-data/")),
                    stream = { body },
                ),
            ),
            allowPrivate = false,
        )
        application { module(h.cfg, h.stub, h.sessions) }
        val resp = client.get("/api/proxy") { parameter("url", "https://93.184.216.34/hls/index.m3u8") }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
        assertTrue(body.closed, "被拦下的重定向没有关闭上一跳的响应体")
    }

    @Test
    fun `loopback target is rejected before any upstream request`() = testApplication {
        val h = Harness(listOf(hlsPlaylist("seg1.ts")), allowPrivate = false)
        application { module(h.cfg, h.stub, h.sessions) }
        val resp = client.get("/api/proxy") {
            parameter("url", "http://127.0.0.1:8080/api/selector/sources")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
        assertTrue(h.stub.requests.isEmpty(), "被拦下时不该发出任何上游请求")
    }

    @Test
    fun `redirect into metadata address is rejected`() = testApplication {
        // 只校验入口地址会被 302 绕过，所以每一跳都要重新校验。
        // 入口用字面公网 IP，避免测试环境做真实 DNS 解析。
        val h = Harness(
            listOf(
                redirect("http://169.254.169.254/latest/meta-data/"),
                hlsPlaylist("seg1.ts"),
            ),
            allowPrivate = false,
        )
        application { module(h.cfg, h.stub, h.sessions) }
        val resp = client.get("/api/proxy") {
            parameter("url", "https://93.184.216.34/hls/index.m3u8")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
        assertEquals(1, h.stub.requests.size, "跳转到内网地址后必须中断，不能再请求第二跳")
    }

    @Test
    fun `inline credential params are no longer accepted as headers`() = testApplication {
        // 旧协议允许 ?ua=&referer=&cookie= 直接注入任意请求头，
        // 现在这些参数必须被完全忽略（否则又回到「任意头 + 任意地址」的 SSRF 组合）
        val h = Harness(listOf(StreamStubFetcher.Spec(contentType = "video/mp4", body = "X")))
        application { module(h.cfg, h.stub, h.sessions) }
        client.get("/api/proxy") {
            parameter("url", "https://cdn.example.com/v.mp4")
            parameter("ua", "Injected/1.0")
            parameter("cookie", "session=stolen")
            parameter("referer", "https://attacker.example")
        }
        val sent = h.stub.requests.single().second
        assertFalse("User-Agent" in sent, "内联 ua 仍被当请求头：$sent")
        assertFalse("Cookie" in sent, "内联 cookie 仍被当请求头：$sent")
        assertFalse("Referer" in sent, "内联 referer 仍被当请求头：$sent")
    }

    @Test
    fun `missing or non http url is a bad request`() = testApplication {
        val h = Harness(listOf(hlsPlaylist("seg1.ts")))
        application { module(h.cfg, h.stub, h.sessions) }
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/proxy").status)
        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("/api/proxy") { parameter("url", "file:///etc/passwd") }.status,
        )
        assertTrue(h.stub.requests.isEmpty())
    }

    @Test
    fun `gzip applies to playlists but never to streamed media`() = testApplication {
        val h = Harness(
            listOf(
                StreamStubFetcher.Spec(
                    status = 206,
                    contentType = "video/mp4",
                    body = "0123456789",
                    headers = mapOf("content-range" to listOf("bytes 0-1/10")),
                ),
            ),
        )
        application { module(h.cfg, h.stub, h.sessions) }
        val resp = client.get("/api/proxy") {
            parameter("url", "https://cdn.example.com/video.mp4")
            header(HttpHeaders.AcceptEncoding, "gzip")
            header("Range", "bytes=0-1")
        }
        assertEquals(HttpStatusCode.PartialContent, resp.status)
        assertNull(resp.headers[HttpHeaders.ContentEncoding], "视频响应被压缩了，Range/长度都会对不上")
        assertEquals("bytes 0-1/10", resp.headers["Content-Range"])
    }

    @Test
    fun `a leaked sid cannot be used to send credentials to another host`() = testApplication {
        // sid 出现在浏览器历史与访问日志里，泄漏是可预期的；绑定目标主机后，
        // 拿着它也无法让服务端带着源站 Cookie 去攻击者指定的地址。
        val h = Harness(listOf(hlsPlaylist("seg1.ts")))
        application { module(h.cfg, h.stub, h.sessions) }
        val resp = client.get("/api/proxy") {
            parameter("url", "https://attacker.example/collect?c=1.m3u8")
            parameter("sid", h.sid)
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
        assertTrue(resp.bodyAsText().contains("PROXY_SESSION_TARGET_MISMATCH"), resp.bodyAsText())
        assertTrue(h.stub.requests.isEmpty(), "主机校验失败前不该打上游")
    }

    @Test
    fun `video route mints a session and never ships the credentials`() = testApplication {
        val configJson = """
            {"factoryId":"web-selector","version":2,"arguments":{"name":"解析源","searchConfig":{
              "rawBaseUrl":"https://example-site.test",
              "searchUrl":"https://example-site.test/s?q={keyword}",
              "subjectFormatId":"a","channelFormatId":"no-channel",
              "matchVideo":{"enableNestedUrl":false,"matchVideoUrl":"index[.]m3u8",
                "cookies":"sess=COOKIEVALUE",
                "addHeadersToVideo":{"referer":"https://ref.example/p","userAgent":"UAHEADER/9.9"}}}}}
        """.trimIndent()
        val h = Harness(
            listOf(hlsPlaylist("seg1.ts")),
            html = mapOf(
                "https://example-site.test/play/1.html" to
                    """<html><body><a href="https://cdn.example.com/hls/index.m3u8">播放</a></body></html>""",
            ),
        )
        application { module(h.cfg, h.stub, h.sessions) }
        val imported = client.post("/api/selector/sources") {
            contentType(ContentType.Application.Json)
            setBody(configJson)
        }
        assertEquals(HttpStatusCode.Created, imported.status, imported.bodyAsText())
        val resp = client.post("/api/selector/sources/解析源/video") {
            contentType(ContentType.Application.Json)
            setBody("""{"playUrl":"https://example-site.test/play/1.html"}""")
        }
        val body = resp.bodyAsText()
        assertEquals(HttpStatusCode.OK, resp.status, body)
        // 凭据不再下发给浏览器
        for (secret in listOf("COOKIEVALUE", "UAHEADER/9.9", "ref.example")) {
            assertFalse(body.contains(secret), "解析响应泄露了「$secret」：$body")
        }
        assertTrue(body.contains("\"proxyPath\""), body)

        // 解析播放页时确实按正确的头去取（位置传参写反也能在这里被抓到）
        val fetched = h.stub.fetchHeaders.single().second
        assertEquals("UAHEADER/9.9", fetched["User-Agent"])
        assertEquals("https://ref.example/p", fetched["Referer"])
        assertEquals("sess=COOKIEVALUE", fetched["Cookie"])

        // 顺着 proxyPath 取清单，再把改写出的分片地址请求一遍：
        // 会话必须能把同一套头补上，否则防盗链源会全线 403
        val proxyPath = Regex(""""proxyPath":"([^"]+)"""").find(body)!!.groupValues[1]
        val playlist = client.get(proxyPath).bodyAsText()
        assertTrue("sid=" in playlist, playlist)
        val seg = playlist.lines().last { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(HttpStatusCode.OK, client.get(seg).status, "分片地址 $seg 取不回内容")
        val segHeaders = h.stub.requests.last().second
        assertEquals("sess=COOKIEVALUE", segHeaders["Cookie"], "分片请求没带上会话里的 Cookie")
    }

    @Test
    fun `health stays reachable when auth is on but proxy does not`() = testApplication {
        val h = Harness(listOf(hlsPlaylist("seg1.ts")), authEnabled = true, token = "t0ken")
        application { module(h.cfg, h.stub, h.sessions) }

        // 容器 HEALTHCHECK 与前端都要能免鉴权读到 /api/health
        assertEquals(HttpStatusCode.OK, client.get("/api/health").status)

        val denied = client.get("/api/proxy") { parameter("url", "https://cdn.example.com/a.m3u8") }
        assertEquals(HttpStatusCode.Unauthorized, denied.status)
        assertTrue(
            denied.headers[HttpHeaders.WWWAuthenticate]?.contains("Basic") == true,
            "必须下发 Basic 质询，否则 <video>/<img>/hls.js 分片无法携带凭据",
        )

        val viaBearer = client.get("/api/proxy") {
            parameter("url", "https://cdn.example.com/a.m3u8")
            header(HttpHeaders.Authorization, "Bearer t0ken")
        }
        assertEquals(HttpStatusCode.OK, viaBearer.status)

        // 只配 token 的部署：浏览器弹窗用户名任填、密码填 token 也要能通过
        val viaBasic = client.get("/api/proxy") {
            parameter("url", "https://cdn.example.com/a.m3u8")
            header(
                HttpHeaders.Authorization,
                "Basic " + Base64.getEncoder().encodeToString("any:t0ken".toByteArray()),
            )
        }
        assertEquals(HttpStatusCode.OK, viaBasic.status)

        val wrong = client.get("/api/proxy") {
            parameter("url", "https://cdn.example.com/a.m3u8")
            header(HttpHeaders.Authorization, "Bearer nope")
        }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
    }

    @Test
    fun `error status does not forward a lying content-length`() = testApplication {
        // 上游 404 却声明 9999 字节：转发这个长度，客户端会对着一个
        // 永远等不到结尾的长度干转（转圈而不是报错）。错误体一律走 chunked。
        val h = Harness(
            listOf(
                StreamStubFetcher.Spec(
                    status = 404,
                    contentType = "text/plain",
                    body = "gone",
                    headers = mapOf("content-length" to listOf("9999")),
                ),
            ),
        )
        application { module(h.cfg, h.stub, h.sessions) }
        val resp = client.get("/api/proxy") {
            parameter("url", "https://cdn.example.com/video.mp4")
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
        assertNull(resp.headers["Content-Length"])
        assertEquals("gone", resp.bodyAsText())
    }

    @Test
    fun `a slow but live upstream is forwarded in full`() = testApplication {
        // 这条用例守的是「给 body 加绝对超时」那个改动的回归：
        // 每段之间睡 40ms 的健康慢流，在 ANIMEKO_PROXY_TIMEOUT_MS 很短时被砍断，
        // 而客户端拿到的是声明 1000 字节、实际只给了几十十字节且不结束的响应 ——
        // 播放器表现成卡死。慢但活着就该转完，宁可没有超时也不要误伤正常播放。
        val total = 120
        var sent = 0
        val h = Harness(
            listOf(
                StreamStubFetcher.Spec(
                    status = 200,
                    contentType = "video/mp4",
                    headers = mapOf("content-length" to listOf(total.toString())),
                    stream = {
                        object : java.io.InputStream() {
                            override fun read(b: ByteArray, off: Int, len: Int): Int {
                                if (sent >= total) return -1
                                Thread.sleep(4) // 模拟一段一段慢慢吐的上游
                                val n = minOf(8, total - sent, len)
                                java.util.Arrays.fill(b, off, off + n, 'x'.code.toByte())
                                sent += n
                                return n
                            }

                            override fun read(): Int = read(ByteArray(1), 0, 1)
                        }
                    },
                ),
            ),
            // 比整段传输所需时间小得多：任何「按总时长切断」的实现都会在这里被抓住
            proxyTimeoutMs = 1,
        )
        application { module(h.cfg, h.stub, h.sessions) }
        // 用 .mp4 而不是 harness 里那个 .m3u8 常量：后缀会决定走不走清单重写分支
        val resp = client.get("/api/proxy") { parameter("url", "https://cdn.example.com/movie.mp4") }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(total, resp.bodyAsBytes().size, "慢但活着的流必须完整转完")
    }

    @Test
    fun `proxied images get a browser cache header`() = testApplication {
        // 封面经 /api/proxy 中转后必须让浏览器缓存，否则每次刷新都穿透服务器拉图床
        val img = Harness(
            listOf(
                StreamStubFetcher.Spec(status = 200, contentType = "image/jpeg", body = "JPEGDATA"),
            ),
        )
        application { module(img.cfg, img.stub, img.sessions) }
        val r = client.get("/api/proxy") {
            parameter("url", "https://lain.bgm.tv/pic/cover/l/e2/e7/328609_2EHLJ.jpg")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("public, max-age=86400", r.headers["Cache-Control"])
    }

    @Test
    fun `playlists are never given a browser cache`() = testApplication {
        // 清单缓存一天 = 源站换源之后浏览器还在拿旧清单播过期地址
        val m3u8 = Harness(listOf(hlsPlaylist("seg1.ts")))
        application { module(m3u8.cfg, m3u8.stub, m3u8.sessions) }
        val r = client.get("/api/proxy") {
            parameter("url", "https://cdn.example.com/hls/index.m3u8")
            parameter("sid", m3u8.sid)
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(null, r.headers["Cache-Control"], r.headers["Cache-Control"])
    }

    @Test
    fun `a refused image is not labelled cacheable`() = testApplication {
        // 403 的图标成可缓存，等于让浏览器把「拒绝」存一整天
        val img = Harness(
            listOf(
                StreamStubFetcher.Spec(status = 403, contentType = "image/jpeg", body = "no"),
            ),
        )
        application { module(img.cfg, img.stub, img.sessions) }
        val r = client.get("/api/proxy") { parameter("url", "https://lain.bgm.tv/x.jpg") }
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertEquals(null, r.headers["Cache-Control"])
    }
}
