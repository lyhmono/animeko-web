package com.animeko.web.bangumi

import com.animeko.web.http.Fetcher
import com.animeko.web.http.HttpResult
import com.animeko.web.http.StreamResult
import com.animeko.web.http.UpstreamException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 可按 URL 前缀返回不同响应的打桩 Fetcher（支持 POST） */
class BangumiStubFetcher(
    private val getRoutes: Map<String, String> = emptyMap(),
    private val postRoutes: Map<String, String> = emptyMap(),
    private val failingGetPrefixes: Set<String> = emptySet(),
    private val notFoundPrefixes: Set<String> = emptySet(),
) : Fetcher {
    val requests = mutableListOf<String>()

    override suspend fun fetch(
        url: String,
        headers: Map<String, String>,
        followRedirects: Boolean,
    ): HttpResult {
        requests.add("GET $url")
        if (failingGetPrefixes.any { url.startsWith(it) }) throw IllegalStateException("boom")
        if (notFoundPrefixes.any { url.startsWith(it) }) {
            return HttpResult(404, mapOf("content-type" to listOf("text/plain")), "not found".toByteArray())
        }
        val body = getRoutes[url]
            ?: getRoutes.entries.firstOrNull { url.startsWith(it.key) }?.value
            ?: throw IllegalStateException("no fixture for $url")
        return HttpResult(200, mapOf("content-type" to listOf("application/json")), body.toByteArray())
    }

    override suspend fun post(
        url: String,
        body: String,
        headers: Map<String, String>,
        contentType: String,
    ): HttpResult {
        requests.add("POST $url $body")
        val respBody = postRoutes[url]
            ?: postRoutes.entries.firstOrNull { url.startsWith(it.key) }?.value
            ?: throw IllegalStateException("no fixture for $url")
        return HttpResult(200, mapOf("content-type" to listOf("application/json")), respBody.toByteArray())
    }

    override suspend fun fetchStream(
        url: String,
        headers: Map<String, String>,
        followRedirects: Boolean,
    ): StreamResult = throw UnsupportedOperationException()
}

class BangumiClientTest {

    private val searchResponse = """
        {"data":[{"id":328609,"name":"ぼっち・ざ・ろっく！","name_cn":"孤独摇滚！","date":"2022-10-07",
        "platform":"TV","images":{"large":"https://img.example/l/xx.jpg"},
        "rating":{"rank":73,"total":41242,"score":8.4},"eps":12,"type":2}],
        "total":1,"limit":24,"offset":0}
    """.trimIndent()

    private val subjectResponse = """
        {"id":328609,"name":"ぼっち・ざ・ろっく！","name_cn":"孤独摇滚！","summary":"后藤独...",
        "date":"2022-10-07","platform":"TV","rating":{"rank":73,"score":8.4},
        "images":{"large":"https://img.example/l/xx.jpg"},"eps":12,"type":2}
    """.trimIndent()

    private val episodesPage1 = """
        {"data":[
          {"id":1128641,"name":"転がるぼっち","name_cn":"翻转孤独","sort":1,"duration":"00:23:40","airdate":"2022-10-08"},
          {"id":1128642,"name":"","name_cn":"","sort":2,"airdate":"2022-10-15"}
        ],"total":4,"limit":200,"offset":0}
    """.trimIndent()

    private val episodesPage2 = """
        {"data":[
          {"id":1128643,"name":"音楽になりたい","name_cn":"想去成为音乐","sort":3,"airdate":"2022-10-22"},
          {"id":1128644,"name":"転がるぼっち","name_cn":"滚动的孤独","sort":4,"airdate":"2022-10-29"}
        ],"total":4,"limit":200,"offset":2}
    """.trimIndent()

    @Test
    fun `search parses v0 response with type filter`() = runTest {
        val fetcher = BangumiStubFetcher(postRoutes = mapOf("https://api.bgm.tv/v0/search/subjects" to searchResponse))
        val client = BangumiClient(fetcher, listOf("https://api.bgm.tv"))
        val results = client.search("孤独摇滚")
        assertEquals(1, results.size)
        assertEquals(328609, results.first().id)
        assertEquals("孤独摇滚！", results.first().name_cn)
        assertEquals(8.4, results.first().rating?.score)
        // 请求体包含关键词与动画类型过滤
        assertTrue(fetcher.requests.single().contains(""""keyword":"孤独摇滚""""))
        assertTrue(fetcher.requests.single().contains(""""type":[2]"""))
    }

    @Test
    fun `subject pages through episodes past the single page limit`() = runTest {
        val fetcher = BangumiStubFetcher(
            getRoutes = mapOf(
                "https://api.bgm.tv/v0/subjects/328609" to subjectResponse,
                "https://api.bgm.tv/v0/episodes?subject_id=328609&limit=200&offset=0" to episodesPage1,
                "https://api.bgm.tv/v0/episodes?subject_id=328609&limit=200&offset=2" to episodesPage2,
            ),
        )
        val client = BangumiClient(fetcher, listOf("https://api.bgm.tv"))
        val subject = client.subject(328609)
        assertEquals("孤独摇滚！", subject.name_cn)
        val eps = subject.eps as kotlinx.serialization.json.JsonArray
        // total=4 而单页只有 2 条：旧实现固定单次请求，长篇番剧第 200 集之后会整段丢失
        assertEquals(4, eps.size)
        assertEquals(2, fetcher.requests.count { it.contains("/v0/episodes") })
    }

    @Test
    fun `upstream 404 surfaces as subject-not-found`() = runTest {
        val fetcher = BangumiStubFetcher(
            getRoutes = mapOf("https://api.bgm.tv/v0/episodes" to episodesPage1),
            notFoundPrefixes = setOf("https://api.bgm.tv/v0/subjects"),
        )
        val client = BangumiClient(fetcher, listOf("https://api.bgm.tv"))
        assertFailsWith<SubjectNotFoundException> { client.subject(999999) }
    }

    @Test
    fun `connection failure surfaces as upstream error rather than a bare 500`() = runTest {
        val fetcher = BangumiStubFetcher(
            getRoutes = mapOf("https://api.bgm.tv/v0/subjects" to subjectResponse),
            failingGetPrefixes = setOf("https://api.bgm.tv/v0/subjects"),
        )
        val client = BangumiClient(fetcher, listOf("https://api.bgm.tv"))
        assertFailsWith<UpstreamException> { client.subject(328609) }
    }

    @Test
    fun `score and rank from the top level are kept`() = runTest {
        val withScore = """{"data":[{"id":1,"name":"x","name_cn":"y","score":8.4,"rank":73}],"total":1}"""
        val fetcher = BangumiStubFetcher(postRoutes = mapOf("https://api.bgm.tv/v0/search/subjects" to withScore))
        val client = BangumiClient(fetcher, listOf("https://api.bgm.tv"))
        val hit = client.search("x").single()
        // 这两个字段以前没落到 DTO 上，被 ignoreUnknownKeys 直接丢掉，
        // 前端 rating?.score ?? score 的兜底因此恒为空
        assertEquals(8.4, hit.score)
        assertEquals(73, hit.rank)
    }

    @Test
    fun `falls back to mirror when primary fails`() = runTest {
        val fetcher = BangumiStubFetcher(
            postRoutes = mapOf("https://bgmapi.anibt.net/v0/search/subjects" to searchResponse),
            failingGetPrefixes = setOf("https://api.bgm.tv"),
        )
        val client = BangumiClient(
            fetcher,
            listOf("https://api.bgm.tv", "https://bgmapi.anibt.net"),
        )
        val results = client.search("孤独摇滚")
        assertEquals(1, results.size)
        // 第一次请求官方失败后回退镜像
        assertTrue(fetcher.requests.any { it.startsWith("POST https://api.bgm.tv") })
        assertTrue(fetcher.requests.any { it.startsWith("POST https://bgmapi.anibt.net") })
    }

    @Test
    fun `second search prefers working base`() = runTest {
        val fetcher = BangumiStubFetcher(
            postRoutes = mapOf("https://bgmapi.anibt.net/v0/search/subjects" to searchResponse),
            failingGetPrefixes = setOf("https://api.bgm.tv"),
        )
        val client = BangumiClient(fetcher, listOf("https://api.bgm.tv", "https://bgmapi.anibt.net"))
        client.search("第一次")
        client.search("第二次")
        // 第二次不再请求已失败的官方地址
        val officialCalls = fetcher.requests.count { it.startsWith("POST https://api.bgm.tv") }
        assertEquals(1, officialCalls)
    }
}
