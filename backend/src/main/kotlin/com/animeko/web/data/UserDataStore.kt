package com.animeko.web.data

import com.animeko.web.appLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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

    /** 文件不存在或内容坏掉时返回空文档，让前端从干净状态继续，而不是 500。 */
    fun read(): Snapshot {
        val bytes = try {
            if (!Files.isRegularFile(file)) return empty()
            Files.readAllBytes(file)
        } catch (e: Exception) {
            appLog.warn("读取个人数据失败: {}", e.message)
            return empty()
        }
        if (bytes.size == 0) return empty()
        return try {
            val parsed = json.parseToJsonElement(String(bytes, Charsets.UTF_8))
            if (parsed is JsonObject) Snapshot(String(bytes, Charsets.UTF_8), sha256Hex(bytes))
            else empty()
        } catch (e: Exception) {
            // 坏文件不能连带把整个页面打挂，也不能被下一次写入静默覆盖前先备份
            appLog.error("个人数据文件不是合法 JSON，已备份为 *.bad：{}", e.message)
            runCatching { Files.move(file, sib("bad"), StandardCopyOption.REPLACE_EXISTING) }
            empty()
        }
    }

    /**
     * 整体覆盖写入。非法内容或超限抛 IllegalArgumentException（对外是 400），
     * 版本不符抛 Stale（对外是 409）。返回写入后的快照，客户端据此更新手里的指纹。
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
            val current = currentEtagLocked()
            if (ifMatch != null && ifMatch != current) throw Stale()
            Files.createDirectories(file.parent)
            val tmp = sib("tmp")
            Files.newOutputStream(tmp).use { it.write(bytes) }
            // 备份必须是「复制」而不是「挪走」：原来是先 move 走旧文件再 rename 新的，
            // 两次操作之间进程被 kill，userdata.json 就不存在了（只剩 .bak），
            // 而读的一侧不看 .bak —— 等于一次崩溃抹掉全部收藏。
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
        }
        return Snapshot(text, sha256Hex(bytes))
    }

    private fun currentEtagLocked(): String {
        val bytes = if (Files.isRegularFile(file)) runCatching { Files.readAllBytes(file) }.getOrNull() else null
        return if (bytes == null || bytes.isEmpty()) EMPTY.etag else sha256Hex(bytes)
    }

    private fun empty(): Snapshot = EMPTY

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
