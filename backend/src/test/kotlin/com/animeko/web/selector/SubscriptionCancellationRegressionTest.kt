package com.animeko.web.selector

import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class SubscriptionCancellationRegressionTest {
    private val body = """[{"name":"S","searchConfig":{"searchUrl":"https://source.test/s"}}]"""

    @Test
    fun `cancelled selected import cannot commit a new source`() = runTest {
        val repo = SelectorRepository(selectorTempDir("subscription-cancel"))
        val service = SubscriptionService(StubFetcher(emptyMap()), repo, false)
        var entered = false
        val worker = launch {
            currentCoroutineContext().cancel()
            entered = true
            service.importSelected(null, body, listOf("S"))
        }
        worker.join()
        assertTrue(entered, "exercise import from a cancelled caller, not an unstarted job")
        assertTrue(repo.list().isEmpty(), "a cancelled caller still wrote its selected source")
    }

    @Test
    fun `cancelled automatic import cannot commit a new source`() = runTest {
        val repo = SelectorRepository(selectorTempDir("subscription-auto-cancel"))
        val url = "http://127.0.0.1/subscription"
        val service = SubscriptionService(StubFetcher(mapOf(url to body)), repo, true)
        var entered = false
        val worker = launch {
            currentCoroutineContext().cancel()
            entered = true
            service.autoImport(listOf(url), emptySet())
        }
        worker.join()
        assertTrue(entered)
        assertTrue(repo.list().isEmpty(), "cancelled auto-import still committed an item")
    }
}
