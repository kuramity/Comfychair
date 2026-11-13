package sh.hnet.comfychair.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * 图生3D 的结果状态（唯一一份，全局可读）。
 *
 * 为什么不像图片那样塞进 MediaStateHolder：3D 的产物是 **.glb 文件**（实测 54MB），
 * 既不是 Bitmap 也不该在生成完成时就整包下载 —— 用户可能只想拿走文件、根本没打算
 * 在手机上看。所以这里只保存**文件位置**（filename/subfolder/type 三元组，与 ComfyUI
 * `/history` 里 `outputs[node].3d[0]` 的字段一一对应），真正的下载推迟到用户点开查看器时。
 *
 * 与 [SkillTextResult] 同构：`publish` 新结果、`clear` 清空、UI 订阅 StateFlow。
 */
object ThreeDResultHolder {

    /** 一个 3D 产物在服务器上的位置。 */
    data class Result(
        val filename: String,
        val subfolder: String = "",
        val type: String = "output",
    ) {
        /** `/view` 端点用的查询串（不含 base url）。 */
        val query: String
            get() = "filename=$filename&subfolder=$subfolder&type=$type"
    }

    private val _result = MutableStateFlow<Result?>(null)
    val result: StateFlow<Result?> = _result.asStateFlow()

    fun publish(r: Result?) {
        _result.value = r
    }

    fun clear() {
        _result.value = null
    }

    /**
     * 从 `/history` 的 outputs 里提取 3D 产物。
     *
     * ComfyUI 的 `Save3DAdvanced` 把产物写在**独立字段 `3d`** 里（形状与图片的 `images`
     * 数组一致：`[{filename, subfolder, type}]`），而不是 images —— 只找 images 的话
     * 3D 流在 App 里会显示成"什么都没生成"。
     *
     * @return 没找到返回 null（普通出图流走这里就是安静的空操作）
     */
    fun extract(outputs: JSONObject?): Result? {
        if (outputs == null) return null
        val keys = outputs.keys()
        while (keys.hasNext()) {
            val node = outputs.optJSONObject(keys.next()) ?: continue
            val arr = node.optJSONArray("3d") ?: continue
            if (arr.length() == 0) continue
            val o = arr.optJSONObject(0) ?: continue
            val filename = o.optString("filename", "")
            if (filename.isBlank()) continue
            return Result(
                filename = filename,
                subfolder = o.optString("subfolder", ""),
                type = o.optString("type", "output"),
            )
        }
        return null
    }
}
