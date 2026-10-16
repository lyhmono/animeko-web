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
}
