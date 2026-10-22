package com.animeko.web.selector

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelectorRepositoryTest {

    private fun repo() = SelectorRepository(Files.createTempDirectory("animeko-repo"))

    private fun args(name: String) = SelectorSourceArguments(name = name)

    @Test
    fun `an unavailable data dir does not stop startup and writes fail loudly`() {
        // 只读挂载时入口脚本承诺「服务继续运行，给只读浏览留活路」，
        // 而 init 里的 createDirectories 原先会让进程启动即崩（实测 AccessDeniedException）。
        val dir = Files.createTempDirectory("animeko-repo-ro")
        val blocker = dir.resolve("not-a-dir")
        Files.writeString(blocker, "x")
        val repo = SelectorRepository(blocker.resolve("selectors")) // 构造不能抛
        assertEquals(emptyList(), repo.list())
        val e = assertFailsWith<IllegalArgumentException> { repo.save(args("源")) }
        assertTrue(e.message.orEmpty().isNotBlank(), e.message.orEmpty())
    }

    @Test
    fun `save list find round trip`() {
        val r = repo()
        r.save(args("测试源"))
        assertEquals(1, r.list().size)
        assertEquals("测试源", r.find("测试源")?.arguments?.name)
        r.find("测试源")?.let { assertEquals(true, it.enabled) }
    }

    @Test
    fun `colliding sanitized names are rejected instead of silently overwriting`() {
        // 文件名会把空格等标点压成 _，「我的 源」与「我的_源」落到同一个文件；
        // 静默覆盖等于导入第二个源时把第一个删掉
        val r = repo()
        r.save(args("我的 源"))
        val e = assertFailsWith<IllegalArgumentException> { r.save(args("我的_源")) }
        assertTrue(e.message.orEmpty().contains("我的_源"), e.message.orEmpty())
        // Kotlin 的 "$arguments.name" 会 dump 整个数据类而不是取字段，这里必须用 ${...}
        assertTrue(
            !e.message.orEmpty().contains("SelectorSourceArguments"),
            "错误消息不该把整个配置对象吐给用户：${e.message}",
        )
        assertEquals(1, r.list().size)
        assertEquals("我的 源", r.list().single().arguments.name)
    }

    /**
     * 并发导入两个「消毒后同名」的源。旧实现是 read-then-write 且没有互斥：
     * 两个线程都能通过冲突检查，后写的静默盖掉前写的，界面只看到导入成功。
     *
     * 说清楚这个用例的力度边界：竞态本身是概率性的，跑不跑得出来取决于调度；
     * 它锁住的是**不变量**（要么只有一个成功、落盘必须是完整可解析的配置），
     * 而不是「一定触发过竞态」。
     */
    @Test
    fun `concurrent colliding saves keep the invariant`() {
        repeat(60) { round ->
            val r = repo()
            val results = java.util.concurrent.CountDownLatch(2)
            val oks = java.util.concurrent.ConcurrentLinkedQueue<String>()
            val fails = java.util.concurrent.ConcurrentLinkedQueue<String>()
            listOf("并发 源", "并发_源").map { name ->
                Thread {
                    try {
                        r.save(args(name))
                        oks.add(name)
                    } catch (e: IllegalArgumentException) {
                        fails.add(name)
                    } finally {
                        results.countDown()
                    }
                }.start()
            }
            while (results.count > 0) Thread.sleep(1)
            val stored = r.list()
            assertTrue(
                stored.size <= 1,
                "第 $round 轮：两个消毒同名源都写成功了（${oks}），冲突检查被并发绕过",
            )
            // 落盘的必须是完整可解析的一份配置，不能是半个文件
            assertEquals(1, stored.size, "第 $round 轮：至少应有一份成功落盘且可读")
            assertTrue(oks.contains(stored.single().arguments.name))
        }
    }

    /**
     * 写入时先落 `<名字>.json.tmp` 再 rename，所以名字上限必须把 `.tmp` 的 4 字节算进去。
     * 之前只按正式文件名判：82 个汉字能存、83 个汉字通过校验却在 open 临时文件时
     * ENAMETOOLONG，用户看到的是一句「写入失败」，看不出是名字太长。
     */
    @Test
    fun `filename limit accounts for the temporary suffix`() {
        val r = repo()
        val fits = "汉".repeat(82)
        r.save(args(fits))
        assertEquals(1, r.list().size)

        val tooLong = "汉".repeat(83)
        val e = assertFailsWith<IllegalArgumentException> { r.save(args(tooLong)) }
        assertTrue(e.message.orEmpty().contains("临时文件名"), e.message.orEmpty())
        assertTrue(e.message.orEmpty().contains("字节"), e.message.orEmpty())
        assertEquals(1, r.list().size, "被拒的名字不该留下任何文件")
    }

    /** 原子写不该在工作目录里留下临时文件；残留会让下一次导入看到一堆「没有的源」 */
    @Test
    fun `saving leaves no temporary files behind`() {        val dir = Files.createTempDirectory("animeko-repo-tmp")
        val r = SelectorRepository(dir)
        repeat(5) { r.save(args("源 $it")) }
        r.setEnabled("源 0", false)
        r.delete("源 4")
        val leftovers = Files.list(dir).use { it.map { p -> p.fileName.toString() }.toList() }
            .filter { it.endsWith(".tmp") || it.endsWith(".bak") }
        assertTrue(leftovers.isEmpty(), "残留了中间文件：$leftovers")
    }

    /**
     * 落盘失败必须报错，不能「返回成功但什么都没写」。
     * 这里用一个占位的目录冒充目标文件：rename 一定失败，走的是 IOException 那一格。
     *
     * 说清覆盖边界（别再让注释假装它测了别的东西）：网络盘那条分支
     * （ATOMIC_MOVE 抛 AtomicMoveNotSupportedException 后退回普通 move）在本地文件系统上
     * **不会被触发**，所以本用例钉不住那一格——那一格是靠代码形状保证的（失败照抛），
     * 不是靠这条测试。要真的覆盖它需要注入一个不支持原子 rename 的文件系统。
     */
    @Test
    fun `a destination that cannot be replaced fails loudly`() {
        val dir = Files.createTempDirectory("animeko-repo-ro")
        val r = SelectorRepository(dir)
        Files.createDirectories(dir.resolve("坏目标.json"))
        val e = assertFailsWith<IllegalArgumentException> { r.save(args("坏目标")) }
        assertTrue(e.message.orEmpty().contains("写入数据源文件失败"), e.message.orEmpty())
    }

    @Test
    fun `delete only removes the source it names`() {
        val r = repo()
        r.save(args("我的 源"))
        assertFalse(r.delete("我的_源"), "消毒后同名的另一条源不该被误删")
        assertTrue(r.delete("我的 源"))
        assertTrue(r.list().isEmpty())
        assertFalse(r.delete("不存在的源"))
    }

    @Test
    fun `invalid regex in config is rejected at import time`() {
        val r = repo()
        val bad = args("坏源").copy(
            searchConfig = SelectorSearchConfig(
                matchVideo = MatchVideoConfig(matchVideoUrl = "([unclosed"),
            ),
        )
        val e = assertFailsWith<IllegalArgumentException> { r.save(bad) }
        assertTrue(e.message.orEmpty().contains("正则"), e.message.orEmpty())
    }

    @Test
    fun `oversized config is rejected`() {
        val r = repo()
        val huge = "{" + "\"name\":\"巨\"," + "\"description\":\"" + "x".repeat(MAX + 10) + "\"}"
        assertFailsWith<IllegalArgumentException> { r.importJson(huge) }
    }

    @Test
    fun `non web-selector factory is rejected instead of stored as a dead source`() {
        // 社区订阅里混着 bt-selector：以前丢掉 factoryId 后会被当 web 源存下，
        // 列表里看着正常、搜索永远是 0 结果，用户完全无从下手
        val r = repo()
        val bt = """{"factoryId":"bt","version":1,"arguments":{"name":"BT源","searchConfig":{"searchUrl":"https://a.test/s"}}}"""
        val e = assertFailsWith<IllegalArgumentException> { r.importJson(bt) }
        assertTrue(e.message.orEmpty().contains("bt"), e.message.orEmpty())
        assertTrue(e.message.orEmpty().contains("web-selector"), e.message.orEmpty())
        // 裸 arguments（没有包装层）不受影响
        assertEquals("裸配置", r.importJson("""{"name":"裸配置","searchConfig":{"searchUrl":"https://a.test/s"}}""").name)
        assertEquals("示例", r.importJson("""{"factoryId":"web-selector","version":2,"arguments":{"name":"示例","searchConfig":{"searchUrl":"https://a.test/s"}}}""").name)
    }

    @Test
    fun `bare arguments json is accepted`() {
        val r = repo()
        val json = """{"name":"裸配置","searchConfig":{"searchUrl":"https://a.example.com/s?kw={keyword}"}}"""
        val args = r.importJson(json)
        assertEquals("裸配置", args.name)
        r.save(args)
        assertEquals(1, r.list().size)
    }

    private companion object {
        const val MAX = 200_000
    }
}
