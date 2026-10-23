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
        // 结尾的点是 DNS 的根标签（`localhost.` 与 `localhost` 是同一台机器），
        // 不归一化会让按字符串比较的本机名判断失配
        val normalized = host.removeSuffix(".")
        if (isLocalName(normalized)) throw Blocked("目标是本机/内网名，已拒绝: $host")
        val addresses = runCatching { InetAddress.getAllByName(normalized).toList() }.getOrNull()
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
        val normalized = host.removeSuffix(".")
        if (isLocalName(normalized)) throw Blocked("目标是本机/内网名，已拒绝: $host")
        // 只对字面 IP 求值：Java 认得 2130706433 这类十进制写法，会解析成 127.0.0.1
        if (isIpLiteral(normalized)) {
            val literal = runCatching { InetAddress.getByName(normalized) }.getOrNull()
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
    /**
     * 取主机名（小写、去 IPv6 方括号）。
     *
     * 公开给会话绑定用：主机名必须只有**一种**算法，否则会出现「守卫认为放行的是
     * A 主机、会话认为它是 B 主机」的错位。`URI.host` 对带下划线这类非法但 DNS
     * 里真实存在的主机名返回 null，所以下面还要退回 rawAuthority 自己切。
     */
    fun hostOf(url: String): String? =
        runCatching { hostOf(URI(url)) }.getOrNull()

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
     *
     * 单个全数字标签也算：`2130706433` 会被 JVM 直接解析成 127.0.0.1，
     * 而它既没有点、也不匹配十六进制写法（DNS 的顶级标签不允许纯数字，
     * 所以不存在误伤正常域名的问题）。
     */
    private fun isIpLiteral(host: String): Boolean =
        host.contains(':') || host.matches(Regex("""\d{1,3}(\.\d{1,3}){0,3}""")) ||
            host.matches(Regex("""0[xX][0-9a-fA-F]+(\.0[xX][0-9a-fA-F]+){0,3}""")) ||
            host.matches(Regex("""\d{1,10}"""))

    private fun isInternalAddress(addr: InetAddress): Boolean {
        if (
            addr.isLoopbackAddress ||
            addr.isAnyLocalAddress ||
            addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress ||
            addr.isMulticastAddress ||
            isIpv6UniqueLocal(addr)
        ) return true
        // Java 那几个 isXxx 不够：实测 http://100.64.0.1/ 与 http://0.1.2.3:8210/
        // 都能穿过原来的清单（表现是 20 秒连接超时，也就是真的去连了）。
        // 0.0.0.0/8 里只有精确的 0.0.0.0 算 isAnyLocalAddress，整段在 Linux 上
        // 都按「本网络」处理；100.64.0.0/10 是运营商 NAT，很多机房内部地址就落在
        // 这一段，对外的服务被它当跳板并不稀奇。
        val bytes = addr.address
        return when (bytes.size) {
            4 -> isReservedIpv4(bytes)
            16 -> isReservedIpv6(bytes)
            else -> false
        }
    }

    /** IPv4 保留段：0/8、100.64/10（CGNAT）、198.18/15（基准测试）、240/4（含广播）、以及三个 TEST-NET */
    private fun isReservedIpv4(b: ByteArray): Boolean {
        fun oct(i: Int) = b[i].toInt() and 0xff
        val a0 = oct(0)
        val a1 = oct(1)
        return when {
            a0 == 0 -> true                       // 0.0.0.0/8 本网络
            a0 == 100 && a1 >= 64 && a1 <= 127 -> true  // 100.64.0.0/10 CGNAT
            a0 == 198 && (a1 == 18 || a1 == 19) -> true // 198.18.0.0/15 设备基准测试
            a0 == 192 && a1 == 0 && (oct(2) == 2 || oct(2) == 3) -> true // TEST-NET-1 / 保留
            a0 == 198 && a1 == 51 && oct(2) == 100 -> true               // TEST-NET-2
            a0 == 203 && a1 == 0 && oct(2) == 113 -> true                // TEST-NET-3
            a0 >= 240 -> true                     // 240.0.0.0/4 保留 + 255.255.255.255 广播
            else -> false
        }
    }

    /** IPv6 保留段：100::/64（discard-only）、2001:db8::/32（文档）、2002::/16（6to4）、64:ff9b::/96（NAT64） */
    private fun isReservedIpv6(b: ByteArray): Boolean {
        // 参数是**字节偏移**，不是第 n 组：2001:db8:: 的第二组在 b[2..3]。
        // 写成 word(1) 会读到 b[1..2]（0x010d），于是 2001:db8::/32 永远匹配不上
        fun wordAt(byteAt: Int) = ((b[byteAt].toInt() and 0xff) shl 8) or (b[byteAt + 1].toInt() and 0xff)
        val head = wordAt(0)
        val second = wordAt(2)
        return head == 0x0100 || head == 0x2002 || (head == 0x2001 && second == 0x0db8) ||
            // NAT64 的 64:ff9b::/96 可以把内网 IPv4 映射成一个看起来「正常」的 v6 地址，
            // 在不做这条拦截的环境里，http://[64:ff9b::7f00:1]/ 就是 127.0.0.1。
            (head == 0x0064 && second == 0xff9b)
    }

    /** IPv6 唯一本地地址 fc00::/7，Java 不把它归入 siteLocal。 */
    private fun isIpv6UniqueLocal(addr: InetAddress): Boolean {
        val bytes = addr.address
        return bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
    }
}
