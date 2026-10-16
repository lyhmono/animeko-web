package com.animeko.web.api

import com.animeko.web.auth.LoginRateLimiter
import com.animeko.web.auth.SessionStore
import com.animeko.web.bangumi.BangumiClient
import com.animeko.web.config.AppConfig
import com.animeko.web.appLog
import com.animeko.web.data.UserDataStore
import com.animeko.web.http.Fetcher
import com.animeko.web.http.StreamResult
import com.animeko.web.http.UrlGuard
import com.animeko.web.http.UpstreamException
import com.animeko.web.proxy.HlsRewriter
import com.animeko.web.proxy.ProxySessionStore
import com.animeko.web.selector.SelectorEngine
import com.animeko.web.selector.SelectorException
import com.animeko.web.selector.SelectorRepository
import com.animeko.web.selector.SelectorSourceArguments
import com.animeko.web.selector.SubscriptionService
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.contentLength
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readAvailable
import io.ktor.server.response.ApplicationResponse
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest

private val prettyJson = Json { ignoreUnknownKeys = true }

private const val HEALTH_PATH = "/api/health"

private const val LOGIN_PATH = "/api/session/login"
private const val LOGOUT_PATH = "/api/session/logout"

/** 登录会话 Cookie 名。 */
private const val SESSION_COOKIE = "animeko_session"

private const val PROXY_PATH = "/api/proxy"

/** 播放清单体积上限：防止有人把 .m3u8 指向一个大文件，readText() 直接把堆吃光。 */
private const val MAX_PLAYLIST_BYTES = 4L * 1024 * 1024

@kotlinx.serialization.Serializable
data class HealthResponse(
    val status: String,
    val version: String,
    val authEnabled: Boolean,
    /** 未启用鉴权时恒为 true；启用时反映本次请求携带的凭据是否有效 */
    val authenticated: Boolean,
)

@kotlinx.serialization.Serializable
data class VideoSourceResponse(
    /** 解析出的媒体地址，仅便于排查与手动复放 */
    val url: String,
    /** 交给播放器的代理地址（凭据不在里面） */
    val proxyPath: String,
)

@kotlinx.serialization.Serializable
data class SourceEnabledResponse(
    val name: String,
    val enabled: Boolean,
)

/**
 * 登录/注销返回。必须是显式模型：`mapOf("authenticated" to true, "user" to ...)` 会被推断成
 * Map<String, Any>，编译通过、序列化期才报 Serializer not found，用户看到的是一个 500 空登录页。
 */
@kotlinx.serialization.Serializable
data class SessionStateResponse(
    val authenticated: Boolean,
    val user: String = "",
)

@kotlinx.serialization.Serializable
data class DataSavedResponse(
    /** 落盘后的字节数，便于前端在「收藏太多写不进去」时给出可解释的提示 */
    val bytes: Long,
)

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
) {
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
                when (call.authenticate(cfg, site, limiter)) {
                    AuthResult.Authorized -> Unit
                    // 限速期间连「猜对了没有」都不回答：不下发质询（那会勾起浏览器反复弹窗），
                    // 也不给 401（401 与 403 的区别就是一个 yes/no 预言机）
                    AuthResult.Throttled -> {
                        call.response.header(HttpHeaders.RetryAfter, limiter.windowSeconds().toString())
                        call.respond(
                            HttpStatusCode.TooManyRequests,
                            mapOf("error" to "凭据校验失败次数过多，请稍后再试"),
                        )
                        finish()
                    }
                    AuthResult.Failed -> {
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
                    authenticated = !cfg.authEnabled || call.hasValidSession(site),
                ),
            )
        }

        // ---- 登录 / 注销 ----
        post(LOGIN_PATH) {
            // CSRF 检查在根 intercept 里，登录接口也在它的覆盖范围内（只豁免鉴权，不豁免 CSRF）
            val clientKey = call.request.local.remoteHost
            if (limiter.blocked(clientKey)) {
                call.response.header(HttpHeaders.RetryAfter, (limiter.windowSeconds()).toString())
                call.respond(
                    HttpStatusCode.TooManyRequests,
                    mapOf("error" to "失败次数过多，请稍后再试"),
                )
                return@post
            }
            // 请求体只能读一次：分两次 bodyField(...) 会让第二个字段永远为空，
            // 表现是「口令明明对了却提示请填写口令」。
            val form = bodyObject(call)
            val username = form?.stringField("username").orEmpty()
            val password = form?.stringField("password").orEmpty()
            when {
                !cfg.authEnabled -> call.respond(
                    HttpStatusCode.Conflict,
                    mapOf("error" to "服务端未配置登录凭据，无需登录"),
                )
                form == null || password.isEmpty() -> call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "请填写口令"),
                )
                !credentialsMatch(cfg, username, password) -> {
                    limiter.onFailure(clientKey)
                    appLog.warn(
                        "登录失败: user={} ip={} 累计={}",
                        username.take(40), clientKey, limiter.attempts(clientKey),
                    )
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "账号或口令不正确"))
                }
                else -> {
                    limiter.onSuccess(clientKey)
                    val token = site.create()
                    call.response.setSessionCookie(cfg, token, site.ttlSeconds())
                    call.respond(
                        SessionStateResponse(
                            authenticated = true,
                            // 只回显账号名，绝不含口令或 token
                            user = cfg.authUser ?: "token",
                        ),
                    )
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
                    authenticated = !cfg.authEnabled || call.hasValidSession(site),
                ),
            )
        }

        // ---- 登录 / 注销 ----
        post(LOGIN_PATH) {
            // CSRF 检查在根 intercept 里，登录接口也在它的覆盖范围内（只豁免鉴权，不豁免 CSRF）
            val clientKey = call.request.local.remoteHost
            if (limiter.blocked(clientKey)) {
                call.response.header(HttpHeaders.RetryAfter, (limiter.windowSeconds()).toString())
                call.respond(
                    HttpStatusCode.TooManyRequests,
                    mapOf("error" to "失败次数过多，请稍后再试"),
                )
                return@post
            }
            // 请求体只能读一次：分两次 bodyField(...) 会让第二个字段永远为空，
            // 表现是「口令明明对了却提示请填写口令」。
            val form = bodyObject(call)
            val username = form?.stringField("username").orEmpty()
            val password = form?.stringField("password").orEmpty()
            when {
                !cfg.authEnabled -> call.respond(
                    HttpStatusCode.Conflict,
                    mapOf("error" to "服务端未配置登录凭据，无需登录"),
                )
                form == null || password.isEmpty() -> call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "请填写口令"),
                )
                !credentialsMatch(cfg, username, password) -> {
                    limiter.onFailure(clientKey)
                    appLog.warn(
                        "登录失败: user={} ip={} 累计={}",
                        username.take(40), clientKey, limiter.attempts(clientKey),
                    )
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "账号或口令不正确"))
                }
                else -> {
                    limiter.onSuccess(clientKey)
                    val token = site.create()
                    call.response.setSessionCookie(cfg, token, site.ttlSeconds())
                    call.respond(
                        SessionStateResponse(
                            authenticated = true,
                            // 只回显账号名，绝不含口令或 token
                            user = cfg.authUser ?: "token",
                        ),
                    )
                }
            }
        }

        post(LOGOUT_PATH) {
            call.cookieValue(SESSION_COOKIE)?.let { site.invalidate(it) }
            call.response.setSessionCookie(cfg, "", 0)
            call.respond(SessionStateResponse(authenticated = false))
        }

        // ---- Bangumi 元数据 ----
        get("/api/bangumi/search") {
            val q = call.request.queryParameters["q"]?.trim().orEmpty()
            if (q.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing q"))
            } else {
                call.respond(bangumi.search(q))
            }
        }

        get("/api/bangumi/subjects/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid id"))
            } else {
                call.respond(bangumi.subject(id))
            }
        }

        // ---- 数据源管理 ----
        get("/api/selector/sources") {
            call.respond(repo.list())
        }

        post("/api/selector/sources") {
            val text = call.receiveCappedText()
            try {
                val args = repo.importJson(text)
                repo.save(args)
                call.respond(HttpStatusCode.Created, args)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "invalid config")))
            }
        }

        post("/api/selector/sources/{name}/enable") {
            val name = call.parameters["name"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val body = call.receiveCappedText()
            // 必须显式给出布尔值：早期实现把「缺字段」当 false、「解析异常」当 true，
            // 同一个空 body 会时而停用时而启用，语义不可预测。
            val enabled = runCatching {
                prettyJson.parseToJsonElement(body).jsonObject["enabled"]?.jsonPrimitive?.booleanOrNull
            }.getOrNull()
            if (enabled == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "enabled 必须是布尔值"))
            } else if (repo.setEnabled(name, enabled)) {
                call.respond(SourceEnabledResponse(name = name, enabled = enabled))
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "source not found"))
            }
        }

        delete("/api/selector/sources/{name}") {
            val name = call.parameters["name"]
            if (name != null && repo.delete(name)) {
                call.respond(mapOf("deleted" to name))
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "source not found"))
            }
        }

        // ---- 聚合订阅：先预览、再挑着导入 ----
        post("/api/selector/subscriptions/preview") {
            val form = bodyObject(call)
            val url = form?.stringField("url").orEmpty()
            val text = form?.stringField("text").orEmpty()
            if (url.isBlank() && text.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "需要提供订阅地址 url 或订阅正文 text"))
            } else {
                call.respond(
                    subscriptions.preview(
                        url = url.ifBlank { null },
                        text = text.ifBlank { null },
                        label = form?.stringField("label").orEmpty(),
                    ),
                )
            }
        }

        post("/api/selector/subscriptions/import") {
            val form = bodyObject(call)
            val names = form?.get("names")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            val url = form?.stringField("url").orEmpty()
            val text = form?.stringField("text").orEmpty()
            if (names.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "没有选择要导入的数据源"))
            } else if (url.isBlank() && text.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "需要提供订阅地址 url 或订阅正文 text"))
            } else {
                val result = subscriptions.importSelected(
                    url = url.ifBlank { null },
                    text = text.ifBlank { null },
                    names = names,
                )
                // 导入完立刻把最新列表回给前端，省一次「导入后再拉列表」的时序问题
                call.respond(result)
            }
        }

        // ---- 个人数据（收藏 / 历史 / 进度 / 搜索记录）----
        get("/api/data") {
            val snapshot = userData.read()
            call.response.header(HttpHeaders.ETag, "\"${snapshot.etag}\"")
            call.respondText(snapshot.text, ContentType.Application.Json)
        }

        put("/api/data") {
            val text = call.receiveCappedText()
            // 客户端带上它读到的版本时才做条件写；不带（curl、旧版前端）就退回无条件覆盖
            val ifMatch = call.request.headers[HttpHeaders.IfMatch]
                ?.trim()
                ?.trim('"')
                ?.takeIf { it.isNotEmpty() }
            try {
                val snapshot = userData.write(text, ifMatch)
                call.response.header(HttpHeaders.ETag, "\"${snapshot.etag}\"")
                // 回字节数：前端可以据此提示「数据快满了」，而不是只有一句失败
                call.respond(DataSavedResponse(bytes = text.toByteArray(Charsets.UTF_8).size.toLong()))
            } catch (stale: UserDataStore.Stale) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to "DATA_STALE"))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "个人数据格式不正确")))
            }
        }

        // ---- 数据源采集 ----
        post("/api/selector/sources/{name}/search") {
            val source = repo.find(call.parameters["name"] ?: "")
            if (source == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "source not found"))
            } else {
                val keyword = bodyField(call, "keyword")
                if (keyword.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing keyword"))
                } else {
                    call.respond(engine.search(source.arguments.searchConfig, keyword))
                }
            }
        }

        post("/api/selector/sources/{name}/episodes") {
            val source = repo.find(call.parameters["name"] ?: "")
            if (source == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "source not found"))
            } else {
                val subjectUrl = bodyField(call, "subjectUrl")
                if (subjectUrl.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing subjectUrl"))
                } else {
                    call.respond(engine.episodes(source.arguments.searchConfig, subjectUrl))
                }
            }
        }

        post("/api/selector/sources/{name}/video") {
            val source = repo.find(call.parameters["name"] ?: "")
            if (source == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "source not found"))
            } else {
                val playUrl = bodyField(call, "playUrl")
                if (playUrl.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing playUrl"))
                } else {
                    val target = engine.resolveVideo(source.arguments.searchConfig, playUrl)
                    // 凭据换成服务端会话号后再交给前端：浏览器只需要一个能用的代理地址
                    val sid = sessions.put(
                        mediaUrl = target.url,
                        userAgent = target.userAgent,
                        referer = target.referer,
                        cookie = target.cookie,
                    )
                    call.respond(
                        VideoSourceResponse(
                            url = target.url,
                            proxyPath = HlsRewriter.proxyUrl(PROXY_PATH, target.url, sid),
                        ),
                    )
                }
            }
        }

        // ---- 视频/HLS 代理 ----
        get("/api/proxy") { proxyVideo(call, fetcher, cfg, sessions) }
    }
}

// ---- 静态前端 ----

fun Application.configureStaticFrontend(cfg: AppConfig) {
    val dir = File(cfg.frontendDir).absoluteFile
    if (!dir.isDirectory) {
        appLog.warn("前端目录不存在，跳过静态托管: {}", dir.absolutePath)
        return
    }
    routing {
        // 静态文件 + SPA 回退（单一路由显式处理）。
        // 不使用 staticFiles()：它与 catch-all 路由共存时不会消费文件请求，
        // 导致 JS/CSS 全部被 SPA 回退劫持成 index.html（前端白屏）。
        // 这里显式把请求路径解析到文件；找不到再回退 index.html。
        get("/{...}") {
            val path = call.request.path()
            // 归一化后再判：`//api/nope`、`/%61pi/nope` 也算 api 路径，
            // 不能回退成 index.html（那会把「api 打错了」变成一份 200 的 HTML）
            if (call.pathSegments().firstOrNull() == "api" || path.startsWith("/api/") || path == "/api") return@get
            val rel = path.removePrefix("/")
            val index = File(dir, "index.html")
            val file = if (rel.isEmpty()) index else dir.resolve(rel).normalize()
            when {
                file.isFile && file.startsWith(dir) -> {
                    when {
                        // 带内容哈希的产物可以长期强缓存
                        rel.startsWith("assets/") ->
                            call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
                        // 入口文档（含直接访问 /index.html）必须每次回源：
                        // 缓存住它就等于缓存住一批重新部署后已不存在的 chunk 文件名
                        file == index -> call.response.header(HttpHeaders.CacheControl, "no-cache")
                    }
                    call.respondFile(file)
                }
                // 缺失的静态资源必须 404，不能回退 index.html：
                // 那等于把 HTML 当 JS/CSS 返回（200 + text/html），浏览器报模块 MIME 错误；
                // 重新部署后 chunk 文件名变了，用户缓存里的旧 index.html 就会永久白屏。
                looksLikeAsset(rel) -> call.respond(HttpStatusCode.NotFound)
                else -> {
                    if (index.isFile) {
                        call.response.header(HttpHeaders.CacheControl, "no-cache")
                        call.respondFile(index)
                    } else {
                        call.respond(HttpStatusCode.NotFound)
                    }
                }
            }
        }
    }
}

private val assetExtensionRegex = Regex("""\.[A-Za-z0-9]{1,8}$""")

private fun looksLikeAsset(rel: String): Boolean =
    assetExtensionRegex.containsMatchIn(rel.substringAfterLast('/', rel))

// ---- 鉴权 ----

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
private fun ApplicationCall.isCrossSiteWrite(cfg: AppConfig): Boolean {
    if (cfg.allowCrossOrigin) return false
    if (pathSegments().firstOrNull() != "api") return false
    val method = request.local.method
    if (method == HttpMethod.Get || method == HttpMethod.Head || method == HttpMethod.Options) return false
    val origin = request.headers[HttpHeaders.Origin] ?: return false
    val originHost = runCatching { URI(origin).host }
        .getOrNull()?.lowercase()?.trim('[', ']')?.takeIf { it.isNotBlank() }
        ?: return true // Origin 为 "null"（沙箱 iframe / file:// 页面）按跨站处理
    val hostHeader = (request.headers[HttpHeaders.Host] ?: request.local.serverHost).lowercase().trim()
    val hostName = if (hostHeader.startsWith('[')) {
        hostHeader.substringAfter('[').substringBefore(']')
    } else {
        hostHeader.substringBeforeLast(':')
    }
    return originHost != hostName
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
private fun ApplicationCall.isProtectedApiCall(): Boolean {
    val segments = pathSegments()
    if (segments.firstOrNull() != "api") return false
    return segments !in PUBLIC_API_PATHS
}

private val PUBLIC_API_PATHS = setOf(
    listOf("api", "health"),
    listOf("api", "session", "login"),
)

/**
 * 与路由一致的归一化：按 / 拆段、丢掉空段、逐段 URL 解码。
 * 绝对形式请求行（`GET http://host/api/x`）先剥掉 scheme://host。
 */
private fun ApplicationCall.pathSegments(): List<String> {
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

private fun ApplicationCall.hasValidSession(site: SessionStore): Boolean =
    cookieValue(SESSION_COOKIE)?.let { site.isValid(it) } == true

/**
 * 这个 401 该不该附 Basic 质询。
 * 给 XHR 与媒体子资源附质询会让浏览器弹原生口令框，直接盖住前端自己的登录页，
 * 而 hls.js 的分片请求全是 XHR；没有 Sec-Fetch-Mode 的请求（curl、脚本）
 * 才是真的需要质询弹窗才能输入凭据的那类客户端。
 */
private fun ApplicationCall.wantsBasicChallenge(): Boolean {
    val mode = request.headers["Sec-Fetch-Mode"]?.lowercase()?.trim()
    if (mode.isNullOrEmpty()) return true
    return mode == "navigate"
}

private enum class AuthResult { Authorized, Failed, Throttled }

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
private fun ApplicationCall.authenticate(
    cfg: AppConfig,
    site: SessionStore,
    limiter: LoginRateLimiter,
): AuthResult {
    if (hasValidSession(site)) return AuthResult.Authorized
    val header = request.headers["Authorization"] ?: return AuthResult.Failed
    val key = request.local.remoteHost
    if (limiter.blocked(key)) return AuthResult.Throttled
    val ok = headerAuthorized(cfg, site, header)
    if (ok) limiter.onSuccess(key) else limiter.onFailure(key)
    return if (ok) AuthResult.Authorized else AuthResult.Failed
}

private fun headerAuthorized(cfg: AppConfig, site: SessionStore, header: String): Boolean {
    if (header.startsWith("Bearer ")) {
        val token = header.removePrefix("Bearer ").trim()
        if (token.isEmpty()) return false
        // 也接受登录会话号：curl -H "Authorization: Bearer <会话号>" 便于脚本复放
        if (site.isValid(token)) return true
        return cfg.authToken != null && constantTimeEquals(token, cfg.authToken)
    }
    if (header.startsWith("Basic ")) {
        val decoded = runCatching {
            String(java.util.Base64.getDecoder().decode(header.removePrefix("Basic ").trim()), Charsets.UTF_8)
        }.getOrNull() ?: return false
        val parts = decoded.split(":", limit = 2)
        if (parts.size != 2) return false
        return credentialsMatch(cfg, parts[0], parts[1])
    }
    return false
}

/** Basic 质询弹窗 / Authorization 头用的凭据匹配。 */
private fun credentialsMatch(cfg: AppConfig, user: String, pass: String): Boolean {
    if (pass.isEmpty()) return false
    if (cfg.authUser != null && cfg.authPassword != null) {
        return constantTimeEquals(user, cfg.authUser) && constantTimeEquals(pass, cfg.authPassword)
    }
    // 只配了 token 的部署：把 token 当口令接受（用户名任意），
    // 否则浏览器的质询弹窗无法完成认证，播放器与封面会全部 401。
    return cfg.authToken != null && constantTimeEquals(pass, cfg.authToken)
}

/**
 * Cookie 头自己解析：Ktor 的 RequestCookies 会按 URI_ENCODING 解码值，
 * 而我们只放十六进制会话号，无需编解码，也就不会被「同名多值/含 = 的口令」绕开。
 */
private fun ApplicationCall.cookieValue(name: String): String? =
    request.headers.getAll(HttpHeaders.Cookie)
        ?.asSequence()
        ?.flatMap { it.split(';').asSequence() }
        ?.map { it.trim() }
        ?.firstNotNullOfOrNull { pair ->
            if (pair.substringBefore('=').trim() != name) return@firstNotNullOfOrNull null
            pair.substringAfter('=', "").trim().takeIf { it.isNotEmpty() }
        }

private fun ApplicationResponse.setSessionCookie(cfg: AppConfig, value: String, maxAgeSeconds: Long) {
    // SameSite=Strict：会话 Cookie 一旦种下，跨站请求就不会带上它，
    // 恶意站点连「借你的会话发一个写请求」都做不到（Origin 检查是第二层）。
    header(
        HttpHeaders.SetCookie,
        buildString {
            append(SESSION_COOKIE).append('=').append(value)
            append("; Path=/")
            append("; HttpOnly")
            append("; SameSite=Strict")
            append("; Max-Age=").append(maxAgeSeconds)
            if (cfg.cookieSecure) append("; Secure")
        },
    )
}

/** 凭据比较不走 String==（短路返回，长度/前缀可被时序探测）。 */
private fun constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

// ---- 视频代理实现 ----

private const val MAX_REDIRECTS = 5

private suspend fun proxyVideo(
    call: ApplicationCall,
    fetcher: Fetcher,
    cfg: AppConfig,
    sessions: ProxySessionStore,
) {
    // Ktor 已经解码过查询参数，不能再 URLDecoder 一次：
    // 带签名/令牌的地址几乎都含 % 和 +，二次解码会把 ?sign=a%2Bb 变成 ?sign=a b，
    // 表现为「解析成功但播放/清单 404」，且日志里看不出原因。
    val requested = call.request.queryParameters["url"]?.trim().orEmpty()
    if (requested.isBlank() || !requested.startsWith("http")) {
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid url"))
        return
    }
    var url = UrlGuard.requireAllowed(requested, cfg.allowPrivateTargets)

    // 上游所需请求头不再由查询参数带入：UA/Referer/Cookie 留在服务端会话库里，
    // URL 里只出现一个随机 sid。此前这些值会被复制到每一个分片地址上，
    // 等于把源站凭据批量写进浏览器历史与访问日志。
    val sid = call.request.queryParameters["sid"]?.trim().orEmpty()
    val headers = LinkedHashMap<String, String>()
    if (sid.isNotEmpty()) {
        // sid 是能力令牌，必须绑定目标主机：否则拿到一个 sid 就能用受害者的
        // Cookie 去请求攻击者指定的任意地址。
        val grant = sessions.grant(sid, url)
        when (grant.kind) {
            // 会话过期与「源站拒绝」必须可区分，否则排查播放故障只能猜
            ProxySessionStore.Kind.EXPIRED -> {
                call.respond(HttpStatusCode.Gone, mapOf("error" to "PROXY_SESSION_EXPIRED"))
                return
            }
            ProxySessionStore.Kind.HOST_NOT_ALLOWED -> {
                call.respond(
                    HttpStatusCode.Forbidden,
                    mapOf("error" to "PROXY_SESSION_TARGET_MISMATCH"),
                )
                return
            }
            ProxySessionStore.Kind.GRANTED -> headers.putAll(grant.headers)
        }
    }
    call.request.headers["Range"]?.let { if (it.isNotBlank()) headers["Range"] = it }

    var result = fetchStream(fetcher, url, headers)

    // 手动跟随重定向（最多 MAX_REDIRECTS 次）
    var redirects = 0
    while (result.status in 301..399 && redirects < MAX_REDIRECTS) {
        val location = result.header("location")
        result.body.close()
        if (location.isNullOrBlank()) break
        // 每一跳都重新校验：只校验入口地址的话，302 指向 127.0.0.1 照样打进内网。
        val next = UrlGuard.requireAllowed(
            SelectorEngine.absUrl(url, location),
            cfg.allowPrivateTargets,
        )
        if (hostOf(next) != hostOf(url)) {
            // 跨主机重定向不再带 Cookie，避免源站凭据被送给第三个域名
            headers.remove("Cookie")
        }
        url = next
        result = fetchStream(fetcher, url, headers)
        redirects++
    }

    val contentType = result.contentType
    val isHls = url.contains(".m3u8", ignoreCase = true) ||
        contentType?.contains("mpegurl", ignoreCase = true) == true

    if (isHls) {
        val text = readCapped(result.body, MAX_PLAYLIST_BYTES)
        val rewritten = HlsRewriter.rewrite(text, url, sid = sid)
        // 分片与密钥常在另一个主机（清单域 vs CDN 域）：由服务端从清单内容里登记，
        // 客户端无法自行扩大允许范围
        sessions.allowPlaylistHosts(sid, rewritten)
        call.respondText(rewritten, ContentType.parse("application/vnd.apple.mpegurl"))
        return
    }

    if (result.status in 301..399) {
        // 重定向次数用尽仍是 3xx：把 Location 透传给播放器自己续跳，而不是回一个无法跟随的裸 3xx
        result.header("location")?.let { call.response.header(HttpHeaders.Location, it) }
    }
    val status = runCatching { HttpStatusCode.fromValue(result.status) }.getOrDefault(HttpStatusCode.BadGateway)
    result.header("content-range")?.let { call.response.header("Content-Range", it) }
    result.header("accept-ranges")?.let { call.response.header("Accept-Ranges", it) }
    val contentLength = result.header("content-length")?.toLongOrNull()
    val ct = contentType?.let { runCatching { ContentType.parse(it) }.getOrNull() }
    call.respondOutputStream(ct, status, contentLength) {
        result.body.use { input -> input.copyTo(this) }
    }
}

private suspend fun fetchStream(fetcher: Fetcher, url: String, headers: Map<String, String>): StreamResult =
    try {
        fetcher.fetchStream(url, headers, followRedirects = false)
    } catch (e: UrlGuard.Blocked) {
        throw e
    } catch (e: Exception) {
        throw UpstreamException("拉取上游失败: ${e.message ?: e::class.simpleName}")
    }

private fun readCapped(input: InputStream, maxBytes: Long): String =
    input.use { stream ->
        val buffer = ByteArray(8192)
        val out = ByteArrayOutputStream()
        var total = 0L
        while (true) {
            val n = stream.read(buffer)
            if (n == -1) break
            total += n
            if (total > maxBytes) {
                throw UpstreamException("播放清单超过 ${maxBytes / 1024 / 1024}MB 上限")
            }
            out.write(buffer, 0, n)
        }
        String(out.toByteArray(), Charsets.UTF_8)
    }

private fun hostOf(url: String): String? = runCatching { URI(url).host?.lowercase() }.getOrNull()

/**
 * 请求体上限。登录接口免鉴权也可达，不封顶等于让来访者决定这台机器的堆用量
 * （接收侧此前完全没有上限，只有落盘前的 importJson 才校验大小 —— 那时已经读进内存了）。
 */
private const val MAX_BODY_BYTES = 1L * 1024 * 1024

private suspend fun bodyField(call: ApplicationCall, field: String): String? =
    bodyObject(call)?.stringField(field)

private suspend fun bodyObject(call: ApplicationCall): JsonObject? = try {
    prettyJson.parseToJsonElement(call.receiveCappedText()).jsonObject
} catch (e: Exception) {
    null
}

private fun JsonObject.stringField(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

private suspend fun ApplicationCall.receiveCappedText(maxBytes: Long = MAX_BODY_BYTES): String {
    // 先看 Content-Length 挡掉老实的超大请求，再逐块累加，防止 chunked 传输谎报长度
    val declared = request.contentLength()
    require(declared == null || declared <= maxBytes) { "请求体超过 ${maxBytes / 1024}KB 上限" }
    val channel = request.receiveChannel()
    val out = ByteArrayOutputStream()
    val chunk = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = channel.readAvailable(chunk)
        if (n == -1) break
        total += n
        require(total <= maxBytes) { "请求体超过 ${maxBytes / 1024}KB 上限" }
        out.write(chunk, 0, n)
    }
    return String(out.toByteArray(), Charsets.UTF_8)
}
