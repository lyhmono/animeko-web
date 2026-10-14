package com.animeko.web.config

import java.nio.file.Path
import java.nio.file.Paths

/**
 * 全部通过环境变量配置，便于 Docker 部署。默认值面向内网自用（无鉴权）。
 */
data class AppConfig(
    val host: String = env("ANIMEKO_HOST", "0.0.0.0"),
    val port: Int = env("ANIMEKO_PORT", "8080").toIntOrNull() ?: 8080,
    /** 数据目录：数据源配置、缓存等持久化内容 */
    val dataDir: Path = Paths.get(env("ANIMEKO_DATA_DIR", "./data")),
    /** 前端构建产物目录（Docker 镜像内为 /app/frontend） */
    val frontendDir: String = env("ANIMEKO_FRONTEND_DIR", "frontend/dist"),

    // ---- 访问鉴权（默认关闭；公网暴露时建议开启）----
    val authEnabled: Boolean = env("ANIMEKO_AUTH_ENABLED", "false").toBooleanStrictOrNull() ?: false,
    /** Bearer Token 鉴权 */
    val authToken: String? = envOrNull("ANIMEKO_AUTH_TOKEN"),
    /** Basic 鉴权 */
    val authUser: String? = envOrNull("ANIMEKO_AUTH_USER"),
    val authPassword: String? = envOrNull("ANIMEKO_AUTH_PASSWORD"),

    // ---- 第三方服务 ----
    /**
     * Bangumi API base URL 列表（逗号分隔，按顺序故障转移）。
     * 官方 api.bgm.tv 在部分地区不可达，默认附带社区镜像。
     */
    val bangumiBaseUrls: List<String> =
        env("ANIMEKO_BANGUMI_BASE_URLS", "").split(',').map { it.trim().removeSuffix("/") }
            .filter { it.isNotBlank() }
            .ifEmpty {
                System.getenv("ANIMEKO_BANGUMI_BASE_URL")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { listOf(it.trim().removeSuffix("/")) }
                    ?: listOf("https://api.bgm.tv", "https://bgmapi.anibt.net")
            },
    /** 弹弹play 开放平台凭证，在 https://dev.dandanplay.com 申请 */
    val danmakuAppId: String = env("ANIMEKO_DANMAKU_APP_ID", ""),
    val danmakuAppSecret: String = env("ANIMEKO_DANMAKU_APP_SECRET", ""),

    val requestTimeoutMs: Long = env("ANIMEKO_REQUEST_TIMEOUT_MS", "20000").toLongOrNull() ?: 20_000,
    val proxyTimeoutMs: Long = env("ANIMEKO_PROXY_TIMEOUT_MS", "120000").toLongOrNull() ?: 120_000,
) {
    val selectorsDir: Path get() = dataDir.resolve("selectors")
    val danmakuConfigured: Boolean get() = danmakuAppId.isNotBlank() && danmakuAppSecret.isNotBlank()

    companion object {
        fun env(key: String, default: String): String =
            System.getenv(key)?.takeIf { it.isNotBlank() } ?: default

        fun envOrNull(key: String): String? = System.getenv(key)?.takeIf { it.isNotBlank() }
    }
}
