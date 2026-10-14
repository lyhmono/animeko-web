package com.animeko.web.api

import com.animeko.web.bangumi.BangumiClient
import com.animeko.web.config.AppConfig
import com.animeko.web.danmaku.DandanplayClient
import com.animeko.web.appLog
import com.animeko.web.http.Fetcher
import com.animeko.web.http.JdkFetcher
import com.animeko.web.proxy.HlsRewriter
import com.animeko.web.selector.SelectorEngine
import com.animeko.web.selector.SelectorException
import com.animeko.web.selector.SelectorRepository
import com.animeko.web.selector.SelectorSourceArguments
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.http.content.staticFiles
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URLDecoder

private val prettyJson = Json { ignoreUnknownKeys = true }

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
            if (cfg.authEnabled && call.request.local.uri.startsWith("/api/")) {
                if (!call.authorized(cfg)) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "UNAUTHORIZED"))
                    finish()
                }
            }
        }

        get("/api/health") {
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
            val enabled = runCatching {
                prettyJson.parseToJsonElement(body).jsonObject["enabled"]?.jsonPrimitive?.content.toBoolean()
            }.getOrDefault(true)
            if (repo.setEnabled(name, enabled)) {
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
        get("/api/proxy") { proxyVideo(call, fetcher) }
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
            if (file.isFile && file.startsWith(dir)) {
                call.respondFile(file)
            } else {
                val index = File(dir, "index.html")
                if (index.isFile) call.respondFile(index)
            }
        }
    }
}

// ---- 鉴权 ----

private fun ApplicationCall.authorized(cfg: AppConfig): Boolean {
    val header = request.headers["Authorization"] ?: return false
    if (header.startsWith("Bearer ")) {
        val token = header.removePrefix("Bearer ").trim()
        return cfg.authToken != null && token.isNotEmpty() && token == cfg.authToken
    }
    if (header.startsWith("Basic ")) {
        if (cfg.authUser == null || cfg.authPassword == null) return false
        val decoded = runCatching {
            String(java.util.Base64.getDecoder().decode(header.removePrefix("Basic ").trim()), Charsets.UTF_8)
        }.getOrNull() ?: return false
        val (user, pass) = decoded.split(":", limit = 2).let {
            if (it.size == 2) it[0] to it[1] else return false
        }
        return user == cfg.authUser && pass == cfg.authPassword
    }
    return false
}

// ---- 视频代理实现 ----

private suspend fun proxyVideo(call: ApplicationCall, fetcher: Fetcher) {
    var url = call.request.queryParameters["url"]
        ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
        ?.trim()
        .orEmpty()
    if (url.isBlank() || !url.startsWith("http")) {
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid url"))
        return
    }
    val headers = LinkedHashMap<String, String>()
    call.request.queryParameters["ua"]?.let { headers["User-Agent"] = decodeParam(it) }
    call.request.queryParameters["referer"]?.let { headers["Referer"] = decodeParam(it) }
    call.request.queryParameters["cookie"]?.let { headers["Cookie"] = decodeParam(it) }
    call.request.headers["Range"]?.let { if (it.isNotBlank()) headers["Range"] = it }

    var result = try {
        fetcher.fetchStream(url, headers, followRedirects = false)
    } catch (e: Exception) {
        call.respond(HttpStatusCode.BadGateway, mapOf("error" to (e.message ?: "upstream error")))
        return
    }

    // 手动跟随重定向（最多 5 次）
    var redirects = 0
    while (result.status in 301..399 && redirects < 5) {
        val location = result.header("location")
        if (location.isNullOrBlank()) break
        result.body.close()
        url = com.animeko.web.selector.SelectorEngine.absUrl(url, location)
        result = try {
            fetcher.fetchStream(url, headers, followRedirects = false)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to (e.message ?: "upstream error")))
            return
        }
        redirects++
    }

    val contentType = result.contentType
    val isHls = url.contains(".m3u8", ignoreCase = true) ||
        contentType?.contains("mpegurl", ignoreCase = true) == true

    if (isHls) {
        val text = result.body.bufferedReader().use { it.readText() }
        val rewritten = HlsRewriter.rewrite(text, url)
        call.respondText(rewritten, ContentType.parse("application/vnd.apple.mpegurl"))
        return
    }

    val status = HttpStatusCode.fromValue(result.status)
    result.header("content-range")?.let { call.response.header("Content-Range", it) }
    result.header("accept-ranges")?.let { call.response.header("Accept-Ranges", it) }
    val contentLength = result.header("content-length")?.toLongOrNull()
    val ct = contentType?.let { runCatching { ContentType.parse(it) }.getOrNull() }
    call.respondOutputStream(ct, status, contentLength) {
        result.body.use { input -> input.copyTo(this) }
    }
}

private fun decodeParam(value: String): String =
    runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

private suspend fun bodyField(call: ApplicationCall, field: String): String? = try {
    val obj: JsonObject = prettyJson.parseToJsonElement(call.receiveText()).jsonObject
    obj[field]?.jsonPrimitive?.contentOrNull
} catch (e: Exception) {
    null
}
