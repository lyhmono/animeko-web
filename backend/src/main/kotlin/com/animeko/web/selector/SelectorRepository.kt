package com.animeko.web.selector

import com.animeko.web.appLog
import com.animeko.web.ioReason
import com.animeko.web.writeFully
import java.nio.file.InvalidPathException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
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

    /** 解析缓存：数据源目录不常变，而 list()/find() 在每条采集请求的热路径上 */
    private class Cached(val mtime: Long, val source: StoredSource)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Cached>()

    init {
        // 数据目录可能是只读挂载（NAS/NFS root-squash、编排层挂错权限）。这时服务仍应能起来：
        // 浏览、搜索、播放都不需要写这个目录，入口脚本也明确承诺「服务继续运行，给只读浏览
        // 留活路」。原先这里直接 createDirectories，AccessDeniedException 会让进程启动即崩
        // （实测：nobody + 只读 /data → ClassNotFound 之后紧接着的 AccessDeniedException），
        // 容器于是进入 restart 循环，连登录页都打不开 —— 比「不能导入数据源」严重得多。
        runCatching {
            if (!dir.exists()) dir.createDirectories()
        }.onFailure {
            appLog.error(
                "数据源目录不可用（只读挂载或权限不对）：{}。数据源导入/编辑会失败，浏览与播放不受影响：{}",
                dir, it.message ?: it::class.simpleName,
            )
        }
    }

    fun list(): List<StoredSource> {
        if (!dir.exists()) return emptyList()
        return Files.list(dir).use { stream ->
            stream.filter { it.extension == "json" }
                .map { path -> loadCached(path) }
                .toList()
                .filterNotNull()
                .sortedBy { it.arguments.name }
        }
    }

    /**
     * 按 mtime 缓存解析结果。save/delete 会显式失效缓存条目 —— 不能只靠 mtime：
     * getLastModifiedTime 截到毫秒，同一毫秒内的两次写入会被误判成「没变」。
     *
     * 坏文件必须**出声**。读侧对解析失败是跳过（这一步是对的：一个坏文件不该把
     * 整个服务带走），但原先一声不吭，于是排查变成死胡同：用户看到的是
     * 「这个源自己消失了」，列表里少一个，而没有任何地方说过为什么。
     * 同一个文件名只记一次（修好之前不刷屏）：这条路径在每个采集请求上都会走。
     */
    private fun loadCached(path: Path): StoredSource? {
        val key = path.fileName.toString()
        val mtime = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrNull() ?: 0L
        cache[key]?.let { if (it.mtime == mtime) return it.source }
        val parsed = runCatching { json.decodeFromString<StoredSource>(path.readText()) }
        val source = parsed.getOrElse { e ->
            if (loggedCorrupt.add(key)) {
                appLog.warn(
                    "数据源文件解析失败，这个源不会出现在列表里：{}（{}）；改回合法 JSON 或重新导入即恢复",
                    path, e.message ?: e::class.simpleName,
                )
            }
            return null
        }
        loggedCorrupt.remove(key)
        cache[key] = Cached(mtime, source)
        return source
    }

    private val loggedCorrupt = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun invalidate(file: Path) {
        cache.remove(file.fileName.toString())
    }

    /**
     * 按名字找源。enabledOnly=true 时停用的源视为不存在 ——
     * 「停用」必须在服务端生效，只靠前端过滤等于任何直接调 API 的人都能用。
     */
    fun find(name: String, enabledOnly: Boolean = false): StoredSource? =
        list().firstOrNull { it.arguments.name == name && (!enabledOnly || it.enabled) }

    fun save(arguments: SelectorSourceArguments, enabled: Boolean = true): StoredSource = synchronized(this) {
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
        writeAtomic(target, json.encodeToString(StoredSource.serializer(), stored))
        invalidate(target)
        stored
    }

    /**
     * 整目录串行 + 落盘原子化。两处各挡一种事故：
     *  - 上面的「同名消毒冲突」检查是 read-then-write，并发导入时两个请求都能通过检查，
     *    后写的把前写的静默盖掉；Ktor 的路由处理跑在线程池上，这真的会并发。
     *  - writeText 中途被 kill／断电会留下半个 JSON。读侧对坏文件是跳过，
     *    于是那个数据源就这么「自己消失了」，没有任何报错。
     * 写法与 UserDataStore 一致（临时文件 + fsync + rename），不另立一套机制。
     */
    private fun writeAtomic(target: Path, text: String) {
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        try {
            FileChannel.open(
                tmp,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { ch ->
                ch.writeFully(text.toByteArray(Charsets.UTF_8))
                ch.force(true)
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
                syncDirOf(target)
            } catch (e: AtomicMoveNotSupportedException) {
                // NFS/CIFS 这类挂载不支持原子 rename（/data 挂网络盘并不罕见）。
                // 退回普通 move 仍比丢掉这次写入好，但**失败必须照抛**：
                // 这里以前是 runCatching{…}.onFailure{删临时文件}，异常被吞掉，
                // 于是 save() 返回成功、界面报「导入完成」，而文件根本没写进去。
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
                    syncDirOf(target)
                } catch (e2: IOException) {
                    runCatching { Files.deleteIfExists(tmp) }
                    throw IllegalArgumentException("写入数据源文件失败：${ioReason(e2)}", e2)
                }
            }
        } catch (e: IOException) {
            runCatching { Files.deleteIfExists(tmp) }
            throw IllegalArgumentException("写入数据源文件失败：${ioReason(e)}", e)
        }
    }

    fun setEnabled(name: String, enabled: Boolean): Boolean {
        val current = find(name) ?: return false
        save(current.arguments, enabled)
        return true
    }

    fun delete(name: String): Boolean = synchronized(this) {
        val target = fileFor(name)
        val stored = runCatching { json.decodeFromString<StoredSource>(target.readText()) }.getOrNull()
            ?: return false
        // 只有内容里的名字确实是目标名字才删，否则会删掉同名消毒后落到同一文件的另一条源
        val removed = stored.arguments.name == name && target.deleteIfExists()
        invalidate(target)
        return removed
    }

    /** 从 animeko 数据源 JSON 导入 */
    fun importJson(text: String): SelectorSourceArguments {
        val trimmed = text.trim()
        require(trimmed.length <= MAX_CONFIG_CHARS) { "数据源配置过大（上限 $MAX_CONFIG_CHARS 字符）" }
        require(trimmed.startsWith("{")) { "不是合法的 JSON" }
        // 先按标准包装解析，失败则按裸 arguments 解析
        val wrapper = runCatching { json.decodeFromString<SelectorConfigFile>(trimmed) }
        val bare = runCatching { json.decodeFromString<SelectorSourceArguments>(trimmed) }
        val args = wrapper.getOrNull()?.arguments ?: bare.getOrNull()
            ?: throw IllegalArgumentException(
                "无法解析数据源配置：需要 {factoryId, version, arguments} 包装或裸 arguments 对象" +
                    "（含 name 与 searchConfig）。解析错误：" +
                    listOfNotNull(
                        wrapper.exceptionOrNull()?.message?.take(200),
                        bare.exceptionOrNull()?.message?.take(200),
                    ).joinToString(" / "),
            )
        require(args.name.isNotBlank()) { "数据源配置缺少 name 字段" }
        // 包装层里的 factoryId/version 不是装饰：社区订阅同时打包了 web 源与 BT/PikPak 源，
        // 而本服务只会按「抓网页 + 正则」的方式解析。之前解包装时丢掉 factoryId 的结果是
        // 一份 bt 配置被当成 web 源存下来 —— 列表里看着正常，搜索永远 0 结果，
        // 而且再也看不出它原本不是这个类型。
        wrapper.getOrNull()?.let { file ->
            val factory = file.factoryId.trim()
            if (factory.isNotEmpty() && !factory.equals("web-selector", ignoreCase = true)) {
                throw IllegalArgumentException(
                    "数据源 factoryId 是「$factory」，本服务只支持 web-selector（在线抓页型），BT / PikPak 源不支持",
                )
            }
            if (file.version > 2) {
                appLog.warn("数据源「{}」的 version={} 比本项目已验证的 2 更新，字段可能被忽略", args.name, file.version)
            }
        }
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

    private fun fileFor(name: String): Path {
        val safe = name.map { ch ->
            if (ch.isLetterOrDigit() || ch in "-_.") ch else '_'
        }.joinToString("")
        // 文件系统的上限是**字节**（ext4/overlayfs 实测 NAME_MAX=255），而名字长度限的是字符。
        // 100 个汉字符是 300 字节：这类源名会在落盘那一步直接 ENAMETOOLONG，
        // 而报错来自 FileChannel.open，用户看到的是一句「写入数据源文件失败」——
        // 看不出是名字的问题，更没有「短多少才够」的说法（实测导入中文长名订阅必踩）。
        // 所以在碰磁盘之前先按字节判一次，并把差额说清楚。
        val fileName = "$safe.json"
        // 上限要对**临时文件**生效：writeAtomic 先写 `<名字>.json.tmp`（比正式名长
        // 4 字节）再 rename。只按正式名判的话，正好 252~255 字节的名字能通过这里，
        // 却在打开临时文件时 ENAMETOOLONG —— 用户看到的又是那句「写入失败」，
        // 与这段校验本来要消灭的困惑一模一样。
        val tmpName = "$fileName.tmp"
        val bytes = tmpName.toByteArray(Charsets.UTF_8).size
        require(bytes <= MAX_FILE_NAME_BYTES) {
            "数据源名称「$name」转成临时文件名后是 $bytes 字节，超过文件系统单名 ${MAX_FILE_NAME_BYTES} 字节的限制" +
                "（汉字按 3 字节算）：请改成不超过约 ${(MAX_FILE_NAME_BYTES - 9) / 3} 个汉字长度的名字，" +
                "或在导入前先缩短"
        }
        return runCatching { dir.resolve(fileName) }.getOrElse {
            // LANG 未设置时 sun.jnu.encoding 会是 ANSI_X3.4-1968，
            // 中文源名在这一步就抛 InvalidPathException：现象是「导入总是失败」，
            // 而原始报错（Malformed input or input contains unmappable characters）没人看得懂
            throw IllegalArgumentException(fileNameEncodingHint(name), it)
        }
    }

    private fun fileNameEncodingHint(name: String): String {
        val enc = System.getProperty("sun.jnu.encoding") ?: "未知"
        return "数据源名「$name」无法作为文件名写入：当前 JVM 的文件名字符集是 $enc，" +
            "不含中文。请给进程设置 LANG=C.UTF-8 与 LC_ALL=C.UTF-8 后重启（Docker 镜像已内置）。"
    }


    /**
     * rename 之后 fsync **父目录**：目录项才是「新文件到底在不在」的那份记录。
     * 只 force 文件本身的话，掉电后可能出现「文件内容在、目录里没有这个条目」，
     * 于是下一次启动看不到这个数据源（实测本机 JDK21/Linux：FileChannel.open(dir, READ)
     * 可以用，WRITE 会报 Is a directory）。
     * 失败不致命（某些挂载点/平台不支持），静默跳过：那不比现在更差。
     */
    private fun syncDirOf(target: Path) {
        val dir = target.parent ?: return
        runCatching {
            FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
        }
    }

    private companion object {
        const val MAX_NAME_LENGTH = 100

        /** 单个数据源配置的字符上限：正常 animeko 配置在几千字符量级 */
        const val MAX_CONFIG_CHARS = 200_000

        const val MAX_PATTERN_LENGTH = 500

        /**
         * 落盘文件名的字节上限（ext4/overlayfs 的 NAME_MAX，实测本机为 255）。
         * 留了余量：临时文件叫 `<名字>.json.tmp`，比正式名还长 4 字节。
         */
        private const val MAX_FILE_NAME_BYTES = 255
    }
}
