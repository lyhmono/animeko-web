package com.animeko.web

import com.animeko.web.api.configureApiRoutes
import com.animeko.web.api.configureStaticFrontend
import com.animeko.web.auth.LoginRateLimiter
import com.animeko.web.auth.SessionStore
import com.animeko.web.bangumi.BangumiClient
import com.animeko.web.bangumi.BgmChannelStore
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
import kotlinx.coroutines.launch
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
        // 栈被打穿（超深嵌套 JSON）。它必须排在 Throwable 前面，否则会被兜底吃成 500，
        // 而且 appLog.error 会为此落下上千行栈 —— 攻击者要的正是这个日志放大。
        // 这里当**最后一道**用：入站处有 JsonGuard 提前拦，绝大多数载荷到不了这一步。
        // 注意别高估它的覆盖面：UserDataStore.read 用的是 runCatching（catch Throwable），
        // 磁盘上那份过深的存档会被它接住并当成坏文件处理，**走不到这里**
        // —— 那条路径的后果由 ApiRoutesTest 的「深存档」用例盯着，不是这一格。
        exception<StackOverflowError> { call, _ ->
            appLog.warn("解析嵌套过深的 JSON: {}", call.request.path())
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "内容结构嵌套过深（JSON 层级超出限制）"),
            )
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
    // Bangumi 用独立的超时：社区镜像实测要 13~25 秒返回一次搜索，套用抓取那边
    // 的 20 秒上限会把它正好掐死（症状是「搜索页永远没有 Bangumi 结果」，
    // 而手动 curl 是通的）。测试注入 override 时不另开客户端。
    val bangumiFetcher = bangumiFetcherFor(cfg, fetcher, fetcherOverride)
    val bangumi = BangumiClient(
        bangumiFetcher,
        cfg.bangumiBaseUrls,
        channelStore = BgmChannelStore(cfg.bangumiChannelFile),
        // 通道地址会被服务端拿去发请求，内网拦截与 proxy / 订阅同一条规则
        allowPrivateTargets = cfg.allowPrivateTargets,
    )
    val repo = SelectorRepository(cfg.selectorsDir)
    val engine = SelectorEngine(fetcher, cfg.allowPrivateTargets)
    val sessions = proxySessions
    val userData = UserDataStore(cfg.userDataFile)
    val subscriptions = SubscriptionService(fetcher, repo, cfg.allowPrivateTargets)

    // 示例源先落地（纯本地文件操作，不开网络），保证列表立刻非空；
    // 默认订阅随后异步导入 —— 绝不在模块初始化里做网络 I/O：订阅站慢或挂了会把
    // 启动卡住，而端口还没监听时连健康检查都过不去。
    repo.seedIfEmpty()
    if (cfg.autoImportUrls.isNotEmpty()) {
        launch {
            val summaries = runCatching {
                subscriptions.autoImport(cfg.autoImportUrls, cfg.subscriptionAutoTiers)
            }.getOrElse { e ->
                appLog.warn("自动导入订阅异常：{}", e.message ?: e::class.simpleName)
                emptyList()
            }
            appLog.info("订阅自动导入完成：{}", summaries.joinToString(" ; "))
        }
    }

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
 * 给 Bangumi 挑一个客户端。三个分支的含义见 module() 里那段注释
 * （社区镜像实测要 13~25 秒，套用抓取那边 20 秒的上限会把它正好掐死）。
 *
 * 单独抽出来只有一个理由：**这是一条所有测试都碰不到的分支**。
 * 测试一律注入 fetcherOverride（为了不真打外网），于是第一个分支永远命中，
 * 后面两格从未被执行过 —— 有人把 `cfg.bangumiTimeoutMs` 抄成 `cfg.requestTimeoutMs`，
 * 或者把这两个变量写反，全套用例照样全绿，而线上表现是
 * 「搜索页永远没有 Bangumi 结果」，且手动 curl 是通的（这正是历史上出现过的那次）。
 * 抽成函数后，MainWiringTest 能直接对三个分支各要一条断言。
 */
internal fun bangumiFetcherFor(
    cfg: AppConfig,
    fetcher: Fetcher,
    fetcherOverride: Fetcher?,
    create: (Long) -> Fetcher = { JdkFetcher(timeoutMs = it) },
): Fetcher = when {
    fetcherOverride != null -> fetcher
    cfg.bangumiTimeoutMs == cfg.requestTimeoutMs -> fetcher
    else -> create(cfg.bangumiTimeoutMs)
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
