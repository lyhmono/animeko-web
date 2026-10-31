package com.animeko.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.animeko.web.config.AppConfig
import io.ktor.client.request.get
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertFalse

class MainCancellationStatusTest {
    @Test
    fun `StatusPages does not log explicit coroutine cancellation as an application failure`() {
        val logger = appLog as Logger
        val events = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(events)
        try {
            testApplication {
                application {
                    module(AppConfig(
                        authEnabled = false, frontendDir = "missing", dataDir = testDirectory("animeko-status-cancel"),
                        subscriptionAutoImport = emptyList(), seedSubscriptionUrl = null,
                    ))
                    routing { get("/fixture-cancel") { throw CancellationException("intentional cancellation fixture") } }
                }
                // The engine may terminate a cancelled call without an HTTP response. Its final transport
                // behavior is not the contract under test: our application must not classify it as 500/502.
                runCatching { client.get("/fixture-cancel") }
                assertFalse(events.list.any { it.formattedMessage.startsWith("未处理异常") || it.formattedMessage.startsWith("上游失败") },
                    events.list.joinToString("\n") { it.formattedMessage })
            }
        } finally { logger.detachAppender(events); events.stop() }
    }
}
