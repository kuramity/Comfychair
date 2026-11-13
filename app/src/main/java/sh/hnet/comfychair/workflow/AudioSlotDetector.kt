package sh.hnet.comfychair.workflow

import org.json.JSONObject
import sh.hnet.comfychair.model.AudioSlot
import sh.hnet.comfychair.model.AUDIO_MAIN_PLACEHOLDER
import sh.hnet.comfychair.model.AUDIO_SLOT_PLACEHOLDER_PREFIX

/**
 * 从工作流 JSON 识别音频输入位。
 *
 * 做法与 [ImageSlotDetector] 同构：找到"audio 字段是音频占位符的 LoadAudio 节点"，
 * 从它向下游做广度优先搜索，撞上 `ref_audios.ref_audio_N` 这样的容器编号口，
 * 那个口的容器名与前缀就是注入器要用的锚点。
 *
 * 与图片侧的两点差异：
 * - 音频没有具名语义口（没有"首帧/尾帧"那种），所以只有编号一种结果；
 * - 编号上限默认 3（`MiniMaxH3ReferenceToVideo.ref_audios` 的 `max`），
 *   读得到 `/object_info` 时以服务器为准。
 */
object AudioSlotDetector {

    /** 容器编号输入口，形如 ref_audios.ref_audio_0 */
    private val CONTAINER_INPUT = Regex("""^(\w+)\.(\w+?)(\d+)$""")

    /** 只认这两种音频占位符 */
    private val AUDIO_PLACEHOLDER = Regex("""^\{\{(audio_filename|reference_audio_\d+)\}\}$""")

    /** 未做过下游搜索时的兜底容器（与节点的实际输入名一致） */
    private const val FALLBACK_CONTAINER = "ref_audios"
    private const val FALLBACK_PREFIX = "ref_audio_"

    /** `/object_info` 读不到时的默认上限（节点定义里 ref_audios 的 max） */
    private const val DEFAULT_MAX_COUNT = 3

    private const val MAX_SEARCH_DEPTH = 4

    fun detect(workflowJson: String, objectInfo: JSONObject?): List<AudioSlot> {
        val root = runCatching { JSONObject(workflowJson) }.getOrNull() ?: return emptyList()
        return detect(root, objectInfo)
    }

    /** 已解析成 [JSONObject] 时的重载（与 ImageSlotDetector 同款，避免同一份工作流被重复解析）。 */
    fun detect(root: JSONObject, objectInfo: JSONObject?): List<AudioSlot> {
        val nodes = root.optJSONObject("nodes") ?: return emptyList()

        val slots = mutableListOf<AudioSlot>()
        for (sourceId in nodes.keys()) {
            val source = nodes.optJSONObject(sourceId) ?: continue
            if (source.optString("class_type") != "LoadAudio") continue
            val raw = source.optJSONObject("inputs")?.optString("audio").orEmpty()
            val placeholder = AUDIO_PLACEHOLDER.matchEntire(raw)?.groupValues?.get(1) ?: continue
            val index = indexOfPlaceholder(placeholder) ?: continue

            val hit = searchConsumer(nodes, sourceId)
            slots += AudioSlot(
                placeholder = placeholder,
                order = index,
                container = hit?.container ?: FALLBACK_CONTAINER,
                prefix = hit?.prefix ?: FALLBACK_PREFIX,
                index = index,
                maxCount = hit?.let { readMaxCount(objectInfo, it.classType, it.container) }
                    ?: DEFAULT_MAX_COUNT
            )
        }
        return slots.sortedBy { it.order }
    }

    /** audio_filename → 0；reference_audio_N → N */
    private fun indexOfPlaceholder(placeholder: String): Int? = when {
        placeholder == AUDIO_MAIN_PLACEHOLDER -> 0
        placeholder.startsWith(AUDIO_SLOT_PLACEHOLDER_PREFIX) ->
            placeholder.removePrefix(AUDIO_SLOT_PLACEHOLDER_PREFIX).toIntOrNull()
        else -> null
    }

    /** 搜索下游第一个容器编号口；追不到时返回 null，由调用方退回兜底容器 */
    private fun searchConsumer(nodes: JSONObject, sourceId: String): Hit? {
        var frontier = listOf(sourceId)
        val visited = mutableSetOf(sourceId)

        repeat(MAX_SEARCH_DEPTH) {
            val next = mutableListOf<String>()
            for (nodeId in nodes.keys()) {
                val node = nodes.optJSONObject(nodeId) ?: continue
                val inputs = node.optJSONObject("inputs") ?: continue
                for (key in inputs.keys()) {
                    val value = inputs.opt(key)
                    if (value !is org.json.JSONArray || value.length() != 2) continue
                    if (value.optString(0) !in frontier) continue

                    val match = CONTAINER_INPUT.matchEntire(key)
                    if (match != null) {
                        return Hit(
                            container = match.groupValues[1],
                            prefix = match.groupValues[2],
                            classType = node.optString("class_type")
                        )
                    }
                    if (visited.add(nodeId)) next += nodeId
                }
            }
            if (next.isEmpty()) return null
            frontier = next
        }
        return null
    }

    /** 从 /object_info 读出自动增长输入的数量上限 */
    private fun readMaxCount(objectInfo: JSONObject?, classType: String, container: String): Int {
        val spec = objectInfo
            ?.optJSONObject(classType)
            ?.optJSONObject("input")
            ?.let {
                it.optJSONObject("optional")?.optJSONArray(container)
                    ?: it.optJSONObject("required")?.optJSONArray(container)
            }
            ?: return DEFAULT_MAX_COUNT
        if (spec.optString(0) != "COMFY_AUTOGROW_V3") return DEFAULT_MAX_COUNT
        return spec.optJSONObject(1)
            ?.optJSONObject("template")
            ?.optInt("max", DEFAULT_MAX_COUNT)
            ?: DEFAULT_MAX_COUNT
    }

    /** 下游容器编号口 */
    private data class Hit(val container: String, val prefix: String, val classType: String)
}
