package com.animeko.web.api

import com.animeko.web.config.AppConfig
import com.animeko.web.http.Fetcher
import com.animeko.web.http.StreamResult
import com.animeko.web.http.UpstreamException
import com.animeko.web.http.UrlGuard
import com.animeko.web.http.closeOnce
import com.animeko.web.http.closeQuietly
import com.animeko.web.http.copyStreamCancellable
import com.animeko.web.http.readStreamBytes
import com.animeko.web.proxy.HlsRewriter
import com.animeko.web.proxy.ProxySessionStore
import com.animeko.web.selector.SelectorEngine
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.netty.NettyApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingCall
import io.netty.channel.ChannelFutureListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.net.URI

private const val PROXY_PATH = "/api/proxy"
private const val MAX_REDIRECTS = 5
private const val MAX_PLAYLIST_BYTES = 4L * 1024 * 1024

/** Netty does not necessarily cancel an upstream read when the downstream has no more bytes to write. */
internal suspend fun proxyVideo(call: ApplicationCall, fetcher: Fetcher, cfg: AppConfig, sessions: ProxySessionStore) {
    val engineCall = if (call is RoutingCall) call.pipelineCall.engineCall else call
    val connection = (engineCall as? NettyApplicationCall)?.context?.channel()
    val owner = currentCoroutineContext().job
    val disconnect = ChannelFutureListener { owner.cancel(CancellationException("媒体客户端已断开")) }
    connection?.closeFuture()?.addListener(disconnect)
    try {
        currentCoroutineContext().ensureActive()
        proxyVideoOwned(call, fetcher, cfg, sessions)
    } finally { connection?.closeFuture()?.removeListener(disconnect) }
}

private suspend fun proxyVideoOwned(call: ApplicationCall, fetcher: Fetcher, cfg: AppConfig, sessions: ProxySessionStore) {
    call.response.header("Content-Security-Policy", "sandbox")
    // Query parameters are already decoded by Ktor; never decode signed URLs twice.
    val requested = call.request.queryParameters["url"]?.trim().orEmpty()
    if (requested.isBlank() || !requested.startsWith("http")) {
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid url"))
        return
    }
    var url = withContext(Dispatchers.IO) { UrlGuard.requireAllowed(requested, cfg.allowPrivateTargets) }
    val sid = call.request.queryParameters["sid"]?.trim().orEmpty()
    val headers = LinkedHashMap<String, String>()
    if (sid.isNotEmpty()) {
        val grant = sessions.grant(sid, url)
        when (grant.kind) {
            ProxySessionStore.Kind.EXPIRED -> {
                call.respond(HttpStatusCode.Gone, mapOf("error" to "PROXY_SESSION_EXPIRED"))
                return
            }
            ProxySessionStore.Kind.HOST_NOT_ALLOWED -> {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "PROXY_SESSION_TARGET_MISMATCH"))
                return
            }
            ProxySessionStore.Kind.GRANTED -> headers.putAll(grant.headers)
        }
    }
    headers[HttpHeaders.AcceptEncoding] = "identity"
    call.request.headers[HttpHeaders.Range]?.takeIf(String::isNotBlank)?.let { headers[HttpHeaders.Range] = it }

    var result = fetchProxyStream(fetcher, url, headers)
    try {
        var redirects = 0
        while (result.status in 301..399 && redirects < MAX_REDIRECTS) {
            val location = result.header("location")?.takeIf(String::isNotBlank) ?: break
            // Every hop still validates DNS and target policy; failures close the previous body in finally.
            val next = withContext(Dispatchers.IO) {
                UrlGuard.requireAllowed(SelectorEngine.absUrl(url, location), cfg.allowPrivateTargets)
            }
            if (UrlGuard.hostOf(next) != UrlGuard.hostOf(url) || UrlGuard.isInsecureDowngrade(url, next)) {
                headers.remove("Cookie")
            }
            result.body.closeQuietly()
            url = next
            result = fetchProxyStream(fetcher, url, headers)
            redirects++
        }
        if (result.status in 301..399 && !result.header("location").isNullOrBlank()) {
            throw UpstreamException("上游重定向次数过多（超过 $MAX_REDIRECTS 跳）")
        }

        val type = result.contentType
        if (type?.let { it.contains("html", true) || it.contains("svg", true) || it.contains("javascript", true) } == true) {
            call.response.header("X-Content-Type-Options", "nosniff")
        }
        val encoding = result.headers.entries.filter { it.key.equals(HttpHeaders.ContentEncoding, true) }
            .flatMap { it.value }.joinToString(", ").takeIf(String::isNotBlank)
        val playlist = isPlaylistRepresentation(url, type)
        val complete = result.status == 200 || (result.status == 206 && isCompleteRange(result.header("content-range")))
        if (playlist && complete) {
            // Ignore an upstream's failure to honor identity: decode before the decoded-byte budget and rewrite.
            val bytes = readStreamBytes(result.body, MAX_PLAYLIST_BYTES, cfg.proxyTimeoutMs, encoding)
            val text = String(bytes, Charsets.UTF_8)
            val rewritten = HlsRewriter.rewriteDetailed(text, url, sid = sid)
            sessions.allowHosts(sid, rewritten.hosts)
            call.respondText(rewritten.body, ContentType.parse("application/vnd.apple.mpegurl"), HttpStatusCode.OK)
            return
        }

        val status = runCatching { HttpStatusCode.fromValue(result.status) }.getOrDefault(HttpStatusCode.BadGateway)
        val contentType = type?.let { runCatching { ContentType.parse(it) }.getOrNull() }
        // Binary entities remain encoded and byte-for-byte unchanged. Headers describe the same representation.
        encoding?.takeUnless { it.equals("identity", true) }?.let { call.response.header(HttpHeaders.ContentEncoding, it) }
        result.header("content-range")?.let { call.response.header(HttpHeaders.ContentRange, it) }
        result.header("accept-ranges")?.let { call.response.header(HttpHeaders.AcceptRanges, it) }
        val length = if (result.status == 200 || result.status == 206) {
            result.header("content-length")?.toLongOrNull()?.takeIf { it >= 0 }
        } else null
        // Check the emitted header, not the incoming Cookie: only actual renewal disables image caching.
        if (contentType?.match("image/*") == true && result.status == 200 &&
            call.response.headers[HttpHeaders.SetCookie] == null
        ) {
            call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")
        }
        val finalBody = result.body
        // No total duration timeout for healthy long video. Downstream cancellation actively closes upstream.
        call.respondBytesWriter(contentType, status, length) { copyStreamCancellable(finalBody, this) }
    } finally { result.body.closeQuietly() }
}

/** MIME wins over a misleading path; only a generic/missing MIME uses the final path component as fallback. */
private fun isPlaylistRepresentation(url: String, contentType: String?): Boolean {
    val mime = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    if (mime.contains("mpegurl")) return true
    if (mime !in setOf("", "text/plain", "application/octet-stream", "binary/octet-stream", "application/binary")) return false
    val path = runCatching { URI(url).path }.getOrNull().orEmpty()
    return path.substringAfterLast('/').endsWith(".m3u8", ignoreCase = true)
}

private val completeRange = Regex("""bytes\s+0-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)
private fun isCompleteRange(header: String?): Boolean {
    val range = header?.trim()?.let(completeRange::matchEntire) ?: return false
    val last = range.groupValues[1].toLongOrNull() ?: return false
    val total = range.groupValues[2].toLongOrNull() ?: return false
    return total > 0 && last == total - 1
}

internal suspend fun fetchProxyStream(fetcher: Fetcher, url: String, headers: Map<String, String>): StreamResult =
    try {
        val result = fetcher.fetchStream(url, headers, followRedirects = false)
        result.copy(body = result.body.closeOnce())
    } catch (e: CancellationException) {
        throw e
    } catch (e: UrlGuard.Blocked) {
        throw e
    } catch (e: Exception) {
        throw UpstreamException("拉取上游失败: ${e.message ?: e::class.simpleName}")
    }
