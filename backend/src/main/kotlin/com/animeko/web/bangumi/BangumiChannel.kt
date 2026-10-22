package com.animeko.web.bangumi

import com.animeko.web.appLog
import com.animeko.web.writeFully
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 站长在设置页选定的 Bangumi 数据源通道。
 *
 * 为什么把它做成运行时可改 + 持久化，而不是只留 ANIMEKO_BANGUMI_BASE_URLS：
 * 官方 api.bgm.tv 和社区镜像的能力并不对等 —— 镜像只镜像了搜索/条目/剧集，
 * /v0/calendar（首页每日放送）在镜像上是 404。部署在官方不可达地区的人想要放送表，
 * 就得能明确地把通道切到官方、或贴一个自己的中转地址，而不是被环境变量里那串固定列表绑死、
 * 改一次还要重启容器。四种通道的语义：
 *  - AUTO：用 ANIMEKO_BANGUMI_BASE_URLS（默认官方+镜像，能用的先赢）
 *  - OFFICIAL：只走官方（想要每日放送就得选它，代价是部分地区慢或不通）
 *  - MIRROR：只走社区镜像（境外 VPS 上最快，但没有放送表）
 *  - CUSTOM：只走站长填的地址
 */
@Serializable
enum class BgmChannel { AUTO, OFFICIAL, MIRROR, CUSTOM }

@Serializable
data class BgmChannelConfig(
    val channel: BgmChannel = BgmChannel.AUTO,
    /** 仅 CUSTOM 生效；其余通道忽略这里的内容 */
    val customUrls: List<String> = emptyList(),
)

/**
 * 通道配置的持久化。与 SelectorRepository 同款「临时文件 + fsync + 原子 rename」，
 * 半路被 kill 不会留下一个解析不了的 JSON 把设置页拖崩。
 * 文件坏了（有人手改过）时按 AUTO 处理并在下次保存时覆盖回去 —— 读侧不能因为
 * 一个坏配置就让整个元数据链路挂掉。
 */
class BgmChannelStore(private val file: Path) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun load(): BgmChannelConfig {
        val text = runCatching {
            if (Files.isRegularFile(file)) Files.readString(file) else null
        }.getOrElse {
            appLog.warn("读取 Bangumi 通道配置失败，按自动处理：{}", it.message ?: it::class.simpleName)
            return BgmChannelConfig()
        } ?: return BgmChannelConfig()
        return runCatching { json.decodeFromString<BgmChannelConfig>(text) }
            .onFailure {
                appLog.warn("Bangumi 通道配置不是合法 JSON，按自动处理：{}", it.message ?: it::class.simpleName)
            }
            .getOrDefault(BgmChannelConfig())
    }

    fun save(config: BgmChannelConfig) {
        val text = json.encodeToString(BgmChannelConfig.serializer(), config)
        // 首次保存时目录可能还不存在（AppConfig 的 selectorsDir 会自建，但通道文件就在 dataDir 根下）
        runCatching { file.parent?.let { Files.createDirectories(it) } }
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        FileChannel.open(
            tmp,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
        ).use { ch ->
            ch.writeFully(text.toByteArray(Charsets.UTF_8))
            ch.force(true)
        }
        runCatching { Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            .recoverCatching { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING) }
            .onSuccess {
                // rename 成功之后才需要固定目录项（见 syncDirOf）
                val dir = file.parent
                if (dir != null) runCatching {
                    FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
                }
            }
            .onFailure {
                runCatching { Files.deleteIfExists(tmp) }
                throw IllegalArgumentException("保存 Bangumi 通道配置失败：${it.message ?: it::class.simpleName}")
            }
    }
}
