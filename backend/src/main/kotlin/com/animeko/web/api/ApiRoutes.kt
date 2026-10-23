package com.animeko.web.api

import com.animeko.web.auth.LoginRateLimiter
import com.animeko.web.auth.SessionStore
import com.animeko.web.bangumi.BangumiClient
import com.animeko.web.bangumi.BgmChannelResponse
import com.animeko.web.config.AppConfig
import com.animeko.web.appLog
import com.animeko.web.data.UserDataStore
import com.animeko.web.http.Fetcher
import com.animeko.web.http.JsonGuard
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
import io.ktor.server.response.respondBytes
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
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
private const val SESSION_COOKIE = "animeko_session"
private const val HOST_PREFIXED_COOKIE = "__Host-$SESSION_COOKIE"

/** 写：按当前是否 https 选名字。 */
private fun sessionCookieName(cookieSecure: Boolean): String =
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
private fun ApplicationCall.sessionTokens(): List<String> =
    listOf(cookieValue(HOST_PREFIXED_COOKIE), cookieValue(SESSION_COOKIE))
        .filterNotNull()
        .filter { it.isNotEmpty() }
        .distinct()

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
                        // 顺手把两个会话 Cookie 名都过期掉：若浏览器里还揣着一份服务端已不认的
                        // 旧 Cookie（改过 ANIMEKO_COOKIE_SECURE 的部署就会这样），不清掉的话
                        // 它会一直被送上来，而用户看到的是「登录成功、下一个请求又 401」。
                        call.response.clearSessionCookie(cfg)
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
            val clientKey = call.rateLimitKey(cfg)
            // 顺序必须与 authenticate() 那条一致：**先验证，只有猜错的人才去问限速器**。
            // 原先是进门先 blocked()，于是超限窗口内连「输对了口令」也一律 429。
            // 那一格挡不住攻击者（他口令对的时候本来就该进去，等窗口过后再来一次就行），
            // 却能机主锁在自己家门外：会话只存内存，容器一重启就必须重新走登录页，
            // 而任何人都能持续把这条路占住。直连部署下限速键是来源 IP（攻击者只锁自己），
            // 但反代没配 ANIMEKO_TRUSTED_PROXY_HOPS 时全站共用代理那一个键 —— 实测
            // 8 次错口令后 POST /api/session/login 带**正确**口令回 429 Retry-After: 300，
            // 而同一时刻同一套凭据走 Authorization: Basic 打保护接口回 200：两条路径互相矛盾。
            // 猜测速率照样被封死：超限期间的错误口令仍是 429，且不再往计数里加。
            // 请求体只能读一次：分两次 bodyField(...) 会让第二个字段永远为空，
            // 表现是「口令明明对了却提示请填写口令」。
            val form = bodyObject(call, MAX_LOGIN_BODY_BYTES)
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
                    if (limiter.blocked(clientKey)) {
                        call.response.header(
                            HttpHeaders.RetryAfter,
                            limiter.retryAfterSeconds(clientKey).toString(),
                        )
                        call.respond(
                            HttpStatusCode.TooManyRequests,
                            mapOf("error" to "失败次数过多，请稍后再试"),
                        )
                    } else {
                        limiter.onFailure(clientKey)
                        appLog.warn(
                            "登录失败: user={} ip={} 累计={}",
                            username.take(40), clientKey, limiter.attempts(clientKey),
                        )
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "账号或口令不正确"))
                    }
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
            // 两个都作废：它们是同一台机器签发的同一份会话的两种写法
            call.sessionTokens().forEach { site.invalidate(it) }
            call.response.clearSessionCookie(cfg)
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

        // 每日放送表（首页用）。镜像不支持该端点时会 502 —— 前端把它当
        // 可选项：拉不到就整块隐藏，不影响页面其他部分。
        get("/api/bangumi/calendar") {
            call.respond(bangumi.calendar())
        }

        // 排行榜（发现页用）。limit/offset 直接来自查询串，必须夹紧：
        // 上游 limit 上限 50，而 offset 不设界等于允许任何人让服务器去翻一万页。
        get("/api/bangumi/ranking") {
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 24).coerceIn(1, 50)
            val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceIn(0, 5000)
            call.respond(bangumi.ranking(limit = limit, offset = offset))
        }

        // ---- Bangumi 通道（设置页：官方/镜像/自定义/自动）----
        get("/api/bangumi/channel") {
            val st = bangumi.channelState()
            call.respond(
                BgmChannelResponse(
                    channel = st.channel,
                    customUrls = st.customUrls,
                    effectiveBases = bangumi.effectiveBases(),
                    calendarSupported = bangumi.calendarSupported,
                ),
            )
        }

        // 保存通道选择。写接口天然被根 intercept 的 CSRF 检查覆盖，
        // 鉴权开启时也要求凭据 —— 只有站长能改全局上游。
        put("/api/bangumi/channel") {
            val form = bodyObject(call)
            val raw = form?.stringField("channel")?.trim()?.uppercase()
            val channel = when (raw) {
                null -> throw IllegalArgumentException("缺少 channel（AUTO/OFFICIAL/MIRROR/CUSTOM）")
                "AUTO" -> com.animeko.web.bangumi.BgmChannel.AUTO
                "OFFICIAL" -> com.animeko.web.bangumi.BgmChannel.OFFICIAL
                "MIRROR" -> com.animeko.web.bangumi.BgmChannel.MIRROR
                "CUSTOM" -> com.animeko.web.bangumi.BgmChannel.CUSTOM
                else -> throw IllegalArgumentException("无法识别的 channel「$raw」，只接受 AUTO/OFFICIAL/MIRROR/CUSTOM")
            }
            val custom = form?.stringArrayField("customUrls").orEmpty()
            val saved = bangumi.setChannel(com.animeko.web.bangumi.BgmChannelConfig(channel, custom))
            call.respond(
                BgmChannelResponse(
                    channel = saved.channel,
                    customUrls = saved.customUrls,
                    effectiveBases = bangumi.effectiveBases(),
                    calendarSupported = bangumi.calendarSupported,
                ),
            )
        }

        // 探测候选地址的连通性，让站长在保存前就看见「这个镜像不支持每日放送」。
        // body 可带目标通道（探测未保存的选项）；不带则探测当前已保存的通道。
        post("/api/bangumi/channel/probe") {
            val text = call.receiveCappedText()
            // 请求体只能读一次（Ktor 的 receiveChannel 不可重放），这里必须复用
            // 已读到的 text，不能再调 bodyObject —— 那会二次 receive 拿到空内容。
            // 解析失败统一转成 IllegalArgumentException（→ 400），
            // 否则 SerializationException 会落到 Throwable 兜底变成 500。
            val target = if (text.isBlank()) {
                null
            } else {
                val form = runCatching { prettyJson.parseToJsonElement(text).jsonObject }.getOrNull()
                    ?: throw IllegalArgumentException("探测请求必须是 JSON 对象")
                form.stringField("channel")?.trim()?.uppercase()?.let { r ->
                    val ch = when (r) {
                        "AUTO" -> com.animeko.web.bangumi.BgmChannel.AUTO
                        "OFFICIAL" -> com.animeko.web.bangumi.BgmChannel.OFFICIAL
                        "MIRROR" -> com.animeko.web.bangumi.BgmChannel.MIRROR
                        "CUSTOM" -> com.animeko.web.bangumi.BgmChannel.CUSTOM
                        else -> throw IllegalArgumentException("无法识别的 channel「$r」")
                    }
                    com.animeko.web.bangumi.BgmChannelConfig(ch, form.stringArrayField("customUrls"))
                }
            }
            call.respond(bangumi.probe(target))
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
            val names = form?.stringArrayField("names").orEmpty()
            val url = form?.stringField("url").orEmpty()
            val text = form?.stringField("text").orEmpty()
            // 预览时下发的内容指纹：对不上说明订阅在两步之间被更新过，
            // 此时按「当前内容」导入会得到一份和用户刚看过的清单不同的结果
            val fingerprint = form?.stringField("fingerprint")
            if (names.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "没有选择要导入的数据源"))
            } else if (url.isBlank() && text.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "需要提供订阅地址 url 或订阅正文 text"))
            } else {
                val result = subscriptions.importSelected(
                    url = url.ifBlank { null },
                    text = text.ifBlank { null },
                    names = names,
                    fingerprint = fingerprint,
                )
                // 导入完立刻把最新列表回给前端，省一次「导入后再拉列表」的时序问题
                call.respond(result)
            }
        }

        // ---- 个人数据（收藏 / 历史 / 进度 / 搜索记录）----
        get("/api/data") {
            val snapshot = userData.read()
            call.response.header(HttpHeaders.ETag, "\"${snapshot.etag}\"")
            // 个人数据（收藏/历史/进度）绝不能进任何缓存：这份响应的内容随「谁在问」而变，
            // 而 HTTP 里没有 Vary 能表达按会话用户区分。浏览器磁盘缓存一旦存下上一份，
            // 换账号（或注销后用另一个身份登录）后可能出现「看到别人的收藏」，
            // 甚至关掉网络还在放旧进度。SPA 本来就把它读进内存了，缓存这一份毫无收益。
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondText(snapshot.text, ContentType.Application.Json)
        }

        put("/api/data") {
            val text = call.receiveCappedText()
            // 客户端带上它读到的版本时才做条件写；不带（curl、旧版前端）就退回无条件覆盖
            // 原样交给数据层解释：If-Match 可以是 *、可以是逗号分隔的多个标签、
            // 也可以带 W/ 前缀。在这里 trim('"') 会把它们压成一个奇怪的字符串，
            // 于是「*」和「多值里含正确指纹」这两种合法写法都恒定 409
            val ifMatch = call.request.headers[HttpHeaders.IfMatch]?.trim()?.takeIf { it.isNotEmpty() }
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
            val source = repo.find(call.parameters["name"] ?: "", enabledOnly = true)
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
            val source = repo.find(call.parameters["name"] ?: "", enabledOnly = true)
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
            val source = repo.find(call.parameters["name"] ?: "", enabledOnly = true)
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
            // 缓存策略必须按**归一化后的**路径判断：`/assets/../index.html` 的原始 rel
            // 以 assets/ 开头，但实际文件是 index.html，不能给它 immutable。
            val normalizedRel = if (file == index) {
                "index.html"
            } else {
                file.relativeTo(dir).path.replace(File.separatorChar, '/')
            }
            when {
                file.isFile && file.startsWith(dir) -> {
                    when {
                        // 带内容哈希的产物可以长期强缓存
                        normalizedRel.startsWith("assets/") ->
                            call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
                        // 入口文档（含直接访问 /index.html）必须每次回源：
                        // 缓存住它就等于缓存住一批重新部署后已不存在的 chunk 文件名。
                        // sw.js 同理、且更要紧：浏览器发现 SW 文件有变化才会装新版本，
                        // 而它判断依据是响应本身 —— 缓存住就等于让更新永远装不上。
                        // manifest 一起带上，改图标/改名后要能立刻生效。
                        normalizedRel == "index.html" || normalizedRel == "sw.js" ||
                            normalizedRel == "manifest.webmanifest" ->
                            call.response.header(HttpHeaders.CacheControl, "no-cache")
                    }
                    if (normalizedRel == "manifest.webmanifest") {
                        // Ktor 的扩展名映射不认识 .webmanifest，会落到 octet-stream，
                        // 部分浏览器会因此直接忽略这份 manifest。显式给正确类型。
                        // 用 respondBytes 而不是 LocalFileContent：后者在这个 Ktor
                        // 版本里不在 io.ktor.http.content 下（编译期就报 Unresolved）。
                        // manifest 只有几百字节，读进内存没有负担。
                        call.respondBytes(file.readBytes(), ContentType.parse("application/manifest+json"))
                    } else {
                        call.respondFile(file)
                    }
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

/**
 * 缺失的静态资源要 404，不能回退成 index.html（那会让浏览器把 HTML 当 JS/CSS）。
 *
 * 判据只认两种：`/assets/` 下的构建产物，以及**根目录**带扩展名的文件。
 * 不能用「路径最后一段带扩展名」：数据源名本身可以带点（`Anime1.me`、`xx.720p`），
 * `/source/Anime1.me` 是 SPA 路由，按扩展名判会把它 404 掉 —— 站内跳转正常、
 * 一刷新或分享链接就变成 404。SPA 路由不会出现在根目录，这样既保住白屏防线，
 * 也不误伤带点的路由参数。
 */
private fun looksLikeAsset(rel: String): Boolean =
    rel.startsWith("assets/") ||
        (!rel.contains('/') && assetExtensionRegex.containsMatchIn(rel))

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
private fun ApplicationCall.rateLimitKey(cfg: AppConfig): String {
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

private fun looksLikeIpLiteral(value: String): Boolean {
    // 旧实现只判断「含冒号」，`abc:def` 也会被当成 IP 进入限速键空间。
    // 这里做字面校验、不解析 DNS：XFF 是自由文本，解析域名会变成出站请求。
    if (value.contains(':')) {
        return value.count { it == ':' } >= 2 &&
            value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' }
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
private fun ApplicationCall.isCrossSiteWrite(cfg: AppConfig): Boolean {
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
    // 端口从哪来，按可信度分三档；**取不到就放行** —— 宁可漏一格，也不要把每一次
    // 收藏都误杀成 403，那会逼用户去开 ANIMEKO_ALLOW_CROSS_ORIGIN 把整道防线拆掉。
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
        ?: return false
    val originPort = runCatching { URI(origin).port }.getOrNull()?.takeIf { it > 0 }
        ?: defaultPortFor(runCatching { URI(origin).scheme?.lowercase() }.getOrNull())
    // Origin 侧可以猜：它是浏览器给的可信值，scheme 一定在里面
    if (originPort == null) return false
    return originPort != hostPort
}

private fun defaultPortFor(scheme: String?): Int? = when (scheme) {
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
    sessionTokens().any { site.isValid(it) }

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
    // 顺序很重要：**先验证，只有失败了才问限速器**。
    // 反过来（先 blocked() 再验证）会让「超限期间的正确凭据」也被拒成 429 ——
    // 那挡不住攻击者（他手里本来就没有正确凭据），却能锁死合法用户：
    // 限速键在直连部署下就是来源 IP，而反代没配 ANIMEKO_TRUSTED_PROXY_HOPS 时
    // 全站共用一个键，于是任何人发 8 个错口令就能让整站在窗口期内谁都进不来，
    // 并且可以反复续期。爆破的预算照样被限（每窗口 8 次），只是不再拿它惩罚成功。
    val ok = headerAuthorized(cfg, site, header)
    if (ok) return AuthResult.Authorized
    val key = rateLimitKey(cfg)
    if (limiter.blocked(key)) return AuthResult.Throttled
    // 失败要记账，**成功不注销账**（成功在几行前就 return 了）。
    // 这里原先是 `if (ok) limiter.onSuccess(key) else limiter.onFailure(key)`，
    // 而 key 在直连部署下就是来源 IP：浏览器会自动附带缓存的 Basic 凭据，
    // 受害者每正常访问一次就把攻击者的失败计数清零 —— 只要这个 IP 上还有人活着，
    // 在线爆破就没有上限（反代没配 hops 时全站共用一个键，更容易触发）。
    // 登录接口那边仍然在成功时清账：那是「确实知道口令」才能触发的动作，性质不同。
    limiter.onFailure(key)
    return AuthResult.Failed
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
            append(sessionCookieName(cfg.cookieSecure)).append('=').append(value)
            append("; Path=/")
            append("; HttpOnly")
            append("; SameSite=Strict")
            append("; Max-Age=").append(maxAgeSeconds)
            if (cfg.cookieSecure) append("; Secure")
        },
    )
}

/** 凭据比较不走 String==（短路返回，长度/前缀可被时序探测）。 */
/**
 * 注销时把**两个**名字都覆盖掉。只清当前那一个会留下另一半：
 * 站长开过 HTTPS（`__Host-` 那份）又关掉之后，旧浏览器里仍然揣着另一份同名会话，
 * 而读侧两个名字都认 —— 「已注销」的账号会被那份复活。
 * 多下发一条无害：不存在的那个名字浏览器直接忽略。
 */
private fun ApplicationResponse.clearSessionCookie(cfg: AppConfig) {
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
    // 代理会把上游内容原样吐在同源下。CSP sandbox 只在「把代理地址当文档打开」时
    // 生效（媒体/图片子资源不受影响），可以挡住上游 HTML/JS 在站点源下执行；
    // 对 HTML/SVG/JS 再加 nosniff，避免浏览器按内容猜成脚本。
    call.response.header("Content-Security-Policy", "sandbox")
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
        // 没有 Location 就没得跟：把这 3xx 原样透传给播放器。
        // 此时**不能**关流 —— 后面的 readCapped / respondOutputStream 还要读它，
        // 而 JDK 的响应体流关掉再读直接抛异常，表现成一个与源站无关的 500。
        if (location.isNullOrBlank()) break
        // 每一跳都重新校验：只校验入口地址的话，302 指向 127.0.0.1 照样打进内网。
        val next = try {
            UrlGuard.requireAllowed(
                SelectorEngine.absUrl(url, location),
                cfg.allowPrivateTargets,
            )
        } catch (e: Throwable) {
            // 被拦下的这一跳不会再有消费者，必须关掉：否则每个「302 → 内网」的
            // 探测请求都留下一条没人回收的上游连接，而这正是免鉴权可试的路径。
            runCatching { result.body.close() }
            throw e
        }
        if (UrlGuard.hostOf(next) != UrlGuard.hostOf(url)) {
            // 跨主机重定向不再带 Cookie，避免源站凭据被送给第三个域名
            headers.remove("Cookie")
        }
        url = next
        result.body.close()
        result = fetchStream(fetcher, url, headers)
        redirects++
    }

    val contentType = result.contentType
    if (contentType?.let {
            it.contains("html", ignoreCase = true) ||
                it.contains("svg", ignoreCase = true) ||
                it.contains("javascript", ignoreCase = true)
        } == true
    ) {
        call.response.header("X-Content-Type-Options", "nosniff")
    }
    val isHls = url.contains(".m3u8", ignoreCase = true) ||
        contentType?.contains("mpegurl", ignoreCase = true) == true

    if (isHls) {
        val upstreamOk = result.status == 200 || result.status == 206
        val text = readCapped(result.body, MAX_PLAYLIST_BYTES)
        // 只有 2xx 才改写。以前无论上游回什么都 respondText(...)，而它默认 200：
        // 源站一个 403 防盗链页会被包装成「一份合法的 m3u8 清单」发回播放器，
        // hls.js 既不会报错也不会切下一条线路/下一个源，用户只看到「加载中」然后黑屏。
        val rewritten = if (upstreamOk) HlsRewriter.rewriteDetailed(text, url, sid = sid) else null
        val body = rewritten?.body ?: text
        if (rewritten != null) {
            // 分片与密钥常在另一个主机（清单域 vs CDN 域）：只登记**这次真正改写出的**
            // URI 主机，不由客户端、也不由评论行里的任意字符串扩大范围。
            sessions.allowHosts(sid, rewritten.hosts)
        }
        val outType = if (upstreamOk) {
            ContentType.parse("application/vnd.apple.mpegurl")
        } else {
            // 拒绝/出错时沿用上游的类型：把 403 的 HTML 错误页标成 m3u8，
            // 播放器就会把它当清单去解析，而不是当失败去换一条线路
            contentType?.let { runCatching { ContentType.parse(it) }.getOrNull() } ?: ContentType.Text.Plain
        }
        call.respondText(body, outType, if (upstreamOk) HttpStatusCode.OK else HttpStatusCode.fromValue(result.status))
        return
    }

    if (result.status in 301..399) {
        // 跳转次数用尽后如果还有有效 Location，不能再把它交给浏览器：
        // 这一跳从未经过 UrlGuard 校验，等于让页面替服务器去访问一个未验证的地址
        // （浏览器侧的内网探测、混合内容等）。没有 Location（或只有空白值）的 3xx
        // 仍原样透传——不能把「上游没给跳转目标」误报成「重定向超过 5 跳」。
        if (!result.header("location").isNullOrBlank()) {
            runCatching { result.body.close() }
            throw UpstreamException("上游重定向次数过多（超过 $MAX_REDIRECTS 跳）")
        }
    }
    val status = runCatching { HttpStatusCode.fromValue(result.status) }.getOrDefault(HttpStatusCode.BadGateway)
    result.header("content-range")?.let { call.response.header("Content-Range", it) }
    result.header("accept-ranges")?.let { call.response.header("Accept-Ranges", it) }
    val ct = contentType?.let { runCatching { ContentType.parse(it) }.getOrNull() }
    // content-length 只在「正常内容响应」上透传：3xx/错误体的声明长度与实际
    // 转发内容经常对不上（上游断流、gzip 压缩前后长度差），浏览器会对着一个
    // 永远等不到结尾的长度干转。那种情况交给 chunked，宁可没有长度也不错报。
    val contentLength = if (result.status == 200 || result.status == 206) {
        result.header("content-length")?.toLongOrNull()
    } else {
        null
    }
    // 封面/图标的浏览器缓存。经代理的图片 URL 只由内容决定（同一张封面永远是同一个
    // /api/proxy 地址），缓存一天能省掉每次刷新都穿透服务器拉图床的开销。
    // 只对图片生效：m3u8 清单缓存一天等于源站换源不生效，视频分片没有缓存的意义
    // （每个分片只被请求一次，带签名的地址也不该进磁盘缓存）。
    if (ct?.match("image/*") == true && result.status == 200) {
        call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")
    }
    // body 阶段刻意**不设**超时。试过，两种做法都不成立，理由写在这里免得被人再加回来：
    //  1) 绝对期限（从响应头开始计时）会砍掉健康的慢速长传输。实测一部 700KB 的
    //     「每秒给一段」的流被 4 秒期限砍断后，声明了 Content-Length 的响应既不补字节也不关闭，
    //     客户端等满 90 秒仍在等 —— 播放器表现成卡死，比不设超时更糟。
    //  2) 空闲期限也不成立：这条链路是同步 copyTo，两次 read 之间的耗时主要是
    //     写出被客户端背压堵住的时间。按「距上次读到的间隔」判上游死活，误伤的是
    //     边看边缓冲的正常观众；而真正危险的那种「read 自己永久卡住」，
    //     在阻塞读返回之前根本没有任何检查点可以生效。
    // 也就是说：在同步写出这条路径上，期限要么误伤正常播放，要么打不到真正的死流。
    // 要正确解决得换成非阻塞写出（或对每个 read 做带超时的异步包裹），那是另一套机制。
    // 现在靠三件已有的东西兜住：连接超时 + 响应头超时（JDK 层）、上游 EOF/断连时
    // copyTo 自然返回、以及 Netty 对空闲连接的处理。
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

/**
 * 请求体上限。登录接口免鉴权也可达，不封顶等于让来访者决定这台机器的堆用量
 * （接收侧此前完全没有上限，只有落盘前的 importJson 才校验大小 —— 那时已经读进内存了）。
 */
private const val MAX_BODY_BYTES = 1L * 1024 * 1024

/**
 * 登录表单的体积上限，比通用上限小一个量级。
 *
 * 单独设一个数是上面那个顺序决定的：登录现在**先读体再判限速**（否则正确口令会被
 * 限速挡在门外），于是每个来敲门的请求都会真的读一次体。留 1MB 等于让被限流的客户端
 * 照旧能按请求体压带宽与堆；32KB 装得下任意长的 token，又让这件事没有可乘之处。
 */
private const val MAX_LOGIN_BODY_BYTES = 32L * 1024

/**
 * 「请求体超限」要能和「JSON 不合法」区分开。
 *
 * 它俩在 StatusPages 里都映射成 400，但**经由 bodyObject 的路由会先把它吞掉**：
 * bodyObject 对任何异常都返回 null，调用方于是回「请填写口令」或「missing keyword」。
 * 实测：40KB 的登录体（上限 32KB）得到的是 `{"error":"请填写口令"}` —— 状态码没错，
 * 但排查的人会去检查表单，而真正的问题是他发了 40KB。
 * 所以用一个专门类型标记它，bodyObject 见到就原样抛出（TooDeep 同理）。
 */
private class RequestTooLarge(message: String) : IllegalArgumentException(message)

private suspend fun bodyField(call: ApplicationCall, field: String): String? =
    bodyObject(call)?.stringField(field)

private suspend fun bodyObject(call: ApplicationCall, maxBytes: Long = MAX_BODY_BYTES): JsonObject? = try {
    prettyJson.parseToJsonElement(call.receiveCappedText(maxBytes)).jsonObject
} catch (e: RequestTooLarge) {
    // 这两类「内容坏/太大」的结论必须原样传出去，不能伪装成「缺字段」
    throw e
} catch (e: JsonGuard.TooDeep) {
    throw e
} catch (e: Exception) {
    null
}

/**
 * 取字符串字段。
 *
 * 两个坑都要避开：
 *  - `jsonPrimitive` 是强转，`{"url":{"a":1}}` 会抛 IllegalArgumentException，
 *    而它发生在 bodyObject 的 try 之外，于是一个形状奇怪的请求体变成 500；
 *  - `contentOrNull` 对**任何** primitive 都给得出文本，`{"url":true}` 会变成
 *    字符串 "true"，接着被 UrlGuard 判成「不允许的目标地址」回 400/403 ——
 *    把类型错误伪装成安全拒绝，排查时完全看不出是请求体写错了。
 * 所以：只接受字符串，其它一律当作缺字段（调用方本来就有缺失分支会给 400）。
 */
private fun JsonObject.stringField(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * 严格取字符串数组：不是数组、或数组里含非字符串项，都整字段判错。
 *
 * 旧写法 `get(key)?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }` 有两处不成立：
 *  - `jsonArray` 对对象是强转（虽然 IllegalArgumentException 会被兜底成 400，但那是巧合，
 *    不是这一行的设计意图）；
 *  - `contentOrNull` 对**数字**也给出文本，于是 `{"names":[1,2]}` 变成两个「源名」，
 *    导入一条都没匹配上却回 200 —— 类型错误被伪装成一次成功但空的结果。
 * 半收半丢同样糟：`["订阅甲",{"bad":1}]` 只导入一条却报成功，用户看不出少了东西。
 */
private fun JsonObject.stringArrayField(key: String): List<String> {
    val node = this[key] ?: return emptyList()
    if (node is JsonNull) return emptyList()
    val items = node as? JsonArray ?: throw IllegalArgumentException("$key 必须是字符串数组")
    return items.map {
        (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
            ?: throw IllegalArgumentException("$key 只能包含字符串")
    }
}

private suspend fun ApplicationCall.receiveCappedText(maxBytes: Long = MAX_BODY_BYTES): String {
    // 先看 Content-Length 挡掉老实的超大请求，再逐块累加，防止 chunked 传输谎报长度
    val declared = request.contentLength()
    if (declared != null && declared > maxBytes) {
        throw RequestTooLarge("请求体超过 ${maxBytes / 1024}KB 上限")
    }
    val channel = request.receiveChannel()
    val out = ByteArrayOutputStream()
    val chunk = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = channel.readAvailable(chunk)
        if (n == -1) break
        total += n
        if (total > maxBytes) throw RequestTooLarge("请求体超过 ${maxBytes / 1024}KB 上限")
        out.write(chunk, 0, n)
    }
    val text = String(out.toByteArray(), Charsets.UTF_8)
    // 体积上限挡不住「400KB 的一把左括号」：那种递归解析会打穿栈。
    // 不能只指望下游接得住：catch (Exception) 接不住 Error，而 runCatching 接得住
    // 却会把合法 JSON 判成坏文件搬走（两种后果都不是「这个请求被拒绝了」）。
    // 详见 JsonGuard 的类注释。
    JsonGuard.requireShallow(text)
    return text
}
