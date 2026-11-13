package sh.hnet.comfychair.model

import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 3D 查看器的取景计算（纯函数，专门抽出来做单测）。
 *
 * 背景（2026-11-09 两轮返工，教训写在这里防止再犯）：
 * 1. 取景距离曾经读 `ModelNode.halfExtent` —— 它返回的是**缩放前**的包围盒，
 *    而渲染用的是 `scaleToUnits=1f` 之后的尺寸，两者差一个数量级 → 相机落进模型内部。
 *    现在只用 `asset.boundingBox` 的原始尺寸，按"归一化后最长边 = 1（半长 0.5）"换算。
 * 2. 真正让用户看到"贴脸"的是**首帧相机位姿没设置**（相机停在默认 (0,0,1)），
 *    与这里的数学无关 —— 但公式本身也必须有测试锁住，否则改一次错一次。
 *
 * 所有量纲约定：模型已居中于原点，最长边归一化为 1.0 世界单位。
 */
object ThreeDFraming {

    /**
     * 相机垂直视场角（度）。由 SceneView 2.3.0 实现反推：
     * `CameraNode` 写死 `focalLength = 28.0`，Filament 走 24mm 传感器，
     * `setLensProjection(28.0, aspect, …)` → `2·atan(12/28) ≈ 46.4°`。
     */
    const val FOV_DEGREES = 46.4f

    /** 包围球刚好贴边的距离再留 20% 余量 → 屏幕占比约 55~65% */
    const val MARGIN = 1.2f

    /**
     * 距离下限（世界单位）——**防御性兜底**。
     *
     * 两类触发：
     * 1. 默认 fov（46.4°）下不触发（`radius ≥ 0.5` 恒成立，距离恒 ≥ 1.40）；
     * 2. **fov 为 0/负值时触发**（`tan(0)=0` → `radius/0=Infinity`，而
     *    `Infinity.coerceAtLeast(1.4)` 仍是 Infinity，必须先判 `isFinite()` 再回落到本值）。
     * 第 2 类由 v4.2.2 的除零防御测试覆盖。
     */
    const val MIN_DISTANCE = 1.4f

    /** 包围盒半尺寸低于此值 → 视为"包围盒不可用" */
    private const val MIN_BOUNDING_EXTENT = 1e-6f

    private const val PI_OVER_180 = 0.017453292f

    /** 取景结果：相机到原点的距离 + 归一化后的包围球半径（半径便于日志核对） */
    data class Result(val distance: Float, val radius: Float)

    /**
     * 按原始包围盒算相机距离。
     *
     * @param halfExtent `asset.boundingBox.halfExtent`（原始尺寸，未应用节点缩放）
     * @param fovDegrees 垂直 fov（默认 [FOV_DEGREES]）
     * @param margin 边距倍数（默认 [MARGIN]）
     * @param minDistance 距离下限（默认 [MIN_DISTANCE]）
     */
    fun distanceFor(
        halfExtent: FloatArray,
        fovDegrees: Float = FOV_DEGREES,
        margin: Float = MARGIN,
        minDistance: Float = MIN_DISTANCE
    ): Result {
        if (halfExtent.size < 3) {
            // 调用方传错长度时给最保守的兜底，绝不返回 0（返回 0 = 相机进模型内部）
            val r = 0.5f * sqrt(3f)
            val rawRaw = r / tan(fovDegrees / 2f * PI_OVER_180) * margin
            return Result(
                distance = if (rawRaw.isFinite()) rawRaw.coerceAtLeast(minDistance) else minDistance,
                radius = r
            )
        }
        val hx = halfExtent[0]
        val hy = halfExtent[1]
        val hz = halfExtent[2]
        val rawMax = maxOf(hx, hy, hz)
        val radius = if (rawMax > MIN_BOUNDING_EXTENT) {
            // scaleToUnits=1 → 最长边缩到 1（半长 0.5），各轴按同一比例
            val k = 0.5f / rawMax
            sqrt((hx * k) * (hx * k) + (hy * k) * (hy * k) + (hz * k) * (hz * k))
        } else {
            // 包围盒不可用：按归一化立方体的最坏包围球
            0.5f * sqrt(3f)
        }
        // 注：radius ≥ 0.5 恒成立（最长轴半长必为 0.5），所以正常路径下距离恒 ≥ 1.40。
        // **但 fov 传 0（或任何 tan 为 0 的角度）会让 radius/0 = Infinity**，
        // 而 `Infinity.coerceAtLeast(1.4)` 仍是 Infinity —— 相机会飞到无穷远、画面全空。
        // 这是独立复核指出"第 6 条测试只断言 isFinite 但没真触发除零"时挖出来的真缺陷，
        // 所以这里必须先判有限性再钳制。
        val raw = radius / tan(fovDegrees / 2f * PI_OVER_180) * margin
        val distance = if (raw.isFinite()) raw.coerceAtLeast(minDistance) else minDistance
        return Result(distance = distance, radius = radius)
    }
}
