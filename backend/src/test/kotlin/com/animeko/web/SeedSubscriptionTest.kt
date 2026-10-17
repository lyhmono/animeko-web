package com.animeko.web

import com.animeko.web.config.AppConfig
import com.animeko.web.http.Fetcher
import com.animeko.web.http.HttpResult
import com.animeko.web.http.StreamResult
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 开机导入默认订阅的接线（module() 这一层）。
 *
 * 这里曾经测的是另一套机制：「仓库为空时同步阻塞启动拉一次，成功就不种示例源」。
 * 现在统一成「示例源先落地，订阅在后台协程里异步导入」，所以断言跟着改：
 * 示例源**始终**在，订阅源是**追加**进来的，而订阅挂了不影响启动。
 * 异步意味着必须轮询等待，不能假设 application {} 返回时已经导完。
 */
class SeedSubscriptionTest {

    private fun config(
        dataDir: java.nio.file.Path,
        seedUrl: String? = null,
        autoImport: String = "",
    ) = AppConfig(
        dataDir = dataDir,
        frontendDir = "definitely-not-exist",
        seedSubscriptionUrl = seedUrl,
        subscriptionAutoImport = autoImport.split(',').map { it.trim() }.filter { it.isNotBlank() },
    )

    /** 聚合订阅正文（animeko 导出格式） */
    private fun subscriptionBody(vararg names: String): String = """
        {"exportedMediaSourceDataList":{"mediaSources":[
        ${names.joinToString(",") { n ->
            """{"factoryId":"web-selector","version":2,"arguments":{"name":"$n","searchConfig":{"searchUrl":"https://a.test/s?wd={keyword}","rawBaseUrl":"https://a.test"}}}"""
        }}
        ]}}
    """.trimIndent()

    /** 只为 fetchBounded 供数：订阅拉取走的是这条路径 */
    private class StubFetcher(private val body: String, private val status: Int = 200) : Fetcher {
        var fetchCount = 0

        override suspend fun fetch(
            url: String,
            headers: Map<String, String>,
            followRedirects: Boolean,
        ): HttpResult = throw UnsupportedOperationException("订阅拉取只应走 fetchBounded")

        override suspend fun fetchBounded(
            url: String,
            headers: Map<String, String>,
            maxBytes: Long,
        ): HttpResult {
            fetchCount++
            return HttpResult(status, emptyMap(), body.toByteArray())
        }

        override suspend fun post(
            url: String,
            body: String,
            headers: Map<String, String>,
            contentType: String,
        ): HttpResult = throw UnsupportedOperationException()

        override suspend fun fetchStream(
            url: String,
            headers: Map<String, String>,
            followRedirects: Boolean,
        ): StreamResult = throw UnsupportedOperationException()
    }

    /** 轮询到订阅源出现为止；异步导入不等 application {} 返回就完成 */
    private suspend fun awaitSource(client: io.ktor.client.HttpClient, name: String, tries: Int = 60): String {
        var last = ""
        repeat(tries) {
            last = client.get("/api/selector/sources").bodyAsText()
            if (last.contains(name)) return last
            delay(250)
        }
        return last
    }

    @Test
    fun `SEED_SUBSCRIPTION_URL alias still imports on boot`() = testApplication {
        // 别名不能只是「不报错」：老部署写上它仍然要真的把源导进来
        val dataDir = Files.createTempDirectory("animeko-seed-alias")
        val stub = StubFetcher(subscriptionBody("订阅源甲", "订阅源乙"))
        application {
            module(config(dataDir, seedUrl = "https://sub.test/css.json"), fetcherOverride = stub)
        }
        val names = awaitSource(client, "订阅源甲")
        assertTrue(names.contains("订阅源甲"), names)
        assertTrue(names.contains("订阅源乙"), names)
        // 示例源不再被订阅「取代」：它先落地，订阅是追加
        assertTrue(names.contains("示例站点"), "示例源应当仍在列表里：$names")
        assertTrue(stub.fetchCount >= 1, "订阅地址应被拉取过")
    }

    @Test
    fun `a dead subscription does not break startup or the source list`() = testApplication {
        val dataDir = Files.createTempDirectory("animeko-seed-fail")
        application {
            module(config(dataDir, seedUrl = "https://sub.test/down.json"), fetcherOverride = StubFetcher("bad gateway", status = 502))
        }
        val resp = client.get("/api/selector/sources")
        assertEquals(HttpStatusCode.OK, resp.status, "订阅站挂了不能把服务一起带走")
        assertTrue(resp.bodyAsText().contains("示例站点-CSS"), resp.bodyAsText())
    }

    @Test
    fun `no subscription configured means no outbound request at all`() = testApplication {
        val dataDir = Files.createTempDirectory("animeko-seed-none")
        val stub = StubFetcher(subscriptionBody("不该出现的源"))
        application { module(config(dataDir), fetcherOverride = stub) }
        val resp = client.get("/api/selector/sources")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("示例站点-CSS"), resp.bodyAsText())
        assertEquals(0, stub.fetchCount, "没配订阅就不该在启动时打外网")
    }

    @Test
    fun `tier whitelist limits what boot imports`() = testApplication {
        val dataDir = Files.createTempDirectory("animeko-seed-tier")
        val body = """
            {"exportedMediaSourceDataList":{"mediaSources":[
              {"factoryId":"web-selector","version":2,"arguments":{"tier":0,"name":"零级源","searchConfig":{"searchUrl":"https://a.test/s?wd={keyword}"}}},
              {"factoryId":"web-selector","version":2,"arguments":{"tier":4,"name":"四级源","searchConfig":{"searchUrl":"https://b.test/s?wd={keyword}"}}}
            ]}}
        """.trimIndent()
        application {
            module(
                config(dataDir, autoImport = "https://sub.test/css.json")
                    .copy(subscriptionAutoTiers = setOf("0")),
                fetcherOverride = StubFetcher(body),
            )
        }
        val names = awaitSource(client, "零级源")
        assertTrue(names.contains("零级源"), names)
        assertTrue(!names.contains("四级源"), "tier 白名单外的源不该被导入：$names")
    }
}
