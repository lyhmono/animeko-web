package com.animeko.web.selector

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelectorRepositoryConcurrencyRegressionTest {
    private fun args(version: String) = SelectorSourceArguments(
        name = "S", description = version,
        searchConfig = SelectorSearchConfig(searchUrl = "https://source.test/$version?q={keyword}"),
    )

    // Freeze the repository's write monitor. The old implementation takes its read snapshot
    // before blocking in save; an atomic implementation blocks before reading anything.
    private fun whileToggleWaiting(repo: SelectorRepository, change: () -> Unit): Boolean {
        val finished = CompletableFuture<Boolean>()
        val worker = Thread {
            try { finished.complete(repo.setEnabled("S", false)) }
            catch (e: Throwable) { finished.completeExceptionally(e) }
        }
        synchronized(repo) {
            worker.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (worker.state != Thread.State.BLOCKED && !finished.isDone && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals(Thread.State.BLOCKED, worker.state, "toggle must reach the write monitor")
            change()
        }
        return finished.get(5, TimeUnit.SECONDS).also { worker.join(5000) }
    }

    @Test
    fun `enabled update cannot overwrite a newer configuration`() {
        val repo = SelectorRepository(selectorTempDir("selector-atomic-toggle"))
        repo.save(args("old"))
        assertTrue(whileToggleWaiting(repo) { repo.save(args("new")) })
        val stored = repo.find("S")!!
        assertEquals("new", stored.arguments.description)
        assertFalse(stored.enabled)
    }

    @Test
    fun `enabled update cannot resurrect a deleted source`() {
        val repo = SelectorRepository(selectorTempDir("selector-atomic-delete"))
        repo.save(args("old"))
        assertFalse(whileToggleWaiting(repo) { assertTrue(repo.delete("S")) })
        assertNull(repo.find("S"))
    }

    @Test
    fun `find uses a direct source path rather than enumerating the directory`() {
        val dir = selectorTempDir("selector-direct-name")
        if (!Files.getFileStore(dir).supportsFileAttributeView("posix")) return
        val repo = SelectorRepository(dir)
        repo.save(args("old"))
        val original = Files.getPosixFilePermissions(dir)
        try {
            Files.setPosixFilePermissions(dir, setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            ))
            assertEquals("old", repo.find("S")?.arguments?.description,
                "source file is readable via traversal; directory enumeration is intentionally forbidden")
        } finally {
            Files.setPosixFilePermissions(dir, original)
        }
    }

    @Test
    fun `cached reads serialize with writes even when mtimes collide`() {
        val dir = selectorTempDir("selector-cache-race")
        val repo = SelectorRepository(dir)
        repo.save(args("old"))
        val path = dir.resolve("S.json")
        val sameTime = FileTime.fromMillis(1_700_000_000_000)
        Files.setLastModifiedTime(path, sameTime)
        repo.find("S") // warm the old cache
        val entered = java.util.concurrent.CountDownLatch(1)
        val read = CompletableFuture<StoredSource?>()
        val worker = Thread {
            entered.countDown()
            try { read.complete(repo.find("S")) }
            catch (e: Throwable) { read.completeExceptionally(e) }
        }
        synchronized(repo) {
            worker.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!read.isDone && worker.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
            repo.save(args("new"))
            Files.setLastModifiedTime(path, sameTime)
        }
        assertEquals("new", read.get(5, TimeUnit.SECONDS)?.arguments?.description,
            "a read racing a serialized write must not publish the old same-mtime cache")
        worker.join(5000)
        assertEquals("new", repo.find("S")?.arguments?.description)
    }
}
