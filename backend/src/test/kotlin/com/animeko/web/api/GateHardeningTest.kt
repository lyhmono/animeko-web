package com.animeko.web.api

import com.animeko.web.config.AppConfig
import com.animeko.web.module
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 鉴权门控与 CSRF 的对抗性回归。
 *
 * 这组用例存在的理由：门控曾经用 `request.local.uri` 的字符串前缀判断「这是不是 /api/」，
 * 而 Ktor 的路由是按段匹配的（空段被忽略、每段会 URL 解码），于是
 * `//api/selector/sources`、`/%61pi/selector/sources` 这类写法能跳过门控打到真接口 ——
 * 实测可以在完全没有凭据的情况下读走数据源列表、覆写个人数据、调用订阅导入。
 * 现在门控挂在路由子树上，由「路由匹配到了哪个 handler」决定，不看 URI 字符串。
 */
class GateHardeningTest {

    private fun config(user: String? = "me", password: String? = "pw", port: Int = 8080) = AppConfig(
        authEnabled = true,
        port = port,
        authUser = user,
        authPassword = password,
        dataDir = Files.createTempDirectory("animeko-gate"),
        frontendDir = "definitely-not-exist",
    )

    private val basic = "Basic " + java.util.Base64.getEncoder().encodeToString("me:pw".toByteArray())

    private fun guess(n: Int) =
        "Basic " + java.util.Base64.getEncoder().encodeToString("me:guess$n".toByteArray())

    /**
     * 用「原始路径段」发请求：直接给字符串路径会被 URL 构造器归一化（`//` 当 authority、
     * `%61` 重新编码），那样就等于没测到这次回归。
     * 传入 ["", "api", "x"] 会渲染成 `//api/x`，传 ["%61pi", "x"] 会渲染成 `/%61pi/x`。
     */
    private suspend fun HttpResponse.isAuthorized(): Boolean =
        Json.parseToJsonElement(bodyAsText()).jsonObject["authenticated"]!!.jsonPrimitive.content.toBoolean()

    private suspend fun rawGet(client: io.ktor.client.HttpClient, vararg segments: String): HttpResponse =
        client.get {
            url { encodedPathSegments = segments.toList() }
        }

    @Test
    fun `path spelling tricks hit the gate instead of the handler`() = testApplication {
        application { module(config()) }
        val tricks = listOf(
            listOf("", "api", "selector", "sources") to "//api/selector/sources",
            listOf("%61pi", "selector", "sources") to "/%61pi/selector/sources",
            listOf("api", "", "selector", "sources") to "/api//selector/sources",
            listOf("", "api", "data") to "//api/data",
            listOf("%61pi", "data") to "/%61pi/data",
            listOf("", "api", "selector", "subscriptions", "preview") to "//api/selector/subscriptions/preview",
            listOf("", "api", "proxy") to "//api/proxy",
        )
        for ((segments, label) in tricks) {
            val naked = rawGet(client, *segments.toTypedArray())
            assertTrue(
                naked.status == HttpStatusCode.Unauthorized ||
                    naked.status == HttpStatusCode.NotFound ||
                    naked.status == HttpStatusCode.MethodNotAllowed,
                "$label 绕过了鉴权：${naked.status} ${naked.bodyAsText().take(140)}",
            )
        }

        // 反面对照：这些写法本来就路由到真接口 —— 带上凭据必须拿到 JSON。
        // 少了这一半断言，上面的 401 可能只是「路径没匹配上」，测了个寂寞。
        val withAuth = client.get("/api/selector/sources") { header(HttpHeaders.Authorization, basic) }
        assertEquals(HttpStatusCode.OK, withAuth.status)
        assertTrue(withAuth.bodyAsText().trim().startsWith("["), withAuth.bodyAsText().take(80))
        val tricked = rawGet(client, "", "api", "selector", "sources").let {
            client.get {
                url { encodedPathSegments = listOf("", "api", "selector", "sources") }
                header(HttpHeaders.Authorization, basic)
            }
        }
        assertEquals(HttpStatusCode.OK, tricked.status, "写法归一化后仍然是同一个接口")
        assertTrue(tricked.bodyAsText().trim().startsWith("["), tricked.bodyAsText().take(80))
    }

    @Test
    fun `cross-site writes are refused even with correct credentials`() = testApplication {
        application { module(config()) }
        // 浏览器对 Basic 凭据是按域缓存、会自动附带的，SameSite 只管 Cookie。
        // 用户在质询弹窗里输过一次之后，恶意页面的跨站 POST 就会带着那份凭据打进来，
        // 此时唯一还能识别「这是跨站」的是 Origin —— 所以这层不能因为「有鉴权」而省掉。
        val crossSite = client.put("/api/data") {
            header(HttpHeaders.Authorization, basic)
            header(HttpHeaders.Origin, "https://evil.example")
            setBody("""{"favorites":[]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, crossSite.status, crossSite.bodyAsText())
        assertTrue(crossSite.bodyAsText().contains("CROSS_SITE_REQUEST"), crossSite.bodyAsText())
        // 服务端自己造的 Host 也算跨站：Origin 与实际被投递的主机不一致
        val forgedHost = client.put("/api/data") {
            header(HttpHeaders.Authorization, basic)
            header(HttpHeaders.Origin, "http://localhost")
            header(HttpHeaders.Host, "evil.example")
            setBody("""{"favorites":[]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, forgedHost.status, "Origin 与 Host 不一致必须拒")

        val sameOrigin = client.put("/api/data") {
            header(HttpHeaders.Authorization, basic)
            header(HttpHeaders.Origin, "http://localhost")
            setBody("""{"favorites":[{"id":1}]}""")
        }
        assertEquals(HttpStatusCode.OK, sameOrigin.status, sameOrigin.bodyAsText())

        // 不带 Origin 的（curl、脚本）走鉴权那一层，不受 CSRF 检查影响
        val scripted = client.put("/api/data") {
            header(HttpHeaders.Authorization, basic)
            setBody("""{"favorites":[]}""")
        }
        assertEquals(HttpStatusCode.OK, scripted.status)

        // 读不受影响：<video>、封面、hls.js 分片都没法带自定义头
        val read = client.get("/api/selector/sources") { header(HttpHeaders.Authorization, basic) }
        assertEquals(HttpStatusCode.OK, read.status)
    }

    @Test
    fun `login is csrf protected too`() = testApplication {
        // 登录接口在鉴权子树之外（它本来就是要给未登录者用的），CSRF 检查必须自己做
        application { module(config()) }
        val crossSite = client.post("/api/session/login") {
            header(HttpHeaders.Origin, "https://evil.example")
            setBody("""{"username":"me","password":"pw"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, crossSite.status, crossSite.bodyAsText())
        val ok = client.post("/api/session/login") {
            header(HttpHeaders.Origin, "http://localhost")
            setBody("""{"username":"me","password":"pw"}""")
        }
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
    }

    @Test
    fun `health does not answer whether a header credential is correct`() = testApplication {
        // /api/health 免鉴权。它一旦承认 Authorization 头，
        // 就成了一台不限速的「口令猜对了没有」预言机，登录接口的限速形同虚设（实测过）
        application { module(config()) }
        val wrong = client.get("/api/health") { header(HttpHeaders.Authorization, guess(1)) }
        assertEquals(HttpStatusCode.OK, wrong.status)
        assertFalse(
            Json.parseToJsonElement(wrong.bodyAsText()).jsonObject["authenticated"]!!
                .jsonPrimitive.content.toBoolean(),
            "健康检查不该承认 Basic 凭据",
        )
        val right = client.get("/api/health") { header(HttpHeaders.Authorization, basic) }
        assertEquals(HttpStatusCode.OK, right.status)
        assertFalse(
            Json.parseToJsonElement(right.bodyAsText()).jsonObject["authenticated"]!!
                .jsonPrimitive.content.toBoolean(),
            "健康检查只看会话 Cookie：不回答才是答案",
        )
    }

    @Test
    fun `credential guessing on protected endpoints gets throttled`() = testApplication {
        application { module(config()) }
        repeat(8) { i ->
            val r = client.get("/api/selector/sources") { header(HttpHeaders.Authorization, guess(i)) }
            assertEquals(HttpStatusCode.Unauthorized, r.status, "第 ${i + 1} 次猜测")
        }
        // 用完次数之后，连「猜对了」也要先等窗口过去：限速的意义就是让在线爆破没有意义
        val blocked = client.get("/api/selector/sources") { header(HttpHeaders.Authorization, basic) }
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
        assertTrue(blocked.headers[HttpHeaders.RetryAfter]?.isNotBlank() == true, "429 要给 Retry-After")
    }

    /**
     * 成功认证不能把别人的失败计数清零。
     *
     * 浏览器对 Basic 凭据是按域缓存、自动附带的，而限速键在直连部署下就是来源 IP
     * （反代忘了配 ANIMEKO_TRUSTED_PROXY_HOPS 时，全站所有客户端共用同一个键）。
     * 于是原先的 `if (ok) limiter.onSuccess(key)` 等于：受害者每发一次正常请求，
     * 攻击者的失败计数就被抹一次 —— 只要受害者还在用这个页面，爆破就没有上限。
     * 这条防线在共享出口 IP（CGNAT、公司网络）下会整体失效，而且是静默失效。
     */
    /**
     * 「同一台机器的另一个端口」在浏览器眼里是**同站**（SameSite 不看端口，会话 Cookie 照样发），
     * 所以端口比较是这一格唯一的防线。服务跑在默认端口上时 Host 头通常不带端口，
     * 原先那行 `?: return false` 把「拿不到端口」一律当成信息不足放行，
     * 等于让本机任意另一个端口的页面都能写这个账号的数据。
     * 现在用本进程的监听端口把这个岔路分开：默认端口 → 照常比；非默认端口且 Host 被抹掉端口
     * （前置反代的典型形态）→ 不比，避免把正常同源请求全部误杀。
     */
    @Test
    fun `host header without a port still gets the port check on the default port`() = testApplication {
        application { module(config(port = 80)) }
        val crossPort = client.put("/api/data") {
            header(HttpHeaders.Authorization, basic)
            header(HttpHeaders.Host, "127.0.0.1")
            header(HttpHeaders.Origin, "http://127.0.0.1:9999")
            setBody("""{"favorites":[]}""")
        }
        assertEquals(
            HttpStatusCode.Forbidden, crossPort.status,
            "80 端口上的服务，Host 不带端口也要比端口：同机异端口是同站，SameSite 挡不住",
        )
        val samePort = client.put("/api/data") {
            header(HttpHeaders.Authorization, basic)
            header(HttpHeaders.Host, "127.0.0.1")
            header(HttpHeaders.Origin, "http://127.0.0.1")
            setBody("""{"favorites":[]}""")
        }
        assertEquals(HttpStatusCode.OK, samePort.status, samePort.bodyAsText())
    }

    /** 反向对照：非默认端口 + Host 被反代抹掉端口时不许误杀（那会逼用户去开 ALLOW_CROSS_ORIGIN） */
    @Test
    fun `a proxy that strips the port does not lock every write out`() = testApplication {
        application { module(config(port = 8080)) }
        val viaProxy = client.put("/api/data") {
            header(HttpHeaders.Authorization, basic)
            header(HttpHeaders.Host, "127.0.0.1")
            header(HttpHeaders.Origin, "https://127.0.0.1")
            setBody("""{"favorites":[]}""")
        }
        assertEquals(
            HttpStatusCode.OK, viaProxy.status,
            "端口信息确实不可得时应当放行，否则唯一的自救办法就是拆掉整道 CSRF 防线：" + viaProxy.bodyAsText(),
        )
    }

    @Test
    fun `a legitimate success does not reset the attacker's counter`() = testApplication {
        application { module(config()) }
        repeat(7) { i ->
            val r = client.get("/api/selector/sources") { header(HttpHeaders.Authorization, guess(i)) }
            assertEquals(HttpStatusCode.Unauthorized, r.status, "第 ${i + 1} 次猜测不该先被限流")
        }
        // 受害者：带着缓存的 Basic 凭据正常访问一次，成功
        val victim = client.get("/api/selector/sources") { header(HttpHeaders.Authorization, basic) }
        assertEquals(HttpStatusCode.OK, victim.status)
        // 攻击者继续猜。blocked() 是在验证凭据**之前**判的，所以第 8 次仍是 401、第 9 次才 429；
        // 关键是这条曲线不能被一次成功认证重置回 0。
        val eighth = client.get("/api/selector/sources") { header(HttpHeaders.Authorization, guess(8)) }
        assertEquals(HttpStatusCode.Unauthorized, eighth.status, "第 8 次到界，但响应发生在记账之后")
        val ninth = client.get("/api/selector/sources") { header(HttpHeaders.Authorization, guess(9)) }
        assertEquals(
            HttpStatusCode.TooManyRequests,
            ninth.status,
            "一次成功认证把失败计数清零了——共享出口 IP 下在线爆破不再有上限",
        )
    }

    @Test
    fun `a logged-in session is not punished for someone else guessing`() = testApplication {
        application { module(config()) }
        val login = client.post("/api/session/login") { setBody("""{"username":"me","password":"pw"}""") }
        assertEquals(HttpStatusCode.OK, login.status)
        val cookie = login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')

        repeat(10) { i ->
            client.get("/api/selector/sources") { header(HttpHeaders.Authorization, guess(i)) }
        }
        // 会话号是 64 位十六进制随机数，猜不中，所以不参与限速计数
        val stillOk = client.get("/api/data") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.OK, stillOk.status, "已登录的会话不该被别人的失败次数连坐")
    }

    @Test
    fun `native auth dialog is only offered where a human can use it`() = testApplication {
        application { module(config()) }
        // 带 Sec-Fetch-Mode 的是浏览器内部的请求：给它们下发质询会弹原生口令框，
        // 直接盖住前端自己的登录页；而 hls.js 的分片请求全是 XHR
        val xhr = client.get("/api/selector/sources") { header("Sec-Fetch-Mode", "cors") }
        assertEquals(HttpStatusCode.Unauthorized, xhr.status)
        assertFalse(xhr.headers.contains(HttpHeaders.WWWAuthenticate), "XHR 不该收到 Basic 质询")

        val nav = client.get("/api/selector/sources") { header("Sec-Fetch-Mode", "navigate") }
        assertTrue(nav.headers.contains(HttpHeaders.WWWAuthenticate), "地址栏直接访问要能弹窗输口令")

        val curl = client.get("/api/selector/sources")
        assertEquals(HttpStatusCode.Unauthorized, curl.status)
        assertTrue(
            curl.headers.contains(HttpHeaders.WWWAuthenticate),
            "curl 这类客户端没有 Sec-Fetch-Mode，只能靠质询弹窗",
        )
    }

    @Test
    fun `auth disabled still csrf protects`() = testApplication {
        // 不配凭据就是「打开即用」模式：这时 CSRF 是唯一的写保护，不能跟着鉴权一起消失
        application { module(config().copy(authEnabled = false)) }
        assertEquals(HttpStatusCode.OK, client.get("/api/selector/sources").status)
        val crossSite = client.put("/api/data") {
            header(HttpHeaders.Origin, "https://evil.example")
            setBody("""{"a":1}""")
        }
        assertEquals(HttpStatusCode.Forbidden, crossSite.status)
        val local = client.put("/api/data") { setBody("""{"a":1}""") }
        assertEquals(HttpStatusCode.OK, local.status)
    }
}
