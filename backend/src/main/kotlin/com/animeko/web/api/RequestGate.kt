package com.animeko.web.api

import com.animeko.web.auth.LoginRateLimiter
import com.animeko.web.auth.SessionStore
import com.animeko.web.auth.CredentialVerifier
import com.animeko.web.config.AppConfig
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.response.ApplicationResponse
import io.ktor.server.response.header
import io.ktor.server.routing.put
import io.ktor.util.AttributeKey
import java.net.URI

internal val VERIFIED_BEARER_SESSION = AttributeKey<String>("verified-bearer-session")

/**
 * 登录会话 Cookie 名。
 *
 * 开了 `ANIMEKO_COOKIE_SECURE`（= 这套部署确实是 https）时改用 `__Host-` 前缀：
 * 浏览器对带这个前缀的 Cookie 强制三条 —— 必须 Secure、不得带 Domain、Path 必须是 `/`。
 * 于是邻居子域（`evil.example.com`，或被人抢注的子域）再也不能往这台机器上**种**一个
 * 同名会话 Cookie（cookie tossing：攻击者用 `Domain=.example.com` 写一个
 * `animeko_session=<他自己>` ，虽然过不了校验，但能把真用户那份顶掉，
 * 让人反复掉线；更糟的是某些旧的「有 Cookie 就当已登录」的写法会被绕过）。
 * 前缀把这整类可能直接取消了。
 *
 * **默认部署不能加前缀**：`__Host-` 要求 Secure，而本机 8080/SSH 隧道是 http，
 * 加了浏览器会整个丢弃 Set-Cookie，症状是「登录页反复回弹」——比不加固更糟。
 * 所以按 cookieSecure 分支，读的时候两个名字都认（升级那一刻已经登录的人不掉线，
 * 也保证降级回去时旧 Cookie 仍然可用），注销时两个都清。
 */
internal const val SESSION_COOKIE = "animeko_session"
internal const val HOST_PREFIXED_COOKIE = "__Host-$SESSION_COOKIE"

/** 写：按当前是否 https 选名字。 */
internal fun sessionCookieName(cookieSecure: Boolean): String =
    if (cookieSecure) HOST_PREFIXED_COOKIE else SESSION_COOKIE

/**
 * 读：两个名字都认，但**必须两个都试**，不能只取优先的那个。
 *
 * 「优先 `__Host-`」听起来无害，实际会把人锁在门外：从 HTTPS 部署（`__Host-`）
 * 切回 `cookieSecure=false` 之后，浏览器里那份 `__Host-` 还留着；等它在服务端过期
 * 或已被注销，而用户重新登录种下的是不带前缀的新名字，读侧却仍然先看那份陈旧的 ——
 * 于是每一个请求都是 401，连注销接口自己都要鉴权、同样 401，Cookie 永远清不掉。
 * 实测：`Cookie: __Host-animeko_session=<无效>; animeko_session=<有效>` → 401。
 *
 * 现在返回**所有**候选，由调用方「任一有效即算已登录」，
 * 注销则把两个都作废（它们都是这台机器发出去的会话）。
 */
internal fun ApplicationCall.sessionTokens(): List<String> =
    listOf(cookieValue(HOST_PREFIXED_COOKIE), cookieValue(SESSION_COOKIE))
        .filterNotNull()
        .filter { it.isNotEmpty() }
        .distinct()

internal fun ApplicationCall.rateLimitKey(cfg: AppConfig): String {
    val remote = request.local.remoteHost
    if (cfg.trustedProxyHops <= 0) return remote
    val chain = request.headers[HttpHeaders.XForwardedFor]
        ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        ?: return remote
    val candidate = chain.getOrNull(chain.size - cfg.trustedProxyHops) ?: return remote
    // 只接受 IP 字面量：XFF 是自由文本，塞进来的可能是域名、端口后缀或垃圾值，
    // 拿它们当键既不稳定也能被构造。判据是纯字面（不解析 DNS），快且确定。
    return if (looksLikeIpLiteral(candidate)) candidate else remote
}

internal fun looksLikeIpLiteral(value: String): Boolean {
    // 旧实现只判断「含冒号」，`abc:def` 也会被当成 IP 进入限速键空间。
    // 这里做字面校验、不解析 DNS：XFF 是自由文本，解析域名会变成出站请求。
    if (value.contains(':')) {
        // 允许 IPv4-mapped 地址（::ffff:192.0.2.1）：双栈反代会把它作为 IPv4 客户端的
        // XFF，若这里拒绝，所有 IPv4 客户端会退化成共用一个代理 IP 限速桶。
        return value.count { it == ':' } >= 2 &&
            value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
    }
    val parts = value.split('.')
    return parts.size == 4 && parts.all { part ->
        part.length in 1..3 && part.all { it.isDigit() } && part.toInt() in 0..255
    }
}

/**
 * 浏览器总会带上 Origin（同源的 fetch/表单也是），所以拿它与「这个请求被送到哪个主机」
 * 对比就能识别跨站写请求。
 *
 * 不带 Origin 的（curl、脚本、API 客户端）视为本地调用放行 —— 它们本来也拿不到会话 Cookie，
 * 真正的门槛是鉴权那一层。
 *
 * 为什么这一层不能省：浏览器对 HTTP Basic 凭据是按域缓存、会自动附带的，
 * 而 SameSite 只约束 Cookie。用户如果在质询弹窗里输过一次口令，
 * 恶意页面发出的跨站 POST 就会带着那份凭据打进来，此时只有 Origin 能识别。
 */
internal fun ApplicationCall.isCrossSiteWrite(cfg: AppConfig): Boolean {
    if (cfg.allowCrossOrigin) return false
    if (pathSegments().firstOrNull() != "api") return false
    val method = request.local.method
    if (method == HttpMethod.Get || method == HttpMethod.Head || method == HttpMethod.Options) return false
    val origin = request.headers[HttpHeaders.Origin] ?: return false
    val originHost = runCatching { URI(origin).host }
        .getOrNull()?.lowercase()?.trim('[', ']')?.takeIf { it.isNotBlank() }
        ?: return true // Origin 为 "null"（沙箱 iframe / file:// 页面）按跨站处理
    val hostHeader = if (cfg.trustedProxyHops >= 1) {
        // 反代后浏览器看到的原始主机在 X-Forwarded-Host；Host 头已被代理改写成内部目标，
        // 拿它对比会把同源请求误判成跨站（默认 hops=0 时不看 XFH：客户端可任意伪造）。
        request.headers[HttpHeaders.XForwardedHost]
            ?: request.headers[HttpHeaders.Host]
            ?: request.local.serverHost
    } else {
        request.headers[HttpHeaders.Host] ?: request.local.serverHost
    }.lowercase().trim()
    val hostName = if (hostHeader.startsWith('[')) {
        hostHeader.substringAfter('[').substringBefore(']')
    } else {
        hostHeader.substringBeforeLast(':')
    }
    if (originHost != hostName) return true
    // 主机名相同还要比端口：同一台机器的另一个端口在浏览器眼里是**同站**
    // （SameSite 不看端口，会话 Cookie 照样跟着发），不比端口就等于允许本机别的服务
    // 任意写这个账号。实测：服务在 127.0.0.1:8240 时，Origin: http://127.0.0.1:9999
    // 的 PUT 曾返回 200。
    //
    // 端口从哪来，按可信度分三档；取不到时用 Sec-Fetch-Site 兜底（见下），没有该头的
    // 客户端（curl/脚本）仍放行 —— 宁可漏一格，也不要把每一次收藏都误杀成 403。
    //  1) Host / X-Forwarded-Host 里明写的端口：直接用。
    //  2) hops>=1 时用 X-Forwarded-Proto 推：既然已经信任这层代理转发的 Host，
    //     它转发的协议同样可信（https->443、http->80）。这一档以前是缺的：原写法是
    //     「hops>=1 一律不比端口」，实测连 XFH 里明写的 8080 都被忽略，
    //     http://public.example:3000 这种同站跨端口写照样 200，等于白送一个口子。
    //  3) hops=0（直连）而 Host 不带端口：浏览器只对默认端口省略端口，所以只有本进程
    //     确实监听在 80/443 时才继续比；否则说明中间有个没申报的代理（nginx 的
    //     `proxy_set_header Host $host` 默认就抹掉端口），那时端口不可知，放行。
    val hostPortText = if (hostHeader.startsWith('[')) {
        hostHeader.substringAfterLast(']').substringAfter(':', "")
    } else {
        hostHeader.substringAfter(':', "")
    }
    val hostPort = hostPortText.takeIf { it.isNotBlank() }?.toIntOrNull()
        ?: if (cfg.trustedProxyHops >= 1) {
            // 取最后一个值：每经过一跳可信代理就追加一次，最右那个才是我们这位代理看到的协议
            request.headers[HttpHeaders.XForwardedProto]
                ?.split(',')?.lastOrNull()?.trim()?.lowercase()
                ?.let { defaultPortFor(it) }
        } else {
            cfg.port.takeIf { it == 80 || it == 443 }
        }
    if (hostPort == null) {
        // 端口不可知（反代抹掉了端口且没给 X-Forwarded-Proto）。此时如果浏览器
        // 明确说这不是 same-origin，就不能放行；没有 Sec-Fetch-Site 的 curl/脚本
        // 仍按原来的方式放行。
        val site = request.headers["Sec-Fetch-Site"]?.trim()?.lowercase()
        return site != null && site != "same-origin"
    }
    val originPort = runCatching { URI(origin).port }.getOrNull()?.takeIf { it > 0 }
        ?: defaultPortFor(runCatching { URI(origin).scheme?.lowercase() }.getOrNull())
    // Origin 侧可以猜：它是浏览器给的可信值，scheme 一定在里面
    if (originPort == null) return false
    return originPort != hostPort
}

internal fun defaultPortFor(scheme: String?): Int? = when (scheme) {
    "http" -> 80
    "https" -> 443
    else -> null
}

/**
 * 需要凭据的 /api 请求。豁免只有两个，且按「整条路径段序列精确相等」判定：
 * 因为每个 API 路由都是固定段数、没有通配符，能匹配到 ["api","health"] 的请求
 * 就一定是健康检查本身 —— 不存在「看起来豁免、其实打到别的接口」的空间。
 *
 * 为什么不能用 request.local.uri 的字符串前缀：Ktor 的路由按「段」匹配，
 * 每段单独 URL 解码、空段被忽略，所以 `//api/data`、`/%61pi/data` 都能打到真接口，
 * 而原串并不以 "/api/" 开头。实测这样能无凭据读走数据源列表、覆写个人数据。
 * call.request.path() 也不行：它同样返回未解码、未归一化的原串。
 */
internal fun ApplicationCall.isProtectedApiCall(): Boolean {
    val segments = pathSegments()
    if (segments.firstOrNull() != "api") return false
    return segments !in PUBLIC_API_PATHS
}

internal val PUBLIC_API_PATHS = setOf(
    listOf("api", "health"),
    listOf("api", "session", "login"),
)

/**
 * 与路由一致的归一化：按 / 拆段、丢掉空段、逐段 URL 解码。
 * 绝对形式请求行（`GET http://host/api/x`）先剥掉 scheme://host。
 */
internal fun ApplicationCall.pathSegments(): List<String> {
    var uri = request.local.uri
    if (uri.startsWith("http://", ignoreCase = true) || uri.startsWith("https://", ignoreCase = true)) {
        uri = uri.substringAfter("//").substringAfter("/", "/")
    }
    return uri.substringBefore('?').substringBefore('#')
        .split('/')
        .mapNotNull { segment ->
            runCatching { java.net.URLDecoder.decode(segment, "UTF-8") }.getOrDefault(segment)
                .trim()
                .takeIf { it.isNotEmpty() }
        }
}

internal fun ApplicationCall.hasValidSession(site: SessionStore, cfg: AppConfig): Boolean {
    val token = sessionTokens().firstOrNull(site::isValid) ?: return false
    // 注销只发删除头；其余接口用原子周期门闩，让浏览器和服务端的空闲寿命一致。
    if (pathSegments() != listOf("api", "session", "logout") && site.claimCookieRenewal(token)) {
        response.setSessionCookie(cfg, token, site.ttlSeconds())
    }
    return true
}

/** 仅验证站点签发的随机会话，绝不在许可前比较配置 token。 */
internal fun ApplicationCall.validBearerSession(site: SessionStore): String? =
    request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ") }
        ?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() && site.isValid(it) }

/**
 * 这个 401 该不该附 Basic 质询。
 * 给 XHR 与媒体子资源附质询会让浏览器弹原生口令框，直接盖住前端自己的登录页，
 * 而 hls.js 的分片请求全是 XHR；没有 Sec-Fetch-Mode 的请求（curl、脚本）
 * 才是真的需要质询弹窗才能输入凭据的那类客户端。
 */
internal fun ApplicationCall.wantsBasicChallenge(): Boolean {
    val mode = request.headers["Sec-Fetch-Mode"]?.lowercase()?.trim()
    if (mode.isNullOrEmpty()) return true
    return mode == "navigate"
}

internal enum class AuthResult { Authorized, Failed, Throttled }

/**
 * 统一凭据判定。
 *
 * 限速必须覆盖「每一条能验证凭据的路径」，而不只是登录接口：
 * 只要还有一个免限流的接口会回答「这组 Basic/Bearer 对不对」，
 * 它就是一台在线口令字典的 yes/no 预言机（实测此前可以绕过登录限速无上限试探）。
 *
 * 会话 Cookie 不参与限速：那是 64 位十六进制随机数，猜中没有意义；
 * 而人是会输错口令的，只有「人填的凭据」才需要计数。
 */
internal fun ApplicationCall.authenticate(
    cfg: AppConfig,
    site: SessionStore,
    limiter: LoginRateLimiter,
    credentialVerifier: CredentialVerifier,
): AuthResult {
    val bearerSession = validBearerSession(site)
    if (bearerSession != null) attributes.put(VERIFIED_BEARER_SESSION, bearerSession)
    if (hasValidSession(site, cfg) || bearerSession != null) return AuthResult.Authorized
    val header = request.headers["Authorization"] ?: return AuthResult.Failed
    val key = rateLimitKey(cfg)
    if (!limiter.tryAcquire(key)) return AuthResult.Throttled
    return if (headerAuthorized(credentialVerifier, header)) AuthResult.Authorized else AuthResult.Failed
}

internal fun headerAuthorized(verifier: CredentialVerifier, header: String): Boolean {
    if (header.startsWith("Bearer ")) {
        val token = header.removePrefix("Bearer ").trim()
        if (token.isEmpty()) return false
        return verifier.bearer(token)
    }
    if (header.startsWith("Basic ")) {
        val decoded = runCatching {
            String(java.util.Base64.getDecoder().decode(header.removePrefix("Basic ").trim()), Charsets.UTF_8)
        }.getOrNull() ?: return false
        val parts = decoded.split(":", limit = 2)
        if (parts.size != 2) return false
        return verifier.password(parts[0], parts[1])
    }
    return false
}


/**
 * Cookie 头自己解析：Ktor 的 RequestCookies 会按 URI_ENCODING 解码值，
 * 而我们只放十六进制会话号，无需编解码，也就不会被「同名多值/含 = 的口令」绕开。
 */
internal fun ApplicationCall.cookieValue(name: String): String? =
    request.headers.getAll(HttpHeaders.Cookie)
        ?.asSequence()
        ?.flatMap { it.split(';').asSequence() }
        ?.map { it.trim() }
        ?.firstNotNullOfOrNull { pair ->
            if (pair.substringBefore('=').trim() != name) return@firstNotNullOfOrNull null
            pair.substringAfter('=', "").trim().takeIf { it.isNotEmpty() }
        }

internal fun ApplicationResponse.setSessionCookie(cfg: AppConfig, value: String, maxAgeSeconds: Long) {
    // A renewed session token is private even when the response body is a cacheable image.
    header(HttpHeaders.CacheControl, "private, no-store")
    // SameSite=Strict：会话 Cookie 一旦种下，跨站请求就不会带上它，
    // 恶意站点连「借你的会话发一个写请求」都做不到（Origin 检查是第二层）。
    header(
        HttpHeaders.SetCookie,
        buildString {
            append(sessionCookieName(cfg.cookieSecure)).append('=').append(value)
            append("; Path=/")
            append("; HttpOnly")
            append("; SameSite=Strict")
            append("; Max-Age=").append(maxAgeSeconds)
            if (cfg.cookieSecure) append("; Secure")
        },
    )
}

/**
 * 注销时把**两个**名字都覆盖掉。只清当前那一个会留下另一半：
 * 站长开过 HTTPS（`__Host-` 那份）又关掉之后，旧浏览器里仍然揣着另一份同名会话，
 * 而读侧两个名字都认 —— 「已注销」的账号会被那份复活。
 * 多下发一条无害：不存在的那个名字浏览器直接忽略。
 */
internal fun ApplicationResponse.clearSessionCookie(cfg: AppConfig) {
    header(HttpHeaders.CacheControl, "private, no-store")
    listOf(HOST_PREFIXED_COOKIE, SESSION_COOKIE).forEach { name ->
        header(
            HttpHeaders.SetCookie,
            buildString {
                append(name).append("=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0")
                // __Host- 前缀强制要求 Secure：降级到 cookieSecure=false 后，若清理头不带
                // Secure，浏览器会拒绝这条 Set-Cookie，旧会话 Cookie 就永远删不掉。
                if (cfg.cookieSecure || name == HOST_PREFIXED_COOKIE) append("; Secure")
            },
        )
    }
}
