package sh.hnet.comfychair.util

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * 上传前的图片编码器（v4.6.1，模块 D）。
 *
 * 背景：图生图上传 3.78 MB 的 PNG 要 20–30 秒，原因是 PNG 无损 + 内嵌 tEXt 元数据。
 * ComfyUI 的 `LoadImage` 用 PIL 解码，**支持 WebP**，所以上传前转成 WebP 可以：
 * - 保持原始分辨率（不做任何缩放，`Bitmap` 按原尺寸编码）；
 * - 剥离 PNG 内嵌的 tEXt/参数块（重编码天然丢弃）；
 * - 体积通常降到原来的 10%–30%。
 *
 * 两条硬约束：
 * 1. **修复模式（inpainting）必须保留 alpha** —— 合成图把遮罩写进了 alpha 通道，
 *    用有损编码会把 alpha 压坏，必须用 [Bitmap.CompressFormat.WEBP_LOSSLESS]；
 * 2. 其余场景用有损质量 92，视觉上接近无损（需求要求"严禁 Downscaling、肉眼无差"）。
 */
object UploadImageEncoder {

    /** 有损 WebP 质量（0–100）；92 在体积与画质之间取平衡 */
    const val WEBP_QUALITY = 92

    /** 上传文件名扩展名 */
    const val EXTENSION = "webp"

    /** 上传 MIME */
    const val MIME = "image/webp"

    /**
     * 编码为 WebP 字节。
     *
     * @param preserveAlpha 需要保留 alpha 通道时用无损（修复模式的遮罩合成图）
     * @return 编码失败（compress 返回 false）时返回 null——空字节不能推进上传链路
     */
    fun encode(bitmap: Bitmap, preserveAlpha: Boolean): ByteArray? {
        val outputStream = ByteArrayOutputStream()
        val compressed = if (preserveAlpha) {
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, outputStream)
        } else {
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, WEBP_QUALITY, outputStream)
        }
        if (!compressed) return null
        return outputStream.toByteArray()
    }
}
