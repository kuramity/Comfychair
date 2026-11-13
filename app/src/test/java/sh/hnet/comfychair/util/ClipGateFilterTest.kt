package sh.hnet.comfychair.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CLIP 格式门控的单元测试。
 *
 * 背景：带 `clip_gguf` 开关的工作流同时挂了原生 `CLIPLoader`（只吃 .safetensors）与
 * `CLIPLoaderGGUF`（只吃 .gguf），两者互斥，配错会报 Value not in list。
 *
 * **候选不过滤**（2026-09-24 起）：早先按格式过滤掉整个列表项，结果只含 .gguf 的目录
 * 在关掉开关时连文件夹都不显示，用户在列表里找不到路（实测反馈）。现在候选恒为全量，
 * 这里把这条语义与选中值的回落规则钉住。
 */
class ClipGateFilterTest {

    private val clips = listOf(
        "flux\\5xxl_fp8_e4m3fn.safetensors",
        "qwen_series\\qwen_3_4b_fp8_mixed.safetensors",
        "wan\\umt5_xxl_fp8_e4m3fn_scaled.safetensors",
        "Z-Image\\Z-Image-AbliteratedV1.Q8_0.gguf",
        "Z-Image\\Another.UPPER.GGUF",
    )

    @Test
    fun `开关打开或关闭都返回完整候选`() {
        assertEquals(clips, ValidationUtils.filterClipsByGate(clips, mapOf("clip_gguf" to "true")))
        assertEquals(clips, ValidationUtils.filterClipsByGate(clips, mapOf("clip_gguf" to "false")))
        assertEquals(clips, ValidationUtils.filterClipsByGate(clips, emptyMap()))
    }

    @Test
    fun `只含 gguf 的目录在关掉开关时依然可见`() {
        val out = ValidationUtils.filterClipsByGate(clips, mapOf("clip_gguf" to "false"))
        assertTrue("Z-Image 目录必须在候选里", out.any { it.startsWith("Z-Image\\") })
    }

    @Test
    fun `选中值仍可用时保留`() {
        assertEquals(
            "Z-Image\\Z-Image-AbliteratedV1.Q8_0.gguf",
            ValidationUtils.pickClipsAfterGate("Z-Image\\Z-Image-AbliteratedV1.Q8_0.gguf", clips)
        )
    }

    @Test
    fun `选中值失效时回落到第一项`() {
        assertEquals(clips.first(), ValidationUtils.pickClipsAfterGate("已删除的文件.gguf", clips))
    }

    @Test
    fun `空选中值回落到第一项`() {
        assertEquals(clips.first(), ValidationUtils.pickClipsAfterGate("", clips))
    }

    @Test
    fun `空列表返回空串`() {
        assertEquals("", ValidationUtils.pickClipsAfterGate("x", emptyList()))
    }
}
