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
import sh.hnet.comfychair.ui.components.TRAILING_BORDER_OPEN_ALPHA
import sh.hnet.comfychair.ui.components.TRAILING_GRADIENT_MIDPOINT_FRACTION
import sh.hnet.comfychair.ui.components.shared.GEAR_OPEN_BG_ALPHA
import sh.hnet.comfychair.ui.components.shared.GEAR_PRESSED_ALPHA_UNIFIED_DARK
import sh.hnet.comfychair.ui.components.shared.GEAR_PRESSED_ALPHA_UNIFIED_LIGHT

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

    // ---- 5. 模块 2（B2）：按压统一档 / 齿轮打开态 / 下拉同色度规格常量 ----

    @Test
    fun `按压底色统一档深13浅15_percent且回退档保留旧值`() {
        // 统一档（模块 2/D2 + B2b 拆深浅）：顶栏图标/导航 tab/齿轮三处按压终值同源 ——
        // 深色 White 13% ≈1.45:1（实测 1.435）、浅色 Black 15% ≈1.41:1（实测 1.409；
        // 13% 的数学天花板 1.343 过不了 1.35 判据，浅色提档）
        assertEquals(0.13f, PRESS_BG_ALPHA_V2_DARK)
        assertEquals(0.15f, PRESS_BG_ALPHA_V2_LIGHT)
        // 回退档（安全阀关闭）：改动前的深色 8% / 浅色 6%
        assertEquals(0.08f, PRESS_BG_ALPHA_DARK_V2_LEGACY)
        assertEquals(0.06f, PRESS_BG_ALPHA_LIGHT_V2_LEGACY)
    }

    @Test
    fun `齿轮按压统一档与打开态底色规格`() {
        // 按压终值统一档：编译期引用顶栏/导航同源常量（深 13% / 浅 15%，常驻 5% 底保留）
        assertEquals(0.13f, GEAR_PRESSED_ALPHA_UNIFIED_DARK)
        assertEquals(0.15f, GEAR_PRESSED_ALPHA_UNIFIED_LIGHT)
        // 打开态（模块 2/S1）：底色替换为 primary@0.18（与导航选中态同语言）
        assertEquals(0.18f, GEAR_OPEN_BG_ALPHA)
    }

    @Test
    fun `下拉中点色与打开态白边规格`() {
        // C1（模块 2）：下拉基色 = 主按钮渐变（accent → +35°）的中点，纯色填充
        assertEquals(0.5f, TRAILING_GRADIENT_MIDPOINT_FRACTION)
        // S2：打开态白边提亮到 32%（静置走主按钮同源默认 22%/12%，不在此钉）
        assertEquals(0.32f, TRAILING_BORDER_OPEN_ALPHA)
    }

    // ---- 6. Q2（v4.16.6）：禁用态白边降档规格 ----

    @Test
    fun `禁用态白边降档约为启用档一半`() {
        // v2 皮肤深色启用 0.22 → 禁用 0.10（≈一半；叠禁用容器对比 1.37:1、叠背景 1.85:1，
        // 禁用灰块的胶囊轮廓可辨）
        assertEquals(0.10f, BORDER_ALPHA_DARK_V2_DISABLED)
        // 浅色启用 0.12 → 禁用 0.08（规格建议 6% 实测对比只有 1.14:1 糊进灰块，提一档到 1.19:1）
        assertEquals(0.08f, BORDER_ALPHA_LIGHT_DISABLED)
        // 回退态深色启用 0.50 → 禁用 0.25
        assertEquals(0.25f, BORDER_ALPHA_DARK_DISABLED)
    }

    // ---- 7. S1（v4.16.6）：禁用态按压底色半强度 ----

    @Test
    fun `禁用态按压底色强度等于启用乘0_5`() {
        // 派工单判据：禁用强度 = 启用 × 0.5（允许 ±0.01）——深色统一档 13% → 6.5%、
        // 浅色统一档 15% → 7.5%、回退档 8% → 4% / 6% → 3%
        assertEquals(0.13f * PRESS_BG_DISABLED_FACTOR, resolvePressBgAlpha(false, 0.13f), 0.01f)
        assertEquals(0.15f * PRESS_BG_DISABLED_FACTOR, resolvePressBgAlpha(false, 0.15f), 0.01f)
        assertEquals(0.08f * PRESS_BG_DISABLED_FACTOR, resolvePressBgAlpha(false, 0.08f), 0.01f)
        assertEquals(0.06f * PRESS_BG_DISABLED_FACTOR, resolvePressBgAlpha(false, 0.06f), 0.01f)
        // 启用态取满强度、不受系数影响
        assertEquals(0.13f, resolvePressBgAlpha(true, 0.13f), 0.0001f)
        assertEquals(0.15f, resolvePressBgAlpha(true, 0.15f), 0.0001f)
        // 系数本身钉住 0.5（internal 常量供 instrumented 测试复用）
        assertEquals(0.5f, PRESS_BG_DISABLED_FACTOR)
    }
}
