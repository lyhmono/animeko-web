package com.animeko.web.api

import com.animeko.web.appLog
import com.animeko.web.config.AppConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** Normalize the configured root once; all filesystem resolution and file work runs on IO. */
fun Application.configureStaticFrontend(cfg: AppConfig) {
    val root = Path.of(cfg.frontendDir).toAbsolutePath().normalize()
    // Configuration is synchronous at startup. Keep the existence probe on IO, and do not
    // register a catch-all for a missing frontend: that would change API 405 responses into 404.
    if (!runBlocking(Dispatchers.IO) { Files.isDirectory(root) }) {
        appLog.warn("前端目录不存在，跳过静态托管: {}", root)
        return
    }
    routing {
        get("/{...}") {
            if (call.pathSegments().firstOrNull() == "api") return@get
            val rel = try {
                call.request.path().split('/').filter(String::isNotEmpty)
                    .joinToString("/") { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
            } catch (_: IllegalArgumentException) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            val asset = withContext(Dispatchers.IO) { resolveStaticFile(root, rel.ifEmpty { "index.html" }) }
            when (asset) {
                is StaticFile.Found -> call.serveStaticFile(asset)
                StaticFile.Denied -> call.respond(HttpStatusCode.NotFound)
                StaticFile.Missing -> {
                    if (looksLikeAsset(rel)) {
                        call.respond(HttpStatusCode.NotFound)
                    } else {
                        when (val index = withContext(Dispatchers.IO) { resolveStaticFile(root, "index.html") }) {
                            is StaticFile.Found -> call.serveStaticFile(index)
                            else -> call.respond(HttpStatusCode.NotFound)
                        }
                    }
                }
            }
        }
    }
}

private sealed interface StaticFile {
    class Found(val path: Path, val relative: String) : StaticFile
    data object Denied : StaticFile
    data object Missing : StaticFile
}

/** Both lexical traversal and links to files outside the real root are rejected, including index.html. */
private fun resolveStaticFile(root: Path, relative: String): StaticFile {
    val candidate = try { root.resolve(relative).normalize() } catch (_: InvalidPathException) { return StaticFile.Denied }
    if (!candidate.startsWith(root)) return StaticFile.Denied
    val realRoot: Path
    val realFile: Path
    try {
        realRoot = root.toRealPath()
        realFile = candidate.toRealPath()
    } catch (_: NoSuchFileException) {
        return StaticFile.Missing
    }
    if (!realFile.startsWith(realRoot)) return StaticFile.Denied
    if (!Files.isRegularFile(realFile)) return StaticFile.Missing
    return StaticFile.Found(realFile, realRoot.relativize(realFile).toString().replace('\\', '/'))
}

private suspend fun io.ktor.server.application.ApplicationCall.serveStaticFile(asset: StaticFile.Found) {
    val relative = asset.relative
    when {
        relative == "index.html" || relative == "sw.js" || relative == "manifest.webmanifest" ->
            response.header(HttpHeaders.CacheControl, "no-cache")
        relative.startsWith("assets/") ->
            response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
    }
    if (relative == "manifest.webmanifest") {
        val bytes = withContext(Dispatchers.IO) { Files.readAllBytes(asset.path) }
        respondBytes(bytes, ContentType.parse("application/manifest+json"))
    } else {
        withContext(Dispatchers.IO) { respondFile(asset.path.toFile()) }
    }
}

private val assetExtensionRegex = Regex("""\.[A-Za-z0-9]{1,8}$""")

/** Dotted source identifiers remain SPA routes; only root assets and assets/ misses are 404. */
private fun looksLikeAsset(relative: String): Boolean =
    relative.startsWith("assets/") || (!relative.contains('/') && assetExtensionRegex.containsMatchIn(relative))
