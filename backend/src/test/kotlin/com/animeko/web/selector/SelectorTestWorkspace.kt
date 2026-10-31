package com.animeko.web.selector

import com.animeko.web.http.Fetcher
import com.animeko.web.http.UrlGuard
import java.nio.file.Files
import java.nio.file.Path

internal fun selectorTempDir(prefix: String): Path = Files.createTempDirectory(
    Path.of(System.getenv("TMPDIR") ?: "${System.getProperty("user.home")}/.hermes/cache/scratch"),
    prefix,
)

/** Keep fixture DNS validation synchronous so real IO cannot auto-advance a virtual deadline. */
internal fun selectorTestEngine(
    fetcher: Fetcher,
    allowPrivateTargets: Boolean = false,
    monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
): SelectorEngine = SelectorEngine(fetcher, allowPrivateTargets, monotonicMillis) { url, allowPrivate ->
    UrlGuard.requireAllowed(url, allowPrivate)
}
