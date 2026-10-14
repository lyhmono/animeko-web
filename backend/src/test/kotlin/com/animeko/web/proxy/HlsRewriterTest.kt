package com.animeko.web.proxy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HlsRewriterTest {

    @Test
    fun `rewrites absolute and relative segments`() {
        val body = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXTINF:10,
            https://cdn.example.com/hls/seg0.ts
            #EXTINF:10,
            seg1.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val result = HlsRewriter.rewrite(body, "https://cdn.example.com/hls/index.m3u8")

        assertTrue(result.contains("#EXTM3U"))
        assertTrue(result.contains("#EXT-X-ENDLIST"))
        assertTrue(result.contains("/api/proxy?url=https%3A%2F%2Fcdn.example.com%2Fhls%2Fseg0.ts"))
        // 相对地址基于播放列表 URL 解析
        assertTrue(result.contains("/api/proxy?url=https%3A%2F%2Fcdn.example.com%2Fhls%2Fseg1.ts"))
        // 注释行保持原样（除 URI 属性）；共 7 行
        assertEquals(7, result.lines().size)
    }

    @Test
    fun `rewrites ext-x-key uri attribute`() {
        val body = """#EXT-X-KEY:METHOD=AES-128,URI="https://key.example.com/k.bin",IV=0x1234"""
        val result = HlsRewriter.rewrite(body, "https://cdn.example.com/hls/index.m3u8")
        assertTrue(result.contains("""URI="/api/proxy?url=https%3A%2F%2Fkey.example.com%2Fk.bin""""))
        assertTrue(result.contains("IV=0x1234"))
    }

    @Test
    fun `forwards auth params into every rewritten url`() {
        // 源站对分片和密钥同样做防盗链校验：改写后若只剩 url 参数，
        // 清单拉得到、分片却全部 403（表现为「有声音没画面」或干脆播不出）。
        val body = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://key.example.com/k.bin"
            #EXTINF:10,
            seg1.ts
            https://cdn.example.com/hls/seg2.ts
        """.trimIndent()
        val params = mapOf(
            "ua" to "Mozilla/5.0 Test",
            "referer" to "https://v.example.com/",
            "cookie" to "quality=1080",
        )

        val result = HlsRewriter.rewrite(
            body,
            "https://cdn.example.com/hls/index.m3u8",
            forwardParams = params,
        )

        assertTrue(result.contains("cookie=quality%3D1080"), result)
        assertTrue(result.contains("referer=https%3A%2F%2Fv.example.com%2F"), result)
        assertTrue(result.contains("ua=Mozilla%2F5.0+Test"), result)
        // 密钥 + 两个分片 = 3 处都必须带上
        assertEquals(3, Regex("cookie=quality%3D1080").findAll(result).count(), result)
    }

    @Test
    fun `omits blank and absent forward params`() {
        val result = HlsRewriter.rewrite(
            "#EXTM3U\nseg1.ts",
            "https://cdn.example.com/hls/index.m3u8",
            forwardParams = mapOf("ua" to "UA", "referer" to "", "cookie" to "x=y"),
        )
        assertTrue(result.contains("&ua=UA"), result)
        assertTrue(result.contains("&cookie=x%3Dy"), result)
        assertTrue(!result.contains("referer"), "空值不该出现在改写地址里: $result")
    }

    @Test
    fun `forwarded values decode back to the original after one percent-decode`() {
        // 改写出的分片地址会被浏览器原样发回，服务端只做一次查询参数解码。
        // 若编码方式与之不匹配（空格、字面 +、%），UA/cookie 会静默变形，
        // 表现为「有的源能播有的不能」这种查不出原因的问题。
        val params = mapOf("ua" to "Player 1.0 +test 50%", "cookie" to "sid=a+b%2Bc")
        val result = HlsRewriter.rewrite(
            "#EXTM3U\nseg1.ts",
            "https://cdn.example.com/hls/index.m3u8",
            forwardParams = params,
        )
        val query = result.lines().last().substringAfter('?')
        for ((key, value) in params) {
            val encoded = query.split('&').first { it.startsWith("$key=") }.substringAfter('=')
            assertEquals(
                value,
                java.net.URLDecoder.decode(encoded, "UTF-8"),
                "$key 编解码往返后变形",
            )
        }
    }
}
