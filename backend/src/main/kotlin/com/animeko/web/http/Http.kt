package com.animeko.web.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FilterInputStream
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** 上游返回非 2xx，或返回的内容无法按预期解析。映射为 502，避免与「本服务自己的错误」混成 500。 */
class UpstreamException(message: String) : Exception(message)

data class HttpResult(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    val contentType: String? get() = header("content-type")
    val text: String get() = String(body, Charsets.UTF_8)

    /** 状态码非 2xx 时抛出，附带上游地址便于排查。 */
    fun requireSuccess(source: String): HttpResult {
        if (status !in 200..299) {
            throw UpstreamException("上游 $source 返回 $status")
        }
        return this
    }
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

/**
 * 带绝对期限的输入流包装：视频代理转发 body 时，超过 deadline 仍未读完就抛
 * [java.io.IOException]，避免上游慢流/死流无限占用连接。
 *
 * 局限：已阻塞在一次 read() 内部的连接无法被中断（需 NIO 才能彻底解决），
 * 但可挡住「每次吐几字节的慢速拖流」这类最常见的资源占用形态 ——
 * JDK HttpClient 的 timeout 只覆盖到响应头，body 阶段没有任何保护。
 */
class DeadlineInputStream(
    private val delegate: InputStream,
    private val deadlineNanos: Long,
) : FilterInputStream(delegate) {
    override fun read(): Int {
        checkDeadline()
        return super.read()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        checkDeadline()
        return super.read(b, off, len)
    }

    private fun checkDeadline() {
        if (System.nanoTime() > deadlineNanos) {
            throw java.io.IOException("upstream stream exceeded deadline")
        }
    }
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

    /**
     * 有体积上限、且不自动跟随重定向的 GET。
     *
     * 为什么单独开一个方法而不是给 fetch 加参数：`fetch` 用 ofByteArray()，
     * 上限只能在「整个响应已经进堆」之后才检查 —— 对一个几百 MB 的 URL 等于没有上限。
     * 真实实现边读边断；默认实现退回事后检查（测试桩够用，生产用 JdkFetcher 覆盖）。
     *
     * 不跟随重定向是刻意的：302 指向 169.254.169.254 时，
     * 「先校验入口地址、再由 HTTP 客户端自己跳」等于把校验绕过去了。
     * 调用方需要逐跳重新校验，见 SubscriptionService。
     */
    suspend fun fetchBounded(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Long,
    ): HttpResult {
        val result = fetch(url, headers, followRedirects = false)
        if (result.body.size > maxBytes) {
            throw UpstreamException("响应体 ${result.body.size} 字节，超过 ${maxBytes / 1024 / 1024}MB 上限")
        }
        return result
    }
}

class JdkFetcher(
    private val timeoutMs: Long = 20_000,
    private val defaultUserAgent: String = DEFAULT_UA,
    /** 视频代理/分片下载的超时：远大于元数据请求，单独配置（ANIMEKO_PROXY_TIMEOUT_MS）。 */
    private val streamTimeoutMs: Long = timeoutMs,
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
        val request = buildRequest(url, headers)
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
        val request = buildRequest(url, headers, streamTimeoutMs)
        val cl = if (followRedirects) client else noRedirectClient
        val resp = cl.send(request, HttpResponse.BodyHandlers.ofInputStream())
        StreamResult(
            status = resp.statusCode(),
            headers = resp.headers().map().mapValues { it.value.toList() },
            body = resp.body(),
        )
    }

    override suspend fun fetchBounded(
        url: String,
        headers: Map<String, String>,
        maxBytes: Long,
    ): HttpResult = withContext(Dispatchers.IO) {
        val request = buildRequest(url, headers)
        // ofInputStream + 逐块读：上限要能在「读到的那一刻」生效。
        // ofByteArray 的话，上限只能在整份响应进堆之后检查，等于没有上限。
        val resp = noRedirectClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        val bytes = resp.body().use { readCapped(it, maxBytes) }
        HttpResult(
            status = resp.statusCode(),
            headers = resp.headers().map().mapValues { it.value.toList() },
            body = bytes,
        )
    }

    private fun readCapped(input: InputStream, maxBytes: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(chunk)
            if (n == -1) break
            total += n
            if (total > maxBytes) {
                throw UpstreamException("响应体超过 ${maxBytes / 1024 / 1024}MB 上限（已读 $total 字节）")
            }
            out.write(chunk, 0, n)
        }
        return out.toByteArray()
    }

    private fun buildRequest(
        url: String,
        headers: Map<String, String>,
        timeout: Long = timeoutMs,
    ): HttpRequest {
        // JDK 的 Builder.header() 是「追加」语义，已单独设置的头必须在下面的循环里跳过，
        // 否则会发出两个 User-Agent / Accept。
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(timeout))
            .header("User-Agent", headers["User-Agent"]?.takeIf { it.isNotBlank() } ?: defaultUserAgent)
            .header("Accept", headers["Accept"] ?: "*/*")
        headers.forEach { (k, v) ->
            if (!k.equals("User-Agent", ignoreCase = true) &&
                !k.equals("Accept", ignoreCase = true) &&
                isValidHeaderName(k)
            ) {
                builder.header(k, v)
            }
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
