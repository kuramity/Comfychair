package sh.hnet.comfychair.model

/**
 * 片段编辑器里的一个片段：本地待上传文件 + 它在时间轴上的摆放。
 *
 * 与 [TimelineClip] 分开是有原因的：那个是"要发给插件的形态"（只认服务器文件名），
 * 这个是"用户正在编辑的形态"（认手机上的 uri）。两者之间隔着一次上传，
 * 混成一个类会让"还没上传"和"已经在服务器上"两种情况纠缠不清。
 *
 * [uploadedFile] 与 [uri] 的分工：
 * - 手机上新加的片段：有 [uri]，[uploadedFile] 为 null，提交时才上传；
 * - 从电脑端已有时间线读出来的片段：没有 [uri]（文件在服务器上），[uploadedFile] 是文件名，
 *   用户不动它就原样提交 —— 这样"在电脑上摆好、手机上直接生成"不会因为手机上没选文件而失败。
 */
data class TimelineClipDraft(
    val id: String,
    /** 本地待上传的视频；从服务器读回来的片段为 null */
    val uri: android.net.Uri?,
    /** 界面上显示的文件名 */
    val displayName: String,
    val start: Double,
    val duration: Double,
    val trimStart: Double,
    val sourceDuration: Double,
    val hasAudio: Boolean,
    val referenceMode: TimelineReferenceMode,
    val uploadedFile: String? = null
) {

    /**
     * 源视频的总长度（秒）。
     *
     * [sourceDuration] 缺失时（电脑端的旧数据、或手写的时间线里没这个字段）退回
     * "入点 + 当前占用长度" —— 那是这个片段**实际用到**的源范围，拿它当总长不会误伤
     * 已有设置。直接用裸 [sourceDuration] 会让"点一下源入点输入框"就把片段压成 5 帧
     * （0 减去任何正数都会被夹到下限）。
     */
    val sourceLengthSeconds: Double
        get() = if (sourceDuration > 0.0) sourceDuration else trimStart + duration

    /**
     * 源视频里还剩多少秒可用。
     */
    val availableSourceSeconds: Double
        get() = (sourceLengthSeconds - trimStart).coerceAtLeast(0.0)

    /** 这条片段还没进服务器（需要上传） */
    val needsUpload: Boolean get() = uri != null

    /**
     * 改成最终形态。[file] 是服务器上的相对路径。
     *
     * 时长再夹一次：`trimStart` 被调大后 `duration` 可能已经超出源视频剩余长度，
     * 插件会按源长度截断（`_windows()`），但界面上仍显示原来的长度，读数会对不上。
     */
    fun toClip(file: String): TimelineClip = TimelineClip(
        id = id,
        file = file,
        name = displayName,
        start = start,
        // 上界是"源里还剩多少秒"而不是生成时长的上限（那个 150 秒是选区长度的事）：
        // 拿它夹片段会把一条 3 分钟的长镜头压成 150 秒，而时间轴上的位置却没变
        duration = duration.coerceIn(
            TimelineDocument.MIN_CLIP_SECONDS,
            availableSourceSeconds.coerceAtLeast(TimelineDocument.MIN_CLIP_SECONDS)
        ),
        trimStart = trimStart,
        sourceDuration = if (sourceDuration > 0.0) sourceDuration else duration,
        hasAudio = hasAudio,
        referenceMode = referenceMode
    )
}

/**
 * 新片段默认落在哪：已有片段的**最大结束时刻**（接续排布），没有片段时是 0。
 *
 * 刻意不做"自动留空隙"：空隙是用户要显式调的东西，默认留一个反而要先去猜它留了多少。
 * 接续排布的好处是"连加几段"结果可预测 —— 时间线上就是一段接一段。
 */
fun nextClipStart(drafts: List<TimelineClipDraft>): Double =
    drafts.maxOfOrNull { it.start + it.duration } ?: 0.0

/**
 * 从服务器上的片段还原成编辑器草稿。
 *
 * [uploadedFile] 直接填 [TimelineClip.file]，用户不动它就能原样提交 ——
 * 这正是"在电脑上摆好时间线、手机上直接生成"能跑通的关键：手机上不需要重新选一次文件。
 */
fun TimelineClip.toDraft(): TimelineClipDraft = TimelineClipDraft(
    id = id,
    uri = null,
    displayName = name,
    start = start,
    duration = duration,
    trimStart = trimStart,
    sourceDuration = sourceDuration,
    hasAudio = hasAudio,
    referenceMode = referenceMode,
    uploadedFile = file
)

/** 片段数量上限：手机上摆得下、也够表达"前段 / 空隙 / 后段"这种结构 */
const val MAX_TIMELINE_CLIPS = 6

/** 按 id 替换一个片段；id 不存在时原样返回（界面上的删除与编辑可能交错到达） */
fun withClipUpdated(
    drafts: List<TimelineClipDraft>,
    id: String,
    transform: (TimelineClipDraft) -> TimelineClipDraft
): List<TimelineClipDraft> = drafts.map { if (it.id == id) transform(it) else it }

/** 删掉一个片段 */
fun withClipRemoved(drafts: List<TimelineClipDraft>, id: String): List<TimelineClipDraft> =
    drafts.filterNot { it.id == id }
