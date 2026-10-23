package com.animeko.web.proxy

import com.animeko.web.http.UrlGuard
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

    /** 改写结果：正文 + **实际改写出的**分片/密钥主机（供代理会话做主机绑定）。 */
    data class RewriteResult(val body: String, val hosts: Set<String>)

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
    ): String = rewriteDetailed(body, playlistUrl, proxyPath, sid).body

    /**
     * 与 [rewrite] 相同，但顺带返回真正被改写的 URI 主机。
     *
     * 不要再用正则回扫改写后的全文来登记主机：评论行里随便写一个
     * `/api/proxy?url=...` 就能把任意主机加进会话白名单。主机必须来自
     * 这里真正改写的那几条 URI。
     */
    fun rewriteDetailed(
        body: String,
        playlistUrl: String,
        proxyPath: String = "/api/proxy",
        sid: String = "",
    ): RewriteResult {
        val hosts = LinkedHashSet<String>()
        // 去掉开头的 BOM：U+FEFF 不是 isWhitespace，trim() 不会把它带走，
        // 于是 "\uFEFF#EXTM3U" 会被当成一行**分片地址**改写进代理，
        // 清单丢掉 #EXTM3U 头 → 播放器直接判定不是 m3u8（部分 Windows/国内打包站会带 BOM）
        val rewritten = body.removePrefix("\uFEFF").lines().joinToString("\n") { line ->
            val t = line.trim()
            when {
                t.isEmpty() -> line
                t.startsWith("#") -> rewriteTagAttributes(line, playlistUrl, proxyPath, sid, hosts)
                else -> {
                    val abs = if (t.startsWith("http://") || t.startsWith("https://")) {
                        t
                    } else {
                        SelectorEngine.absUrl(playlistUrl, t)
                    }
                    // 解析不出绝对地址时原样留着这一行。absUrl 失败会返回相对路径本身
                    // （`%` 后面不是两位十六进制一类畸形 URI，国内 CDN 真会吐出来），
                    // 那种情况改写成 /api/proxy?url=seg.ts 会让代理直接 400 ——
                    // 每个分片都失败，还把原因藏进了一个看不懂的代理地址里。
                    // 原样透传至少让播放器自己去按清单地址解析一次。
                    if (abs.startsWith("http")) {
                        UrlGuard.hostOf(abs)?.let(hosts::add)
                        proxyUrl(proxyPath, abs, sid)
                    } else {
                        t
                    }
                }
            }
        }
        return RewriteResult(rewritten, hosts)
    }

    /** #EXT-X-KEY:METHOD=AES-128,URI="https://..." 等标签中的地址改写 */
    private fun rewriteTagAttributes(
        line: String,
        playlistUrl: String,
        proxyPath: String,
        sid: String,
        hosts: MutableSet<String>,
    ): String =
        uriAttrRegex.replace(line) { m ->
            val url = m.groupValues[1]
            val abs = if (url.startsWith("http://") || url.startsWith("https://")) {
                url
            } else {
                SelectorEngine.absUrl(playlistUrl, url)
            }
            // 与下面的分片分支同一条规则：解析不出绝对地址就原样留着。
            // 少了这一格，`URI="key%zz.bin"` 这类畸形密钥地址会被改写成
            // /api/proxy?url=key%zz.bin，而代理入口要求 url 必须以 http 开头 → 必然 400，
            // 症状是整条流黑屏且没有任何能看懂的错误。
            if (!abs.startsWith("http")) {
                m.value
            } else {
                UrlGuard.hostOf(abs)?.let(hosts::add)
                """URI="${proxyUrl(proxyPath, abs, sid)}""""
            }
        }

    fun proxyUrl(proxyPath: String, url: String, sid: String = ""): String {
        // sid 是本服务生成的十六进制串，不需要再编码
        val extra = if (sid.isEmpty()) "" else "&sid=$sid"
        return "$proxyPath?url=${URLEncoder.encode(url, "UTF-8")}$extra"
    }
}
