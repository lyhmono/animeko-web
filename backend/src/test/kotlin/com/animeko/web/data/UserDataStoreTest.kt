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

    @Test
    fun `a corrupt main file recovers from the backup instead of reporting empty`() {
        // 回归：.bak 一直在写，却从来没有一侧去读它。
        // 掉电/中断把主文件只剩半个时，读侧返回 {} → 前端认为「文档是空的」→
        // 用户下一次点收藏就把整份数据覆盖掉，而完好的一份就在同一个目录里。
        val (s, file) = store()
        s.write("""{"favorites":[{"id":1},{"id":2}]}""", null)
        s.write("""{"favorites":[{"id":3}]}""", s.read().etag)
        // 模拟第二次写入中途被打断：主文件成了半截 JSON，.bak 里是上一份完整数据
        Files.writeString(file, """{"favorites":[{"id":3}""")

        val restored = s.read()
        assertTrue(restored.text.contains("\"id\":1"), "必须从 .bak 恢复，实际读到：${restored.text}")
        assertEquals("""{"favorites":[{"id":1},{"id":2}]}""", Files.readString(file), "恢复要写回主文件，不能只在内存里给")
        assertTrue(Files.exists(file.resolveSibling("${file.fileName}.bad")), "坏掉的那份要留证据")

        // 恢复出来的 etag 必须对应真实内容，否则客户端的条件写会永远 409
        assertEquals(restored.etag, s.read().etag)
        s.write("""{"favorites":[{"id":1},{"id":2},{"id":9}]}""", restored.etag)
        assertTrue(s.read().text.contains("\"id\":9"))
    }

    @Test
    fun `a missing main file also recovers from the backup`() {
        val (s, file) = store()
        s.write("""{"v":1}""", null)
        s.write("""{"v":2}""", s.read().etag)
        Files.delete(file)
        assertEquals("""{"v":1}""", s.read().text, "主文件没了也不该把备份当不存在")
    }

    @Test
    fun `a corrupt backup degrades to empty rather than restoring garbage`() {
        val (s, file) = store()
        s.write("""{"v":1}""", null)
        Files.writeString(file, "{半截")
        Files.writeString(file.resolveSibling("${file.fileName}.bak"), "{也半截")
        assertEquals("{}", s.read().text)
    }

    @Test
    fun `If-Match follows HTTP semantics not literal string compare`() {
        // 回归：以前把整个头 trim('"') 后与当前指纹逐字比较，于是下面这些
        // 合法写法全部恒定 409 —— 照 HTTP 惯例写的脚本客户端永远写不进去
        val (s, _) = store()
        val first = s.write("""{"v":1}""", null)

        // 单个强标签
        s.write("""{"v":2}""", "\"${first.etag}\"")
        val second = s.read()

        // 多值里含当前标签：任一匹配即可
        s.write("""{"v":3}""", "\"deadbeef\", \"${second.etag}\"")
        val third = s.read()

        // * = 资源存在就允许写
        s.write("""{"v":4}""", "*")

        // 大小写与空白不该影响判定
        s.write("""{"v":5}""", "  \"${s.read().etag}\"  ")

        // 过期标签仍然必须拒
        assertFailsWith<UserDataStore.Stale> { s.write("""{"v":6}""", "\"${first.etag}\"") }
        // 弱标签不参与强比较
        assertFailsWith<UserDataStore.Stale> { s.write("""{"v":7}""", "W/\"${s.read().etag}\"") }
        // 全是弱标签/全不匹配的多值也要拒
        assertFailsWith<UserDataStore.Stale> { s.write("""{"v":8}""", "\"a\", W/\"${s.read().etag}\"") }
        assertEquals("5", doc(s.read().text)["v"]?.toString()?.trim('"'), "前面 4 次条件写都应生效，最后一次是 v5")
    }

    @Test
    fun `star does not match when the document does not exist yet`() {
        // If-Match: * 的含义是「只在资源已存在时更新」，首次创建该用不带 If-Match 的写
        val (s, file) = store()
        assertFailsWith<UserDataStore.Stale> { s.write("""{"v":1}""", "*") }
        assertFalse(Files.exists(file), "被拒时不该留下文件")
        s.write("""{"v":1}""", null)
        s.write("""{"v":2}""", "*") // 现在存在了，* 应放行
        assertEquals("""{"v":2}""", s.read().text)
    }

    @Test
    fun `reading while writing never quarantines the freshly committed file`() {
        // 回归：read() 不在写锁里，而它遇到坏文件会把主文件 move 成 .bad。
        // 与并发 write() 的非原子回退路径交错时，读侧能把写侧刚提交好的文件搬走，
        // 盘上就此没有文档了，而 write() 还回了 200。
        val (s, file) = store()
        s.write("""{"seed":true}""", null)
        var lost = 0
        repeat(200) {
            val writer = Thread { s.write("""{"tick":$it}""", null) }
            val reader = Thread { runCatching { s.read() } }
            writer.start(); reader.start()
            writer.join(); reader.join()
            if (!Files.isRegularFile(file)) lost++
        }
        assertEquals(0, lost, "有 $lost 次读操作把主文档弄没了")
        assertTrue(s.read().text.contains("tick"))
    }
}
