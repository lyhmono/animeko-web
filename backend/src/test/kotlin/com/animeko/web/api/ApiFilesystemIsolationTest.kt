package com.animeko.web.api

import com.animeko.web.auth.LoginRateLimiter
import com.animeko.web.auth.SessionStore
import com.animeko.web.bangumi.BangumiClient
import com.animeko.web.config.AppConfig
import com.animeko.web.data.UserDataStore
import com.animeko.web.http.Fetcher
import com.animeko.web.http.HttpResult
import com.animeko.web.http.StreamResult
import com.animeko.web.proxy.ProxySessionStore
import com.animeko.web.selector.SelectorEngine
import com.animeko.web.selector.SelectorRepository
import com.animeko.web.selector.SubscriptionService
import com.animeko.web.testDirectory
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A real Netty server with one call thread must remain schedulable during a held filesystem commit lock. */
class ApiFilesystemIsolationTest {
    private class NoNetwork : Fetcher {
        override suspend fun fetch(url: String, headers: Map<String, String>, followRedirects: Boolean): HttpResult = error("no outbound request expected")
        override suspend fun post(url: String, body: String, headers: Map<String, String>, contentType: String): HttpResult = error("no outbound request expected")
        override suspend fun fetchStream(url: String, headers: Map<String, String>, followRedirects: Boolean): StreamResult = error("no outbound request expected")
    }

    private fun verifyIsolation(write: Boolean) {
        val cfg = AppConfig(authEnabled = false, dataDir = testDirectory("animeko-fs-isolation"), frontendDir = "missing")
        val fetcher = NoNetwork()
        val data = UserDataStore(cfg.userDataFile)
        val initial = data.write("{}")
        val repo = SelectorRepository(cfg.selectorsDir)
        val server = embeddedServer(Netty, environment = applicationEnvironment { }, configure = {
            connector { host = "127.0.0.1"; port = 0 }
            connectionGroupSize = 1; workerGroupSize = 1; callGroupSize = 1; shareWorkGroup = false
        }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
            configureApiRoutes(cfg, BangumiClient(fetcher, listOf("https://metadata.test")), repo, SelectorEngine(fetcher, true),
                fetcher, ProxySessionStore(), SessionStore(), LoginRateLimiter(), data, SubscriptionService(fetcher, repo, true))
        }.start(wait = false)
        val port = runBlocking { server.engine.resolvedConnectors().single().port }
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = thread(name = "fixture-fsync-lock", isDaemon = true) {
            synchronized(data) { held.countDown(); release.await() }
        }
        var healthStatus: Int? = null
        val client = HttpClient.newHttpClient()
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/data")).timeout(Duration.ofSeconds(8))
        val request = if (write) builder.header("If-Match", "\"${initial.etag}\"").PUT(HttpRequest.BodyPublishers.ofString("{\"favorites\":[1]}")) else builder.GET()
        try {
            assertTrue(held.await(3, TimeUnit.SECONDS))
            val dataRequest = client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofByteArray())
            try {
                // Observe the actual blocked UserDataStore call before probing health; no guessed sleep ordering.
                val method = if (write) "write" else "read"
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                var observed = false
                while (!observed && System.nanoTime() < deadline) {
                    observed = Thread.getAllStackTraces().any { (t, frames) ->
                        t.state == Thread.State.BLOCKED && frames.any { it.className == UserDataStore::class.java.name && it.methodName == method }
                    }
                    if (!observed) Thread.sleep(5)
                }
                assertTrue(observed, "the real data operation never reached the held filesystem lock")
                val health = client.sendAsync(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/health")).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
                healthStatus = runCatching { health.get(800, TimeUnit.MILLISECONDS).statusCode() }.getOrNull()
            } finally { release.countDown() }
            assertEquals(200, dataRequest.get(5, TimeUnit.SECONDS).statusCode(), "valid data operation must finish after the fixture releases its lock")
        } finally {
            release.countDown(); holder.join(3_000); server.stop(0, 1_000)
        }
        assertEquals(200, healthStatus, "filesystem ${if (write) "commit" else "read"} occupied the sole Netty call thread")
    }

    @Test
    fun `slow user data read does not block health scheduling`() = verifyIsolation(write = false)

    @Test
    fun `slow atomic conditional write does not block health scheduling`() = verifyIsolation(write = true)
}
