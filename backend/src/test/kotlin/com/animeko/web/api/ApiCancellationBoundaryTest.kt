package com.animeko.web.api

import com.animeko.web.http.Fetcher
import com.animeko.web.http.HttpResult
import com.animeko.web.http.StreamResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readAvailable
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ApiCancellationBoundaryTest {
    @Test
    fun `proxy fetch preserves the exact caller cancellation rather than converting it to upstream failure`() = runBlocking {
        val cancelled = CancellationException("fixture cancellation")
        val fetcher = object : Fetcher {
            override suspend fun fetchStream(url: String, headers: Map<String, String>, followRedirects: Boolean): StreamResult = throw cancelled
            override suspend fun fetch(url: String, headers: Map<String, String>, followRedirects: Boolean): HttpResult = error("unused")
            override suspend fun post(url: String, body: String, headers: Map<String, String>, contentType: String): HttpResult = error("unused")
        }
        val caught = assertFailsWith<CancellationException> { fetchProxyStream(fetcher, "https://fixture.test/movie.mp4", emptyMap()) }
        assertSame(cancelled, caught)
    }

    @Test
    fun `request body read preserves the exact caller cancellation rather than reporting missing fields`() = runBlocking {
        val cancelled = CancellationException("cancelled body receive")
        val caught = assertFailsWith<CancellationException> { readJsonObject { throw cancelled } }
        assertSame(cancelled, caught)
    }

    @Test
    fun `cancellation while waiting for a real request byte channel cannot become a null form`() = runBlocking {
        val channel = ByteChannel()
        val entered = CompletableDeferred<Unit>()
        val cancelled = CancellationException("fixture request channel cancelled")
        val received = AtomicReference<CancellationException>()
        val forwarded = AtomicReference<CancellationException>()
        val read = async {
            try {
                readJsonObject {
                    entered.complete(Unit)
                    try {
                        channel.readAvailable(ByteArray(8))
                        "{}"
                    } catch (failure: CancellationException) {
                        received.set(failure)
                        throw failure
                    }
                }
            } catch (failure: CancellationException) {
                forwarded.set(failure)
                throw failure
            }
        }
        entered.await()
        channel.cancel(cancelled)
        assertFailsWith<CancellationException> { read.await() }
        // Coroutine stacktrace recovery can clone an exception across Deferred.await.
        // Assert identity at the parser boundary, and type at the coroutine boundary.
        assertTrue(received.get() != null)
        assertSame(received.get(), forwarded.get())
    }

    @Test
    fun `already cancelled request channel cannot be mistaken for clean EOF and a missing form`() = runBlocking {
        val channel = ByteChannel()
        val cancelled = CancellationException("cancel before body read")
        channel.cancel(cancelled)
        val caught = assertFailsWith<CancellationException> { readJsonObject { readRequestChannel(channel) } }
        // Ktor CloseToken creates a fresh CancellationException with the original as its cause.
        assertSame(cancelled, caught.cause)
    }

    @Test
    fun `failed request channel EOF preserves its I O failure rather than returning an empty form`() = runBlocking {
        val channel = ByteChannel()
        val failure = IOException("body channel already failed")
        channel.cancel(failure)
        val caught = assertFailsWith<IOException> { readJsonObject { readRequestChannel(channel) } }
        // closedCause is a ClosedByteChannelException wrapper, not the original IOException.
        assertSame(failure, caught.cause)
    }

    @Test
    fun `request channel I O failures cannot become a malformed or missing JSON field`() = runBlocking {
        val failure = IOException("fixture channel failed")
        val caught = assertFailsWith<IOException> { readJsonObject { throw failure } }
        assertSame(failure, caught)
        assertNull(readJsonObject { "not JSON" }, "actual syntax errors still follow the established 400/missing-field contract")
        assertNull(readJsonObject { "[]" }, "non-object JSON remains a shape error")
    }
}
