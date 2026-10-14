package com.animeko.web.proxy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ProxySessionStoreTest {

    @Test
    fun `put and read back the header set`() {
        val s = ProxySessionStore()
        val sid = s.put("UA/1.0", "https://v.example/watch", "q=1080")
        assertTrue(sid.matches(Regex("^[0-9a-f]{32}$")), "sid 应是不可猜的十六进制：$sid")
        val headers = assertNotNull(s.headers(sid))
        assertEquals("UA/1.0", headers["User-Agent"])
        assertEquals("https://v.example/watch", headers["Referer"])
        assertEquals("q=1080", headers["Cookie"])
    }

    @Test
    fun `blank header set allocates nothing`() {
        val s = ProxySessionStore()
        assertEquals("", s.put("", "", ""))
        assertEquals(0, s.size)
        // 空 sid 表示「无额外头」，不应该被当成过期
        assertEquals(emptyMap(), s.headers(""))
    }

    @Test
    fun `unknown sid is distinguishable from no session`() {
        val s = ProxySessionStore()
        assertNull(s.headers("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
    }

    @Test
    fun `ids do not repeat`() {
        val s = ProxySessionStore()
        val ids = (1..200).map { s.put("UA/$it", "", "") }.toSet()
        assertEquals(200, ids.size)
    }

    @Test
    fun `expiry is enforced and use slides it forward`() {
        val short = ProxySessionStore(ttlMs = 300)
        val sid = short.put("UA", "R", "C")
        assertNotNull(short.headers(sid))
        Thread.sleep(420)
        assertNull(short.headers(sid), "超过 TTL 必须失效")

        val sliding = ProxySessionStore(ttlMs = 500)
        val sid2 = sliding.put("UA", "R", "C")
        var elapsed = 0L
        while (elapsed < 1400) {
            Thread.sleep(200)
            elapsed += 200
            assertNotNull(sliding.headers(sid2), "持续使用中的会话不应在第 ${elapsed}ms 被掐断")
        }
    }

    @Test
    fun `store size stays bounded under churn`() {
        val s = ProxySessionStore(ttlMs = 60_000, maxEntries = 8)
        repeat(40) { s.put("UA/$it", "", "") }
        assertTrue(s.size <= 8, "会话库必须有上界，否则长时间运行会吃满内存：${s.size}")
    }

    @Test
    fun `returned header map cannot be mutated by callers`() {
        // proxyVideo 会在跨主机重定向时 remove("Cookie")；
        // 若它拿到的是可变的共享 map，一次跳转就会永久毁掉这个会话的后续分片
        val s = ProxySessionStore()
        val sid = s.put("UA", "R", "C")
        val headers = assertNotNull(s.headers(sid))
        try {
            (headers as MutableMap<String, String>).remove("Cookie")
            fail("会话内的请求头应当是只读的")
        } catch (expected: UnsupportedOperationException) {
            // 正是想要的行为
        }
        assertEquals("C", assertNotNull(s.headers(sid))["Cookie"])
    }
}
