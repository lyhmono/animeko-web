package com.animeko.web.selector

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals

class SubscriptionIdentityRegressionTest {
    @Test
    fun `selection names remain opaque preview identifiers including whitespace`() = runTest {
        val body = """[
            {"name":" S ","description":"selected","searchConfig":{"searchUrl":"https://selected.test/s"}},
            {"name":"S","description":"not selected","searchConfig":{"searchUrl":"https://other.test/s"}}
        ]"""
        val repo = SelectorRepository(selectorTempDir("subscription-identity"))
        val service = SubscriptionService(StubFetcher(emptyMap()), repo, false)
        val preview = service.preview(null, body)
        assertEquals(listOf(" S ", "S"), preview.candidates.map { it.name })
        val imported = service.importSelected(null, body, listOf(preview.candidates.first().name), preview.fingerprint)
        assertEquals(listOf(" S "), imported.added)
        assertEquals(emptyList(), imported.failed)
        assertEquals("selected", repo.list().single().arguments.description)
        assertEquals(" S ", repo.list().single().arguments.name)
    }

    @Test
    fun `identical subscriptions do not rewrite already committed source files`() = runTest {
        val body = """[{"name":"S","searchConfig":{"searchUrl":"https://source.test/s"}}]"""
        val dir = selectorTempDir("subscription-no-op")
        val repo = SelectorRepository(dir)
        val service = SubscriptionService(StubFetcher(emptyMap()), repo, false)
        assertEquals(listOf("S"), service.importSelected(null, body, listOf("S")).added)
        val path = dir.resolve("S.json")
        val sentinel = FileTime.fromMillis(1_700_000_000_000)
        Files.setLastModifiedTime(path, sentinel)
        val before = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java)
        assertEquals(listOf("S"), service.importSelected(null, body, listOf("S")).updated)
        val after = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java)
        assertEquals(before.lastModifiedTime(), after.lastModifiedTime(), "unchanged import must not fsync/rename")
        assertEquals(before.fileKey(), after.fileKey(), "unchanged import must keep the same file")
    }
}
