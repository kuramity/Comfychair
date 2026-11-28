package sh.hnet.comfychair.prompt

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.distribute.AssetDistributor
import sh.hnet.comfychair.distribute.AssetKind
import sh.hnet.comfychair.storage.WorkflowFilePolicy
import java.io.File

/**
 * 提示词技能（Skill）仓库：列出并读取 `assets/prompts/` 下的 Markdown 规则文件。
 *
 * 这些文件是**规则提示词**——交给工作流里的技能加载器（`QwenTE_SkillLoader` 的
 * `加载文件名` / `加载文件内容` 两个字段），由本地模型按规则把用户需求改写成成品提示词。
 *
 * B5 起支持**服务器下发覆盖层**（`filesDir/overlay/prompts/`）：list 合并两侧清单、
 * 同名 overlay 优先；read 先 overlay 后 assets——下发的规则文件覆盖同名内置版。
 * 文件名在 overlay 读取前必须过 WorkflowFilePolicy 同款安全校验（覆盖层文件名来自
 * 服务器 manifest，属于外部输入，防路径穿越）。
 *
 * 正文**不缓存**：换技能只要替换 assets 或覆盖层里的 md，不需要动代码；缓存会让用户
 * 看到旧规则。文件清单每次都列（目录很小，代价可忽略），失败时退化成空表 —— 调用方
 * 据此隐藏整个下拉，不因为一个可选项读取失败就挡住生成。
 */
object SkillPromptRepository {

    private const val TAG = "SkillPrompt"

    /** assets 下的技能目录 */
    const val ASSET_DIR = "prompts"

    /** 「不注入」的值（空串）：不往工作流里写任何技能，节点按自身默认走 */
    const val NONE = ""

    data class SkillPrompt(
        /** assets 或覆盖层里的文件名（含扩展名），注入到工作流的 `加载文件名` */
        val fileName: String,
        /** 界面显示名（去掉扩展名） */
        val displayName: String,
        /** 是否来自服务器下发覆盖层（同名时读取优先读 overlay 文件） */
        val fromOverlay: Boolean = false,
    )

    /** 服务器下发技能覆盖层的标准位置（与 AssetKind.SKILL_MD 的落盘目录同一常量）。 */
    fun overlayDir(context: Context): File =
        File(File(context.filesDir, AssetDistributor.OVERLAY_DIR), AssetKind.SKILL_MD.dirName)

    /** 列出全部技能文件（覆盖层 ∪ assets，同名 overlay 优先），按文件名排序。 */
    fun list(assets: AssetManager, overlayDir: File? = null): List<SkillPrompt> {
        val assetNames = try {
            assets.list(ASSET_DIR)?.toList() ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "列出技能文件失败", e)
            // assets 失败仍列出覆盖层：部分列表好过全空（不让下发的技能一起消失）
            emptyList()
        }
        val overlayNames = overlayDir?.listFiles { f -> f.isFile }?.map { it.name } ?: emptyList()
        return mergeNames(overlayNames, assetNames)
    }

    /**
     * 清单合并谓词（internal 纯函数，JVM 直测）：overlay 同名条目覆盖 assets 条目，
     * 只认 .md / .txt（与既有 assets 过滤一致），按文件名排序。
     */
    internal fun mergeNames(overlayNames: List<String>, assetNames: List<String>): List<SkillPrompt> {
        fun accepted(name: String): Boolean =
            name.endsWith(".md", ignoreCase = true) || name.endsWith(".txt", ignoreCase = true)
        val merged = LinkedHashMap<String, SkillPrompt>()
        for (name in overlayNames) {
            if (accepted(name)) merged[name] = SkillPrompt(name, name.substringBeforeLast('.'), fromOverlay = true)
        }
        for (name in assetNames) {
            if (accepted(name) && name !in merged) {
                merged[name] = SkillPrompt(name, name.substringBeforeLast('.'), fromOverlay = false)
            }
        }
        return merged.values.sortedBy { it.fileName }
    }

    /** 读取技能正文（先覆盖层后 assets）；文件不存在或读取失败返回 null。 */
    fun read(assets: AssetManager, fileName: String, overlayDir: File? = null): String? {
        if (fileName.isEmpty()) return null
        readFromOverlay(overlayDir, fileName)?.let { return it }
        return try {
            assets.open("$ASSET_DIR/$fileName").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "读取技能文件失败: $fileName", e)
            null
        }
    }

    /**
     * 只读覆盖层侧（internal 纯 File IO，JVM 直测）：文件不在覆盖层时返回 null（调用方
     * 回退 assets）。文件名按 WorkflowFilePolicy 复核——覆盖层文件名理论上由 manifest
     * 保证过校验，但读取入口独立复核一次（防历史版本落盘的脏文件名被读出应用私有文件）。
     */
    internal fun readFromOverlay(overlayDir: File?, fileName: String): String? {
        if (overlayDir == null || fileName.isEmpty()) return null
        if (!WorkflowFilePolicy.isValidFilename(fileName)) return null
        return try {
            val file = File(overlayDir, fileName)
            if (file.isFile) file.readText(Charsets.UTF_8) else null
        } catch (e: Exception) {
            Log.w(TAG, "读取覆盖层技能文件失败: $fileName", e)
            null
        }
    }

    /**
     * 按工作流类型给出默认技能文件名。
     *
     * 只给明确的两种默认（文生图 / 图像编辑）；视频等其余类型默认不注入，
     * 由用户手动选。目标文件不在列表里时同样返回「不注入」，不硬塞一个不存在的名字。
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
