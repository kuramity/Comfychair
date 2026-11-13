package sh.hnet.comfychair.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 取景数学的单测。
 *
 * 为什么要测：这套公式在 2026-11-09 一天内改了两版（第一次读错包围盒、第二次 fov 标错），
 * 两次都是"看着对、实测错"。公式本身是纯数学，用测试锁住比靠肉眼截图可靠。
 */
class ThreeDFramingTest {

    private val eps = 1e-4f

    @Test
    fun `最长轴半长归一化为1时半径等于三轴半尺寸的平方和开根`() {
        // 原始尺寸：0.4 / 0.8 / 0.6（半尺寸），最长轴 0.8 → 归一化后最长边半长 0.5
        val half = floatArrayOf(0.4f, 0.8f, 0.6f)
        val r = ThreeDFraming.distanceFor(half)
        // k = 0.5/0.8 = 0.625 → 半尺寸 0.25 / 0.5 / 0.375
        val expectedRadius = sqrt(0.25f * 0.25f + 0.5f * 0.5f + 0.375f * 0.375f)
        assertEquals(expectedRadius, r.radius, eps)
    }

    @Test
    fun `尺度不变性——模型整体放大1000倍，距离不变`() {
        val small = floatArrayOf(0.4f, 0.8f, 0.6f)
        val large = floatArrayOf(400f, 800f, 600f)
        assertEquals(
            ThreeDFraming.distanceFor(small).distance,
            ThreeDFraming.distanceFor(large).distance,
            eps
        )
    }

    @Test
    fun `包围盒为0时走兜底且距离远大于0`() {
        val r = ThreeDFraming.distanceFor(floatArrayOf(0f, 0f, 0f))
        // 兜底半径 = 0.5*sqrt(3) ≈ 0.866
        assertEquals(0.5f * sqrt(3f), r.radius, eps)
        assertTrue("兜底距离必须 > 1（否则相机在模型内部）", r.distance > 1f)
    }

    @Test
    fun `距离永不低于下限且扁模型的半径仍按最长轴归一化`() {
        // 极端扁模型：三轴里只有一轴有尺寸。归一化后最长轴半长必须是 0.5，
        // 另外两轴按比例缩到接近 0 —— 这比"distance >= MIN"强：后者在当前公式下恒真。
        val r = ThreeDFraming.distanceFor(floatArrayOf(1f, 0.0001f, 0.0001f))
        assertEquals("最长轴必须归一化到半长 0.5", 0.5f, r.radius, 0.001f)
        assertTrue("距离必须是有限正值", r.distance.isFinite() && r.distance > 0f)
        assertTrue(r.distance >= ThreeDFraming.MIN_DISTANCE - eps)
    }

    @Test
    fun `fov为0时除零产生的Infinity必须被下限兜住`() {
        // tan(0) = 0 → radius/0 = Infinity。**Infinity.coerceAtLeast(1.4) 仍是 Infinity**，
        // 所以单纯 coerceAtLeast 兜不住——必须显式判有限性。
        // 这条测试在修复前会失败（distance=Infinity），修复后必须等于 MIN_DISTANCE。
        val r = ThreeDFraming.distanceFor(floatArrayOf(0.5f, 0.5f, 0.5f), fovDegrees = 0f)
        assertTrue("距离必须是有限值，实际 ${r.distance}", r.distance.isFinite())
        assertEquals(
            "除零时必须退到下限，而不是 Infinity 或 0",
            ThreeDFraming.MIN_DISTANCE, r.distance, eps
        )
    }

    @Test
    fun `负fov同样被兜住`() {
        val r = ThreeDFraming.distanceFor(floatArrayOf(0.4f, 0.8f, 0.6f), fovDegrees = -30f)
        assertTrue(r.distance.isFinite() && r.distance > 0f)
    }

    @Test
    fun `fov46度4与边距1点2下 典型模型距离落在合理区间`() {
        val r = ThreeDFraming.distanceFor(floatArrayOf(0.31f, 0.62f, 0.53f))
        // radius ≈ 0.70 → 0.70/tan(23.2°)*1.2 ≈ 1.96
        assertTrue("距离应在 1.5~2.5 之间（模型占屏 55~65%），实际 ${r.distance}",
            r.distance in 1.5f..2.5f)
    }

    @Test
    fun `参数不合法时也绝不返回0距离`() {
        // fov 传 0 会让 tan(0)=0 → 除零得 Infinity；下限钳制必须兜住
        val r = ThreeDFraming.distanceFor(floatArrayOf(0.5f, 0.5f, 0.5f), fovDegrees = 0.001f)
        assertTrue("距离必须是有限正数，实际 ${r.distance}", r.distance.isFinite() && r.distance > 0f)
        assertTrue(abs(r.radius - 0.5f * sqrt(3f)) < eps)
    }

    @Test
    fun `传入长度不足的数组不崩溃且给安全距离`() {
        val r = ThreeDFraming.distanceFor(floatArrayOf(0.5f))
        assertTrue(r.distance > 1f)
        assertTrue(r.radius > 0f)
    }

    @Test
    fun `长度不足且fov为0时也不返回Infinity`() {
        val r = ThreeDFraming.distanceFor(floatArrayOf(0.5f), fovDegrees = 0f)
        assertTrue("双重重灾场景必须仍给有限值，实际 ${r.distance}", r.distance.isFinite())
    }
}
