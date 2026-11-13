package sh.hnet.comfychair.workflow

import org.json.JSONObject
import sh.hnet.comfychair.model.TimelineClip
import sh.hnet.comfychair.model.TimelineDocument
import sh.hnet.comfychair.model.TimelineReferenceMode

/**
 * 从工作流 JSON 识别「时间线导演台」输入位。
 *
 * 与图位/音频位那两套探测器的做法**刻意不同**：导演台不需要沿链路找容器编号口，
 * 因为它的全部素材（视频片段、图片、音频）都不走节点连线，而是塞在 `timeline_data`
 * 这一个字符串输入里的 JSON 中。所以这里只做一件事：找到那个节点、把它的当前值读出来。
 *
 * 认出来的意义有两个：
 * 1. 界面显示片段编辑器（而不是图位区）；
 * 2. 提交流程放开"必须有一张图"的校验 —— 导演台的图片参考可选，视频片段才是主体。
 */
object TimelineSlotDetector {

    /** 识别目标节点。写死类名是有意的：它就是这个插件唯一的入口节点 */
    private const val NODE_CLASS_TYPE = "MiniMaxH3TimelineDirector"

    /** 承载整份时间线的输入名 */
    private const val TIMELINE_FIELD = "timeline_data"

    /** 生成时长输入名（与 `selection.duration` 双向同步） */
    private const val GENERATION_SECONDS_FIELD = "generation_seconds"

    /** App 注入用的占位符（工作流里写这个，由 [WorkflowManager] 替换成序列化结果） */
    const val TIMELINE_PLACEHOLDER = "{{timeline_data}}"

    /** 生成时长占位符（替换成一个 JSON 数字字面量，不带引号） */
    const val GENERATION_SECONDS_PLACEHOLDER = "{{generation_seconds}}"

    /**
     * 探测结果。
     *
     * @param nodeId 导演台节点在 API JSON 里的 id
     * @param document 节点里**已有**的时间线内容解析结果；占位符/空串/坏 JSON 都得到空文档
     * @param hasInitialClips 已有内容里确实带了视频片段 —— 界面据此提示"这是电脑上摆好的"
     */
    data class TimelineSlot(
        val nodeId: String,
        val document: TimelineDocument,
        val hasInitialClips: Boolean
    )

    /**
     * @return 工作流里没有导演台节点时返回 null（绝大多数工作流都是这种情况）
     */
    fun detect(workflowJson: String): TimelineSlot? {
        val root = runCatching { JSONObject(workflowJson) }.getOrNull() ?: return null
        return detect(root)
    }

    /** 已解析成 [JSONObject] 时的重载（工作流校验流程手上就是 JSON 对象，不必再序列化一遍） */
    fun detect(root: JSONObject): TimelineSlot? {
        val nodes = root.optJSONObject("nodes") ?: return null

        for (nodeId in nodes.keys()) {
            val node = nodes.optJSONObject(nodeId) ?: continue
            if (node.optString("class_type") != NODE_CLASS_TYPE) continue
            val inputs = node.optJSONObject("inputs") ?: continue
            val raw = inputs.optString(TIMELINE_FIELD)

            // 占位符 / 空值都按"还没有内容"处理：前者等 App 注入，后者插件自己也会退成空状态。
            // 只有真的写着一段 JSON（用户在电脑上摆好的）才尝试解析成初值。
            val document = if (raw.isBlank() || raw == TIMELINE_PLACEHOLDER) {
                emptyDocument()
            } else {
                parse(raw)
            }
            return TimelineSlot(
                nodeId = nodeId,
                document = document,
                hasInitialClips = document.clips.isNotEmpty()
            )
        }
        return null
    }

    /** 空时间线：选区 0→5 秒（与插件 `emptyState()` 的默认一致），无片段 */
    private fun emptyDocument() = TimelineDocument(
        selectionStart = 0.0,
        selectionDuration = 5.0,
        clips = emptyList()
    )
    /**
     * 解析节点里已有的 `timeline_data`。
     *
     * **宽进严出**：任何一个字段读不出来就退回该字段的默认值，绝不因为一处坏数据丢掉整份时间线
     * ——这份内容可能是用户在电脑上花时间摆好的，宁可少读一个字段，也不能让编辑器变成空白。
     */
    private fun parse(raw: String): TimelineDocument {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyDocument()
        val selection = root.optJSONObject("selection")
        val clipsJson = root.optJSONArray("videoClips")

        val clips = buildList {
            for (i in 0 until (clipsJson?.length() ?: 0)) {
                val item = clipsJson?.optJSONObject(i) ?: continue
                // file 是插件的硬性要求（`filter(x => x?.file)`），没有它的条目直接跳过
                val file = item.optString("file")
                if (file.isBlank()) continue
                val duration = item.optDouble("duration", TimelineDocument.MIN_CLIP_SECONDS)
                add(
                    TimelineClip(
                        id = item.optString("id").ifBlank { newClipId() },
                        file = file,
                        name = item.optString("name").ifBlank { file.substringAfterLast('/') },
                        start = TimelineDocument.clampStart(item.optDouble("start", 0.0)),
                        duration = duration.coerceAtLeast(TimelineDocument.MIN_CLIP_SECONDS),
                        trimStart = TimelineDocument.clampStart(item.optDouble("trimStart", 0.0)),
                        sourceDuration = item.optDouble("sourceDuration", 0.0).coerceAtLeast(0.0),
                        hasAudio = item.optBoolean("hasAudio", true),
                        referenceMode = TimelineReferenceMode.fromValue(item.optString("referenceMode"))
                    )
                )
            }
        }

        return TimelineDocument(
            selectionStart = TimelineDocument.clampStart(selection?.optDouble("start", 0.0) ?: 0.0),
            selectionDuration = TimelineDocument.clampSelectionDuration(
                selection?.optDouble("duration", 5.0) ?: 5.0
            ),
            clips = clips,
            videoAudioEnabled = root.optBoolean("videoAudioEnabled", true),
            // 素材箱原样带回：App 不编辑它们，但提示词里的 <Picture N> / <Audio N>
            // 依赖它们存在，清空会让提示词引用不存在的参考（见 TimelineDocument.rawImages）
            rawImages = nonEmptyArrayText(root.optJSONArray("images")),
            rawAudios = nonEmptyArrayText(root.optJSONArray("audios"))
        )
    }

    /** 空数组按"没有"处理（省掉一次无意义的序列化与再解析） */
    private fun nonEmptyArrayText(array: org.json.JSONArray?): String? =
        array?.takeIf { it.length() > 0 }?.toString()

    /** 与插件 JS 的 `uid()` 同义：只需要在本机内唯一，不要求可读 */
    private fun newClipId(): String =
        "clip_" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
}
