package sh.hnet.comfychair.update

import org.json.JSONObject

/**
 * 服务器更新清单 `update.json` 的结构化映射（v4.6.0 内置更新模块）。
 *
 * 服务器端示例：
 * ```json
 * {
 *   "versionCode": 109,
 *   "versionName": "v4.6.0",
 *   "apkUrl": "https://your-domain.example/apk/update/ComfyChair-v4.7.0-code110.apk",
 *   "apkSize": 159305371,
 *   "sha256": "…64 位十六进制…",
 *   "force": false
 * }
 * ```
 *
 * 字段语义（与硬性约束一一对应）：
 * - [versionCode] 与 BuildConfig.VERSION_CODE 做**整型比较**（禁止 versionName 字符串比较）
 * - [apkSize] 用于下载前校验磁盘剩余空间、下载中显示总进度
 * - [sha256] 下载完成后流式校验，通过才原子重命名为 update.apk
 * - [force] 仅控制弹窗文案的强调程度（单用户场景，逻辑极简）
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256: String,
    val force: Boolean,
    /**
     * 本次更新内容（v4.9.2 新增）。
     *
     * 由 `update.json` 的 `changelog` 提供，支持两种写法：
     * - 数组：`"changelog": ["修复取消不生效", "通知栏加进度条"]`（推荐，弹窗逐条显示）
     * - 字符串：`"changelog": "修复取消不生效；通知栏加进度条"`（兼容简单场景，会整体当成一条）
     *
     * 为空时弹窗不显示该区块（老版 update.json 没有这个字段也能正常解析）。
     */
    val changelog: List<String> = emptyList(),
    /**
     * 完整更新说明（v4.10.1 新增）—— 相当于这一版的 README。
     *
     * `changelog` 适合"一句话一条"的短列表；`notes` 用于写得更细的段落
     * （每行一段、`\n` 分段，弹窗里按段落显示，超出高度可滚动）。
     * 两者可以同时存在：先显示 [notes] 段落，再显示 [changelog] 条目。
     */
    val notes: String = ""
) {
    companion object {
        /**
         * 从 update.json 的文本解析；字段缺失或类型不对时抛 IllegalArgumentException，
         * 由调用方（UpdateChecker）统一捕获后静默降级。
         */
        fun fromJson(text: String): UpdateInfo {
            val obj = JSONObject(text)
            val url = obj.optString("apkUrl")
            if (url.isEmpty()) throw IllegalArgumentException("update.json 缺少 apkUrl")
            val code = obj.optInt("versionCode", Int.MIN_VALUE)
            if (code == Int.MIN_VALUE) throw IllegalArgumentException("update.json 缺少 versionCode")
            return UpdateInfo(
                versionCode = code,
                versionName = obj.optString("versionName"),
                apkUrl = url,
                apkSize = obj.optLong("apkSize", -1L),
                sha256 = obj.optString("sha256"),
                force = obj.optBoolean("force", false),
                changelog = parseChangelog(obj),
                notes = obj.optString("notes").trim()
            )
        }

        /**
         * 解析 `changelog`：优先按数组读（每项一条），退化为整串一条，缺失返回空列表。
         *
         * 数组里空白项会被丢掉 —— 手工维护 update.json 时多打一个逗号不该在弹窗里留一行空行。
         */
        private fun parseChangelog(obj: JSONObject): List<String> {
            val array = obj.optJSONArray("changelog")
            if (array != null) {
                return buildList {
                    for (i in 0 until array.length()) {
                        array.optString(i).trim().takeIf { it.isNotEmpty() }?.let { add(it) }
                    }
                }
            }
            val single = obj.optString("changelog").trim()
            return if (single.isEmpty()) emptyList() else listOf(single)
        }
    }
}
