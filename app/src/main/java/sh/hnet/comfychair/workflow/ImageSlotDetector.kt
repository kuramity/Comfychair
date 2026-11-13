package sh.hnet.comfychair.workflow

import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.model.ImageSlot
import sh.hnet.comfychair.model.NUMBERED_SLOT_ORDER_BASE

/**
 * 从工作流 JSON 识别图片输入位。
 *
 * 做法：找到所有"image 字段是图片占位符的 LoadImage 节点"，
 * 从它出发向下游做广度优先搜索，直到撞上真正消费图片的输入口——
 * 那个输入口的名字就决定了这个图位的语义（首帧/尾帧/参考图）。
 *
 * 之所以要搜索而不是只看一跳：实测 flf2v 里 LoadImage 与 MiniMaxH3 之间
 * 隔着 ImageResizeKJv2 这类中间节点。
 */
object ImageSlotDetector {

    /** 容器编号输入口，形如 ref_images.ref_image_0 */
    private val CONTAINER_INPUT = Regex("""^(\w+)\.(\w+?)(\d+)$""")

    /** 只认这两种图片占位符（设计文档已验证全部预置工作流仅用这两种） */
    private val IMAGE_PLACEHOLDER = Regex("""^\{\{(image_filename|reference_image_\d+)\}\}$""")

    /**
     * 通用图片输入口。它们只是"图片从这儿流过去"（如 ImageResizeKJv2.image），
     * **不代表图位语义**，所以不能当作搜索终点，只作兜底。
     */
    private val GENERIC_INPUTS = setOf("image", "pixels", "samples")

    private val SEMANTIC_INPUTS = mapOf(
        "first_frame" to ImageSlot.Semantics.FIRST_FRAME,
        "last_frame" to ImageSlot.Semantics.LAST_FRAME
    )

    private const val MAX_SEARCH_DEPTH = 6
    private const val DEFAULT_MAX_COUNT = 9

    /** 甲类图位的显示序：首帧 → 尾帧 → 兜底图位 */
    private const val ORDER_FIRST_FRAME = 0
    private const val ORDER_LAST_FRAME = 1
    private const val ORDER_GENERIC_BASE = 2

    /** 乙类图位排在甲类之后，内部按编号升序（基准与 `NUMBERED_SLOT_ORDER_BASE` 同源，勿各写一份） */
    private const val ORDER_NUMBERED_BASE = NUMBERED_SLOT_ORDER_BASE

    fun detect(workflowJson: String, objectInfo: JSONObject?): List<ImageSlot> {
        val root = runCatching { JSONObject(workflowJson) }.getOrNull() ?: return emptyList()
        return detect(root, objectInfo)
    }

    /**
     * 已解析成 [JSONObject] 时的重载。
     *
     * 同一份工作流在一轮加载里要喂给图片/音频/时间线等多个探测器，字符串版本会让每个探测器
     * 各自 `JSONObject(...)` 解析一遍（模板 JSON 常达几十上百 KB）。调用方手上已有 root 就直接传。
     */
    fun detect(root: JSONObject, objectInfo: JSONObject?): List<ImageSlot> {
        val nodes = root.optJSONObject("nodes") ?: return emptyList()

        val slots = mutableListOf<ImageSlot>()
        for (sourceId in nodes.keys()) {
            val source = nodes.optJSONObject(sourceId) ?: continue
            if (source.optString("class_type") != "LoadImage") continue
            val raw = source.optJSONObject("inputs")?.optString("image").orEmpty()
            val placeholder = IMAGE_PLACEHOLDER.matchEntire(raw)?.groupValues?.get(1) ?: continue

            // 追不到消费口也要建图位：占位符仍在工作流里，交给提交方按空位清理
            slots += buildSlot(placeholder, searchConsumer(nodes, sourceId), objectInfo)
        }
        return normalizeOrder(slots)
    }

    /** 搜索下游第一个承载语义的输入口。追不到时退化用通用口兜底，再追不到返回 null。 */
    private fun searchConsumer(nodes: JSONObject, sourceId: String): Hit? {
        var frontier = listOf(sourceId)
        val visited = mutableSetOf(sourceId)
        var genericFallback: Hit.Generic? = null

        repeat(MAX_SEARCH_DEPTH) {
            val next = mutableListOf<String>()
            val levelHits = mutableListOf<Hit>()

            for (nodeId in nodes.keys()) {
                val node = nodes.optJSONObject(nodeId) ?: continue
                val inputs = node.optJSONObject("inputs") ?: continue
                val classType = node.optString("class_type")
                for (key in inputs.keys()) {
                    val value = inputs.opt(key)
                    if (value !is JSONArray || value.length() != 2) continue
                    if (value.optString(0) !in frontier) continue

                    when (val hit = classify(key, classType)) {
                        // 通用口：记下兜底值，但继续穿过中间节点往下找语义口
                        is Hit.Generic -> {
                            if (genericFallback == null) genericFallback = hit
                            if (visited.add(nodeId)) next += nodeId
                        }
                        // 普通连线（如 width/height/anything）：只当作路径继续走
                        null -> if (visited.add(nodeId)) next += nodeId
                        else -> levelHits += hit
                    }
                }
            }

            // 同一层内：容器口优先于语义口；两者都优于通用兜底（通用口不限层级也要让位）
            levelHits.firstOrNull { it is Hit.Container }?.let { return it }
            levelHits.firstOrNull { it is Hit.Semantic }?.let { return it }

            if (next.isEmpty()) return genericFallback
            frontier = next
        }
        return genericFallback
    }

    /** 把一个输入口名归类：容器编号口 / 语义口 / 通用口 / 普通连线。 */
    private fun classify(key: String, classType: String): Hit? {
        CONTAINER_INPUT.matchEntire(key)?.let { m ->
            val index = m.groupValues[3].toIntOrNull() ?: return null
            return Hit.Container(
                container = m.groupValues[1],
                prefix = m.groupValues[2],
                index = index,
                classType = classType
            )
        }
        SEMANTIC_INPUTS[key]?.let { return Hit.Semantic(key, it) }
        if (key in GENERIC_INPUTS) return Hit.Generic(key)
        return null
    }

    private fun buildSlot(placeholder: String, hit: Hit?, objectInfo: JSONObject?): ImageSlot {
        when (hit) {
            is Hit.Container -> return ImageSlot.Numbered(
                placeholder = placeholder,
                order = ORDER_NUMBERED_BASE + hit.index,
                container = hit.container,
                prefix = hit.prefix,
                index = hit.index,
                maxCount = readMaxCount(objectInfo, hit.classType, hit.container)
            )
            is Hit.Semantic -> return ImageSlot.Named(
                placeholder = placeholder,
                order = when (hit.semantics) {
                    ImageSlot.Semantics.FIRST_FRAME -> ORDER_FIRST_FRAME
                    ImageSlot.Semantics.LAST_FRAME -> ORDER_LAST_FRAME
                    ImageSlot.Semantics.GENERIC -> ORDER_GENERIC_BASE
                },
                semantics = hit.semantics,
                targetInput = hit.inputName
            )
            // 通用口兜底 / 完全追不到：都按通用图位处理，targetInput 空串表示没找到消费口
            is Hit.Generic -> return genericSlot(placeholder, hit.inputName)
            null -> return genericSlot(placeholder, "")
        }
    }

    private fun genericSlot(placeholder: String, targetInput: String): ImageSlot.Named =
        ImageSlot.Named(
            placeholder = placeholder,
            order = ORDER_GENERIC_BASE,
            semantics = ImageSlot.Semantics.GENERIC,
            targetInput = targetInput
        )

    /**
     * 多个兜底图位不能并列同一 order（否则显示顺序取决于字典遍历顺序），
     * 按设计文档「兜底图位按占位符名排序」给定序。
     */
    private fun normalizeOrder(slots: List<ImageSlot>): List<ImageSlot> {
        val genericPlaceholders = slots
            .filterIsInstance<ImageSlot.Named>()
            .filter { it.semantics == ImageSlot.Semantics.GENERIC }
            .map { it.placeholder }
            .distinct()
            .sorted()
        if (genericPlaceholders.size < 2) return slots.sortedBy { it.order }

        val orderByPlaceholder = genericPlaceholders.withIndex()
            .associate { (rank, placeholder) -> placeholder to ORDER_GENERIC_BASE + rank }
        return slots.map { slot ->
            if (slot is ImageSlot.Named && slot.semantics == ImageSlot.Semantics.GENERIC) {
                slot.copy(order = orderByPlaceholder[slot.placeholder] ?: slot.order)
            } else {
                slot
            }
        }.sortedBy { it.order }
    }

    /** 从 /object_info 读出自动增长输入的数量上限。 */
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

    /** 下游输入口的归类结果。 */
    private sealed interface Hit {
        /** 容器编号口（乙类）：ref_images.ref_image_N */
        data class Container(
            val container: String,
            val prefix: String,
            val index: Int,
            val classType: String
        ) : Hit

        /** 语义口（甲类）：first_frame / last_frame */
        data class Semantic(val inputName: String, val semantics: ImageSlot.Semantics) : Hit

        /** 通用口：只是兜底，不承载语义 */
        data class Generic(val inputName: String) : Hit
    }
}
