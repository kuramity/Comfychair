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
    val force: Boolean
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
                force = obj.optBoolean("force", false)
            )
        }
    }
}
