package com.animeko.web.bangumi

import com.animeko.web.http.Fetcher
import com.animeko.web.http.HttpResult
import com.animeko.web.http.StreamResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 通道配置的判定逻辑。做成纯 JVM 单测（不起 HTTP）的理由：
 * resolveBases/setChannel 是「选完通道实际会用哪些地址」的唯一解释处，
 * 这条链上的错误（比如自定义地址没清尾斜杠、换通道没清赢家）都会表现成
 * 「设置了但没生效」，用集成测试排查成本太高。
 */
class BangumiChannelTest {

    /** 只记录 URL、按前缀给固定响应，够通道逻辑测试用 */
    private class StubFetcher(private val statuses: Map<String, Int>) : Fetcher {
        val seen = mutableListOf<String>()
        override suspend fun fetch(url: String, headers: Map<String, String>, followRedirects: Boolean): HttpResult {
            seen.add(url)
            val status = statuses.entries.firstOrNull { url.startsWith(it.key) }?.value ?: 200
            return HttpResult(status, mapOf("content-type" to listOf("application/json")), "[]".toByteArray())
        }
        override suspend fun post(url: String, body: String, headers: Map<String, String>, contentType: String): HttpResult =
            fetch(url, headers)
        override suspend fun fetchStream(url: String, headers: Map<String, String>, followRedirects: Boolean): StreamResult =
            throw UnsupportedOperationException()
    }

    /** Virtual delays force different search/calendar winners; no real upstream is contacted. */
    private class SearchStub(
        private val searchBody: String = """{"total":0,"data":[],"limit":24,"offset":0}""",
    ) : Fetcher {
        val seen = mutableListOf<String>()
        val completed = mutableListOf<String>()
        override suspend fun fetch(url: String, headers: Map<String, String>, followRedirects: Boolean): HttpResult {
            seen.add(url)
            val isCalendar = url.contains("/v0/calendar")
            delay(if (url.startsWith(BangumiClient.OFFICIAL_BASE)) 10 else 100)
            val status = if (isCalendar && url.contains("bgmapi.anibt.net")) 404 else 200
            val body = if (isCalendar) "[]" else searchBody
            completed.add(url)
            return HttpResult(status, mapOf("content-type" to listOf("application/json")), body.toByteArray())
        }
        override suspend fun post(url: String, body: String, headers: Map<String, String>, contentType: String): HttpResult {
            seen.add(url)
            delay(if (url.startsWith(BangumiClient.MIRROR_BASE)) 10 else 100)
            completed.add(url)
            return HttpResult(200, mapOf("content-type" to listOf("application/json")), searchBody.toByteArray())
        }
        override suspend fun fetchStream(url: String, headers: Map<String, String>, followRedirects: Boolean): StreamResult =
            throw UnsupportedOperationException()
    }

    private fun store(): BgmChannelStore = BgmChannelStore(
        Files.createTempDirectory(
            java.nio.file.Path.of(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir")),
            "bgmchan",
        ).resolve("c.json"),
    )

    @Test
    fun `an oversized saved custom channel falls back to auto`() {
        val store = store()
        store.save(BgmChannelConfig(BgmChannel.CUSTOM, (1..20).map { "https://relay$it.test" }))
        val client = BangumiClient(StubFetcher(emptyMap()), channelStore = store)
        // 手工改出来的超长列表在启动时就退回 AUTO，而不是留着让每次请求逐条 DNS
        assertEquals(BgmChannel.AUTO, client.channelState().channel)
        assertEquals(BgmChannel.AUTO, store.load().channel, "退回结果要落盘，否则下次启动还会读到老配置")
    }

    @Test
    fun `official channel uses only official base`() {
        val client = BangumiClient(StubFetcher(emptyMap()), channelStore = store())
        val saved = client.setChannel(BgmChannelConfig(BgmChannel.OFFICIAL))
        assertEquals(BgmChannel.OFFICIAL, saved.channel)
        assertEquals(listOf("https://api.bgm.tv"), client.effectiveBases())
        assertTrue(client.calendarSupported)
    }

    @Test
    fun `mirror channel has no calendar`() {
        val client = BangumiClient(StubFetcher(emptyMap()), channelStore = store())
        client.setChannel(BgmChannelConfig(BgmChannel.MIRROR))
        assertEquals(listOf("https://bgmapi.anibt.net"), client.effectiveBases())
        assertFalse(client.calendarSupported)
    }

    @Test
    fun `custom channel trims slashes and keeps order`() {
        val client = BangumiClient(StubFetcher(emptyMap()), channelStore = store())
        client.setChannel(
            BgmChannelConfig(BgmChannel.CUSTOM, listOf("https://relay.example.com/", "https://backup.example.com")),
        )
        assertEquals(listOf("https://relay.example.com", "https://backup.example.com"), client.effectiveBases())
        assertTrue(client.calendarSupported) // 自定义地址能不能用由探测说了算，这里不武断
    }

    @Test
    fun `custom rejects empty and non-http`() {
        val client = BangumiClient(StubFetcher(emptyMap()), channelStore = store())
        assertFailsWith<IllegalArgumentException> { client.setChannel(BgmChannelConfig(BgmChannel.CUSTOM)) }
        assertFailsWith<IllegalArgumentException> {
            client.setChannel(BgmChannelConfig(BgmChannel.CUSTOM, listOf("ftp://nope")))
        }
        assertFailsWith<IllegalArgumentException> {
            client.setChannel(BgmChannelConfig(BgmChannel.CUSTOM, listOf("http://")))
        }
    }

    /**
     * 安全回归：自定义通道不能指向内网。
     *
     * 这个地址会被服务端拿去发请求（搜索/放送/探测都走它），等于一个
     * 「让服务器访问任意 URL」的入口；而默认部署 ANIMEKO_AUTH_ENABLED 是关的，
     * 不拦就是给任何能打开设置页的人一个 SSRF 跳板 —— 内网端口扫描、
     * 云元数据（169.254.169.254）都能打。
     * proxy 与订阅早就走 UrlGuard，这条新入口当初漏了。
     */
    @Test
    fun `custom channel rejects internal addresses`() {
        val client = BangumiClient(StubFetcher(emptyMap()), channelStore = store())
        for (bad in listOf("http://127.0.0.1:8080", "http://169.254.169.254/latest/meta-data")) {
            assertFailsWith<IllegalArgumentException>("应拒绝内网地址：$bad") {
                client.setChannel(BgmChannelConfig(BgmChannel.CUSTOM, listOf(bad)))
            }
        }
        // 被拒之后通道应保持原样，不能半截写入
        assertEquals(BgmChannel.AUTO, client.channelState().channel)
    }

    /** 显式允许内网时（ANIMEKO_ALLOW_PRIVATE_TARGETS=true）应放行 —— 局域网自建中转的场景 */
    @Test
    fun `custom channel allows internal addresses when explicitly permitted`() {
        val client = BangumiClient(
            StubFetcher(emptyMap()),
            channelStore = store(),
            allowPrivateTargets = true,
        )
        val saved = client.setChannel(BgmChannelConfig(BgmChannel.CUSTOM, listOf("http://127.0.0.1:8080")))
        assertEquals(BgmChannel.CUSTOM, saved.channel)
        assertEquals(listOf("http://127.0.0.1:8080"), client.effectiveBases())
    }

    @Test
    fun `setting persists across instances via store`() {
        val store = store()
        BangumiClient(StubFetcher(emptyMap()), channelStore = store)
            .setChannel(BgmChannelConfig(BgmChannel.MIRROR))
        val reloaded = BangumiClient(StubFetcher(emptyMap()), channelStore = store)
        assertEquals(BgmChannel.MIRROR, reloaded.channelState().channel)
    }

    @Test
    fun `probe separates core availability from calendar availability`() {
        val stub = StubFetcher(
            mapOf(
                "https://api.bgm.tv/v0/calendar" to 200,
                "https://bgmapi.anibt.net/v0/calendar" to 404,
            ),
        )
        val client = BangumiClient(stub, channelStore = store())
        val results = kotlinx.coroutines.runBlocking { client.probe(BgmChannelConfig(BgmChannel.AUTO)) }
        assertEquals(2, results.size)

        // 官方：核心与放送都可用
        val official = results.first { it.base == "https://api.bgm.tv" }
        assertTrue(official.ok)
        assertTrue(official.calendarOk)
        assertTrue(official.detail.contains("每日放送"))

        // 镜像：核心可用（subjects 200），但没有放送表 —— 这是真实测到的组合。
        // 早前的实现用 calendar 单点当判据，会把镜像错报成「不可用」。
        val mirror = results.first { it.base == "https://bgmapi.anibt.net" }
        assertTrue(mirror.ok, "镜像的搜索/条目可用，不该报成不可用")
        assertFalse(mirror.calendarOk)
        assertTrue(mirror.detail.contains("不提供每日放送"))
    }

    @Test
    fun `effective bases follow the channel`() {
        val client = BangumiClient(StubFetcher(emptyMap()), channelStore = store())
        client.setChannel(BgmChannelConfig(BgmChannel.OFFICIAL))
        assertEquals(listOf("https://api.bgm.tv"), client.effectiveBases())
        client.setChannel(BgmChannelConfig(BgmChannel.MIRROR))
        assertEquals(listOf("https://bgmapi.anibt.net"), client.effectiveBases())
    }

    /**
     * 回归：放送表赢了之后，不能把「全局首选」改写成它用的地址。
     *
     * 与下一条是镜像问题的两个方向，必须都锁住：
     *  - 这一条：自定义通道下放送表可能命中某个中转 → 不能让它成为搜索的首选，
     *    否则搜索会绕到一个只保证放送表可用的地址上
     *  - 下一条：搜索锚定镜像 → 放送表不能跟着撞 404
     */
    @Test
    fun `calendar does not hijack the preferred base used by search`() = runTest {
        val stub = SearchStub()
        val client = BangumiClient(stub, channelStore = store())
        client.search("establish mirror winner")
        stub.seen.clear()
        client.search("confirm search preference")
        val searchPreferred = "${BangumiClient.MIRROR_BASE}/v0/search/subjects"
        assertEquals(listOf(searchPreferred), stub.seen, "the mirror must actually be the warm search winner")

        stub.seen.clear()
        stub.completed.clear()
        assertEquals(emptyList(), client.calendar())
        assertEquals(listOf("${BangumiClient.OFFICIAL_BASE}/v0/calendar"), stub.completed, "calendar must be won by a different base")

        stub.seen.clear()
        client.search("after calendar")
        assertEquals(listOf(searchPreferred), stub.seen, "calendar must preserve the exact search winner, not merely a single request")
    }

    /**
     * 回归：搜索把「赢家」锚定到镜像之后，放送表不能跟着只撞镜像的 404。
     *
     * 这个 bug 真实存在过：withFallback 的首选快路径是全局共享的，
     * 境外 VPS 上搜索几乎总是镜像先赢并被记住，于是首页放送表每次只试镜像、
     * 拿到 404 就整块消失 —— 而官方明明是通的。
     */
    @Test
    fun `calendar still reaches official after search anchored on mirror`() = runTest {
        val stub = SearchStub()
        val client = BangumiClient(stub, channelStore = store())
        client.search("establish mirror winner")
        stub.seen.clear()
        client.search("confirm mirror winner")
        assertEquals(listOf("${BangumiClient.MIRROR_BASE}/v0/search/subjects"), stub.seen)
        stub.seen.clear()

        val days = client.calendar()
        assertEquals(0, days.size)
        assertTrue(
            stub.seen.any { it == "${BangumiClient.OFFICIAL_BASE}/v0/calendar" },
            "预期向官方请求放送表，实际请求：${stub.seen}",
        )
    }
}
