package com.animeko.web.http

import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/** Several cancellation/finally paths may race; the underlying resource is still closed exactly once. */
internal class CloseOnceInputStream(input: InputStream) : FilterInputStream(input) {
    private val closed = AtomicBoolean()
    override fun close() {
        if (closed.compareAndSet(false, true)) super.close()
    }
}

internal fun InputStream.closeOnce(): InputStream = if (this is CloseOnceInputStream) this else CloseOnceInputStream(this)
internal fun InputStream.closeQuietly() { runCatching { close() } }

/** Finite bodies: IO reads, deadline over the entire body, decoded-byte cap and active close on cancellation. */
internal suspend fun readStreamBytes(
    input: InputStream,
    maxBytes: Long,
    timeoutMs: Long,
    contentEncoding: String? = null,
): ByteArray {
    val owned = input.closeOnce()
    try {
        return supervisorScope {
            val reader = async(Dispatchers.IO) {
                decodedStream(owned, contentEncoding).use { stream ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = stream.read(buffer)
                        if (count == -1) break
                        if (count == 0) { yield(); continue }
                        total += count
                        if (total > maxBytes) throw UpstreamException("响应体超过 ${maxBytes / 1024 / 1024}MB 上限（已读 $total 字节）")
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray()
                }
            }
            try {
                withTimeoutOrNull(timeoutMs) { reader.await() }
                    ?: throw UpstreamException("响应体读取超时（${timeoutMs}ms）")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                throw UpstreamException("读取响应体失败：${failure.message ?: failure::class.simpleName}")
            } finally {
                // Close before supervisorScope waits for its blocking child; cancel alone cannot wake read().
                owned.closeQuietly()
                reader.cancel()
            }
        }
    } finally { owned.closeQuietly() }
}

/** Binary media has no total duration limit. Each IO read has a cancellable owner; writes are backpressured. */
internal suspend fun copyStreamCancellable(input: InputStream, output: ByteWriteChannel) {
    val owned = input.closeOnce()
    try {
        supervisorScope {
            try {
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = async(Dispatchers.IO) { owned.read(buffer) }.await()
                    if (count == -1) break
                    if (count == 0) { yield(); continue }
                    output.writeFully(buffer, 0, count)
                    output.flush()
                }
            } finally { owned.closeQuietly() }
        }
    } finally { owned.closeQuietly() }
}

/** Construct decompression wrappers inside the IO reader, including their potentially blocking header reads. */
private fun decodedStream(input: InputStream, contentEncoding: String?): InputStream {
    var result = input
    val encodings = contentEncoding.orEmpty().split(',').map { it.trim().lowercase() }.filter(String::isNotEmpty)
    for (encoding in encodings.asReversed()) {
        result = when (encoding) {
            "identity" -> result
            "gzip", "x-gzip" -> GZIPInputStream(result)
            "deflate" -> InflaterInputStream(result)
            else -> throw UpstreamException("播放清单的内容编码不受支持：$encoding")
        }
    }
    return result
}
