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

    // ---- 访问鉴权 ----
    /** Bearer Token 鉴权（也可以只把它当登录页的口令用） */
    val authToken: String? = envOrNull("ANIMEKO_AUTH_TOKEN"),
    /** Basic 鉴权 / 登录页账号 */
    val authUser: String? = envOrNull("ANIMEKO_AUTH_USER"),
    val authPassword: String? = envOrNull("ANIMEKO_AUTH_PASSWORD"),
    /**
     * 是否启用鉴权。默认跟随凭据：配了 ANIMEKO_AUTH_TOKEN 或账号口令就自动开启，
     * 这样「登录后才能用」是开箱行为，而不是要额外记一个开关。
     * 显式写 ANIMEKO_AUTH_ENABLED=false 可以关掉（例如已有前置反代鉴权）。
     */
    val authEnabled: Boolean = env("ANIMEKO_AUTH_ENABLED", "").toBooleanStrictOrNull()
        ?: (authToken != null || (authUser != null && authPassword != null)),
    /**
     * 会话 Cookie 是否加 Secure。默认 false：本机 8080 或 SSH 隧道都是 http，
     * 加了 Secure 浏览器会直接丢弃 Set-Cookie，表现为「登录页反复回弹」。
     * 反代配好 HTTPS 之后请设为 true。
     */
    val cookieSecure: Boolean =
        env("ANIMEKO_COOKIE_SECURE", "false").toBooleanStrictOrNull() ?: false,

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
    val requestTimeoutMs: Long = env("ANIMEKO_REQUEST_TIMEOUT_MS", "20000").toLongOrNull() ?: 20_000,
    /**
     * Bangumi 元数据请求的超时，单独配。社区镜像（bgmapi.anibt.net）实测要
     * 13~25 秒才能返回一次搜索，而抓取用的 20 秒上限会把它正好掐死 ——
     * 表现是「搜索页永远没有 Bangumi 结果」，而镜像手动 curl 又是通的。
     */
    val bangumiTimeoutMs: Long = env("ANIMEKO_BANGUMI_TIMEOUT_MS", "45000").toLongOrNull() ?: 45_000,
    val proxyTimeoutMs: Long = env("ANIMEKO_PROXY_TIMEOUT_MS", "120000").toLongOrNull() ?: 120_000,

    /**
     * 是否允许代理/抓取访问内网与保留地址。默认 false：
     * 服务默认绑定 0.0.0.0 且鉴权默认关闭，放开内网等于给同网段一台任意端口的跳板。
     * 只有当你确实要代理局域网内的 NAS/源站时才设为 true。
     */
    val allowPrivateTargets: Boolean =
        env("ANIMEKO_ALLOW_PRIVATE_TARGETS", "false").toBooleanStrictOrNull() ?: false,

    /**
     * 开机自动导入的聚合订阅地址（逗号分隔可写多条），例如
     * ANIMEKO_SUBSCRIPTION_AUTO_IMPORT=https://sub.creamycake.org/v1/css1.json
     * 空则不自动导入（仍是手动在界面里挑）。自动导入只影响「同名源被覆盖」，
     * 所以改过配置的自建源不要和订阅重名。
     */
    val subscriptionAutoImport: List<String> =
        env("ANIMEKO_SUBSCRIPTION_AUTO_IMPORT", "").split(',').map { it.trim() }.filter { it.isNotBlank() },

    /**
     * 自动导入时的分级白名单（逗号分隔，如 `0,1`）。订阅里 tier 越低质量越好，
     * 而境外 VPS 上高 tier 常常是 JS 加密或只对国内 IP 开放的 CDN，导进来只会拖慢搜索。
     * 留空 = 全导。界面里的勾选导入不受此项限制。
     */
    val subscriptionAutoTiers: Set<String> =
        env("ANIMEKO_SUBSCRIPTION_AUTO_TIERS", "").split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet(),

    /**
     * 是否放开「写接口只允许同源」的 CSRF 检查。默认 false。
     * 只有在反向代理改写了 Host、导致同源判定误报时才需要设为 true，
     * 那种情况下应当自己确保服务不对不可信网络开放。
     */
    val allowCrossOrigin: Boolean =
        env("ANIMEKO_ALLOW_CROSS_ORIGIN", "false").toBooleanStrictOrNull() ?: false,

    /**
     * 服务前面有几层「由你控制」的反代（nginx / Caddy / CDN）。默认 0 = 直连部署：
     * X-Forwarded-For / X-Forwarded-Host 一律不信任（客户端可任意伪造），
     * 限速按 remoteHost 记账、同源判定比对 Host 头。
     *
     * 设为 N 之后：限速取 XFF 从右数第 N 个地址（真实客户端），同源判定改看
     * X-Forwarded-Host（浏览器看到的原始主机）。**必须如实填写层数**：填少了
     * 会把代理自己的 IP 当成客户端（限速全员共享），填多了会把客户端伪造的
     * XFF 值当成真实地址（限速可被绕）。
     */
    val trustedProxyHops: Int =
        env("ANIMEKO_TRUSTED_PROXY_HOPS", "0").toIntOrNull()?.coerceIn(0, 8) ?: 0,

    /**
     * `ANIMEKO_SUBSCRIPTION_AUTO_IMPORT` 的别名：等价于往那个列表里追加一个地址。
     *
     * 这里曾经并存两套机制 —— 这套是「只在仓库为空时、同步阻塞启动拉一次」，
     * 另一套是「每次启动异步导入、可限 tier、沿用本机已关闭的源」。同步那套最坏
     * 情况是端口还没监听就卡几分钟（订阅站挂起 + 逐跳重定向各算一次超时），而且
     * preview 与 import 会把同一个 URL 各拉一遍。现在只留异步这一套；变量名保留，
     * 是为了不让已经写上它的部署静默失效。
     */
    val seedSubscriptionUrl: String? = envOrNull("ANIMEKO_SEED_SUBSCRIPTION_URL"),
) {

    /** 开机要导入的订阅地址：主变量 + 别名，去重后按书写顺序 */
    val autoImportUrls: List<String> =
        (subscriptionAutoImport + listOfNotNull(seedSubscriptionUrl?.trim()?.takeIf { it.isNotEmpty() })).distinct()

    val selectorsDir: Path get() = dataDir.resolve("selectors")

    /** 个人数据（收藏/历史/进度）在服务端的落点 */
    val userDataFile: Path get() = dataDir.resolve("userdata.json")

    companion object {
        fun env(key: String, default: String): String =
            System.getenv(key)?.takeIf { it.isNotBlank() } ?: default

        fun envOrNull(key: String): String? = System.getenv(key)?.takeIf { it.isNotBlank() }
    }
}
