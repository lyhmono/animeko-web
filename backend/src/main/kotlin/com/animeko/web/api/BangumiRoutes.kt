package com.animeko.web.api

import com.animeko.web.bangumi.BangumiClient
import com.animeko.web.bangumi.BgmChannelResponse
import com.animeko.web.selector.SelectorEngine
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import io.ktor.server.routing.Route

/**
 * Bangumi 元数据与通道。
 *
 * 从 configureApiRoutes 里搬出来时保持原样：with(deps) 让路由体里的 cfg / bangumi /
 * repo 等名字照旧解析，所以这次拆分不改任何一行路由逻辑，只改它住在哪个文件。
 */
internal fun Route.bangumiRoutes(deps: ApiDeps) {
    with(deps) {
        // ---- Bangumi 元数据 ----
        //
        // 这一段（以及下面的数据源采集）每个 bangumi.* / engine.* 调用都必须包在
        // withContext(Dispatchers.IO) 里，理由不是风格：
        //   - BangumiClient.resolveBases 在 CUSTOM 通道下对每个地址调
        //     UrlGuard.requireAllowed，它内部是 InetAddress.getAllByName —— 阻塞式 DNS，
        //     慢解析能挂住数秒；
        //   - SelectorEngine 用 Jsoup.parse 解析不可信远端页面并跑用户配置的正则，是 CPU 密集。
        // 两者都不切调度器时就跑在 Netty 事件循环线程上（每核一条），卡住的是**整个 loop
        // 上的所有连接**，包括 HLS 代理的媒体流。同文件的 repo.* / userData.* 一直是这么包的，
        // 新增路由请照做。
        get("/api/bangumi/search") {
            val q = call.request.queryParameters["q"]?.trim().orEmpty()
            if (q.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing q"))
            } else {
                call.respond(withContext(Dispatchers.IO) { bangumi.search(q) })
            }
        }

        // 每日放送表（首页用）。镜像不支持该端点时会 502 —— 前端把它当
        // 可选项：拉不到就整块隐藏，不影响页面其他部分。
        get("/api/bangumi/calendar") {
            call.respond(withContext(Dispatchers.IO) { bangumi.calendar() })
        }

        // 排行榜（发现页用）。limit/offset 直接来自查询串，必须夹紧：
        // 上游 limit 上限 50，而 offset 不设界等于允许任何人让服务器去翻一万页。
        get("/api/bangumi/ranking") {
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 24).coerceIn(1, 50)
            val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceIn(0, 5000)
            call.respond(withContext(Dispatchers.IO) { bangumi.ranking(limit = limit, offset = offset) })
        }

        // ---- Bangumi 通道（设置页：官方/镜像/自定义/自动）----
        get("/api/bangumi/channel") {
            val st = bangumi.channelState()
            call.respond(
                BgmChannelResponse(
                    channel = st.channel,
                    customUrls = st.customUrls,
                    effectiveBases = bangumi.effectiveBases(),
                    calendarSupported = bangumi.calendarSupported,
                ),
            )
        }

        // 保存通道选择。写接口天然被根 intercept 的 CSRF 检查覆盖，
        // 鉴权开启时也要求凭据 —— 只有站长能改全局上游。
        put("/api/bangumi/channel") {
            val form = bodyObject(call)
            val raw = form?.stringField("channel")?.trim()?.uppercase()
            val channel = when (raw) {
                null -> throw IllegalArgumentException("缺少 channel（AUTO/OFFICIAL/MIRROR/CUSTOM）")
                "AUTO" -> com.animeko.web.bangumi.BgmChannel.AUTO
                "OFFICIAL" -> com.animeko.web.bangumi.BgmChannel.OFFICIAL
                "MIRROR" -> com.animeko.web.bangumi.BgmChannel.MIRROR
                "CUSTOM" -> com.animeko.web.bangumi.BgmChannel.CUSTOM
                else -> throw IllegalArgumentException("无法识别的 channel「$raw」，只接受 AUTO/OFFICIAL/MIRROR/CUSTOM")
            }
            val custom = form.stringArrayField("customUrls")
            val saved = withContext(Dispatchers.IO) { bangumi.setChannel(com.animeko.web.bangumi.BgmChannelConfig(channel, custom)) }
            call.respond(
                BgmChannelResponse(
                    channel = saved.channel,
                    customUrls = saved.customUrls,
                    effectiveBases = bangumi.effectiveBases(),
                    calendarSupported = bangumi.calendarSupported,
                ),
            )
        }

        // 探测候选地址的连通性，让站长在保存前就看见「这个镜像不支持每日放送」。
        // body 可带目标通道（探测未保存的选项）；不带则探测当前已保存的通道。
        post("/api/bangumi/channel/probe") {
            // 先取许可再读 body：被限流的请求连解析正文的开销都不该付。
            // 这个接口在默认部署（不配凭据 = 无鉴权）下人人可发，而每个目标地址都要做
            // 一次阻塞 DNS 加一次带上限的 HTTP 探测 —— 不限速它就是一份现成的放大器。
            val probeKey = call.rateLimitKey(deps.cfg)
            if (!deps.probeLimiter.tryAcquire(probeKey)) {
                call.response.header(
                    HttpHeaders.RetryAfter,
                    deps.probeLimiter.retryAfterSeconds(probeKey).toString(),
                )
                call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "探测过于频繁，请稍后再试"))
                return@post
            }
            val text = call.receiveCappedText()
            // 请求体只能读一次（Ktor 的 receiveChannel 不可重放），这里必须复用
            // 已读到的 text，不能再调 bodyObject —— 那会二次 receive 拿到空内容。
            // 解析失败统一转成 IllegalArgumentException（→ 400），
            // 否则 SerializationException 会落到 Throwable 兜底变成 500。
            val target = if (text.isBlank()) {
                null
            } else {
                val form = runCatching { prettyJson.parseToJsonElement(text).jsonObject }.getOrNull()
                    ?: throw IllegalArgumentException("探测请求必须是 JSON 对象")
                form.stringField("channel")?.trim()?.uppercase()?.let { r ->
                    val ch = when (r) {
                        "AUTO" -> com.animeko.web.bangumi.BgmChannel.AUTO
                        "OFFICIAL" -> com.animeko.web.bangumi.BgmChannel.OFFICIAL
                        "MIRROR" -> com.animeko.web.bangumi.BgmChannel.MIRROR
                        "CUSTOM" -> com.animeko.web.bangumi.BgmChannel.CUSTOM
                        else -> throw IllegalArgumentException("无法识别的 channel「$r」")
                    }
                    com.animeko.web.bangumi.BgmChannelConfig(ch, form.stringArrayField("customUrls"))
                }
            }
            call.respond(withContext(Dispatchers.IO) { bangumi.probe(target) })
        }

        get("/api/bangumi/subjects/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid id"))
            } else {
                call.respond(withContext(Dispatchers.IO) { bangumi.subject(id) })
            }
        }


    }
}
