package com.animeko.web.data

import com.animeko.web.appLog
import com.animeko.web.ioReason
import com.animeko.web.writeFully
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/**
 * 个人数据（收藏 / 历史 / 进度 / 搜索记录）在服务端的单一 JSON 文档。
 *
 * 为什么存服务端：换设备、清浏览器缓存、用隐私模式都不该把收藏弄丢，
 * 而这台机器只服务一个账号，一份文档整体读写比一堆零散接口简单得多。
 * 结构由前端定义，后端只保证「是个 JSON 对象、不超上限、落盘原子」。
 *
 * 整体读写有个必然副作用：两个标签页各持一份副本时，后写的那份会覆盖前一份。
 * 所以这里给出 etag（内容指纹）并支持 If-Match 条件写，让客户端能做
 * 「读到的版本没变才覆盖」，冲突时返回 409 而不是静默吃掉别人的改动。
 */
class UserDataStore(
    private val file: Path,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** 一次读取的结果：text 保证是合法 JSON 对象，etag 是它的指纹。 */
    class Snapshot(val text: String, val etag: String)

    /** If-Match 与当前内容不一致：客户端必须重读再合并，不能直接覆盖。 */
    class Stale : Exception("DATA_STALE")

    /**
     * 文件存在但读不出来（权限、磁盘、路径被目录占用）。这不是「空文档」：
     * 若把它当空文档返回，前端会认为服务器上什么都没有，下一次保存就把原数据覆盖掉。
     * 让它走 5xx，前端保持「未同步」并等待重试。
     */
    class ReadFailed(message: String, cause: Throwable? = null) : Exception(message, cause)

    private class StoredDocument(val bytes: ByteArray, val snapshot: Snapshot)

    /**
     * 一次读取。必须和写入共用同一把锁：坏文件在这里会被移走（*.bad），
     * 而并发的 write() 正在往同一个路径上 rename ——
     * 不加锁时读侧能把写侧刚提交好的文件当坏文件搬走，盘上直接没有文档了。
     */
    fun read(): Snapshot = synchronized(this) { readLocked() }

    private fun readLocked(): Snapshot = readDocumentLocked()?.snapshot ?: EMPTY

    private fun readDocumentLocked(): StoredDocument? {
        val bytes = readBytesStrict(file, "个人数据")
        if (bytes != null && bytes.isNotEmpty()) {
            val text = String(bytes, Charsets.UTF_8)
            val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull()
            if (parsed is JsonObject) return StoredDocument(bytes, Snapshot(text, sha256Hex(bytes)))
            // 先保留损坏文件；隔离失败也不能发布一个伪造的空文档基准。
            try {
                Files.move(file, sib("bad"), StandardCopyOption.REPLACE_EXISTING)
            } catch (e: IOException) {
                throw ReadFailed("损坏的个人数据文件无法隔离：${ioReason(e)}（文件：$file）", e)
            }
            appLog.error("个人数据文件不是合法 JSON，已备份为 *.bad")
        }
        // 主文件缺失、为空或已损坏：先试上一份备份。
        // 这一步不是锦上添花 —— .bak 一直在写却没人读，等于「备了份没人看的档案」：
        // 掉电/中断让主文件只剩半个，读侧就返回 {}，前端把它当空文档，
        // 用户下一次点收藏就把整份数据覆盖掉，而完好的一份就在同一个目录里。
        return restoreFromBackup()
    }

    /**
     * 读取一个「应该存在」的数据文件。
     *
     * 文件不存在返回 null；存在但读不出来必须抛 [ReadFailed]，不能降级成空文档。
     * 这里用 readAttributes 而不是 isRegularFile：后者在 I/O 错误时也只返回 false，
     * 会把「读不了」伪装成「不存在」。
     */
    private fun readBytesStrict(path: Path, label: String): ByteArray? {
        val attrs = try {
            Files.readAttributes(path, BasicFileAttributes::class.java)
        } catch (e: NoSuchFileException) {
            return null
        } catch (e: Exception) {
            throw ReadFailed("$label 读取失败：${e.message ?: e::class.simpleName}", e)
        }
        if (!attrs.isRegularFile) throw ReadFailed("$label 路径不是普通文件：$path")
        return try {
            Files.readAllBytes(path)
        } catch (e: Exception) {
            throw ReadFailed("$label 读取失败：${e.message ?: e::class.simpleName}", e)
        }
    }

    /** 缺失/损坏的备份可降级；存在但无法读取或提交的备份必须保留存储错误。 */
    private fun restoreFromBackup(): StoredDocument? {
        val bak = sib("bak")
        val bytes = readBytesStrict(bak, "个人数据备份")
        if (bytes == null || bytes.isEmpty()) return null
        val text = String(bytes, Charsets.UTF_8)
        val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull()
        if (parsed !is JsonObject) {
            appLog.error("个人数据备份 *.bak 也不是合法 JSON，无法恢复")
            return null
        }
        // 备份不挪走；先提交完整临时文件，成功后才能发布它的 ETag。
        val tmp = sib("tmp")
        try {
            FileChannel.open(
                tmp,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { ch ->
                ch.writeFully(bytes)
                ch.force(true)
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
            syncDirOf(file)
        } catch (e: IOException) {
            runCatching { Files.deleteIfExists(tmp) }
            throw ReadFailed("个人数据备份恢复失败：${ioReason(e)}（文件：$file）", e)
        }
        appLog.warn("个人数据主文档不可用，已从 *.bak 恢复（{} 字节）", bytes.size)
        return StoredDocument(bytes, Snapshot(text, sha256Hex(bytes)))
    }

    /**
     * 整体覆盖写入。非法内容或超限抛 IllegalArgumentException（对外是 400），
     * 版本不符抛 Stale（对外是 409）。返回写入后的快照，客户端据此更新手里的指纹。
     *
     * @param ifMatch 是 **HTTP If-Match 头的原文**，不是单个指纹。按 RFC 7232 解释：
     *  `*` 表示「只要资源已存在就允许写」，`"a", "b"` 表示任一强标签匹配即可，
     *  `W/"a"` 这类弱标签**不参与**强比较（视为不匹配）。
     *  以前是把整串去掉首尾引号后与当前指纹逐字比较，于是 `*` 和「多值里含正确指纹」
     *  这两种合法写法都恒定返回 409 —— 脚本客户端照 HTTP 惯例写就会永远写不进去。
     */
    fun write(text: String, ifMatch: String? = null): Snapshot {
        val parsed = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("个人数据必须是合法的 JSON 对象")
        } ?: throw IllegalArgumentException("个人数据必须是一个 JSON 对象")

        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxBytes) {
            "个人数据超过 ${maxBytes / 1024}KB 上限（当前 ${bytes.size / 1024}KB）"
        }

        synchronized(this) {
            // 条件写必须和写入在同一个临界区里，否则「检查通过」和「覆盖落盘」
            // 之间还能挤进另一个写者，检查就只是装饰
            val current = try {
                readDocumentLocked()
            } catch (e: ReadFailed) {
                // 读取失败属于「这次写入无法建立版本基准」，映射为可诊断的 400 停止重试；
                // 不能让它落到 500，否则前端会把它当瞬时故障每 5 秒重试下去。
                throw IllegalArgumentException("个人数据无法写入：无法读取当前文档，已停止写入：${e.message}", e)
            }
            if (!ifMatchAllows(ifMatch, current?.snapshot?.etag ?: EMPTY.etag)) throw Stale()
            // 先验版本，再比较真实字节；不存在/空文件不能把首次 {} 创建短路掉。
            if (current != null && current.bytes.contentEquals(bytes)) return current.snapshot
            val tmp = sib("tmp")
            try {
                Files.createDirectories(file.parent)
                // 先写临时文件、fsync、再 rename：写入中途被 kill 或被断电都不会留下半个文件。
                // 少了 fsync，rename 只保证元数据进了目录项，掉电后文件可能存在但内容是旧的/全零。
                FileChannel.open(
                    tmp,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                ).use { ch ->
                    ch.writeFully(bytes)
                    ch.force(true)
                }
                // 备份必须是「复制」而不是「挪走」：原来是先 move 走旧文件再 rename 新的，
                // 两次操作之间进程被 kill，userdata.json 就不存在了（只剩 .bak）。
                // 现在读的一侧会认这份 .bak（见 restoreFromBackup），两头合起来才真的不丢数据。
                if (Files.isRegularFile(file)) {
                    runCatching { Files.copy(file, sib("bak"), StandardCopyOption.REPLACE_EXISTING) }
                }
                // 先写临时文件再 rename：写入中途被 kill 也不会留下半个文件
                try {
                    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE)
                } catch (e: AtomicMoveNotSupportedException) {
                    // NFS/CIFS 这类挂载不支持原子 rename；退回普通 move 也比丢掉这次写入好
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
                }
                syncDirOf(file)
            } catch (e: IOException) {
                // 只读挂载/磁盘满这类「写不进去」原来会一路穿到兜底 500 INTERNAL_ERROR，
                // 前端把它当瞬时故障每 5 秒重试一次，永远重试下去；文案里也没有任何线索。
                // 这里换成一条说得清的错误（→ 400），前端把它当不可重试的失败停下。
                runCatching { Files.deleteIfExists(tmp) }
                throw IllegalArgumentException(
                    "个人数据无法写入：${ioReason(e)}（文件：$file）",
                    e,
                )
            }
        }
        return Snapshot(text, sha256Hex(bytes))
    }

    /**
     * 解释 If-Match 头。必须在持有写锁时调用（`*` 要看资源此刻是否存在）。
     * 不带该头 = 不做条件检查，按客户端的明确意图直接覆盖。
     */
    private fun ifMatchAllows(header: String?, currentEtag: String): Boolean {
        val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return true
        if (value == "*") return Files.isRegularFile(file) && runCatching { Files.size(file) > 0L }.getOrDefault(false)
        return value.split(',')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            // 弱标签不参与强比较：W/"x" 与 "x" 在 If-Match 里都不该匹配
            .filterNot { it.startsWith("W/", ignoreCase = true) }
            .map { it.trim('"') }
            .any { it == currentEtag }
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

    private fun sib(suffix: String) = file.resolveSibling("${file.fileName}.$suffix")

    private companion object {
        private const val DEFAULT_MAX_BYTES = 512L * 1024

        /** 不存在/空文档的统一指纹：客户端首次写入的条件检查要用它 */
        private val EMPTY = Snapshot("{}", sha256Hex("{}".toByteArray(Charsets.UTF_8)))
    }
}

/** etag 用内容指纹而不是版本号：不需要额外存状态，重启后一样能比对。 */
private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(32)
