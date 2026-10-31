package com.animeko.web

import java.nio.file.Files
import java.nio.file.Path

/** Test fixtures respect the runner's user-cache TMPDIR. */
internal fun testDirectory(prefix: String): Path = Files.createTempDirectory(
    Path.of(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir")), prefix,
)
