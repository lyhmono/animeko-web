package com.animeko.web.api

import com.animeko.web.config.AppConfig
import com.animeko.web.danmaku.DdComment
import com.animeko.web.module
import com.animeko.web.selector.EpisodeHit
import com.animeko.web.selector.EpisodesResult
import com.animeko.web.selector.ResolvedVideo
import com.animeko.web.selector.SubjectHit
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `auth rejects missing token and accepts bearer`() = testApplication {
        application { module(testConfig(authEnabled = true, token = "secret-token")) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/health").status)
        val ok = client.get("/api/health") {
            header("Authorization", "Bearer secret-token")
        }
        assertEquals(HttpStatusCode.OK, ok.status)
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
        assertEquals(HttpStatusCode.Created, created.status)

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
