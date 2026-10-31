package com.animeko.web.http

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real CompletableFuture/continuation races with a controlled transport seam, not a fabricated network result. */
class StreamOwnershipRegressionTest {
    private class TrackedBody : InputStream() {
        val closes = AtomicInteger()
        override fun read(): Int = -1
        override fun close() { closes.incrementAndGet() }
    }

    private class QueuedDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun hasQueuedTask(): Boolean = queue.isNotEmpty()
        fun drain() { while (true) (queue.poll() ?: return).run() }
    }

    private class Response(private val input: InputStream) : HttpResponse<InputStream> {
        override fun body(): InputStream = input
        override fun statusCode(): Int = 200
        override fun request(): HttpRequest = HttpRequest.newBuilder(uri()).GET().build()
        override fun previousResponse(): Optional<HttpResponse<InputStream>> = Optional.empty()
        override fun headers(): HttpHeaders = HttpHeaders.of(mapOf("content-type" to listOf("video/mp4"))) { _, _ -> true }
        override fun sslSession(): Optional<SSLSession> = Optional.empty()
        override fun uri(): URI = URI("https://transport.test/video.mp4")
        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
    }

    private class ControlledClient : HttpClient() {
        val started = CountDownLatch(1)
        // A completion already owned by the HTTP implementation need not be undone by cancel(true).
        val future = object : CompletableFuture<HttpResponse<InputStream>>() {
            override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
        }
        @Suppress("UNCHECKED_CAST")
        override fun <T> sendAsync(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): CompletableFuture<HttpResponse<T>> {
            started.countDown()
            return future as CompletableFuture<HttpResponse<T>>
        }
        override fun <T> sendAsync(request: HttpRequest, handler: HttpResponse.BodyHandler<T>, push: HttpResponse.PushPromiseHandler<T>): CompletableFuture<HttpResponse<T>> = sendAsync(request, handler)
        override fun <T> send(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> = error("must use cancellable sendAsync")
        override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()
        override fun connectTimeout(): Optional<Duration> = Optional.of(Duration.ofSeconds(1))
        override fun followRedirects(): Redirect = Redirect.NEVER
        override fun proxy(): Optional<ProxySelector> = Optional.empty()
        override fun sslContext(): SSLContext = SSLContext.getDefault()
        override fun sslParameters(): SSLParameters = SSLParameters()
        override fun authenticator(): Optional<Authenticator> = Optional.empty()
        override fun version(): Version = Version.HTTP_1_1
        override fun executor(): Optional<Executor> = Optional.empty()
    }

    private fun fetcher(transport: ControlledClient): JdkFetcher = JdkFetcher(timeoutMs = 1_000).also {
        // No public Fetcher contract changes: only replace the JDK transport in this ownership race fixture.
        JdkFetcher::class.java.getDeclaredField("noRedirectClient").apply { isAccessible = true }.set(it, transport)
    }

    private fun pump(dispatcher: QueuedDispatcher, until: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!until() && System.nanoTime() < deadline) {
            dispatcher.drain()
            Thread.sleep(1)
        }
        dispatcher.drain()
        return until()
    }

    @Test
    fun `a late response after caller cancellation closes its unclaimed body`() = runBlocking {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val transport = ControlledClient()
        val body = TrackedBody()
        try {
            val request = scope.async(start = CoroutineStart.UNDISPATCHED) { fetcher(transport).fetchStream("https://transport.test/video.mp4") }
            assertTrue(transport.started.await(3, TimeUnit.SECONDS), "transport seam was not reached")
            request.cancel()
            transport.future.complete(Response(body))
            assertTrue(pump(dispatcher) { request.isCompleted })
            assertEquals(1, body.closes.get(), "inactive continuation discarded an InputStream instead of releasing it")
        } finally {
            scope.cancel()
            dispatcher.drain()
            if (body.closes.get() == 0) body.close()
        }
    }

    @Test
    fun `cancellation before delivery on the original dispatcher closes the response body`() = runBlocking {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val transport = ControlledClient()
        val body = TrackedBody()
        try {
            val request = scope.async(start = CoroutineStart.UNDISPATCHED) { fetcher(transport).fetchStream("https://transport.test/video.mp4") }
            assertTrue(transport.started.await(3, TimeUnit.SECONDS))
            transport.future.complete(Response(body))
            // Do not run queued delivery: this is the withContext/resume prompt-cancellation window.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!dispatcher.hasQueuedTask() && System.nanoTime() < deadline) Thread.sleep(1)
            assertTrue(dispatcher.hasQueuedTask(), "response never reached the caller delivery boundary")
            request.cancel()
            assertTrue(pump(dispatcher) { request.isCompleted })
            assertEquals(1, body.closes.get(), "response body lost ownership while returning to caller dispatcher")
        } finally {
            scope.cancel()
            dispatcher.drain()
            if (body.closes.get() == 0) body.close()
        }
    }
}
