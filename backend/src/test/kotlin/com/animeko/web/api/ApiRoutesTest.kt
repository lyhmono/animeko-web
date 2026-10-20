package com.animeko.web.api

import com.animeko.web.config.AppConfig
import com.animeko.web.module
import com.animeko.web.selector.EpisodeHit
import com.animeko.web.selector.EpisodesResult
import com.animeko.web.selector.SubjectHit
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
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
    fun `deep nesting is refused at the entry instead of blowing the stack`() = testApplication {
        // JsonGuardTest 只测了那个纯函数本身，**四个调用点一个都没测到**：
        // 把 requireShallow 那四行全删掉，整套测试仍然全绿。
        //
        // 这条用例第一次写出来的时候**也是一条废用例**，这里把原因留下：
        // 删掉闸门后那个请求仍然回 400，body 里也仍然带「嵌套」两个字 ——
        // 因为 SOE 穿过了 userData.write 的 catch (Exception)，落进
        // Main 的 exception<StackOverflowError> 兜底，而它的文案同样写着「嵌套过深」。
        // 只看状态码 + 关键字，就分辨不出「闸门挡住了」和「栈已经打穿过一次」，
        // 而这两者的区别正是本用例存在的意义（后者每次要落上千行栈）。
        // 所以断言钉死在**闸门的文案**上：超深、128 层。
        application { module(testConfig()) }
        val bomb = "[".repeat(200_000) + "]".repeat(200_000)
        val refused = client.put("/api/data") { setBody(bomb) }
        assertEquals(HttpStatusCode.BadRequest, refused.status, "深度超限应当是 400（内容坏的是来访者的请求）")
        val reason = refused.bodyAsText()
        assertTrue(reason.contains("超过") && reason.contains("128"), "要的是入站闸门的答复，不是栈崩之后的兜底文案：${reason.take(160)}")
        // 正面对照：同一接口对正常深度的文档必须照常可写，
        // 否则上面那个 400 可能只是「这个接口根本不接受任何东西」
        val ok = client.put("/api/data") { setBody("""{"favorites":[{"id":1}]}""") }
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        // 字符串里的括号不算深度：这是扫描器最容易写错的一格
        val strings = client.put("/api/data") { setBody("""{"note":"${"[".repeat(5000)}"}""") }
        assertEquals(HttpStatusCode.OK, strings.status, strings.bodyAsText())
    }

    @Test
    fun `a deep document already on disk neither 500s nor gets thrown away`() = testApplication {
        // 磁盘上的旧文件不经过入站闸门（那道闸在 receiveCappedText 里），
        // 所以这一格是唯一还能碰到「极深 JSON 解析」的入口。
        //
        // 三条**实测事实**，写在用例里免得后人照着注释猜机制（本轮跑出来的）：
        //  · 20 万层（400KB）在这条路径上抛的是 StackOverflowError；
        //  · 它被读侧的 runCatching **接住了** —— runCatching 捕获的是 Throwable，
        //    不是 Exception，所以 Error 也照样接（这一点曾经写在 JsonGuard 的注释里，
        //    而且是错的，现已改掉）；
        //  · 接住之后读侧按「坏文件」处理：备份成 *.bad、对外回一份 {}。
        //    「坏文件不该把整个页面带走」是对的，但它有个必须记在账上的副作用：
        //    客户端拿到成功的 200 就认为同步过了，于是下一次写入会覆盖主文档，
        //    而原件只剩 *.bad 那份谁也不会回去读的备份。
        // 所以这条路径**不会**走到 Main 的 exception<StackOverflowError>（那里
        // 只有 StatusPages 一条链上没人接的 Error 才轮得到），本用例断言的是
        // 现在真实可观察到的行为，不是一个听起来正确的假设。
        val cfg = testConfig()
        val stored = cfg.dataDir.resolve("userdata.json")
        java.nio.file.Files.writeString(stored, "[".repeat(200_000) + "]".repeat(200_000))
        application { module(cfg) }
        val resp = client.get("/api/data")
        assertTrue(
            resp.status == HttpStatusCode.OK || resp.status == HttpStatusCode.BadRequest,
            "磁盘上的深嵌套只能得到 200/400，实际 ${resp.status}：${resp.bodyAsText().take(120)}",
        )
        // 不管走哪条分支，用户写过的字节都不许凭空消失：要么还在主文档里，
        // 要么在 *.bad 里。这一格盯的是「解析失败顺手删档/只留一份没人读的副本还不备份」那类改动。
        val survived = java.nio.file.Files.isRegularFile(stored) ||
            java.nio.file.Files.isRegularFile(cfg.dataDir.resolve("userdata.json.bad"))
        assertTrue(survived, "解析不了也得把原文件留着：读侧没有权力销毁用户的存档")
    }

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
        // 只看 /api/health 是 200 等于没测：health 本来就在免鉴权白名单里，
        // 鉴权开着它同样是 200 —— 把默认值改成「开」这条用例也不会红。
        // 必须读 body 里的 authEnabled，并且真的打一个保护接口确认无需凭据可用。
        val body = Json.parseToJsonElement(client.get("/api/health").bodyAsText()).jsonObject
        assertEquals("false", body["authEnabled"]?.jsonPrimitive?.content, "没配凭据时鉴权应当是关的")
        val open = client.get("/api/selector/sources")
        assertEquals(HttpStatusCode.OK, open.status, "鉴权关闭时保护接口不该要凭据")
        assertTrue(open.bodyAsText().trim().startsWith("["), open.bodyAsText().take(80))
        // 正面对照在紧挨着的那条 `auth protects api...` 里：同一个路径、门装上以后
        // 无凭据必须是 401。两条合起来才说明「200」是因为门开着，而不是路由没接上。
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

        // 「/」走的是文件命中分支而不是回退分支，曾经漏掉 no-cache：
        // 浏览器一启发式缓存住 index.html，重新部署后就永久白屏
        for (entry in listOf("/", "/index.html")) {
            val resp = client.get(entry)
            assertEquals(HttpStatusCode.OK, resp.status, entry)
            assertEquals("no-cache", resp.headers[HttpHeaders.CacheControl], "$entry 必须每次回源")
        }
    }

    /**
     * 静态托管是「把请求路径拼到前端目录上」实现的，必须钉死它出不了那个目录：
     * 前端目录旁边往往就挨着 data/userdata.json 与 selectors/，读出去等于把
     * 收藏和内网配置交给任何一个访客。
     *
     * 这一条原先是**空的**：没有 `application { module(...) }`，测试应用里根本没装路由，
     * 三次请求全是 404 空身体，`assertFalse(body.contains("root:"))` 于是永远成立
     * ——把 ApiRoutes 里的 `file.startsWith(dir)` 删掉、让它真的去读 /etc/passwd，
     * 这条用例照样绿灯。所以这次带上「正向对照」：先证明路由装着、合法文件读得到，
     * 再断言越界读不到。
     */
    @Test
    fun `static handler cannot be walked out of the frontend directory`() = testApplication {
        val root = java.nio.file.Files.createTempDirectory("animeko-traversal")
        val frontend = root.resolve("frontend")
        java.nio.file.Files.createDirectories(frontend)
        java.io.File(frontend.toFile(), "index.html").writeText("<html>spa</html>")
        java.io.File(frontend.toFile(), "app.js").writeText("console.log(1)")
        // 放在前端目录的**旁边**：越界成功就会读到它
        java.nio.file.Files.writeString(root.resolve("SECRET.txt"), "root:secret-marker")

        application {
            module(
                AppConfig(
                    dataDir = root,
                    frontendDir = frontend.toString(),
                    authEnabled = false,
                ),
            )
        }

        // 正向对照：路由装着、文件命中分支工作。少了这一步，下面的断言可能是「空应用」给的
        val legit = client.get("/app.js")
        assertEquals(HttpStatusCode.OK, legit.status, "合法静态文件必须能读到，否则用例失去意义")
        assertTrue(legit.bodyAsText().contains("console.log"), legit.bodyAsText())

        // 客户端会把字面的 ../ 规范化掉，所以越界写法以编码形式送出去：
        // Ktor 的路由是按段解码匹配的（`/%61pi/...` 也能命中真接口，这条已被验证过），
        // 因此 `%2e%2e` 会以 `..` 这一段到达拼路径的那行代码
        val attempts = listOf(
            "/../SECRET.txt",
            "/../../SECRET.txt",
            "/%2e%2e/SECRET.txt",
            "/%2e%2e%2fSECRET.txt",
            "/%252e%252e/SECRET.txt",
            "/sub/%2e%2e/%2e%2e/SECRET.txt",
        )
        for (path in attempts) {
            val resp = client.get(path)
            val body = resp.bodyAsText()
            assertFalse(
                body.contains("secret-marker"),
                "越界读到了前端目录之外的文件：$path -> ${resp.status} ${body.take(80)}",
            )
        }
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
            VideoSourceResponse.serializer(),
            VideoSourceResponse(
                "https://example-site.test/v/index.m3u8",
                "/api/proxy?url=https%3A%2F%2Fexample-site.test%2Fv%2Findex.m3u8&sid=ab12",
            ),
        )
        assertTrue(video.contains("\"proxyPath\":\"/api/proxy?url="), video)

        val health = json.encodeToString(
            HealthResponse.serializer(),
            HealthResponse(status = "ok", version = "0.1.0", authEnabled = true, authenticated = false),
        )
        // 弹幕功能已移除，健康检查不该再有 danmakuConfigured 字段
        assertTrue(health.contains("\"authEnabled\":true"), health)
        // 前端登录门控靠 authenticated 决定去留，缺了它就会「已登录却被踢回登录页」
        assertTrue(health.contains("\"authenticated\":false"), health)
        assertTrue(!health.contains("danmaku"), health)

        // 登录成功响应：真实事故是写成 mapOf("authenticated" to true, "user" to name)，
        // 推断出 Map<String, Any> → 运行期 Serializer not found → 登录永远 500。
        val session = json.encodeToString(
            SessionStateResponse.serializer(),
            SessionStateResponse(authenticated = true, user = "me"),
        )
        assertTrue(session.contains("\"authenticated\":true"), session)
    }

    @Test
    fun `disabled source is rejected by the collection api`() = testApplication {
        application { module(testConfig()) }
        val configJson = javaClass.getResourceAsStream("/selectors/example-css-site.json")!!
            .bufferedReader().use { it.readText() }
        client.post("/api/selector/sources") {
            contentType(ContentType.Application.Json)
            setBody(configJson)
        }
        val name = java.net.URLEncoder.encode("示例站点-CSS", "UTF-8")
        val off = client.post("/api/selector/sources/$name/enable") {
            contentType(ContentType.Application.Json)
            setBody("""{"enabled":false}""")
        }
        assertEquals(HttpStatusCode.OK, off.status)
        // 停用是服务端语义：直接调采集接口也必须 404，
        // 只靠前端 filter 的话，任何绕过 UI 的调用都能抓取「已停用」的源
        val resp = client.post("/api/selector/sources/$name/search") {
            contentType(ContentType.Application.Json)
            setBody("""{"keyword":"test"}""")
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }
}
