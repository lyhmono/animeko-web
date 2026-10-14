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
        val stored = StoredSource(enabled = enabled, arguments = arguments)
        fileFor(arguments.name).writeText(json.encodeToString(StoredSource.serializer(), stored))
        return stored
    }

    fun setEnabled(name: String, enabled: Boolean): Boolean {
        val current = find(name) ?: return false
        save(current.arguments, enabled)
        return true
    }

    fun delete(name: String): Boolean = fileFor(name).deleteIfExists()

    /** 从 animeko 数据源 JSON 导入 */
    fun importJson(text: String): SelectorSourceArguments {
        val trimmed = text.trim()
        require(trimmed.startsWith("{")) { "不是合法的 JSON" }
        // 先按标准包装解析，失败则按裸 arguments 解析
        val wrapper = runCatching { json.decodeFromString<SelectorConfigFile>(trimmed) }.getOrNull()
        val args = wrapper?.arguments
            ?: runCatching { json.decodeFromString<SelectorSourceArguments>(trimmed) }.getOrNull()
            ?: throw IllegalArgumentException("无法解析数据源配置：缺少 name 或 searchConfig 字段")
        require(args.name.isNotBlank()) { "数据源配置缺少 name 字段" }
        return args
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
}
