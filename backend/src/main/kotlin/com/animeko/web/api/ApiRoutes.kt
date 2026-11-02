package com.animeko.web.api

import com.animeko.web.auth.LoginRateLimiter
import com.animeko.web.auth.SessionStore
import com.animeko.web.auth.CredentialVerifier
import com.animeko.web.auth.ConfiguredCredentialVerifier
import com.animeko.web.bangumi.BangumiClient
import com.animeko.web.config.AppConfig
import com.animeko.web.appLog
import com.animeko.web.data.UserDataStore
import com.animeko.web.http.Fetcher
import com.animeko.web.proxy.ProxySessionStore
import com.animeko.web.selector.SelectorEngine
import com.animeko.web.selector.SelectorRepository
import com.animeko.web.selector.SubscriptionService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.net.URI


internal const val HEALTH_PATH = "/api/health"

/**
 * 探测接口的预算。设置页一个人点几下是正常用法，超过这个量就是脚本在拿它当
 * DNS 放大器：默认部署无鉴权时该接口人人可发，而每个目标地址都要做一次阻塞 DNS
 * 加一次带上限的 HTTP 探测。单独一份预算，不与登录口令共用桶 —— 站长测通道
 * 不该消耗自己的登录容错，反之撞口令的人也不该顺带把探测锁死。
 */
internal const val PROBE_MAX_ATTEMPTS = 20
internal const val PROBE_WINDOW_MS = 5 * 60 * 1000L

internal const val LOGIN_PATH = "/api/session/login"
internal const val LOGOUT_PATH = "/api/session/logout"

fun Application.configureApiRoutes(
    cfg: AppConfig,
    bangumi: BangumiClient,
    repo: SelectorRepository,
    engine: SelectorEngine,
    fetcher: Fetcher,
    sessions: ProxySessionStore,
    site: SessionStore,
    limiter: LoginRateLimiter,
    userData: UserDataStore,
    subscriptions: SubscriptionService,
    credentialVerifier: CredentialVerifier = ConfiguredCredentialVerifier(cfg),
    probeLimiter: LoginRateLimiter = LoginRateLimiter(maxAttempts = PROBE_MAX_ATTEMPTS, windowMs = PROBE_WINDOW_MS),
) {
    val deps = ApiDeps(
        cfg, bangumi, repo, engine, fetcher, sessions, site,
        limiter, userData, subscriptions, credentialVerifier, probeLimiter,
    )

    routing {
        intercept(ApplicationCallPipeline.Call) {
            // 写操作必须同源。这一层不能因为「有鉴权」就省掉：
            //  1) 浏览器发跨站 POST 时不会预检（text/plain 属「简单请求」），用户访问恶意网页
            //     就可能被塞进一个带回溯正则的数据源（远程 DoS）或一条内网代理规则；
            //  2) Basic 凭据是浏览器按域缓存、会自动附带的，SameSite 只约束 Cookie，
            //     所以跨站请求可能带着用户输过的凭据打进来 —— 那时只有 Origin 能识破。
            if (call.isCrossSiteWrite(cfg)) {
                appLog.warn("拒绝跨站写请求: origin={} host={}",
                    call.request.headers[HttpHeaders.Origin], call.request.headers[HttpHeaders.Host])
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "CROSS_SITE_REQUEST"))
                finish()
                return@intercept
            }
            if (cfg.authEnabled && call.isProtectedApiCall()) {
                when (call.authenticate(cfg, site, limiter, credentialVerifier)) {
                    AuthResult.Authorized -> Unit
                    // 限速期间连「猜对了没有」都不回答：不下发质询（那会勾起浏览器反复弹窗），
                    // 也不给 401（401 与 403 的区别就是一个 yes/no 预言机）
                    AuthResult.Throttled -> {
                        call.response.header(
                            HttpHeaders.RetryAfter,
                            limiter.retryAfterSeconds(call.rateLimitKey(cfg)).toString(),
                        )
                        call.respond(
                            HttpStatusCode.TooManyRequests,
                            mapOf("error" to "凭据校验失败次数过多，请稍后再试"),
                        )
                        finish()
                    }
                    AuthResult.Failed -> {
                        // 只有请求确实带着（可能是陈旧的）会话时才清 Cookie：若浏览器里
                        // 还揣着一份服务端已不认的旧 Cookie，不清掉会让「登录成功、下一
                        // 个请求又 401」循环。反过来，跨站无 Cookie 的 401（例如恶意
                        // <img>）不能下发删除头，否则等于一个登出 CSRF。
                        if (call.sessionTokens().isNotEmpty()) {
                            call.response.clearSessionCookie(cfg)
                        }
                        // 只有「浏览器直接访问」这类请求才值得下发 Basic 质询（curl 与命令行也没有
                        // Sec-Fetch-Mode）。给 XHR/媒体请求下发质询会让浏览器弹原生口令框，
                        // 盖住前端自己的登录页 —— 而 hls.js 的分片请求全是 XHR。
                        if (call.wantsBasicChallenge()) {
                            call.response.header(
                                HttpHeaders.WWWAuthenticate,
                                "Basic realm=\"animeko-web\", charset=\"UTF-8\"",
                            )
                        }
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "UNAUTHORIZED"))
                        finish()
                    }
                }
            }
        }

        // ---- 免鉴权的两个入口：容器 HEALTHCHECK 与登录页 ----
        // 门控挂在路由子树上，绝不判断 URI 前缀：Ktor 的路由是按「段」匹配的，
        // 每段单独 URL 解码、空段被忽略，所以 `//api/selector/sources`、
        // `/%61pi/selector/sources` 都会命中真接口，
        // 而 request.local.uri 与 call.request.path() 拿到的原串都不以 "/api/" 开头
        // （实测：用前缀判断时，这两个写法能无凭据读到数据源列表与个人数据）。
        get(HEALTH_PATH) {
            call.respond(
                HealthResponse(
                    status = "ok",
                    version = "0.1.0",
                    authEnabled = cfg.authEnabled,
                    // 只承认会话 Cookie。这个接口免鉴权、也不该有副作用，
                    // 若它连 Authorization 头一起判定，就等于给「口令猜对了没有」
                    // 开了一个免限流的免费预言机。
                    authenticated = !cfg.authEnabled || call.hasValidSession(site, cfg),
                ),
            )
        }

        sessionRoutes(deps)
        bangumiRoutes(deps)
        selectorRoutes(deps)
        dataRoutes(deps)

        // ---- 视频/HLS 代理 ----
        get("/api/proxy") { proxyVideo(call, fetcher, cfg, sessions) }
    }
}

// ---- 鉴权 ----

/**
 * 限速记账用的客户端标识。
 *
 * 默认（trustedProxyHops=0，直连部署）用 remoteHost：X-Forwarded-For 完全由
 * 客户端提供，信它就能用一个伪造头无限重置计数器。
 *
 * 反代部署如实填了层数后，取 XFF 从右数第 N 个：每经过一跳可信代理，XFF 右端
 * 就被追加一个「这一跳看到的地址」，所以真实客户端在第 size-N 位。
 * 取值只接受 IP 形态且链长必须够——短于层数说明有人伪造/裁剪过，退回
 * remoteHost，别让垃圾值进键空间。
 */
