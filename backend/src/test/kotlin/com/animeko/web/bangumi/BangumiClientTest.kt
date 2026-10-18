package com.animeko.web.bangumi

import com.animeko.web.http.Fetcher
import com.animeko.web.http.HttpResult
import com.animeko.web.http.StreamResult
import com.animeko.web.http.UpstreamException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
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
    // 模拟「镜像回了 302，目标是内网元数据地址」
    private val redirectPrefixes: Set<String> = emptySet(),
) : Fetcher {
    val requests = mutableListOf<String>()

    /** 元数据链路必须逐调用关掉自动跳转（这条链路没有 UrlGuard 兜着），记录实际传参 */
    val getFollowFlags = mutableListOf<Boolean>()

    override suspend fun fetch(
        url: String,
        headers: Map<String, String>,
        followRedirects: Boolean,
    ): HttpResult {
        requests.add("GET $url")
        getFollowFlags.add(followRedirects)
        if (redirectPrefixes.any { url.startsWith(it) }) {
            return HttpResult(
                302,
                mapOf("location" to listOf("http://169.254.169.254/latest/meta-data/")),
                ByteArray(0),
            )
        }
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

@OptIn(ExperimentalCoroutinesApi::class)
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

    /**
     * 元数据链路是唯一没有逐跳 UrlGuard 的出站路径（代理与抓取都有），
     * 所以它必须自己拒绝跟随重定向：否则一个运营者配置的社区镜像
     * 就能把这台服务器（VPS 上常常与云元数据地址同网段可达）的请求引过去。
     */
    @Test
    fun `every metadata GET refuses auto-redirects`() = runTest {
        val fetcher = BangumiStubFetcher(
            getRoutes = mapOf(
                "https://api.bgm.tv/v0/subjects/328609" to subjectResponse,
                "https://api.bgm.tv/v0/episodes?subject_id=328609&limit=200&offset=0" to episodesPage1,
                "https://api.bgm.tv/v0/episodes?subject_id=328609&limit=200&offset=2" to episodesPage2,
            ),
        )
        BangumiClient(fetcher, listOf("https://api.bgm.tv")).subject(328609)
        assertTrue(fetcher.getFollowFlags.isNotEmpty(), "打桩没被 GET 到，用例失去意义")
        assertTrue(
            fetcher.getFollowFlags.all { it == false },
            "存在未显式关闭自动跳转的 GET：${fetcher.getFollowFlags}",
        )
    }

    @Test
    fun `a redirect from a base is reported as a base-url problem`() = runTest {
        val fetcher = BangumiStubFetcher(redirectPrefixes = setOf("https://api.bgm.tv/v0/subjects"))
        val client = BangumiClient(fetcher, listOf("https://api.bgm.tv"))
        val e = assertFailsWith<UpstreamException> { client.subject(328609) }
        // 只报「返回 302」会被当成网络抖动；要给出可执行的下一步
        assertTrue(e.message!!.contains("ANIMEKO_BANGUMI_BASE_URLS"), e.message ?: "no message")
        assertEquals(0, fetcher.getFollowFlags.count { it }, "302 之后不该再有第二跳请求")
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

    /** 按地址模拟快慢不同的上游（runTest 的虚拟时钟，delay 不真的等） */
    private class TimingFetcher(
        private val delayByPrefix: Map<String, Long>,
        private val failPrefixes: Set<String> = emptySet(),
        private val body: String,
    ) : Fetcher {
        val requests = mutableListOf<String>()

        override suspend fun fetch(
            url: String,
            headers: Map<String, String>,
            followRedirects: Boolean,
        ): HttpResult = throw UnsupportedOperationException()

        override suspend fun post(
            url: String,
            body: String,
            headers: Map<String, String>,
            contentType: String,
        ): HttpResult {
            requests.add(url)
            delayByPrefix.entries.firstOrNull { url.startsWith(it.key) }?.let { delay(it.value) }
            if (failPrefixes.any { url.startsWith(it) }) throw IllegalStateException("hang then die")
            return HttpResult(200, mapOf("content-type" to listOf("application/json")), this.body.toByteArray())
        }

        override suspend fun fetchStream(
            url: String,
            headers: Map<String, String>,
            followRedirects: Boolean,
        ): StreamResult = throw UnsupportedOperationException()
    }

    @Test
    fun `a hanging base must not delay the one that answers`() = runTest {
        // 实测就是用户那台 VPS 的形状：官方域名挂住不回，镜像要 13~25 秒才给结果。
        // 串行尝试时总耗时是「两者相加」，42 秒后两个都超时 —— 搜索页就此永远没有条目。
        val fetcher = TimingFetcher(
            delayByPrefix = mapOf("https://api.bgm.tv" to 30_000L, "https://bgmapi.anibt.net" to 1_000L),
            failPrefixes = setOf("https://api.bgm.tv"),
            body = searchResponse,
        )
        val client = BangumiClient(fetcher, listOf("https://api.bgm.tv", "https://bgmapi.anibt.net"))
        client.search("CLANNAD")
        // 竞速：1 秒时镜像就赢了，不该被官方那 30 秒拖住
        assertTrue(currentTime < 5_000L, "总耗时 $currentTime ms，说明还是在串行等慢的那个")
        assertEquals(2, fetcher.requests.size, "两个地址应同时发出")
    }

    @Test
    fun `the winner is used alone next time`() = runTest {
        val fetcher = TimingFetcher(
            delayByPrefix = mapOf("https://api.bgm.tv" to 30_000L, "https://bgmapi.anibt.net" to 1_000L),
            failPrefixes = setOf("https://api.bgm.tv"),
            body = searchResponse,
        )
        val client = BangumiClient(fetcher, listOf("https://api.bgm.tv", "https://bgmapi.anibt.net"))
        client.search("第一次")
        client.search("第二次")
        // 稳态下不该再给那个已知挂死的域名开连接：竞速只在第一轮发生一次
        val official = fetcher.requests.count { it.startsWith("https://api.bgm.tv") }
        assertEquals(1, official, "死域名被打了 $official 次：${fetcher.requests}")
        assertEquals(2, fetcher.requests.count { it.startsWith("https://bgmapi.anibt.net") })
    }

    @Test
    fun `a stale winner falls back to racing instead of failing`() = runTest {
        // 记住的地址后来挂了：不能一直信记录，必须回到竞速，否则整站元数据就此死掉
        var mirrorAlive = true
        val fetcher = object : Fetcher {
            val seen = mutableListOf<String>()
            override suspend fun fetch(
                url: String,
                headers: Map<String, String>,
                followRedirects: Boolean,
            ): HttpResult = throw UnsupportedOperationException()

            override suspend fun post(
                url: String,
                body: String,
                headers: Map<String, String>,
                contentType: String,
            ): HttpResult {
                seen.add(url)
                val alive = if (url.startsWith("https://bgmapi.anibt.net")) mirrorAlive else !mirrorAlive
                if (!alive) throw IllegalStateException("down")
                return HttpResult(200, mapOf("content-type" to listOf("application/json")), searchResponse.toByteArray())
            }

            override suspend fun fetchStream(
                url: String,
                headers: Map<String, String>,
                followRedirects: Boolean,
            ): StreamResult = throw UnsupportedOperationException()
        }
        val client = BangumiClient(fetcher, listOf("https://api.bgm.tv", "https://bgmapi.anibt.net"))
        assertEquals(1, client.search("第一次").size, "第一轮镜像活，应由镜像赢")
        // 换过来：记住的镜像挂了、之前挂死的官方活了
        mirrorAlive = false
        val second = client.search("第二次")
        assertEquals(1, second.size, "首选地址挂掉后必须回退到竞速，而不是直接失败")
    }
}
