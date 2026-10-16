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
    fun `resolve video extracts JS-escaped URLs from player_aaaa`() = runTest {
        // 照抄真实社区源（樱花/番茄/叽哔这类 AppleCMS 站）的播放页形态：
        // 地址在页面里是明文可读的，但斜杠被 JSON 转义成 \/。
        // 提取正则把反斜杠当终止符时，整条 URL 一个字都取不到，
        // 用户看到的就是「视频地址解析失败：NO_VIDEO_URL_FOUND」——播放不了的头号成因。
        val page = """
            <html><body><script>
            var player_aaaa={"flag":"play","encrypt":0,"link":"\/play\/542-1-1.html",
            "vod_data":{"vod_name":"\u5492\u672f\u56de\u6218"},
            "url":"https:\/\/v6.tlkqc.com\/wjv6\/202309\/09\/abc\/video\/index.m3u8",
            "url_next":"https:\/\/v6.tlkqc.com\/wjv6\/202309\/09\/next\/video\/index.m3u8",
            "from":"wjm3u8","server":"","note":"","id":"542","sid":1,"nid":2}
            </script></body></html>
        """.trimIndent()
        val fetcher = StubFetcher(mapOf("$base/play/542-1-1.html" to page))
        val video = SelectorEngine(fetcher).resolveVideo(config(), "$base/play/542-1-1.html")
        assertEquals("https://v6.tlkqc.com/wjv6/202309/09/abc/video/index.m3u8", video.url)
    }

    @Test
    fun `resolve video still extracts plain URLs after the unescape pass`() = runTest {
        // 解转义不能把没转义的写法弄坏（两条路径都要能出地址，且不能重复出）
        val page = """
            <html><body>
            <video src="https://cdn.plain.example/one.mp4"></video>
            <script>var u = "https:\/\/cdn.escaped.example\/two.m3u8?sig=9";</script>
            </body></html>
        """.trimIndent()
        val fetcher = StubFetcher(mapOf("$base/play/plain.html" to page))
        val video = SelectorEngine(fetcher).resolveVideo(config(), "$base/play/plain.html")
        assertTrue(video.url.startsWith("https://cdn."), video.url)
    }

    @Test
    fun `format a carries the lazy-loaded cover so results can show posters`() = runTest {
        // 列表站的 src 往往是占位 gif，真图在 data-src；取不到封面就只能显示空白卡片
        val page = """
            <html><body><div class="list">
              <a href="/v/1.html" title="番剧甲"><img src="/p/placeholder.gif" data-src="https://img.cdn.test/a.jpg"></a>
              <a href="/v/2.html" title="没有图的条目"><span>纯文字</span></a>
            </div></body></html>
        """.trimIndent()
        val cfg = config(SubjectFormatA.ID).copy(
            selectorSubjectFormatA = SubjectFormatA.Config(selectLists = "div.list > a"),
        )
        val fetcher = StubFetcher(mapOf("$base/search?keyword=%E6%97%A5" to page))
        val hits = SelectorEngine(fetcher).search(cfg, "日")
        assertEquals(2, hits.size)
        assertEquals("https://img.cdn.test/a.jpg", hits[0].cover)
        kotlin.test.assertNull(hits[1].cover, "取不到封面要留空，不能编一个")
    }

    @Test
    fun `cover comes from the item box when the title link and the poster are siblings`() = runTest {
        // myui（AppleCMS）：标题在 h4>a，海报在平级 <a> 里的 <img>。
        // 只在 <a> 自己的子树里找会让整站结果一张图都没有（实测嘀嗒影视就是这个形态）。
        val page = """
            <html><body><ul class="myui-vodlist">
              <li><div class="thumb"><a href="/v/1.html"><img data-original="/p/1.jpg"></a></div>
                  <h4><a href="/v/1.html">葬送的芙莉莲</a></h4></li>
              <li><div class="thumb"><a href="/v/2.html"><img data-original="/p/2.jpg"></a></div>
                  <h4><a href="/v/2.html">灵能百分百</a></h4></li>
            </ul></body></html>
        """.trimIndent()
        val cfg = config(SubjectFormatA.ID).copy(
            selectorSubjectFormatA = SubjectFormatA.Config(selectLists = ".myui-vodlist h4 > a"),
        )
        val fetcher = StubFetcher(mapOf("$base/search?keyword=%E6%97%A5" to page))
        val hits = SelectorEngine(fetcher).search(cfg, "日")
        assertEquals(2, hits.size)
        // 各自回到自己的 <li> 里取图：错位一格就是「看着 A 的海报点进去播 B」
        assertEquals(
            mapOf("葬送的芙莉莲" to "$base/p/1.jpg", "灵能百分百" to "$base/p/2.jpg"),
            hits.associate { it.name to it.cover },
        )
    }

    @Test
    fun `stui cover lives on the thumb link as data-original without any img`() = runTest {
        // stui 模板（樱花动漫/影视森林这类）整页只有三个装饰 <img>，海报是
        // 另一个平级 <a> 的 data-original 属性。只找 <img> 的话封面永远为空。
        val page = """
            <html><body><ul class="stui-vodlist__media">
              <li><div class="thumb"><a class="lazyload" href="/d/1.html" data-original="/upload/1.jpg"></a></div>
                  <div class="detail"><h3 class="title"><a href="/d/1.html">葬送的芙莉莲</a></h3>
                  <p><a class="btn" href="/p/1.html">立即播放</a></p></div></li>
              <li><div class="thumb"><a class="lazyload" href="/d/2.html" data-original="https://pic.test/2.webp"></a></div>
                  <div class="detail"><h3 class="title"><a href="/d/2.html">灵能百分百</a></h3></div></li>
            </ul></body></html>
        """.trimIndent()
        val cfg = config(SubjectFormatA.ID).copy(
            selectorSubjectFormatA = SubjectFormatA.Config(selectLists = ".title > a"),
        )
        val fetcher = StubFetcher(mapOf("$base/search?keyword=%E6%97%A5" to page))
        val hits = SelectorEngine(fetcher).search(cfg, "日")
        assertEquals(
            mapOf("葬送的芙莉莲" to "$base/upload/1.jpg", "灵能百分百" to "https://pic.test/2.webp"),
            hits.associate { it.name to it.cover },
        )
    }

    @Test
    fun `indexed format keeps covers aligned with names after blank titles`() = runTest {
        val page = """
            <html><body>
              <div class="box"><i><a href="/v/1.html"><img data-original="https://img.cdn.test/1.jpg"></a></i><p class="t">甲</p></div>
              <div class="box"><i><a href="/v/2.html"><img data-original="https://img.cdn.test/2.jpg"></a></i><p class="t"></p></div>
              <div class="box"><i><a href="/v/3.html"><img data-original="https://img.cdn.test/3.jpg"></a></i><p class="t">丙</p></div>
            </body></html>
        """.trimIndent()
        val cfg = config(SubjectFormatIndexed.ID).copy(
            selectorSubjectFormatIndexed = SubjectFormatIndexed.Config(
                selectNames = "p.t",
                selectLinks = "i a",
            ),
        )
        val fetcher = StubFetcher(mapOf("$base/search?keyword=%E6%97%A5" to page))
        val hits = SelectorEngine(fetcher).search(cfg, "日")
        assertEquals(listOf("甲", "丙"), hits.map { it.name })
        // 名字为空的那条被丢掉时，封面必须跟着自己的链接走，不能整体错位
        assertEquals(listOf("https://img.cdn.test/1.jpg", "https://img.cdn.test/3.jpg"), hits.map { it.cover })
        // 封面必须跟着自己的链接：错位一次，用户就是「看着 A 的海报点进去播 B」
    }

    @Test
    fun `json results pick up cover fields under whichever common name the site uses`() = runTest {
        val body = """{"list":[{"vod_name":"甲","vod_pic":"https://img.cdn.test/j.jpg","vod_play_url":"https://cdn.test/a.m3u8"},
                                     {"vod_name":"乙","vod_pic":"","vod_play_url":"https://cdn.test/b.m3u8"}]}"""
        val cfg = config(SubjectFormatJsonPathIndexed.ID).copy(
            selectorSubjectFormatJsonPathIndexed = SubjectFormatJsonPathIndexed.Config(
                selectNames = "$.list[*].vod_name",
                selectLinks = "$.list[*].vod_play_url",
            ),
        )
        val fetcher = StubFetcher(mapOf("$base/search?keyword=%E6%97%A5" to body))
        val hits = SelectorEngine(fetcher).search(cfg, "日")
        assertEquals("https://img.cdn.test/j.jpg", hits[0].cover)
        assertTrue(hits[1].cover.isNullOrBlank(), "空字符串封面要当成没有")
    }

    @Test
    fun `resolve video throws when not found`() = runTest {
        val fetcher = StubFetcher(mapOf("$base/play/x.html" to "<html><body>no video</body></html>"))
        assertFailsWith<SelectorException> {
            SelectorEngine(fetcher).resolveVideo(config(), "$base/play/x.html")
        }
    }

    @Test
    fun `a stale CDN whitelist in the source config falls back to the video extension`() = runTest {
        // 社区配置普遍把 matchVideoUrl 写成「CDN 域名白名单」，源站换域名后就永远 0 命中，
        // 而播放页里明明写着 index.m3u8（实测番茄动漫就是这个形态）。
        // 这种情况必须还能播，而不是让用户看到 NO_VIDEO_URL_FOUND 去猜是谁的问题。
        val cfg = config().copy(
            matchVideo = MatchVideoConfig(matchVideoUrl = """^https://old-cdn\.test/"""),
        )
        val page = """<html><body><script>var u="https://v9.newcdn.test/x/index.m3u8?sign=abc"</script></body></html>"""
        val fetcher = StubFetcher(mapOf("$base/play/x.html" to page))
        val video = SelectorEngine(fetcher).resolveVideo(cfg, "$base/play/x.html")
        assertEquals("https://v9.newcdn.test/x/index.m3u8?sign=abc", video.url)
    }

    @Test
    fun `the source regex still wins over the generic fallback`() = runTest {
        // 兜底只能捡配置没认出来的地址：同页有配置指定的线路时，绝不能被
        // 一个「看起来也是视频」的旁路地址抢掉（那往往是预览小样或广告）
        val cfg = config().copy(
            matchVideo = MatchVideoConfig(matchVideoUrl = """https://preferred\.test/"""),
        )
        val page = """<html><body>
            <script>var a="https://other.test/x/preview.mp4"</script>
            <script>var b="https://preferred.test/y/index.m3u8"</script></body></html>"""
        val fetcher = StubFetcher(mapOf("$base/play/x.html" to page))
        val video = SelectorEngine(fetcher).resolveVideo(cfg, "$base/play/x.html")
        assertEquals("https://preferred.test/y/index.m3u8", video.url)
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
