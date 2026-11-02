package com.animeko.web.api

import com.animeko.web.appLog
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.Route

/**
 * 登录 / 注销。
 *
 * 从 configureApiRoutes 里搬出来时保持原样：with(deps) 让路由体里的 cfg / bangumi /
 * repo 等名字照旧解析，所以这次拆分不改任何一行路由逻辑，只改它住在哪个文件。
 */
internal fun Route.sessionRoutes(deps: ApiDeps) {
    with(deps) {
        // ---- 登录 / 注销 ----
        post(LOGIN_PATH) {
            // CSRF 检查在根 intercept 里，登录接口也在它的覆盖范围内（只豁免鉴权，不豁免 CSRF）
            val clientKey = call.rateLimitKey(cfg)
            if (!cfg.authEnabled) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to "服务端未配置登录凭据，无需登录"))
                return@post
            }
            // 随机会话已经通过验证，既无需重新验口令，也不受口令桶连坐。
            if (call.hasValidSession(site, cfg) || call.validBearerSession(site) != null) {
                call.respond(SessionStateResponse(authenticated = true, user = cfg.authUser ?: "token"))
                return@post
            }
            // 先原子取得许可再读表单/验密。超限时所有候选都走同一个答复，
            // 正确口令不能作为绕过限速的判定入口；可信代理配置解决共享来源误锁。
            if (!limiter.tryAcquire(clientKey)) {
                call.response.header(HttpHeaders.RetryAfter, limiter.retryAfterSeconds(clientKey).toString())
                call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "凭据校验次数过多，请稍后再试"))
                return@post
            }
            // 请求体只能读一次：分两次 bodyField(...) 会让第二个字段永远为空，
            // 表现是「口令明明对了却提示请填写口令」。
            val form = bodyObject(call, MAX_LOGIN_BODY_BYTES)
            val username = form?.stringField("username").orEmpty()
            val password = form?.stringField("password").orEmpty()
            when {
                form == null || password.isEmpty() -> call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "请填写口令"),
                )
                !credentialVerifier.password(username, password) -> {
                    appLog.warn("登录失败: user={} ip={} 累计={}", username.take(40), clientKey, limiter.attempts(clientKey))
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "账号或口令不正确"))
                }
                else -> {
                    val token = site.create()
                    call.response.setSessionCookie(cfg, token, site.ttlSeconds())
                    call.respond(
                        SessionStateResponse(
                            authenticated = true,
                            // 只回显账号名，绝不含口令或 token
                            user = cfg.authUser ?: "token",
                        ),
                    )
                }
            }
        }

        post(LOGOUT_PATH) {
            // 两个都作废：它们是同一台机器签发的同一份会话的两种写法
            call.sessionTokens().forEach { site.invalidate(it) }
            call.attributes.getOrNull(VERIFIED_BEARER_SESSION)?.let(site::invalidate)
            call.response.clearSessionCookie(cfg)
            call.respond(SessionStateResponse(authenticated = false))
        }


    }
}
