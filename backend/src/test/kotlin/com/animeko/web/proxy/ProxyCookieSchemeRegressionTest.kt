package com.animeko.web.proxy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProxyCookieSchemeRegressionTest {
    @Test
    fun `HTTPS session cannot grant a cookie to HTTP children including explicitly allowed hosts`() {
        val sessions = ProxySessionStore()
        val sid = sessions.put("https://media.test/master.m3u8", "UA", "https://media.test/watch", "secret=x", setOf("keys.test"))
        sessions.allowHosts(sid, setOf("keys.test"))
        for (url in listOf("http://media.test/segment.ts", "http://media.test/key.bin", "http://media.test/child.m3u8", "http://keys.test/key.bin")) {
            val grant = sessions.grant(sid, url)
            assertEquals(ProxySessionStore.Kind.GRANTED, grant.kind, "普通 HTTP 媒体仍可播放：$url")
            assertEquals("UA", grant.headers["User-Agent"])
            assertNull(grant.headers["Cookie"], "HTTPS 凭据不得被同主机子请求/显式 Cookie 主机恢复到明文 HTTP：$url")
        }
        assertEquals("secret=x", sessions.grant(sid, "https://media.test/segment.ts").headers["Cookie"])
        assertEquals("secret=x", sessions.grant(sid, "https://keys.test/key.bin").headers["Cookie"])
    }

    @Test
    fun `explicitly configured HTTP sources retain cookies on HTTP and HTTPS`() {
        val sessions = ProxySessionStore()
        val sid = sessions.put("http://media.test/master.m3u8", "UA", "http://media.test/watch", "secret=x")
        assertEquals("secret=x", sessions.grant(sid, "http://media.test/segment.ts").headers["Cookie"])
        assertEquals("secret=x", sessions.grant(sid, "https://media.test/segment.ts").headers["Cookie"])
    }
}
