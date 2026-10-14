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
     * @param sid 服务端会话号：UA/Referer/Cookie 存在 ProxySessionStore 里，
     *   分片地址只带这个号。清单能拉到、分片也必须能拉到（源站对分片同样做防盗链校验），
     *   但凭据本身不该被复制到成百上千个分片 URL 上（浏览器历史与访问日志都会留下）。
     */
    fun rewrite(
        body: String,
        playlistUrl: String,
        proxyPath: String = "/api/proxy",
        sid: String = "",
    ): String {
        return body.lines().joinToString("\n") { line ->
            val t = line.trim()
            when {
                t.isEmpty() -> line
                t.startsWith("#") -> rewriteTagAttributes(line, playlistUrl, proxyPath, sid)
                else -> {
                    val abs = if (t.startsWith("http://") || t.startsWith("https://")) {
                        t
                    } else {
                        SelectorEngine.absUrl(playlistUrl, t)
                    }
                    proxyUrl(proxyPath, abs, sid)
                }
            }
        }
    }

    /** #EXT-X-KEY:METHOD=AES-128,URI="https://..." 等标签中的地址改写 */
    private fun rewriteTagAttributes(
        line: String,
        playlistUrl: String,
        proxyPath: String,
        sid: String,
    ): String =
        uriAttrRegex.replace(line) { m ->
            val url = m.groupValues[1]
            val abs = if (url.startsWith("http://") || url.startsWith("https://")) {
                url
            } else {
                SelectorEngine.absUrl(playlistUrl, url)
            }
            """URI="${proxyUrl(proxyPath, abs, sid)}""""
        }

    fun proxyUrl(proxyPath: String, url: String, sid: String = ""): String {
        // sid 是本服务生成的十六进制串，不需要再编码
        val extra = if (sid.isEmpty()) "" else "&sid=$sid"
        return "$proxyPath?url=${URLEncoder.encode(url, "UTF-8")}$extra"
    }
}
