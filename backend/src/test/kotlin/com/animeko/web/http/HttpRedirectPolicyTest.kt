package com.animeko.web.http

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 出站客户端的重定向策略。
 *
 * 这里用两个真 HTTP 服务器，而不是打桩：要证明的是「这一跳根本没发出去」，
 * 打桩只能证明参数传对了，证明不了 JDK 客户端真的没跟。
 *
 * 为什么要紧：Bangumi 镜像是运营者填进来的第三方，但它不该能决定这台服务器
 * 去向内网发请求。代理链路与抓取链路都是**逐跳**过 UrlGuard 的
 * （见 SelectorEngine / SubscriptionService 的重定向循环），而元数据链路
 * 没有那道检查 —— 一旦自动跟随跳转，`http://169.254.169.254` 就在射程内了。
 */
class HttpRedirectPolicyTest {
    private class Pair : AutoCloseable {
        val hits = AtomicInteger()
        val redirectPort: Int
        val targetPort: Int
        private val a = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val b = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        init {
            redirectPort = a.address.port
            targetPort = b.address.port
            a.createContext("/start") { ex ->
                val location = "http://127.0.0.1:$targetPort/trap"
                ex.responseHeaders.add("Location", location)
                ex.sendResponseHeaders(302, -1)
                ex.close()
            }
            b.createContext("/trap") { ex ->
                hits.incrementAndGet()
                val body = "internal-secret".toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            b.createContext("/big") { ex ->
                val chunk = ByteArray(64 * 1024) { 'x'.code.toByte() }
                try {
                    ex.sendResponseHeaders(200, (chunk.size * 4).toLong())
                    ex.responseBody.use { out -> repeat(4) { out.write(chunk) } }
                } catch (_: IOException) {
                    // 客户端在达到上限后主动断开，服务端写失败是预期行为
                }
                ex.close()
            }
            b.createContext("/slow") { ex ->
                try {
                    ex.sendResponseHeaders(200, 10)
                    Thread.sleep(1_000)
                    ex.responseBody.use { it.write("0123456789".toByteArray()) }
                } catch (_: Exception) {
                    // 客户端在 body 超时后关闭了连接
                }
                ex.close()
            }
            a.start(); b.start()
        }

        val start = "http://127.0.0.1:$redirectPort/start"
        override fun close() { a.stop(0); b.stop(0) }
    }

    @Test
    fun `post never follows a redirect to another host`() = kotlinx.coroutines.runBlocking {
        Pair().use { p ->
            val resp = JdkFetcher(timeoutMs = 3000).post(p.start, """{"keyword":"x"}""")
            assertEquals(302, resp.status, "POST 应当把跳转如实交回调用方")
            assertEquals(0, p.hits.get(), "POST 跟随了重定向：内网地址被真实请求了")
        }
    }

    @Test
    fun `fetch with followRedirects=false does not touch the redirect target`() =
        kotlinx.coroutines.runBlocking {
            Pair().use { p ->
                val resp = JdkFetcher(timeoutMs = 3000).fetch(p.start, followRedirects = false)
                assertEquals(302, resp.status)
                assertEquals(0, p.hits.get())
            }
        }

    /**
     * 反向对照：默认值仍然会跟。这条不是「测试通过所以安全」的装饰 ——
     * 它说明 `followRedirects` 的默认值是危险的，元数据调用方必须显式关掉。
     *
     * 但别把话说过头（原先这里的注释写着「哪天有人把 BangumiClient 里的
     * followRedirects = false 删了，上一条用例就会失败」——不会）：
     * 上面那条用例直接调 `JdkFetcher.fetch(..., followRedirects = false)`，
     * 它验的是**这个类**在拿到 false 时的行为，跟 BangumiClient **怎么调它**没有关系。
     * 调用点被改掉是由 BangumiClientTest 里那组 redirect 用例看着的，
     * 而代理那条调用点（ApiRoutes 的 fetchStream）现在由 ProxyRouteTest 的
     * `same host redirect keeps cookie...` 里那句「每一跳都必须自己发起」看着。
     */
    @Test
    fun `https to http downgrade is treated as a credential scope change`() {
        assertTrue(UrlGuard.isInsecureDowngrade("https://a.example/x", "http://a.example/y"))
        assertTrue(UrlGuard.isInsecureDowngrade("https://a.example/x", "http://a.example:8080/y"))
        assertFalse(UrlGuard.isInsecureDowngrade("http://a.example/x", "https://a.example/y"))
        assertFalse(UrlGuard.isInsecureDowngrade("https://a.example/x", "https://b.example/y"))
        assertFalse(UrlGuard.isInsecureDowngrade("https://a.example/x", "https://a.example/y"))
    }

    @Test
    fun `fetchStream defaults to not following redirects`() = kotlinx.coroutines.runBlocking {
        Pair().use { p ->
            val resp = JdkFetcher(timeoutMs = 3000).fetchStream(p.start)
            try {
                assertEquals(302, resp.status, "视频代理的流式请求必须把跳转交回调用方逐跳校验")
                assertEquals(0, p.hits.get(), "fetchStream 自动跟随了重定向")
            } finally {
                resp.body.close()
            }
        }
    }

    @Test
    fun `post body read has a timeout after headers arrive`() = kotlinx.coroutines.runBlocking {
        Pair().use { p ->
            val e = assertFailsWith<UpstreamException> {
                JdkFetcher(timeoutMs = 3000, streamTimeoutMs = 200)
                    .post("http://127.0.0.1:${p.targetPort}/slow", "{}")
            }
            assertTrue(e.message.orEmpty().contains("超时"), e.message.orEmpty())
        }
    }

    @Test
    fun `fetchBounded aborts as soon as the byte cap is exceeded`() = kotlinx.coroutines.runBlocking {
        Pair().use { p ->
            val e = assertFailsWith<UpstreamException> {
                JdkFetcher(timeoutMs = 3000).fetchBounded(
                    "http://127.0.0.1:${p.targetPort}/big",
                    maxBytes = 1024,
                )
            }
            assertTrue(e.message.orEmpty().contains("上限"), e.message.orEmpty())
        }
    }

    @Test
    fun `default fetch does follow, so call sites must opt out`() = kotlinx.coroutines.runBlocking {
        Pair().use { p ->
            val resp = JdkFetcher(timeoutMs = 3000).fetch(p.start)
            assertEquals(200, resp.status)
            assertEquals(1, p.hits.get(), "对照组：默认行为确实是自动跟随")
        }
    }
}
