package sh.hnet.comfychair.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import sh.hnet.comfychair.model.MaskPathData

/**
 * 蒙版预览绘制画笔缓存（模式参考 WorkflowGraphCanvas 的 GraphPaintCache / MaskPaintCanvas
 * 的 MaskEditorPaints）：原先在 Canvas 绘制 lambda 内每帧 new Paint + new PorterDuffXfermode
 * （native 对象）。画笔只在 Compose 绘制线程访问，且每次使用前都会重设 style / strokeWidth
 * （color、join、cap、xfermode 全程不变），跨帧复用不串味。
 *
 * 与编辑态（MaskEditorPaints）分开定义：预览蒙版是半透明红（alpha 180 直接画在笔迹上），
 * 也没有橡皮预览/图层合成画笔，合并会让两条绘制路径互相牵扯。
 */
private class MaskPreviewPaints {
    /** 蒙版笔迹：半透明红 */
    val maskPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.argb(180, 255, 0, 0)
        style = android.graphics.Paint.Style.STROKE
        strokeJoin = android.graphics.Paint.Join.ROUND
        strokeCap = android.graphics.Paint.Cap.ROUND
        isAntiAlias = true
    }

    /** 橡皮擦落笔：CLEAR 模式切穿图层（Xfermode 为 native 对象，随画笔一起只建一次） */
    val clearPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.TRANSPARENT
        style = android.graphics.Paint.Style.STROKE
        strokeJoin = android.graphics.Paint.Join.ROUND
        strokeCap = android.graphics.Paint.Cap.ROUND
        isAntiAlias = true
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
    }
}

/**
 * A read-only composable that displays a source image with mask overlay.
 * This is used for previewing the combined source image and mask without allowing editing.
 * The image is scaled to fill the container (center crop) and clipped to bounds.
 *
 * @param sourceImage The source image to display
 * @param maskPaths List of paths that make up the current mask (in image coordinates)
 * @param modifier Modifier for the canvas
 */
@Composable
fun MaskPreview(
    sourceImage: Bitmap?,
    maskPaths: List<MaskPathData>,
    modifier: Modifier = Modifier
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // 绘制缓存：画笔（含 Xfermode）用 remember 持有，不随每帧重建
    val paints = remember { MaskPreviewPaints() }

    // Calculate image rect for centerCrop scaling (fills the canvas, may crop image)
    fun calculateImageRect(canvasWidth: Int, canvasHeight: Int, imageWidth: Int, imageHeight: Int): Rect {
        if (imageWidth <= 0 || imageHeight <= 0 || canvasWidth <= 0 || canvasHeight <= 0) {
            return Rect(0f, 0f, canvasWidth.toFloat(), canvasHeight.toFloat())
        }

        val canvasAspect = canvasWidth.toFloat() / canvasHeight
        val imageAspect = imageWidth.toFloat() / imageHeight

        return if (imageAspect > canvasAspect) {
            // Image is wider - scale to fill height, crop sides
            val scaledWidth = canvasHeight * imageAspect
            val left = (canvasWidth - scaledWidth) / 2
            Rect(left, 0f, left + scaledWidth, canvasHeight.toFloat())
        } else {
            // Image is taller - scale to fill width, crop top/bottom
            val scaledHeight = canvasWidth / imageAspect
            val top = (canvasHeight - scaledHeight) / 2
            Rect(0f, top, canvasWidth.toFloat(), top + scaledHeight)
        }
    }

    Canvas(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { size ->
                canvasSize = size
            }
    ) {
        val imgRect = sourceImage?.let { img ->
            calculateImageRect(size.width.toInt(), size.height.toInt(), img.width, img.height)
        } ?: Rect.Zero

        // Draw source image
        sourceImage?.let { img ->
            drawImage(
                image = img.asImageBitmap(),
                dstOffset = IntOffset(imgRect.left.toInt(), imgRect.top.toInt()),
                dstSize = IntSize(imgRect.width.toInt(), imgRect.height.toInt())
            )
        }

        // Draw mask paths using native canvas for proper transformation
        if (sourceImage != null && imgRect.width > 0 && imgRect.height > 0) {
            // 画笔为 remember 缓存（MaskPreviewPaints），style / strokeWidth 在使用前重设
            val maskPaint = paints.maskPaint

            // Paint for erasing (cuts through the layer)
            val clearPaint = paints.clearPaint

            // Save layer for the mask overlay - this allows CLEAR mode to work
            val layerBounds = android.graphics.RectF(0f, 0f, size.width, size.height)
            drawContext.canvas.nativeCanvas.saveLayer(layerBounds, null)

            // Clip to canvas bounds
            drawContext.canvas.nativeCanvas.clipRect(0f, 0f, size.width, size.height)

            // Transform to image coordinates
            drawContext.canvas.nativeCanvas.translate(imgRect.left, imgRect.top)
            drawContext.canvas.nativeCanvas.scale(
                imgRect.width / sourceImage.width,
                imgRect.height / sourceImage.height
            )

            // Draw all paths in order - this allows paint after erase to work correctly
            maskPaths.forEach { pathData ->
                val paint = if (pathData.isEraser) clearPaint else maskPaint
                // P1-25：反相的整图覆盖笔是填充语义（与 MaskPaintCanvas / 蒙版生成同口径）
                paint.style = if (pathData.isFill) {
                    android.graphics.Paint.Style.FILL
                } else {
                    android.graphics.Paint.Style.STROKE
                }
                paint.strokeWidth = pathData.brushSize
                drawContext.canvas.nativeCanvas.drawPath(pathData.path, paint)
            }

            maskPaint.style = android.graphics.Paint.Style.STROKE
            clearPaint.style = android.graphics.Paint.Style.STROKE

            drawContext.canvas.nativeCanvas.restore()
        }
    }
}
