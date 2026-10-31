package com.animeko.web.selector

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SelectorThrottleRegressionTest {
    @Test
    fun `cancelled queued searches do not reserve future throttle starts`() = runBlocking {
        val base = "http://127.0.0.1"
        val fetcher = StubFetcher(mapOf("$base/search" to """<a href="/a">A</a>"""))
        val cfg = SelectorSearchConfig(
            searchUrl = "$base/search?q={keyword}", requestInterval = 200,
            selectorSubjectFormatA = SubjectFormatA.Config(selectLists = "a"),
        )
        val engine = selectorTestEngine(fetcher, allowPrivateTargets = true)
        engine.search(cfg, "first")
        repeat(8) { index ->
            val abandoned = launch { engine.search(cfg, "cancel$index") }
            delay(25)
            abandoned.cancelAndJoin()
        }
        val result = withTimeoutOrNull(600) { engine.search(cfg, "live") }
        assertNotNull(result, "cancelled tasks left more than one request interval of reservations")
        assertEquals("A", result.single().name)
    }
}
