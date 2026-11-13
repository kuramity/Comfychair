package sh.hnet.comfychair.prompt

import android.content.res.AssetManager
import android.util.Log
import sh.hnet.comfychair.WorkflowType

/**
 * 提示词技能（Skill）仓库：列出并读取 `assets/prompts/` 下的 Markdown 规则文件。
 *
 * 这些文件是**规则提示词**——交给工作流里的技能加载器（`QwenTE_SkillLoader` 的
 * `加载文件名` / `加载文件内容` 两个字段），由本地模型按规则把用户需求改写成成品提示词。
 *
 * 正文**不缓存**：换技能只要替换 assets 里的 md，不需要动代码；缓存会让用户看到旧规则。
 * 文件清单每次都列（目录很小，代价可忽略），失败时退化成空表 —— 调用方据此隐藏整个下拉，
 * 不因为一个可选项读取失败就挡住生成。
 */
object SkillPromptRepository {

    private const val TAG = "SkillPrompt"

    /** assets 下的技能目录 */
    const val ASSET_DIR = "prompts"

    /** 「不注入」的值（空串）：不往工作流里写任何技能，节点按自身默认走 */
    const val NONE = ""

    data class SkillPrompt(
        /** assets 里的文件名（含扩展名），注入到工作流的 `加载文件名` */
        val fileName: String,
        /** 界面显示名（去掉扩展名） */
        val displayName: String
    )

    /** 列出全部技能文件，按文件名排序。 */
    fun list(assets: AssetManager): List<SkillPrompt> {
        val names = try {
            assets.list(ASSET_DIR)?.toList() ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "列出技能文件失败", e)
            return emptyList()
        }
        return names
            .filter { it.endsWith(".md", ignoreCase = true) || it.endsWith(".txt", ignoreCase = true) }
            .sorted()
            .map { SkillPrompt(it, it.substringBeforeLast('.')) }
    }

    /** 读取技能正文；文件不存在或读取失败返回 null。 */
    fun read(assets: AssetManager, fileName: String): String? {
        if (fileName.isEmpty()) return null
        return try {
            assets.open("$ASSET_DIR/$fileName").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "读取技能文件失败: $fileName", e)
            null
        }
    }

    /**
     * 按工作流类型给出默认技能文件名。
     *
     * 只给明确的两种默认（文生图 / 图像编辑）；视频等其余类型默认不注入，
     * 由用户手动选。目标文件不在 assets 里时同样返回「不注入」，不硬塞一个不存在的名字。
     */
    fun defaultFor(workflowType: WorkflowType, available: List<SkillPrompt>): String {
        val wanted = when (workflowType) {
            WorkflowType.TTI -> "Qwen-Image-2.1-文生图"
            WorkflowType.ITI_EDITING -> "Qwen-Image-2.1-图像编辑"
            else -> return NONE
        }
        return available.firstOrNull { it.displayName == wanted }?.fileName ?: NONE
    }
}
