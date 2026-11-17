package sh.hnet.comfychair.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图库网格捏合改列数（C1~C3，v4.16.2）的纯逻辑层测试。
 *
 * 钉住四件事：
 * 1. 手势倍率 → 连续列数（张开放大 / 合拢缩小，两个方向都对）
 * 2. 松手吸附档位（2 / 3 / 4 全部分界点与越界钳制）
 * 3. 格子尺寸档位与 [galleryDisplayScaleForColumns] 严格一致（换列瞬间尺寸不跳）
 * 4. 异常输入（0 / 负数 / NaN / 无穷）不会把列数或缩放推成非法值
 *
 * 手势流程本身（多指事件消费、跟手帧率）依赖真机，在自测报告里给
 * instrumented / dump 证据。
 */
class GalleryColumnPinchLogicTest {

    // ---------- 1. 手势倍率 → 连续列数 ----------

    @Test
    fun `没捏时列数不变`() {
        assertEquals(2f, galleryColumnsForGesture(2f, 1f), 1e-6f)
        assertEquals(3f, galleryColumnsForGesture(3f, 1f), 1e-6f)
        assertEquals(4f, galleryColumnsForGesture(4f, 1f), 1e-6f)
    }

    @Test
    fun `张开手指列数变少`() {
        // 手指放大 1.5 倍：3 列 → 2 列（格子变大）
        assertEquals(2f, galleryColumnsForGesture(3f, 1.5f), 1e-6f)
        // 放大 4/3 倍：4 列 → 3 列
        assertEquals(3f, galleryColumnsForGesture(4f, 4f / 3f), 1e-5f)
    }

    @Test
    fun `合拢手指列数变多`() {
        // 手指缩小到 0.5 倍：2 列 → 4 列
        assertEquals(4f, galleryColumnsForGesture(2f, 0.5f), 1e-6f)
        // 缩小到 2/3：3 列 → 4.5 钳到 4（档位上限）
        assertEquals(4f, galleryColumnsForGesture(3f, 2f / 3f), 1e-6f)
    }

    @Test
    fun `连续列数两端钳制在档位区间`() {
        // 张开一倍也只到 2 列（不出现 1 列）
        assertEquals(2f, galleryColumnsForGesture(2f, 2f), 1e-6f)
        // 合拢到 1/5 也只到 4 列（不出现 5/6 列）
        assertEquals(4f, galleryColumnsForGesture(4f, 0.2f), 1e-6f)
    }

    @Test
    fun `起始列数越界先钳进档位区间`() {
        assertEquals(2f, galleryColumnsForGesture(1f, 1f), 1e-6f)
        assertEquals(4f, galleryColumnsForGesture(9f, 1f), 1e-6f)
    }

    @Test
    fun `异常手势倍率返回起始列数`() {
        // 0 / 负数 / NaN / 无穷：不做除法换算，返回起点（否则会得到 Inf / NaN）
        assertEquals(3f, galleryColumnsForGesture(3f, 0f), 1e-6f)
        assertEquals(3f, galleryColumnsForGesture(3f, -1.5f), 1e-6f)
        assertEquals(3f, galleryColumnsForGesture(3f, Float.NaN), 1e-6f)
        assertEquals(3f, galleryColumnsForGesture(3f, Float.POSITIVE_INFINITY), 1e-6f)
    }

    // ---------- 2. 松手吸附档位 ----------

    @Test
    fun `吸附到最近档位`() {
        assertEquals(2, galleryColumnsSnap(2f))
        assertEquals(2, galleryColumnsSnap(2.49f))
        // 分界：过半才升级
        assertEquals(3, galleryColumnsSnap(2.5f))
        assertEquals(3, galleryColumnsSnap(3f))
        assertEquals(3, galleryColumnsSnap(3.49f))
        assertEquals(4, galleryColumnsSnap(3.5f))
        assertEquals(4, galleryColumnsSnap(4f))
    }

    @Test
    fun `吸附越界与异常输入钳到档位区间`() {
        assertEquals(2, galleryColumnsSnap(0f))
        assertEquals(2, galleryColumnsSnap(-5f))
        assertEquals(2, galleryColumnsSnap(1.2f))
        assertEquals(4, galleryColumnsSnap(9f))
        assertEquals(2, galleryColumnsSnap(Float.NaN))
        assertEquals(2, galleryColumnsSnap(Float.NEGATIVE_INFINITY))
    }

    // ---------- 2b. 松手结算入口（起点列数 + 手势倍率 → 目标档位） ----------

    @Test
    fun `从2列合拢手指能换到3列和4列`() {
        // 手指缩小到 2/3 → 连续 3 列 → 3
        assertEquals(3, galleryTargetColumns(2f, 2f / 3f))
        // 手指缩小到 1/2 → 连续 4 列 → 4
        assertEquals(4, galleryTargetColumns(2f, 0.5f))
        // 只轻微合拢（0.66→连续 3.03）仍应进 3 列，绝不能被舍成 2 列
        assertEquals(3, galleryTargetColumns(2f, 0.66f))
    }

    @Test
    fun `从3列张开或合拢到相邻档位`() {
        // 张开 1.5 倍 → 连续 2 列
        assertEquals(2, galleryTargetColumns(3f, 1.5f))
        // 合拢到 0.75 → 连续 4 列
        assertEquals(4, galleryTargetColumns(3f, 0.75f))
        // 几乎没动（0.95 → 3.16）→ 仍在 3 列
        assertEquals(3, galleryTargetColumns(3f, 0.95f))
    }

    @Test
    fun `松手结算不会把倍率直接当列数`() {
        // 回归护栏：倍率 0.66 若被当成"连续列数"直接吸附，会舍成 1 再钳回 2（永远停在 2 列）。
        // 正确路径必须先把倍率换算成列数（2 / 0.66 = 3.03），目标为 3。
        assertEquals(2, galleryColumnsSnap(0.66f))
        assertEquals(3, galleryTargetColumns(2f, 0.66f))
    }

    @Test
    fun `松手结算对异常倍率保持原档位`() {
        assertEquals(2, galleryTargetColumns(2f, 1f))
        assertEquals(3, galleryTargetColumns(3f, 0f))
        assertEquals(3, galleryTargetColumns(3f, Float.NaN))
        assertEquals(3, galleryTargetColumns(3f, Float.POSITIVE_INFINITY))
    }

    // ---------- 3. 格子尺寸档位一致性 ----------

    @Test
    fun `连续版尺寸系数在整数档位上与整数版同值`() {
        for (n in GALLERY_MIN_COLUMNS..GALLERY_MAX_COLUMNS) {
            assertEquals(
                "列数 $n",
                galleryDisplayScaleForColumns(n),
                galleryDisplayScaleForColumnCount(n.toFloat()),
                1e-6f
            )
        }
    }

    @Test
    fun `连续版尺寸系数两端钳制`() {
        // 异常小列数按 2 列（1f），异常大列数按 4 列（0.5f）
        assertEquals(1f, galleryDisplayScaleForColumnCount(1f), 1e-6f)
        assertEquals(0.5f, galleryDisplayScaleForColumnCount(8f), 1e-6f)
        // 中间值线性：2.5 列 → 0.8
        assertEquals(0.8f, galleryDisplayScaleForColumnCount(2.5f), 1e-6f)
    }

    @Test
    fun `连续版尺寸系数异常输入兜底为原尺寸`() {
        assertEquals(1f, galleryDisplayScaleForColumnCount(0f), 1e-6f)
        assertEquals(1f, galleryDisplayScaleForColumnCount(-2f), 1e-6f)
        assertEquals(1f, galleryDisplayScaleForColumnCount(Float.NaN), 1e-6f)
    }

    @Test
    fun `跟手系数等于两档格子尺寸之比`() {
        // 已提交 2 列、捏到 2.667 列（= 3 列尺寸）→ 0.75
        assertEquals(
            0.75f,
            galleryPinchScaleForColumns(2, 8f / 3f),
            1e-5f
        )
        // 已提交 4 列、捏到 2 列 → 放大一倍
        assertEquals(2f, galleryPinchScaleForColumns(4, 2f), 1e-6f)
        // 已提交 3 列、捏到 4 列 → 0.75
        assertEquals(0.75f, galleryPinchScaleForColumns(3, 4f), 1e-6f)
        // 没捏（目标 = 当前）→ 1
        assertEquals(1f, galleryPinchScaleForColumns(2, 2f), 1e-6f)
    }

    @Test
    fun `换列瞬间视觉尺寸严格连续`() {
        // 先按旧列数排版并乘跟手系数，再换成新列数复位 1f —— 两者必须相等，
        // 否则换列那一帧会闪一下（这是本实现"先补间再提交"的正确性前提）
        for (from in GALLERY_MIN_COLUMNS..GALLERY_MAX_COLUMNS) {
            for (to in GALLERY_MIN_COLUMNS..GALLERY_MAX_COLUMNS) {
                val beforeCommit = galleryDisplayScaleForColumns(from) *
                    galleryPinchScaleForColumns(from, to.toFloat())
                assertEquals(
                    "从 $from 列换到 $to 列",
                    galleryDisplayScaleForColumns(to),
                    beforeCommit,
                    1e-6f
                )
            }
        }
    }

    @Test
    fun `跟手系数异常输入兜底为原尺寸`() {
        assertEquals(1f, galleryPinchScaleForColumns(2, Float.NaN), 1e-6f)
        assertEquals(1f, galleryPinchScaleForColumns(2, 0f), 1e-6f)
        // 越界连续列数先钳到档位区间再换算：4 列尺寸 / 2 列尺寸 = 0.5
        assertEquals(0.5f, galleryPinchScaleForColumns(2, 99f), 1e-6f)
    }

    // ---------- 4. 常量与时长 ----------

    @Test
    fun `列数档位为2到4`() {
        assertEquals(2, GALLERY_MIN_COLUMNS)
        assertEquals(4, GALLERY_MAX_COLUMNS)
    }

    @Test
    fun `换列吸附时长在可用区间`() {
        // 太快显得生硬、太慢显得黏手；限定 120~400ms
        assertTrue(COLUMN_SETTLE_MS in 120..400)
    }
}
