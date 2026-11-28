package sh.hnet.comfychair.util

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 文本文件的原子写：`.tmp` → flush + fsync → 原子改名覆盖目标。
 *
 * 为什么统一到这里：写一半被杀（或写到一半磁盘满）会留下半截文本，下次读到就是坏数据。
 * 项目里原本有三处各写一遍同样逻辑（图库索引 / object_info 缓存 / 崩溃日志），
 * 模型介绍覆盖层与 Markdown 导出也要用——抽成一处，避免再复制第四份。
 *
 * 写入序列：
 * 1. 同目录写 `<目标>.tmp`，flush 后 `fd.sync()`（把页缓存刷盘，否则"改名成功但数据还在
 *    缓存里"同样会丢）；
 * 2. `Files.move(..., ATOMIC_MOVE)` 原子覆盖；
 * 3. 文件系统不支持 ATOMIC_MOVE（或目标被占用）时退化为「先删目标再改名」，
 *    仍失败则整份拷贝兜底——无论如何都不留半截文件。
 */
object AtomicFileWriter {

    /** 临时文件后缀（与目标同目录，rename 才是同一文件系统内的原子操作）。 */
    private const val TMP_SUFFIX = ".tmp"

    fun writeText(file: File, content: String) {
        writeBytes(file, content.toByteArray(Charsets.UTF_8))
    }

    /**
     * 二进制文件的原子写（写入序列与 [writeText] 一致）：`.tmp` → flush + fsync → 原子改名。
     * B5 资产下发按字节落盘（清单校验的是字节 SHA），文本入口保留为编码转换的便捷层。
     * 父目录不存在时自动创建（B5 的 overlay/<dirName>/ 两层目录首次落盘时可能都缺）。
     */
    fun writeBytes(file: File, content: ByteArray) {
        file.parentFile?.let { parent -> if (!parent.isDirectory) parent.mkdirs() }
        val tmp = File(file.parentFile, file.name + TMP_SUFFIX)
        FileOutputStream(tmp).use { out ->
            out.write(content)
            out.flush()
            out.fd.sync()
        }
        try {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (e: Exception) {
            // 个别文件系统不支持 ATOMIC_MOVE（或目标被占用）：退化为"先删目标再改名"，
            // 至少不会留下半截文件；仍失败则整份拷贝兜底
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
            }
        }
    }
}