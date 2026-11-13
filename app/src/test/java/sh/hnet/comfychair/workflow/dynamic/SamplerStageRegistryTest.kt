package sh.hnet.comfychair.workflow.dynamic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 采样阶段前缀识别。
 *
 * 这张表同时被 **UI 分组**（[sh.hnet.comfychair.ui.components.config] 的阶段卡片）
 * 和 **体检脚本**（`tools/comfyui-scripts/verify_prebuilt_mapping.py` 的
 * `STAGE_PREFIXES` / `STAGE_PARAMS`）依赖：多一个前缀在这里漏登记，占位符就会
 * 被当成"凭空写的"而报错；删掉老前缀，竖叉流用户已保存的三阶段参数会瞬间丢分组。
 * 所以老前缀的回归与新前缀的登记都在这里钉死。
 */
class SamplerStageRegistryTest {

    // ------------------------------------------------------------ 老前缀回归

    @Test
    fun `竖叉三阶段的前缀仍然按原索引分组`() {
        assertEquals(
            SamplerStageRegistry.StageParam(SamplerStageRegistry.Stage(1, "comp"), "steps"),
            SamplerStageRegistry.resolve("comp_steps")
        )
        assertEquals(
            SamplerStageRegistry.StageParam(SamplerStageRegistry.Stage(2, "struct"), "denoise"),
            SamplerStageRegistry.resolve("struct_denoise")
        )
        assertEquals(
            SamplerStageRegistry.StageParam(SamplerStageRegistry.Stage(3, "detail"), "scheduler"),
            SamplerStageRegistry.resolve("detail_scheduler")
        )
    }

    @Test
    fun `Krea2 二采前缀仍是第二阶段`() {
        assertEquals(
            SamplerStageRegistry.StageParam(SamplerStageRegistry.Stage(2, "stage2"), "cfg"),
            SamplerStageRegistry.resolve("stage2_cfg")
        )
    }

    // ------------------------------------------------------------ 新前缀 hires_

    @Test
    fun `hires 的六个采样参数都归入第四阶段`() {
        val expected = SamplerStageRegistry.Stage(4, "hires")
        for (param in listOf("steps", "cfg", "denoise", "sampler", "sampler_name", "scheduler")) {
            assertEquals(
                "hires_$param 必须是第 4 阶段的 $param",
                SamplerStageRegistry.StageParam(expected, param),
                SamplerStageRegistry.resolve("hires_$param")
            )
        }
    }

    @Test
    fun `hires_scale 被识别且参数名是 scale`() {
        // 唯一一个非采样参数：ImageScaleBy.scale_by 的预放大倍数，
        // 参数名拼错（如 hires_upscale）会让它掉出阶段卡片、只剩一个英文占位符标签
        assertEquals(
            SamplerStageRegistry.StageParam(SamplerStageRegistry.Stage(4, "hires"), "scale"),
            SamplerStageRegistry.resolve("hires_scale")
        )
    }

    @Test
    fun `分次补采的三个参数名对 1 到 3 轮都成立`() {
        // 竖叉流（prebuilt_11）的 1~3 次可开关补采：每轮一个开关、一个步数、一个强度。
        // 少认一个，界面上就是一个英文占位符标签 + 没有中文标签，且不会归到「极清放大」卡片里
        val stage = SamplerStageRegistry.Stage(4, "hires")
        for (round in 1..3) {
            for (key in listOf("on", "steps", "denoise")) {
                assertEquals(
                    "hires_$key$round 必须归入第 4 阶段",
                    SamplerStageRegistry.StageParam(stage, "$key$round"),
                    SamplerStageRegistry.resolve("hires_$key$round")
                )
            }
        }
    }

    @Test
    fun `补采轮次后缀只到 3`() {
        // 资产里就是三个 KSampler（85/87/89）。多认会让 UI 上出现永远没有落点的"幽灵控件"，
        // 也会让 verify_prebuilt_mapping.py 与这里对不上
        assertNull(SamplerStageRegistry.resolve("hires_on4"))
        assertNull(SamplerStageRegistry.resolve("hires_steps0"))
        assertNull(SamplerStageRegistry.resolve("hires_denoise12"))
        // 三轮共用的三个仍在（老名字，不许被后缀规则吃掉）
        assertTrue(SamplerStageRegistry.isStageParam("hires_cfg"))
        assertTrue(SamplerStageRegistry.isStageParam("hires_sampler"))
        assertTrue(SamplerStageRegistry.isStageParam("hires_scheduler"))
    }

    @Test
    fun `CLIP 格式门控归入第五阶段`() {
        // prebuilt_01/21 的 `clip_gguf`（CRZSwitch.value，BOOLEAN）：它严格说不是采样参数，
        // 但走同一条路才能拿到阶段卡片 + 中文标签 + 说明行 —— 不登记的话界面上只有一串
        // `{{clip_gguf}}`，用户根本不知道该不该开（两个加载器的适用格式互斥）
        assertEquals(
            SamplerStageRegistry.StageParam(SamplerStageRegistry.Stage(5, "clip"), "gguf"),
            SamplerStageRegistry.resolve("clip_gguf")
        )
        assertTrue(SamplerStageRegistry.isStageParam("clip_gguf"))
    }

    @Test
    fun `clip 前缀只认 gguf 一个参数名`() {
        // 泛化识别（认任何 `clip_xxx`）会把已识别的 CLIP 名称占位符也抓进阶段卡片 ——
        // 那些是专用逻辑的字段（CLIP 选择器），界面上已经有控件了
        assertNull(SamplerStageRegistry.resolve("clip_name"))
        assertNull(SamplerStageRegistry.resolve("clip_name1"))
        assertNull(SamplerStageRegistry.resolve("clip_"))
    }

    @Test
    fun `未知参数名与空参数名不被接管`() {
        assertNull("认不得的参数名不能接管", SamplerStageRegistry.resolve("hires_unknown"))
        assertNull("只有前缀没有参数名不能接管", SamplerStageRegistry.resolve("hires_"))
        assertNull(SamplerStageRegistry.resolve("comp_seed"))
        assertNull(SamplerStageRegistry.resolve(""))
    }

    @Test
    fun `阶段判定对采样参数成立对通用占位符不成立`() {
        assertTrue(SamplerStageRegistry.isStageParam("hires_steps"))
        assertTrue(SamplerStageRegistry.isStageParam("hires_scale"))
        assertTrue(SamplerStageRegistry.isStageParam("detail_cfg"))

        assertFalse("提示词不是采样阶段参数", SamplerStageRegistry.isStageParam("positive_prompt"))
        assertFalse("种子不是采样阶段参数", SamplerStageRegistry.isStageParam("seed"))
        assertFalse(SamplerStageRegistry.isStageParam("image_filename"))
    }

    @Test
    fun `阶段索引唯一且从 1 起排`() {
        // UI 按 index 排序决定卡片先后（1 在前），重复索引会让两张卡片交错渲染
        val indices = listOf("comp_steps", "stage2_cfg", "detail_steps", "hires_steps")
            .mapNotNull { SamplerStageRegistry.resolve(it)?.stage }
        assertEquals(listOf(1, 2, 3, 4), indices.map { it.index }.sorted())
    }
}
