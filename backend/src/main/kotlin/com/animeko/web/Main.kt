package com.animeko.web

import com.animeko.web.api.configureApiRoutes
import com.animeko.web.api.configureStaticFrontend
import com.animeko.web.auth.LoginRateLimiter
import com.animeko.web.auth.SessionStore
import com.animeko.web.bangumi.BangumiClient
import com.animeko.web.bangumi.SubjectNotFoundException
import com.animeko.web.config.AppConfig
import com.animeko.web.data.UserDataStore
import com.animeko.web.http.Fetcher
import com.animeko.web.http.JdkFetcher
import com.animeko.web.http.UpstreamException
import com.animeko.web.http.UrlGuard
import com.animeko.web.proxy.ProxySessionStore
import com.animeko.web.selector.SelectorEngine
import com.animeko.web.selector.SelectorException
import com.animeko.web.selector.SelectorRepository
import com.animeko.web.selector.SubscriptionService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.matchContentType
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

fun main() {
    val cfg = AppConfig()
    embeddedServer(Netty, host = cfg.host, port = cfg.port) {
        module(cfg)
    }.start(wait = true)
}

fun Application.module(
    cfg: AppConfig = AppConfig(),
    fetcherOverride: Fetcher? = null,
    proxySessions: ProxySessionStore = ProxySessionStore(),
    siteSessions: SessionStore = SessionStore(),
    loginLimiter: LoginRateLimiter = LoginRateLimiter(),
) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }
    // 前端产物未压缩直出有 1.1MB JS，移动端首屏很慢。
    // 只对文本类响应压缩：视频/分片走流式，压缩只会白烧 CPU 还可能破坏 Range 透传。
    install(Compression) {
        gzip {
            matchContentType(
                ContentType.Text.CSS,
                ContentType.Text.Html,
                ContentType.Text.JavaScript,
                ContentType.Text.Plain,
                ContentType.Application.Json,
                ContentType("application", "javascript"),
                ContentType("application", "x-javascript"),
                ContentType("application", "vnd.apple.mpegurl"),
            )
        }
    }
    install(StatusPages) {
        // 日志里只记路径、不记完整查询串：/api/proxy 的查询串含 sid（能力令牌）
        // 与媒体地址，落进日志文件就等于一份可重放的代理 URL
        exception<SelectorException> { call, cause ->
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to (cause.message ?: "selector error")))
        }
        // 条目在 Bangumi 已删除/不存在：透传成 404，而不是被 withFallback 拖成 502
        exception<SubjectNotFoundException> { call, cause ->
            appLog.info("Bangumi 条目不存在: {}", cause.message)
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Bangumi 未收录该条目"))
        }
        // 拒绝原因里带着「主机名 -> 内网 IP」的映射，对任何能连到这台机器的人都是网络拓扑泄露，
        // 所以细节只进日志。目标被 SSRF 规则拦下：明确 403，而不是混进 500
        exception<UrlGuard.Blocked> { call, cause ->
            appLog.warn("拒绝访问受限目标: {} <- {}", cause.message, call.request.path())
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "目标地址不被允许（仅允许公网 http/https）"))
        }
        exception<UpstreamException> { call, cause ->
            appLog.warn("上游失败: {} <- {}", cause.message, call.request.path())
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to (cause.message ?: "upstream error")))
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "bad request")))
        }
        exception<Throwable> { call, cause ->
            appLog.error("未处理异常: {}", call.request.path(), cause)
            // 兜底不回显 cause.message：里面常带路径/上游地址等内部信息，日志里已有完整堆栈
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "INTERNAL_ERROR"))
        }
    }

    val fetcher = fetcherOverride ?: JdkFetcher(
        timeoutMs = cfg.requestTimeoutMs,
        streamTimeoutMs = cfg.proxyTimeoutMs,
    )
    val bangumi = BangumiClient(fetcher, cfg.bangumiBaseUrls)
    val repo = SelectorRepository(cfg.selectorsDir)
    repo.seedIfEmpty()
    val engine = SelectorEngine(fetcher, cfg.allowPrivateTargets)
    val sessions = proxySessions
    val userData = UserDataStore(cfg.userDataFile)
    val subscriptions = SubscriptionService(fetcher, repo, cfg.allowPrivateTargets)

    configureApiRoutes(
        cfg, bangumi, repo, engine, fetcher, sessions,
        siteSessions, loginLimiter, userData, subscriptions,
    )
    configureStaticFrontend(cfg)

    if (cfg.authEnabled) {
        appLog.info("登录鉴权已启用，未登录只能访问 /api/health 与登录接口（监听 {}）", cfg.host)
    } else {
        appLog.warn(
            "登录鉴权未启用：设置 ANIMEKO_AUTH_USER/ANIMEKO_AUTH_PASSWORD 或 ANIMEKO_AUTH_TOKEN 即可开启。" +
                "服务监听 {}，若这台机器可从公网访问，所有数据源与代理接口都是裸奔状态。",
            cfg.host,
        )
    }
    checkFileNameEncoding()
}

/**
 * LANG 没设置时 sun.jnu.encoding 会退化成 ASCII，中文数据源名在 Paths.get 这一步就失败。
 * 症状是「导入永远失败 / 示例源凭空消失」，而原始报错没人看得懂，所以启动时自检并说人话。
 */
private fun checkFileNameEncoding() {
    val probe = runCatching { java.nio.file.Paths.get("编码自检-测试.json") }
    if (probe.isSuccess) return
    appLog.error(
        "文件名字符集不可用（sun.jnu.encoding={}）：包含中文的数据源名将无法落盘。" +
            "请给进程设置 LANG=C.UTF-8 与 LC_ALL=C.UTF-8 后重启。",
        System.getProperty("sun.jnu.encoding") ?: "未知",
    )
}
