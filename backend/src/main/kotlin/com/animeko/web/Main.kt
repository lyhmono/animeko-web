package com.animeko.web

import com.animeko.web.api.configureApiRoutes
import com.animeko.web.api.configureStaticFrontend
import com.animeko.web.bangumi.BangumiClient
import com.animeko.web.bangumi.SubjectNotFoundException
import com.animeko.web.config.AppConfig
import com.animeko.web.danmaku.DandanplayClient
import com.animeko.web.danmaku.DanmakuNotConfiguredException
import com.animeko.web.http.Fetcher
import com.animeko.web.http.JdkFetcher
import com.animeko.web.http.UpstreamException
import com.animeko.web.http.UrlGuard
import com.animeko.web.proxy.ProxySessionStore
import com.animeko.web.selector.SelectorEngine
import com.animeko.web.selector.SelectorException
import com.animeko.web.selector.SelectorRepository
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
            appLog.warn("拒绝访问受限目标: {} <- {}", cause.message, call.request.local.uri)
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "目标地址不被允许（仅允许公网 http/https）"))
        }
        exception<UpstreamException> { call, cause ->
            appLog.warn("上游失败: {} <- {}", cause.message, call.request.local.uri)
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to (cause.message ?: "upstream error")))
        }
        // 弹幕未配置属于服务端缺配置，返回 503 而不是被兜底当成内部错误。
        // 注意：消息里的 DANMAKU_NOT_CONFIGURED 是与前端 PlayerPage 的字符串契约，别改
        exception<DanmakuNotConfiguredException> { call, cause ->
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("error" to (cause.message ?: "DANMAKU_NOT_CONFIGURED")),
            )
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "bad request")))
        }
        exception<Throwable> { call, cause ->
            appLog.error("未处理异常: {}", call.request.local.uri, cause)
            // 兜底不回显 cause.message：里面常带路径/上游地址等内部信息，日志里已有完整堆栈
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "INTERNAL_ERROR"))
        }
    }

    val fetcher = fetcherOverride ?: JdkFetcher(
        timeoutMs = cfg.requestTimeoutMs,
        streamTimeoutMs = cfg.proxyTimeoutMs,
    )
    val bangumi = BangumiClient(fetcher, cfg.bangumiBaseUrls)
    val danmaku = DandanplayClient(fetcher, cfg.danmakuAppId, cfg.danmakuAppSecret)
    val repo = SelectorRepository(cfg.selectorsDir)
    repo.seedIfEmpty()
    val engine = SelectorEngine(fetcher, cfg.allowPrivateTargets)
    val sessions = proxySessions

    configureApiRoutes(cfg, bangumi, danmaku, repo, engine, fetcher, sessions)
    configureStaticFrontend(cfg)
}
