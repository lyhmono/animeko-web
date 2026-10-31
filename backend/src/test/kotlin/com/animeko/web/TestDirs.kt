package com.animeko.web

import java.nio.file.Files
import java.nio.file.Path

/**
 * 测试夹具根目录。
 *
 * 只允许两种来源：runner 显式设的 TMPDIR，否则退回 JVM 自己的临时目录
 * （java.io.tmpdir 一定存在）。**绝不写机器专属路径**（比如某个人的缓存目录）：
 * CI runner 上没有那种目录，而 Files.createTempDirectory 不会补父级，
 * 于是干净检出会在 fixture 行抛 NoSuchFileException，本地却因目录残留假绿 —— 这一版就是这么红过 9 条。
 */
internal fun testRoot(): Path = Path.of(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir"))

/** Test fixtures respect the runner's user-cache TMPDIR, and create missing parents. */
internal fun testDirectory(prefix: String): Path {
    val root = testRoot()
    Files.createDirectories(root)
    return Files.createTempDirectory(root, prefix)
}
