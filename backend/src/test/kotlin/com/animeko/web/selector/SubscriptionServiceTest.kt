package com.animeko.web.selector

import com.animeko.web.http.UrlGuard
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubscriptionServiceTest {

    private val aggregate = fixture("subscription_aggregate.json")

    private fun fixture(name: String): String =
        checkNotNull(SubscriptionServiceTest::class.java.getResourceAsStream("/fixtures/$name")) {
            "fixture $name not found"
        }.bufferedReader().use { it.readText() }

    private fun service(
        body: String,
        vararg saved: SelectorSourceArguments,
    ): Pair<SubscriptionService, SelectorRepository> {
        val repo = SelectorRepository(Files.createTempDirectory("animeko-sub"))
        saved.forEach { repo.save(it) }
        val fetcher = StubFetcher(mapOf("https://sub.example.test/css.json" to body))
        return SubscriptionService(fetcher, repo, allowPrivateTargets = false) to repo
    }

    private fun existing(name: String) = SelectorSourceArguments(
        name = name,
        description = "本机已有",
        searchConfig = SelectorSearchConfig(searchUrl = "https://existing.test/?wd={keyword}"),
    )

    @Test
    fun `preview lists usable sources without shipping whole configs`() = runTest {
        val (svc, _) = service(aggregate)
        val preview = svc.preview(url = "https://sub.example.test/css.json", text = null)
        assertEquals(listOf("稀饭动漫", "嘀嗒影视", "正则有害的源"), preview.candidates.map { it.name })
        // 没有 searchConfig 的那条能解码成功（字段有默认值），但导入进去就是一条搜不出东西的死源，
        // 必须在预览时就被挑出来并说明原因
        assertEquals(1, preview.rejected.size, preview.rejected.toString())
        assertTrue(preview.rejected[0].contains("缺配置的源"), preview.rejected.toString())
        assertTrue(preview.rejected[0].contains("searchUrl"), preview.rejected.toString())

        val tiers = preview.candidates.associate { it.name to it.tier }
        // 订阅里 tier 有数字也有字符串，展示层要拿到统一文本
        assertEquals("0", tiers["稀饭动漫"], tiers.toString())
        assertEquals("1", tiers["嘀嗒影视"], tiers.toString())
        assertEquals("xfdm.test", preview.candidates.first { it.name == "稀饭动漫" }.site, "要能一眼看出是哪个站")
        assertTrue(preview.subscription.contains("css.json"), preview.subscription)
    }

    /**
     * 社区订阅里 tier 的写法不受我们控制：整数、字符串都见过，对象也完全可能出现
     * （`"tier": {"t0": "..."}` 这类自制的映射）。`jsonPrimitive` 对非 primitive
     * 是直接强转，会抛 ClassCastException —— 而 preview 的候选清单是一整条 map，
     * 一条源的 tier 长得奇怪就让整个预览 500，用户看到的是「这个订阅打不开」。
     */
    /**
     * 热门聚合订阅（css1.json 一类）动辄六七百个源。
     * 原先 `require(size <= 500)` 让整份订阅直接失败：用户一个源也导不进来，
     * 而这份文件下面就有「一条坏只丢一条」的机制 —— 上限该用来封顶工作量，
     * 不该用来取消全部工作。
     */
    @Test
    fun `an over-sized subscription keeps what it can instead of failing entirely`() = runTest {
        val many = (1..503).joinToString(",") { i ->
            """{"factoryId":"web-selector","version":2,"arguments":{"name":"源$i",""" +
                """"searchConfig":{"searchUrl":"https://s$i.test/?wd={keyword}","rawBaseUrl":"https://s$i.test"}}}"""
        }
        val body = """{"exportedMediaSourceDataList":{"mediaSources":[$many]}}"""
        val (svc, _) = service(body)
        val preview = svc.preview(url = "https://sub.example.test/css.json", text = null)
        assertEquals(500, preview.candidates.size, "应当列出前 500 个，而不是一个都不给")
        // rejected 是「说明列表」而不是逐条列表：溢出用一个条目讲清有多少被跳过
        assertEquals(1, preview.rejected.size, preview.rejected.toString())
        assertTrue(preview.rejected.single().contains("3 个数据源"), preview.rejected.toString())
        assertTrue(preview.rejected.single().contains("上限"), preview.rejected.toString())
    }

    @Test
    fun `a tier written as an object degrades to text instead of sinking the preview`() = runTest {
        val odd = aggregate.replace("\"tier\": 0", "\"tier\": {\"label\": \"t0\", \"by\": [1,2]}")
        // 断言替换真的发生了。原先写的是 assertTrue("{" in odd)，而 JSON 本身就有大括号
        // —— fixture 一旦改格式（比如去掉冒号两侧空格），替换静默失配，
        // 对象分支一次都没跑过，这个用例却还是绿的。
        assertTrue(odd.contains("\"tier\": {\"label\""), "替换没生效，用例失去意义")
        assertTrue(!odd.contains("\"tier\": 0"), "替换后原值应当消失")
        val (svc, _) = service(odd)
        val preview = svc.preview(url = "https://sub.example.test/css.json", text = null)
        assertEquals(3, preview.candidates.size, preview.candidates.toString())
        // 别的源照常列出，tier 退化成一段可展示的文本而不是崩掉
        val tier = preview.candidates.first { it.name == "稀饭动漫" }.tier
        assertTrue(tier.isNotBlank(), "tier 至少要有个可展示的文本形式")
        assertEquals("1", preview.candidates.first { it.name == "嘀嗒影视" }.tier)
    }

    @Test
    fun `preview marks what is already imported`() = runTest {
        val (svc, _) = service(aggregate, existing("嘀嗒影视"))
        val preview = svc.preview(url = "https://sub.example.test/css.json", text = null)
        assertTrue(preview.candidates.first { it.name == "嘀嗒影视" }.exists, "同名源要提示「已存在，导入会覆盖」")
        assertFalse(preview.candidates.first { it.name == "稀饭动漫" }.exists)
    }

    @Test
    fun `only the checked sources get imported`() = runTest {
        val (svc, repo) = service(aggregate)
        val result = svc.importSelected(
            url = "https://sub.example.test/css.json",
            text = null,
            names = listOf("稀饭动漫"),
        )
        assertEquals(listOf("稀饭动漫"), result.added)
        assertEquals(emptyList(), result.updated)
        assertEquals(1, repo.list().size, "没勾选的源不该被顺手写进仓库")
        assertEquals("https://xfdm.test/api/search?wd={keyword}", repo.find("稀饭动漫")!!.arguments.searchConfig.searchUrl)
    }

    @Test
    fun `a broken entry fails alone and reports why`() = runTest {
        val (svc, repo) = service(aggregate)
        val result = svc.importSelected(
            url = "https://sub.example.test/css.json",
            text = null,
            names = listOf("稀饭动漫", "正则有害的源", "订阅里根本没有的源"),
        )
        assertEquals(listOf("稀饭动漫"), result.added)
        assertEquals(2, result.failed.size, result.failed.toString())
        assertTrue(result.failed.any { it.startsWith("正则有害的源") && it.contains("正则") }, result.failed.toString())
        assertTrue(result.failed.any { it.contains("没有这一条") }, result.failed.toString())
        assertEquals(1, repo.list().size, "坏条目只丢自己，前面已成功的必须保留")
    }

    @Test
    fun `re-importing an existing name reports an update`() = runTest {
        val (svc, repo) = service(aggregate, existing("嘀嗒影视"))
        val result = svc.importSelected(
            url = "https://sub.example.test/css.json", text = null, names = listOf("嘀嗒影视"),
        )
        assertEquals(listOf("嘀嗒影视"), result.updated)
        assertEquals(emptyList(), result.added)
        assertEquals(1, repo.list().size)
        assertTrue(repo.find("嘀嗒影视")!!.arguments.searchConfig.searchUrl.contains("dadi.test"), "应换成订阅里的配置")
    }

    @Test
    fun `re-importing an existing source keeps it disabled`() = runTest {
        // 实测（本机 18079）：导入 → 停用 → 用订阅「更新」同一条 → enabled 又变回 true。
        // 用户关掉一个源是明确判断过它不可用的（死站，或每个源都要等 20 秒超时的那一个）；
        // 重新导入订阅是为拿新内容/新源，不该顺手把这个决定翻回去 ——
        // 症状是之后每次搜索都在那个源上等超时，用户只看到「搜索变慢了」。
        // 自动导入那条路径一直是沿用原状态的（autoImport 的 `known[name] ?: true`），
        // 手动挑源导入漏了这一步。
        val (svc, repo) = service(aggregate, existing("嘀嗒影视"))
        repo.setEnabled("嘀嗒影视", false)
        assertFalse(repo.find("嘀嗒影视")!!.enabled, "前提：源已被停用")

        val result = svc.importSelected(
            url = "https://sub.example.test/css.json", text = null, names = listOf("嘀嗒影视"),
        )
        assertEquals(listOf("嘀嗒影视"), result.updated)
        assertFalse(repo.find("嘀嗒影视")!!.enabled, "更新配置不能把用户关掉的源重新打开")
        assertTrue(
            repo.find("嘀嗒影视")!!.arguments.searchConfig.searchUrl.contains("dadi.test"),
            "但内容要真的换成订阅里的那一份（停用不等于拒绝更新）",
        )

        // 正面对照：新导入的源默认是启用的（别把「保住已停用」做成「导进来全是停用」）
        val fresh = svc.importSelected(
            url = "https://sub.example.test/css.json", text = null, names = listOf("稀饭动漫"),
        )
        assertEquals(listOf("稀饭动漫"), fresh.added)
        assertTrue(repo.find("稀饭动漫")!!.enabled)
    }

    @Test
    fun `bare array and single source shapes are accepted`() = runTest {
        val bareArray = mediaSourcesJson()
        val (svcA, _) = service(bareArray)
        assertEquals(3, svcA.preview(url = "https://sub.example.test/css.json", text = null).candidates.size)

        val single = """{"factoryId":"web-selector","version":2,"arguments":{"name":"单文件源","searchConfig":{"searchUrl":"https://one.test/s"}}}"""
        val (svcB, repoB) = service(single)
        val previewB = svcB.preview(url = "https://sub.example.test/css.json", text = null)
        assertEquals(listOf("单文件源"), previewB.candidates.map { it.name })
        assertEquals(
            listOf("单文件源"),
            svcB.importSelected(url = "https://sub.example.test/css.json", text = null, names = listOf("单文件源")).added,
        )
        assertEquals(1, repoB.list().size)
    }

    @Test
    fun `mediaSources key without the exported wrapper is accepted`() = runTest {
        val (svc, _) = service("""{"mediaSources": ${mediaSourcesJson()}}""")
        assertEquals(3, svc.preview(url = "https://sub.example.test/css.json", text = null).candidates.size)
    }

    /** 把聚合订阅里的 mediaSources 数组单独取出来（用于构造「裸数组」「少一层包装」两种形态） */
    private fun mediaSourcesJson(): String =
        Json.parseToJsonElement(aggregate)
            .jsonObject["exportedMediaSourceDataList"]!!.jsonObject["mediaSources"]!!
            .jsonArray.joinToString(",", "[", "]")

    /** 订阅里第 index 条的原始 JSON */
    private fun entryJson(index: Int): String =
        Json.parseToJsonElement(aggregate)
            .jsonObject["exportedMediaSourceDataList"]!!.jsonObject["mediaSources"]!!
            .jsonArray[index].toString()

    @Test
    fun `inline text works when the subscription site is down`() = runTest {
        val (svc, _) = service("不会被用到")
        assertEquals(3, svc.preview(url = null, text = aggregate).candidates.size)
    }

@Test
    fun `subscription redirect to an internal address is refused per hop`() = runTest {
        // 订阅地址由用户填、服务端去取，这和 /api/proxy 是同一个 SSRF 面。
        // 只校验入口地址、然后让 HTTP 客户端自己跟 302，等于让订阅站把我们送进内网
        val redirect = StubFetcher(
            routes = mapOf("https://sub.example.test/css.json" to aggregate),
            redirects = mapOf("https://sub.example.test/css.json" to "http://127.0.0.1:8080/api/health"),
        )
        val repo = SelectorRepository(Files.createTempDirectory("animeko-sub-redir"))
        val svc = SubscriptionService(redirect, repo, allowPrivateTargets = false)
        assertFailsWith<UrlGuard.Blocked> { svc.preview(url = "https://sub.example.test/css.json", text = null) }
        assertTrue(
            redirect.requested.any { it.startsWith("http://127.0.0.1") }.not(),
            "被拦下的那一跳根本不该发出去",
        )
    }

    @Test
    fun `subscription redirect chain that stays public is followed`() = runTest {
        val fetcher = StubFetcher(
            routes = mapOf("https://mirror.example.test/css.json" to aggregate),
            redirects = mapOf("https://sub.example.test/css.json" to "https://mirror.example.test/css.json"),
        )
        val repo = SelectorRepository(Files.createTempDirectory("animeko-sub-chain"))
        val svc = SubscriptionService(fetcher, repo, allowPrivateTargets = false)
        assertEquals(3, svc.preview(url = "https://sub.example.test/css.json", text = null).candidates.size)
    }

    @Test
    fun `redirect loops stop instead of burning requests`() = runTest {
        val fetcher = StubFetcher(
            routes = emptyMap(),
            redirects = mapOf("https://loop.test/a" to "https://loop.test/b", "https://loop.test/b" to "https://loop.test/a"),
        )
        val repo = SelectorRepository(Files.createTempDirectory("animeko-sub-loop"))
        val svc = SubscriptionService(fetcher, repo, allowPrivateTargets = false)
        val e = assertFailsWith<com.animeko.web.http.UpstreamException> {
            svc.preview(url = "https://loop.test/a", text = null)
        }
        assertTrue(e.message.orEmpty().contains("重定向"), e.message.orEmpty())
        assertTrue(fetcher.requested.size <= 7, "跳数必须封在上限内：${fetcher.requested.size}")
    }

    @Test
    fun `private subscription urls are refused`() = runTest {
        val (svc, _) = service(aggregate)
        // 订阅地址由用户填、由服务端去取，和 /api/proxy 是同一个 SSRF 面：
        // 不校验的话「导入订阅」就成了打内网/云元数据接口的跳板
        assertFailsWith<UrlGuard.Blocked> { svc.preview(url = "http://127.0.0.1:8080/api/health", text = null) }
        assertFailsWith<UrlGuard.Blocked> { svc.preview(url = "http://169.254.169.254/latest/meta-data/", text = null) }
        assertFailsWith<UrlGuard.Blocked> { svc.preview(url = "file:///etc/passwd", text = null) }
        assertFailsWith<UrlGuard.Blocked> {
            svc.importSelected(url = "http://localhost:8080/api/data", text = null, names = listOf("稀饭动漫"))
        }
    }

    @Test
    fun `nothing to do when inputs are missing`() = runTest {
        val (svc, _) = service(aggregate)
        assertFailsWith<IllegalArgumentException> { svc.preview(url = "  ", text = "") }
        assertFailsWith<IllegalArgumentException> {
            svc.importSelected(url = "https://sub.example.test/css.json", text = null, names = emptyList())
        }
    }

    @Test
    fun `non json body is rejected with a readable message`() = runTest {
        // 订阅站挂掉时最常见的返回就是一张 HTML 错误页：必须明确说「不是 JSON」，
        // 而不是让用户对着「没有 searchUrl」之类的误导性结论排查
        for (body in listOf("<html>订阅站挂了</html>", "{半截", "")) {
            val (svc, _) = service(body)
            val e = assertFailsWith<IllegalArgumentException> {
                svc.preview(url = "https://sub.example.test/css.json", text = null)
            }
            assertTrue(e.message.orEmpty().contains("JSON"), "$body -> ${e.message}")
        }
    }

    @Test
    fun `an entry that appears twice only imports once`() = runTest {
        val dup = """{"mediaSources": [${entryJson(0)},${entryJson(0)}]}"""
        val (svc, _) = service(dup)
        val preview = svc.preview(url = "https://sub.example.test/css.json", text = null)
        assertEquals(1, preview.candidates.size)
        assertTrue(preview.rejected.any { it.contains("重名") }, preview.rejected.toString())
    }

    @Test
    fun `import with the preview fingerprint goes through`() = runTest {
        val (svc, _) = service(aggregate)
        val preview = svc.preview(url = "https://sub.example.test/css.json", text = null)
        val result = svc.importSelected(
            url = "https://sub.example.test/css.json",
            text = null,
            names = listOf("稀饭动漫"),
            fingerprint = preview.fingerprint,
        )
        assertEquals(listOf("稀饭动漫"), result.added)
    }

    @Test
    fun `import refuses when the subscription changed after the preview`() = runTest {
        // 浏览器里用户按预览勾了名，服务端导入时订阅已被站点更新：
        // 拿着旧指纹必须拒绝，而不是按一份刚拉到的、和用户看过的不一样的清单导入
        val (svc, _) = service(aggregate)
        val preview = svc.preview(url = "https://sub.example.test/css.json", text = null)

        // 同一地址，内容已经变了（站点更新了订阅）
        val mutated = aggregate.replace("稀饭动漫", "改过名的动漫")
        val (svcNow, _) = service(mutated)
        val fresh = svcNow.preview(url = "https://sub.example.test/css.json", text = null)
        assertTrue(
            preview.fingerprint != fresh.fingerprint,
            "两次内容不同指纹必须不同，否则这个校验没有意义",
        )

        assertFailsWith<IllegalArgumentException> {
            svcNow.importSelected(
                url = "https://sub.example.test/css.json",
                text = null,
                names = listOf("改过名的动漫"),
                fingerprint = preview.fingerprint,
            )
        }
    }

    @Test
    fun `auto import keeps a source the user switched off`() = runTest {
        // 界面上关掉一个源，一定是试过之后判定它不可用；
        // 自动导入若每次重启都把它打开，等于替用户重做他拒绝过的决定
        val (svc, repo) = service(aggregate, existing("嘀嗒影视"))
        assertTrue(repo.setEnabled("嘀嗒影视", false))

        // 「正则有害的源」结构合法但正则编译不过，save 会拒——只记日志，不影响其余
        val report = svc.autoImport(listOf("https://sub.example.test/css.json"), emptySet())

        assertTrue(report.single().endsWith("→ 2/3 个"), report.toString())
        assertFalse(repo.find("嘀嗒影视")!!.enabled, "已关掉的源必须保持关闭")
        assertTrue(repo.find("稀饭动漫")!!.enabled, "新导入的源默认启用")
    }

    @Test
    fun `auto import honours the tier whitelist`() = runTest {
        val (svc, repo) = service(aggregate)
        svc.autoImport(listOf("https://sub.example.test/css.json"), setOf("0"))

        val names = repo.list().map { it.arguments.name }
        assertTrue("稀饭动漫" in names, names.toString())
        assertFalse("嘀嗒影视" in names, "tier 1 不在白名单里，不该导进来")
    }

    @Test
    fun `a dead subscription url does not sink the other urls`() = runTest {
        // 一个订阅站挂了，只会少一批源，不能连带第二个订阅也不导入
        val repo = SelectorRepository(Files.createTempDirectory("animeko-sub"))
        val fetcher = StubFetcher(mapOf("https://sub.example.test/css.json" to aggregate))
        val svc = SubscriptionService(fetcher, repo, allowPrivateTargets = false)

        val report = svc.autoImport(
            listOf("https://gone.example.test/a.json", "https://sub.example.test/css.json"),
            emptySet(),
        )
        assertTrue(report[0].endsWith("→ 失败 个"), report.toString())
        assertTrue(report[1].endsWith("→ 2/3 个"), report.toString())
        assertEquals(2, repo.list().size)
    }
}
