package sh.hnet.comfychair.ui.components.shared

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable

/**
 * 底部弹窗（ModalBottomSheet）内容的统一安全区。
 *
 * 背景：生成页的选项弹窗原先写死 `contentWindowInsets = { WindowInsets(0, 0, 0, 0) }`，
 * 弹窗内容一直铺到屏幕物理边缘。手势导航下最后一行压在底部手势条上、三键导航下直接
 * 被 48dp 的导航栏挡住（内容虽然能滚，但滚到底也还是挡住）。
 *
 * 方案：只取 `safeDrawing` 的**底部 + 左右**两条边——
 * - 底部包含导航栏（手势条 / 三键栏）与输入法（ime）占据的高度，键盘弹起时内容同步上移；
 * - 左右包含横屏时的刘海 / 侧边导航栏；
 * - **不取顶部**：弹窗自身的高度约束已经避开状态栏，再吃顶部 inset 会白白缩掉一截内容。
 *
 * 用法：`ModalBottomSheet(contentWindowInsets = { bottomSheetSafeDrawingInsets() }) { ... }`
 */
@Composable
fun bottomSheetSafeDrawingInsets(): WindowInsets =
    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
