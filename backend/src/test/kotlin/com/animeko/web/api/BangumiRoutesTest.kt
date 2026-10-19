package com.animeko.web.api

import com.animeko.web.bangumi.BangumiStubFetcher
import com.animeko.web.config.AppConfig
import com.animeko.web.module
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bangumi 三个新端点的**路由级**测试。
 *
 * BangumiChannelTest 验的是客户端逻辑（选哪个地址、怎么探测），但「HTTP 状态码
 * 对不对」只有走到路由才看得见：StatusPages 把 IllegalArgumentException 映成 400、
 * UpstreamException 映成 502、SubjectNotFoundException 映成 404 —— 这些映射写错时
 * 客户端单测全绿，前端却会收到一个含义完全不同的状态码。
 */
class BangumiRoutesTest {

    private fun config() = AppConfig(
        dataDir = Files.createTempDirectory("animeko-bgm-routes"),
        frontendDir = "definitely-not-exist",
    )

    private val rankingFixture = """
        {"total":1,"data":[{"id":326,"name":"攻殻機動隊","name_cn":"攻壳机动队","summary":"",
        "rating":{"rank":1,"score":9.2},"images":{"large":"https://img.example/1.jpg"}}],
        "limit":24,"offset":0}
    """.trimIndent()

    private val calendarFixture = """
        [{"weekday":{"id":1,"en":"Mon","cn":"周一"},"items":[
        {"id":101,"name":"Test","name_cn":"测试番剧","eps":12,"rating":{"score":8.1}}]}]
    """.trimIndent()

    @Test
    fun `ranking returns paged subjects`() = testApplication {
        val stub = BangumiStubFetcher(
            getRoutes = mapOf(
                "https://api.bgm.tv/v0/subjects?type=2&sort=rank&limit=24&offset=0" to rankingFixture,
            ),
        )
        application { module(config(), stub) }
        val resp = client.get("/api/bangumi/ranking")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        assertTrue(body.contains("攻壳机动队"), body)
        assertTrue(body.contains("\"total\":1"), body)
    }

    @Test
    fun `ranking clamps limit and offset`() = testApplication {
        // 越界的 limit/offset 必须被夹紧而不是原样传给上游（否则等于让人翻一万页）
        val stub = BangumiStubFetcher(
            getRoutes = mapOf(
                "https://api.bgm.tv/v0/subjects?type=2&sort=rank&limit=50&offset=5000" to rankingFixture,
            ),
        )
        application { module(config(), stub) }
        val resp = client.get("/api/bangumi/ranking?limit=9999&offset=999999")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(stub.requests.any { it.contains("limit=50") && it.contains("offset=5000") }, "${stub.requests}")
    }

    @Test
    fun `calendar parses weekday groups`() = testApplication {
        val stub = BangumiStubFetcher(
            getRoutes = mapOf("https://api.bgm.tv/v0/calendar" to calendarFixture),
        )
        application { module(config(), stub) }
        val resp = client.get("/api/bangumi/calendar")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        assertTrue(body.contains("测试番剧"), body)
        assertTrue(body.contains("周一"), body)
    }

    @Test
    fun `calendar maps upstream 404 to 502`() = testApplication {
        // 先切到「仅官方」，让候选唯一 —— AUTO 会并发两个地址，
        // 谁先失败不确定，状态码会飘
        val stub = BangumiStubFetcher(notFoundPrefixes = setOf("https://api.bgm.tv/v0/calendar"))
        application { module(config(), stub) }
        val put = client.put("/api/bangumi/channel") {
            contentType(ContentType.Application.Json)
            setBody("""{"channel":"OFFICIAL"}""")
        }
        assertEquals(HttpStatusCode.OK, put.status)

        val resp = client.get("/api/bangumi/calendar")
        // 不是 404：这里没有「条目不存在」这回事，是上游缺端点
        assertEquals(HttpStatusCode.BadGateway, resp.status)
        assertTrue(resp.bodyAsText().contains("每日放送"), resp.bodyAsText())
    }

    @Test
    fun `channel rejects internal address with 400`() = testApplication {
        application { module(config(), BangumiStubFetcher()) }
        val resp = client.put("/api/bangumi/channel") {
            contentType(ContentType.Application.Json)
            setBody("""{"channel":"CUSTOM","customUrls":["http://127.0.0.1:9000"]}""")
        }
        // 走 StatusPages 的 IllegalArgumentException 分支：配置值非法是 400，
        // 而 UrlGuard.Blocked 的 403 语义是「你要访问的目标被拒绝」，两者不能混
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        val body = resp.bodyAsText()
        assertTrue(body.contains("不被允许"), body)
        // 回显用户自己提交的地址没问题（他本来就知道），
        // 但绝不能把 Blocked 的原文带出来 —— 那是「主机名 -> 解析出的内网 IP」映射，
        // 对任何能打开设置页的人都是网络拓扑泄露
        assertTrue(!body.contains("->"), "错误信息不应包含地址映射：$body")
        assertTrue(!body.contains("已拒绝"), "不应透出 UrlGuard 的原文：$body")
    }

    @Test
    fun `channel switch takes effect immediately`() = testApplication {
        application { module(config(), BangumiStubFetcher()) }
        val put = client.put("/api/bangumi/channel") {
            contentType(ContentType.Application.Json)
            setBody("""{"channel":"MIRROR"}""")
        }
        assertEquals(HttpStatusCode.OK, put.status)

        val body = client.get("/api/bangumi/channel").bodyAsText()
        assertTrue(body.contains("\"MIRROR\""), body)
        assertTrue(body.contains("bgmapi.anibt.net"), body)
        // 镜像没有每日放送：这个标志要让前端能提前把区块收起来
        assertTrue(body.contains("\"calendarSupported\":false"), body)
    }
}
