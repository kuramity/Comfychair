package sh.hnet.comfychair.distribute

import org.json.JSONObject
import sh.hnet.comfychair.storage.WorkflowFilePolicy
import sh.hnet.comfychair.update.DownloadPolicy
import sh.hnet.comfychair.util.DebugLogger

/**
 * 资产清单（B5）与单条资产的数据形态。
 *
 * manifest 由桌面端生成脚本产出、经同源 /view 以 `output/comfychair/asset_manifest.json`
 * 下发。App 端单方面定义格式（服务器端脚本照此生成）：
 *
 * ```json
 * {"version": 3, "generated_at": "2026-11-28 12:00:00", "assets": [
 *   {"kind": "prebuilt_workflow", "filename": "prebuilt_35_xxx.json", "sha256": "<64hex>", "size": 12345}
 * ]}
 * ```
 *
 * 解析纪律：
 * - **顶层坏 → 整份拒绝**（[AssetManifestParser.parse] 抛异常）：非 JSON / version 缺失或 <1 /
 *   assets 数组缺失——顶层结构都立不住，谈不上「其余条目仍可用」；
 * - **单条坏 → 只拒该条**：字段缺失 / kind 未知 / filename 非法（路径穿越）/ sha256 非
 *   64 位十六进制 / size 缺失或负——其余条目照常同步；
 * - sha256 缺失即拒，绝不短路放行（DownloadPolicy 同款口径：缺失的校验值等于「把校验关掉」的开关）。
 */
data class AssetManifestEntry(
    val kind: AssetKind,
    val filename: String,
    val sha256: String,
    val size: Long,
)

data class AssetManifest(
    val version: Int,
    val generatedAt: String?,
    val entries: List<AssetManifestEntry>,
)

object AssetManifestParser {

    private const val TAG = "AssetManifest"

    /** manifest 顶层结构不合规时抛出（整份拒绝，调用方按同步失败处理）。 */
    class InvalidManifestException(message: String) : Exception(message)

    fun parse(raw: String): AssetManifest {
        val root = try {
            JSONObject(raw)
        } catch (e: Exception) {
            throw InvalidManifestException("清单不是合法 JSON: ${e.message}")
        }
        val version = root.optInt("version", -1)
        if (version < 1) throw InvalidManifestException("清单 version 缺失或 <1（实际 $version）")
        val generatedAt = root.optString("generated_at", "").takeIf { it.isNotBlank() }
        val assets = root.optJSONArray("assets")
            ?: throw InvalidManifestException("清单 assets 数组缺失")
        val entries = ArrayList<AssetManifestEntry>(assets.length())
        for (i in 0 until assets.length()) {
            val obj = assets.optJSONObject(i) ?: continue
            parseEntry(obj)?.let { entries.add(it) }
        }
        return AssetManifest(version = version, generatedAt = generatedAt, entries = entries)
    }

    /**
     * 条目级解析：任一字段缺失 / 不合规返回 null（该条目拒收），其余条目不受影响。
     * filename 必须是「目录内的单层安全文件名」（WorkflowFilePolicy 同款校验，防路径穿越——
     * manifest 是服务器给的**外部输入**，被构造过的清单不得把文件写到 overlay 目录之外）。
     */
    internal fun parseEntry(obj: JSONObject): AssetManifestEntry? {
        val filename = obj.optString("filename", "")
        val kind = AssetKind.fromWireName(obj.optString("kind", ""))
        if (kind == null) {
            DebugLogger.w(TAG, "清单条目被拒（kind 缺失或未知）：filename=$filename")
            return null
        }
        if (!WorkflowFilePolicy.isValidFilename(filename)) {
            DebugLogger.w(TAG, "清单条目被拒（文件名不安全，疑似路径穿越）：filename=$filename")
            return null
        }
        val sha256 = DownloadPolicy.normalizeSha256(obj.optString("sha256", ""))
        if (sha256 == null) {
            DebugLogger.w(TAG, "清单条目被拒（sha256 缺失或非 64 位十六进制）：filename=$filename")
            return null
        }
        val size = obj.optLong("size", -1L)
        if (size < 0) {
            DebugLogger.w(TAG, "清单条目被拒（size 缺失或为负）：filename=$filename")
            return null
        }
        return AssetManifestEntry(kind = kind, filename = filename, sha256 = sha256, size = size)
    }
}
