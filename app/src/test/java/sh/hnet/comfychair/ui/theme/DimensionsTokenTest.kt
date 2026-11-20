package sh.hnet.comfychair.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 尺寸令牌的数值契约测试。
 *
 * 为什么需要：本轮新建的间距/圆角/描边令牌里，有一部分在当前代码中还没有调用点
 * （它们是后续批次替换全项目硬编码时的目标刻度，见 [Dimensions] 的类注释）。没有调用
 * 点的常量最容易在后续重构中被顺手改错、删掉或"统一"成别的值而无人发现 —— 这里把
 * **全部**令牌（含本批之前就存在的 [Dimensions.PlaceholderLogoSize]）的数值显式钉住，
 * 让任何改动都先经过一次可见的失败。
 *
 * 断言的另一层含义：这些数字是**设计刻度**，不是随手写的。
 * - 间距走 4dp 网格（[Dimensions.Spacing4]…[Dimensions.Spacing24]）；
 * - 圆角与 `Shapes`（ui/theme/Shape.kt）的 8/12/16/20/28 体系同源；
 * - 胶囊按钮圆角必须等于按钮高度的一半（28 × 2 = 56）。
 *
 * 纯 JVM 测试：dp/sp 是 Float 支撑的值类，读 `.value` 即可，不需要 Android 环境。
 */
class DimensionsTokenTest {

    @Test
    fun `间距令牌为4dp网格`() {
        assertEquals(4f, Dimensions.Spacing4.value, 0.001f)
        assertEquals(8f, Dimensions.Spacing8.value, 0.001f)
        assertEquals(12f, Dimensions.Spacing12.value, 0.001f)
        assertEquals(16f, Dimensions.Spacing16.value, 0.001f)
        assertEquals(24f, Dimensions.Spacing24.value, 0.001f)
        // 弹窗内容底距：不在 4dp 网格上，但同样是固定规格
        assertEquals(32f, Dimensions.Spacing32.value, 0.001f)
    }

    @Test
    fun `圆角令牌与 Shapes 体系一致`() {
        assertEquals(8f, Dimensions.Radius8.value, 0.001f)
        assertEquals(12f, Dimensions.Radius12.value, 0.001f)
        assertEquals(16f, Dimensions.Radius16.value, 0.001f)
        assertEquals(18f, Dimensions.Radius18.value, 0.001f)
        assertEquals(28f, Dimensions.Radius28.value, 0.001f)
    }

    @Test
    fun `描边令牌为1dp与1_5dp`() {
        assertEquals(1f, Dimensions.Stroke1.value, 0.001f)
        assertEquals(1.5f, Dimensions.Stroke1_5.value, 0.001f)
    }

    @Test
    fun `胶囊按钮圆角等于按钮高度的一半`() {
        // 胶囊的几何定义：56dp 高按钮的圆角必须是 28dp，否则会退化成"两端椭圆弧"，
        // 白边与本体曲线也会错位（v4.16.10 N1 的取证结论）。
        assertEquals(
            Dimensions.ButtonHeight.value / 2f,
            Dimensions.Radius28.value,
            0.001f,
        )
    }

    @Test
    fun `按钮规格令牌数值`() {
        assertEquals(56f, Dimensions.ButtonHeight.value, 0.001f)
        assertEquals(54f, Dimensions.TrailingButtonWidth.value, 0.001f)
        assertEquals(10f, Dimensions.SplitButtonGap.value, 0.001f)
        assertEquals(24f, Dimensions.IconSize24.value, 0.001f)
        assertEquals(18f, Dimensions.ButtonTextSize.value, 0.001f)
    }

    @Test
    fun `空态占位图标尺寸`() {
        // 本轮之前就存在的唯一令牌，恰好最容易被"顺手统一"改动 —— 一并钉住。
        assertEquals(300f, Dimensions.PlaceholderLogoSize.value, 0.001f)
    }
}