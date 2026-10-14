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
