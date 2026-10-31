package com.animeko.web.data

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class UserDataWriteRecoveryTest {
    private fun withStore(block: (UserDataStore, Path) -> Unit) {
        val root = Path.of(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir"))
        val dir = Files.createTempDirectory(root, "animeko-data-write-recovery-")
        try {
            block(UserDataStore(dir.resolve("userdata.json")), dir.resolve("userdata.json"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `conditional write recovers the same version used by the read path`() = withStore { store, file ->
        val previous = store.write("""{"favorites":[{"id":7}]}""")
        Files.copy(file, file.resolveSibling("userdata.json.bak"))
        Files.delete(file)

        val written = store.write("""{"favorites":[{"id":7},{"id":8}]}""", previous.etag)
        assertEquals(written.text, Files.readString(file))
        assertEquals(written.etag, store.read().etag)
        assertEquals(previous.text, Files.readString(file.resolveSibling("userdata.json.bak")))
    }

    @Test
    fun `an empty baseline cannot overwrite a recoverable backup`() = withStore { store, file ->
        val emptyEtag = store.read().etag
        val previous = store.write("""{"favorites":[{"id":7}]}""")
        Files.copy(file, file.resolveSibling("userdata.json.bak"))
        Files.delete(file)

        assertFailsWith<UserDataStore.Stale> { store.write("{}", emptyEtag) }
        assertEquals(previous.text, store.read().text)
    }

    @Test
    fun `write refuses an unreadable backup even without If-Match`() = withStore { store, file ->
        Files.createDirectory(file.resolveSibling("userdata.json.bak"))
        assertFailsWith<IllegalArgumentException> { store.write("""{"new":true}""") }
        assertFalse(Files.exists(file))
    }
}
