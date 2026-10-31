package com.animeko.web.selector

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SourceThrottleTest {
    @Test
    fun `cancelled waiters leave only the last actual start interval`() = runTest {
        val limiter = SourceThrottle { currentTime }
        limiter.await("A", 1_000)
        assertEquals(0, currentTime)
        repeat(20) {
            val abandoned = launch { limiter.await("A", 1_000) }
            runCurrent()
            abandoned.cancelAndJoin()
        }
        limiter.await("A", 1_000)
        assertEquals(1_000, currentTime)
        limiter.await("A", 1_000)
        assertEquals(2_000, currentTime)
    }

    @Test
    fun `waiting on one source does not block admission for another`() = runTest {
        val limiter = SourceThrottle { currentTime }
        limiter.await("A", 1_000)
        val waiting = async { limiter.await("A", 1_000) }
        runCurrent()
        limiter.await("B", 1_000)
        assertEquals(0, currentTime)
        advanceUntilIdle()
        waiting.await()
        assertEquals(1_000, currentTime)
    }

    @Test
    fun `source queue length is bounded and cancellation releases admission`() = runTest {
        val limiter = SourceThrottle { currentTime }
        limiter.await("A", 1_000)
        val waiters = List(64) { launch { limiter.await("A", 1_000) } }
        runCurrent()
        assertFailsWith<SelectorException> { limiter.await("A", 1_000) }
        waiters.forEach { it.cancelAndJoin() }
        limiter.await("A", 1_000)
        assertEquals(1_000, currentTime)
    }
}
