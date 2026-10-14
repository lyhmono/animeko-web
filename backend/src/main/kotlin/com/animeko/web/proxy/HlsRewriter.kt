package com.animeko.web.proxy

import com.animeko.web.selector.SelectorEngine
import java.net.URLEncoder

/**
 * HLS (m3u8) 清单重写：把分片/密钥地址改写为经过服务端代理的地址，
 * 从而在浏览器侧规避目标站的防盗链/Referer 校验与 CORS 限制。
 *
 * 相对地址基于播放列表的完整 URL 解析（RFC 3986）。
 */
object HlsRewriter {

    private val uriAttrRegex = Regex("""URI="([^"]+)"""")

    fun rewrite(body: String, playlistUrl: String, proxyPath: String = "/api/proxy"): String {
        return body.lines().joinToString("\n") { line ->
            val t = line.trim()
            when {
                t.isEmpty() -> line
                t.startsWith("#") -> rewriteTagAttributes(line, playlistUrl, proxyPath)
                else -> {
                    val abs = if (t.startsWith("http://") || t.startsWith("https://")) {
                        t
                    } else {
                        SelectorEngine.absUrl(playlistUrl, t)
                    }
                    proxyUrl(proxyPath, abs)
                }
            }
        }
    }

    /** #EXT-X-KEY:METHOD=AES-128,URI="https://..." 等标签中的地址改写 */
    private fun rewriteTagAttributes(line: String, playlistUrl: String, proxyPath: String): String =
        uriAttrRegex.replace(line) { m ->
            val url = m.groupValues[1]
            val abs = if (url.startsWith("http://") || url.startsWith("https://")) {
                url
            } else {
                SelectorEngine.absUrl(playlistUrl, url)
            }
            """URI="${proxyUrl(proxyPath, abs)}""""
        }

    fun proxyUrl(proxyPath: String, url: String): String =
        "$proxyPath?url=${URLEncoder.encode(url, "UTF-8")}"
}
