package com.animeko.web

import com.animeko.web.config.AppConfig
import com.animeko.web.http.Fetcher
import com.animeko.web.http.HttpResult
import com.animeko.web.http.StreamResult
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * module() 里那几条「选哪个客户端」的接线。
 *
 * 为什么单独开一个类：这类分支在其余用例里永远命中不到 —— 所有测试都注入
 * fetcherOverride（为了不真打外网），于是 Bangumi 用的那个客户端在测试里
 * 从来没被真正创建过。线上那次的症状是「搜索页永远没有 Bangumi 结果，
 * 而手动 curl 镜像是通的」（社区镜像要 13~25 秒，被抓取侧 20 秒的超时掐死），
 * 当时整套用例也是全绿的。
 */
class MainWiringTest {

    private class Marker(val label: String) : Fetcher {
        override suspend fun fetch(url: String, headers: Map<String, String>, followRedirects: Boolean): HttpResult =
            throw UnsupportedOperationException(label)

        override suspend fun post(url: String, body: String, headers: Map<String, String>, contentType: String): HttpResult =
            throw UnsupportedOperationException(label)

        override suspend fun fetchStream(url: String, headers: Map<String, String>, followRedirects: Boolean): StreamResult =
            throw UnsupportedOperationException(label)
    }

    private fun cfg(request: Long, bangumi: Long) = AppConfig(
        dataDir = Files.createTempDirectory("animeko-wiring"),
        frontendDir = "definitely-not-exist",
        requestTimeoutMs = request,
        bangumiTimeoutMs = bangumi,
    )

    @Test
    fun `an injected fetcher is used for bangumi as is`() {
        val stub = Marker("stub")
        var created = 0
        val got = bangumiFetcherFor(cfg(20_000, 45_000), stub, stub) { created++; Marker("new") }
        assertSame(stub, got)
        assertEquals(0, created, "测试注入替身时不该再开一个真客户端")
    }

    @Test
    fun `bangumi reuses the shared client when both timeouts are the same`() {
        val shared = Marker("shared")
        var created = 0
        val got = bangumiFetcherFor(cfg(20_000, 20_000), shared, null) { created++; Marker("new") }
        assertSame(shared, got, "超时一样就别多开一个连接池")
        assertEquals(0, created)
    }

    @Test
    fun `bangumi gets its own client built on its own timeout`() {
        // 这一格是那起事故的正面防线：新客户端拿到的必须是 bangumiTimeoutMs。
        // 把两个变量抄反（或干脆复用同一个 fetcher），全套 API 用例照样绿，
        // 因为它们没有一个真的在打镜像。
        val shared = Marker("shared")
        var seen: Long = -1
        val replacement = Marker("bangumi")
        val got = bangumiFetcherFor(cfg(20_000, 45_000), shared, null) { t -> seen = t; replacement }
        assertSame(replacement, got, "两个超时不同时必须另开客户端")
        assertEquals(45_000L, seen, "新客户端要用 Bangumi 自己的超时，不是抓取那个")
    }

    @Test
    fun `the two timeouts are separate knobs`() {
        // 配置层面再确认一次：ANIMEKO_BANGUMI_TIMEOUT_MS 与 ANIMEKO_REQUEST_TIMEOUT_MS
        // 是两个独立变量。上面三条用例都在依赖这个前提，前提本身也该有一条断言。
        val c = cfg(1234, 5678)
        assertEquals(1234L, c.requestTimeoutMs)
        assertEquals(5678L, c.bangumiTimeoutMs)
        assertTrue(c.bangumiTimeoutMs != c.requestTimeoutMs)
    }
}
