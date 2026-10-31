package com.animeko.web.data

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import org.junit.jupiter.api.Assumptions.assumeFalse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class UserDataRecoveryBoundaryTest {
    private fun withStore(block: (UserDataStore, Path) -> Unit) {
        val root = Path.of(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir"))
        val dir = Files.createTempDirectory(root, "animeko-data-recovery-")
        try {
            val file = dir.resolve("userdata.json")
            block(UserDataStore(file), file)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing main with unreadable backup is a storage failure not an empty document`() = withStore { store, file ->
        val backup = file.resolveSibling("${file.fileName}.bak")
        Files.createDirectory(backup)
        Files.writeString(backup.resolve("recoverable.json"), """{"favorites":[{"id":7}]}""")

        assertFailsWith<UserDataStore.ReadFailed> { store.read() }
        assertFalse(Files.exists(file), "failed recovery must not publish an empty main document")
        assertEquals("""{"favorites":[{"id":7}]}""", Files.readString(backup.resolve("recoverable.json")))
    }

    @Test
    fun `a valid backup without read permission cannot be reported as missing`() = withStore { store, file ->
        val backup = file.resolveSibling("${file.fileName}.bak")
        Files.writeString(backup, """{"favorites":[{"id":7}]}""")
        val permissions = Files.getPosixFilePermissions(backup)
        try {
            Files.setPosixFilePermissions(backup, emptySet())
            assumeFalse(Files.isReadable(backup), "this runtime bypasses POSIX permissions")
            assertFailsWith<UserDataStore.ReadFailed> { store.read() }
            assertFalse(Files.exists(file))
        } finally {
            Files.setPosixFilePermissions(backup, permissions)
        }
    }

    @Test
    fun `failed backup restoration does not return an uncommitted snapshot`() = withStore { store, file ->
        val backup = file.resolveSibling("${file.fileName}.bak")
        val body = """{"favorites":[{"id":7}]}"""
        Files.writeString(backup, body)
        val permissions = Files.getPosixFilePermissions(file.parent)
        try {
            Files.setPosixFilePermissions(file.parent, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE))
            assumeFalse(Files.isWritable(file.parent), "this runtime bypasses POSIX permissions")
            assertFailsWith<UserDataStore.ReadFailed> { store.read() }
            assertFalse(Files.exists(file), "a failed restore must not claim to have created the main file")
            assertEquals(body, Files.readString(backup))
        } finally {
            Files.setPosixFilePermissions(file.parent, permissions)
        }
        val restored = store.read()
        assertEquals(body, Files.readString(file))
        store.write("""{"favorites":[{"id":7},{"id":8}]}""", restored.etag)
    }

    @Test
    fun `a failed corrupt-file quarantine must not report a usable empty document`() = withStore { store, file ->
        Files.writeString(file, "{broken")
        val quarantine = file.resolveSibling("userdata.json.bad")
        Files.createDirectory(quarantine)
        Files.writeString(quarantine.resolve("evidence"), "keep")

        assertFailsWith<UserDataStore.ReadFailed> { store.read() }
        assertEquals("{broken", Files.readString(file))
        assertEquals("keep", Files.readString(quarantine.resolve("evidence")))
    }

    @Test
    fun `an occupied restore temporary path cannot produce an uncommitted etag`() = withStore { store, file ->
        val body = """{"favorites":[{"id":7}]}"""
        val backup = file.resolveSibling("userdata.json.bak")
        Files.writeString(backup, body)
        val temporary = file.resolveSibling("userdata.json.tmp")
        Files.createDirectory(temporary)
        Files.writeString(temporary.resolve("evidence"), "keep")

        assertFailsWith<UserDataStore.ReadFailed> { store.read() }
        assertFalse(Files.exists(file))
        assertEquals(body, Files.readString(backup))
    }
}
