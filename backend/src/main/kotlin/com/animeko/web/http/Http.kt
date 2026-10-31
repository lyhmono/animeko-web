package com.animeko.web.http

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

/** 出站 HTTP 抽象，便于测试时打桩。 */
interface Fetcher {
    suspend fun fetch(
        url: String,
        headers: Map<String, String> = emptyMap(),
        followRedirects: Boolean = true,
    ): HttpResult

    /**
     * POST 一律不自动跟随重定向（唯一调用方是 Bangumi 搜索）。
     * 三个理由，任一都足够：重定向到别的主体时 301/302 会退化成 GET，
     * 请求体就此丢失；一个 JSON API 本来也不该靠跳转工作；
     * 而自动跳转意味着镜像站点可以把这台服务器的请求引到内网地址上 ——
     * 这条链路没有 UrlGuard（代理与抓取那两条有，见各自的逐跳校验）。
     *
     * 实现必须对响应体限长：默认 base 列表里含社区镜像，镜像异常或恶意返回
     * 一个巨大响应时，`ofByteArray()` 会先把整份内容吃进堆里再谈限制。
     */
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
        val resp = cl.sendCancellable(request, HttpResponse.BodyHandlers.ofByteArray())
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
        // 见接口上的注释：POST 永不自动跳转；响应体边读边限，不能先 ofByteArray 再检查。
        val resp = noRedirectClient.sendCancellable(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        val bytes = readCappedTimed(resp.body(), MAX_POST_BYTES)
        HttpResult(
            status = resp.statusCode(),
            headers = resp.headers().map().mapValues { it.value.toList() },
            body = bytes,
        )
    }

    override suspend fun fetchStream(
        url: String,
        headers: Map<String, String>,
        followRedirects: Boolean,
    ): StreamResult {
        // sendAsync is non-blocking. Do not return an InputStream across withContext(IO):
        // prompt cancellation can discard that result before any caller acquires ownership.
        val request = buildRequest(url, headers, streamTimeoutMs)
        val cl = if (followRedirects) client else noRedirectClient
        val resp = cl.sendCancellable(request, HttpResponse.BodyHandlers.ofInputStream())
        return try {
            StreamResult(resp.statusCode(), resp.headers().map().mapValues { it.value.toList() }, resp.body().closeOnce())
        } catch (failure: Throwable) {
            resp.body().closeQuietly()
            throw failure
        }
    }

    override suspend fun fetchBounded(
        url: String,
        headers: Map<String, String>,
        maxBytes: Long,
    ): HttpResult = withContext(Dispatchers.IO) {
        val request = buildRequest(url, headers)
        // ofInputStream + 逐块读：上限要能在「读到的那一刻」生效。
        // ofByteArray 的话，上限只能在整份响应进堆之后检查，等于没有上限。
        val resp = noRedirectClient.sendCancellable(request, HttpResponse.BodyHandlers.ofInputStream())
        val bytes = readCappedTimed(resp.body(), maxBytes)
        HttpResult(
            status = resp.statusCode(),
            headers = resp.headers().map().mapValues { it.value.toList() },
            body = bytes,
        )
    }

    /**
     * 可取消的发送。
     *
     * `HttpClient.send()` 是阻塞的，而 `withContext(Dispatchers.IO)` 只是换线程，
     * **不会**在协程取消时打断它。Bangumi 的地址竞速靠 cancel 输家来把总耗时
     * 压到「最快的那个」，用阻塞 send 时输家仍会跑满 45 秒超时，coroutineScope
     * 也要等它结束才返回 —— 竞速在真实的黑洞地址上等于没做。
     *
     * sendAsync 的 future 在协程取消时会被 cancel，JDK 随即关闭这次交换，
     * 竞速与 withTimeout 才能按时返回。
     */
    private suspend fun <T> HttpClient.sendCancellable(
        request: HttpRequest,
        handler: HttpResponse.BodyHandler<T>,
    ): HttpResponse<T> = suspendCancellableCoroutine { cont ->
        val future = sendAsync(request, handler)
        cont.invokeOnCancellation { future.cancel(true) }
        future.whenComplete { response, error ->
            // Cancelling an already completed future cannot close its unconsumed body.
            if (!cont.isActive) {
                response?.let(::closeResponseBody)
                return@whenComplete
            }
            when {
                error != null -> cont.resumeWithException(error)
                response != null -> cont.resume(response) { _, lostResponse, _ -> closeResponseBody(lostResponse) }
            }
        }
    }

    /**
     * 响应头到手后，请求超时就不再约束 body 读取了；这里给阻塞读加一个期限，
     * 超时主动关闭流，避免慢速镜像长期占住 IO 线程。
     */
    private suspend fun readCappedTimed(input: InputStream, maxBytes: Long): ByteArray =
        readStreamBytes(input, maxBytes, streamTimeoutMs)

    private fun closeResponseBody(response: HttpResponse<*>) {
        (response.body() as? InputStream)?.closeQuietly()
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
        /** Bangumi 搜索/POST 响应体上限；真实响应只有几十 KB，8MB 只防异常/恶意大包。 */
        private const val MAX_POST_BYTES = 8L * 1024 * 1024

        const val DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3"

        fun isValidHeaderName(name: String): Boolean =
            name.isNotBlank() && name.all { it.code in 33..126 && it != ':' }

        fun encodeUrl(s: String): String = URLEncoder.encode(s, "UTF-8")
    }
}
