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
}
