package com.animeko.web

import org.slf4j.LoggerFactory

/** 全局日志器（Ktor 3 的 Application.log 已不可用，统一走 SLF4J） */
val appLog = LoggerFactory.getLogger("com.animeko.web")
