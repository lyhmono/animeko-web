package com.animeko.web.api

import com.animeko.web.proxy.HlsRewriter
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.server.routing.Route

/**
 * 数据源管理与聚合订阅、数据源采集。
 *
 * 从 configureApiRoutes 里搬出来时保持原样：with(deps) 让路由体里的 cfg / bangumi /
 * repo 等名字照旧解析，所以这次拆分不改任何一行路由逻辑，只改它住在哪个文件。
 */
internal fun Route.selectorRoutes(deps: ApiDeps) {
    with(deps) {
        // ---- 数据源管理 ----
        get("/api/selector/sources") {
            call.respond(withContext(Dispatchers.IO) { repo.list() })
        }

        post("/api/selector/sources") {
            val text = call.receiveCappedText()
            try {
                val args = withContext(Dispatchers.IO) { repo.importJson(text).also { repo.save(it) } }
                call.respond(HttpStatusCode.Created, args)
            } catch (e: com.animeko.web.StorageFailure) {
                // 磁盘/权限问题不能伪装成「配置不合法」的 400，也不能回显数据目录路径。
                throw e
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "invalid config")))
            }
        }

        post("/api/selector/sources/{name}/enable") {
            val name = call.parameters["name"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val body = call.receiveCappedText()
            // 必须显式给出布尔值：早期实现把「缺字段」当 false、「解析异常」当 true，
            // 同一个空 body 会时而停用时而启用，语义不可预测。
            val enabled = runCatching {
                prettyJson.parseToJsonElement(body).jsonObject["enabled"]?.jsonPrimitive?.booleanOrNull
            }.getOrNull()
            if (enabled == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "enabled 必须是布尔值"))
            } else if (withContext(Dispatchers.IO) { repo.setEnabled(name, enabled) }) {
                call.respond(SourceEnabledResponse(name = name, enabled = enabled))
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "source not found"))
            }
        }

        delete("/api/selector/sources/{name}") {
            val name = call.parameters["name"]
            if (name != null && withContext(Dispatchers.IO) { repo.delete(name) }) {
                call.respond(mapOf("deleted" to name))
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "source not found"))
            }
        }

        // ---- 聚合订阅：先预览、再挑着导入 ----
        post("/api/selector/subscriptions/preview") {
            val form = bodyObject(call)
            val url = form?.stringField("url").orEmpty()
            val text = form?.stringField("text").orEmpty()
            if (url.isBlank() && text.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "需要提供订阅地址 url 或订阅正文 text"))
            } else {
                call.respond(
                    subscriptions.preview(
                        url = url.ifBlank { null },
                        text = text.ifBlank { null },
                        label = form?.stringField("label").orEmpty(),
                    ),
                )
            }
        }

        post("/api/selector/subscriptions/import") {
            val form = bodyObject(call)
            val names = form?.stringArrayField("names").orEmpty()
            val url = form?.stringField("url").orEmpty()
            val text = form?.stringField("text").orEmpty()
            // 预览时下发的内容指纹：对不上说明订阅在两步之间被更新过，
            // 此时按「当前内容」导入会得到一份和用户刚看过的清单不同的结果
            val fingerprint = form?.stringField("fingerprint")
            if (names.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "没有选择要导入的数据源"))
            } else if (url.isBlank() && text.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "需要提供订阅地址 url 或订阅正文 text"))
            } else {
                val result = subscriptions.importSelected(
                    url = url.ifBlank { null },
                    text = text.ifBlank { null },
                    names = names,
                    fingerprint = fingerprint,
                )
                // 导入完立刻把最新列表回给前端，省一次「导入后再拉列表」的时序问题
                call.respond(result)
            }
        }


        // ---- 数据源采集 ----
        post("/api/selector/sources/{name}/search") {
            val source = withContext(Dispatchers.IO) { repo.find(call.parameters["name"] ?: "", enabledOnly = true) }
            if (source == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "source not found"))
            } else {
                val keyword = bodyField(call, "keyword")
                if (keyword.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing keyword"))
                } else {
                    call.respond(withContext(Dispatchers.IO) { engine.search(source.arguments.searchConfig, keyword) })
                }
            }
        }

        post("/api/selector/sources/{name}/episodes") {
            val source = withContext(Dispatchers.IO) { repo.find(call.parameters["name"] ?: "", enabledOnly = true) }
            if (source == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "source not found"))
            } else {
                val subjectUrl = bodyField(call, "subjectUrl")
                if (subjectUrl.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing subjectUrl"))
                } else {
                    call.respond(withContext(Dispatchers.IO) { engine.episodes(source.arguments.searchConfig, subjectUrl) })
                }
            }
        }

        post("/api/selector/sources/{name}/video") {
            val source = withContext(Dispatchers.IO) { repo.find(call.parameters["name"] ?: "", enabledOnly = true) }
            if (source == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "source not found"))
            } else {
                val playUrl = bodyField(call, "playUrl")
                if (playUrl.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing playUrl"))
                } else {
                    val target = withContext(Dispatchers.IO) { engine.resolveVideo(source.arguments.searchConfig, playUrl) }
                    // 凭据换成服务端会话号后再交给前端：浏览器只需要一个能用的代理地址
                    val sid = sessions.put(
                        mediaUrl = target.url,
                        userAgent = target.userAgent,
                        referer = target.referer,
                        cookie = target.cookie,
                        extraCookieHosts = target.cookieHosts,
                    )
                    call.respond(
                        VideoSourceResponse(
                            url = target.url,
                            proxyPath = HlsRewriter.proxyUrl(PROXY_PATH, target.url, sid),
                        ),
                    )
                }
            }
        }


    }
}
