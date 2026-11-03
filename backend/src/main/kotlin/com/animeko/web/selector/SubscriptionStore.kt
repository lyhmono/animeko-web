package com.animeko.web.selector

import com.animeko.web.StorageFailure
import com.animeko.web.writeFully
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

@Serializable
data class SubscriptionEntry(
    val url: String,
    val addedAt: Long,
    val lastRefreshAt: Long = 0,
    val lastError: String? = null,
)

/**
 * 订阅地址的持久化清单（data/subscriptions.json）。
 * 自动刷新只遍历它；数据源配置本身仍在 selectors/ 下，由 SelectorRepository 管。
 */
class SubscriptionStore(private val file: Path) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Synchronized
    fun list(): List<SubscriptionEntry> = read()

    @Synchronized
    fun register(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        val entries = read().toMutableList()
        if (entries.any { it.url == trimmed }) return
        entries += SubscriptionEntry(url = trimmed, addedAt = System.currentTimeMillis())
        write(entries)
    }

    @Synchronized
    fun markResult(url: String, error: String?) {
        val entries = read().toMutableList()
        val idx = entries.indexOfFirst { it.url == url }
        if (idx < 0) return
        entries[idx] = entries[idx].copy(lastRefreshAt = System.currentTimeMillis(), lastError = error)
        write(entries)
    }

    private fun read(): List<SubscriptionEntry> {
        if (!Files.isRegularFile(file)) return emptyList()
        return runCatching {
            json.decodeFromString<List<SubscriptionEntry>>(Files.readString(file))
        }.getOrElse { emptyList() }
    }

    private fun write(entries: List<SubscriptionEntry>) {
        file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        try {
            FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { ch ->
                ch.writeFully(json.encodeToString(ListSerializer(SubscriptionEntry.serializer()), entries).toByteArray(Charsets.UTF_8))
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(tmp) }
            throw StorageFailure("订阅清单无法写入：${e.message}", e)
        }
    }
}