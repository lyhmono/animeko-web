package com.animeko.web

import java.util.concurrent.atomic.AtomicLong

/**
 * 进程级运行指标：只计数，不记录任何请求内容。
 * 搜索词、收藏、媒体地址、数据源配置都不进这个对象 —— 它能回答的是
 * 「这台服务被用了多少、代理流量占多少」，而不是「谁在看什么」。
 */
object Metrics {
    private val startedAtMs = System.currentTimeMillis()
    val requests = AtomicLong()
    val proxyRequests = AtomicLong()

    fun snapshot(): Map<String, Long> = mapOf(
        "requests" to requests.get(),
        "proxyRequests" to proxyRequests.get(),
        "uptimeSeconds" to (System.currentTimeMillis() - startedAtMs) / 1000,
    )
}