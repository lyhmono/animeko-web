package com.animeko.web.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * 配置解析的回归测试。
 *
 * 这些用例针对的都是「服务正常启动了，只是门没锁」这一类故障：
 * 静默取默认值不会留下任何痕迹，所以只能靠测试把它钉住 —— 读代码是读不出来的。
 */
class AppConfigAuthFlagTest {
    @Test
    fun `credentials present enable auth without an explicit switch`() {
        assertEquals(true, AppConfig.resolveAuthEnabled(null, "admin", "pw", null))
        assertEquals(true, AppConfig.resolveAuthEnabled(null, null, null, "token"))
    }

    @Test
    fun `no credentials means no auth`() {
        assertEquals(false, AppConfig.resolveAuthEnabled(null, null, null, null))
    }

    /**
     * 曾经最危险的一条：只配了 ANIMEKO_AUTH_USER、忘了口令。
     * 按凭据推导出 false，而这对鉴权来说就是「整机敞开」——
     * 实测 /api/data 无需任何凭据即可 200 读到数据。
     */
    @Test
    fun `half configured credentials refuse to start instead of disabling auth`() {
        for (e in listOf(
            assertFailsWith<IllegalStateException> {
                AppConfig.resolveAuthEnabled(null, "admin", null, null)
            },
            assertFailsWith<IllegalStateException> {
                AppConfig.resolveAuthEnabled(null, null, "pw", null)
            },
        )) {
            assertEquals(true, e.message?.contains("必须成对"))
        }
    }

    /** 大写 TRUE / 1 / yes 都是运维的自然写法，被当成「没配」就等于把门重新打开 */
    @Test
    fun `truthy spellings are honored not silently dropped`() {
        for (raw in listOf("true", "TRUE", "True", " 1 ", "yes", "ON", "y", "t")) {
            assertEquals(true, AppConfig.resolveAuthEnabled(raw, "admin", "pw", null), raw)
        }
    }

    @Test
    fun `falsy spellings disable explicitly`() {
        for (raw in listOf("false", "FALSE", " 0", "no", "Off", "n", "f")) {
            assertEquals(false, AppConfig.resolveAuthEnabled(raw, "admin", "pw", null), raw)
        }
    }

    /** 认不出来的布尔值必须报错，而不是悄悄回到默认行为 */
    @Test
    fun `unrecognized boolean aborts startup`() {
        for (raw in listOf("truex", "enabled", "2", "否", " ", "\t")) {
            // 空白视为「未配置」，其余一律报错
            if (raw.isBlank()) {
                assertNull(AppConfig.parseBoolStrict("ANIMEKO_X", raw), raw)
            } else {
                assertFailsWith<IllegalStateException>("expected throw for [$raw]") {
                    AppConfig.parseBoolStrict("ANIMEKO_X", raw)
                }
            }
        }
    }

    /**
     * 上面那一格单独测解析函数，**测不到调用方**：如果有人把
     * `resolveAuthEnabled` 里的解析包成 `runCatching { … }.getOrNull() ?: 凭据推导`，
     * 那条最危险的路径（明确要求开启却被当成没配）就又静默了，而上面的用例照样全绿。
     * 所以这里从最终入口再断言一次。
     */
    @Test
    fun `unrecognized value is fatal through the real entry point too`() {
        for (raw in listOf("truex", "maybe", "2")) {
            assertFailsWith<IllegalStateException>("resolveAuthEnabled($raw) 不该静默回落") {
                AppConfig.resolveAuthEnabled(raw, "admin", "pw", null)
            }
            // 没有凭据时也一样：不能因为「推导出来是 false」就把错误咽下去
            assertFailsWith<IllegalStateException> {
                AppConfig.resolveAuthEnabled(raw, null, null, null)
            }
        }
    }

    /** 显式关闭鉴权时，残缺的凭据只是无用配置，不该阻止启动 */
    @Test
    fun `explicit opt out tolerates a lone username`() {
        assertEquals(false, AppConfig.resolveAuthEnabled("false", "admin", null, null))
    }

    /** 开了鉴权却没有任何凭据 = 谁也进不去，且没有接口能补救 */
    @Test
    fun `auth enabled without credentials refuses the self lockout`() {
        val e = assertFailsWith<IllegalStateException> {
            AppConfig.resolveAuthEnabled("true", null, null, null)
        }
        assertEquals(true, e.message?.contains("ANIMEKO_AUTH_TOKEN"))
    }

    /** 只有口令没有用户名：token 可用时不算自锁，但口令本身仍是不成对配置 */
    @Test
    fun `password alone is still a broken pairing even with a token`() {
        assertFailsWith<IllegalStateException> {
            AppConfig.resolveAuthEnabled("true", null, "pw", "token")
        }
    }
}

class AppConfigNumberTest {
    @Test
    fun `missing or blank falls back to the default`() {
        assertEquals(8080, AppConfig.parseIntStrict("ANIMEKO_PORT", null, 8080, 1, 65535))
        assertEquals(8080, AppConfig.parseIntStrict("ANIMEKO_PORT", "   ", 8080, 1, 65535))
        assertEquals(20000L, AppConfig.parseLongStrict("T", "", 20000L, 600000L))
    }

    /** 端口写错字：以前静默起在 8080，部署的人对着另一个端口排查半天 */
    @Test
    fun `non numeric port is an error not a default`() {
        for (raw in listOf("808o", "8080/tcp", "0x1F90", "808 0", "NaN")) {
            assertFailsWith<IllegalStateException>("expected throw for [$raw]") {
                AppConfig.parseIntStrict("ANIMEKO_PORT", raw, 8080, 1, 65535)
            }
        }
    }

    /**
     * 全角数字会被接受（`Integer.parseInt` 走 `Character.digit`，中文输入法下敲出的
     * `８０８０` 解析成 8080）。钉住这个行为而不是假装它不存在：结果是良性的 ——
     * 正是运维想写的端口，且区间校验照旧生效，越界的全角值仍会拒绝启动。
     */
    @Test
    fun `full width digits parse to the same value`() {
        assertEquals(8080, AppConfig.parseIntStrict("ANIMEKO_PORT", "８０８０", 8080, 1, 65535))
        assertFailsWith<IllegalStateException> {
            AppConfig.parseIntStrict("ANIMEKO_PORT", "６５５３６", 8080, 1, 65535)
        }
    }

    /** 0 号端口在 Netty bind 阶段才炸，堆栈里没有变量名 */
    @Test
    fun `out of range port is rejected by name`() {
        val e = assertFailsWith<IllegalStateException> {
            AppConfig.parseIntStrict("ANIMEKO_PORT", "0", 8080, 1, 65535)
        }
        assertEquals(true, e.message?.contains("ANIMEKO_PORT"))
        assertFailsWith<IllegalStateException> {
            AppConfig.parseIntStrict("ANIMEKO_PORT", "65536", 8080, 1, 65535)
        }
    }

    /**
     * 反代层数写错 → 0 跳 → 限速键退化成代理 IP，
     * 所有客户端共用一个计数器，等于对全内网没有上限。夹取到 8 同样错。
     */
    @Test
    fun `proxy hops are never clamped`() {
        assertFailsWith<IllegalStateException> {
            AppConfig.parseIntStrict("ANIMEKO_TRUSTED_PROXY_HOPS", "two", 0, 0, 8)
        }
        assertFailsWith<IllegalStateException> {
            AppConfig.parseIntStrict("ANIMEKO_TRUSTED_PROXY_HOPS", "99", 0, 0, 8)
        }
        assertFailsWith<IllegalStateException> {
            AppConfig.parseIntStrict("ANIMEKO_TRUSTED_PROXY_HOPS", "-1", 0, 0, 8)
        }
        assertEquals(2, AppConfig.parseIntStrict("ANIMEKO_TRUSTED_PROXY_HOPS", "2", 0, 0, 8))
        assertEquals(0, AppConfig.parseIntStrict("ANIMEKO_TRUSTED_PROXY_HOPS", "0", 0, 0, 8))
    }

    @Test
    fun `timeouts must be positive and bounded`() {
        assertEquals(20000L, AppConfig.parseLongStrict("ANIMEKO_REQUEST_TIMEOUT_MS", "20000", 20000L, 600000L))
        for (raw in listOf("-1", "0")) {
            assertFailsWith<IllegalStateException>("expected throw for [$raw]") {
                AppConfig.parseLongStrict("ANIMEKO_REQUEST_TIMEOUT_MS", raw, 20000L, 600000L)
            }
        }
        // 1e15 毫秒：值合法，但请求会永久挂住，且足以撑爆 Int 相关计算
        assertFailsWith<IllegalStateException> {
            AppConfig.parseLongStrict("ANIMEKO_PROXY_TIMEOUT_MS", "1000000000000000", 120000L, 3600000L)
        }
    }
}
