package sh.hnet.comfychair.ui.components.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.hnet.comfychair.model.ModelIntro

/**
 * [modelSubtitle] 的 7 组覆盖（技术债补测，v4.16.1）。
 *
 * 此函数原先唯一的调用者 [ModelOptionLabel] 是死代码（v4.16.1 已删除），
 * 但函数本身保留——目录树下拉将来复用。补测是为了它不再处于"零引用、零测试"
 * 的状态：拼串规则（只拼有值的部分、tag 取前 3、missing 徽标追加）任何一个
 * 被改坏都能在这里被发现。
 */
class ModelSubtitleTest {

    /**
     * 三字段全有的基准条目。
     * author 特意保留：用来钉住「作者有值也不进副标题」这条规则（用户要求）。
     */
    private fun fullIntro(missing: Boolean = false) = ModelIntro(
        file = "model.safetensors",
        base = "SD 1.5",
        type = "LoRA",
        author = "张三",
        missing = missing
    )

    @Test
    fun `intro为null_返回null`() {
        // 表里没收录的模型：副标题整行不出现，下拉项保持只有路径一行
        assertNull(modelSubtitle(null, "badge"))
    }

    @Test
    fun `三字段全有_只拼底模类型_作者不进副标题`() {
        // 用户要求：作者名不出现在界面上，副标题只有底模 · 类型
        assertEquals("SD 1.5 · LoRA", modelSubtitle(fullIntro()))
    }

    @Test
    fun `部分字段有_只拼有值的部分`() {
        val intro = ModelIntro(file = "m.safetensors", base = "Anima", author = "李四")
        // type 缺失时跳过不留空段；author 有值也不参与拼接
        assertEquals("Anima", modelSubtitle(intro))
    }

    @Test
    fun `全空但有tags_退到推荐tag取前三个`() {
        val intro = ModelIntro(
            file = "m.safetensors",
            tags = listOf("1girl", "masterpiece", "best quality", "extra")
        )
        // 结构化字段全空时用作者推荐 tag 兜底，最多取 3 个（多出的截掉）
        assertEquals("推荐 tag：1girl、masterpiece、best quality", modelSubtitle(intro))
    }

    @Test
    fun `全空无tags且missing带badge_只显示badge`() {
        val intro = ModelIntro(file = "m.safetensors", missing = true)
        // tag 也没有时至少把「已移除」透出去，列表项不至于完全空白
        assertEquals("(文件已移除)", modelSubtitle(intro, "(文件已移除)"))
    }

    @Test
    fun `全空无tags且missing但无badge_返回null`() {
        val intro = ModelIntro(file = "m.safetensors", missing = true)
        // 没有可显示的内容：返回 null，调用方不渲染副标题
        assertNull(modelSubtitle(intro, ""))
    }

    @Test
    fun `有字段且missing带badge_拼接后追加badge`() {
        // 有实质字段时 missing 徽标追在末尾，字段信息与移除状态同时可见
        assertEquals("SD 1.5 · LoRA · (文件已移除)", modelSubtitle(fullIntro(missing = true), "(文件已移除)"))
    }
}
