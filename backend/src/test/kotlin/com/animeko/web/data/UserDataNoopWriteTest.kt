package com.animeko.web.data

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UserDataNoopWriteTest {
    @Test
    fun `an identical write preserves the last distinct backup`() {
        val root = Path.of(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir"))
        val dir = Files.createTempDirectory(root, "animeko-data-noop-")
        try {
            val file = dir.resolve("userdata.json")
            val store = UserDataStore(file)
            val first = store.write("""{"v":1}""")
            val second = store.write("""{"v":2}""", first.etag)
            val repeated = store.write(second.text, second.etag)

            assertEquals(second.text, repeated.text)
            assertEquals(second.etag, repeated.etag)
            assertEquals(first.text, Files.readString(file.resolveSibling("userdata.json.bak")), "no-op writes must not consume the only prior distinct version")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `an identical write does not touch the main file or temporary path`() {
        val root = Path.of(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir"))
        val dir = Files.createTempDirectory(root, "animeko-data-noop-io-")
        try {
            val file = dir.resolve("userdata.json")
            val store = UserDataStore(file)
            val first = store.write("""{"v":1}""")
            val second = store.write("""{"v":2}""", first.etag)
            val unchangedTime = FileTime.fromMillis(1_000)
            Files.setLastModifiedTime(file, unchangedTime)
            Files.createDirectory(file.resolveSibling("userdata.json.tmp"))

            assertEquals(second.etag, store.write(second.text, second.etag).etag)
            assertEquals(unchangedTime, Files.getLastModifiedTime(file))
            assertEquals(first.text, Files.readString(file.resolveSibling("userdata.json.bak")))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a stale If-Match is rejected even when the submitted content is identical`() {
        val root = Path.of(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir"))
        val dir = Files.createTempDirectory(root, "animeko-data-noop-stale-")
        try {
            val store = UserDataStore(dir.resolve("userdata.json"))
            val first = store.write("""{"v":1}""")
            val second = store.write("""{"v":2}""", first.etag)
            assertFailsWith<UserDataStore.Stale> { store.write(second.text, first.etag) }
            assertEquals(second.etag, store.read().etag)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `an initial empty object write still creates a real main document`() {
        val root = Path.of(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir"))
        val dir = Files.createTempDirectory(root, "animeko-data-noop-first-")
        try {
            val file = dir.resolve("userdata.json")
            val store = UserDataStore(file)
            val empty = store.read()
            assertEquals(empty.etag, store.write("{}", empty.etag).etag)
            assertTrue(Files.isRegularFile(file))
            assertEquals("{}", Files.readString(file))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
