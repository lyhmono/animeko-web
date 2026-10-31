package com.animeko.web.api

import com.animeko.web.config.AppConfig
import com.animeko.web.module
import com.animeko.web.testDirectory
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaticFrontendRegressionTest {
    private fun config(root: Path, frontend: String) = AppConfig(
        authEnabled = false, dataDir = root.resolve("data"), frontendDir = frontend,
    )

    @Test
    fun `relative dot dot frontend configuration serves assets and SPA routes consistently`() = testApplication {
        val root = testDirectory("animeko-static-relative")
        val dist = Files.createDirectories(root.resolve("frontend/dist"))
        Files.createDirectories(root.resolve("backend"))
        Files.createDirectories(dist.resolve("assets"))
        Files.writeString(dist.resolve("index.html"), "<html>SPA marker</html>")
        Files.writeString(dist.resolve("assets/app-123.js"), "console.log('asset marker')")
        Files.writeString(dist.resolve("assets/app-123.css"), "body{color:red}")
        val unnormalized = Path.of("").toAbsolutePath().relativize(root.resolve("backend/../frontend/dist")).toString()
        assertTrue(".." in unnormalized)
        application { module(config(root, unnormalized)) }
        for (path in listOf("/", "/index.html", "/subject/123", "/source/Anime1.me")) {
            val response = client.get(path)
            assertEquals(HttpStatusCode.OK, response.status, path)
            assertTrue("SPA marker" in response.bodyAsText(), path)
            assertEquals("no-cache", response.headers[HttpHeaders.CacheControl])
        }
        for ((path, marker) in listOf("/assets/app-123.js" to "asset marker", "/assets/app-123.css" to "color:red")) {
            val response = client.get(path)
            assertEquals(HttpStatusCode.OK, response.status, path)
            assertTrue(marker in response.bodyAsText(), path)
            assertTrue("immutable" in response.headers[HttpHeaders.CacheControl].orEmpty())
        }
        assertEquals(HttpStatusCode.NotFound, client.get("/assets/missing.js").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/missing").status)
    }

    @Test
    fun `asset symlinks cannot expose a sibling private file`() = testApplication {
        val root = testDirectory("animeko-static-symlink")
        val dist = Files.createDirectories(root.resolve("frontend"))
        Files.createDirectories(dist.resolve("assets"))
        Files.writeString(dist.resolve("index.html"), "<html>safe</html>")
        Files.writeString(dist.resolve("assets/app.js"), "console.log('safe')")
        val secret = root.resolve("secret.js")
        Files.writeString(secret, "private sibling marker")
        Files.createSymbolicLink(dist.resolve("assets/leak.js"), secret)
        application { module(config(root, dist.toString())) }
        assertEquals(HttpStatusCode.OK, client.get("/assets/app.js").status)
        val leak = client.get("/assets/leak.js")
        assertEquals(HttpStatusCode.NotFound, leak.status)
        assertFalse("private sibling marker" in leak.bodyAsText())
    }

    @Test
    fun `SPA index symlink outside the root is refused on root and deep links`() = testApplication {
        val root = testDirectory("animeko-static-index-symlink")
        val dist = Files.createDirectories(root.resolve("frontend"))
        val secret = root.resolve("private.html")
        Files.writeString(secret, "private index marker")
        Files.createSymbolicLink(dist.resolve("index.html"), secret)
        application { module(config(root, dist.toString())) }
        for (path in listOf("/", "/index.html", "/subject/123")) {
            val response = client.get(path)
            assertEquals(HttpStatusCode.NotFound, response.status, path)
            assertFalse("private index marker" in response.bodyAsText(), path)
        }
    }

    @Test
    fun `decoded traversal stays refused while an internal normalized path is served without immutable caching`() = testApplication {
        val root = testDirectory("animeko-static-traversal")
        val dist = Files.createDirectories(root.resolve("frontend"))
        Files.createDirectories(dist.resolve("assets"))
        Files.writeString(dist.resolve("index.html"), "<html>safe</html>")
        Files.writeString(root.resolve("SECRET.txt"), "private traversal marker")
        application { module(config(root, dist.toString())) }
        for (segments in listOf(listOf("%2e%2e", "SECRET.txt"), listOf("assets", "%2e%2e", "%2e%2e", "SECRET.txt"), listOf("%2e%2e%2fSECRET.txt"))) {
            val response = client.get { url { encodedPathSegments = segments } }
            assertEquals(HttpStatusCode.NotFound, response.status, segments.toString())
            assertFalse("private traversal marker" in response.bodyAsText())
        }
        val internal = client.get { url { encodedPathSegments = listOf("assets", "%2e%2e", "index.html") } }
        assertEquals(HttpStatusCode.OK, internal.status)
        assertEquals("no-cache", internal.headers[HttpHeaders.CacheControl])
        assertTrue("safe" in internal.bodyAsText())
    }
}
