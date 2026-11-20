package sh.hnet.comfychair.util

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * 图片 EXIF 方向处理（P1-26）。
 *
 * 背景：`BitmapFactory` **不读** EXIF 的 Orientation 标签 —— 它只按像素原始顺序解码。
 * 手机拍的 JPEG、以及部分相机/修图工具导出的 JPEG 会把"应该转 90°"写在 EXIF 里，
 * 解码后是一张躺倒的图；本仓库此前全仓零处理，这类图在查看器与图库里方向都是错的。
 *
 * 处理策略：
 * - 只在**确实是 JPEG**（SOI 标记 `FF D8`）时才去解析 EXIF —— PNG/WebP 走同一个入口
 *   也不额外付一次 IO；
 * - 覆盖全部 8 种方向（含左右/上下镜像与转置），因为相机前置摄像头常用镜像方向；
 * - 无法解析（数据损坏、非 JPEG）时原样返回，绝不让方向处理把图弄丢。
 */
object ExifOrientation {

    private const val TAG = "ExifOrientation"

    /** JPEG 文件头（SOI） */
    private fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 2 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte()

    /**
     * 生成"把图片摆正"的变换矩阵；方向为正常/无法识别时返回 null（不需要变换）。
     *
     * 8 种 EXIF 方向的映射与 AOSP 常见实现一致：
     * 2=水平镜像、3=旋转180、4=垂直镜像、5=转置、6=旋转90、7=横向转置、8=旋转270。
     */
    fun matrixForOrientation(orientation: Int): Matrix? = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Matrix().apply { setScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_180 -> Matrix().apply { setRotate(180f) }
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> Matrix().apply { setScale(1f, -1f) }
        ExifInterface.ORIENTATION_TRANSPOSE -> Matrix().apply {
            setRotate(90f)
            postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> Matrix().apply { setRotate(90f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> Matrix().apply {
            setRotate(-90f)
            postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> Matrix().apply { setRotate(-90f) }
        else -> null
    }

    /** 该方向是否会把宽高互换（90° / 270° / 转置） */
    fun swapsDimensions(orientation: Int): Boolean = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
        orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
        orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
        orientation == ExifInterface.ORIENTATION_TRANSVERSE

    /**
     * 按 EXIF 方向把位图摆正。
     *
     * @param recycleSource 变换产生新位图时是否回收传入的那张。
     *   默认 true：调用方传的都是**刚解码出来**的临时位图，旋转会短暂让内存翻倍
     *   （4096² 图约 64MB → 128MB），不回收容易在大图上触发 OOM。
     *   若要保留原图，显式传 false。
     * @return 方向正常时**原样返回同一个对象**（不复制、不多占内存）；
     *         需要变换时返回新位图
     */
    fun applyToBitmap(bitmap: Bitmap, orientation: Int, recycleSource: Boolean = true): Bitmap {
        val matrix = matrixForOrientation(orientation) ?: return bitmap
        return try {
            val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (transformed !== bitmap && recycleSource && !bitmap.isRecycled) {
                bitmap.recycle()
            }
            transformed
        } catch (e: Exception) {
            Log.w(TAG, "应用 EXIF 方向失败，保持原图", e)
            bitmap
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "应用 EXIF 方向 OOM，保持原图")
            bitmap
        }
    }

    /** 从字节里读 EXIF 方向（非 JPEG 或解析失败返回 [ExifInterface.ORIENTATION_NORMAL]） */
    fun readOrientation(bytes: ByteArray): Int {
        if (!isJpeg(bytes)) return ExifInterface.ORIENTATION_NORMAL
        return try {
            ByteArrayInputStream(bytes).use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    /** 从输入流读 EXIF 方向（调用方负责关闭流） */
    fun readOrientation(stream: InputStream): Int = try {
        ExifInterface(stream).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /** 对刚按字节解码出来的位图应用 EXIF 方向（网络下载链路用） */
    fun applyFromEncodedBytes(bytes: ByteArray, bitmap: Bitmap): Bitmap {
        val orientation = readOrientation(bytes)
        return applyToBitmap(bitmap, orientation)
    }

    /** 对刚按文件解码出来的位图应用 EXIF 方向（磁盘缓存链路用） */
    fun applyFromFile(file: File, bitmap: Bitmap): Bitmap {
        val orientation = try {
            FileInputStream(file).use { readOrientation(it) }
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        return applyToBitmap(bitmap, orientation)
    }
}