package com.animeko.web.api

import com.animeko.web.bangumi.BangumiClient
import com.animeko.web.config.AppConfig
import com.animeko.web.danmaku.DandanplayClient
import com.animeko.web.appLog
import com.animeko.web.http.Fetcher
import com.animeko.web.http.StreamResult
import com.animeko.web.http.UrlGuard
import com.animeko.web.http.UpstreamException
import com.animeko.web.proxy.HlsRewriter
import com.animeko.web.selector.SelectorEngine
import com.animeko.web.selector.SelectorException
import com.animeko.web.selector.SelectorRepository
import com.animeko.web.selector.SelectorSourceArguments
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

/** 播放清单体积上限：防止有人把 .m3u8 指向一个大文件，readText() 直接把堆吃光。 */
private const val MAX_PLAYLIST_BYTES = 4L * 1024 * 1024

@kotlinx.serialization.Serializable
data class HealthResponse(
    val status: String,
    val version: String,
    val danmakuConfigured: Boolean,
    val authEnabled: Boolean,
)

@kotlinx.serialization.Serializable
data class SourceEnabledResponse(
    val name: String,
    val enabled: Boolean,
)

fun Application.configureApiRoutes(
    cfg: AppConfig,
    bangumi: BangumiClient,
    danmaku: DandanplayClient,
    repo: SelectorRepository,
    engine: SelectorEngine,
    fetcher: Fetcher,
) {
    routing {
        // ---- 可选鉴权（默认关闭；公网暴露时开启）----
        intercept(ApplicationCallPipeline.Call) {
            // 写操作必须同源：浏览器发跨站 POST 时不会预检（text/plain 属「简单请求」），
            // 而服务默认无鉴权又监听 0.0.0.0 —— 用户访问一个恶意网页就能被塞进一个
            // 带回溯正则的数据源（远程 DoS）或一条内网代理规则。
            if (call.isCrossSiteWrite(cfg)) {
                appLog.warn("拒绝跨站写请求: origin={} host={}",
                    call.request.headers[HttpHeaders.Origin], call.request.headers[HttpHeaders.Host])
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "CROSS_SITE_REQUEST"))
                finish()
                return@intercept
            }
            if (cfg.authEnabled && call.request.local.uri.startsWith("/api/") &&
                call.request.local.uri.substringBefore('?') != HEALTH_PATH
            ) {
                if (!call.authorized(cfg)) {
                    // 浏览器没法给 <video>/<img>/hls.js 的分片请求加自定义请求头，
                    // 所以下发 Basic 质询：用户输入一次后浏览器对该源的所有子资源都会自动带上。
                    // 只用 ANIMEKO_AUTH_TOKEN 时，在弹窗里用户名任填、密码填 token 即可。
                    call.response.header(
                        HttpHeaders.WWWAuthenticate,
                        "Basic realm=\"animeko-web\", charset=\"UTF-8\"",
                    )
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "UNAUTHORIZED"))
                    finish()
                }
            }
        }

        get(HEALTH_PATH) {
            call.respond(
                HealthResponse(
                    status = "ok",
                    version = "0.1.0",
                    danmakuConfigured = cfg.danmakuConfigured,
                    authEnabled = cfg.authEnabled,
                ),
            )
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
            val text = call.receiveText()
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
            val body = call.receiveText()
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
                    call.respond(engine.resolveVideo(source.arguments.searchConfig, playUrl))
                }
            }
        }

        // ---- 弹弹play 弹幕 ----
        get("/api/danmaku/search") {
            val anime = call.request.queryParameters["anime"]?.trim().orEmpty()
            val episode = call.request.queryParameters["episode"]?.trim()
            if (anime.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing anime"))
            } else {
                call.respond(danmaku.searchEpisodes(anime, episode))
            }
        }

        get("/api/danmaku/comments") {
            val id = call.request.queryParameters["episodeId"]?.toLongOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing episodeId"))
            } else {
                call.respond(danmaku.comments(id))
            }
        }

        // ---- 视频/HLS 代理 ----
        get("/api/proxy") { proxyVideo(call, fetcher, cfg) }
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
            if (path.startsWith("/api/") || path == "/api") return@get
            val rel = path.removePrefix("/")
            val file = if (rel.isEmpty()) File(dir, "index.html") else dir.resolve(rel).normalize()
            when {
                file.isFile && file.startsWith(dir) -> {
                    // 带内容哈希的产物可以长期强缓存
                    if (rel.startsWith("assets/")) {
                        call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
                    }
                    call.respondFile(file)
                }
                // 缺失的静态资源必须 404，不能回退 index.html：
                // 那等于把 HTML 当 JS/CSS 返回（200 + text/html），浏览器报模块 MIME 错误；
                // 重新部署后 chunk 文件名变了，用户缓存里的旧 index.html 就会永久白屏。
                looksLikeAsset(rel) -> call.respond(HttpStatusCode.NotFound)
                else -> {
                    val index = File(dir, "index.html")
                    if (index.isFile) {
                        // 入口必须每次回源：缓存住旧 index.html，就等于缓存住一批
                        // 已经在重新部署后不存在的 chunk 文件名（前端会直接坏掉）
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
 * 浏览器总会带上 Origin（同源的 fetch/表单也是），所以拿它与 Host 对比就能识别跨站写请求。
 * 不带 Origin 的（curl、脚本、API 客户端）视为本地调用放行，鉴权仍照常生效。
 */
private fun ApplicationCall.isCrossSiteWrite(cfg: AppConfig): Boolean {
    if (cfg.allowCrossOrigin) return false
    val method = request.local.method
    if (method == HttpMethod.Get || method == HttpMethod.Head || method == HttpMethod.Options) return false
    if (!request.local.uri.startsWith("/api/")) return false
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

private fun ApplicationCall.authorized(cfg: AppConfig): Boolean {
    val header = request.headers["Authorization"] ?: return false
    if (header.startsWith("Bearer ")) {
        val token = header.removePrefix("Bearer ").trim()
        return cfg.authToken != null && token.isNotEmpty() && constantTimeEquals(token, cfg.authToken)
    }
    if (header.startsWith("Basic ")) {
        val decoded = runCatching {
            String(java.util.Base64.getDecoder().decode(header.removePrefix("Basic ").trim()), Charsets.UTF_8)
        }.getOrNull() ?: return false
        val parts = decoded.split(":", limit = 2)
        if (parts.size != 2) return false
        val user = parts[0]
        val pass = parts[1]
        if (cfg.authUser != null && cfg.authPassword != null) {
            return constantTimeEquals(user, cfg.authUser) && constantTimeEquals(pass, cfg.authPassword)
        }
        // 只配了 token 的部署：把 token 当 Basic 口令接受（用户名任意），
        // 否则浏览器的质询弹窗无法完成认证，播放器与封面会全部 401。
        return cfg.authToken != null && constantTimeEquals(pass, cfg.authToken)
    }
    return false
}

/** 凭据比较不走 String==（短路返回，长度/前缀可被时序探测）。 */
private fun constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

// ---- 视频代理实现 ----

private const val MAX_REDIRECTS = 5

private suspend fun proxyVideo(call: ApplicationCall, fetcher: Fetcher, cfg: AppConfig) {
    // Ktor 已经解码过查询参数，不能再 URLDecoder 一次：
    // 带签名/令牌的地址几乎都含 % 和 +，二次解码会把 ?sign=a%2Bb 变成 ?sign=a b，
    // 表现为「解析成功但播放/清单 404」，且日志里看不出原因。
    val requested = call.request.queryParameters["url"]?.trim().orEmpty()
    if (requested.isBlank() || !requested.startsWith("http")) {
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid url"))
        return
    }
    var url = UrlGuard.requireAllowed(requested, cfg.allowPrivateTargets)

    // 上游所需请求头由查询参数带入（数据源解析出的 ua/referer/cookie）。
    // 同一份参数还要写进改写后的分片地址，否则分片请求丢防盗链头 → 清单能拉、画面播不出。
    val forwardParams = LinkedHashMap<String, String>()
    for (key in listOf("ua", "referer", "cookie")) {
        call.request.queryParameters[key]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { forwardParams[key] = it }
    }
    val headers = LinkedHashMap<String, String>()
    forwardParams["ua"]?.let { headers["User-Agent"] = it }
    forwardParams["referer"]?.let { headers["Referer"] = it }
    forwardParams["cookie"]?.let { headers["Cookie"] = it }
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
            forwardParams.remove("cookie")
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
        val rewritten = HlsRewriter.rewrite(text, url, forwardParams = forwardParams)
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

private suspend fun bodyField(call: ApplicationCall, field: String): String? = try {
    val obj: JsonObject = prettyJson.parseToJsonElement(call.receiveText()).jsonObject
    obj[field]?.jsonPrimitive?.contentOrNull
} catch (e: Exception) {
    null
}
