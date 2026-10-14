package com.animeko.web.api

import com.animeko.web.config.AppConfig
import com.animeko.web.danmaku.DdComment
import com.animeko.web.module
import com.animeko.web.selector.EpisodeHit
import com.animeko.web.selector.EpisodesResult
import com.animeko.web.selector.ResolvedVideo
import com.animeko.web.selector.SubjectHit
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiRoutesTest {

    private fun testConfig(
        authEnabled: Boolean = false,
        token: String? = null,
    ) = AppConfig(
        authEnabled = authEnabled,
        authToken = token,
        dataDir = java.nio.file.Files.createTempDirectory("animeko-test"),
        frontendDir = "definitely-not-exist",
    )

    @Test
    fun `health returns ok`() = testApplication {
        application { module(testConfig()) }
        val response = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("ok", body["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `auth disabled by default`() = testApplication {
        application { module(testConfig()) }
        assertEquals(HttpStatusCode.OK, client.get("/api/health").status)
    }

    @Test
    fun `auth protects api but leaves health open for the container healthcheck`() = testApplication {
        application { module(testConfig(authEnabled = true, token = "secret-token")) }
        // /api/health 必须免鉴权：Docker HEALTHCHECK 与前端都要能读到 authEnabled，
        // 否则开了鉴权后容器永远 unhealthy、前端也只知道「后端未连接」。
        assertEquals(HttpStatusCode.OK, client.get("/api/health").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/selector/sources").status)
        val ok = client.get("/api/selector/sources") {
            header("Authorization", "Bearer secret-token")
        }
        assertEquals(HttpStatusCode.OK, ok.status)
    }

    @Test
    fun `enable requires an explicit boolean`() = testApplication {
        application { module(testConfig()) }
        val configJson = javaClass.getResourceAsStream("/selectors/example-css-site.json")!!
            .bufferedReader().use { it.readText() }
        client.post("/api/selector/sources") {
            contentType(ContentType.Application.Json)
            setBody(configJson)
        }
        // 缺字段/非布尔一律 400：旧实现里空 body 会「时而停用时而启用」
        assertEquals(
            HttpStatusCode.BadRequest,
            client.post("/api/selector/sources/示例站点-CSS/enable") { setBody("{}") }.status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            client.post("/api/selector/sources/示例站点-CSS/enable") { setBody("not-json") }.status,
        )
        val off = client.post("/api/selector/sources/示例站点-CSS/enable") {
            contentType(ContentType.Application.Json)
            setBody("""{"enabled":false}""")
        }
        assertEquals(HttpStatusCode.OK, off.status, off.bodyAsText())
        assertTrue(off.bodyAsText().contains("\"enabled\":false"))
    }

    @Test
    fun `import animeko selector config and list it`() = testApplication {
        application { module(testConfig()) }
        val configJson = javaClass.getResourceAsStream("/selectors/example-css-site.json")!!
            .bufferedReader().use { it.readText() }
        val created = client.post("/api/selector/sources") {
            contentType(ContentType.Application.Json)
            setBody(configJson)
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())

        val list = client.get("/api/selector/sources")
        val sources = Json.parseToJsonElement(list.bodyAsText()).jsonArray
        assertTrue(sources.any { src ->
            src.jsonObject["arguments"]?.jsonObject?.get("name")?.jsonPrimitive?.content == "示例站点-CSS"
        })
    }

    @Test
    fun `import rejects invalid json`() = testApplication {
        application { module(testConfig()) }
        val resp = client.post("/api/selector/sources") {
            contentType(ContentType.Application.Json)
            setBody("not-json")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `unknown selector source returns 404`() = testApplication {
        application { module(testConfig()) }
        val resp = client.post("/api/selector/sources/not-exist/search") {
            contentType(ContentType.Application.Json)
            setBody("""{"keyword":"test"}""")
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `unknown api path returns 404 not spa html`() = testApplication {
        // 前端目录真实存在（含 index.html），确保 SPA 回退分支可触发
        val dir = java.nio.file.Files.createTempDirectory("animeko-fe")
        java.io.File(dir.toFile(), "index.html").writeText("<html>spa</html>")
        application { module(testConfig().copy(frontendDir = dir.toString())) }
        // 未匹配的 /api/ 路径必须是 404，不能被 SPA 回退劫持成 200 HTML
        val api404 = client.get("/api/not-exist")
        assertEquals(HttpStatusCode.NotFound, api404.status)
        // 普通 SPA 路由仍正常回退 index.html
        assertEquals(HttpStatusCode.OK, client.get("/subject/123").status)
    }

    @Test
    fun `serves static assets instead of spa fallback`() = testApplication {
        val dir = java.nio.file.Files.createTempDirectory("animeko-fe")
        java.io.File(dir.toFile(), "index.html").writeText("<html>spa-index</html>")
        java.io.File(dir.toFile(), "assets").mkdirs()
        java.io.File(dir.toFile(), "assets/app.css").writeText("body{color:red}")
        application { module(testConfig().copy(frontendDir = dir.toString())) }

        // 根路径服务 index.html
        val root = client.get("/")
        assertEquals(HttpStatusCode.OK, root.status)
        assertTrue(root.bodyAsText().contains("spa-index"))

        // 静态文件必须真实服务（历史上被 SPA 回退劫持成 index.html，导致前端白屏）
        val css = client.get("/assets/app.css")
        assertEquals(HttpStatusCode.OK, css.status)
        assertTrue(css.bodyAsText().contains("color:red"))

        // 子路径 SPA 路由仍回退
        val spa = client.get("/subject/123")
        assertTrue(spa.bodyAsText().contains("spa-index"))

        // API 404 不被劫持
        assertEquals(HttpStatusCode.NotFound, client.get("/api/not-exist").status)
    }

    @Test
    fun `static js assets are gzipped`() = testApplication {
        // Compression 是后来加的，这条守住它的实际收益：打包后的 JS 有 1.1MB，
        // 不压缩的话移动端首屏很慢。历史上这里因为没有 staticFiles 而整站白屏，
        // 所以同时确认文件仍然真实服务、没被 SPA 回退劫持。
        val dir = java.nio.file.Files.createTempDirectory("animeko-fe-gz")
        java.io.File(dir.toFile(), "index.html").writeText("<html>spa</html>")
        java.io.File(dir.toFile(), "assets").mkdirs()
        java.io.File(dir.toFile(), "assets/app.js").writeText("console.log(1);".repeat(400))
        application { module(testConfig().copy(frontendDir = dir.toString())) }
        val resp = client.get("/assets/app.js") { header(HttpHeaders.AcceptEncoding, "gzip") }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals("gzip", resp.headers[HttpHeaders.ContentEncoding], "打包后的 JS 未被压缩")
    }

    @Test
    fun `missing static asset is 404 rather than the spa index`() = testApplication {
        val dir = java.nio.file.Files.createTempDirectory("animeko-fe-404")
        java.io.File(dir.toFile(), "index.html").writeText("<html>spa</html>")
        application { module(testConfig().copy(frontendDir = dir.toString())) }
        // 重新部署后 chunk 改名，用户缓存里的旧 index.html 会请求旧文件名。
        // 回退 index.html 等于让浏览器把 HTML 当 ES module 执行（MIME 报错），
        // 且 React.lazy 会永久记住这次失败 → 只能整页 reload 才能恢复。
        assertEquals(HttpStatusCode.NotFound, client.get("/assets/stale-hash.js").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/favicon.ico").status)
        // 普通 SPA 深链接仍要回退
        val spa = client.get("/subject/123")
        assertEquals(HttpStatusCode.OK, spa.status)
        assertTrue(spa.bodyAsText().contains("spa"))
    }

    @Test
    fun `danmaku without credentials is 503 with the stable contract string`() = testApplication {
        application { module(testConfig()) }
        val resp = client.get("/api/danmaku/search?anime=x")
        assertEquals(HttpStatusCode.ServiceUnavailable, resp.status)
        // PlayerPage 用这个字符串区分「未配置」与真故障；改名会退化成红色报错
        assertTrue(resp.bodyAsText().contains("DANMAKU_NOT_CONFIGURED"), resp.bodyAsText())
    }

    @Test
    fun `ssrf denial does not echo the internal address`() = testApplication {
        application { module(testConfig()) }
        val resp = client.get("/api/proxy") { parameter("url", "http://192.168.1.7/x.m3u8") }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
        val body = resp.bodyAsText()
        assertFalse(body.contains("192.168.1.7"), "拒绝原因不该回显内网地址: $body")
    }

    @Test
    fun `cross-site writes are rejected but reads are not`() = testApplication {
        application { module(testConfig()) }
        val evil = "https://evil.example"
        assertEquals(
            HttpStatusCode.Forbidden,
            client.post("/api/selector/sources") {
                header(HttpHeaders.Origin, evil)
                contentType(ContentType.Application.Json)
                setBody("{}")
            }.status,
            "跨站 POST 必须挡掉：默认无鉴权时这是远程装数据源的入口",
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            client.delete("/api/selector/sources/nope") { header(HttpHeaders.Origin, evil) }.status,
        )
        // 跨站读不该挡：响应体浏览器本来也读不到，挡了只会误伤嵌入场景
        assertEquals(
            HttpStatusCode.OK,
            client.get("/api/selector/sources") { header(HttpHeaders.Origin, evil) }.status,
        )
        // 同源的 Origin 要正常放行（浏览器对同源 POST 同样会带 Origin）
        val configJson = javaClass.getResourceAsStream("/selectors/example-css-site.json")!!
            .bufferedReader().use { it.readText() }
        val ok = client.post("/api/selector/sources") {
            header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.Json)
            setBody(configJson)
        }
        assertEquals(HttpStatusCode.Created, ok.status, ok.bodyAsText())
    }

    @Test
    fun `hashed assets are cached forever and the entry is not`() = testApplication {
        val dir = java.nio.file.Files.createTempDirectory("animeko-fe-cache")
        java.io.File(dir.toFile(), "index.html").writeText("<html>spa</html>")
        java.io.File(dir.toFile(), "assets").mkdirs()
        java.io.File(dir.toFile(), "assets/app-abc123.js").writeText("console.log(1)")
        application { module(testConfig().copy(frontendDir = dir.toString())) }

        val js = client.get("/assets/app-abc123.js")
        val cc = js.headers[HttpHeaders.CacheControl].orEmpty()
        assertTrue(cc.contains("immutable") && cc.contains("max-age=31536000"), cc)

        val spa = client.get("/subject/123")
        assertEquals("no-cache", spa.headers[HttpHeaders.CacheControl], "入口被缓存就会指向已删除的旧 chunk")
    }

    @Test
    fun `api response models are serializable`() {
        // 回归：以下模型经 call.respond(...) 做 JSON 序列化。
        // 缺 @Serializable 时编译不报错、引擎单测（直接调方法）也不暴露，
        // 直到运行期 respond 才抛 "Serializer not found"，导致数据源/弹幕接口成功路径全部 500。
        // 这里显式调用 .serializer()：未标注 @Serializable 时编译期即失败。
        val json = Json { encodeDefaults = true }

        val hit = json.encodeToString(
            SubjectHit.serializer(),
            SubjectHit("孤独摇滚", "https://example-site.test/subject/1", null),
        )
        assertTrue(hit.contains("\"name\":\"孤独摇滚\""), hit)
        assertTrue(hit.contains("\"url\":\"https://example-site.test/subject/1\""), hit)

        val episode = EpisodeHit(null, "第01话", "01", 1, "https://example-site.test/play/1")
        val ep = json.encodeToString(EpisodeHit.serializer(), episode)
        assertTrue(ep.contains("\"playUrl\":\"https://example-site.test/play/1\""), ep)

        val episodes = json.encodeToString(
            EpisodesResult.serializer(),
            EpisodesResult(listOf("默认线路"), listOf(episode), emptyList()),
        )
        assertTrue(episodes.contains("\"episodes\""), episodes)

        val video = json.encodeToString(
            ResolvedVideo.serializer(),
            ResolvedVideo("https://example-site.test/v/index.m3u8", "https://example-site.test", "ua", ""),
        )
        assertTrue(video.contains("\"url\":\"https://example-site.test/v/index.m3u8\""), video)

        val comment = json.encodeToString(
            DdComment.serializer(),
            DdComment(cid = 1L, time = 12.5, text = "前方高能", color = 16777215, mode = 1),
        )
        assertTrue(comment.contains("\"text\":\"前方高能\""), comment)
        assertTrue(comment.contains("\"time\":12.5"), comment)
    }
}
