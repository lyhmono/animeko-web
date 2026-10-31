package com.animeko.web.api

import com.animeko.web.auth.LoginRateLimiter
import com.animeko.web.auth.SessionStore
import com.animeko.web.auth.CredentialVerifier
import com.animeko.web.auth.ConfiguredCredentialVerifier
import com.animeko.web.bangumi.BangumiClient
import com.animeko.web.bangumi.BgmChannelResponse
import com.animeko.web.config.AppConfig
import com.animeko.web.appLog
import com.animeko.web.data.UserDataStore
import com.animeko.web.http.Fetcher
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
import io.ktor.server.request.path
import io.ktor.server.response.ApplicationResponse
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI


private val prettyJson = Json { ignoreUnknownKeys = true }

private const val HEALTH_PATH = "/api/health"

private const val LOGIN_PATH = "/api/session/login"
private const val LOGOUT_PATH = "/api/session/logout"
private val VERIFIED_BEARER_SESSION = AttributeKey<String>("verified-bearer-session")

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
    credentialVerifier: CredentialVerifier = ConfiguredCredentialVerifier(cfg),
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

        // ---- 登录 / 注销 ----
        post(LOGIN_PATH) {
            // CSRF 检查在根 intercept 里，登录接口也在它的覆盖范围内（只豁免鉴权，不豁免 CSRF）
            val clientKey = call.rateLimitKey(cfg)
            if (!cfg.authEnabled) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to "服务端未配置登录凭据，无需登录"))
                return@post
            }
            // 随机会话已经通过验证，既无需重新验口令，也不受口令桶连坐。
            if (call.hasValidSession(site, cfg) || call.validBearerSession(site) != null) {
                call.respond(SessionStateResponse(authenticated = true, user = cfg.authUser ?: "token"))
                return@post
            }
            // 先原子取得许可再读表单/验密。超限时所有候选都走同一个答复，
            // 正确口令不能作为绕过限速的判定入口；可信代理配置解决共享来源误锁。
            if (!limiter.tryAcquire(clientKey)) {
                call.response.header(HttpHeaders.RetryAfter, limiter.retryAfterSeconds(clientKey).toString())
                call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "凭据校验次数过多，请稍后再试"))
                return@post
            }
            // 请求体只能读一次：分两次 bodyField(...) 会让第二个字段永远为空，
            // 表现是「口令明明对了却提示请填写口令」。
            val form = bodyObject(call, MAX_LOGIN_BODY_BYTES)
            val username = form?.stringField("username").orEmpty()
            val password = form?.stringField("password").orEmpty()
            when {
                form == null || password.isEmpty() -> call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "请填写口令"),
                )
                !credentialVerifier.password(username, password) -> {
                    appLog.warn("登录失败: user={} ip={} 累计={}", username.take(40), clientKey, limiter.attempts(clientKey))
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "账号或口令不正确"))
                }
                else -> {
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
            call.attributes.getOrNull(VERIFIED_BEARER_SESSION)?.let(site::invalidate)
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
            val custom = form.stringArrayField("customUrls")
            val saved = withContext(Dispatchers.IO) { bangumi.setChannel(com.animeko.web.bangumi.BgmChannelConfig(channel, custom)) }
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
            call.respond(withContext(Dispatchers.IO) { repo.list() })
        }

        post("/api/selector/sources") {
            val text = call.receiveCappedText()
            try {
                val args = withContext(Dispatchers.IO) { repo.importJson(text).also { repo.save(it) } }
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
            } else if (withContext(Dispatchers.IO) { repo.setEnabled(name, enabled) }) {
                call.respond(SourceEnabledResponse(name = name, enabled = enabled))
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "source not found"))
            }
        }

        delete("/api/selector/sources/{name}") {
            val name = call.parameters["name"]
            if (name != null && withContext(Dispatchers.IO) { repo.delete(name) }) {
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
            val snapshot = withContext(Dispatchers.IO) { userData.read() }
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
                val snapshot = withContext(Dispatchers.IO) { userData.write(text, ifMatch) }
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
            val source = withContext(Dispatchers.IO) { repo.find(call.parameters["name"] ?: "", enabledOnly = true) }
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
            val source = withContext(Dispatchers.IO) { repo.find(call.parameters["name"] ?: "", enabledOnly = true) }
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
            val source = withContext(Dispatchers.IO) { repo.find(call.parameters["name"] ?: "", enabledOnly = true) }
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
                        extraCookieHosts = target.cookieHosts,
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

private fun ApplicationCall.hasValidSession(site: SessionStore, cfg: AppConfig): Boolean {
    val token = sessionTokens().firstOrNull(site::isValid) ?: return false
    // 注销只发删除头；其余接口用原子周期门闩，让浏览器和服务端的空闲寿命一致。
    if (pathSegments() != listOf("api", "session", "logout") && site.claimCookieRenewal(token)) {
        response.setSessionCookie(cfg, token, site.ttlSeconds())
    }
    return true
}

/** 仅验证站点签发的随机会话，绝不在许可前比较配置 token。 */
private fun ApplicationCall.validBearerSession(site: SessionStore): String? =
    request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ") }
        ?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() && site.isValid(it) }

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

private fun headerAuthorized(verifier: CredentialVerifier, header: String): Boolean {
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
private fun ApplicationResponse.clearSessionCookie(cfg: AppConfig) {
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
