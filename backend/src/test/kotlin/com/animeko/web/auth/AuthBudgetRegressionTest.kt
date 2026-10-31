package com.animeko.web.auth

import com.animeko.web.config.AppConfig
import com.animeko.web.module
import com.animeko.web.testDirectory
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Counter proofs supplement the original route-level RED, without substituting status-only assertions. */
class AuthBudgetRegressionTest {
    private fun config() = AppConfig(
        authEnabled = true, authUser = "me", authPassword = "pw", authToken = "configured-token",
        dataDir = testDirectory("animeko-budget"), frontendDir = "missing", trustedProxyHops = 1,
        subscriptionAutoImport = emptyList(), seedSubscriptionUrl = null,
    )
    private class CountingVerifier(cfg: AppConfig) : CredentialVerifier {
        private val real = ConfiguredCredentialVerifier(cfg)
        val calls = AtomicInteger()
        override fun password(user: String, pass: String): Boolean { calls.incrementAndGet(); return real.password(user, pass) }
        override fun bearer(token: String): Boolean { calls.incrementAndGet(); return real.bearer(token) }
    }
    private fun basic(pass: String) = "Basic " + Base64.getEncoder().encodeToString("me:$pass".toByteArray())

    @Test
    fun `blocked login candidates never reach the password validator and expiry restores a permit`() = testApplication {
        var now = 0L
        val cfg = config()
        val verifier = CountingVerifier(cfg)
        val limiter = LoginRateLimiter(3, 1_000, now = { now })
        application { module(cfg, loginLimiter = limiter, credentialVerifier = verifier) }
        suspend fun login(pass: String) = client.post("/api/session/login") {
            header(HttpHeaders.XForwardedFor, "203.0.113.7")
            setBody("""{"username":"me","password":"$pass"}""")
        }
        repeat(3) { assertEquals(HttpStatusCode.Unauthorized, login("wrong-$it").status) }
        assertEquals(3, verifier.calls.get())
        for (pass in listOf("wrong", "pw", "another", "configured-token")) {
            val response = login(pass)
            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertTrue(response.headers.getAll(HttpHeaders.SetCookie).isNullOrEmpty())
        }
        assertEquals(3, verifier.calls.get(), "exhausted candidates were still compared")
        assertEquals(3, limiter.attempts("203.0.113.7"))
        now = 1_000
        assertEquals(HttpStatusCode.OK, login("pw").status)
        assertEquals(4, verifier.calls.get())
        assertEquals(1, limiter.attempts("203.0.113.7"), "success consumes a permit and cannot reset the bucket")
    }

    @Test
    fun `Basic and configured Bearer share a budget while verified random sessions bypass comparison`() = testApplication {
        val cfg = config()
        val verifier = CountingVerifier(cfg)
        val sessions = SessionStore()
        val existing = sessions.create()
        val limiter = LoginRateLimiter(maxAttempts = 2)
        application { module(cfg, siteSessions = sessions, loginLimiter = limiter, credentialVerifier = verifier) }
        suspend fun request(auth: String) = client.get("/api/data") {
            header(HttpHeaders.XForwardedFor, "203.0.113.7")
            header(HttpHeaders.Authorization, auth)
        }
        assertEquals(HttpStatusCode.Unauthorized, request(basic("wrong")).status)
        assertEquals(HttpStatusCode.Unauthorized, request("Bearer wrong").status)
        assertEquals(2, verifier.calls.get())
        for (header in listOf(basic("pw"), basic("wrong"), "Bearer configured-token", "Bearer wrong")) {
            assertEquals(HttpStatusCode.TooManyRequests, request(header).status)
        }
        assertEquals(2, verifier.calls.get(), "headers retained a secret-comparison oracle")
        assertEquals(HttpStatusCode.OK, request("Bearer $existing").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/data") {
            header(HttpHeaders.XForwardedFor, "203.0.113.7")
            header(HttpHeaders.Cookie, "animeko_session=$existing")
        }.status)
        assertEquals(2, verifier.calls.get(), "a verified random session should never verify configured credentials")
        for ((name, value) in listOf(HttpHeaders.Cookie to "animeko_session=$existing", HttpHeaders.Authorization to "Bearer $existing")) {
            assertEquals(HttpStatusCode.OK, client.post("/api/session/login") {
                header(HttpHeaders.XForwardedFor, "203.0.113.7")
                header(name, value)
                setBody("""{"username":"me","password":"wrong"}""")
            }.status)
        }
        assertEquals(2, verifier.calls.get())
    }

    @Test
    fun `concurrent login candidates cannot obtain more than the atomic permit budget`() = testApplication {
        val cfg = config()
        val verifier = CountingVerifier(cfg)
        val limiter = LoginRateLimiter(maxAttempts = 8)
        application { module(cfg, loginLimiter = limiter, credentialVerifier = verifier) }
        client.get("/api/health") // initialize the actual application before starting the concurrent candidates
        val statuses = coroutineScope {
            List(64) { i -> async(Dispatchers.Default) {
                client.post("/api/session/login") {
                    header(HttpHeaders.XForwardedFor, "203.0.113.7")
                    setBody("""{"username":"me","password":"wrong-$i"}""")
                }.status
            } }.awaitAll()
        }
        assertEquals(8, statuses.count { it == HttpStatusCode.Unauthorized })
        assertEquals(statuses.size - 8, statuses.count { it == HttpStatusCode.TooManyRequests })
        assertEquals(8, verifier.calls.get(), "parallel candidates exceeded the actual comparison budget")
        assertEquals(8, limiter.attempts("203.0.113.7"))
    }

    @Test
    fun `atomic permits and periodic cookie renewals have a single concurrent winner`() {
        val executor = Executors.newFixedThreadPool(16)
        try {
            val limiter = LoginRateLimiter(maxAttempts = 8, now = { 0L })
            val start = CountDownLatch(1)
            val attempts = List(128) { executor.submit<Boolean> { start.await(); limiter.tryAcquire("same-source") } }
            start.countDown()
            assertEquals(8, attempts.count { it.get(3, TimeUnit.SECONDS) })
            assertEquals(8, limiter.attempts("same-source"))
            var now = 0L
            val sessions = SessionStore(ttlMs = 5_000, now = { now })
            val token = sessions.create()
            now = 3_000
            assertTrue(sessions.isValid(token))
            val renewals = List(128) { executor.submit<Boolean> { sessions.claimCookieRenewal(token) } }
            assertEquals(1, renewals.count { it.get(3, TimeUnit.SECONDS) }, "each fragment concurrently renewed the same cookie")
        } finally { executor.shutdownNow() }
    }

    @Test
    fun `active saturated buckets are not evicted to reopen an exhausted source`() {
        val limiter = LoginRateLimiter(maxAttempts = 2, now = { 0L })
        assertTrue(limiter.tryAcquire("victim"))
        assertTrue(limiter.tryAcquire("victim"))
        repeat(4_200) { limiter.tryAcquire("new-$it") }
        assertTrue(limiter.trackedClients <= 4_096)
        assertFalse(limiter.tryAcquire("victim"), "IP churn reopened a still-active credential bucket")
        assertEquals(2, limiter.attempts("victim"))
    }
}
