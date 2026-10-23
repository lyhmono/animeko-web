package com.animeko.web.selector

import com.animeko.web.appLog
import com.animeko.web.http.Fetcher
import com.animeko.web.http.HttpResult
import com.animeko.web.http.JsonGuard
import com.animeko.web.http.UrlGuard
import com.animeko.web.http.UpstreamException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 聚合订阅：一条 URL 背后是几十上百个数据源的集合（例如 css1.json 这种）。
 * 这里负责「拉取 → 拆条目 → 让用户挑 → 只把选中的写进仓库」，
 * 而不是整包导入：一次导入几十个来源，实际能用的往往只有几个，
 * 剩下的只会让每次搜索都多打一遍不可达的站点。
 */
class SubscriptionService(
    private val fetcher: Fetcher,
    private val repo: SelectorRepository,
    private val allowPrivateTargets: Boolean,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** 预览用的一行；不含 searchConfig，避免把 50KB 的订阅原样发给浏览器。 */
    @kotlinx.serialization.Serializable
    data class Candidate(
        val name: String,
        val description: String,
        val tier: String,
        val site: String,
        /** 仓库里已经有同名源（导入会覆盖它） */
        val exists: Boolean,
        val enabled: Boolean,
    )

    @kotlinx.serialization.Serializable
    data class PreviewResponse(
        val subscription: String,
        val candidates: List<Candidate>,
        val rejected: List<String>,
        /**
         * 正文内容指纹。预览与导入之间订阅内容可能变化（站点更新），
         * 导入时带上它：对不上就要求重新预览，而不是按一份看不见的清单导入。
         */
        val fingerprint: String,
    )

    @kotlinx.serialization.Serializable
    data class ImportResponse(
        val added: List<String>,
        val updated: List<String>,
        val failed: List<String>,
        val rejected: List<String>,
    )

    private sealed class Parsed {
        data class Ok(val arguments: SelectorSourceArguments) : Parsed()
        data class Bad(val label: String, val reason: String) : Parsed()
    }

    /**
     * 订阅地址与订阅正文二选一。
     * 允许直接贴正文：订阅站挂了 / 需要登录时，用户手上往往是一份导出的文件。
     */
    private suspend fun loadBody(url: String?, text: String?): String {
        // U+FEFF 不是 isWhitespace：带 BOM 的导出文件会让 JSON 在 offset 0 直接失败，
        // 用户只看到「不是合法 JSON」。粘贴正文与 HTTP 响应两条分支都要剥掉它。
        val direct = text?.trim()?.removePrefix("\uFEFF").orEmpty()
        if (direct.isNotEmpty()) {
            require(direct.length <= MAX_SUBSCRIPTION_BYTES.toInt()) {
                "订阅内容超过 ${MAX_SUBSCRIPTION_BYTES / 1024 / 1024}MB 上限"
            }
            return direct
        }
        var target = url?.trim().orEmpty()
        require(target.isNotEmpty()) { "需要提供订阅地址 url 或订阅正文 text" }
        target = UrlGuard.requireAllowed(target, allowPrivateTargets)

        // 自己逐跳跟重定向：JdkFetcher 默认按 Redirect.NORMAL 自动跳，
        // 而 UrlGuard 只看得到第一跳 —— 302 指向 169.254.169.254 时
        // 「校验入口地址」等于给 SSRF 让路（/api/proxy 早就是逐跳校验的）
        var hops = 0
        while (true) {
            val result = try {
                fetcher.fetchBounded(target, emptyMap(), MAX_SUBSCRIPTION_BYTES)
            } catch (e: CancellationException) {
                throw e
            } catch (e: UrlGuard.Blocked) {
                throw e
            } catch (e: UpstreamException) {
                throw e
            } catch (e: Exception) {
                throw UpstreamException("拉取订阅失败: ${e.message ?: e::class.simpleName}")
            }
            if (result.status !in 301..399) {
                return requireSuccess(target, result)
            }
            val location = result.header("location")
            if (location.isNullOrBlank()) throw UpstreamException("订阅返回 $result.status 但没有 Location")
            if (hops >= MAX_REDIRECTS) throw UpstreamException("订阅重定向次数过多")
            hops++
            target = UrlGuard.requireAllowed(SelectorEngine.absUrl(target, location), allowPrivateTargets)
        }
    }

    private fun requireSuccess(target: String, result: HttpResult): String {
        if (result.status !in 200..299) {
            // 日志只该出现 scheme://host/path：订阅地址常把 token 放在查询串里
            throw UpstreamException("订阅返回 ${result.status}（${SelectorEngine.urlForLog(target)}）")
        }
        return String(result.body, Charsets.UTF_8).removePrefix("\uFEFF")
    }

    /**
     * 开机自动导入（ANIMEKO_SUBSCRIPTION_AUTO_IMPORT）。
     * 任何失败都只记日志：订阅站挂了不能把整个服务带走，更不能让容器起不来。
     *
     * 已存在的源沿用它的 enabled —— 界面里关掉的源一定是用户试过、判定不可用的，
     * 每次重启都把它重新打开，等于替用户做一次他明确拒绝过的决定（而且会让
     * 每次搜索重新多打一遍那个打不开的站点）。
     */
    suspend fun autoImport(urls: List<String>, tiers: Set<String>): List<String> = urls.map { url ->
        val label = try {
            val body = loadBody(url, null)
            val (items, _) = parse(body)
            val wanted = items.filter { tiers.isEmpty() || displayTier(it.tier) in tiers }
            if (wanted.isEmpty()) {
                appLog.warn("订阅 {} 没有匹配 tier={} 的数据源，跳过", SelectorEngine.urlForLog(url), tiers)
            }
            var ok = 0
            val known = repo.list().associate { it.arguments.name to it.enabled }
            wanted.forEach { args ->
                runCatching { repo.save(args, known[args.name] ?: true) }
                    .onSuccess { ok++ }
                    .onFailure {
                        appLog.warn(
                            "自动导入 {} 的「{}」失败：{}",
                            SelectorEngine.urlForLog(url),
                            args.name,
                            it.message ?: it::class.simpleName,
                        )
                    }
            }
            "$ok/${wanted.size}"
        } catch (e: CancellationException) {
            // 关停/超时取消必须原样上抛，不能被当成「这个订阅失败」继续跑下一个
            throw e
        } catch (e: Exception) {
            appLog.warn(
                "自动导入订阅 {} 失败：{}",
                SelectorEngine.urlForLog(url),
                e.message ?: e::class.simpleName,
            )
            "失败"
        }
        "${SelectorEngine.urlForLog(url)} → $label 个"
    }

    suspend fun preview(url: String?, text: String?, label: String = ""): PreviewResponse {
        val body = loadBody(url, text)
        val (items, rejected) = parse(body)
        val known = repo.list().associateBy { it.arguments.name }
        return PreviewResponse(
            // 订阅地址常把 token 放在查询串里；预览页会把它显示出来，截图/共享屏幕就是泄露。
            // urlForLog 只保留 scheme://host/path，足够让人认出是哪个订阅。
            subscription = label.ifBlank {
                url?.trim()?.takeIf { it.isNotEmpty() }?.let { SelectorEngine.urlForLog(it) }.orEmpty()
            },
            candidates = items.map { args ->
                val existing = known[args.name]
                Candidate(
                    name = args.name,
                    description = args.description.take(160),
                    tier = displayTier(args.tier),
                    site = siteOf(args),
                    exists = existing != null,
                    enabled = existing?.enabled ?: true,
                )
            },
            rejected = rejected,
            fingerprint = sha256Hex(body),
        )
    }

    /**
     * 只导入勾选的条目。
     * 逐条 save：某一条坏了（正则非法、名字消毒后撞车）不该让前面已经成功的白做。
     *
     * @param fingerprint 预览时拿到的正文指纹；非空且与当前正文不符时拒绝导入
     *   （订阅在预览后被更新过，用户看到的清单已经不是当前内容）。
     */
    suspend fun importSelected(
        url: String?,
        text: String?,
        names: List<String>,
        fingerprint: String? = null,
    ): ImportResponse {
        require(names.isNotEmpty()) { "没有选择要导入的数据源" }
        val body = loadBody(url, text)
        if (!fingerprint.isNullOrBlank() && fingerprint != sha256Hex(body)) {
            throw IllegalArgumentException("订阅内容已变化，请重新拉取预览后再导入")
        }
        val (items, rejected) = parse(body)
        val byName = items.associateBy { it.name }
        val added = mutableListOf<String>()
        val updated = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (name in names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()) {
            val args = byName[name]
            if (args == null) {
                failed += "$name（订阅里没有这一条）"
                continue
            }
            val existing = repo.find(name)
            try {
                // 更新已有源时**沿用它的启停状态**：用户把某个源关掉，是明确判断过它不可用
                // （死站，或者每个源都要等 20 秒超时的那个）。重新导入订阅是为了拿新内容/新源，
                // 不该顺手把这个决定翻回去 —— 自动导入那条路径早就这样做了
                // （autoImport 的 `known[args.name] ?: true`），手动挑源导入却漏了。
                // 实测症状：停用 → 用订阅「更新」同一条 → 又变回启用，随后每次搜索都要
                // 在那个死源上等超时，用户只看到「搜索变慢了」，联想不到是导入干的。
                repo.save(args, existing?.enabled ?: true)
                if (existing != null) updated += name else added += name
            } catch (e: IllegalArgumentException) {
                failed += "$name（${e.message?.take(120) ?: "配置不合法"}）"
            }
        }
        return ImportResponse(added = added, updated = updated, failed = failed, rejected = rejected)
    }

    /**
     * 兼容三种形态：
     *  1. 聚合订阅 `{exportedMediaSourceDataList:{mediaSources:[{factoryId,version,arguments}]}}`
     *  2. 裸数组 `[{factoryId,version,arguments}]`（有的站点直接导出列表）
     *  3. 单个源 / 裸 arguments（让「订阅」这一个入口也能导入单文件，前端不必分两条路）
     * 条目解析失败只丢那一条，整包不失败。
     */
    private fun parse(body: String): Pair<List<SelectorSourceArguments>, List<String>> {
        val root = try {
            // 订阅正文来自第三方：深度不检查的话，一份 `[[[[…` 会打穿栈，
            // 而 StackOverflowError 不是 Exception，下面这个 catch 接不住
            JsonGuard.requireShallow(body)
            json.parseToJsonElement(body)
        } catch (e: Exception) {
            throw IllegalArgumentException("订阅内容不是合法 JSON：${e.message?.take(160)}")
        }
        val elements: List<JsonElement> = when (root) {
            is JsonArray -> root.toList()
            is JsonObject -> {
                val list = root["exportedMediaSourceDataList"]
                val arr = (list as? JsonObject)?.get("mediaSources")
                    ?: root["mediaSources"]
                    ?: root["list"]
                when (arr) {
                    is JsonArray -> arr.toList()
                    // 没有列表字段：按「整个文档就是一个源」处理
                    else -> listOf(root)
                }
            }
            else -> throw IllegalArgumentException(
                "订阅内容不是 JSON 对象或数组（订阅站可能返回了错误页）：${shapeOf(root)}",
            )
        }
        // 上限是用来封顶工作量的，不是用来取消全部工作的：
        // 热门聚合订阅动辄六七百个源，原先直接 require(size <= MAX_ENTRIES) 会让整份订阅
        // 一个源都导不进来，而下面本来就有「一条坏只丢一条并说明原因」的机制。
        val overflow = elements.size - MAX_ENTRIES
        val considered = if (overflow > 0) elements.take(MAX_ENTRIES) else elements
        val items = mutableListOf<SelectorSourceArguments>()
        val rejected = mutableListOf<String>()
        if (overflow > 0) {
            rejected += "另有 $overflow 个数据源超出单次 ${MAX_ENTRIES} 个上限，未列出（可让订阅站拆分，或挑少量源单独导入）"
        }
        considered.forEachIndexed { index, el ->
            val args = decode(el)
            val label = args?.name?.takeIf { it.isNotBlank() } ?: "第 ${index + 1} 条（${shapeOf(el)}）"
            when {
                args == null -> rejected += "$label：缺少 name/searchConfig 或结构不符"
                args.name.isBlank() -> rejected += "$label：name 为空"
                // searchConfig 有默认值，所以「只有名字、没有搜索地址」的条目能解码成功，
                // 但导进去就是一条永远搜不出东西的死源——在预览阶段就该被挑出来
                args.searchConfig.searchUrl.isBlank() -> rejected += "$label：没有 searchUrl，无法搜索"
                items.any { it.name == args.name } -> rejected += "${args.name}：订阅内重名，只保留先出现的那条"
                else -> items += args
            }
        }
        return items to rejected
    }

    private fun decode(el: JsonElement): SelectorSourceArguments? {
        val wrapper = runCatching { json.decodeFromJsonElement(SelectorConfigFile.serializer(), el) }
            .getOrNull()
        if (wrapper != null) {
            // 与 SelectorRepository.importJson 同一条规则。订阅里同时打包了 web 与
            // BT/PikPak 源；丢掉 factoryId 后，只要那条恰好带 searchUrl 就会被当成
            // 网页源导入 —— 列表里看着正常，搜索永远 0 结果，也看不出它原本不是这个类型
            val factory = wrapper.factoryId.trim()
            if (factory.isNotEmpty() && !factory.equals("web-selector", ignoreCase = true)) return null
            return wrapper.arguments.takeIf { it.name.isNotBlank() }
        }
        return runCatching { json.decodeFromJsonElement(SelectorSourceArguments.serializer(), el) }
            .getOrNull()?.takeIf { it.name.isNotBlank() }
    }

    private fun shapeOf(el: JsonElement): String = when (el) {
        is JsonObject -> "字段 ${el.keys.take(4).joinToString()}"
        is JsonArray -> "数组 ${el.size} 项"
        else -> el.jsonPrimitive.contentOrNull?.take(20) ?: el.toString().take(20)
    }

    /**
     * tier 在 animeko 里既可能是数字（分级）也可能是字符串，展示时统一成文本。
     *
     * 社区订阅里它还可能是**对象或数组**（自制映射、`{"t0": ...}` 这种），而
     * `JsonElement.jsonPrimitive` 对非 primitive 是直接抛 IllegalArgumentException。
     * 这里必须写成全函数：tier 只是列表里一列展示文本，不值得让一条源的写法
     * 把整个预览（以及自动导入）打成一团 500。
     */
    private fun displayTier(value: JsonElement?): String = when (value) {
        null, is JsonNull -> ""
        is JsonPrimitive -> value.contentOrNull ?: value.toString().take(40)
        else -> value.toString().take(40)
    }

    /** 站点主机名：让用户在列表里一眼看出这是哪个站，而不必展开配置。 */
    private fun siteOf(args: SelectorSourceArguments): String {
        val cfg = args.searchConfig
        val raw = listOf(cfg.rawBaseUrl, cfg.searchUrl).firstOrNull { it.isNotBlank() } ?: return ""
        // 解析不出主机名时也不能把带 token 的原文整个展示出来
        return runCatching { java.net.URI(raw).host }.getOrNull()?.lowercase()
            ?: SelectorEngine.urlForLog(raw).take(60)
    }

    private companion object {
        private const val MAX_SUBSCRIPTION_BYTES = 4L * 1024 * 1024
        private const val MAX_ENTRIES = 500
        private const val MAX_REDIRECTS = 5
    }
}

/** 内容指纹：etag 风格的短哈希，仅用于「预览与导入看到的是不是同一份内容」 */
private fun sha256Hex(text: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(32)
