package com.animeko.web.api

/**
 * REST 响应的显式模型。
 *
 * 必须是显式模型而不是 mapOf(...)：Map<String, Any> 编译通过、序列化期才报
 * Serializer not found，用户看到的是一个 500 空登录页。
 */
@kotlinx.serialization.Serializable
data class HealthResponse(
    val status: String,
    val version: String,
    val authEnabled: Boolean,
    /** 未启用鉴权时恒为 true；启用时反映本次请求携带的凭据是否有效 */
    val authenticated: Boolean,
)

@kotlinx.serialization.Serializable
data class VideoSourceResponse(
    /** 解析出的媒体地址，仅便于排查与手动复放 */
    val url: String,
    /** 交给播放器的代理地址（凭据不在里面） */
    val proxyPath: String,
)

@kotlinx.serialization.Serializable
data class SourceEnabledResponse(
    val name: String,
    val enabled: Boolean,
)

/**
 * 登录/注销返回。必须是显式模型：`mapOf("authenticated" to true, "user" to ...)` 会被推断成
 * Map<String, Any>，编译通过、序列化期才报 Serializer not found，用户看到的是一个 500 空登录页。
 */
@kotlinx.serialization.Serializable
data class SessionStateResponse(
    val authenticated: Boolean,
    val user: String = "",
)

@kotlinx.serialization.Serializable
data class DataSavedResponse(
    /** 落盘后的字节数，便于前端在「收藏太多写不进去」时给出可解释的提示 */
    val bytes: Long,
)
