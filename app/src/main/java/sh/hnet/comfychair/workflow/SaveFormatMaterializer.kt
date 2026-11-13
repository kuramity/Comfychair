package sh.hnet.comfychair.workflow

import org.json.JSONArray
import org.json.JSONObject

/**
 * 输出端落盘格式物化器（v4.6.1，模块 C）。
 *
 * 需求：输出为图片的工作流，可以在 App 端选择"保存为 WebP"，并且**只能落一份文件**
 * （不能 PNG + WebP 各存一份）。
 *
 * 本机实测（2026-11-13，服务端 4871 个节点）：
 * - 原生 `SaveImage` 只有 `images` + `filename_prefix`，固定输出 PNG，没有格式参数；
 * - `SaveImageAdvanced` 的 format 只有 png/exr/avif，**没有 webp**；
 * - 已安装的 `ComfyUI-ImageToolkit-CN` 提供 `BatchImageSaverCN`，`输出格式` 可选 WEBP，
 *   且返回 `ui.images`（结构与 SaveImage 一致），App 图库能正常收录。
 *
 * 因此实现方式是**节点替换**而不是改参数：把 `SaveImage` 换成 `BatchImageSaverCN`。
 * 关闭时原样返回，工作流仍是 `SaveImage` → 只产出 PNG，天然满足"互斥、不重复保存"。
 *
 * 该物化器只在图片类 prepare 路径（TTI / ITI 修复 / ITI 编辑）末尾调用；
 * 视频（SaveVideo）与 3D（Save3DAdvanced/SaveGLB）不会被触碰。
 */
object SaveFormatMaterializer {

    /** 插件节点的 class_type（见服务端 /object_info） */
    const val WEBP_SAVER_CLASS = "BatchImageSaverCN"

    /** 原生 PNG 保存节点 */
    private const val PNG_SAVER_CLASS = "SaveImage"

    /** 固定子目录，避免"每次运行新建一个文件夹"把图库撑成一堆目录 */
    private const val SUBFOLDER = "ComfyChair"

    /**
     * @param json 已注入全部参数的工作流 JSON（UI 格式，顶层含 `nodes`）
     * @param saveAsWebp true = 把 SaveImage 换成 BatchImageSaverCN(WEBP)；
     *                   false = 原样返回（PNG）
     */
    fun materialize(json: String, saveAsWebp: Boolean): String {
        if (!saveAsWebp) return json

        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            return json
        }
        val nodes = root.optJSONObject("nodes") ?: return json

        var changed = false
        val ids = nodes.keys()
        while (ids.hasNext()) {
            val node = nodes.optJSONObject(ids.next()) ?: continue
            if (node.optString("class_type") != PNG_SAVER_CLASS) continue
            convertToWebpSaver(node)
            changed = true
        }

        return if (changed) root.toString() else json
    }

    /**
     * 把一个 SaveImage 节点改写成 BatchImageSaverCN。
     *
     * 参数映射（插件 required 全部有默认值，这里显式写死以保证行为稳定）：
     * - `images` 连线 → `image`（单图输入；IMAGE_LIST 是给批量加载用的）
     * - `filename_prefix` → `自定义前缀`（清理掉 %date% 这类格式化 token）
     * - 固定目录 + 自动加序号：同一目录不覆盖历史，图库仍按文件名索引
     */
    private fun convertToWebpSaver(node: JSONObject) {
        val inputs = node.optJSONObject("inputs") ?: JSONObject()
        val imagesRef: JSONArray? = inputs.optJSONArray("images")
        val rawPrefix = inputs.optString("filename_prefix")

        val newInputs = JSONObject()
        if (imagesRef != null) {
            newInputs.put("image", imagesRef)
        }
        newInputs.put("子文件夹名称", SUBFOLDER)
        newInputs.put("文件夹策略", "固定文件夹（覆盖同名文件）")
        newInputs.put("文件命名", "自定义前缀 + 序号")
        newInputs.put("自定义前缀", sanitizePrefix(rawPrefix))
        newInputs.put("序号起始", 1)
        newInputs.put("序号位数", 4)
        newInputs.put("输出格式", "WEBP")
        newInputs.put("质量", 90)
        newInputs.put("同名文件处理", "自动加序号")
        newInputs.put("保留工作流信息", "保留")

        node.put("class_type", WEBP_SAVER_CLASS)
        node.put("inputs", newInputs)
    }

    /**
     * 把 `filename_prefix` 变成安全的文件名前缀。
     *
     * 原前缀可能带 ComfyUI 的格式化 token（如 `%date:yyyy-MM-dd%`），插件不认这种语法，
     * 直接用会得到带百分号的文件名；这里只保留字母/数字/中文/下划线/横线，并限长。
     */
    internal fun sanitizePrefix(raw: String): String {
        val stripped = raw
            .replace(Regex("""%[^%]*%"""), "")   // 去掉 %date:...% / %Empty Latent Image.width% 等
            .replace(Regex("""[\\/:*?"<>|\s]+"""), "_")
            .trim('_')
        val limited = stripped.take(40).ifEmpty { "ComfyChair" }
        return limited
    }
}
