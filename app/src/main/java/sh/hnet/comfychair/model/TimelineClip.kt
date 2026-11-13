package sh.hnet.comfychair.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 时间线导演台（`MiniMaxH3TimelineDirector`）里的一个视频片段。
 *
 * 字段名与插件 JS 的 `normalizeState()` 一一对应，**不能改名字**：`timeline_data` 是一段
 * 交给插件解析的 JSON，键名写错不会报错，只会让那个字段悄悄退回默认值
 * （例如 `referenceMode` 拼错就会退成 `guide`，用户选了别的模式却按 guide 生成）。
 *
 * 三个时间字段的语义（插件里都按秒、浮点）：
 * - [start]：片段在**时间轴**上的落点
 * - [duration]：片段在时间轴上占的长度
 * - [trimStart]：从源视频的第几秒开始取
 *
 * 三者独立，所以同一个源可以被摆到时间轴不同位置、各取不同的一段。
 */
data class TimelineClip(
    /** 稳定标识，插件用它关联 guide 与片段（`clip_id`）；只用一次，不参与业务 */
    val id: String,
    /** ComfyUI input 目录下的相对路径，如 `minimax_h3_timeline_director/xxx.mp4` */
    val file: String,
    /** 界面上显示的名字（用户选文件时的原始文件名） */
    val name: String,
    val start: Double,
    val duration: Double,
    val trimStart: Double,
    /** 源视频总时长，用来限制 [trimStart] + [duration] 不超过源 */
    val sourceDuration: Double,
    /** 源视频有没有音轨；没有则插件不会为它解音频参考 */
    val hasAudio: Boolean,
    val referenceMode: TimelineReferenceMode
)

/**
 * 片段与生成选区的三种关系（插件 `_reference_mode()` 的三个取值）。
 *
 * 决定的是"这段原画面被固定得多死"：
 * - [GUIDE] 逐帧固定 —— 原画面逐帧贴到目标帧位置，最忠实但也最不自由；
 * - [EDIT] 可编辑参考 —— 只当提示参考，不固定原人物/画面；
 * - [BOUNDARY] 仅固定边界 —— 只把首尾两帧钉住，中间由模型自由发挥。
 */
enum class TimelineReferenceMode(val value: String) {
    GUIDE("guide"),
    EDIT("edit"),
    BOUNDARY("boundary");

    companion object {
        /** 解析插件的取值；认不出来时退回 [GUIDE]（与插件 `_reference_mode()` 的兜底一致） */
        fun fromValue(raw: String?): TimelineReferenceMode =
            entries.firstOrNull { it.value == raw } ?: GUIDE
    }
}

/**
 * 时间线导演台的一份完整数据（`timeline_data` 的那个 JSON）。
 *
 * App 侧只让用户编辑 [selection]（生成选区）与 [clips]（视频片段）；其余字段
 * **原样透传**（见 [videoAudioEnabled] / [rawImages] / [rawAudios]）。
 */
data class TimelineDocument(
    val selectionStart: Double,
    val selectionDuration: Double,
    val clips: List<TimelineClip>,
    /** 是否把视频片段原声一起作为参考（插件里那个「视频原声」开关） */
    val videoAudioEnabled: Boolean = true,
    /**
     * 电脑端素材箱里的图片/音频的原样 JSON 文本（App 不编辑它们，只带回）。
     *
     * 这两个字段必须透传而不是清空：插件把图片素材箱映射成提示词里的 `<Picture N>`、
     * 音频映射成 `<Audio N>`（序号按数组顺序数）。用户在电脑上摆好素材、提示词里写着
     * `<Picture 1>`，App 却在提交时把 images 清成空数组 —— 提示词引用了一个不存在的
     * 参考，节点不报错，只是生成结果与用户在电脑上看到的安排不一致。
     *
     * 存 JSON 文本而不是 JSONArray 是为了让本类保持可比较（JSONArray 不实现 equals），
     * 也顺带避免外部改动到共享的实例。
     */
    val rawImages: String? = null,
    val rawAudios: String? = null
) {

    /** 选区的结束时刻（秒） */
    val selectionEnd: Double get() = selectionStart + selectionDuration

    /**
     * 选区里**没有被任何片段覆盖**的区间，升序。
     *
     * 这是导演台的核心读数：落在空隙里的部分没有原画面可固定，由模型自由生成；
     * 被片段覆盖的部分则按该片段的 [TimelineReferenceMode] 被固定。
     * 界面据此提示"这段是要生成的新内容"。
     */
    fun uncoveredIntervals(): List<UncoveredInterval> =
        uncoveredIntervals(selectionStart, selectionDuration, clips)

    /**
     * 序列化成插件认的 JSON 文本。
     *
     * `version` 固定 3（插件 `emptyState()` 的当前版本），`fps` 固定 24 —— 插件只在自己
     * 空状态时写这两个值，读取时并不校验，写 3/24 与桌面端一致，避免下次在电脑上打开时
     * 被归一化出差异。
     */
    fun toJson(): String {
        val root = JSONObject()
        root.put("version", TIMELINE_VERSION)
        root.put("fps", TIMELINE_FPS)
        root.put("selection", JSONObject().apply {
            put("start", selectionStart)
            put("duration", selectionDuration)
        })
        root.put("videoAudioEnabled", videoAudioEnabled)
        root.put("videoClips", JSONArray().apply {
            for (clip in clips) put(clip.toJson())
        })
        // 素材箱原样带回（见 rawImages 的说明）。解析不出来时退成空数组：
        // 宁可少给一个素材，也不能因为一处坏数据让整份 timeline_data 变成非法 JSON
        root.put("images", rawArrayOrEmpty(rawImages))
        root.put("audios", rawArrayOrEmpty(rawAudios))
        return root.toString()
    }

    private fun rawArrayOrEmpty(raw: String?): JSONArray =
        raw?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()

    private fun TimelineClip.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("file", file)
        put("name", name)
        put("start", start)
        put("duration", duration)
        put("trimStart", trimStart)
        put("sourceDuration", sourceDuration)
        put("hasAudio", hasAudio)
        put("referenceMode", referenceMode.value)
        // 波形峰值（插件只用它画音频轨的装饰波形）与低清代理：App 侧不生成，按插件的
        // 默认值输出 —— peaks 空数组、proxy 空串，插件分别退化成正弦占位与直接读源文件
        put("peaks", JSONArray())
        put("proxy", "")
    }

    companion object {
        const val TIMELINE_VERSION = 3
        const val TIMELINE_FPS = 24

        /** 插件里 `MIN_REF_VIDEO_SECONDS`：5 帧 @24fps，是**片段与参考窗口**的长度下限 */
        const val MIN_CLIP_SECONDS = 5.0 / TIMELINE_FPS

        /**
         * **生成时长**的下限（秒）：节点 `generation_seconds` 的 `min=0.21`。
         *
         * 刻意比 [MIN_CLIP_SECONDS]（0.20833…）大一档 —— 两者看起来是"同一件事的下限"，
         * 其实一个是插件的窗口长度、另一个是节点定义的输入范围。用 5/24 去夹生成时长会
         * 提交出 0.208333，服务器按 `val < min` 判 `value_smaller_than_min`，整张工作流
         * 400；而界面用两位小数把它显示成 "0.21"，用户看到的是合法值，根本查不出来。
         */
        const val MIN_SELECTION_SECONDS = 0.21

        /** 插件里 `generation_seconds` 的上限（节点定义 max=150.0） */
        const val MAX_DURATION_SECONDS = 150.0

        /**
         * 把**生成时长**夹到节点承认的区间（[MIN_SELECTION_SECONDS] – [MAX_DURATION_SECONDS]）。
         *
         * 片段长度不走这里：它的上界是"源里还剩多少秒"，下界是 [MIN_CLIP_SECONDS]，
         * 见 [TimelineClipDraft.toClip]。
         */
        fun clampSelectionDuration(seconds: Double): Double =
            seconds.coerceIn(MIN_SELECTION_SECONDS, MAX_DURATION_SECONDS)

        /** 起始时刻只要求非负（插件 `Math.max(0, ...)`） */
        fun clampStart(seconds: Double): Double = seconds.coerceAtLeast(0.0)
    }
}

/** 选区内一段没有被片段覆盖的区间 */
data class UncoveredInterval(val start: Double, val end: Double) {
    val duration: Double get() = end - start
}

/**
 * 选区减去片段覆盖后的剩余区间。
 *
 * 抽成顶层函数是为了能在 JVM 单测里直接钉住边界行为（重合、相邻、包含、零长度），
 * 不必构造整个 [TimelineDocument]。段落边界按帧对齐（1/24 秒）判定，
 * 所以"贴着放"的两段（前段结束 == 后段开始）视为连续、不产生空隙 —— 这与插件
 * `_pick_gap_guides()` 里的 `epsilon = 1/FPS` 是同一个口径。
 */
fun uncoveredIntervals(
    selectionStart: Double,
    selectionDuration: Double,
    clips: List<TimelineClip>
): List<UncoveredInterval> {
    if (selectionDuration <= 0.0) return emptyList()
    val selectionEnd = selectionStart + selectionDuration
    val epsilon = 1.0 / TimelineDocument.TIMELINE_FPS

    // 只保留与选区有实际重叠的片段，并按起点排序后合并
    val covers = clips
        .map { it.start to (it.start + it.duration) }
        .filter { (start, end) -> end - start > epsilon && end > selectionStart && start < selectionEnd }
        .sortedBy { it.first }

    val result = mutableListOf<UncoveredInterval>()
    var cursor = selectionStart
    for ((start, end) in covers) {
        val clampedStart = start.coerceAtLeast(selectionStart)
        val clampedEnd = end.coerceAtMost(selectionEnd)
        // 只有领先超过一帧才算真空隙：贴边、叠一点点的都当成连续
        if (clampedStart - cursor > epsilon) {
            result += UncoveredInterval(cursor, clampedStart)
        }
        if (clampedEnd > cursor) cursor = clampedEnd
    }
    if (selectionEnd - cursor > epsilon) {
        result += UncoveredInterval(cursor, selectionEnd)
    }
    return result
}

/**
 * 选区被片段覆盖的总时长（秒）。
 *
 * 与 [uncoveredIntervals] 互补：覆盖 + 未覆盖 = 选区长度。界面用这两个读数告诉用户
 * "多少秒是被原画面固定的、多少秒是模型要新生成的"。
 */
fun coveredSeconds(
    selectionStart: Double,
    selectionDuration: Double,
    clips: List<TimelineClip>
): Double {
    if (selectionDuration <= 0.0) return 0.0
    val uncovered = uncoveredIntervals(selectionStart, selectionDuration, clips).sumOf { it.duration }
    return (selectionDuration - uncovered).coerceAtLeast(0.0)
}
