package com.animeko.web

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException

/**
 * 把写入失败翻译成一句人能读懂的原因。
 *
 * JDK 的 `FileSystemException.message` 往往**只有一个路径**，而 `reason` 在
 * `AccessDeniedException(file)` 这种构造下又是 null —— 运维看到的报错是
 * 「无法写入：/data/userdata.json.tmp」，看不出是权限、是目录不存在、还是磁盘满。
 * 按异常类型映射成明确说法（实测：nobody + 只读 /data → 没有写权限）。
 */
internal fun ioReason(e: IOException): String = when (e) {
    is AccessDeniedException -> "没有写权限（Permission denied）"
    is NoSuchFileException -> "目标或父目录不存在"
    is FileSystemException -> e.reason ?: e.message ?: "文件系统错误"
    else -> e.message ?: e::class.simpleName ?: "未知错误"
}
