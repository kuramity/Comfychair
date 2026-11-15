package sh.hnet.comfychair.ui.components.ripple

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 皮肤层参数化（v5 拆分开关）的视觉契约测试。
 *
 * 钉住三件事：
 * 1. 按压缩放的默认值与取值逻辑（完整皮肤 0.96 / 顶栏裸图标 0.94，松手一律回 1）；
 * 2. 圆角选择逻辑：cornerRadius 非空 → 圆角矩形（顶栏裸图标 12dp），null → 沿用传入形状（回退态圆形）；
 * 3. 视觉要素开关默认全开（回退语义 = 旧版完整皮肤），白边与投影的两套规格常量。
 *
 * 全部走纯函数 / 常量，不进 Compose 组装（JVM 单测可直接跑）。
 */
class RippleFeedbackSkinTest {

    // ---- 1. pressScale：默认值与取值逻辑 ----

    @Test
    fun `按压缩放默认值是0_96且开关默认全开`() {
        assertEquals(0.96f, DEFAULT_PRESS_SCALE)
        // 各视觉要素开关默认值 = 旧版完整皮肤（回退语义的基准形态）
        assertTrue(DEFAULT_SHOW_BORDER)
        assertTrue(DEFAULT_SHOW_HALO)
        assertTrue(DEFAULT_SHOW_TOUCH_GLOW)
        // U1 收敛后点阵开关默认仍为 true（生成主按钮不传即拿到点阵）
        assertTrue(DEFAULT_SHOW_DOTS)
    }

    @Test
    fun `按下取传入的pressScale_松手回1`() {
        assertEquals(0.96f, resolvePressScale(pressed = true, pressScale = DEFAULT_PRESS_SCALE))
        assertEquals(1f, resolvePressScale(pressed = false, pressScale = DEFAULT_PRESS_SCALE))
        // 顶栏裸图标态传 0.94
        assertEquals(0.94f, resolvePressScale(pressed = true, pressScale = 0.94f))
        assertEquals(1f, resolvePressScale(pressed = false, pressScale = 0.94f))
    }

    // ---- 2. 圆角选择逻辑 ----

    @Test
    fun `cornerRadius为null时沿用传入形状`() {
        val fallback = CircleShape
        // 回退态：顶栏图标保持圆形（identity 级复用，不新建对象）
        assertSame(fallback, resolveFeedbackShape(null, fallback))
    }

    @Test
    fun `cornerRadius非null时改用该圆角矩形`() {
        val shape = resolveFeedbackShape(12.dp, CircleShape)
        // 顶栏裸图标态：必须是圆角矩形而不是传入的圆形
        assertTrue("应返回 RoundedCornerShape，实际 ${shape::class.simpleName}", shape is RoundedCornerShape)
        // 且圆角半径恰为 12dp（density=1 时 outline 的角半径即 dp 数值）
        val outline = shape.createOutline(
            size = Size(44f, 44f),
            layoutDirection = LayoutDirection.Ltr,
            density = object : Density {
                override val density: Float get() = 1f
                override val fontScale: Float get() = 1f
            },
        )
        assertTrue("44dp 方块的轮廓应为圆角矩形", outline is Outline.Rounded)
        val corner = (outline as Outline.Rounded).roundRect.topLeftCornerRadius
        assertEquals(12f, corner.x, 0.001f)
        assertEquals(12f, corner.y, 0.001f)
    }

    // ---- 3. 白边 / 投影两套规格常量（回退契约 + v2 规格）----

    @Test
    fun `白边规格_回退态1_5dp的50_percent_v2皮肤1dp的22_percent`() {
        assertEquals(1.5f, BORDER_STROKE_DP)
        assertEquals(0.5f, BORDER_ALPHA_DARK)
        assertEquals(1f, BORDER_STROKE_DP_V2)
        assertEquals(0.22f, BORDER_ALPHA_DARK_V2)
    }

    @Test
    fun `向下投影规格_两层偏移2_4dp且深浅两套alpha`() {
        // v2 主按钮投影（U3 减档）：三层减成两层（去掉 6dp 外沿），深色 0.16/0.08、浅色更轻 0.10/0.05
        assertEquals(2, SHADOW_OFFSETS_DP.size)
        assertEquals(2, SHADOW_ALPHAS_DARK.size)
        assertEquals(2, SHADOW_ALPHAS_LIGHT.size)
        assertEquals(2f, SHADOW_OFFSETS_DP[0])
        assertEquals(4f, SHADOW_OFFSETS_DP[1])
        assertEquals(0.16f, SHADOW_ALPHAS_DARK[0])
        assertEquals(0.08f, SHADOW_ALPHAS_DARK[1])
        assertEquals(0.10f, SHADOW_ALPHAS_LIGHT[0])
        assertEquals(0.05f, SHADOW_ALPHAS_LIGHT[1])
    }

    // ---- 4. 点阵收敛开关（U1）：showDots=false 必须零绘制 ----

    @Test
    fun `showDots关闭时任何boost都不画点`() {
        // 点阵层关闭：无论采样出的 boost 是多少（包括波前正扫过的 1.0），点都不画
        assertFalse(shouldDrawDot(showDots = false, boost = 0f))
        assertFalse(shouldDrawDot(showDots = false, boost = 0.5f))
        assertFalse(shouldDrawDot(showDots = false, boost = 1f))
        // 开启时行为不变：boost > 0 才画
        assertTrue(shouldDrawDot(showDots = true, boost = 0.01f))
        assertFalse(shouldDrawDot(showDots = true, boost = 0f))
    }

    @Test
    fun `showDots关闭时触点辉光一并关闭`() {
        // 触点辉光属于点阵皮肤的一部分：showDots=false 时即使 showTouchGlow=true 也不画
        assertFalse(shouldDrawTouchGlow(showDots = false, showTouchGlow = true))
        assertFalse(shouldDrawTouchGlow(showDots = false, showTouchGlow = false))
        // 开启时由 showTouchGlow 决定
        assertTrue(shouldDrawTouchGlow(showDots = true, showTouchGlow = true))
        assertFalse(shouldDrawTouchGlow(showDots = true, showTouchGlow = false))
    }
}
