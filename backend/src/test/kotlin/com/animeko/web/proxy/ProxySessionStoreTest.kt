package com.animeko.web.proxy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 时间通过构造参数注入：早先的用例靠 Thread.sleep 对 300ms TTL 断言，
 * 一次 GC 或调度抖动就会翻车；改成驱动虚拟时钟后既确定又能验证淘汰顺序。
 */
class ProxySessionStoreTest {

    private var clock = 1_000_000L

    private fun store(ttlMs: Long = 6L * 60 * 60 * 1000, maxEntries: Int = 4096) =
        ProxySessionStore(ttlMs, maxEntries) { clock }

    private fun mint(s: ProxySessionStore, url: String = MEDIA) =
        s.put(url, "UA/1.0", "https://v.example/watch", "q=1080")

    @Test
    fun `put and grant back the header set`() {
        val s = store()
        val sid = mint(s)
        assertTrue(sid.matches(Regex("^[0-9a-f]{32}$")), "sid 应是不可猜的十六进制：$sid")
        val grant = s.grant(sid, MEDIA)
        assertEquals(ProxySessionStore.Kind.GRANTED, grant.kind)
        assertEquals("UA/1.0", grant.headers["User-Agent"])
        assertEquals("https://v.example/watch", grant.headers["Referer"])
        assertEquals("q=1080", grant.headers["Cookie"])
    }

    @Test
    fun `blank header set allocates nothing and an empty sid grants no headers`() {
        val s = store()
        assertEquals("", s.put(MEDIA, "", "", ""))
        assertEquals(0, s.size)
        val grant = s.grant("", "https://anything.example/x.ts")
        assertEquals(ProxySessionStore.Kind.GRANTED, grant.kind)
        assertTrue(grant.headers.isEmpty())
    }

    @Test
    fun `unknown sid is reported expired rather than host-not-allowed`() {
        val s = store()
        assertEquals(ProxySessionStore.Kind.EXPIRED, s.grant("a".repeat(32), MEDIA).kind)
    }

    @Test
    fun `sid cannot be replayed against another host`() {
        // sid 是能力令牌。不绑主机的话「拿到一个 sid」就等于
        // 「带着受害者的 Cookie 去访问攻击者指定的任意地址」。
        val s = store()
        val sid = mint(s)
        val attacker = "https://attacker.example/log?x=1"
        val grant = s.grant(sid, attacker)
        assertEquals(ProxySessionStore.Kind.HOST_NOT_ALLOWED, grant.kind)
        assertTrue(grant.headers.isEmpty(), "被拒时不该交出任何凭据")
    }

    @Test
    fun `a host URI cannot parse still gets bound instead of releasing credentials`() {
        // 回归：grant() 写的是 `host != null && host !in allowedHosts`，
        // 于是 URI.host 解析不出主机名的地址（带下划线的主机名，DNS 里真实存在）
        // 会跳过绑定检查、直接把源站 Cookie 交出去 —— 与它上面那行注释正好相反。
        val s = store()
        val sid = s.put("https://v_1.example/index.m3u8", "UA/1.0", "https://v_1.example/watch", "")
        assertEquals(
            ProxySessionStore.Kind.GRANTED,
            s.grant(sid, "https://v_1.example/seg.ts").kind,
            "同一个带下划线的主机应当正常放行",
        )
        val evil = s.grant(sid, "https://evil.example/log?x=1")
        assertEquals(ProxySessionStore.Kind.HOST_NOT_ALLOWED, evil.kind, "主机绑定不能因为解析不出名字就形同虚设")
        assertTrue(evil.headers.isEmpty(), "被拒时不该交出任何凭据")
    }

    @Test
    fun `playlist host registration is bounded per session`() {
        // 真实清单只有 1~3 个主机。不设上界的话，一份 4MB 清单能往单个会话里
        // 塞进上万个主机字符串，并且每次重拉清单都要全量正则 + 逐条解码
        val s = store()
        val sid = mint(s)
        val playlist = (1..500).joinToString("\n") {
            "/api/proxy?url=${java.net.URLEncoder.encode("https://h$it.example/seg.ts", "UTF-8")}&sid=$sid"
        }
        s.allowPlaylistHosts(sid, playlist)
        // 前 64 个主机可用
        assertEquals(
            ProxySessionStore.Kind.GRANTED,
            s.grant(sid, "https://h1.example/seg.ts").kind,
        )
        // 超出的不再扩权
        assertEquals(
            ProxySessionStore.Kind.HOST_NOT_ALLOWED,
            s.grant(sid, "https://h499.example/seg.ts").kind,
            "清单主机数超过上界后不该继续登记",
        )
    }

    @Test
    fun `an address with no extractable authority is refused`() {
        val s = store()
        val sid = mint(s)
        assertEquals(ProxySessionStore.Kind.HOST_NOT_ALLOWED, s.grant(sid, "https:///x.ts").kind)
        assertEquals(ProxySessionStore.Kind.HOST_NOT_ALLOWED, s.grant(sid, "not-a-url").kind)
    }

    @Test
    fun `playlist hosts registered by the server become usable`() {
        // 真实源站常见清单在一个域、分片在另一个 CDN 域：绑定不能把播放弄坏
        val s = store()
        val sid = mint(s)
        val cdn = "https://cdn.other.example/seg1.ts"
        assertEquals(ProxySessionStore.Kind.HOST_NOT_ALLOWED, s.grant(sid, cdn).kind)
        s.allowPlaylistHosts(
            sid,
            "#EXTINF:10,\n/api/proxy?url=${java.net.URLEncoder.encode(cdn, "UTF-8")}&sid=$sid\n",
        )
        assertEquals(ProxySessionStore.Kind.GRANTED, s.grant(sid, cdn).kind)
    }

    @Test
    fun `allowPlaylistHosts cannot widen an unknown or empty session`() {
        val s = store()
        val sid = mint(s)
        s.allowPlaylistHosts("b".repeat(32), "/api/proxy?url=https%3A%2F%2Fevil.example%2Fx")
        s.allowPlaylistHosts("", "/api/proxy?url=https%3A%2F%2Fevil.example%2Fx")
        assertEquals(ProxySessionStore.Kind.HOST_NOT_ALLOWED, s.grant(sid, "https://evil.example/x").kind)
    }

    @Test
    fun `expiry and sliding renewal follow the clock`() {
        val s = store(ttlMs = 1_000)
        val sid = mint(s)
        clock += 900
        assertEquals(ProxySessionStore.Kind.GRANTED, s.grant(sid, MEDIA).kind, "使用中的会话应被续期")
        clock += 900
        assertEquals(ProxySessionStore.Kind.GRANTED, s.grant(sid, MEDIA).kind, "距上次使用未超 TTL 不该失效")
        clock += 1_001
        assertEquals(ProxySessionStore.Kind.EXPIRED, s.grant(sid, MEDIA).kind, "超过 TTL 必须失效")
    }

    @Test
    fun `ids across independent stores do not repeat and are not counters`() {
        val ids = (1..50).map { mint(store()) }
        assertEquals(50, ids.toSet().size)
        assertTrue(
            ids.map { it.take(8) }.toSet().size > 40,
            "sid 前缀过于规律，像计数器而不是随机令牌：${ids.take(4)}",
        )
    }

    @Test
    fun `eviction drops the oldest sessions and keeps the newest`() {
        val s = store(ttlMs = 10_000, maxEntries = 4)
        val oldest = mint(s)
        clock += 10
        val second = mint(s)
        clock += 10
        mint(s)
        clock += 10
        mint(s)
        clock += 10
        val newest = mint(s)
        assertTrue(s.size <= 4, "会话库必须有上界，实际 ${s.size}")
        // 只断言数量会漏掉「淘汰了错的会话」这种实现，这里钉住顺序
        assertEquals(ProxySessionStore.Kind.EXPIRED, s.grant(oldest, MEDIA).kind)
        assertEquals(ProxySessionStore.Kind.EXPIRED, s.grant(second, MEDIA).kind)
        assertEquals(ProxySessionStore.Kind.GRANTED, s.grant(newest, MEDIA).kind)
    }

    @Test
    fun `granted header map cannot be mutated`() {
        val s = store()
        val sid = mint(s)
        val headers = s.grant(sid, MEDIA).headers
        try {
            (headers as MutableMap<String, String>).remove("Cookie")
            fail("会话内的请求头应当是只读的")
        } catch (expected: UnsupportedOperationException) {
            // 正是想要的行为
        }
        assertEquals("q=1080", s.grant(sid, MEDIA).headers["Cookie"])
    }

    private companion object {
        const val MEDIA = "https://cdn.example.com/hls/index.m3u8"
    }
}
