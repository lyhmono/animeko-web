package com.animeko.web.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UrlGuardTest {

    private fun blocked(url: String): String =
        assertFailsWith<UrlGuard.Blocked> { UrlGuard.requireAllowed(url) }.message.orEmpty()

    @Test
    fun `rejects non-http schemes`() {
        assertTrue(blocked("file:///etc/passwd").contains("http"), "file 协议应被拒绝")
        assertTrue(blocked("gopher://10.0.0.1:111/x").contains("http"), "gopher 协议应被拒绝")
    }

    @Test
    fun `rejects loopback private and metadata addresses`() {
        listOf(
            "http://127.0.0.1:8080/api/selector/sources",
            "http://127.1/x",
            "http://2130706433/x",
            "http://10.1.2.3/",
            "http://192.168.1.1/admin",
            "http://172.16.0.9/",
            "http://169.254.169.254/latest/meta-data/iam/",
            "http://[::1]:8080/api/health",
            "http://[fc00::1]/x",
            "http://0.0.0.0/",
        ).forEach { url ->
            assertTrue(blocked(url).isNotBlank(), "$url 应当被拒绝")
        }
    }

    @Test
    fun `rejects reserved ranges that Java's isXxx helpers miss`() {
        // 回归：这些在旧清单下是**放行**的（实测表现为 20 秒连接超时，即真的去连了）。
        // Java 的 isAnyLocalAddress 只管精确的 0.0.0.0，不管整个 0.0.0.0/8；
        // 100.64.0.0/10 是运营商 NAT，机房里大量内部地址就落在这段。
        listOf(
            "http://100.64.0.1/x",
            "http://100.127.255.255/x",
            "http://0.1.2.3:8080/api/health",
            "http://0.255.255.255/x",
            "http://198.18.0.1/x",
            "http://198.19.255.255/x",
            "http://192.0.2.1/x",
            "http://198.51.100.7/x",
            "http://203.0.113.9/x",
            "http://[100::1]/x",
            "http://[2001:db8::1]/x",
            "http://[2002:c000:204::]/x",
        ).forEach { url ->
            assertTrue(blocked(url).isNotBlank(), "$url 属于保留地址段，应当被拒绝")
        }
    }

    @Test
    fun `reserved ranges stay blocked at the boundary and do not swallow public space`() {
        // 这条用例必须有**两侧**的断言。原先只在 `if (e is Blocked)` 里做断言，
        // 而这四个地址都不在保留段内、requireAllowed 根本不抛 ——
        // 于是整个用例一条断言都没执行（绿灯什么也没证明）。
        //
        // 外侧：紧邻保留段边界的公网地址，必须**放行**（不被顺手吞掉）。
        // 字面 IP 不会触发 DNS（100.63.255.255 之类直接由 IP 形态判定），
        // 所以这里可以直接断言返回值就是原串。
        listOf(
            "http://100.63.255.255/x",   // CGNAT 100.64.0.0/10 的下边界之外
            "http://100.128.0.1/x",      // 上边界之外
            "http://198.17.255.255/x",   // 198.18.0.0/15 之下
            "http://198.20.0.1/x",       // 之上
        ).forEach { url ->
            assertEquals(url, UrlGuard.requireAllowed(url), "$url 在保留段之外，不该被拦")
        }
        // 内侧：紧贴边界的保留地址，必须仍然被拦，且拦它的理由是「保留段」
        // （不是「无法解析」那一类的误伤）。边界写错一格（a1>=64 写成 63、
        // 198.18/19 少算一个）就是这一格要红的。
        listOf(
            "http://100.64.0.0/x" to "100.64.0.0",
            "http://100.127.255.255/x" to "100.127.255.255",
            "http://198.18.0.1/x" to "198.18.0.1",
            "http://198.19.255.255/x" to "198.19.255.255",
        ).forEach { (url, ip) ->
            val message = blocked(url)
            assertTrue(message.contains("内网/保留"), "$url 应按保留段被拒，实际原因：$message")
            assertTrue(message.contains(ip), "$url 的拒绝原因里应带上被拦的地址 $ip：$message")
        }
    }

    @Test
    fun `non-http scheme is rejected even when private targets are allowed`() {
        // allowPrivate 只放开内网地址，不该顺手把 file:// 之类的协议放行
        assertFailsWith<UrlGuard.Blocked> {
            UrlGuard.requireAllowed("file:///etc/passwd", allowPrivate = true)
        }
        assertFailsWith<UrlGuard.Blocked> {
            UrlGuard.requireNotLiteralInternal("gopher://10.0.0.1:111/x", allowPrivate = true)
        }
    }

    @Test
    fun `unusual but real hostnames are not rejected as missing host`() {
        // URI.getHost() 对含下划线的主机名返回 null（现实中的内网 NAS/个别 CDN 就这么命名），
        // 早期实现把它判成「URL 缺少主机名」，等于新增了一类打不开的数据源。
        val url = "https://media_nas.example.com/video/index.m3u8"
        assertEquals(url, UrlGuard.requireNotLiteralInternal(url))
        assertEquals(url, UrlGuard.requireAllowed(url))
        assertEquals("http://host_name:8080/a.m3u8", UrlGuard.requireNotLiteralInternal("http://host_name:8080/a.m3u8"))
    }

    @Test
    fun `userinfo cannot disguise the real host`() {
        assertFailsWith<UrlGuard.Blocked> { UrlGuard.requireAllowed("http://user@127.0.0.1/x") }
        // 反过来：127.0.0.1 放在 userinfo 段、真实主机是公网名，不该被误杀成内网
        assertEquals(
            "http://127.0.0.1:80@example.invalid/a.m3u8",
            UrlGuard.requireNotLiteralInternal("http://127.0.0.1:80@example.invalid/a.m3u8"),
        )
    }

    @Test
    fun `rejects localhost style hostnames`() {
        assertFailsWith<UrlGuard.Blocked> { UrlGuard.requireNotLiteralInternal("http://localhost:8080/x") }
        assertFailsWith<UrlGuard.Blocked> { UrlGuard.requireNotLiteralInternal("http://nas.internal/x") }
        assertFailsWith<UrlGuard.Blocked> { UrlGuard.requireNotLiteralInternal("http://192.168.0.1/x") }
        assertFailsWith<UrlGuard.Blocked> { UrlGuard.requireAllowed("http://[fc00::abcd]/x") }
    }

    @Test
    fun `literal check does not resolve hostnames`() {
        // 数据源配置里的域名（.test 保留域）不该触发真实 DNS
        val url = "https://example-site.test/search?keyword=x"
        assertEquals(url, UrlGuard.requireNotLiteralInternal(url))
    }

    @Test
    fun `allowPrivate opt-out keeps lan targets working`() {
        val url = "http://192.168.1.20:32400/stream"
        assertEquals(url, UrlGuard.requireAllowed(url, allowPrivate = true))
        assertEquals(url, UrlGuard.requireNotLiteralInternal(url, allowPrivate = true))
    }

    @Test
    fun `rejects url without host`() {
        assertFailsWith<UrlGuard.Blocked> { UrlGuard.requireAllowed("http:///x.m3u8") }
    }
}
