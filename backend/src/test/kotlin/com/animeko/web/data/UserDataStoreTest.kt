package com.animeko.web.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class UserDataStoreTest {

    private fun store(maxBytes: Long = 512L * 1024): Pair<UserDataStore, Path> {
        val dir = Files.createTempDirectory("animeko-data")
        val file = dir.resolve("userdata.json")
        return UserDataStore(file, maxBytes) to file
    }

    private fun doc(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `missing file reads as an empty document with a stable etag`() {
        val (s, file) = store()
        val snapshot = s.read()
        assertEquals("{}", snapshot.text)
        assertFalse(Files.exists(file), "读操作不该凭空造文件")
        // 空文档的指纹必须稳定，且与「真的写了一份 {}」一致：
        // 否则客户端拿着首次读到的 etag 做条件写，永远都会撞 409
        assertEquals(snapshot.etag, s.read().etag)
        assertEquals(snapshot.etag, s.write("{}", null).etag)
    }

    @Test
    fun `round trip keeps the structure the client sent`() {
        val (s, file) = store()
        val body = """{"favorites":[{"id":41,"title":"孤独摇滚"}],"progress":{"41:3":1234.5},"n":3}"""
        val written = s.write(body, null)
        assertEquals(written.etag, s.read().etag, "写入返回的指纹必须就是当前版本")
        val back = doc(s.read().text)
        assertEquals("孤独摇滚", back["favorites"]!!.jsonArray[0].jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals(3, back["n"]!!.jsonPrimitive.content.toInt())
        assertTrue(Files.isRegularFile(file))
        // 临时文件必须被 rename 走，不能留在目录里
        assertFalse(Files.exists(file.resolveSibling("${file.fileName}.tmp")))
        s.write("""{"favorites":[]}""", written.etag)
        assertTrue(Files.exists(file.resolveSibling("${file.fileName}.bak")), "应保留上一份数据备份")
    }

    @Test
    fun `conditional write rejects a stale client instead of clobbering it`() {
        val (s, _) = store()
        val first = s.write("""{"favorites":[]}""", null)
        // 模拟两个标签页：都读到 first，另一个先写 succeeded
        val second = s.write("""{"favorites":[{"id":1}]}""", first.etag)
        assertNotEquals(first.etag, second.etag)
        // 手上还拿着 first.etag 的那一方必须被告知冲突，而不是静默把别人的收藏覆盖掉
        assertFailsWith<UserDataStore.Stale> { s.write("""{"favorites":[]}""", first.etag) }
        assertEquals("""{"favorites":[{"id":1}]}""", s.read().text, "冲突的那次写入不能落到盘上")
        // 不声明版本 = 明确要覆盖（curl、旧客户端）
        s.write("""{"cleared":true}""", null)
        assertTrue(s.read().text.contains("cleared"))
    }

    @Test
    fun `anything that is not a json object is refused`() {
        val (s, file) = store()
        for (bad in listOf("", "   ", "[1,2,3]", "\"文本\"", "123", "{oops", "null")) {
            assertFailsWith<IllegalArgumentException> { s.write(bad, null) }
        }
        assertFalse(Files.exists(file), "被拒绝的写入不能留下文件")
    }

    @Test
    fun `oversized document is refused instead of eating the disk`() {
        val (s, _) = store(maxBytes = 2_000)
        val big = """{"blob":"${"x".repeat(4_000)}"}"""
        val e = assertFailsWith<IllegalArgumentException> { s.write(big, null) }
        assertTrue(e.message.orEmpty().contains("上限"), e.message.orEmpty())
    }

    @Test
    fun `a corrupt file is quarantined and reading degrades to empty`() {
        val (s, file) = store()
        Files.createDirectories(file.parent)
        Files.writeString(file, "{半截 JSON")
        assertEquals("{}", s.read().text, "坏文件不能把整个页面打挂")
        assertFalse(Files.exists(file), "坏文件要挪走，否则下一次写入前的备份会盖掉证据")
        assertTrue(Files.exists(file.resolveSibling("${file.fileName}.bad")))
        s.write("""{"ok":true}""", null)
        assertTrue(s.read().text.contains("ok"))
    }

    @Test
    fun `backup never removes the live file`() {
        // 回归：实现曾把旧文件 move 成 .bak 再 rename 新的，
        // 两次操作之间被 kill 就没有 userdata.json 了（读的一侧不看 .bak）= 一次崩溃丢掉全部收藏
        val (s, file) = store()
        s.write("""{"v":1}""", null)
        s.write("""{"v":2}""", s.read().etag)
        assertTrue(Files.isRegularFile(file), "覆盖写之后当前文件必须还在")
        assertEquals("""{"v":2}""", s.read().text)
        assertEquals("""{"v":1}""", Files.readString(file.resolveSibling("${file.fileName}.bak")))
    }

    @Test
    fun `writes are serialized across threads and always leave a valid document`() {
        val (s, _) = store()
        val threads = (1..8).map { i ->
            Thread { repeat(20) { s.write("""{"writer":$i}""", null) } }.also { it.start() }
        }
        threads.forEach { it.join() }
        // 并发写最怕留下交错内容：读回来必须仍然是一个合法文档
        assertTrue(doc(s.read().text).containsKey("writer"))
    }
}
