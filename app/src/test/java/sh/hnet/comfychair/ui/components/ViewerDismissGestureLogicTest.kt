package sh.hnet.comfychair.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下滑脱离手势的三条纯判定规则（实现见 ViewerDismissGestureLogic.kt）：
 * 1. 方向锁定——水平必须让给 Pager 翻页，只有明显垂直才进脱离拖动；
 * 2. 松手关闭——位移或甩速任一达标即关，否则弹回；
 * 3. 位移→缩放/透明度映射——两端 clamp，不能缩没了或完全消失。
 */
class ViewerDismissGestureLogicTest {

    // ---------- 1. 方向锁定 ----------

    @Test
    fun `累计位移没过阈值不算方向`() {
        // 8dp 阈值以内都是"未决"，此时既不 consume 也不动图片，轻点不受影响
        assertEquals(
            ViewerPanDirection.UNDECIDED,
            ViewerDismissGesture.resolvePanDirection(
                accumulatedDx = 5f,
                accumulatedDy = 6f,
                thresholdPx = ViewerDismissGesture.DIRECTION_LOCK_THRESHOLD_DP
            )
        )
    }

    @Test
    fun `水平主导锁定为水平`() {
        // dx=50 明显大于 dy，手势应整体让给 Pager 翻页
        assertEquals(
            ViewerPanDirection.HORIZONTAL,
            ViewerDismissGesture.resolvePanDirection(50f, 10f, 8f)
        )
    }

    @Test
    fun `垂直主导锁定为垂直`() {
        // dy=50 明显大于 dx，进入下滑脱离拖动
        assertEquals(
            ViewerPanDirection.VERTICAL,
            ViewerDismissGesture.resolvePanDirection(10f, 50f, 8f)
        )
    }

    @Test
    fun `斜向偏水平时算水平`() {
        // |dy|=55 未超过 |dx|*1.2=60：斜向偏水平一律算水平，不抢翻页
        assertEquals(
            ViewerPanDirection.HORIZONTAL,
            ViewerDismissGesture.resolvePanDirection(50f, 55f, 8f)
        )
    }

    @Test
    fun `恰好等于1点2倍偏置仍算水平`() {
        // |dy| = |dx| * 1.2 的边界：判定条件是"大于"，相等不给垂直
        assertEquals(
            ViewerPanDirection.HORIZONTAL,
            ViewerDismissGesture.resolvePanDirection(50f, 60f, 8f)
        )
    }

    // ---------- 2. 松手是否关闭 ----------

    /** 便于换算：屏高 2000px 时 22% = 440px；速度阈值按 3x 密度取 2700px/s */
    private val screenHeight = 2000f
    private val velocityThreshold =
        ViewerDismissGesture.DISMISS_VELOCITY_DP_PER_SEC * 3f

    @Test
    fun `位移达到屏高22个百分点就关闭即使没有甩速`() {
        // 慢慢拖过 22% 线，松手速度为 0 也要关
        assertTrue(
            ViewerDismissGesture.shouldDismiss(
                dy = screenHeight * ViewerDismissGesture.DISMISS_DISTANCE_RATIO + 1f,
                screenHeightPx = screenHeight,
                velocityYDownPxPerSec = 0f,
                velocityThresholdPxPerSec = velocityThreshold
            )
        )
    }

    @Test
    fun `向下甩速够快就关闭即使位移很小`() {
        // 快速下甩（fling）：没拖多远也关
        assertTrue(
            ViewerDismissGesture.shouldDismiss(
                dy = 100f,
                screenHeightPx = screenHeight,
                velocityYDownPxPerSec = velocityThreshold + 1f,
                velocityThresholdPxPerSec = velocityThreshold
            )
        )
    }

    @Test
    fun `位移和速度都不达标必须弹回`() {
        assertFalse(
            ViewerDismissGesture.shouldDismiss(
                dy = screenHeight * 0.1f,
                screenHeightPx = screenHeight,
                velocityYDownPxPerSec = velocityThreshold * 0.5f,
                velocityThresholdPxPerSec = velocityThreshold
            )
        )
    }

    @Test
    fun `向上拖或向上甩永远不关闭`() {
        // 负值 = 向上：距离条件为假；向上甩速度为负也追不上正向阈值
        assertFalse(
            ViewerDismissGesture.shouldDismiss(
                dy = -500f,
                screenHeightPx = screenHeight,
                velocityYDownPxPerSec = -velocityThreshold * 2f,
                velocityThresholdPxPerSec = velocityThreshold
            )
        )
    }

    // ---------- 3. 位移 → 缩放/透明度映射 ----------

    @Test
    fun `零位移保持原始大小与不透明`() {
        val visuals = ViewerDismissGesture.dismissTransform(0f, screenHeight)
        assertEquals(1f, visuals.scale, 0.0001f)
        assertEquals(1f, visuals.alpha, 0.0001f)
    }

    @Test
    fun `拖到半屏按比例缩小变淡`() {
        // 半屏：scale = 1 - 0.5*0.35 = 0.825；alpha = 1 - 0.5*0.8 = 0.6
        val visuals = ViewerDismissGesture.dismissTransform(screenHeight * 0.5f, screenHeight)
        assertEquals(0.825f, visuals.scale, 0.0001f)
        assertEquals(0.6f, visuals.alpha, 0.0001f)
    }

    @Test
    fun `位移超过屏高被clamp在下限`() {
        // 拖出一屏多：缩放不低于 0.65、不透明度不低于 0.2（图片永远可辨）
        val visuals = ViewerDismissGesture.dismissTransform(screenHeight * 1.5f, screenHeight)
        assertEquals(0.65f, visuals.scale, 0.0001f)
        assertEquals(0.2f, visuals.alpha, 0.0001f)
    }

    @Test
    fun `向上位移或屏高无效都按原样处理`() {
        // 向上拖不进脱离模式；屏高未测得（0）时也不能出 NaN 污染 graphicsLayer
        val upward = ViewerDismissGesture.dismissTransform(-200f, screenHeight)
        assertEquals(1f, upward.scale, 0.0001f)
        assertEquals(1f, upward.alpha, 0.0001f)

        val noSize = ViewerDismissGesture.dismissTransform(100f, 0f)
        assertEquals(1f, noSize.scale, 0.0001f)
        assertEquals(1f, noSize.alpha, 0.0001f)
    }

    // ---------- 4. 捏合返回（C1） ----------

    @Test
    fun `单指捏合不触发退出`() {
        // 单指一律交给既有方向锁定逻辑（水平让给 Pager、垂直走下滑脱离）
        assertFalse(
            ViewerDismissGesture.shouldDismissByPinch(
                scaleAtGestureStart = 1f,
                gestureScale = 0.5f,
                pointerCount = 1
            )
        )
    }

    @Test
    fun `起手未放大双指捏过阈值触发退出`() {
        // 起手未放大（≤1.05）+ 累计缩小越过 0.92：iOS 相册式的"捏合退出"。
        // v4.15.x 既有语义保持——起点锁定只拦"起手已放大"的手势
        assertTrue(
            ViewerDismissGesture.shouldDismissByPinch(
                scaleAtGestureStart = 1f,
                gestureScale = 0.9f,
                pointerCount = 2
            )
        )
    }

    @Test
    fun `双指放大不触发退出`() {
        // 张开手指是放大意图，与退出无关
        assertFalse(
            ViewerDismissGesture.shouldDismissByPinch(
                scaleAtGestureStart = 1f,
                gestureScale = 1.1f,
                pointerCount = 2
            )
        )
    }

    @Test
    fun `起手已放大捏合缩小不退出`() {
        // R-B23a2 核心场景：按下瞬间已放大（> 1.05）→ 整段手势只做"缩回 1f"，
        // 即使累计倍率已越过 0.92 退出阈值也绝不触发关闭（起手 1.1 模拟双击放大态）
        assertFalse(
            ViewerDismissGesture.shouldDismissByPinch(
                scaleAtGestureStart = 1.1f,
                gestureScale = 0.5f,
                pointerCount = 2
            )
        )
    }

    @Test
    fun `起手已放大累积极小也不退出`() {
        // 起手 2x（双击放大后的典型值）+ 手势累计只缩了一点点：放大态捏合从源头被锁死
        assertFalse(
            ViewerDismissGesture.shouldDismissByPinch(
                scaleAtGestureStart = 2f,
                gestureScale = 0.99f,
                pointerCount = 2
            )
        )
    }

    @Test
    fun `起手已放大一口气捏过阈值仍不退出`() {
        // R-B23a2 回归场景：双击放大到 2x 后一口气捏到 0.45——缩回 1f 之后多余的
        // zoom 帧继续累计越过了 0.92，但起点锁定要求整段手势不触发关闭，只停回 Fit
        assertFalse(
            ViewerDismissGesture.shouldDismissByPinch(
                scaleAtGestureStart = 2f,
                gestureScale = 0.45f,
                pointerCount = 2
            )
        )
    }

    @Test
    fun `恰好等于阈值触发退出`() {
        // gestureScale == PINCH_DISMISS_SCALE 的边界：判定条件是"小于等于"
        assertTrue(
            ViewerDismissGesture.shouldDismissByPinch(
                scaleAtGestureStart = 1f,
                gestureScale = ViewerDismissGesture.PINCH_DISMISS_SCALE,
                pointerCount = 2
            )
        )
    }

    @Test
    fun `略大于阈值不退出`() {
        // 缩小量没到 8%：不做退出，等手指继续捏
        assertFalse(
            ViewerDismissGesture.shouldDismissByPinch(
                scaleAtGestureStart = 1f,
                gestureScale = 0.93f,
                pointerCount = 2
            )
        )
    }

    @Test
    fun `三指捏合同样触发退出`() {
        // pointerCount >= 2 都算捏合手势
        assertTrue(
            ViewerDismissGesture.shouldDismissByPinch(
                scaleAtGestureStart = 1f,
                gestureScale = 0.8f,
                pointerCount = 3
            )
        )
    }

    @Test
    fun `起手恰好等于放大门槛仍视为未放大`() {
        // scaleAtGestureStart == PINCH_ZOOMED_THRESHOLD 的边界：判定条件是"大于"，
        // 起手恰好 1.05 不算已放大，捏合关闭语义照常生效
        assertTrue(
            ViewerDismissGesture.shouldDismissByPinch(
                scaleAtGestureStart = ViewerDismissGesture.PINCH_ZOOMED_THRESHOLD,
                gestureScale = 0.5f,
                pointerCount = 2
            )
        )
    }

    @Test
    fun `没有手指按下不触发退出`() {
        // pointerCount < 2（含 0）一律 false，兜住异常输入
        assertFalse(
            ViewerDismissGesture.shouldDismissByPinch(
                scaleAtGestureStart = 1f,
                gestureScale = 0.5f,
                pointerCount = 0
            )
        )
    }

    // ---------- 5. 并排预览三级手势（D1~D5） ----------

    /** 视口宽 1000px，便于换算露边边界 */
    private val viewport = 1000f

    @Test
    fun `并排进度与露边比例的映射`() {
        // 全屏 / 并排标准档 / 临时缩小档 三个关键点
        assertEquals(0f, ViewerSideBySide.edgeFraction(0f), 0.0001f)
        assertEquals(0.15f, ViewerSideBySide.edgeFraction(1f), 0.0001f)
        assertEquals(0.225f, ViewerSideBySide.edgeFraction(ViewerSideBySide.MAX_PROGRESS), 0.0001f)
        // 中间页宽度与露边互补：70% / 55%
        assertEquals(ViewerSideBySide.PAGE_FRACTION, ViewerSideBySide.pageFraction(1f), 0.0001f)
        assertEquals(0.55f, ViewerSideBySide.pageFraction(1.5f), 0.0001f)
        assertEquals(1f, ViewerSideBySide.pageFraction(0f), 0.0001f)
    }

    @Test
    fun `并排进度越界被钳制`() {
        // 负进度（向上方向异常值）与超上限都 clamp 在 [0, 1.5]
        assertEquals(0f, ViewerSideBySide.edgeFraction(-0.5f), 0.0001f)
        assertEquals(
            ViewerSideBySide.edgeFraction(ViewerSideBySide.MAX_PROGRESS),
            ViewerSideBySide.edgeFraction(99f),
            0.0001f
        )
    }

    @Test
    fun `未放大态合拢到7成恰好走满并排进度`() {
        // 手感设计：合拢 30% = 并排档，跟手期间视觉缩放 ≈ 手指缩放
        assertEquals(1f, ViewerSideBySide.progressFromGesture(0f, 0.7f), 0.0001f)
        // 半程：合拢 15% → 进度 0.5（吸附分界）
        assertEquals(0.5f, ViewerSideBySide.progressFromGesture(0f, 0.85f), 0.0001f)
        // 没捏（scale=1）进度不动
        assertEquals(0f, ViewerSideBySide.progressFromGesture(0f, 1f), 0.0001f)
    }

    @Test
    fun `并排态继续合拢进入临时缩小档`() {
        // 基线 1（并排）再合拢 15% → 进度 1.5（页宽 55%），与规格第 ③ 步对应
        assertEquals(
            ViewerSideBySide.MAX_PROGRESS,
            ViewerSideBySide.progressFromGesture(1f, 0.85f),
            0.0001f
        )
    }

    @Test
    fun `并排态张开手指回退到全屏`() {
        // 张开 30% → 进度恰好归 0（回全屏，状态机第 ④ 步）
        assertEquals(0f, ViewerSideBySide.progressFromGesture(1f, 1.3f), 0.0001f)
        // 张开一半 → 进度 0.5（吸回并排）
        assertEquals(0.5f, ViewerSideBySide.progressFromGesture(1f, 1.15f), 0.0001f)
    }

    @Test
    fun `捏合进度换算两端钳制`() {
        // 手势倍率超出累计上限时进度封顶 1.5 / 破底 0
        assertEquals(
            ViewerSideBySide.MAX_PROGRESS,
            ViewerSideBySide.progressFromGesture(1f, ViewerSideBySide.GESTURE_SCALE_MIN),
            0.0001f
        )
        assertEquals(0f, ViewerSideBySide.progressFromGesture(0f, 99f), 0.0001f)
    }

    @Test
    fun `松手吸附过半进并排`() {
        // 规格第 ② 步：捏过半（进度 ≥ 0.5）松手吸附并排
        assertEquals(1f, ViewerSideBySide.settleProgress(0.5f), 0.0001f)
        assertEquals(1f, ViewerSideBySide.settleProgress(0.51f), 0.0001f)
        assertEquals(1f, ViewerSideBySide.settleProgress(ViewerSideBySide.MAX_PROGRESS), 0.0001f)
    }

    @Test
    fun `松手吸附不过半回全屏`() {
        assertEquals(0f, ViewerSideBySide.settleProgress(0f), 0.0001f)
        assertEquals(0f, ViewerSideBySide.settleProgress(0.49f), 0.0001f)
        assertEquals(0f, ViewerSideBySide.settleProgress(-1f), 0.0001f)
    }

    @Test
    fun `并排态继续合拢松手必弹回并排不回图库`() {
        // 规格强调：第 ③ 步临时缩小（进度 1.5）松手 → 吸附 1.0（并排），绝不能判定成回图库
        assertEquals(1f, ViewerSideBySide.settleProgress(1.5f), 0.0001f)
    }

    @Test
    fun `并排态点击命中分区`() {
        // 并排态（进度 1）：露边各 15% → 左区 [0, 150)、右区 (850, 1000]
        assertEquals(-1, ViewerSideBySide.tapZone(100f, viewport, 1f))
        assertEquals(0, ViewerSideBySide.tapZone(500f, viewport, 1f))
        assertEquals(1, ViewerSideBySide.tapZone(900f, viewport, 1f))
        // 边界：恰在露边边界上算居中（严格小于才判露边）
        assertEquals(0, ViewerSideBySide.tapZone(150f, viewport, 1f))
        assertEquals(0, ViewerSideBySide.tapZone(850f, viewport, 1f))
        assertEquals(-1, ViewerSideBySide.tapZone(149.9f, viewport, 1f))
        assertEquals(1, ViewerSideBySide.tapZone(850.1f, viewport, 1f))
    }

    @Test
    fun `全屏进度下点击一律判居中`() {
        // 进度 0（全屏）无露边区，任何位置都是居中页
        assertEquals(0, ViewerSideBySide.tapZone(10f, viewport, 0f))
        assertEquals(0, ViewerSideBySide.tapZone(990f, viewport, 0f))
    }

    @Test
    fun `视口宽度无效时点击兜底居中`() {
        // 宽度未测得（0/负）时不判露边，避免误触发翻页
        assertEquals(0, ViewerSideBySide.tapZone(10f, 0f, 1f))
        assertEquals(0, ViewerSideBySide.tapZone(10f, -100f, 1f))
    }

    @Test
    fun `临时缩小档点击命中分区随进度收窄`() {
        // 进度 1.5：露边 22.5% → 左区 [0, 225)
        assertEquals(-1, ViewerSideBySide.tapZone(200f, viewport, 1.5f))
        assertEquals(0, ViewerSideBySide.tapZone(300f, viewport, 1.5f))
    }

    @Test
    fun `异常手势倍率不污染并排进度`() {
        // 0 / 负数 / NaN / 无穷：一律视为"没捏"，返回起点进度（NaN 进 graphicsLayer 会让整层消失）
        assertEquals(1f, ViewerSideBySide.progressFromGesture(1f, 0f), 0.0001f)
        assertEquals(1f, ViewerSideBySide.progressFromGesture(1f, -2f), 0.0001f)
        assertEquals(0f, ViewerSideBySide.progressFromGesture(0f, Float.NaN), 0.0001f)
        assertEquals(1f, ViewerSideBySide.progressFromGesture(1f, Float.POSITIVE_INFINITY), 0.0001f)
    }

    @Test
    fun `起点进度异常按全屏兜底`() {
        assertEquals(0f, ViewerSideBySide.progressFromGesture(Float.NaN, 1f), 0.0001f)
        // 起点越界先钳进 [0, 1.5] 再换算
        assertEquals(0f, ViewerSideBySide.progressFromGesture(-9f, 1f), 0.0001f)
        assertEquals(
            ViewerSideBySide.MAX_PROGRESS,
            ViewerSideBySide.progressFromGesture(9f, 1f),
            0.0001f
        )
    }

    @Test
    fun `异常进度不产生NaN露边`() {
        assertEquals(0f, ViewerSideBySide.edgeFraction(Float.NaN), 0.0001f)
        assertEquals(1f, ViewerSideBySide.pageFraction(Float.NaN), 0.0001f)
        assertEquals(0f, ViewerSideBySide.edgeFraction(Float.POSITIVE_INFINITY), 0.0001f)
    }

    // ---------- 点击坐标反算（设备实测数值，回归护栏） ----------
    //
    // 真机实测数据（模拟器 320x640，并排进度 1.0）：
    //   在屏幕 x=300 点右露边 → ImageViewer 上报层内坐标 40，页距 +1
    //   旧实现只加"页位移"、没还原 70% 缩放 → 换算出 264 → 判成"点了中间图"，
    //   表现为"点露边页只放大不翻页"。下面用这组实测值钉住换算公式。

    @Test
    fun `层内坐标经缩放与页位移还原为视口坐标`() {
        // 实测：屏幕 300 上的点击，在右邻页（页距 +1）里上报为 40
        assertEquals(
            300f,
            ViewerSideBySide.viewportXFromImageLocal(
                imageLocalX = 40f, viewportWidth = 320f, fraction = 0.7f, pageDistance = 1f
            ),
            0.01f
        )
        // 当前页（页距 0）：层内 40 → 视口 (40-160)*0.7+160 = 76
        assertEquals(
            76f,
            ViewerSideBySide.viewportXFromImageLocal(
                imageLocalX = 40f, viewportWidth = 320f, fraction = 0.7f, pageDistance = 0f
            ),
            0.01f
        )
        // 当前页中心点在任何档位下都落在视口中心（缩放以中心为锚点）
        assertEquals(
            160f,
            ViewerSideBySide.viewportXFromImageLocal(
                imageLocalX = 160f, viewportWidth = 320f, fraction = 0.55f, pageDistance = 0f
            ),
            0.01f
        )
    }

    @Test
    fun `露边点击换算后落进对应分区`() {
        // 左露边：屏幕上点 x=20，当前页层内上报 (20-160)/0.7+160 = -40 → 换算回 20 → 左区
        val localLeft = (20f - 160f) / 0.7f + 160f
        val xLeft = ViewerSideBySide.viewportXFromImageLocal(localLeft, 320f, 0.7f, 0f)
        assertEquals(20f, xLeft, 0.01f)
        assertEquals(-1, ViewerSideBySide.tapZone(xLeft, 320f, 1f))

        // 右露边：屏幕上点 x=300 由右邻页（页距 +1）接收，层内 40 → 换算回 300 → 右区
        val xRight = ViewerSideBySide.viewportXFromImageLocal(40f, 320f, 0.7f, 1f)
        assertEquals(300f, xRight, 0.01f)
        assertEquals(1, ViewerSideBySide.tapZone(xRight, 320f, 1f))
    }

    @Test
    fun `全屏档位下坐标换算为恒等变换`() {
        // fraction=1（全屏）+ 页距 0：层内坐标 == 视口坐标
        for (x in listOf(0f, 40f, 160f, 300f, 319f)) {
            assertEquals(
                x,
                ViewerSideBySide.viewportXFromImageLocal(x, 320f, 1f, 0f),
                0.01f
            )
        }
    }

    @Test
    fun `坐标换算对异常输入兜底`() {
        // 视口宽 0：返回 0（调用方随后会被 tapZone 判为居中）
        assertEquals(
            0f,
            ViewerSideBySide.viewportXFromImageLocal(40f, 0f, 0.7f, 1f),
            0.0001f
        )
        // fraction 为 NaN / 非正 → 按全屏 1 处理，绝不产出 NaN
        assertEquals(
            40f,
            ViewerSideBySide.viewportXFromImageLocal(40f, 320f, Float.NaN, 0f),
            0.01f
        )
        assertEquals(
            40f,
            ViewerSideBySide.viewportXFromImageLocal(40f, 320f, -1f, 0f),
            0.01f
        )
        // 页距 NaN → 按 0 处理
        assertEquals(
            76f,
            ViewerSideBySide.viewportXFromImageLocal(40f, 320f, 0.7f, Float.NaN),
            0.01f
        )
    }

    // ---------- 手势起点语义锁定（D3） ----------

    @Test    fun `未放大态手势允许进并排`() {
        assertTrue(ViewerSideBySide.canEngageFromStart(1f))
        assertTrue(ViewerSideBySide.canEngageFromStart(1.04f))
        // 恰好等于放大门槛仍算未放大（与 shouldDismissByPinch 的门槛口径一致）
        assertTrue(ViewerSideBySide.canEngageFromStart(ViewerDismissGesture.PINCH_ZOOMED_THRESHOLD))
    }

    @Test
    fun `已放大态手势整段只做zoom不进并排`() {
        // 双击放大到 crop（实测 cropScale=2）后合拢：整个手势都不该切成并排
        assertFalse(ViewerSideBySide.canEngageFromStart(1.06f))
        assertFalse(ViewerSideBySide.canEngageFromStart(2f))
        assertFalse(ViewerSideBySide.canEngageFromStart(5f))
    }

    @Test
    fun `起点缩放异常按允许处理`() {
        // NaN/无穷不会有合法的放大含义，按未放大处理（避免手势彻底失灵）
        assertTrue(ViewerSideBySide.canEngageFromStart(Float.NaN))
        assertTrue(ViewerSideBySide.canEngageFromStart(Float.POSITIVE_INFINITY))
    }

    // ---------- 双指意图锁定（Q1-2，v4.16.5） ----------
    //
    // 规格：一段手势第一帧产生非恒等 zoom 时定一次意图，整段手势不翻转——
    // 全屏 + 张开 → 放大（恢复被 v4.16.2 吞掉的行为）；全屏 + 合拢 → 并排；
    // 起点已在并排进度上 → 整段交并排换算（张开 = 回全屏的合法输入）。

    @Test
    fun `全屏起点张开判为放大`() {
        // v4.16.2~v4.16.4 的回归根因：全屏态双指张开被当成并排进度上报
        //（zoom>1 算出负进度钳回 0 且事件被 consume），双指放大从此不可达
        assertEquals(
            ViewerSideBySide.PinchIntent.ZOOM_IN,
            ViewerSideBySide.resolvePinchIntent(
                progressAtStart = 0f, zoom = 1.08f,
                currentIntent = ViewerSideBySide.PinchIntent.UNDECIDED
            )
        )
    }

    @Test
    fun `全屏起点合拢判为并排`() {
        assertEquals(
            ViewerSideBySide.PinchIntent.SIDE_BY_SIDE,
            ViewerSideBySide.resolvePinchIntent(
                progressAtStart = 0f, zoom = 0.92f,
                currentIntent = ViewerSideBySide.PinchIntent.UNDECIDED
            )
        )
    }

    @Test
    fun `并排态起点整段交并排换算`() {
        // 并排态（progress=1）张开：是"从并排回全屏"的合法输入，不走放大路径——
        // 沿用 progressFromGesture 现有语义（baseProgress=1 + zoom>1 → 进度下降）
        assertEquals(
            ViewerSideBySide.PinchIntent.SIDE_BY_SIDE,
            ViewerSideBySide.resolvePinchIntent(
                progressAtStart = 1f, zoom = 1.15f,
                currentIntent = ViewerSideBySide.PinchIntent.UNDECIDED
            )
        )
        // 并排态继续合拢：临时缩小档（progress → 1.5，松手弹回 1.0）
        assertEquals(
            ViewerSideBySide.PinchIntent.SIDE_BY_SIDE,
            ViewerSideBySide.resolvePinchIntent(
                progressAtStart = 1f, zoom = 0.9f,
                currentIntent = ViewerSideBySide.PinchIntent.UNDECIDED
            )
        )
        // 吸附动画中途按下（中间进度）同样交并排换算
        assertEquals(
            ViewerSideBySide.PinchIntent.SIDE_BY_SIDE,
            ViewerSideBySide.resolvePinchIntent(
                progressAtStart = 0.3f, zoom = 1.2f,
                currentIntent = ViewerSideBySide.PinchIntent.UNDECIDED
            )
        )
    }

    @Test
    fun `意图锁定后整段手势不翻转`() {
        // 先张开锁定放大：后续帧的合拢 zoom 不允许把意图改成并排（逐帧切换会抖）
        assertEquals(
            ViewerSideBySide.PinchIntent.ZOOM_IN,
            ViewerSideBySide.resolvePinchIntent(
                progressAtStart = 0f, zoom = 0.85f,
                currentIntent = ViewerSideBySide.PinchIntent.ZOOM_IN
            )
        )
        // 先合拢锁定并排：后续张开帧也不翻转
        assertEquals(
            ViewerSideBySide.PinchIntent.SIDE_BY_SIDE,
            ViewerSideBySide.resolvePinchIntent(
                progressAtStart = 0f, zoom = 1.3f,
                currentIntent = ViewerSideBySide.PinchIntent.SIDE_BY_SIDE
            )
        )
    }

    @Test
    fun `无方向信号保持未决`() {
        // zoom 恰为 1（只有双指平移）与异常值都不产生意图
        assertEquals(
            ViewerSideBySide.PinchIntent.UNDECIDED,
            ViewerSideBySide.resolvePinchIntent(
                progressAtStart = 0f, zoom = 1f,
                currentIntent = ViewerSideBySide.PinchIntent.UNDECIDED
            )
        )
        assertEquals(
            ViewerSideBySide.PinchIntent.UNDECIDED,
            ViewerSideBySide.resolvePinchIntent(
                progressAtStart = 0f, zoom = Float.NaN,
                currentIntent = ViewerSideBySide.PinchIntent.UNDECIDED
            )
        )
    }

    @Test
    fun `档位比例反解进度与正向换算互逆`() {
        // pageFraction(progress) 的逆运算：全屏 1 → 0，并排 0.7 → 1，临时档 0.55 → 1.5
        assertEquals(
            0f,
            ViewerSideBySide.progressFromPageFraction(ViewerSideBySide.pageFraction(0f)),
            0.0001f
        )
        assertEquals(
            1f,
            ViewerSideBySide.progressFromPageFraction(ViewerSideBySide.pageFraction(1f)),
            0.0001f
        )
        assertEquals(
            ViewerSideBySide.MAX_PROGRESS,
            ViewerSideBySide.progressFromPageFraction(ViewerSideBySide.pageFraction(1.5f)),
            0.0001f
        )
    }

    @Test
    fun `档位反解对异常输入按全屏兜底`() {
        // NaN / ≥1 一律按全屏 0 处理——宁可少进并排，不误判方向
        assertEquals(0f, ViewerSideBySide.progressFromPageFraction(Float.NaN), 0.0001f)
        assertEquals(0f, ViewerSideBySide.progressFromPageFraction(1f), 0.0001f)
        assertEquals(0f, ViewerSideBySide.progressFromPageFraction(1.2f), 0.0001f)
    }

    // ---------- 6. 下拉透出遮罩分段映射（E2，B-23b 第三批） ----------

    @Test
    fun `拖动9个百分点仍保持纯黑`() {
        // 边界左端：进度 < 10% → alpha 1.0（E1 全屏态背景纯黑）
        assertEquals(
            1f,
            ViewerDismissGesture.dismissMaskTargetAlpha(screenHeight * 0.09f, screenHeight),
            0.0001f
        )
    }

    @Test
    fun `拖动恰好10个百分点目标透出`() {
        // 边界点：判定条件是"≥"，恰好 10% 就翻到透出目标 0.2（图库透出约 80%）
        assertEquals(
            ViewerDismissGesture.REVEAL_MASK_ALPHA,
            ViewerDismissGesture.dismissMaskTargetAlpha(
                screenHeight * ViewerDismissGesture.REVEAL_PROGRESS_RATIO, screenHeight
            ),
            0.0001f
        )
        assertEquals(
            ViewerDismissGesture.REVEAL_MASK_ALPHA,
            ViewerDismissGesture.dismissMaskTargetAlpha(screenHeight * 0.11f, screenHeight),
            0.0001f
        )
    }

    @Test
    fun `零位移与向上拖都不透出`() {
        // 0 与负值（向上拖）都算没进透出段
        assertEquals(1f, ViewerDismissGesture.dismissMaskTargetAlpha(0f, screenHeight), 0.0001f)
        assertEquals(1f, ViewerDismissGesture.dismissMaskTargetAlpha(-100f, screenHeight), 0.0001f)
    }

    @Test
    fun `异常输入遮罩兜底纯黑`() {
        // NaN / 屏高未测得（0 / 负 / NaN）：宁可黑也不透出错帧
        assertEquals(1f, ViewerDismissGesture.dismissMaskTargetAlpha(Float.NaN, screenHeight), 0.0001f)
        assertEquals(1f, ViewerDismissGesture.dismissMaskTargetAlpha(100f, 0f), 0.0001f)
        assertEquals(1f, ViewerDismissGesture.dismissMaskTargetAlpha(100f, -1f), 0.0001f)
        assertEquals(1f, ViewerDismissGesture.dismissMaskTargetAlpha(100f, Float.NaN), 0.0001f)
    }

    // ---------- 7. 位置飞回几何（E4，B-23b 第三批） ----------

    /** 便于换算：容器 320x640（模拟器实测），Fit 内容矩形 320x320，目标格子 80x80 */
    private val containerW = 320f
    private val containerH = 640f

    @Test
    fun `飞回目标因子按宽高各自对齐格子`() {
        val target = ViewerDismissGesture.flyBackTargetFactors(
            containerWidth = containerW,
            containerHeight = containerH,
            contentWidth = 320f,
            contentHeight = 320f,
            cellRect = ViewerRect(left = 120f, top = 280f, width = 80f, height = 80f)
        )
        // scale = 格子边 / 内容边；X/Y 各自算（格子是正方形、内容不一定，允许轻微变形
        // 与缩略图 Crop 语义一致）
        assertEquals(0.25f, target.scaleX, 0.0001f)
        assertEquals(0.25f, target.scaleY, 0.0001f)
        // 平移 = 格子中心 - 容器中心：(160, 320) - (160, 320) = 0
        assertEquals(0f, target.translationX, 0.0001f)
        assertEquals(0f, target.translationY, 0.0001f)
        assertEquals(1f, target.alpha, 0.0001f)
    }

    @Test
    fun `飞回目标因子平移对准偏离中心的格子`() {
        // 格子中心 (40, 140) → 平移 = (40-160, 140-320) = (-120, -180)
        val target = ViewerDismissGesture.flyBackTargetFactors(
            containerWidth = containerW,
            containerHeight = containerH,
            contentWidth = 320f,
            contentHeight = 640f,
            cellRect = ViewerRect(left = 0f, top = 100f, width = 80f, height = 80f)
        )
        assertEquals(-120f, target.translationX, 0.0001f)
        assertEquals(-180f, target.translationY, 0.0001f)
        // 内容是全屏比例 320x640 → X/Y 缩放不同（等边格子把内容拉成方形）
        assertEquals(0.25f, target.scaleX, 0.0001f)
        assertEquals(0.125f, target.scaleY, 0.0001f)
    }

    @Test
    fun `飞回目标因子异常尺寸返回恒等`() {
        // 内容 / 格子任一尺寸无效（0 / 负 / NaN）都不算数，返回恒等因子兜底
        val identity = ViewerDismissGesture.flyBackTargetFactors(
            containerWidth = containerW,
            containerHeight = containerH,
            contentWidth = 0f,
            contentHeight = 320f,
            cellRect = ViewerRect(0f, 0f, 80f, 80f)
        )
        assertEquals(1f, identity.scaleX, 0.0001f)
        assertEquals(1f, identity.scaleY, 0.0001f)
        assertEquals(0f, identity.translationX, 0.0001f)
        assertEquals(0f, identity.translationY, 0.0001f)
        assertEquals(1f, identity.alpha, 0.0001f)

        val identity2 = ViewerDismissGesture.flyBackTargetFactors(
            containerWidth = containerW,
            containerHeight = containerH,
            contentWidth = 320f,
            contentHeight = 320f,
            cellRect = ViewerRect(0f, 0f, Float.NaN, 80f)
        )
        assertEquals(1f, identity2.scaleX, 0.0001f)
    }

    @Test
    fun `飞回插值端点`() {
        // t=0 → 起始快照原样；t=1 → 目标因子（alpha 恢复到 1，飞回途中图片可见）
        val start = FlyBackStartFactors(
            scaleX = 0.9f, scaleY = 0.9f, translationX = 0f, translationY = 140f, alpha = 0.8f
        )
        val target = ViewerDismissGesture.flyBackTargetFactors(
            containerWidth = containerW,
            containerHeight = containerH,
            contentWidth = 320f,
            contentHeight = 320f,
            cellRect = ViewerRect(120f, 280f, 80f, 80f)
        )
        val atStart = ViewerDismissGesture.flyBackFactors(start, target, 0f)
        assertEquals(0.9f, atStart.scaleX, 0.0001f)
        assertEquals(140f, atStart.translationY, 0.0001f)
        assertEquals(0.8f, atStart.alpha, 0.0001f)

        val atEnd = ViewerDismissGesture.flyBackFactors(start, target, 1f)
        assertEquals(target.scaleX, atEnd.scaleX, 0.0001f)
        assertEquals(target.scaleY, atEnd.scaleY, 0.0001f)
        assertEquals(target.translationX, atEnd.translationX, 0.0001f)
        assertEquals(target.translationY, atEnd.translationY, 0.0001f)
        assertEquals(1f, atEnd.alpha, 0.0001f)
    }

    @Test
    fun `飞回插值中点是线性中值`() {
        val start = FlyBackStartFactors(
            scaleX = 1f, scaleY = 1f, translationX = 0f, translationY = 128f, alpha = 1f
        )
        val target = FlyBackFactors(
            scaleX = 0.5f, scaleY = 0.5f, translationX = -100f, translationY = -256f, alpha = 1f
        )
        val mid = ViewerDismissGesture.flyBackFactors(start, target, 0.5f)
        assertEquals(0.75f, mid.scaleX, 0.0001f)
        assertEquals(0.75f, mid.scaleY, 0.0001f)
        assertEquals(-50f, mid.translationX, 0.0001f)
        assertEquals(-64f, mid.translationY, 0.0001f)
        assertEquals(1f, mid.alpha, 0.0001f)
    }

    @Test
    fun `飞回插值t越界钳制且异常t兜底`() {
        val start = FlyBackStartFactors(1f, 1f, 0f, 0f, 1f)
        val target = FlyBackFactors(0.5f, 0.5f, -100f, -200f, 1f)
        // t > 1 / t < 0 都 clamp 到端点，不许过冲
        assertEquals(
            target.scaleX,
            ViewerDismissGesture.flyBackFactors(start, target, 1.5f).scaleX,
            0.0001f
        )
        assertEquals(
            start.scaleX,
            ViewerDismissGesture.flyBackFactors(start, target, -0.5f).scaleX,
            0.0001f
        )
        // NaN t 按"动画结束"处理（t=1）
        assertEquals(
            target.scaleX,
            ViewerDismissGesture.flyBackFactors(start, target, Float.NaN).scaleX,
            0.0001f
        )
    }

    @Test
    fun `飞回插值NaN因子不穿透`() {
        // 起始因子里混入 NaN（屏高未测得等异常路径）也不许把 NaN 带进 graphicsLayer
        val start = FlyBackStartFactors(
            scaleX = Float.NaN, scaleY = 1f, translationX = 0f,
            translationY = Float.POSITIVE_INFINITY, alpha = Float.NaN
        )
        val target = FlyBackFactors(0.5f, 0.5f, -100f, -200f, 1f)
        val mid = ViewerDismissGesture.flyBackFactors(start, target, 0.5f)
        assertEquals(0.75f, mid.scaleX, 0.0001f)   // NaN → 兜底 1f
        assertEquals(0.75f, mid.scaleY, 0.0001f)
        assertEquals(-100f, mid.translationY, 0.0001f) // 无穷 → 兜底 0f
        assertEquals(1f, mid.alpha, 0.0001f)       // NaN → 兜底 1f
    }

    @Test
    fun `飞回起点就是目标时全程定格`() {
        // 松手位置恰好已经在格子上（理论边界）：插值全程与目标重合
        val target = FlyBackFactors(0.5f, 0.5f, -100f, -200f, 1f)
        val start = FlyBackStartFactors(0.5f, 0.5f, -100f, -200f, 1f)
        val mid = ViewerDismissGesture.flyBackFactors(start, target, 0.5f)
        assertEquals(target.scaleX, mid.scaleX, 0.0001f)
        assertEquals(target.translationY, mid.translationY, 0.0001f)
    }

    // ---------- 8. 放大态水平边界判定（N6，v4.16.9） ----------

    /** 便于换算：2x 放大后水平余量 excessWidth = 100px（左界 -100 / 右界 +100） */
    private val excessWidth = 100f
    private val edgeTolerance = 0.5f

    @Test
    fun `刚好到界判贴边`() {
        // offsetX == excessWidth：constrainOffset 的 coerceIn 精确钳在此值，必须判贴边
        assertTrue(
            ViewerDismissGesture.isAtHorizontalEdge(excessWidth, excessWidth, edgeTolerance)
        )
        assertTrue(
            ViewerDismissGesture.isAtHorizontalEdge(-excessWidth, excessWidth, edgeTolerance)
        )
    }

    @Test
    fun `差1px不算贴边`() {
        // 容差 0.5px：差 1px 还有真实平移余地，判未贴边（继续正常平移）
        assertFalse(
            ViewerDismissGesture.isAtHorizontalEdge(excessWidth - 1f, excessWidth, edgeTolerance)
        )
        assertFalse(
            ViewerDismissGesture.isAtHorizontalEdge(-(excessWidth - 1f), excessWidth, edgeTolerance)
        )
    }

    @Test
    fun `超出边界判贴边`() {
        // 理论上 constrainOffset 不会输出超界值，判定对超界输入也要稳（true）
        assertTrue(
            ViewerDismissGesture.isAtHorizontalEdge(excessWidth + 1f, excessWidth, edgeTolerance)
        )
    }

    @Test
    fun `无水平余量时任何位置都算贴边`() {
        // crop 放大铺满宽度（excessWidth = 0）：水平无平移余地，左右拖全该让位翻页
        assertTrue(ViewerDismissGesture.isAtHorizontalEdge(0f, 0f, edgeTolerance))
        assertTrue(ViewerDismissGesture.isAtHorizontalEdge(0f, -1f, edgeTolerance))
    }

    @Test
    fun `异常输入判未贴边`() {
        // NaN 不产生让位信号：不确定时宁可继续平移，不让位是保守行为
        assertFalse(
            ViewerDismissGesture.isAtHorizontalEdge(Float.NaN, excessWidth, edgeTolerance)
        )
        assertFalse(
            ViewerDismissGesture.isAtHorizontalEdge(excessWidth, Float.NaN, edgeTolerance)
        )
    }

    @Test
    fun `贴右界向右拖产生正向过冲`() {
        assertEquals(
            30f,
            ViewerDismissGesture.horizontalEdgeOverscrollPan(excessWidth, excessWidth, 30f, edgeTolerance),
            0.0001f
        )
    }

    @Test
    fun `贴右界向左拖是往回平移不产生过冲`() {
        // 方向不一致（拖回屏内）：返回 0，调用方把累计清零，防"先右后左"抖动攒假过冲
        assertEquals(
            0f,
            ViewerDismissGesture.horizontalEdgeOverscrollPan(excessWidth, excessWidth, -30f, edgeTolerance),
            0.0001f
        )
    }

    @Test
    fun `贴左界向左拖产生负向过冲`() {
        assertEquals(
            -30f,
            ViewerDismissGesture.horizontalEdgeOverscrollPan(-excessWidth, excessWidth, -30f, edgeTolerance),
            0.0001f
        )
    }

    @Test
    fun `未贴边不产生过冲`() {
        assertEquals(
            0f,
            ViewerDismissGesture.horizontalEdgeOverscrollPan(excessWidth - 1f, excessWidth, 30f, edgeTolerance),
            0.0001f
        )
    }

    @Test
    fun `无余量时任意方向都是过冲`() {
        assertEquals(
            30f,
            ViewerDismissGesture.horizontalEdgeOverscrollPan(0f, 0f, 30f, edgeTolerance),
            0.0001f
        )
        assertEquals(
            -30f,
            ViewerDismissGesture.horizontalEdgeOverscrollPan(0f, 0f, -30f, edgeTolerance),
            0.0001f
        )
    }

    // ---------- 9. 二维自由拖动阈值（N8，v4.16.9） ----------

    @Test
    fun `下拉到10个百分点屏高横向仍未放开`() {
        // 前 10%（= 20% 阈值的一半起步点）锁纵向：权重 0
        assertEquals(
            0f,
            ViewerDismissGesture.resolveFreeDragFactor(
                screenHeight * 0.1f, screenHeight, ViewerDismissGesture.FREE_DRAG_THRESHOLD_RATIO
            ),
            0.0001f
        )
    }

    @Test
    fun `下拉到20个百分点屏高横向完全放开`() {
        assertEquals(
            1f,
            ViewerDismissGesture.resolveFreeDragFactor(
                screenHeight * 0.2f, screenHeight, ViewerDismissGesture.FREE_DRAG_THRESHOLD_RATIO
            ),
            0.0001f
        )
    }

    @Test
    fun `下拉超过20个百分点横向保持放开`() {
        assertEquals(
            1f,
            ViewerDismissGesture.resolveFreeDragFactor(
                screenHeight * 0.3f, screenHeight, ViewerDismissGesture.FREE_DRAG_THRESHOLD_RATIO
            ),
            0.0001f
        )
    }

    @Test
    fun `阈值之内横向权重线性爬升`() {
        // 15% 屏高恰在起步点（10%）与满格点（20%）正中间 → 权重 0.5
        assertEquals(
            0.5f,
            ViewerDismissGesture.resolveFreeDragFactor(
                screenHeight * 0.15f, screenHeight, ViewerDismissGesture.FREE_DRAG_THRESHOLD_RATIO
            ),
            0.0001f
        )
    }

    @Test
    fun `向上拖（负值阻尼后）同样放开横向`() {
        // |dismissDragY| 口径：向上拖 25% 屏高（阻尼后 -50）权重也是 1（回弹途中横移自由）
        assertEquals(
            1f,
            ViewerDismissGesture.resolveFreeDragFactor(
                -screenHeight * 0.25f, screenHeight, ViewerDismissGesture.FREE_DRAG_THRESHOLD_RATIO
            ),
            0.0001f
        )
    }

    @Test
    fun `阈值改成0后全程放开（反证内核）`() {
        // 反证：thresholdRatio = 0 时 resolveFreeDragFactor 必须返回 1（全程放开）——
        // 这正是"把阈值改成 0 后 0.1×屏高断言（期望 0）必失败"的机制来源：
        // 正式用例`下拉到10个百分点屏高横向仍未放开`喂入 0 阈值时会得到 1 ≠ 0 而变红
        assertEquals(
            1f,
            ViewerDismissGesture.resolveFreeDragFactor(screenHeight * 0.1f, screenHeight, 0f),
            0.0001f
        )
        assertEquals(
            1f,
            ViewerDismissGesture.resolveFreeDragFactor(screenHeight * 0.1f, screenHeight, -0.2f),
            0.0001f
        )
    }

    @Test
    fun `屏高无效或NaN时横向权重为0`() {
        // 宁可不放开，也不把 NaN 带进位移（graphicsLayer 防 NaN 同思路）
        assertEquals(
            0f,
            ViewerDismissGesture.resolveFreeDragFactor(100f, 0f, ViewerDismissGesture.FREE_DRAG_THRESHOLD_RATIO),
            0.0001f
        )
        assertEquals(
            0f,
            ViewerDismissGesture.resolveFreeDragFactor(
                Float.NaN, screenHeight, ViewerDismissGesture.FREE_DRAG_THRESHOLD_RATIO
            ),
            0.0001f
        )
    }

    // ---------- 10. 二维拖动松手归宿（N8，v4.16.9） ----------

    @Test
    fun `纵向位移达标判关闭（原语义不变）`() {
        assertEquals(
            DismissOutcome.FLY_BACK,
            ViewerDismissGesture.resolveDismissOutcome(
                dy = screenHeight * ViewerDismissGesture.DISMISS_DISTANCE_RATIO + 1f,
                dx = 0f,
                velocityYDownPxPerSec = 0f,
                velocityXPxPerSec = 0f,
                screenHeightPx = screenHeight,
                screenWidthPx = viewport,
                velocityThresholdPxPerSec = velocityThreshold
            )
        )
    }

    @Test
    fun `纵向甩速达标判关闭（原语义不变）`() {
        assertEquals(
            DismissOutcome.FLY_BACK,
            ViewerDismissGesture.resolveDismissOutcome(
                dy = 100f,
                dx = 0f,
                velocityYDownPxPerSec = velocityThreshold + 1f,
                velocityXPxPerSec = 0f,
                screenHeightPx = screenHeight,
                screenWidthPx = viewport,
                velocityThresholdPxPerSec = velocityThreshold
            )
        )
    }

    @Test
    fun `横向甩速大即使纵向没拖够也判关闭`() {
        // N8 新增：自由拖动后横向快速甩出是"甩掉查看器"意图，纵向上不再要求 22%
        assertEquals(
            DismissOutcome.FLY_BACK,
            ViewerDismissGesture.resolveDismissOutcome(
                dy = screenHeight * 0.05f,
                dx = viewport * 0.3f,
                velocityYDownPxPerSec = 0f,
                velocityXPxPerSec = velocityThreshold + 1f,
                screenHeightPx = screenHeight,
                screenWidthPx = viewport,
                velocityThresholdPxPerSec = velocityThreshold
            )
        )
    }

    @Test
    fun `向左甩与向右甩同样判关闭`() {
        // |vx| 口径：横向甩出不分左右
        assertEquals(
            DismissOutcome.FLY_BACK,
            ViewerDismissGesture.resolveDismissOutcome(
                dy = screenHeight * 0.05f,
                dx = -viewport * 0.3f,
                velocityYDownPxPerSec = 0f,
                velocityXPxPerSec = -(velocityThreshold + 1f),
                screenHeightPx = screenHeight,
                screenWidthPx = viewport,
                velocityThresholdPxPerSec = velocityThreshold
            )
        )
    }

    @Test
    fun `两轴都未达标回全屏（接近原位归宿）`() {
        assertEquals(
            DismissOutcome.SPRING_BACK,
            ViewerDismissGesture.resolveDismissOutcome(
                dy = screenHeight * 0.02f,
                dx = viewport * 0.02f,
                velocityYDownPxPerSec = velocityThreshold * 0.5f,
                velocityXPxPerSec = velocityThreshold * 0.5f,
                screenHeightPx = screenHeight,
                screenWidthPx = viewport,
                velocityThresholdPxPerSec = velocityThreshold
            )
        )
    }

    @Test
    fun `横向拖很远但纵向没达标仍回全屏不误关`() {
        // 慢速横拖半屏松手：|dx| 很大但不甩——必须弹回全屏（x 弹簧归零），绝不许误关
        assertEquals(
            DismissOutcome.SPRING_BACK,
            ViewerDismissGesture.resolveDismissOutcome(
                dy = screenHeight * 0.05f,
                dx = viewport * 0.5f,
                velocityYDownPxPerSec = 0f,
                velocityXPxPerSec = velocityThreshold * 0.5f,
                screenHeightPx = screenHeight,
                screenWidthPx = viewport,
                velocityThresholdPxPerSec = velocityThreshold
            )
        )
    }

    @Test
    fun `横向甩速恰好等于阈值不算甩出`() {
        // 判定条件是"严格大于"：恰好贴线仍是回全屏
        assertEquals(
            DismissOutcome.SPRING_BACK,
            ViewerDismissGesture.resolveDismissOutcome(
                dy = screenHeight * 0.05f,
                dx = 0f,
                velocityYDownPxPerSec = 0f,
                velocityXPxPerSec = velocityThreshold,
                screenHeightPx = screenHeight,
                screenWidthPx = viewport,
                velocityThresholdPxPerSec = velocityThreshold
            )
        )
    }

    // ---------- 8. 透出收回时机（v4.16.14 修：松手即作废） ----------

    /**
     * 透出的第二个判据：手指必须还按在屏上。
     *
     * 这是"松手回弹瞬间闪一下图库"的根因反证。修复前只按位移判定：回弹期间位移
     * 还在 10% 以上，遮罩目标就一直是 0.2，那条 120ms 淡出**在手指抬起后继续补完**
     * （图库截图里的缩略图反而变得更清楚），之后才淡回纯黑 —— 观感就是松手瞬间
     * 闪一下图片。加上 gestureActive 之后，收回与松手发生在同一帧。
     */
    @Test
    fun `松手后即使位移还超过阈值也不再透出`() {
        val h = 2000f
        val deep = h * 0.15f // 远超 10% 阈值
        // 手指还按着：透出
        assertTrue(ViewerDismissGesture.shouldRevealMask(deep, h, gestureActive = true))
        // 手指已松（回弹中）：一律收回，不管位移多深
        assertFalse(ViewerDismissGesture.shouldRevealMask(deep, h, gestureActive = false))
        // 松手 + 位移已经很浅：同样不透露
        assertFalse(ViewerDismissGesture.shouldRevealMask(h * 0.02f, h, gestureActive = false))
    }

    /** 拖动中的透出阈值必须与 dismissMaskTargetAlpha 同口径（"≥ 10%" 才透出）。 */
    @Test
    fun `拖动中的透出阈值与遮罩映射同口径`() {
        val h = 2000f
        assertTrue(ViewerDismissGesture.shouldRevealMask(h * 0.10f, h, gestureActive = true))
        assertFalse(ViewerDismissGesture.shouldRevealMask(h * 0.099f, h, gestureActive = true))
        // 零位移 / 向上拖 / 屏高未测得：一律不透出（宁可黑也不透出错帧）
        assertFalse(ViewerDismissGesture.shouldRevealMask(0f, h, gestureActive = true))
        assertFalse(ViewerDismissGesture.shouldRevealMask(-100f, h, gestureActive = true))
        assertFalse(ViewerDismissGesture.shouldRevealMask(100f, 0f, gestureActive = true))
    }
}
