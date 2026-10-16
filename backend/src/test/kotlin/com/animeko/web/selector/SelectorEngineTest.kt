package com.animeko.web.selector

import com.animeko.web.http.Fetcher
import com.animeko.web.http.HttpResult
import com.animeko.web.http.StreamResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 按 URL 前缀返回 fixture 的打桩 Fetcher。
 * redirects 用来模拟「源站 302 到内网」这类逐跳校验场景（值 = Location）。
 */
class StubFetcher(
    private val routes: Map<String, String>,
    private val redirects: Map<String, String> = emptyMap(),
) : Fetcher {
    val requested = mutableListOf<String>()

    private fun lookup(url: String): String? = routes[url]
        ?: routes.entries.firstOrNull { (prefix, _) -> url.startsWith(prefix) }?.value

    override suspend fun fetch(
        url: String,
        headers: Map<String, String>,
        followRedirects: Boolean,
    ): HttpResult {
        requested.add(url)
        redirects[url]?.let { loc ->
            return HttpResult(302, mapOf("location" to listOf(loc)), ByteArray(0))
        }
        val body = lookup(url) ?: throw IllegalStateException("no fixture for $url")
        return HttpResult(200, mapOf("content-type" to listOf("text/html")), body.toByteArray())
    }

    /** 生产实现是「逐跳校验 + 边读边限」，桩要跟着走这条路径，否则新代码在测试里等于没被测到 */
    override suspend fun fetchBounded(
        url: String,
        headers: Map<String, String>,
        maxBytes: Long,
    ): HttpResult {
        val r = fetch(url, headers, followRedirects = false)
        if (r.body.size > maxBytes) throw com.animeko.web.http.UpstreamException("超过 ${maxBytes} 字节上限")
        return r
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

private fun fixture(name: String): String =
    checkNotNull(SelectorEngineTest::class.java.getResourceAsStream("/fixtures/$name")) {
        "fixture $name not found"
    }.bufferedReader().use { it.readText() }

class SelectorEngineTest {

    private val base = "https://example-site.test"

    private fun config(
        subjectFormatId: String = SubjectFormatA.ID,
        channelFormatId: String = ChannelFormatNoChannel.ID,
    ) = SelectorSearchConfig(
        searchUrl = "$base/search?keyword={keyword}",
        rawBaseUrl = base,
        requestInterval = 0,
        subjectFormatId = subjectFormatId,
        channelFormatId = channelFormatId,
    )

    @Test
    fun `search with subject format A`() = runTest {
        val fetcher = StubFetcher(mapOf("$base/search" to fixture("search_page.html")))
        val hits = SelectorEngine(fetcher).search(config(), "示例")
        assertEquals(3, hits.size)
        // preferShorterName 默认开启，按名称长度升序：另一个番剧(5) < 示例番剧 第一季 < 较长的番剧名称测试
        assertEquals("另一个番剧", hits.first().name)
        assertEquals("$base/anime/5678.html", hits.first().url)
        assertTrue(hits.map { it.name }.contains("示例番剧 第一季"))
        assertTrue(hits.first().name.length <= hits.last().name.length)
    }

    @Test
    fun `search with indexed format`() = runTest {
        val fetcher = StubFetcher(mapOf("$base/search" to fixture("search_indexed.html")))
        val hits = SelectorEngine(fetcher).search(config(subjectFormatId = SubjectFormatIndexed.ID), "番剧")
        assertEquals(
            listOf("番剧A" to "$base/a/1.html", "番剧B" to "$base/a/2.html"),
            hits.map { it.name to it.url },
        )
    }

    @Test
    fun `search with json-path indexed format`() = runTest {
        val fetcher = StubFetcher(mapOf("$base/search" to fixture("search.json")))
        val hits = SelectorEngine(fetcher).search(config(subjectFormatId = SubjectFormatJsonPathIndexed.ID), "JSON")
        assertEquals(
            listOf("JSON番剧一" to "$base/j/1.html", "JSON番剧二" to "$base/j/2.html"),
            hits.map { it.name to it.url },
        )
    }

    @Test
    fun `indexed format keeps names and links aligned when one entry has no title`() = runTest {
        // 图片卡 / 广告位 / 「更多」链接常常只有链接没有文字。
        // 旧实现先把两条列表各自 filter 掉空值再按位置 zip，少掉一个名字会让
        // 后面所有条目整体错位一位：列表显示「番剧B」，点进去播的是 /ad.html。
        val html = """
            <html><body><div class="search-box">
              <div class="thumb-content">
                <div class="thumb-txt">番剧A</div>
                <div class="thumb-txt"></div>
                <div class="thumb-txt">番剧B</div>
              </div>
              <div class="thumb-menu">
                <a href="/a/1.html">x</a>
                <a href="/ad.html">x</a>
                <a href="/a/2.html">x</a>
              </div>
            </div></body></html>
        """.trimIndent()
        val fetcher = StubFetcher(mapOf("$base/search" to html))
        val hits = SelectorEngine(fetcher).search(
            config(subjectFormatId = SubjectFormatIndexed.ID), "番剧",
        )
        assertEquals(
            listOf("番剧A" to "$base/a/1.html", "番剧B" to "$base/a/2.html"),
            hits.map { it.name to it.url },
        )
    }

    @Test
    fun `json path indexed drops null titles without shifting the remaining urls`() = runTest {
        val body = """
            [{"title":"JSON甲","url":"/j/1.html"},{"title":null,"url":"/ad.html"},{"title":"JSON乙","url":"/j/2.html"}]
        """.trimIndent()
        val fetcher = StubFetcher(mapOf("$base/search" to body))
        val hits = SelectorEngine(fetcher).search(
            config(subjectFormatId = SubjectFormatJsonPathIndexed.ID), "JSON",
        )
        // title 为 JSON null 时不能变成字面串 "null"，也不能让后面的地址前移一位
        assertEquals(
            listOf("JSON甲" to "$base/j/1.html", "JSON乙" to "$base/j/2.html"),
            hits.map { it.name to it.url },
        )
    }

    @Test
    fun `episodes with no-channel format`() = runTest {
        val fetcher = StubFetcher(mapOf("$base/anime/1.html" to fixture("detail_nochannel.html")))
        val cfg = config().copy(
            selectorChannelFormatNoChannel = ChannelFormatNoChannel.Config(selectEpisodes = "#glist-1 a"),
        )
        val result = SelectorEngine(fetcher).episodes(cfg, "$base/anime/1.html")
        assertEquals(3, result.episodes.size)
        assertTrue(result.channels.isEmpty())
        val first = result.episodes.first()
        assertEquals("第1话 起始之日", first.name)
        assertEquals("1", first.sort)
        assertEquals(1, first.sortNum)
        assertEquals("$base/play/9-1.html", first.playUrl)
        assertNull(first.channel)
    }

    @Test
    fun `episodes with index-grouped format`() = runTest {
        val fetcher = StubFetcher(mapOf("$base/anime/1.html" to fixture("detail_grouped.html")))
        val result = SelectorEngine(fetcher).episodes(
            config(channelFormatId = ChannelFormatIndexGrouped.ID),
            "$base/anime/1.html",
        )
        assertEquals(listOf("线路一", "备用线路"), result.channels)
        assertEquals(4, result.episodes.size)
        assertEquals(2, result.groups.size)
        assertEquals("线路一", result.groups[0].name)
        assertEquals(2, result.groups[0].episodes.size)
        // 集号从「第N话」提取
        assertEquals(1, result.episodes[0].sortNum)
        assertEquals(2, result.episodes[1].sortNum)
        assertEquals("$base/play/2-2.html", result.episodes[3].playUrl)
    }

    @Test
    fun `resolve video url from play page`() = runTest {
        val fetcher = StubFetcher(mapOf("$base/play/1-1.html" to fixture("play_page.html")))
        val video = SelectorEngine(fetcher).resolveVideo(config(), "$base/play/1-1.html")
        assertEquals("https://cdn.example.com/video/12345/index.m3u8?token=abcdef", video.url)
        assertEquals(base, video.referer)
        // 播放页 UA 默认来自 userAgent
        assertTrue(video.userAgent.contains("Mozilla"))
    }

    @Test
    fun `resolve video follows nested iframe`() = runTest {
        val outer = """
            <html><body><iframe src="/player/frame.php?id=9"></iframe></body></html>
        """.trimIndent()
        val inner = """
            <html><body><script>var u="https://cdn.example.net/hls/movie.m3u8?sig=1";</script></body></html>
        """.trimIndent()
        val fetcher = StubFetcher(
            mapOf(
                "$base/play/outer.html" to outer,
                "$base/player/frame.php?id=9" to inner,
            ),
        )
        // 外层页面没有直接视频地址；matchNestedUrl 自定义为匹配 frame.php
        val cfg = config().copy(
            matchVideo = MatchVideoConfig(
                enableNestedUrl = true,
                matchNestedUrl = """frame\.php""",
            ),
        )
        val video = SelectorEngine(fetcher).resolveVideo(cfg, "$base/play/outer.html")
        assertEquals("https://cdn.example.net/hls/movie.m3u8?sig=1", video.url)
        assertTrue(fetcher.requested.contains("$base/player/frame.php?id=9"))
    }

    @Test
    fun `resolve video throws when not found`() = runTest {
        val fetcher = StubFetcher(mapOf("$base/play/x.html" to "<html><body>no video</body></html>"))
        assertFailsWith<SelectorException> {
            SelectorEngine(fetcher).resolveVideo(config(), "$base/play/x.html")
        }
    }

@Test
    fun `search redirect into the intranet is refused on the hop, not fetched`() = runTest {
        // 数据源可以是社区导入的，它返回的 302 属于「别人决定我们要连谁」。
        // 只校验入口 URL、然后让 HTTP 客户端自动跟随，等于把这决定权交了出去
        val fetcher = StubFetcher(
            routes = emptyMap(),
            redirects = mapOf("$base/search?keyword=%E5%AD%A4%E7%8B%AC" to "http://127.0.0.1:8080/admin"),
        )
        val engine = SelectorEngine(fetcher, allowPrivateTargets = false)
        // 必须是 403（UrlGuard.Blocked），而不是被包成 502 且带上「主机名 -> 内网 IP」的拒绝原因
        val e = assertFailsWith<com.animeko.web.http.UrlGuard.Blocked> { engine.search(config(), "孤独") }
        assertTrue(e.message!!.contains("127.0.0.1"), e.message!!)
        assertTrue(
            fetcher.requested.none { it.contains("127.0.0.1") },
            "被拦下的一跳不该真的发出去：${fetcher.requested}",
        )
    }

    @Test
    fun `search redirect that stays public is followed`() = runTest {
        val fetcher = StubFetcher(
            routes = mapOf("https://mirror-site.test/search?keyword=%E5%AD%A4%E7%8B%AC" to fixture("search_page.html")),
            redirects = mapOf("$base/search?keyword=%E5%AD%A4%E7%8B%AC" to "https://mirror-site.test/search?keyword=%E5%AD%A4%E7%8B%AC"),
        )
        val engine = SelectorEngine(fetcher, allowPrivateTargets = false)
        val hits = engine.search(config(), "孤独")
        assertTrue(hits.isNotEmpty(), "公网内的续跳应当照常工作")
    }

    @Test
    fun `oversized upstream page is refused`() = runTest {
        // 上限要在读取过程中生效：正则跑在几百 MB 文本上不是「慢」，是挂死线程
        val huge = "<html><body>" + "x".repeat(9 * 1024 * 1024) + "</body></html>"
        val fetcher = StubFetcher(routes = mapOf("$base/search?keyword=%E5%AD%A4%E7%8B%AC" to huge))
        val engine = SelectorEngine(fetcher, allowPrivateTargets = false)
        val e = assertFailsWith<com.animeko.web.http.UpstreamException> { engine.search(config(), "孤独") }
        assertTrue(e.message!!.contains("上限"), e.message!!)
    }

    @Test
    fun `keyword processing removes specials and keeps first word`() {
        val auto = SelectorAutoMatchConfig(searchUseOnlyFirstWord = true, searchRemoveSpecial = true)
        assertEquals("孤独摇滚", auto.processKeyword("孤独摇滚！ 第二季"))
    }

    @Test
    fun `autoMatch legacy flat fields are folded`() {
        val cfg = SelectorSearchConfig(searchUseOnlyFirstWord = false, searchRemoveSpecial = false)
        val resolved = cfg.resolvedAutoMatch()
        assertEquals(false, resolved.searchUseOnlyFirstWord)
        assertEquals(false, resolved.searchRemoveSpecial)
    }

    @Test
    fun `resolve video strips template literal backticks from candidates`() = runTest {
        // 反引号模板串是现在最主流的 JS 写法。候选 URL 的正则漏掉反引号时，
        // 匹配上的候选会带着尾部的 ` 被整串返回：解析「成功」、播放 404，
        // 且完全看不出是候选串被污染了。
        val page = """
            <html><body><script>
            var u = `https://cdn.example.net/hls/movie.m3u8?sig=1`;
            </script></body></html>
        """.trimIndent()
        val fetcher = StubFetcher(mapOf("$base/play/backtick.html" to page))
        val video = SelectorEngine(fetcher).resolveVideo(config(), "$base/play/backtick.html")
        assertEquals("https://cdn.example.net/hls/movie.m3u8?sig=1", video.url)
    }
}
