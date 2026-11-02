package com.animeko.web

/**
 * 本地存储不可用（磁盘满、只读挂载、权限不对）。
 *
 * 与普通业务校验错误（401/400 里那些「名字太长」「JSON 不合法」）分开：
 * 这类消息会带文件路径、挂载点等部署细节，只能进服务端日志，不能回给客户端。
 */
class StorageFailure(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)