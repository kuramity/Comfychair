package sh.hnet.comfychair.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 连接页背景照片的存取。
 *
 * 用户选完照片后**立刻重编码**存进私有目录（而不是只记住一个 content:// URI）：
 * 相册原图可能被删、被移动，授权也可能在重启后失效；重编码还能把动辄几十 MP 的
 * 原图压到屏幕够用的尺寸，避免连接页每次进来都解码一张巨图。
 */
object LoginBackgroundStore {

    private const val TAG = "LoginBackground"

    private const val FILE_NAME = "login_background.jpg"

    /** 重编码后的最大边长（连接页全屏铺满，2K 足够且解码快） */
    private const val MAX_DIMENSION = 2048

    private const val JPEG_QUALITY = 90

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun hasBackground(context: Context): Boolean = file(context).exists()

    /**
     * 把选中的照片保存为背景（IO 线程）。
     *
     * @return 成功 true；解码失败（不是图片 / 读取被拒）返回 false
     */
    suspend fun save(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            // 走文件描述符而不是 InputStream：这里需要"先测尺寸、再按采样率解码"两次读取，
            // 而照片选择器（Photo Picker）给的流不一定可重复读取，用流会让第二次解码拿到空数据
            // （2026-09-20 实测：用 openInputStream 时保存静默失败，只弹"读不出来"）
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    Log.w(TAG, "背景图尺寸无效，放弃保存")
                    return@withContext false
                }

                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(maxOf(bounds.outWidth, bounds.outHeight))
                }
                val bitmap = BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, options)
                    ?: return@withContext false

                file(context).outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }
                bitmap.recycle()
                true
            } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "背景图保存失败", e)
            false
        }
    }

    /** 读取背景图（IO 线程）。文件已按 [MAX_DIMENSION] 压过，直接解码即可。 */
    suspend fun load(context: Context): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val target = file(context)
            if (!target.exists()) null else BitmapFactory.decodeFile(target.absolutePath)
        } catch (e: Exception) {
            null
        }
    }

    /** 清除背景（恢复默认外观）。 */
    fun clear(context: Context) {
        file(context).delete()
    }

    /** 采样率必须是 2 的幂：循环到解码后最长边不超过上限为止。 */
    private fun sampleSizeFor(longestSide: Int): Int {
        var sample = 1
        while (longestSide / sample > MAX_DIMENSION) sample *= 2
        return sample
    }
}
