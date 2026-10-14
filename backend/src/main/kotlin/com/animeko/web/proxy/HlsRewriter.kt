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

    /**
     * @param forwardParams 拉取本清单时使用的 ua/referer/cookie 等参数，
     *   必须一并写进每个分片地址：源站对分片同样做防盗链校验，
     *   只带 url 参数的分片请求会被判 403（清单能拉到、画面却播不出来）。
     */
    fun rewrite(
        body: String,
        playlistUrl: String,
        proxyPath: String = "/api/proxy",
        forwardParams: Map<String, String> = emptyMap(),
    ): String {
        return body.lines().joinToString("\n") { line ->
            val t = line.trim()
            when {
                t.isEmpty() -> line
                t.startsWith("#") -> rewriteTagAttributes(line, playlistUrl, proxyPath, forwardParams)
                else -> {
                    val abs = if (t.startsWith("http://") || t.startsWith("https://")) {
                        t
                    } else {
                        SelectorEngine.absUrl(playlistUrl, t)
                    }
                    proxyUrl(proxyPath, abs, forwardParams)
                }
            }
        }
    }

    /** #EXT-X-KEY:METHOD=AES-128,URI="https://..." 等标签中的地址改写 */
    private fun rewriteTagAttributes(
        line: String,
        playlistUrl: String,
        proxyPath: String,
        forwardParams: Map<String, String>,
    ): String =
        uriAttrRegex.replace(line) { m ->
            val url = m.groupValues[1]
            val abs = if (url.startsWith("http://") || url.startsWith("https://")) {
                url
            } else {
                SelectorEngine.absUrl(playlistUrl, url)
            }
            """URI="${proxyUrl(proxyPath, abs, forwardParams)}""""
        }

    fun proxyUrl(proxyPath: String, url: String, forwardParams: Map<String, String> = emptyMap()): String {
        val extra = forwardParams.entries
            .filter { it.value.isNotBlank() }
            .joinToString("") { "&${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        return "$proxyPath?url=${URLEncoder.encode(url, "UTF-8")}$extra"
    }
}
