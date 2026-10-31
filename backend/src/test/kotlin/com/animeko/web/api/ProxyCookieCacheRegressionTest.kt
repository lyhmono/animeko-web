package com.animeko.web.api

import com.animeko.web.auth.SessionStore
import com.animeko.web.config.AppConfig
import com.animeko.web.http.Fetcher
import com.animeko.web.http.HttpResult
import com.animeko.web.http.StreamResult
import com.animeko.web.module
import com.animeko.web.testDirectory
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProxyCookieCacheRegressionTest {
    private val image = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    private fun config() = AppConfig(
        authEnabled = true, authUser = "me", authPassword = "pw", allowPrivateTargets = true,
        dataDir = testDirectory("animeko-proxy-cookie-cache"), frontendDir = "missing",
        subscriptionAutoImport = emptyList(), seedSubscriptionUrl = null,
    )
    private fun imageFetcher() = object : Fetcher {
        override suspend fun fetchStream(url: String, headers: Map<String, String>, followRedirects: Boolean) =
            StreamResult(200, mapOf("content-type" to listOf("image/png"), "content-length" to listOf(image.size.toString())), ByteArrayInputStream(image))
        override suspend fun fetch(url: String, headers: Map<String, String>, followRedirects: Boolean): HttpResult = error("unused")
        override suspend fun post(url: String, body: String, headers: Map<String, String>, contentType: String): HttpResult = error("unused")
    }
    private fun HttpResponse.cacheDirectives() = headers.getAll(HttpHeaders.CacheControl).orEmpty()
        .flatMap { it.split(',') }.map { it.trim().lowercase() }.toSet()

    @Test
    fun `image cookie renewal cannot enter a public cache and later images remain cacheable`() = testApplication {
        var now = 0L
        val sessions = SessionStore(ttlMs = 5_000, now = { now })
        application { module(config(), imageFetcher(), siteSessions = sessions) }
        val login = client.post("/api/session/login") { setBody("""{"username":"me","password":"pw"}""") }
        assertEquals(HttpStatusCode.OK, login.status)
        var cookie = assertNotNull(login.headers[HttpHeaders.SetCookie]).substringBefore(';')
        suspend fun imageResponse() = client.get("/api/proxy") {
            parameter("url", "https://media.test/cover.png")
            header(HttpHeaders.Cookie, cookie)
        }
        val before = imageResponse()
        assertEquals(HttpStatusCode.OK, before.status)
        assertContentEquals(image, before.bodyAsBytes())
        assertTrue(before.headers.getAll(HttpHeaders.SetCookie).isNullOrEmpty())
        assertEquals(setOf("public", "max-age=86400"), before.cacheDirectives())

        now = 3_000
        val renewed = imageResponse()
        assertEquals(HttpStatusCode.OK, renewed.status)
        assertContentEquals(image, renewed.bodyAsBytes())
        val renewal = assertNotNull(renewed.headers[HttpHeaders.SetCookie], "must exercise actual renewal, not merely an incoming Cookie")
        assertTrue("Max-Age=5" in renewal)
        assertEquals(cookie, renewal.substringBefore(';'))
        assertTrue("no-store" in renewed.cacheDirectives(), "renewal response must not be stored: ${renewed.cacheDirectives()}")
        assertTrue("private" in renewed.cacheDirectives())
        assertFalse("public" in renewed.cacheDirectives())
        assertFalse(renewed.cacheDirectives().any { it.startsWith("max-age=") })
        cookie = renewal.substringBefore(';')

        val after = imageResponse()
        assertEquals(HttpStatusCode.OK, after.status)
        assertTrue(after.headers.getAll(HttpHeaders.SetCookie).isNullOrEmpty())
        assertEquals(setOf("public", "max-age=86400"), after.cacheDirectives())
        now = 5_500
        val api = client.get("/api/data") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.OK, api.status, "renewal must preserve the active session beyond original Max-Age")
        assertNotNull(api.headers[HttpHeaders.SetCookie])
        assertTrue("no-store" in api.cacheDirectives())
        assertFalse("public" in api.cacheDirectives())
    }
}
