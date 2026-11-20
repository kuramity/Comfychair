package sh.hnet.comfychair.util

import org.json.JSONObject

/**
 * 从 ComfyUI `/history` 的 `outputs` 里挑出视频产物的统一规则（P2-6）。
 *
 * 修复前 [VideoUtils.fetchVideoFromHistory] 的写法是
 * `videos ?: gifs ?: images` 之后取 `[0]`，有三个问题：
 * 1. **空数组会遮蔽**：某个节点的 `videos` 是空数组时，`?:` 不会继续看 `gifs`/`images`，
 *    更不会看后面的节点 —— 只要有任意一个节点带空 `videos`，整条挑选就提前结束；
 * 2. **不校验扩展名**：`images` 里的 png 也会被当成视频下载；
 * 3. **顺序不定**：`JSONObject.keys()` 底层是 HashMap，同一份历史每次可能挑到不同文件。
 *
 * 现在统一为：遍历**所有**节点 → 依次检查 `videos` / `gifs` / `images` 三个数组 →
 * 按扩展名过滤 → 节点 id 排序保证结果稳定。抽成无 Android 依赖的 object，
 * JVM 单测可直接覆盖。
 */
internal object VideoOutputSelector {

    /** 与 GalleryRepository 的 VIDEO_EXTENSIONS 保持同一份判定口径。 */
    val VIDEO_EXTENSIONS = listOf(".mp4", ".webm", ".gif", ".avi", ".mov")

    /** `/history` 里可能承载视频产物的数组字段（按优先级）。 */
    private val ARRAY_KEYS = listOf("videos", "gifs", "images")

    /** 一条视频产物在 `/view` 请求里需要的三个字段。 */
    data class VideoOutput(
        val filename: String,
        val subfolder: String,
        val type: String
    )

    fun isVideoFilename(filename: String): Boolean {
        val lower = filename.lowercase()
        return VIDEO_EXTENSIONS.any { lower.endsWith(it) }
    }

    /**
     * 列出 outputs 里所有视频产物（去重）。
     *
     * 顺序固定为：`videos` 字段的全部节点 → `gifs` 字段的全部节点 → `images` 字段的全部节点；
     * 同一字段内按节点 id 升序、数组内保持原顺序。这样同一份历史每次挑到的"第一个视频"
     * 都相同，且保留旧实现「videos 优先」的意图。
     *
     * outputs 为 null / 不是对象时返回空列表。
     */
    fun findAll(outputs: JSONObject?): List<VideoOutput> {
        if (outputs == null) return emptyList()
        val result = LinkedHashMap<String, VideoOutput>()
        // keys() 顺序未定义；排序后同一份历史总是得到同一个"第一个视频"
        val nodeIds = outputs.keys().asSequence().toList().sorted()
        // 数组字段在外层：先看所有节点的 videos，再看所有节点的 gifs，最后才是 images。
        // 这样既保留旧实现「videos 优先」的意图，又不会被某个节点的空数组遮蔽。
        for (arrayKey in ARRAY_KEYS) {
            for (nodeId in nodeIds) {
                val nodeOutput = outputs.optJSONObject(nodeId) ?: continue
                val array = nodeOutput.optJSONArray(arrayKey) ?: continue
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val filename = item.optString("filename", "").trim()
                    if (filename.isEmpty() || !isVideoFilename(filename)) continue
                    val output = VideoOutput(
                        filename = filename,
                        subfolder = item.optString("subfolder", ""),
                        type = item.optString("type", "output").ifBlank { "output" }
                    )
                    // 同一文件可能同时出现在 videos 与 images：按 (type, subfolder, filename) 去重
                    val identity = "${output.type}\u0000${output.subfolder}\u0000${output.filename}"
                    result.putIfAbsent(identity, output)
                }
            }
        }
        return result.values.toList()
    }

    /** 取第一个视频产物；没有时返回 null。 */
    fun first(outputs: JSONObject?): VideoOutput? = findAll(outputs).firstOrNull()
}