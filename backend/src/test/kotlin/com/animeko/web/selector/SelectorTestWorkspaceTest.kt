package com.animeko.web.selector

import com.animeko.web.testDirectory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 夹具目录的防回归契约。
 *
 * CI 曾经在这里整片红：helper 把 TMPDIR 的兜底写成了某个人的缓存目录，
 * 干净检出没有那个父目录，`Files.createTempDirectory` 不会补父级，
 * 于是 9 条 selector 测试全在 fixture 行抛 NoSuchFileException，而本地因为目录残留一路假绿。
 */
class SelectorTestWorkspaceTest {

    @Test
    fun `fixtures land under the runner temp root rather than an arbitrary path`() {
        val expectedRoots = listOfNotNull(
            System.getenv("TMPDIR")?.let { Path.of(it) },
            Path.of(System.getProperty("java.io.tmpdir")),
        )
        val created = (1..3).map { selectorTempDir("selector-contract-$it") }
        try {
            assertTrue(created.distinct().size == created.size, "two fixtures shared one directory")
            created.forEach { fixture ->
                assertTrue(Files.isDirectory(fixture), "fixture directory missing: $fixture")
                assertTrue(expectedRoots.any { fixture.startsWith(it) }, "fixture escaped the temp root: $fixture")
                assertTrue(fixture.fileName.toString().startsWith("selector-contract-"))
            }
        } finally {
            created.forEach { dir -> Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } } }
        }
    }

    @Test
    fun `missing parents are created instead of throwing NoSuchFileException`() {
        val root = testDirectory("selector-nested-parent")
        try {
            val deep = root.resolve("does/not/exist/yet")
            val fixture = newFixtureDirectory(deep, "selector-nested")
            assertTrue(Files.isDirectory(fixture), "a missing parent root must be created, not fail: $fixture")
            assertTrue(fixture.startsWith(deep))
        } finally {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    @Test
    fun `no test helper bakes in a machine specific cache path`() {
        listOf(
            "src/test/kotlin/com/animeko/web/TestDirs.kt",
            "src/test/kotlin/com/animeko/web/selector/SelectorTestWorkspace.kt",
        ).forEach { relative ->
            val source = Path.of(relative).toAbsolutePath()
            assertTrue(Files.isRegularFile(source), "helper disappeared, update this contract: $source")
            val text = Files.readAllBytes(source).decodeToString()
            assertTrue(!text.contains(".hermes"), "$relative hardcodes a personal machine path")
            assertTrue(!text.contains("/tmp/"), "$relative hardcodes an absolute temp path")
        }
    }
}
