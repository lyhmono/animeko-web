package com.animeko.web.http

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

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
     * 它说明 `followRedirects` 的默认值是危险的，元数据调用方必须显式关掉；
     * 哪天有人把 BangumiClient 里的 `followRedirects = false` 删了，
     * 上一条用例就会失败，而这一条解释了为什么那个参数不能省。
     */
    @Test
    fun `default fetch does follow, so call sites must opt out`() = kotlinx.coroutines.runBlocking {
        Pair().use { p ->
            val resp = JdkFetcher(timeoutMs = 3000).fetch(p.start)
            assertEquals(200, resp.status)
            assertEquals(1, p.hits.get(), "对照组：默认行为确实是自动跟随")
        }
    }
}
