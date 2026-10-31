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
        if (cfg.authUser != null && cfg.authPassword != null) {
            return constantTimeEquals(user, cfg.authUser) && constantTimeEquals(pass, cfg.authPassword)
        }
        return cfg.authToken != null && constantTimeEquals(pass, cfg.authToken)
    }

    override fun bearer(token: String): Boolean =
        token.isNotEmpty() && cfg.authToken != null && constantTimeEquals(token, cfg.authToken)
}

private fun constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
