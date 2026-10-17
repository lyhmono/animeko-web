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
        // 边界：100.63.x 与 100.128.x 都在 CGNAT 段外，不该被顺手拦掉。
        // 这里只断言「不是因为落在保留段而被拒」——它们解析不出来时按设计放行，
        // 所以用字面 IP 才有效。
        listOf("http://100.63.255.255/x", "http://100.128.0.1/x", "http://198.17.255.255/x", "http://198.20.0.1/x")
            .forEach { url ->
                val e = runCatching { UrlGuard.requireAllowed(url) }.exceptionOrNull()
                if (e is UrlGuard.Blocked) {
                    assertTrue(
                        !e.message.orEmpty().contains("内网/保留"),
                        "$url 不在保留段内，却被当成内网拦了：${e.message}",
                    )
                }
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
