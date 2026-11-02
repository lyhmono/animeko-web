package com.animeko.web.api

import com.animeko.web.data.UserDataStore
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import io.ktor.server.routing.Route

/**
 * 个人数据。
 *
 * 从 configureApiRoutes 里搬出来时保持原样：with(deps) 让路由体里的 cfg / bangumi /
 * repo 等名字照旧解析，所以这次拆分不改任何一行路由逻辑，只改它住在哪个文件。
 */
internal fun Route.dataRoutes(deps: ApiDeps) {
    with(deps) {
        // ---- 个人数据（收藏 / 历史 / 进度 / 搜索记录）----
        get("/api/data") {
            val snapshot = withContext(Dispatchers.IO) { userData.read() }
            call.response.header(HttpHeaders.ETag, "\"${snapshot.etag}\"")
            // 个人数据（收藏/历史/进度）绝不能进任何缓存：这份响应的内容随「谁在问」而变，
            // 而 HTTP 里没有 Vary 能表达按会话用户区分。浏览器磁盘缓存一旦存下上一份，
            // 换账号（或注销后用另一个身份登录）后可能出现「看到别人的收藏」，
            // 甚至关掉网络还在放旧进度。SPA 本来就把它读进内存了，缓存这一份毫无收益。
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondText(snapshot.text, ContentType.Application.Json)
        }

        put("/api/data") {
            val text = call.receiveCappedText()
            // 客户端带上它读到的版本时才做条件写；不带（curl、旧版前端）就退回无条件覆盖
            // 原样交给数据层解释：If-Match 可以是 *、可以是逗号分隔的多个标签、
            // 也可以带 W/ 前缀。在这里 trim('"') 会把它们压成一个奇怪的字符串，
            // 于是「*」和「多值里含正确指纹」这两种合法写法都恒定 409
            val ifMatch = call.request.headers[HttpHeaders.IfMatch]?.trim()?.takeIf { it.isNotEmpty() }
            try {
                val snapshot = withContext(Dispatchers.IO) { userData.write(text, ifMatch) }
                call.response.header(HttpHeaders.ETag, "\"${snapshot.etag}\"")
                // 回字节数：前端可以据此提示「数据快满了」，而不是只有一句失败
                call.respond(DataSavedResponse(bytes = text.toByteArray(Charsets.UTF_8).size.toLong()))
            } catch (stale: UserDataStore.Stale) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to "DATA_STALE"))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "个人数据格式不正确")))
            }
        }


    }
}
