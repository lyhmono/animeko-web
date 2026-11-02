package com.animeko.web.api

import com.animeko.web.auth.LoginRateLimiter
import com.animeko.web.auth.SessionStore
import com.animeko.web.auth.CredentialVerifier
import com.animeko.web.bangumi.BangumiClient
import com.animeko.web.config.AppConfig
import com.animeko.web.data.UserDataStore
import com.animeko.web.http.Fetcher
import com.animeko.web.proxy.ProxySessionStore
import com.animeko.web.selector.SelectorEngine
import com.animeko.web.selector.SelectorRepository
import com.animeko.web.selector.SubscriptionService

/**
 * 各路由组共用的依赖。
 *
 * 拆成多个文件之前，configureApiRoutes 的 440 行路由体全都闭包捕获这十个依赖；
 * 拆出去之后每组要么带十个参数、要么带这一个对象。选后者：新增一组路由不必再改
 * 五处参数表，而每组实际用到哪几个依赖在读它的文件时一目了然。
 */
internal class ApiDeps(
    val cfg: AppConfig,
    val bangumi: BangumiClient,
    val repo: SelectorRepository,
    val engine: SelectorEngine,
    val fetcher: Fetcher,
    val sessions: ProxySessionStore,
    val site: SessionStore,
    val limiter: LoginRateLimiter,
    val userData: UserDataStore,
    val subscriptions: SubscriptionService,
    val credentialVerifier: CredentialVerifier,
    val probeLimiter: LoginRateLimiter,
)
