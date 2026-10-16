package com.animeko.web.auth

import com.animeko.web.config.AppConfig
import com.animeko.web.module
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
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
import kotlin.test.fail

class AuthFlowTest {

    private fun config(
        user: String? = "me",
        password: String? = "correct horse",
        token: String? = null,
        authEnabled: Boolean = true,
        trustedProxyHops: Int = 0,
    ) = AppConfig(
        authEnabled = authEnabled,
        authUser = user,
        authPassword = password,
        authToken = token,
        trustedProxyHops = trustedProxyHops,
        dataDir = Files.createTempDirectory("animeko-auth-test"),
        frontendDir = "definitely-not-exist",
    )

    private fun cookieOf(response: io.ktor.client.statement.HttpResponse): String {
        val raw = response.headers[HttpHeaders.SetCookie] ?: fail("登录成功必须下发 Set-Cookie")
        return raw.substringBefore(';')
    }

    @Test
    fun `login issues a hardened session cookie that unlocks the api`() = testApplication {
        application { module(config()) }
        val denied = client.get("/api/selector/sources")
        assertEquals(HttpStatusCode.Unauthorized, denied.status)

        val resp = client.post("/api/session/login") {
            setBody("""{"username":"me","password":"correct horse"}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val raw = resp.headers[HttpHeaders.SetCookie] ?: fail("缺少 Set-Cookie")
        assertTrue(raw.contains("HttpOnly"), raw)
        // 会话 Cookie 会被自动带到所有同源请求上，SameSite 必须是 Strict，
        // 否则恶意页面就能借用户的会话调用写接口（Origin 检查只是第二层）
        assertTrue(raw.contains("SameSite=Strict"), raw)
        assertTrue(raw.contains("Path=/"), raw)
        assertTrue(raw.contains("Max-Age="), raw)

        val ok = client.get("/api/selector/sources") { header(HttpHeaders.Cookie, cookieOf(resp)) }
        assertEquals(HttpStatusCode.OK, ok.status)
    }

    @Test
    fun `health reports login state so the SPA knows whether to show the gate`() = testApplication {
        application { module(config()) }
        // /api/health 必须免鉴权：Docker HEALTHCHECK 与前端登录页都要读它
        val before = Json.parseToJsonElement(client.get("/api/health").bodyAsText()).jsonObject
        assertEquals("true", before["authEnabled"]?.jsonPrimitive?.content)
        assertEquals("false", before["authenticated"]?.jsonPrimitive?.content)

        val login = client.post("/api/session/login") {
            setBody("""{"username":"me","password":"correct horse"}""")
        }
        val after = Json.parseToJsonElement(
            client.get("/api/health") { header(HttpHeaders.Cookie, cookieOf(login)) }.bodyAsText()
        ).jsonObject
        assertEquals("true", after["authenticated"]?.jsonPrimitive?.content)
    }

    @Test
    fun `wrong credentials never set a cookie`() = testApplication {
        application { module(config()) }
        for (body in listOf(
            """{"username":"me","password":"wrong"}""",
            """{"username":"attacker","password":"correct horse"}""",
            """{"username":"me","password":""}""",
            """{"username":"me"}""",
        )) {
            val resp = client.post("/api/session/login") { setBody(body) }
            assertTrue(
                resp.status == HttpStatusCode.Unauthorized || resp.status == HttpStatusCode.BadRequest,
                "$body -> ${resp.status}",
            )
            assertTrue(resp.headers.getAll(HttpHeaders.SetCookie).isNullOrEmpty(), "$body 不该发 Cookie")
        }
    }

    @Test
    fun `logout revokes the session server side`() = testApplication {
        application { module(config()) }
        val login = client.post("/api/session/login") {
            setBody("""{"username":"me","password":"correct horse"}""")
        }
        val cookie = cookieOf(login)
        val token = cookie.substringAfter('=').trim()
        assertEquals(HttpStatusCode.OK, client.get("/api/selector/sources") { header(HttpHeaders.Cookie, cookie) }.status)

        val out = client.post("/api/session/logout") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.OK, out.status)
        val cleared = out.headers[HttpHeaders.SetCookie] ?: fail("注销应当覆盖同名 Cookie")
        assertTrue(cleared.contains("Max-Age=0"), cleared)

        // 只清浏览器端不够：会话号必须在服务端也被作废（否则 Cookie 被转发出去就还能用）
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/selector/sources") { header(HttpHeaders.Cookie, cookie) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/selector/sources") { header("Authorization", "Bearer $token") }.status)
    }

    @Test
    fun `token only deployment can log in with the token as password`() = testApplication {
        application { module(config(user = null, password = null, token = "s3cret-token")) }
        val ok = client.post("/api/session/login") {
            setBody("""{"username":"anything","password":"s3cret-token"}""")
        }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals(
            HttpStatusCode.OK,
            client.get("/api/selector/sources") { header(HttpHeaders.Cookie, cookieOf(ok)) }.status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/api/session/login") { setBody("""{"username":"u","password":"token"}""") }.status,
        )
    }

    @Test
    fun `repeated failures get rate limited for one client`() = testApplication {
        val limiter = LoginRateLimiter(maxAttempts = 3, windowMs = 60_000)
        application { module(config(), loginLimiter = limiter) }
        repeat(3) {
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.post("/api/session/login") { setBody("""{"username":"me","password":"nope"}""") }.status,
            )
        }
        // 次数用完之后，连「输对了口令」也要先等窗口过去：限速的意义就是让在线爆破没有意义
        val blocked = client.post("/api/session/login") {
            setBody("""{"username":"me","password":"correct horse"}""")
        }
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
        assertTrue(blocked.headers[HttpHeaders.RetryAfter]?.isNotBlank() == true, "429 要给浏览器 Retry-After")
    }

    @Test
    fun `auth disabled skips the gate entirely`() = testApplication {
        application { module(config(authEnabled = false)) }
        assertEquals(HttpStatusCode.OK, client.get("/api/selector/sources").status)
        val body = Json.parseToJsonElement(client.get("/api/health").bodyAsText()).jsonObject
        assertEquals("false", body["authEnabled"]?.jsonPrimitive?.content)
        assertEquals("true", body["authenticated"]?.jsonPrimitive?.content)
        // 没配凭据却调登录：明确报状态，而不是让用户对着永远失败的表单发呆
        assertEquals(
            HttpStatusCode.Conflict,
            client.post("/api/session/login") { setBody("""{"username":"a","password":"b"}""") }.status,
        )
    }

    @Test
    fun `login page can be served but the api stays shut`() = testApplication {
        // 静态资源不鉴权：登录页本身必须能打开，否则用户只会看到一个空白/401
        application { module(config()) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/bangumi/search?q=x").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/health").status)
    }

    // ---- SessionStore / LoginRateLimiter 单元行为 ----

    @Test
    fun `session tokens are unguessable and independent`() {
        val store = SessionStore()
        val tokens = (1..50).map { store.create() }.toSet()
        assertEquals(50, tokens.size)
        tokens.forEach { assertTrue(it.matches(Regex("^[0-9a-f]{64}$")), it) }
        assertFalse(store.isValid(tokens.first().dropLast(1)))
        assertFalse(store.isValid(""))
        assertFalse(store.isValid("nope"))
    }

    @Test
    fun `session expires on a sliding window`() {
        var now = 1_000L
        val store = SessionStore(ttlMs = 5_000, now = { now })
        val token = store.create()
        now += 4_000
        assertTrue(store.isValid(token))     // 使用中：续期
        now += 4_000
        assertTrue(store.isValid(token), "距上次使用只过 4s，不该失效")
        now += 5_001
        assertFalse(store.isValid(token), "静置超过 TTL 必须失效")
        assertEquals(0, store.size, "过期会话要能被回收，否则长期运行只增不减")
    }

    @Test
    fun `a stalled clock never shortens a session below its remaining life`() {
        var now = 0L
        val store = SessionStore(ttlMs = 10_000, now = { now })
        val token = store.create()
        now = 5_000
        assertTrue(store.isValid(token))     // 续到 15_000
        now = 4_000                          // 时钟回拨（NTP 校正）
        assertTrue(store.isValid(token))
        now = 14_000
        assertTrue(store.isValid(token), "回拨不应把会话寿命截短")
    }

    @Test
    fun `session store is bounded`() {
        val store = SessionStore(ttlMs = 60_000, maxEntries = 8)
        repeat(40) { store.create() }
        assertTrue(store.size <= 8, "会话库必须有上界：${store.size}")
    }

    @Test
    fun `rate limiter map stays bounded under ip churn`() {
        // 扫描器会轮换源 IP：每个 IP 留一个 Bucket，只在失败时清理的话
        // blocked() 这条更常走的路径会让 map 随扫描流量单调增长
        var now = 0L
        val limiter = LoginRateLimiter(maxAttempts = 2, windowMs = 1_000, now = { now })
        repeat(20_000) { i ->
            limiter.blocked("ip-$i")
            if (i % 3 == 0) limiter.onFailure("ip-$i")
            if (i % 500 == 0) now += 1_001
        }
        assertTrue(limiter.trackedClients <= 4_096, "限速表必须有上界：${limiter.trackedClients}")
    }

    @Test
    fun `rate limiter windows reset`() {
        var now = 0L
        val limiter = LoginRateLimiter(maxAttempts = 2, windowMs = 1_000, now = { now })
        assertFalse(limiter.blocked("ip"))
        limiter.onFailure("ip")
        limiter.onFailure("ip")
        assertTrue(limiter.blocked("ip"))
        assertEquals(2, limiter.attempts("ip"))
        now = 1_001
        assertFalse(limiter.blocked("ip"), "窗口结束就要放开")
        limiter.onFailure("ip")
        limiter.onSuccess("ip")
        assertFalse(limiter.blocked("ip"))
        assertEquals(0, limiter.attempts("ip"))
    }

    @Test
    fun `forged x-forwarded-for cannot reset the counter without a trusted proxy`() = testApplication {
        application { module(config()) }
        // hops=0（直连部署）：XFF 完全由客户端提供。若拿它当限速键，
        // 攻击者每输错一次就轮换一个假 IP，计数器永远攒不满 —— 在线口令字典复活。
        // 这里连换 10 个假地址输错口令，第 9 次起必须 429
        var last = HttpStatusCode.OK
        repeat(10) { i ->
            last = client.post("/api/session/login") {
                header(HttpHeaders.XForwardedFor, "10.0.0.$i")
                setBody("""{"username":"me","password":"nope"}""")
            }.status
        }
        assertEquals(HttpStatusCode.TooManyRequests, last)
    }

    @Test
    fun `trusted proxy hops make the limiter per real client`() = testApplication {
        application { module(config(trustedProxyHops = 1)) }
        // 反代之后所有请求的 remoteHost 都是代理自己：不认 XFF 的话，
        // 一个客户端输错到限额，其他所有人跟着被锁 5 分钟。
        // hops=1 时取 XFF 最后一个地址当真实客户端，各记各的账。
        repeat(8) {
            client.post("/api/session/login") {
                header(HttpHeaders.XForwardedFor, "203.0.113.7")
                setBody("""{"username":"me","password":"nope"}""")
            }
        }
        val stillBlocked = client.post("/api/session/login") {
            header(HttpHeaders.XForwardedFor, "203.0.113.7")
            setBody("""{"username":"me","password":"correct horse"}""")
        }
        assertEquals(HttpStatusCode.TooManyRequests, stillBlocked.status)

        val other = client.post("/api/session/login") {
            header(HttpHeaders.XForwardedFor, "203.0.113.8")
            setBody("""{"username":"me","password":"correct horse"}""")
        }
        assertEquals(HttpStatusCode.OK, other.status, other.bodyAsText())
    }

    @Test
    fun `forwarded chain shorter than the hop count falls back to remote host`() = testApplication {
        application { module(config(trustedProxyHops = 3)) }
        // 声称 3 层代理但 XFF 只有一个地址：链路被人裁剪/伪造过，
        // 不许把那个孤值当真实客户端，退回 remoteHost 记账（行为与直连一致）
        var last = HttpStatusCode.OK
        repeat(10) { i ->
            last = client.post("/api/session/login") {
                header(HttpHeaders.XForwardedFor, "10.0.0.$i")
                setBody("""{"username":"me","password":"nope"}""")
            }.status
        }
        assertEquals(HttpStatusCode.TooManyRequests, last)
    }
}
