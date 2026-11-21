package sh.hnet.comfychair.viewmodel

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旧版「时间线导演台」工作流判定（[isLegacyTimelineWorkflow]）的回归锁。
 *
 * LEGACY-FORMAT-01：实现最初只认 app 自定义的 `{"nodes": {...}}` 包一层格式，漏掉
 * 标准 ComfyUI API 格式（节点直接挂在根对象上，见 `WorkflowParser` 的结构判定）——
 * 用户旧时间线副本若是裸 API JSON，`legacyTimelineUnsupported` 不置位，提交时按普通
 * 图生视频走、撞一个看不懂的 400，而不是拿到友好提示。
 */
class LegacyTimelineWorkflowTest {

    private val directorNode =
        """{"class_type": "MiniMaxH3TimelineDirector", "inputs": {"timeline_data": "x"}}"""

    /** ① app 自定义格式：`{"nodes": {...}}` 包一层（app 内导入路径产出的形状） */
    @Test
    fun wrappedNodesFormat_isDetected() {
        val json = JSONObject(
            """{"name": "旧时间线", "nodes": {"9": $directorNode, "12": {"class_type": "KSampler"}}}"""
        )
        assertTrue(isLegacyTimelineWorkflow(json))
    }

    /** ② 标准 ComfyUI API 格式：节点直接挂在根对象上（LEGACY-FORMAT-01 修复目标） */
    @Test
    fun bareApiFormat_isDetected() {
        val json = JSONObject(
            """{"3": {"class_type": "KSampler", "inputs": {}}, "9": $directorNode}"""
        )
        assertTrue(isLegacyTimelineWorkflow(json))
    }

    /** ③ 普通工作流（无导演节点）：两种格式都不得误报 */
    @Test
    fun workflowsWithoutDirector_returnFalse() {
        val wrapped = JSONObject("""{"nodes": {"3": {"class_type": "KSampler"}}}""")
        val bare = JSONObject("""{"3": {"class_type": "KSampler"}, "4": {"class_type": "VAE"}}""")
        assertFalse(isLegacyTimelineWorkflow(wrapped))
        assertFalse(isLegacyTimelineWorkflow(bare))
    }

    /**
     * LiteGraph 格式（`nodes` 是数组、节点用 `type` 而非 `class_type`）不是节点表，
     * 本轮明确不处理——必须保持返回 false，不得因根级回退误检出。
     */
    @Test
    fun liteGraphArrayFormat_returnsFalse() {
        val json = JSONObject(
            """{"last_node_id": 5, "nodes": [{"id": 9, "type": "MiniMaxH3TimelineDirector"}]}"""
        )
        assertFalse(isLegacyTimelineWorkflow(json))
    }

    /**
     * 根级只有 LiteGraph 元数据（`last_node_id` 等标量、无任何 `class_type`）时，
     * 根级回退不得把它误当节点表。
     */
    @Test
    fun rootMetadataOnly_returnsFalse() {
        val json = JSONObject("""{"last_node_id": 5, "last_link_id": 3, "version": 0.4}""")
        assertFalse(isLegacyTimelineWorkflow(json))
    }
}
