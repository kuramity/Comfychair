package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N1（v4.16.10）形状对齐契约测试：**本体形状（M3 SplitButton 画的灰块）与白边形状
 * （皮肤层描边）必须是同一条曲线**。
 *
 * 背景（R7 取证）：M3 默认 leading 形状是"左全圆 + 右 4dp"不对称半胶囊、trailing 是
 * "左 4dp + 右全圆"且 checked 时整体 CircleShape，而皮肤层白边一直按对称
 * RoundedCornerShape(28.dp)/18.dp 画。启用态容器透明看不出来；禁用后 M3 Surface 按本体
 * 不对称形状画灰块，主按钮右端与白边错开 24.75dp、下拉右端白边悬空 9.25dp。
 *
 * 本批修复 = 本体显式传与皮肤同源的 shapes（四角全 28dp / 18dp，含按压与展开态）。
 * 上轮漏检教训（派工单原文）：验收判据必须用**两条曲线的几何量相等**（逐角半径），
 * 不许用"数值没变"代替 —— 本测试逐角断言，并把"M3 旧默认确实不对称"固化为反证守护，
 * 防止将来有人把 shapes 参数"简化"回默认值。
 *
 * 全部走常量 / 纯函数（JVM 单测可直接跑，不进 Compose 组装）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class GenerationButtonShapeAlignmentTest {

    /** density=1 的假 Density：outline 的角半径数值即 dp 数值（与 RippleFeedbackSkinTest 同口径）。 */
    private val density = object : Density {
        override val density: Float get() = 1f
        override val fontScale: Float get() = 1f
    }

    /** 取 shape 四角半径（px，density=1 时即 dp）：顺序 = 左上/右上/右下/左下。 */
    private fun cornerRadiiDp(shape: Shape, side: Float = 100f): FloatArray {
        // 走 CornerSize.toPx（纯 Kotlin）取角半径，不走 createOutline —— Outline.Rounded
        // 对"混合半径"圆角矩形会在构造期建 Path（AndroidPath.addRoundRect → android.graphics
        // stub 在 JVM 单测未 mock，实测抛 RuntimeException）。本测试只关心几何数值，
        // CornerSize.toPx 与 createOutline 给出的角半径完全一致。
        assertTrue("本测试只支持 RoundedCornerShape，实际 ${shape::class.simpleName}", shape is RoundedCornerShape)
        val rcs = shape as RoundedCornerShape
        val size = Size(side, side)
        return floatArrayOf(
            rcs.topStart.toPx(size, density),
            rcs.topEnd.toPx(size, density),
            rcs.bottomEnd.toPx(size, density),
            rcs.bottomStart.toPx(size, density),
        )
    }

    /** 四角逐角相等断言（半径两两相等的圆角矩形，四角应全部等于 [expectedDp]）。 */
    private fun assertAllCorners(shape: Shape, expectedDp: Float, label: String) {
        val radii = cornerRadiiDp(shape)
        val names = listOf("左上", "右上", "右下", "左下")
        names.forEachIndexed { i, name ->
            assertEquals(
                "$label $name 角半径应为 ${expectedDp}dp（白边与本体逐角相等）",
                expectedDp,
                radii[i],
                0.001f,
            )
        }
    }

    // ---- 1. 本体形状四角全圆（与皮肤层白边同一条曲线）----

    @Test
    fun `主按钮本体形状四角全28dp`() {
        assertAllCorners(GENERATION_LEADING_BODY_SHAPE, 28f, "主按钮本体")
    }

    @Test
    fun `下拉本体形状四角全18dp`() {
        assertAllCorners(GENERATION_TRAILING_BODY_SHAPE, 18f, "下拉本体")
    }

    @Test
    fun `主按钮本体与白边规格同源_共用28dp`() {
        // 白边形状即皮肤层传给 rippleFeedback 的 shape —— 与本体共用同一个常量对象
        //（GenerationButton.kt 调用点 shape = GENERATION_LEADING_BODY_SHAPE）。
        // 这里把白边规格（28dp 全圆）独立写出，断言与本体常量逐角相等：
        // 将来任何一侧单独改动都会在此失败。
        val whiteEdgeShape = RoundedCornerShape(28.dp)
        val bodyRadii = cornerRadiiDp(GENERATION_LEADING_BODY_SHAPE)
        val edgeRadii = cornerRadiiDp(whiteEdgeShape)
        bodyRadii.forEachIndexed { i, r ->
            assertEquals("主按钮白边与本体第 ${i + 1} 角半径必须相等", edgeRadii[i], r, 0.001f)
        }
    }

    @Test
    fun `下拉本体与白边规格同源_共用18dp`() {
        val whiteEdgeShape = RoundedCornerShape(18.dp)
        val bodyRadii = cornerRadiiDp(GENERATION_TRAILING_BODY_SHAPE)
        val edgeRadii = cornerRadiiDp(whiteEdgeShape)
        bodyRadii.forEachIndexed { i, r ->
            assertEquals("下拉白边与本体第 ${i + 1} 角半径必须相等", edgeRadii[i], r, 0.001f)
        }
    }

    // ---- 2. 反证守护：M3 旧默认形状确实不对称（证明本体必须显式传 shapes）----

    @Test
    fun `反证_M3旧默认leading形状四角不全等28dp`() {
        // M3 旧默认 leading 形状（material3 1.5.0-alpha11 字节码反编译确认，等价手写）：
        // leadingButtonShapes 默认 = 左 cornerFull（50% 全圆）+ 右 MediumInnerCornerSize。
        // 注：M3 的 leadingButtonShapes()/trailingButtonShapes() 工厂在 Kotlin 元数据里是
        // internal（字节码 public 但跨模块不可见），无法在测试里直接调用 —— 这里按反编译
        // 结构等价手写，守护目的相同：若将来把 GenerationButton 的自定义 shapes 删掉
        // 回到这个默认形状，逐角断言即失败。
        // 先钉住取证口径本身：MediumInnerCornerSize 恰为 4dp（56dp 高按钮上量）。
        val innerPx = SplitButtonDefaults.MediumInnerCornerSize
            .toPx(Size(56f, 56f), density)
        assertEquals("M3 默认内角档应为 4dp（R7 取证口径）", 4f, innerPx, 0.001f)
        val legacyLeadingShape = RoundedCornerShape(
            topStart = CornerSize(percent = 50),
            topEnd = CornerSize(4.dp),
            bottomEnd = CornerSize(4.dp),
            bottomStart = CornerSize(percent = 50),
        )
        val radii = cornerRadiiDp(legacyLeadingShape)
        val mismatched = radii.count { Math.abs(it - 28f) > 0.01f }
        assertTrue(
            "M3 旧默认 leading 形状应有角不等于 28dp（实测 ${radii.joinToString()}），否则本体无需显式传 shapes",
            mismatched > 0,
        )
    }

    @Test
    fun `反证_M3旧默认trailing形状四角不全等18dp`() {
        // M3 旧默认 trailing 形状（同上反编译口径）：左 4dp + 右全圆，与白边 18dp 在
        // 左端错开 14.75dp、右端白边悬空 9.25dp（R7 量化）。等价手写同 leading。
        val legacyTrailingShape = RoundedCornerShape(
            topStart = CornerSize(4.dp),
            topEnd = CornerSize(percent = 50),
            bottomEnd = CornerSize(percent = 50),
            bottomStart = CornerSize(4.dp),
        )
        val radii = cornerRadiiDp(legacyTrailingShape)
        val mismatched = radii.count { Math.abs(it - 18f) > 0.01f }
        assertTrue(
            "M3 旧默认 trailing 形状应有角不等于 18dp（实测 ${radii.joinToString()}），否则本体无需显式传 shapes",
            mismatched > 0,
        )
    }

    // ---- 3. 下拉白边 override 门控（N1-2.1）----

    @Test
    fun `断连禁用下拉_不返回启用档override_走皮肤层禁用降档`() {
        // 修复的缺陷：pressUnified=false + 断连（trailingEnabled=false）原先返回 0.18
        //（启用档，比禁用降档亮约 80%）。现在必须 null —— 皮肤层 `!enabled` 分支接管，
        // 白边走禁用降档（深 0.10 / 浅 0.08）。
        assertNull(
            resolveTrailingBorderAlpha(pressUnified = false, trailingEnabled = false, showMenu = false),
        )
        // 反证钉死：该组合绝不允许拿到 0.18
        assertNotEquals(
            0.18f,
            resolveTrailingBorderAlpha(false, false, false) ?: -1f,
        )
    }

    @Test
    fun `override门控全组合回归`() {
        // 模块 2 开（pressUnified=true）
        assertEquals(0.32f, resolveTrailingBorderAlpha(true, true, true))   // 打开态提亮
        assertNull(resolveTrailingBorderAlpha(true, true, false))           // 静置走默认档
        assertNull(resolveTrailingBorderAlpha(true, false, true))           // 禁用一律 null
        assertNull(resolveTrailingBorderAlpha(true, false, false))
        // 回退态（pressUnified=false）：启用档 18%（打开也不提亮，旧行为保留）
        assertEquals(0.18f, resolveTrailingBorderAlpha(false, true, false))
        assertEquals(0.18f, resolveTrailingBorderAlpha(false, true, true))
        // 回退态 + 禁用：null（N1-2.1 的修复本身）
        assertNull(resolveTrailingBorderAlpha(false, false, true))
    }
}
