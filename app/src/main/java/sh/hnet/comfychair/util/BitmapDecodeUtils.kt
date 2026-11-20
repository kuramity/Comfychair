package sh.hnet.comfychair.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 用户选图（相册）的解码工具。
 *
 * 相册原图动辄 8000×6000，ARGB_8888 下就是 **192MB** —— 直接全幅解码会让低内存设备
 * 立刻 OOM（而且这张图还会进内存缓存、再被压缩上传一份）。这里按最长边上限采样，
 * 把超大输入压到合理尺寸。
 *
 * 上限取得比较高（[MAX_USER_IMAGE_DIMENSION] = 4096）是刻意的：常见手机照片
 * （4000×3000 及以下）**完全不受影响**，只有超出这个尺寸的输入才会按 2 的幂降采样，
 * 而这类输入对工作流本身没有意义（生成目标普遍在 1024~2048）。
 *
 * 走文件描述符而不是 InputStream：这里需要"先测尺寸、再按采样率解码"两次读取，
 * 而照片选择器给的流不一定可重复读（见 LoginBackgroundStore 的同款处理）。
 */
object BitmapDecodeUtils {

    private const val TAG = "BitmapDecodeUtils"

    /** 用户选图的最长边上限，超过则按 2 的幂采样。 */
    const val MAX_USER_IMAGE_DIMENSION = 4096

    /**
     * 从相册 URI 解码位图并按最长边上限采样。
     *
     * @return 解码成功返回位图；URI 读不到或不是图片时返回 null（调用方按原逻辑提示失败）
     */
    suspend fun decodeSampledFromUri(
        context: Context,
        uri: Uri,
        maxDimension: Int = MAX_USER_IMAGE_DIMENSION
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            // 先解码，再按 EXIF 方向摆正（P1-26）：手机相册里的照片几乎都是
            // "像素躺着 + EXIF 记 90°"，不读 EXIF 会把源图转错。
            val decoded = context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    Log.w(TAG, "选图尺寸无效，放弃解码")
                    return@withContext null
                }

                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(maxOf(bounds.outWidth, bounds.outHeight), maxDimension)
                }
                BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, options)
            }

            if (decoded == null) {
                null
            } else {
                val orientation = runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        ExifOrientation.readOrientation(it)
                    } ?: android.media.ExifInterface.ORIENTATION_NORMAL
                }.getOrDefault(android.media.ExifInterface.ORIENTATION_NORMAL)
                ExifOrientation.applyToBitmap(decoded, orientation)
            }
        } catch (e: Exception) {
            Log.w(TAG, "解码用户选图失败", e)
            null
        }
    }

    /** 采样率必须是 2 的幂：循环到解码后最长边不超过上限为止。 */
    fun sampleSizeFor(longestSide: Int, maxDimension: Int): Int {
        var sample = 1
        while (longestSide / sample > maxDimension) sample *= 2
        return sample
    }
}
