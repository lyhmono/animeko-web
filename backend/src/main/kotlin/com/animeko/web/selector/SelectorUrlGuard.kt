package com.animeko.web.selector

import com.animeko.web.http.UrlGuard
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** DNS can ignore thread interruption. Cancel the awaiting coroutine without joining that worker. */
internal class SelectorUrlGuard(
    private val lookup: (String, Boolean) -> String = UrlGuard::requireAllowed,
    workers: Int = 2,
    queueSize: Int = 64,
) : AutoCloseable {
    private val executor = ThreadPoolExecutor(
        workers, workers, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(queueSize),
        { action -> Thread(action, "selector-url-guard").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }

    suspend fun requireAllowed(url: String, allowPrivate: Boolean): String = suspendCancellableCoroutine { continuation ->
        val task = FutureTask<Unit> {
            try {
                val checked = lookup(url, allowPrivate)
                if (continuation.isActive) continuation.resume(checked)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
        continuation.invokeOnCancellation {
            task.cancel(true)
            executor.remove(task)
        }
        if (continuation.isActive) {
            try {
                executor.execute(task)
                // Cover cancellation racing the execute/queue handoff.
                if (task.isCancelled) executor.remove(task)
            } catch (e: RejectedExecutionException) {
                task.cancel(false)
                if (continuation.isActive) continuation.resumeWithException(SelectorException("数据源 DNS 校验队列繁忙，请稍后重试"))
            }
        }
    }

    override fun close() { executor.shutdownNow() }

    companion object {
        val shared = SelectorUrlGuard()
    }
}
