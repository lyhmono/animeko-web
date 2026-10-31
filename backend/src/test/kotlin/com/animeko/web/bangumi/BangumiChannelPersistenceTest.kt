package com.animeko.web.bangumi

import com.animeko.web.http.Fetcher
import com.animeko.web.http.HttpResult
import com.animeko.web.http.StreamResult
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BangumiChannelPersistenceTest {
    private val unusedFetcher = object : Fetcher {
        override suspend fun fetch(url: String, headers: Map<String, String>, followRedirects: Boolean): HttpResult =
            error("this test must not make upstream requests")
        override suspend fun post(url: String, body: String, headers: Map<String, String>, contentType: String): HttpResult =
            error("this test must not make upstream requests")
        override suspend fun fetchStream(url: String, headers: Map<String, String>, followRedirects: Boolean): StreamResult =
            error("this test must not make upstream requests")
    }

    @Test
    fun `failed persistence leaves the published channel unchanged`() {
        val root = Path.of(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir"))
        val dir = Files.createTempDirectory(root, "animeko-channel-transaction-")
        try {
            val file = dir.resolve("c.json")
            val store = BgmChannelStore(file)
            val client = BangumiClient(unusedFetcher, channelStore = store)
            val previous = client.setChannel(BgmChannelConfig(BgmChannel.OFFICIAL))
            Files.createDirectory(file.resolveSibling("c.json.tmp"))

            assertFailsWith<Exception> { client.setChannel(BgmChannelConfig(BgmChannel.MIRROR)) }
            assertEquals(previous, client.channelState(), "a rejected save must not change the runtime channel")
            assertEquals(listOf(BangumiClient.OFFICIAL_BASE), client.effectiveBases())
            assertEquals(previous, store.load(), "the previous committed configuration must remain on disk")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `concurrent channel updates publish only committed configurations`() {
        val root = Path.of(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir"))
        val dir = Files.createTempDirectory(root, "animeko-channel-concurrent-")
        val outcomes = listOf(AtomicReference<Throwable?>(), AtomicReference<Throwable?>())
        var writers = emptyList<Thread>()
        try {
            val store = BgmChannelStore(dir.resolve("c.json"))
            val initial = BgmChannelConfig()
            store.save(initial)
            val client = BangumiClient(unusedFetcher, channelStore = store)
            writers = listOf(BgmChannel.OFFICIAL, BgmChannel.MIRROR).mapIndexed { index, channel ->
                Thread {
                    try {
                        client.setChannel(BgmChannelConfig(channel))
                    } catch (failure: Throwable) {
                        outcomes[index].set(failure)
                    }
                }
            }
            // This gate is the real store's existing save monitor, not a simulated persistence response.
            synchronized(store) {
                for (writer in writers) {
                    writer.start()
                    val deadline = System.nanoTime() + 5_000_000_000L
                    while (writer.state != Thread.State.BLOCKED && writer.isAlive && System.nanoTime() < deadline) Thread.sleep(1)
                    assertEquals(Thread.State.BLOCKED, writer.state, "writer must have reached the persistence/commit boundary")
                }
                assertEquals(initial, client.channelState(), "waiting saves must not expose uncommitted channel state")
                assertEquals(initial, store.load())
            }
            writers.forEach { it.join(5_000) }
            writers.forEach { assertFalse(it.isAlive, "a completed transaction must not retain a blocked writer") }
            outcomes.forEach { assertTrue(it.get() == null, "unexpected save failure: ${it.get()}") }
            assertEquals(store.load(), client.channelState(), "runtime and disk must agree after both successful updates")
        } finally {
            writers.forEach { if (it.state != Thread.State.NEW) it.join(5_000) }
            dir.toFile().deleteRecursively()
        }
    }
}
