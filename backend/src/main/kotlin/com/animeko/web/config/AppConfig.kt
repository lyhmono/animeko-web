package com.animeko.web.config

import java.nio.file.Path
import java.nio.file.Paths

/**
 * 全部通过环境变量配置，便于 Docker 部署。默认值面向内网自用（无鉴权）。
 */
data class AppConfig(
    val host: String = env("ANIMEKO_HOST", "0.0.0.0"),
    val port: Int = intEnv("ANIMEKO_PORT", 8080, min = 1, max = 65535),
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
     *
     * 这里以前是 `toBooleanStrictOrNull() ?: 凭据推导`，两个致命的静默失败：
     *  1) 只配了 ANIMEKO_AUTH_USER、忘配口令 → 推导结果是 false，实测 /api/data
     *     无凭据直接 200 读到数据 —— 想开登录的人得到了一台敞开的主机；
     *  2) 写 `ANIMEKO_AUTH_ENABLED=TRUE`（大写）或 `1`/`yes` → 严格解析返回 null，
     *     于是「明确要求开启」被当成没写，仍然是 false。
     * 安全开关只能出错在「更严」的那一侧：值认不出来就报错退出，凭据只配一半也报错退出。
     */
    val authEnabled: Boolean = resolveAuthEnabled(
        System.getenv("ANIMEKO_AUTH_ENABLED"), authUser, authPassword, authToken,
    ),
    /**
     * 会话 Cookie 是否加 Secure。默认 false：本机 8080 或 SSH 隧道都是 http，
     * 加了 Secure 浏览器会直接丢弃 Set-Cookie，表现为「登录页反复回弹」。
     * 反代配好 HTTPS 之后请设为 true。
     */
    val cookieSecure: Boolean = boolEnvStrict("ANIMEKO_COOKIE_SECURE") ?: false,

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
    val requestTimeoutMs: Long = longEnv("ANIMEKO_REQUEST_TIMEOUT_MS", 20_000, max = 600_000),
    /**
     * Bangumi 元数据请求的超时，单独配。社区镜像（bgmapi.anibt.net）实测要
     * 13~25 秒才能返回一次搜索，而抓取用的 20 秒上限会把它正好掐死 ——
     * 表现是「搜索页永远没有 Bangumi 结果」，而镜像手动 curl 又是通的。
     */
    val bangumiTimeoutMs: Long = longEnv("ANIMEKO_BANGUMI_TIMEOUT_MS", 45_000, max = 600_000),
    val proxyTimeoutMs: Long = longEnv("ANIMEKO_PROXY_TIMEOUT_MS", 120_000, max = 3_600_000),

    /**
     * 是否允许代理/抓取访问内网与保留地址。默认 false：
     * 服务默认绑定 0.0.0.0 且鉴权默认关闭，放开内网等于给同网段一台任意端口的跳板。
     * 只有当你确实要代理局域网内的 NAS/源站时才设为 true。
     */
    val allowPrivateTargets: Boolean = boolEnvStrict("ANIMEKO_ALLOW_PRIVATE_TARGETS") ?: false,

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
    val allowCrossOrigin: Boolean = boolEnvStrict("ANIMEKO_ALLOW_CROSS_ORIGIN") ?: false,

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
    val trustedProxyHops: Int = intEnv("ANIMEKO_TRUSTED_PROXY_HOPS", 0, min = 0, max = 8),

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

    /** Bangumi 通道选择的持久化位置（设置页写入，重启后保留） */
    val bangumiChannelFile: Path get() = dataDir.resolve("bangumi-channel.json")

    /** 个人数据（收藏/历史/进度）在服务端的落点 */
    val userDataFile: Path get() = dataDir.resolve("userdata.json")

    companion object {
        /**
         * 鉴权开关的最终判定。做成纯函数是为了能在 CI 里回归：JVM 改不了自己的
         * 环境变量，而这些分支恰恰是「读代码看不出危害」的那一类。
         *
         * @param raw ANIMEKO_AUTH_ENABLED 的原始值（未配置传 null）
         * @param user/password/token 已经过空白过滤（空串视为未配置）
         */
        fun resolveAuthEnabled(raw: String?, user: String?, password: String?, token: String?): Boolean {
            val explicit = parseBoolStrict("ANIMEKO_AUTH_ENABLED", raw)
            // 只配了一半的凭据永远匹配不上（Basic 要求两者齐备），而「按凭据推导」
            // 会因此得出 false —— 「我以为开了登录」实际变成「服务对整个网络敞开」。
            // 除了显式关闭，这种写法没有合法用途，所以拒绝启动。
            if (explicit != false && (user == null) != (password == null)) {
                val set = if (user != null) "ANIMEKO_AUTH_USER" else "ANIMEKO_AUTH_PASSWORD"
                val missing = if (user != null) "ANIMEKO_AUTH_PASSWORD" else "ANIMEKO_AUTH_USER"
                throw IllegalStateException(
                    "$set 已配置但 $missing 为空：账号口令必须成对生效，否则谁也登录不进来，" +
                        "而鉴权又会因为「没有完整凭据」被推导为关闭。补齐 $missing，" +
                        "或显式设置 ANIMEKO_AUTH_ENABLED=false（例如前面已有反代鉴权）。",
                )
            }
            val anyCreds = token != null || (user != null && password != null)
            if (explicit == true && !anyCreds) {
                throw IllegalStateException(
                    "ANIMEKO_AUTH_ENABLED=true 但没有可用凭据：请配置 ANIMEKO_AUTH_TOKEN，" +
                        "或成对配置 ANIMEKO_AUTH_USER + ANIMEKO_AUTH_PASSWORD。" +
                        "凭据只能来自环境变量（没有注册接口），这种组合会让所有人都永久进不去。",
                )
            }
            return explicit ?: anyCreds
        }

        fun env(key: String, default: String): String =
            System.getenv(key)?.takeIf { it.isNotBlank() } ?: default

        fun envOrNull(key: String): String? = System.getenv(key)?.takeIf { it.isNotBlank() }

        /**
         * 整数配置：没配（null/空白）走默认值；配了但不是 [min,max] 内的整数就拒绝启动。
         *
         * `toIntOrNull() ?: 默认值` 会把 `ANIMEKO_PORT=808o` 变成「起在 8080」，
         * 把 `ANIMEKO_TRUSTED_PROXY_HOPS=two` 变成「0 跳」——限速键退化成代理 IP，
         * 所有客户端共享同一个计数器，等于没有上限。端口 0 更糟：值本身合法，
         * 但 Netty 要到 bind 阶段才拒绝，堆栈里没有变量名，排查时根本想不到是配置。
         */
        fun parseIntStrict(key: String, raw: String?, default: Int, min: Int, max: Int): Int {
            val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return default
            val value = text.toIntOrNull()
                ?: throw IllegalStateException("$key 的值「$text」不是整数。")
            if (value !in min..max) {
                throw IllegalStateException("$key 必须在 $min..$max 范围内，当前为 $value。")
            }
            return value
        }

        fun intEnv(key: String, default: Int, min: Int, max: Int): Int =
            parseIntStrict(key, System.getenv(key), default, min, max)

        /** 毫秒类超时：同上，但必须为正数，且上限防呆（避免写成 1e15 让请求永久挂起） */
        fun parseLongStrict(key: String, raw: String?, default: Long, max: Long): Long {
            val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return default
            val value = text.toLongOrNull()
                ?: throw IllegalStateException("$key 的值「$text」不是整数。")
            if (value !in 1..max) {
                throw IllegalStateException("$key 必须在 1..$max 之间，当前为 $value。")
            }
            return value
        }

        fun longEnv(key: String, default: Long, max: Long): Long =
            parseLongStrict(key, System.getenv(key), default, max)

        /**
         * 解析一个布尔量：没配返回 null，交给调用方的默认值；配了但认不出来**直接报错**。
         *
         * 为什么不能「解析失败就取默认值」：`toBooleanStrictOrNull()` 会把 `TRUE`（大写）、
         * `1`、`yes` 这类再自然不过的写法当成「没配」。对功能开关来说那只是回到默认行为；
         * 对 ANIMEKO_AUTH_ENABLED 来说，「我明确要求开启」被静默降级成「没配 → 按凭据推导 →
         * 凭据只配了一半 → 关闭鉴权」，运维以为上了锁，实际得到一台敞开的主机。
         * 宁可启动失败让容器刷错误日志，也不要在安全属性上猜。
         */
        fun parseBoolStrict(key: String, raw: String?): Boolean? {
            val text = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return when (text) {
                "true", "1", "yes", "on", "y", "t" -> true
                "false", "0", "no", "off", "n", "f" -> false
                else -> throw IllegalStateException(
                    "$key 的值「$text」无法识别，" +
                        "只接受 true/false（大小写不限，也接受 1/0、yes/no、on/off）。",
                )
            }
        }

        fun boolEnvStrict(key: String): Boolean? = parseBoolStrict(key, System.getenv(key))
    }
}
