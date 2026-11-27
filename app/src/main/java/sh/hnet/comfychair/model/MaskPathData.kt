package sh.hnet.comfychair.model

import android.graphics.Path

/**
 * Data class representing a path drawn on the mask canvas
 *
 * @param isFill 该路径是否**整块填充**而不是描边（P1-25）。
 *   反相蒙版需要"全图覆盖"这一笔：修复前用一个 1px 笔宽 + STROKE 的整图矩形来表示，
 *   实际只画出 1px 的边框，图内几乎全是黑色 —— 反相后等于"什么都没蒙"。
 *   填充笔必须单独标记，绘制与生成两边都按 FILL 处理。
 */
data class MaskPathData(
    val path: Path,
    val isEraser: Boolean,
    val brushSize: Float,
    val isFill: Boolean = false
)
