package com.animeko.web.selector

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** 持久化的数据源（animeko 兼容 JSON + 启停标记） */
@kotlinx.serialization.Serializable
data class StoredSource(
    val factoryId: String = "web-selector",
    val version: Int = 2,
    val enabled: Boolean = true,
    val arguments: SelectorSourceArguments,
)

/**
 * 数据源仓库：每个数据源一个 JSON 文件，存放在 {dataDir}/selectors/ 下。
 * 导入时同时接受 animeko 标准包装（factoryId/version/arguments）与裸 arguments 对象。
 */
class SelectorRepository(private val dir: Path) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        if (!dir.exists()) dir.createDirectories()
    }

    fun list(): List<StoredSource> {
        if (!dir.exists()) return emptyList()
        return Files.list(dir).use { stream ->
            stream.filter { it.extension == "json" }
                .map { path ->
                    runCatching {
                        json.decodeFromString<StoredSource>(path.readText())
                    }.getOrNull()
                }
                .toList()
                .filterNotNull()
                .sortedBy { it.arguments.name }
        }
    }

    fun find(name: String): StoredSource? = list().firstOrNull { it.arguments.name == name }

    fun save(arguments: SelectorSourceArguments, enabled: Boolean = true): StoredSource {
        require(arguments.name.isNotBlank()) { "数据源名称不能为空" }
        require(arguments.name.length <= MAX_NAME_LENGTH) { "数据源名称过长（上限 $MAX_NAME_LENGTH 字符）" }
        validateRegexes(arguments.searchConfig)
        val stored = StoredSource(enabled = enabled, arguments = arguments)
        val target = fileFor(arguments.name)
        // 文件名是名字的消毒结果（标点/空格统一压成 _），两个名字可能落到同一个文件，
        // 例如「我的 源」与「我的_源」。不加这层校验就是静默覆盖别人已导入的数据源。
        val existing = runCatching { json.decodeFromString<StoredSource>(target.readText()) }.getOrNull()
        val conflict = existing?.takeIf { it.arguments.name != arguments.name }
        require(conflict == null) {
            "数据源名称「${arguments.name}」与已有「${existing?.arguments?.name}」会存到同一个文件，请改名后重试"
        }
        target.writeText(json.encodeToString(StoredSource.serializer(), stored))
        return stored
    }

    fun setEnabled(name: String, enabled: Boolean): Boolean {
        val current = find(name) ?: return false
        save(current.arguments, enabled)
        return true
    }

    fun delete(name: String): Boolean {
        val target = fileFor(name)
        val stored = runCatching { json.decodeFromString<StoredSource>(target.readText()) }.getOrNull()
            ?: return false
        // 只有内容里的名字确实是目标名字才删，否则会删掉同名消毒后落到同一文件的另一条源
        return stored.arguments.name == name && target.deleteIfExists()
    }

    /** 从 animeko 数据源 JSON 导入 */
    fun importJson(text: String): SelectorSourceArguments {
        val trimmed = text.trim()
        require(trimmed.length <= MAX_CONFIG_CHARS) { "数据源配置过大（上限 $MAX_CONFIG_CHARS 字符）" }
        require(trimmed.startsWith("{")) { "不是合法的 JSON" }
        // 先按标准包装解析，失败则按裸 arguments 解析
        val wrapper = runCatching { json.decodeFromString<SelectorConfigFile>(trimmed) }.getOrNull()
        val args = wrapper?.arguments
            ?: runCatching { json.decodeFromString<SelectorSourceArguments>(trimmed) }.getOrNull()
            ?: throw IllegalArgumentException("无法解析数据源配置：缺少 name 或 searchConfig 字段")
        require(args.name.isNotBlank()) { "数据源配置缺少 name 字段" }
        return args
    }

    /**
     * 导入时就把配置里的正则编译一遍。运行期用的是 `runCatching { Regex(...) }.getOrNull()`，
     * 非法正则会被静默降级成「没匹配到」，用户只会看到「抓不到东西」而无从下手；
     * 长度上限则约束住回溯型正则在长文本上的开销（JVM 正则没有超时可用）。
     */
    private fun validateRegexes(cfg: SelectorSearchConfig) {
        val patterns = listOfNotNull(
            cfg.matchVideo.matchVideoUrl,
            cfg.matchVideo.matchNestedUrl,
            cfg.selectorChannelFormatNoChannel.matchEpisodeSortFromName,
            cfg.selectorChannelFormatFlattened.matchEpisodeSortFromName,
            cfg.selectorChannelFormatFlattened.matchChannelName.takeIf { it.isNotBlank() },
        )
        patterns.forEach { p ->
            require(p.length <= MAX_PATTERN_LENGTH) { "正则过长（${p.length} 字符，上限 $MAX_PATTERN_LENGTH）" }
            runCatching { Regex(p) }.onFailure { throw IllegalArgumentException("正则无效：$p") }
        }
    }

    /** 首次运行时把内置示例数据源拷贝到数据目录 */
    fun seedIfEmpty(seedResourceDir: String = "/selectors") {
        if (list().isNotEmpty()) return
        // 通过 classpath 逐个拷贝（resource 目录无列表 API，这里用已知文件名）
        val seeds = listOf("example-json-site.json", "example-css-site.json")
        seeds.forEach { fileName ->
            val stream = javaClass.getResourceAsStream("$seedResourceDir/$fileName") ?: return@forEach
            val content = stream.bufferedReader().use { it.readText() }
            runCatching { save(importJson(content)) }
        }
    }

    private fun fileFor(name: String): Path {
        val safe = name.map { ch ->
            if (ch.isLetterOrDigit() || ch in "-_.") ch else '_'
        }.joinToString("")
        return dir.resolve("$safe.json")
    }

    private companion object {
        const val MAX_NAME_LENGTH = 100

        /** 单个数据源配置的字符上限：正常 animeko 配置在几千字符量级 */
        const val MAX_CONFIG_CHARS = 200_000

        const val MAX_PATTERN_LENGTH = 500
    }
}
