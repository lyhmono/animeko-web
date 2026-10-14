package com.animeko.web.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class HttpResult(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    val contentType: String? get() = header("content-type")
    val text: String get() = String(body, Charsets.UTF_8)
}

data class StreamResult(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: InputStream,
) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    val contentType: String? get() = header("content-type")
}

/** 出站 HTTP 抽象，便于测试时打桩。 */
interface Fetcher {
    suspend fun fetch(
        url: String,
        headers: Map<String, String> = emptyMap(),
        followRedirects: Boolean = true,
    ): HttpResult

    suspend fun post(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        contentType: String = "application/json",
    ): HttpResult

    /** 流式读取（视频代理用） */
    suspend fun fetchStream(
        url: String,
        headers: Map<String, String> = emptyMap(),
        followRedirects: Boolean = false,
    ): StreamResult
}

class JdkFetcher(
    private val timeoutMs: Long = 20_000,
    private val defaultUserAgent: String = DEFAULT_UA,
) : Fetcher {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(timeoutMs))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val noRedirectClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(timeoutMs))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override suspend fun fetch(
        url: String,
        headers: Map<String, String>,
        followRedirects: Boolean,
    ): HttpResult = withContext(Dispatchers.IO) {
        val request = buildRequest(url, headers, followRedirects)
        val cl = if (followRedirects) client else noRedirectClient
        val resp = cl.send(request, HttpResponse.BodyHandlers.ofByteArray())
        HttpResult(
            status = resp.statusCode(),
            headers = resp.headers().map().mapValues { it.value.toList() },
            body = resp.body(),
        )
    }

    override suspend fun post(
        url: String,
        body: String,
        headers: Map<String, String>,
        contentType: String,
    ): HttpResult = withContext(Dispatchers.IO) {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(timeoutMs))
            .header("Content-Type", contentType)
            .header("User-Agent", headers["User-Agent"]?.takeIf { it.isNotBlank() } ?: defaultUserAgent)
            .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
        headers.forEach { (k, v) ->
            if (!k.equals("User-Agent", ignoreCase = true) &&
                !k.equals("Content-Type", ignoreCase = true) &&
                isValidHeaderName(k)
            ) {
                builder.header(k, v)
            }
        }
        val resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        HttpResult(
            status = resp.statusCode(),
            headers = resp.headers().map().mapValues { it.value.toList() },
            body = resp.body(),
        )
    }

    override suspend fun fetchStream(
        url: String,
        headers: Map<String, String>,
        followRedirects: Boolean,
    ): StreamResult = withContext(Dispatchers.IO) {
        val request = buildRequest(url, headers, followRedirects)
        val cl = if (followRedirects) client else noRedirectClient
        val resp = cl.send(request, HttpResponse.BodyHandlers.ofInputStream())
        StreamResult(
            status = resp.statusCode(),
            headers = resp.headers().map().mapValues { it.value.toList() },
            body = resp.body(),
        )
    }

    private fun buildRequest(url: String, headers: Map<String, String>, followRedirects: Boolean): HttpRequest {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(timeoutMs))
            .header("User-Agent", headers["User-Agent"]?.takeIf { it.isNotBlank() } ?: defaultUserAgent)
            .header("Accept", headers["Accept"] ?: "*/*")
        headers.forEach { (k, v) ->
            if (!k.equals("User-Agent", ignoreCase = true) && isValidHeaderName(k)) builder.header(k, v)
        }
        return builder.GET().build()
    }

    companion object {
        const val DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3"

        fun isValidHeaderName(name: String): Boolean =
            name.isNotBlank() && name.all { it.code in 33..126 && it != ':' }

        fun encodeUrl(s: String): String = URLEncoder.encode(s, "UTF-8")
    }
}
