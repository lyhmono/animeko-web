package com.animeko.web.proxy

import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `every rewritten url carries the session id`() {
        // 源站对分片与密钥同样做防盗链校验：只带 url 的话清单能拉、分片全 403。
        // 但凭据本身不出现在 URL 上，只带一个会话号。
        val body = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://key.example.com/k.bin"
            #EXTINF:10,
            seg1.ts
            https://cdn.example.com/hls/seg2.ts
        """.trimIndent()

        val result = HlsRewriter.rewrite(
            body,
            "https://cdn.example.com/hls/index.m3u8",
            sid = "ab12cd34ef56",
        )

        assertTrue(
            result.contains("""URI="/api/proxy?url=https%3A%2F%2Fkey.example.com%2Fk.bin&sid=ab12cd34ef56"""),
            result,
        )
        assertTrue(result.contains("seg1.ts&sid=ab12cd34ef56"), result)
        assertTrue(result.contains("seg2.ts&sid=ab12cd34ef56"), result)
        // 密钥 + 两个分片，一处都不能漏
        assertEquals(3, Regex("sid=ab12cd34ef56").findAll(result).count(), result)
    }

    @Test
    fun `empty session id adds no parameter`() {
        val result = HlsRewriter.rewrite("#EXTM3U\nseg1.ts", "https://cdn.example.com/hls/index.m3u8", sid = "")
        assertTrue(result.contains("/api/proxy?url=https%3A%2F%2Fcdn.example.com%2Fhls%2Fseg1.ts"), result)
        assertFalse(result.contains("sid="), "空会话号不该留下空参数：$result")
    }

    @Test
    fun `encoded media url decodes back exactly once`() {
        // 改写后的地址会被浏览器原样发回，服务端只做一次查询参数解码；
        // 签名地址里的 %2B / %25 / 空格必须能原样还原，否则分片直接 404
        val signed = "https://cdn.example.com/hls/a.ts?sign=a+b%2Bc&exp=50%"
        val result = HlsRewriter.rewrite("#EXTM3U\n$signed", "https://cdn.example.com/hls/index.m3u8")
        val encoded = result.lines().last().substringAfter("url=").substringBefore("&")
        assertEquals(signed, URLDecoder.decode(encoded, "UTF-8"))
    }

    @Test
    fun `a leading BOM does not turn the playlist header into a segment`() {
        // U+FEFF 不是 isWhitespace，trim() 带不走它。曾经 "＃EXTM3U"（带 BOM）被当成
        // 一行分片地址改写进代理，清单就此丢掉 #EXTM3U 头 → 播放器判定不是 m3u8
        val body = "﻿#EXTM3U\n#EXTINF:10,\nseg1.ts\n"
        val result = HlsRewriter.rewrite(body, "https://cdn.example.com/hls/index.m3u8")
        assertTrue(result.startsWith("#EXTM3U"), "清单头必须还在，实际：${result.take(40)}")
        assertTrue(result.contains("/api/proxy?url=https%3A%2F%2Fcdn.example.com%2Fhls%2Fseg1.ts"), result)
    }

    @Test
    fun `a malformed segment uri is passed through instead of becoming a broken proxy url`() {
        // `%` 后面不是两位十六进制 → URI 解析失败，absUrl 只能退回相对路径。
        // 以前会把这条相对路径直接塞进代理地址，于是 /api/proxy?url=seg%zz.ts 每个分片 400
        val body = "#EXTM3U\n#EXTINF:10,\nseg%zz.ts\n"
        val result = HlsRewriter.rewrite(body, "https://cdn.example.com/hls/index.m3u8")
        assertTrue(result.contains("seg%zz.ts"), result)
        assertFalse(result.contains("/api/proxy?url=seg"), "畸形地址不该被改写成必然 400 的代理地址：$result")
    }
}
