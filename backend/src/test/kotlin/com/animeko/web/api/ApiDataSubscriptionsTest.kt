package com.animeko.web.api

import com.animeko.web.config.AppConfig
import com.animeko.web.module
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiDataSubscriptionsTest {

    private fun config(authEnabled: Boolean = false) = AppConfig(
        authEnabled = authEnabled,
        authUser = if (authEnabled) "me" else null,
        authPassword = if (authEnabled) "pw" else null,
        dataDir = Files.createTempDirectory("animeko-api"),
        frontendDir = "definitely-not-exist",
    )

    private val basic = "Basic " + java.util.Base64.getEncoder().encodeToString("me:pw".toByteArray())

    private suspend fun jsonOf(resp: HttpResponse) =
        Json.parseToJsonElement(resp.bodyAsText()).jsonObject

    /** /api/selector/sources 返回的是数组，用它取名字列表 */
    private suspend fun namesOf(resp: HttpResponse): List<String> =
        Json.parseToJsonElement(resp.bodyAsText()).jsonArray
            .map { it.jsonObject["arguments"]!!.jsonObject["name"]!!.jsonPrimitive.content }

    /**
     * 请求体里的订阅正文。用 buildJsonObject 而不是拼字符串：
     * 正文自带大量引号，手工转义很容易写出一条「测试通过但其实请求体是坏的」的用例。
     */
    private fun subscriptionBody(vararg names: String): String = buildJsonObject {
        put("text", subscription)
        // 勾选的名字都是纯文本，可以直接拼；订阅正文那种大段 JSON 才需要 buildJsonObject 转义
        if (names.isNotEmpty()) put("names", Json.parseToJsonElement(names.joinToString(",", "[", "]") { "\"$it\"" }))
    }.toString()

    private val subscription = """
        {"exportedMediaSourceDataList":{"mediaSources":[
          {"factoryId":"web-selector","version":2,"arguments":{"name":"订阅甲","searchConfig":{"searchUrl":"https://a.test/s?wd={keyword}","rawBaseUrl":"https://a.test"}}},
          {"factoryId":"web-selector","version":2,"arguments":{"name":"订阅乙","tier":3,"searchConfig":{"searchUrl":"https://b.test/s?wd={keyword}","rawBaseUrl":"https://b.test"}}},
          {"factoryId":"web-selector","version":2,"arguments":{"name":"没有搜索地址的"}}
        ]}}
    """.trimIndent()

    // ---- 个人数据 ----

    @Test
    fun `user data round trips through the server`() = testApplication {
        application { module(config()) }
        assertEquals(emptyMap(), jsonOf(client.get("/api/data")))
        val put = client.put("/api/data") {
            setBody("""{"favorites":[{"id":41,"title":"孤独摇滚"}],"progress":{"41:3":12.5}}""")
        }
        assertEquals(HttpStatusCode.OK, put.status, put.bodyAsText())
        val back = jsonOf(client.get("/api/data"))
        assertEquals("孤独摇滚", back["favorites"]!!.jsonArray[0].jsonObject["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun `user data must be a json object`() = testApplication {
        application { module(config()) }
        for (bad in listOf("""[1,2]""", "not json", "null")) {
            val resp = client.put("/api/data") { setBody(bad) }
            assertEquals(HttpStatusCode.BadRequest, resp.status, "$bad -> ${resp.status}")
        }
        // 被拒的写入不能污染已有数据
        assertEquals(emptyMap(), jsonOf(client.get("/api/data")))
    }

    @Test
    fun `user data is behind the login when auth is on`() = testApplication {
        application { module(config(authEnabled = true)) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/data").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.put("/api/data") { setBody("""{"a":1}""") }.status,
        )
        val withAuth = client.get("/api/data") { header(HttpHeaders.Authorization, basic) }
        assertEquals(HttpStatusCode.OK, withAuth.status)
    }

    @Test
    fun `cross site write to user data is refused`() = testApplication {
        application { module(config()) }
        // 会话 Cookie 会自动跟着同源请求走，所以这一层不能省：
        // 恶意页面借用户浏览器发 POST，就能覆写收藏/历史
        val evil = client.put("/api/data") {
            setBody("""{"favorites":[]}""")
            header(HttpHeaders.Origin, "https://attacker.example")
        }
        assertEquals(HttpStatusCode.Forbidden, evil.status)
    }

    // ---- 聚合订阅 ----

    /**
     * 请求体字段写成对象/数组而不是字符串（`{"url":{"a":1}}`）时，
     * 取字段的 `jsonPrimitive` 会抛异常。那类异常原先落在 bodyObject 的 try 之外，
     * 于是变成一个 500：客户端一个笔误就能打出服务端栈，而正确的回答是 400。
     */
    /**
     * import 的 `names` 是数组字段，取法与 stringField 同一类坑：
     * `jsonArray` 对对象是强转、`jsonPrimitive` 对对象/数组也是强转，
     * 两者抛出的都是 bodyObject 的 try 之外的异常 → 一个笔误的请求体打成 500。
     */
    @Test
    fun `import rejects a mis-shaped names field instead of throwing 500`() = testApplication {
        application { module(config()) }
        for (names in listOf("""{"a":1}""", """[1,2]""", """["ok",{"bad":1}]""", """null""", """"string"""")) {
            val resp = client.post("/api/selector/subscriptions/import") {
                setBody("""{"names":$names,"text":${Json.encodeToString(subscription)}}""")
            }
            assertTrue(
                resp.status == HttpStatusCode.BadRequest,
                "names=$names → ${resp.status} ${resp.bodyAsText().take(100)}（应为 400）",
            )
        }
    }

    @Test
    fun `a request field shaped like an object is a bad request not a server error`() = testApplication {
        application { module(config()) }
        for (body in listOf(
            """{"url":{"a":1}}""",
            """{"url":[1,2]}""",
            """{"url":null}""",
            """{"text":123}""",
            """{"url":true}""",
        )) {
            val resp = client.post("/api/selector/subscriptions/preview") {
                setBody(body)
            }
            assertEquals(
                HttpStatusCode.BadRequest,
                resp.status,
                "$body → ${resp.status} ${resp.bodyAsText().take(120)}",
            )
        }
    }

    @Test
    fun `subscription preview lists candidates and explains dropped entries`() = testApplication {
        application { module(config()) }
        val resp = client.post("/api/selector/subscriptions/preview") {
            setBody(subscriptionBody())
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val body = jsonOf(resp)
        val names = body["candidates"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("订阅甲", "订阅乙"), names, "没有 searchUrl 的那条要落到 rejected")
        val rejected = body["rejected"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(1, rejected.size, rejected.toString())
        assertTrue(rejected[0].contains("没有搜索地址的"), rejected.toString())
        val tiers = body["candidates"]!!.jsonArray.associate {
            it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["tier"]!!.jsonPrimitive.content
        }
        assertEquals("3", tiers["订阅乙"], "tier 是数字也要能显示")
        assertEquals("a.test", body["candidates"]!!.jsonArray[0].jsonObject["site"]!!.jsonPrimitive.content)
    }

    @Test
    fun `only selected subscription sources are imported`() = testApplication {
        application { module(config()) }
        val resp = client.post("/api/selector/subscriptions/import") {
            setBody(subscriptionBody("订阅乙"))
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val body = jsonOf(resp)
        assertEquals(listOf("订阅乙"), body["added"]!!.jsonArray.map { it.jsonPrimitive.content })

        val names = namesOf(client.get("/api/selector/sources"))
        // 首次运行会播种两个示例源，所以这里只能断言「勾选的进来了、没勾的没进来」
        assertTrue("订阅乙" in names, names.toString())
        assertFalse("订阅甲" in names, "没勾选的源不能顺手导入：$names")
        assertFalse(names.any { it.contains("没有搜索地址的") }, names.toString())
    }

    @Test
    fun `import without a selection is refused`() = testApplication {
        application { module(config()) }
        val resp = client.post("/api/selector/subscriptions/import") {
            setBody(subscriptionBody())
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(jsonOf(resp)["error"]!!.jsonPrimitive.content.contains("没有选择"))
    }

    @Test
    fun `preview without url or text is refused`() = testApplication {
        application { module(config()) }
        assertEquals(
            HttpStatusCode.BadRequest,
            client.post("/api/selector/subscriptions/preview") { setBody("""{"url":"  "}""") }.status,
        )
    }

    @Test
    fun `subscription url pointing at the intranet is blocked`() = testApplication {
        // 订阅地址由用户填、服务端去取，属于 SSRF 面：内网/元数据地址必须拦
        application { module(config()) }
        for (url in listOf("http://127.0.0.1:8080/api/health", "http://169.254.169.254/latest/meta-data/")) {
            val resp = client.post("/api/selector/subscriptions/preview") {
                setBody("""{"url":"$url"}""")
            }
            assertEquals(HttpStatusCode.Forbidden, resp.status, "$url -> ${resp.status}")
        }
    }

    @Test
    fun `subscription endpoints sit behind the login too`() = testApplication {
        application { module(config(authEnabled = true)) }
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/api/selector/subscriptions/preview") {
                setBody("""{"text":"{}"}""")
            }.status,
        )
    }
}
