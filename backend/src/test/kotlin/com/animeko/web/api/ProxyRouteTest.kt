package com.animeko.web.api

import com.animeko.web.config.AppConfig
import com.animeko.web.http.Fetcher
import com.animeko.web.http.HttpResult
import com.animeko.web.http.StreamResult
import com.animeko.web.module
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
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
private class StreamStubFetcher(private val specs: List<Spec>) : Fetcher {
    val requests = mutableListOf<Pair<String, Map<String, String>>>()

    data class Spec(
        val status: Int = 200,
        val contentType: String = "application/vnd.apple.mpegurl",
        val body: String = "",
        val headers: Map<String, List<String>> = emptyMap(),
    )

    override suspend fun fetchStream(
        url: String,
        headers: Map<String, String>,
        followRedirects: Boolean,
    ): StreamResult {
        // 存副本：代理会在跨主机重定向后就地 remove("Cookie")，
        // 直接持有同一个 map 会让第一次请求的记录被改写，断言失去意义。
        requests.add(url to LinkedHashMap(headers))
        val spec = specs[minOf(requests.lastIndex, specs.lastIndex)]
        val merged = LinkedHashMap<String, List<String>>()
        merged["content-type"] = listOf(spec.contentType)
        spec.headers.forEach { (k, v) -> merged[k] = v }
        return StreamResult(spec.status, merged, spec.body.byteInputStream())
    }

    override suspend fun fetch(
        url: String,
        headers: Map<String, String>,
        followRedirects: Boolean,
    ): HttpResult = throw UnsupportedOperationException("本测试只覆盖代理链路")

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

class ProxyRouteTest {

    /**
     * allowPrivate 默认 true：本类的用例都带 .example.com 这类假域名，
     * 严格的 UrlGuard 会做真实 DNS 解析；SSRF 用例单独传 false（目标全是字面 IP，不触发解析）。
     */
    private fun cfg(
        allowPrivate: Boolean = true,
        authEnabled: Boolean = false,
        token: String? = null,
    ) = AppConfig(
        dataDir = Files.createTempDirectory("animeko-proxy-test"),
        frontendDir = "definitely-not-exist",
        allowPrivateTargets = allowPrivate,
        authEnabled = authEnabled,
        authToken = token,
    )

    @Test
    fun `rewritten segments keep ua referer and cookie`() = testApplication {
        val stub = StreamStubFetcher(listOf(hlsPlaylist("seg1.ts")))
        application { module(cfg(), stub) }
        val resp = client.get("/api/proxy") {
            parameter("url", "https://cdn.example.com/hls/index.m3u8")
            parameter("ua", "SecretUA/1.0")
            parameter("referer", "https://v.example.com/watch")
            parameter("cookie", "token=abc")
        }
        val text = resp.bodyAsText()
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(1, stub.requests.size)
        // 分片地址必须带上同一套头，否则清单能拉、分片全 403
        assertTrue(text.contains("ua=SecretUA%2F1.0"), text)
        assertTrue(text.contains("referer=https%3A%2F%2Fv.example.com%2Fwatch"), text)
        assertTrue(text.contains("cookie=token%3Dabc"), text)
        assertEquals("SecretUA/1.0", stub.requests.single().second["User-Agent"])
    }

    @Test
    fun `range request is forwarded and content-range echoed`() = testApplication {
        val stub = StreamStubFetcher(
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
        application { module(cfg(), stub) }
        val resp = client.get("/api/proxy") {
            parameter("url", "https://cdn.example.com/video.mp4")
            parameter("referer", "https://v.example.com/watch")
            header("Range", "bytes=0-1")
        }
        assertEquals(HttpStatusCode.PartialContent, resp.status)
        assertEquals("bytes 0-1/10", resp.headers["Content-Range"])
        assertEquals("bytes", resp.headers["Accept-Ranges"])
        assertEquals("0123456789", resp.bodyAsText())
        assertEquals("bytes=0-1", stub.requests.single().second["Range"])
        assertEquals("https://v.example.com/watch", stub.requests.single().second["Referer"])
    }

    @Test
    fun `cross host redirect drops cookie`() = testApplication {
        val stub = StreamStubFetcher(
            listOf(
                redirect("https://other-host.example.com/hls/index.m3u8"),
                hlsPlaylist("seg1.ts"),
            ),
        )
        application { module(cfg(), stub) }
        client.get("/api/proxy") {
            parameter("url", "https://cdn.example.com/hls/index.m3u8")
            parameter("cookie", "token=abc")
        }
        assertEquals(2, stub.requests.size)
        assertEquals("token=abc", stub.requests[0].second["Cookie"])
        assertFalse("Cookie" in stub.requests[1].second, "跨主机重定向后不该继续携带源站 Cookie")
    }

    @Test
    fun `same host redirect keeps cookie and resolves relative location`() = testApplication {
        val stub = StreamStubFetcher(
            listOf(
                redirect("/hls/index2.m3u8"),
                hlsPlaylist("seg1.ts"),
            ),
        )
        application { module(cfg(), stub) }
        val resp = client.get("/api/proxy") {
            parameter("url", "https://cdn.example.com/hls/index.m3u8")
            parameter("cookie", "token=abc")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(2, stub.requests.size)
        assertEquals("https://cdn.example.com/hls/index2.m3u8", stub.requests[1].first)
        assertEquals("token=abc", stub.requests[1].second["Cookie"])
    }

    @Test
    fun `forwarded header values survive the query round trip`() = testApplication {
        val stub = StreamStubFetcher(listOf(hlsPlaylist("seg1.ts")))
        application { module(cfg(), stub) }
        // 含空格、字面 + 和 %XX 的 UA/cookie 很常见（base64 口令、带签名的 referer）。
        // 若服务端对已解码的查询参数再解码一次，这些值会被静默改写。
        val tricky = "Player/1.0 build+7 q=50%25"
        client.get("/api/proxy") {
            parameter("url", "https://cdn.example.com/hls/index.m3u8")
            parameter("ua", tricky)
            parameter("cookie", "sid=abc%2Bdef")
        }
        val sent = stub.requests.single().second
        assertEquals(tricky, sent["User-Agent"], "UA 在解码环节被改写了")
        assertEquals("sid=abc%2Bdef", sent["Cookie"], "cookie 里的 %2B 被二次解码成了 +")
    }

    @Test
    fun `gzip applies to playlists but never to streamed media`() = testApplication {
        // Compression 是按内容类型白名单开的。这里同时验证两件事：
        // 文本清单确实被压缩（移动端首屏），而视频/分片绝不能被压缩——
        // 那会白烧 CPU、改变 Content-Length，并破坏 Range 断点续播。
        val specs = listOf(
            StreamStubFetcher.Spec(
                status = 206,
                contentType = "video/mp4",
                body = "0123456789",
                headers = mapOf("content-range" to listOf("bytes 0-1/10")),
            ),
        )
        val stub = StreamStubFetcher(specs)
        application { module(cfg(), stub) }
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
    fun `loopback target is rejected before any upstream request`() = testApplication {
        val stub = StreamStubFetcher(listOf(hlsPlaylist("seg1.ts")))
        application { module(cfg(allowPrivate = false), stub) }
        val resp = client.get("/api/proxy") {
            parameter("url", "http://127.0.0.1:8080/api/selector/sources")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
        assertTrue(stub.requests.isEmpty(), "被拦下时不该发出任何上游请求")
    }

    @Test
    fun `redirect into metadata address is rejected`() = testApplication {
        // 只校验入口地址会被 302 绕过，所以每一跳都要重新校验。
        // 入口用字面公网 IP，避免测试环境做真实 DNS 解析。
        val stub = StreamStubFetcher(
            listOf(
                redirect("http://169.254.169.254/latest/meta-data/"),
                hlsPlaylist("seg1.ts"),
            ),
        )
        application { module(cfg(allowPrivate = false), stub) }
        val resp = client.get("/api/proxy") {
            parameter("url", "https://93.184.216.34/hls/index.m3u8")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
        assertEquals(1, stub.requests.size, "跳转到内网地址后必须中断，不能再请求第二跳")
    }

    @Test
    fun `missing or non http url is a bad request`() = testApplication {
        val stub = StreamStubFetcher(listOf(hlsPlaylist("seg1.ts")))
        application { module(cfg(), stub) }
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/proxy").status)
        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("/api/proxy") { parameter("url", "file:///etc/passwd") }.status,
        )
        assertTrue(stub.requests.isEmpty())
    }

    @Test
    fun `health stays reachable when auth is on but proxy does not`() = testApplication {
        val stub = StreamStubFetcher(listOf(hlsPlaylist("seg1.ts")))
        application { module(cfg(authEnabled = true, token = "t0ken"), stub) }

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

        // 只配了 token 的部署：浏览器弹窗用户名任填、密码填 token 也要能通过
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
}
