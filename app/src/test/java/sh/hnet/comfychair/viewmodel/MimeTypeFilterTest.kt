package sh.hnet.comfychair.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上传音频时的 MIME 过滤。
 *
 * 内容提供者返回的 MIME 是**外部输入**，而它会被直接喂给 OkHttp 的
 * `toMediaType()`。实测 OkHttp 对畸形值一律抛 `IllegalArgumentException`：
 * - `""` / `"unknown"` → No subtype found
 * - `"audio/mpeg "`（尾空格）、`"audio/mpeg,audio/mp3"` → Parameter is not formatted correctly
 *
 * 而这条调用链没有 catch（`prepareWorkflow` 只有 try/finally、调用点是裸 `launch`），
 * 异常会直达主线程闪退。所以畸形值必须在这里被挡住、退回通用类型。
 */
class MimeTypeFilterTest {

    @Test
    fun `正常的 MIME 类型放行`() {
        listOf(
            "audio/mpeg",
            "audio/wav",
            "audio/x-wav",
            "audio/mp4",
            "audio/flac",
            "video/mp4",
            "application/octet-stream"
        ).forEach { mime ->
            assertTrue("$mime 应被放行", MIME_TYPE_PATTERN.matches(mime))
        }
    }

    @Test
    fun `畸形 MIME 被挡下`() {
        listOf(
            "",                        // 空串
            "unknown",                 // 没有斜杠
            "audio/",                  // 缺 subtype
            "/mpeg",                   // 缺 type
            "audio/mpeg ",             // 尾空格
            " audio/mpeg",             // 首空格
            "audio/mpeg,audio/mp3",    // 逗号分隔的多个值
            "audio/mpeg;charset=utf-8" // 带参数（OkHttp 之外的地方也不该这么传）
        ).forEach { mime ->
            assertFalse("$mime 应被挡下并退回通用类型", MIME_TYPE_PATTERN.matches(mime))
        }
    }
}
