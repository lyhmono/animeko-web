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
        cookieSecure: Boolean = false,
    ) = AppConfig(
        authEnabled = authEnabled,
        authUser = user,
        authPassword = password,
        authToken = token,
        trustedProxyHops = trustedProxyHops,
        cookieSecure = cookieSecure,
        dataDir = Files.createTempDirectory("animeko-auth-test"),
        frontendDir = "definitely-not-exist",
    )

    private fun cookieOf(response: io.ktor.client.statement.HttpResponse): String {
        val raw = response.headers[HttpHeaders.SetCookie] ?: fail("登录成功必须下发 Set-Cookie")
        return raw.substringBefore(';')
    }

    /** 登录一次，返回 `name=value` 形态的会话 Cookie。 */
    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.login(
        password: String = "correct horse",
    ): String = cookieOf(
        client.post("/api/session/login") { setBody("""{"username":"me","password":"$password"}""") },
    )

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
    fun `https deployments get the __Host- prefix and it still authenticates`() = testApplication {
        application { module(config(cookieSecure = true)) }
        val resp = client.post("/api/session/login") {
            setBody("""{"username":"me","password":"correct horse"}""")
        }
        val raw = resp.headers[HttpHeaders.SetCookie] ?: fail("登录必须下发会话 Cookie")
        assertTrue(raw.startsWith("__Host-animeko_session="), "`__Host-` 前缀只在 Secure 下合法：$raw")
        assertTrue(raw.contains("Secure"), "前缀要求 Secure，缺了浏览器会整条丢弃：$raw")
        assertTrue(raw.contains("SameSite=Strict"), raw)

        // 真正要紧的是**读侧也认这个名字**：只改写入不改读取的话，
        // 登录返回 200 而每一次后续请求都是 401，症状是「登录页反复回弹」
        val ok = client.get("/api/selector/sources") { header(HttpHeaders.Cookie, cookieOf(resp)) }
        assertEquals(HttpStatusCode.OK, ok.status, "带 __Host- 会话必须能用（Cookie 头是原样名字）")
    }

    @Test
    fun `a stale host-prefixed cookie cannot shadow a valid session`() = testApplication {
        // 实测（本机 18078）：`Cookie: __Host-animeko_session=<无效>; animeko_session=<有效>` → 401。
        // 触发路径不刁钻：HTTPS 部署（曾种下 __Host- 那份）改成 cookieSecure=false 之后，
        // 浏览器里那份还在，等它服务端过期、用户重新登录种下不带前缀的新名字，
        // 读侧却先看旧的那份 —— 每个请求 401，而注销接口自己也要鉴权、同样 401，
        // Cookie 永远清不掉。读侧必须「任一有效即算已登录」。
        application { module(config()) }
        val token = login().substringAfter('=')
        val ok = client.get("/api/selector/sources") {
            header(HttpHeaders.Cookie, "__Host-animeko_session=deadbeef; animeko_session=$token")
        }
        assertEquals(HttpStatusCode.OK, ok.status, "陈旧的 __Host- 不该遮蔽有效会话")
        val other = client.get("/api/selector/sources") {
            header(HttpHeaders.Cookie, "__Host-animeko_session=$token; animeko_session=deadbeef")
        }
        assertEquals(HttpStatusCode.OK, other.status)
        // 对照：两个都无效时必须还是 401（别为了修遮蔽把校验放过）
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/api/selector/sources") {
                header(HttpHeaders.Cookie, "__Host-animeko_session=aaa; animeko_session=bbb")
            }.status,
        )
    }

    @Test
    fun `logout invalidates both cookie name variants and clears both`() = testApplication {
        application { module(config()) }
        val a = login().substringAfter('=')
        val b = login().substringAfter('=')
        // 两份都带着（正是改过 COOKIE_SECURE 的浏览器会送出来的形状）
        val both = "__Host-animeko_session=$b; animeko_session=$a"
        assertEquals(HttpStatusCode.OK, client.get("/api/selector/sources") { header(HttpHeaders.Cookie, both) }.status)
        val out = client.post("/api/session/logout") { header(HttpHeaders.Cookie, both) }
        assertEquals(HttpStatusCode.OK, out.status)
        val cleared = out.headers.getAll(HttpHeaders.SetCookie).orEmpty()
        assertTrue(cleared.any { it.startsWith("__Host-animeko_session=;") }, cleared.toString())
        assertTrue(cleared.any { it.startsWith("animeko_session=;") }, cleared.toString())
        // 两份会话都在服务端作废了：只作废被「优先」的那一份，另一份还能用
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/selector/sources") { header(HttpHeaders.Cookie, "__Host-animeko_session=$b") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/selector/sources") { header(HttpHeaders.Cookie, "animeko_session=$a") }.status)
    }

    @Test
    fun `a failed auth expires both cookie names`() = testApplication {
        // 浏览器里留着一份服务端不认的会话时，401 必须顺手把它过期掉：
        // 否则「登录成功 → 下一个请求仍 401」会一直循环，用户只能去手动清 Cookie
        application { module(config()) }
        val resp = client.get("/api/selector/sources") {
            header(HttpHeaders.Cookie, "__Host-animeko_session=stale; animeko_session=stale2")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
        val cleared = resp.headers.getAll(HttpHeaders.SetCookie).orEmpty()
        assertEquals(2, cleared.size, "401 要把两个名字都清掉：$cleared")
        assertTrue(cleared.all { it.contains("Max-Age=0") }, cleared.toString())
    }

    @Test
    fun `an oversized login body is reported as oversized, not as a missing field`() = testApplication {
        // 实测（本机 18079）：40KB 的登录体（上限 32KB）原先回的是
        // `{"error":"请填写口令"}` —— 400 是对的，但这句话会把人引去检查表单，
        // 而真正的问题是他发了 40KB。bodyObject 以前把所有异常都吞成 null。
        application { module(config()) }
        val big = """{"username":"me","password":"${"a".repeat(40_000)}"}"""
        val resp = client.post("/api/session/login") { setBody(big) }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue("上限" in resp.bodyAsText(), "要说明是体积超限：${resp.bodyAsText()}")
        assertEquals(emptyList(), resp.headers.getAll(HttpHeaders.SetCookie).orEmpty())
    }

    @Test
    fun `a too-deep login body says nesting instead of a missing field`() = testApplication {
        application { module(config()) }
        val deep = "[".repeat(200) + "]".repeat(200)   // 远小于 32KB，但深度超过 128
        val resp = client.post("/api/session/login") { setBody(deep) }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue("嵌套" in resp.bodyAsText(), resp.bodyAsText())
    }

    @Test
    fun `plain http deployments must not use the host prefix`() = testApplication {
        // `__Host-` 要求 Secure。默认部署是 http（本机 8080 或 SSH 隧道），
        // 那时加上前缀浏览器会**整条丢弃** Set-Cookie，比不加固糟糕得多：
        // 表现就是「口令明明对了，页面一直停在登录页」。
        application { module(config(cookieSecure = false)) }
        val raw = client.post("/api/session/login") {
            setBody("""{"username":"me","password":"correct horse"}""")
        }.headers[HttpHeaders.SetCookie].orEmpty()
        assertTrue(raw.startsWith("animeko_session="), "http 部署必须用不带前缀的名字：$raw")
        assertFalse(raw.contains("__Host-"), raw)
        assertEquals(HttpStatusCode.OK, client.get("/api/selector/sources") { header(HttpHeaders.Cookie, raw.substringBefore(';')) }.status)
    }

    @Test
    fun `logout clears both cookie names`() = testApplication {
        // 读侧两个名字都认（升级/降级期间不断登录），所以注销也必须两个都覆盖：
        // 只清当前那一个，另一份同名会话会在浏览器里活下来并被重新采纳
        application { module(config(cookieSecure = true)) }
        val login = client.post("/api/session/login") {
            setBody("""{"username":"me","password":"correct horse"}""")
        }
        val cookie = cookieOf(login)
        assertEquals(HttpStatusCode.OK, client.get("/api/selector/sources") { header(HttpHeaders.Cookie, cookie) }.status)
        val out = client.post("/api/session/logout") { header(HttpHeaders.Cookie, cookie) }
        val cleared = out.headers.getAll(HttpHeaders.SetCookie).orEmpty()
        assertEquals(2, cleared.size, "两个名字都要清：$cleared")
        assertTrue(cleared.any { it.startsWith("__Host-animeko_session=;") }, cleared.toString())
        assertTrue(cleared.any { it.startsWith("animeko_session=;") }, cleared.toString())
        assertTrue(cleared.all { it.contains("Max-Age=0") }, cleared.toString())
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/selector/sources") { header(HttpHeaders.Cookie, cookie) }.status)
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
        // 正向对照：会话号本来就**允许**当 Bearer 用（命令行/脚本复放）。
        // 少了这一行，注销后那两个 401 就分辨不出「会话被作废」和「根本不支持 Bearer」——
        // 把 headerAuthorized 里的 site.isValid(token) 删掉，这两个断言照样全绿。
        assertEquals(
            HttpStatusCode.OK,
            client.get("/api/selector/sources") { header("Authorization", "Bearer $token") }.status,
            "注销前同一个会话号当 Bearer 必须可用，否则后面的 401 说明不了吊销",
        )

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
    fun `repeated failures get rate limited but the right password is never locked out`() = testApplication {
        val limiter = LoginRateLimiter(maxAttempts = 3, windowMs = 60_000)
        application { module(config(), loginLimiter = limiter) }
        repeat(3) {
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.post("/api/session/login") { setBody("""{"username":"me","password":"nope"}""") }.status,
            )
        }
        // 超限额之后，**猜错**不再得到 yes/no：在线爆破的速率就这么被封住
        val blockedWrong = client.post("/api/session/login") {
            setBody("""{"username":"me","password":"nope"}""")
        }
        assertEquals(HttpStatusCode.TooManyRequests, blockedWrong.status)
        assertTrue(blockedWrong.headers[HttpHeaders.RetryAfter]?.isNotBlank() == true, "429 要给浏览器 Retry-After")

        // 但**猜对的人不能被自己的正确口令挡在门外**。这一格原先是 429，理由写着
        // 「让在线爆破没有意义」——那站不住：攻击者口令对了本来就该进去，
        // 等 5 分钟再来一次即可，被这个决定付出的代价是零。
        // 收代价的是机主：会话只存内存，容器一重启就必须重新走登录页，
        // 而直连部署下限速键是来源 IP（攻击者只锁得到自己），
        // 反代没配 ANIMEKO_TRUSTED_PROXY_HOPS 时全站共用代理那一个键 ——
        // 任何匿名访客都能持续把机主锁在他自己家门外。
        // 实测（8 次错口令后）：正确口令 429 Retry-After: 300，
        // 而同一时刻同一套凭据走 Basic 打保护接口是 200，两条路径自相矛盾。
        val blockedRight = client.post("/api/session/login") {
            setBody("""{"username":"me","password":"correct horse"}""")
        }
        assertEquals(HttpStatusCode.OK, blockedRight.status, blockedRight.bodyAsText())
        // 这个 200 必须是真的能用（不是回了个成功却不发会话）
        assertEquals(
            HttpStatusCode.OK,
            client.get("/api/selector/sources") { header(HttpHeaders.Cookie, cookieOf(blockedRight)) }.status,
        )
        // 成功登录清掉本客户端的账：只有「确实知道口令」的人才有能力重置计数器
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/api/session/login") { setBody("""{"username":"me","password":"nope"}""") }.status,
        )
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
        // 静态资源不鉴权：登录页本身必须能打开，否则用户只会看到一个空白/401。
        //
        // 这条用例原先**没有测它的标题**：config() 把 frontendDir 指到一个不存在的目录，
        // configureStaticFrontend 在 `if (!dir.isDirectory)` 处直接 return，
        // 连那条 catch-all 路由都没装 —— 于是这里只断言了两个 API 状态码，
        // 「登录页能打开」这半句一次都没请求过。现在真的摆一份 dist 出来。
        val site = Files.createTempDirectory("animeko-login-page")
        Files.writeString(
            site.resolve("index.html"),
            """<!doctype html><html><body><div id="root"></div></body></html>""",
        )
        application { module(config().copy(frontendDir = site.toString())) }
        val page = client.get("/")
        assertEquals(HttpStatusCode.OK, page.status, "鉴权开启时登录页也必须能直接打开")
        assertTrue(page.bodyAsText().contains("id=\"root\""), page.bodyAsText().take(120))
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/bangumi/search?q=x").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/health").status)
        // 缺失的静态资源不能被 SPA 回退蒙成一份 200 的 HTML（那会让重新部署后白屏）
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/assets/definitely-missing.js").status,
            "缺失资源必须 404，而不是回退成 index.html",
        )
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
        // .7 的窗口用尽：它再猜错就得不到 yes/no
        val stillBlocked = client.post("/api/session/login") {
            header(HttpHeaders.XForwardedFor, "203.0.113.7")
            setBody("""{"username":"me","password":"nope"}""")
        }
        assertEquals(HttpStatusCode.TooManyRequests, stillBlocked.status)

        val other = client.post("/api/session/login") {
            header(HttpHeaders.XForwardedFor, "203.0.113.8")
            // 用**错**口令问：这里要证明的是「各记各的账」，
            // 而正确口令在哪个客户端都该是 200（上一条用例专门管这件事）
            setBody("""{"username":"me","password":"nope"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, other.status, other.bodyAsText())

        // 已经被限流的 .7 仍然能用正确口令进来：限速不许被拿来锁站
        val owner = client.post("/api/session/login") {
            header(HttpHeaders.XForwardedFor, "203.0.113.7")
            setBody("""{"username":"me","password":"correct horse"}""")
        }
        assertEquals(HttpStatusCode.OK, owner.status, owner.bodyAsText())
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
