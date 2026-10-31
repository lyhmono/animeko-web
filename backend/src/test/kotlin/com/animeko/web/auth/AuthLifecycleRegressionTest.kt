package com.animeko.web.auth

import com.animeko.web.config.AppConfig
import com.animeko.web.module
import com.animeko.web.testDirectory
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthLifecycleRegressionTest {
    private fun config() = AppConfig(
        authEnabled = true, authUser = "me", authPassword = "pw", authToken = "configured-token",
        dataDir = testDirectory("animeko-auth-lifecycle"), frontendDir = "missing",
    )

    @Test
    fun `bearer initiated logout revokes the dynamic session for bearer and cookie replay`() = testApplication {
        application { module(config()) }
        val login = client.post("/api/session/login") { setBody("""{"username":"me","password":"pw"}""") }
        assertEquals(HttpStatusCode.OK, login.status)
        val cookie = login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
        val token = cookie.substringAfter('=')
        assertEquals(HttpStatusCode.OK, client.get("/api/data") { header(HttpHeaders.Authorization, "Bearer $token") }.status)
        val logout = client.post("/api/session/logout") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, logout.status)
        assertTrue(logout.headers.getAll(HttpHeaders.SetCookie).orEmpty().all { "Max-Age=0" in it })
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/data") { header(HttpHeaders.Authorization, "Bearer $token") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/data") { header(HttpHeaders.Cookie, cookie) }.status)
        // 配置 token 是长期配置凭据，不是可吊销的动态会话。
        assertEquals(HttpStatusCode.OK, client.post("/api/session/logout") { header(HttpHeaders.Authorization, "Bearer configured-token") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/data") { header(HttpHeaders.Authorization, "Bearer configured-token") }.status)
    }

    @Test
    fun `active browser cookie is periodically renewed before its original max age`() = testApplication {
        var now = 0L
        val sessions = SessionStore(ttlMs = 5_000, now = { now })
        application { module(config(), siteSessions = sessions) }
        // 明确的可控时钟 Cookie jar；不声称改变了真实浏览器时钟。
        var cookie = ""
        var cookieExpiresAt = 0L
        fun accept(raw: String?) {
            if (raw == null) return
            cookie = raw.substringBefore(';')
            val maxAge = raw.split(';').first { it.trim().startsWith("Max-Age=") }.trim().substringAfter('=').toLong()
            cookieExpiresAt = now + maxAge * 1_000
        }
        accept(client.post("/api/session/login") { setBody("""{"username":"me","password":"pw"}""") }.headers[HttpHeaders.SetCookie])
        assertEquals(5_000L, cookieExpiresAt)
        now = 3_000
        val health = client.get("/api/health") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.OK, health.status)
        accept(health.headers[HttpHeaders.SetCookie])
        assertTrue(cookieExpiresAt > 5_000, "只滑动服务端 TTL 而不更新浏览器 Cookie 会在原始 Max-Age 掉线")
        // 同一时刻的分片/接口不应每次发 Set-Cookie。
        repeat(10) {
            val response = client.get("/api/data") { header(HttpHeaders.Cookie, cookie) }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.headers.getAll(HttpHeaders.SetCookie).isNullOrEmpty())
        }
        now = 5_500
        assertTrue(now < cookieExpiresAt)
        val active = client.get("/api/data") { if (now < cookieExpiresAt) header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.OK, active.status)
        now = cookieExpiresAt + 5_001
        assertFalse(sessions.isValid(cookie.substringAfter('=')), "真正空闲超时仍必须撤销服务端会话")
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/data").status)
    }
}
