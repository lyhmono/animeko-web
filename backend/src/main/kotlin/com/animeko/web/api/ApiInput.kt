package com.animeko.web.api

import com.animeko.web.http.JsonGuard
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream

private val prettyJson = Json { ignoreUnknownKeys = true }

/**
 * 请求体上限。登录接口免鉴权也可达，不封顶等于让来访者决定这台机器的堆用量
 * （接收侧此前完全没有上限，只有落盘前的 importJson 才校验大小 —— 那时已经读进内存了）。
 */
internal const val MAX_BODY_BYTES = 1L * 1024 * 1024

/**
 * 登录表单的体积上限，比通用上限小一个量级。
 *
 * 获得许可之后仍须限制表单，不能让一个候选决定接收侧的堆用量。
 * 32KB 足以容纳配置 token；被拒绝的请求在读取表单之前已经返回。
 */
internal const val MAX_LOGIN_BODY_BYTES = 32L * 1024

/**
 * 「请求体超限」要能和「JSON 不合法」区分开。
 *
 * 它俩在 StatusPages 里都映射成 400，但**经由 bodyObject 的路由会先把它吞掉**：
 * bodyObject 对任何异常都返回 null，调用方于是回「请填写口令」或「missing keyword」。
 * 实测：40KB 的登录体（上限 32KB）得到的是 `{"error":"请填写口令"}` —— 状态码没错，
 * 但排查的人会去检查表单，而真正的问题是他发了 40KB。
 * 所以用一个专门类型标记它，bodyObject 见到就原样抛出（TooDeep 同理）。
 */
internal class RequestTooLarge(message: String) : IllegalArgumentException(message)

internal suspend fun bodyField(call: ApplicationCall, field: String): String? =
    bodyObject(call)?.stringField(field)

internal suspend fun bodyObject(call: ApplicationCall, maxBytes: Long = MAX_BODY_BYTES): JsonObject? =
    readJsonObject { call.receiveCappedText(maxBytes) }

internal suspend fun readJsonObject(read: suspend () -> String): JsonObject? {
    // Transport failures, limits and cancellation are not JSON shape errors. Read outside the parse catch.
    val text = try { read() } catch (cancelled: CancellationException) { throw cancelled }
    return try {
        prettyJson.parseToJsonElement(text) as? JsonObject
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

/**
 * 取字符串字段。
 *
 * 两个坑都要避开：
 *  - `jsonPrimitive` 是强转，`{"url":{"a":1}}` 会抛 IllegalArgumentException，
 *    而它发生在 bodyObject 的 try 之外，于是一个形状奇怪的请求体变成 500；
 *  - `contentOrNull` 对**任何** primitive 都给得出文本，`{"url":true}` 会变成
 *    字符串 "true"，接着被 UrlGuard 判成「不允许的目标地址」回 400/403 ——
 *    把类型错误伪装成安全拒绝，排查时完全看不出是请求体写错了。
 * 所以：只接受字符串，其它一律当作缺字段（调用方本来就有缺失分支会给 400）。
 */
internal fun JsonObject.stringField(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * 严格取字符串数组：不是数组、或数组里含非字符串项，都整字段判错。
 *
 * 旧写法 `get(key)?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }` 有两处不成立：
 *  - `jsonArray` 对对象是强转（虽然 IllegalArgumentException 会被兜底成 400，但那是巧合，
 *    不是这一行的设计意图）；
 *  - `contentOrNull` 对**数字**也给出文本，于是 `{"names":[1,2]}` 变成两个「源名」，
 *    导入一条都没匹配上却回 200 —— 类型错误被伪装成一次成功但空的结果。
 * 半收半丢同样糟：`["订阅甲",{"bad":1}]` 只导入一条却报成功，用户看不出少了东西。
 */
internal fun JsonObject.stringArrayField(key: String): List<String> {
    val node = this[key] ?: return emptyList()
    if (node is JsonNull) return emptyList()
    val items = node as? JsonArray ?: throw IllegalArgumentException("$key 必须是字符串数组")
    return items.map {
        (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
            ?: throw IllegalArgumentException("$key 只能包含字符串")
    }
}

internal suspend fun ApplicationCall.receiveCappedText(maxBytes: Long = MAX_BODY_BYTES): String {
    // 先看 Content-Length 挡掉老实的超大请求，再逐块累加，防止 chunked 传输谎报长度
    val declared = request.contentLength()
    if (declared != null && declared > maxBytes) {
        throw RequestTooLarge("请求体超过 ${maxBytes / 1024}KB 上限")
    }
    return readRequestChannel(request.receiveChannel(), maxBytes)
}

internal suspend fun readRequestChannel(channel: ByteReadChannel, maxBytes: Long = MAX_BODY_BYTES): String {
    val out = ByteArrayOutputStream()
    val chunk = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        // Ktor 3 readAvailable can return -1 for an already failed/cancelled channel.
        // EOF is clean only when closedCause is null; preserve the original transport cause.
        channel.closedCause?.let { throw it }
        val n = channel.readAvailable(chunk)
        if (n == -1) break
        total += n
        if (total > maxBytes) throw RequestTooLarge("请求体超过 ${maxBytes / 1024}KB 上限")
        out.write(chunk, 0, n)
    }
    channel.closedCause?.let { throw it }
    val text = String(out.toByteArray(), Charsets.UTF_8)
    // 体积上限挡不住「400KB 的一把左括号」：那种递归解析会打穿栈。
    // 不能只指望下游接得住：catch (Exception) 接不住 Error，而 runCatching 接得住
    // 却会把合法 JSON 判成坏文件搬走（两种后果都不是「这个请求被拒绝了」）。
    // 详见 JsonGuard 的类注释。
    JsonGuard.requireShallow(text)
    return text
}
