package com.animeko.web.auth

import com.animeko.web.config.AppConfig
import java.security.MessageDigest

/** Secret comparisons are injectable so tests can prove no validator runs without a permit. */
interface CredentialVerifier {
    fun password(user: String, pass: String): Boolean
    fun bearer(token: String): Boolean
}

class ConfiguredCredentialVerifier(private val cfg: AppConfig) : CredentialVerifier {
    override fun password(user: String, pass: String): Boolean {
        if (pass.isEmpty()) return false
        val expectedUser = cfg.authUser
        val expectedPassword = cfg.authPassword
        if (expectedUser != null && expectedPassword != null) {
            // 两次比较都跑完再合取：&& 的短路会让「用户名对不对」留下时间差，
            // 那样 constantTimeEquals 只护住了口令的一半。
            val userOk = constantTimeEquals(user, expectedUser)
            val passOk = constantTimeEquals(pass, expectedPassword)
            return userOk && passOk
        }
        return cfg.authToken != null && constantTimeEquals(pass, cfg.authToken)
    }

    override fun bearer(token: String): Boolean =
        token.isNotEmpty() && cfg.authToken != null && constantTimeEquals(token, cfg.authToken)
}

/**
 * 先各取 SHA-256 再比。MessageDigest.isEqual 只在两侧等长时恒定耗时，长度不同会立刻
 * 返回 —— 那等于把「候选口令有多长」写进了响应时间。摘要长度恒定，比较就与输入长度无关。
 */
private fun constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(sha256(a), sha256(b))

private fun sha256(value: String): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
