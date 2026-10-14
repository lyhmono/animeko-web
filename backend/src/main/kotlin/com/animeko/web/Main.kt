package com.animeko.web

import com.animeko.web.api.configureApiRoutes
import com.animeko.web.api.configureStaticFrontend
import com.animeko.web.bangumi.BangumiClient
import com.animeko.web.config.AppConfig
import com.animeko.web.danmaku.DandanplayClient
import com.animeko.web.http.JdkFetcher
import com.animeko.web.selector.SelectorEngine
import com.animeko.web.selector.SelectorException
import com.animeko.web.selector.SelectorRepository
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

fun main() {
    val cfg = AppConfig()
    embeddedServer(Netty, host = cfg.host, port = cfg.port) {
        module(cfg)
    }.start(wait = true)
}

fun Application.module(cfg: AppConfig = AppConfig()) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }
    install(StatusPages) {
        exception<SelectorException> { call, cause ->
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to (cause.message ?: "selector error")))
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "bad request")))
        }
        exception<Throwable> { call, cause ->
            appLog.error("未处理异常: {}", call.request.local.uri, cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to (cause.message ?: cause::class.simpleName.orEmpty())),
            )
        }
    }

    val fetcher = JdkFetcher(cfg.requestTimeoutMs)
    val bangumi = BangumiClient(fetcher, cfg.bangumiBaseUrls)
    val danmaku = DandanplayClient(fetcher, cfg.danmakuAppId, cfg.danmakuAppSecret)
    val repo = SelectorRepository(cfg.selectorsDir)
    repo.seedIfEmpty()
    val engine = SelectorEngine(fetcher)

    configureApiRoutes(cfg, bangumi, danmaku, repo, engine, fetcher)
    configureStaticFrontend(cfg)
}
