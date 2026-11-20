package sh.hnet.comfychair.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException
import java.io.OutputStream

/**
 * 写入系统相册（图片 / 视频）的**唯一入口**（P1-22）。
 *
 * 修复前每个调用点都是同一套写法：
 * ```
 * insert(...) → openOutputStream(...)?.use { compress(...) } → 弹「已保存」
 * ```
 * 它有两个真问题：
 * 1. **没有 IS_PENDING**：insert 之后、写完之前，系统相册里已经能看到这条记录，
 *    此刻显示的是 0 字节 / 半张图；写入失败时这条残条还会永久留在相册里。
 * 2. **假成功**：`insert` 返回 null、`openOutputStream` 返回 null、`compress` 返回
 *    false 全都被 `?.let` 静默跳过，代码仍然走到成功提示 —— 用户以为存上了，
 *    实际相册里什么都没有。
 *
 * 现在的语义：先以 `IS_PENDING=1` 插入（相册不可见）→ 写入 → 再把 `IS_PENDING` 置 0
 * 发布；任何一步失败都删除 pending 行并返回 false，调用方必须按 false 给失败提示。
 */
object PhotoManager {

    /** 相册子目录（Pictures/ComfyChair、Movies/ComfyChair） */
    private const val ALBUM_NAME = "ComfyChair"

    /** 生成默认文件名：`ComfyChair_<毫秒时间戳>.<ext>` */
    fun defaultDisplayName(extension: String): String =
        "ComfyChair_${System.currentTimeMillis()}.$extension"

    /**
     * 把位图写入相册（PNG）。
     *
     * @return true = 已发布到相册；false = 失败（未写入或写入失败，且 pending 行已回滚）
     */
    fun saveBitmap(
        context: Context,
        bitmap: Bitmap,
        displayName: String = defaultDisplayName("png")
    ): Boolean {
        val resolver = context.contentResolver
        return PhotoSavePolicy.run(
            insert = {
                resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    pendingImageValues(displayName)
                )
            },
            openStream = { uri -> runCatching { resolver.openOutputStream(uri) }.getOrNull() },
            write = { outputStream ->
                // compress 返回 false = 编码失败，不能当成功（修复前的假成功点之一）
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)) {
                    throw IOException("PNG 编码失败")
                }
            },
            publish = { uri -> check(resolver.update(uri, publishedValues(), null, null) > 0) },
            discard = { uri -> resolver.delete(uri, null, null) }
        )
    }

    /**
     * 把视频字节写入相册（MP4）。
     *
     * @return true = 已发布到相册；false = 失败（含 pending 行回滚）
     */
    fun saveVideoBytes(
        context: Context,
        bytes: ByteArray,
        displayName: String = defaultDisplayName("mp4")
    ): Boolean = saveVideo(context, displayName) { outputStream -> outputStream.write(bytes) }

    /**
     * 以流式写入的方式把视频写进相册（P1-22）——避免把几十 MB 视频整个读进内存。
     *
     * @param write 把数据写进 [OutputStream]；抛异常表示失败（会被回滚）
     * @return true = 已发布到相册；false = 失败（含 pending 行回滚）
     */
    fun saveVideo(
        context: Context,
        displayName: String = defaultDisplayName("mp4"),
        write: (OutputStream) -> Unit
    ): Boolean {
        val resolver = context.contentResolver
        return PhotoSavePolicy.run(
            insert = {
                resolver.insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    pendingVideoValues(displayName)
                )
            },
            openStream = { uri -> runCatching { resolver.openOutputStream(uri) }.getOrNull() },
            write = write,
            publish = { uri -> check(resolver.update(uri, publishedValues(), null, null) > 0) },
            discard = { uri -> resolver.delete(uri, null, null) }
        )
    }

    // ============================================================
    // ContentValues
    // ============================================================

    private fun pendingImageValues(displayName: String) = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + ALBUM_NAME)
        // 写入完成前对相册不可见（Android 10+ 的官方机制）
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }

    private fun pendingVideoValues(displayName: String) = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + ALBUM_NAME)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }

    private fun publishedValues() = ContentValues().apply {
        put(MediaStore.MediaColumns.IS_PENDING, 0)
    }
}

/**
 * 相册写入的原子流程（P1-22 的可测核心）。
 *
 * 与 Android API 解耦（用泛型句柄 + java.io 流），JVM 单测可以直接覆盖三条失败分支：
 * insert 返回 null、打开流失败、写入抛异常 —— 三者都必须返回 false 且**回滚** pending 行。
 *
 * 步骤：
 * 1. `insert` → 句柄（null = 失败，直接返回 false，没有行要回滚）；
 * 2. `openStream` → 流（null/抛异常 = 失败，回滚）；
 * 3. `write` 写入（抛异常 = 失败，回滚）；
 * 4. `publish` 发布（抛异常 = 失败，回滚 —— 否则会留下一条永远 pending 的隐形垃圾条目）。
 */
internal object PhotoSavePolicy {

    fun <T : Any> run(
        insert: () -> T?,
        openStream: (T) -> OutputStream?,
        write: (OutputStream) -> Unit,
        publish: (T) -> Unit,
        discard: (T) -> Unit
    ): Boolean {
        val handle = runCatching { insert() }.getOrNull() ?: return false

        val stream = runCatching { openStream(handle) }.getOrNull()
        if (stream == null) {
            runCatching { discard(handle) }
            return false
        }

        val written = runCatching {
            stream.use { out -> write(out) }
        }.isSuccess
        if (!written) {
            runCatching { discard(handle) }
            return false
        }

        val published = runCatching { publish(handle) }.isSuccess
        if (!published) {
            runCatching { discard(handle) }
            return false
        }
        return true
    }
}