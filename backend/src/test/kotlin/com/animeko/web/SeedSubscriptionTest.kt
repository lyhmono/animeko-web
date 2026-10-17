package com.animeko.web

import com.animeko.web.config.AppConfig
import com.animeko.web.http.Fetcher
import com.animeko.web.http.HttpResult
import com.animeko.web.http.StreamResult
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 首次启动的默认订阅预置：配了 seedSubscriptionUrl 且仓库为空时，
 * module() 要拉订阅并导入；拉取失败时回退内置示例源而不是抛异常挂住启动。
 */
class SeedSubscriptionTest {

    private fun config(dataDir: java.nio.file.Path, seedUrl: String?) = AppConfig(
        dataDir = dataDir,
        frontendDir = "definitely-not-exist",
        seedSubscriptionUrl = seedUrl,
    )

    /** 聚合订阅正文（animeko 导出格式） */
    private fun subscriptionBody(vararg names: String): String = """
        {"exportedMediaSourceDataList":{"mediaSources":[
        ${names.joinToString(",") { n ->
            """{"factoryId":"web-selector","version":2,"arguments":{"name":"$n","searchConfig":{"searchUrl":"https://a.test/s?wd={keyword}","rawBaseUrl":"https://a.test"}}}"""
        }}
        ]}}
    """.trimIndent()

    /** 只为 fetchBounded 供数：subscribe 拉取走的是这条路径 */
    private class StubFetcher(private val body: String) : Fetcher {
        var fetchCount = 0

        override suspend fun fetch(
            url: String,
            headers: Map<String, String>,
            followRedirects: Boolean,
        ): HttpResult = throw UnsupportedOperationException("seed 路径只应走 fetchBounded")

        override suspend fun fetchBounded(
            url: String,
            headers: Map<String, String>,
            maxBytes: Long,
        ): HttpResult {
            fetchCount++
            return HttpResult(200, emptyMap(), body.toByteArray())
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

    @Test
    fun `seed subscription imports sources into an empty repository`() = testApplication {
        val dataDir = Files.createTempDirectory("animeko-seed-ok")
        val body = subscriptionBody("订阅源甲", "订阅源乙")
        application { module(config(dataDir, "https://sub.test/css.json"), fetcherOverride = StubFetcher(body)) }
        val resp = client.get("/api/selector/sources")
        assertEquals(HttpStatusCode.OK, resp.status)
        val names = resp.bodyAsText()
        assertTrue(names.contains("订阅源甲"), names)
        assertTrue(names.contains("订阅源乙"), names)
        // 示例源不应再种进去：订阅已经供上源了
        assertTrue(!names.contains("示例站点"), names)
        // 仓库里的文件数与订阅条目一致
        val files = dataDir.resolve("selectors").toFile().listFiles() ?: emptyArray()
        assertEquals(2, files.size, files.joinToString { it.name })
    }

    @Test
    fun `failed seed subscription falls back to built-in examples`() = testApplication {
        val dataDir = Files.createTempDirectory("animeko-seed-fail")
        // 订阅站挂了的形状：上游 502
        val stub = object : Fetcher {
            override suspend fun fetch(
                url: String,
                headers: Map<String, String>,
                followRedirects: Boolean,
            ): HttpResult = HttpResult(502, emptyMap(), "bad gateway".toByteArray())

            override suspend fun fetchBounded(
                url: String,
                headers: Map<String, String>,
                maxBytes: Long,
            ): HttpResult = HttpResult(502, emptyMap(), "bad gateway".toByteArray())

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
        application { module(config(dataDir, "https://sub.test/down.json"), fetcherOverride = stub) }
        // 启动没被订阅绑架，且示例源兜底种上了
        val resp = client.get("/api/selector/sources")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("示例站点-CSS"), resp.bodyAsText())
    }

    @Test
    fun `no seed subscription configured keeps the old behavior`() = testApplication {
        val dataDir = Files.createTempDirectory("animeko-seed-none")
        application { module(config(dataDir, null)) }
        val resp = client.get("/api/selector/sources")
        assertEquals(HttpStatusCode.OK, resp.status)
        // 老行为：只种内置示例源
        assertTrue(resp.bodyAsText().contains("示例站点-CSS"), resp.bodyAsText())
    }
}
