package com.animeko.web.http

import java.net.InetAddress
import java.net.URI

/**
 * 服务端出站点校验：代理与数据源抓取的 URL 都由调用方提供，
 * 不加限制的话，任何能访问到这台服务的人都能借它打内网地址
 * （127.0.0.1 上的服务、169.254.169.254 元数据接口等）。
 *
 * 已知残留风险：校验与实际连接之间是两次独立解析，理论上可被 DNS rebinding 绕过
 * （检查时给公网 IP、连接时给 127.0.0.1）。彻底做法是把已解析的 IP 固定下来再连接，
 * 那会牵动 TLS SNI/证书校验，自用场景暂不处理，故重定向每一跳都重新校验以缩小窗口。
 */
object UrlGuard {

    class Blocked(reason: String) : Exception(reason)

    /**
     * 严格校验：解析域名并逐个检查地址。用于 `/api/proxy`。
     * 域名解析不出来时**放行**，交给后续真实请求自然失败——
     * 否则下划线一类 `URI` 不接受、但 DNS 里可能存在的合法主机名会被误杀。
     */
    fun requireAllowed(raw: String, allowPrivate: Boolean = false): String {
        val uri = runCatching { URI(raw) }.getOrElse { throw Blocked("URL 无法解析") }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") throw Blocked("仅允许 http/https 协议")
        val host = hostOf(uri) ?: throw Blocked("URL 缺少主机名")
        if (allowPrivate) return raw
        if (isLocalName(host)) throw Blocked("目标是本机/内网名，已拒绝: $host")
        val addresses = runCatching { InetAddress.getAllByName(host).toList() }.getOrNull()
        addresses?.firstOrNull { isInternalAddress(it) }?.let {
            throw Blocked("目标地址属于内网/保留地址，已拒绝: $host -> ${it.hostAddress}")
        }
        return raw
    }

    /**
     * 轻量校验：不做真实 DNS 解析，只拦字面内网 IP 与本机/内网域名。
     * 用于数据源抓取——目标本就是运营者配置的站点，且单元测试用的是 `.test` 保留域名，
     * 做真实解析会既慢又不确定。
     */
    fun requireNotLiteralInternal(raw: String, allowPrivate: Boolean = false): String {
        val uri = runCatching { URI(raw) }.getOrElse { throw Blocked("URL 无法解析") }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") throw Blocked("仅允许 http/https 协议")
        val host = hostOf(uri) ?: throw Blocked("URL 缺少主机名")
        if (allowPrivate) return raw
        if (isLocalName(host)) throw Blocked("目标是本机/内网名，已拒绝: $host")
        // 只对字面 IP 求值：Java 认得 2130706433 这类十进制写法，会解析成 127.0.0.1
        if (isIpLiteral(host)) {
            val literal = runCatching { InetAddress.getByName(host) }.getOrNull()
            if (literal != null && isInternalAddress(literal)) {
                throw Blocked("目标地址属于内网/保留地址，已拒绝: $host")
            }
        }
        return raw
    }

    /**
     * `URI.getHost()` 对含下划线等非法字符的主机名返回 null（RFC 2396 的字符集比现实里的
     * 内网 NAS/CDN 域名严格），这里退回到手工拆 authority，避免把合法地址判成「缺少主机名」。
     */
    private fun hostOf(uri: URI): String? {
        uri.host?.let { return it.ifBlank { null }?.let { h -> h.trim('[', ']').lowercase() } }
        val authority = uri.rawAuthority?.substringAfterLast('@') ?: return null
        if (authority.startsWith('[')) {
            return authority.substringAfter('[').substringBefore(']').lowercase().ifBlank { null }
        }
        val colon = authority.lastIndexOf(':')
        val withPort = colon > 0 && colon < authority.length - 1 && authority.substring(colon + 1).all { it.isDigit() }
        val host = if (withPort) authority.substring(0, colon) else authority
        return host.lowercase().ifBlank { null }
    }

    private fun isLocalName(host: String): Boolean =
        host == "localhost" || host.endsWith(".localhost") ||
            host.endsWith(".internal") || host.endsWith(".local")

    /**
     * 只在「确定是 IP 写法」时才交给 InetAddress 求值。
     * 判据必须严格：把普通域名误判成 IP 字面量会触发真实 DNS 查询，
     * 让本不该解析的轻量校验路径变得又慢又不确定。
     */
    private fun isIpLiteral(host: String): Boolean =
        host.contains(':') || host.matches(Regex("""\d{1,3}(\.\d{1,3}){0,3}""")) ||
            host.matches(Regex("""0[xX][0-9a-fA-F]+(\.0[xX][0-9a-fA-F]+){0,3}"""))

    private fun isInternalAddress(addr: InetAddress): Boolean =
        addr.isLoopbackAddress ||
            addr.isAnyLocalAddress ||
            addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress ||
            addr.isMulticastAddress ||
            isIpv6UniqueLocal(addr)

    /** IPv6 唯一本地地址 fc00::/7，Java 不把它归入 siteLocal。 */
    private fun isIpv6UniqueLocal(addr: InetAddress): Boolean {
        val bytes = addr.address
        return bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
    }
}
