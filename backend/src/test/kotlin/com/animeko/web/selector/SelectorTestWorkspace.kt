package com.animeko.web.selector

import com.animeko.web.http.Fetcher
import com.animeko.web.http.UrlGuard
import com.animeko.web.testDirectory
import java.nio.file.Files
import java.nio.file.Path

/** 夹具根可以还不存在：父级必须补出来，否则干净检出的 CI 直接 NoSuchFileException。 */
internal fun newFixtureDirectory(root: Path, prefix: String): Path {
    Files.createDirectories(root)
    return Files.createTempDirectory(root, prefix)
}

/**
 * 测试夹具目录。
 *
 * 这里**不能**写任何机器专属路径：CI runner 上没有某个人的缓存目录，
 * 而 `Files.createTempDirectory` 不会替调用方建缺失的父级 ——
 * 干净检出会在 fixture 行抛 NoSuchFileException，本地却因目录残留假绿。
 */
internal fun selectorTempDir(prefix: String): Path = testDirectory(prefix)

/** Keep fixture DNS validation synchronous so real IO cannot auto-advance a virtual deadline. */
internal fun selectorTestEngine(
    fetcher: Fetcher,
    allowPrivateTargets: Boolean = false,
    monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
): SelectorEngine = SelectorEngine(fetcher, allowPrivateTargets, monotonicMillis) { url, allowPrivate ->
    UrlGuard.requireAllowed(url, allowPrivate)
}
