package sh.hnet.comfychair.util

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream

/**
 * 对外分享载荷的统一落盘目录（P2-12）。
 *
 * 背景：`file_paths.xml` 原先把整个 `cacheDir` 与 `filesDir` 都声明成 FileProvider 可共享根
 * （`path="."`）—— 任何一次分享都会把一个「整个缓存/整个文件目录」的根挂到 content URI
 * 体系里，根目录下的工作流、图库索引、播放临时文件同样落在可寻址范围内。
 *
 * 现在 FileProvider 只声明 `cacheDir/share/` 这一个专用子目录：
 * - **分享载荷**（图片/视频副本）只能写这里，且只能从这里取 content URI（[uriFor]）；
 * - 播放临时文件不再经过 FileProvider（播放方在进程内，`file://` 足够），
 *   因此也不需要为它声明任何可共享根。
 *
 * 目录里的载荷是短命文件：每次取用顺手清掉 1 小时前的旧副本，避免视频副本长期占空间。
 */
object ShareFileStore {

    /** cacheDir 下的分享子目录名（必须与 res/xml/file_paths.xml 的 cache-path 一致） */
    const val DIR_NAME = "share"

    /** 分享副本的保留时长：超过即视为上一轮遗留，可以清掉 */
    private const val STALE_MS = 60 * 60 * 1000L

    /** 分享目录（cacheDir/share），不存在时创建 */
    fun dir(context: Context): File = File(context.cacheDir, DIR_NAME).apply { mkdirs() }

    /**
     * 取一个分享文件（写进 share/ 专用目录）。
     *
     * 注意：**不要**在每次取文件时删同目录其它文件 —— 多选分享会先连续生成多个载荷
     * 再一次性发出（见 GalleryViewModel 的 shareItems），提前清理会把还未发送的文件删掉。
     * 只按 [STALE_MS] 清理上一轮的遗留。
     */
    fun file(context: Context, name: String): File {
        val dir = dir(context)
        cleanupStale(dir)
        return File(dir, name)
    }

    /** 分享文件的 content URI（FileProvider 只声明了 share/，路径越界会抛异常） */
    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /**
     * 把视频 URI 变成可以交给其它应用的 content URI。
     *
     * 播放链路现在给的是 `file://`（P2-12：播放不再依赖 FileProvider），
     * 而系统分享要求 `content://`（`file://` 会在 Intent 离开进程时抛 FileUriExposedException）。
     * 这里按需复制一份到分享目录，再返回它的 content URI。
     *
     * @return content:// 原样返回；file:// 复制成功后返回副本 URI；其它情况返回 null
     */
    fun exportVideoForShare(context: Context, source: Uri): Uri? {
        if (source.scheme == "content") return source
        if (source.scheme != "file") return null
        val path = source.path ?: return null
        val src = File(path)
        if (!src.isFile) return null
        return try {
            val target = file(context, "share_video_${System.currentTimeMillis()}.mp4")
            src.copyTo(target, overwrite = true)
            uriFor(context, target)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 打开媒体来源的输入流：`content://` 走 ContentResolver，`file://` 直接读文件。
     *
     * 播放 URI 改成 file:// 之后，`contentResolver.openInputStream(file://...)` 会抛
     * FileNotFoundException —— 保存到相册等链路必须走这里。
     */
    fun openMediaInput(context: Context, uri: Uri): InputStream? =
        if (uri.scheme == "file") {
            uri.path?.let { path -> File(path).takeIf { it.isFile }?.inputStream() }
        } else {
            context.contentResolver.openInputStream(uri)
        }

    /** 清掉上一轮遗留的分享副本（尽力而为，不影响本次分享） */
    private fun cleanupStale(dir: File) {
        val cutoff = System.currentTimeMillis() - STALE_MS
        try {
            dir.listFiles()?.forEach { stale ->
                if (stale.isFile && stale.lastModified() < cutoff) {
                    runCatching { stale.delete() }
                }
            }
        } catch (_: Exception) {
            // 目录不可读：跳过清理，绝不挡住本次分享
        }
    }
}