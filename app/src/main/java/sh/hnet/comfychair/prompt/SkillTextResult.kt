package sh.hnet.comfychair.prompt

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * 技能流（prebuilt_24/25）的**文本产物**通道。
 *
 * 这类工作流不出图：本地模型按 Skill 规则把用户需求改写成成品提示词，结果落在
 * `TE_text_display` 的输出里（实测 `/history` 的 outputs 节点下有 `text` 与 `input_text`
 * 两个单元素数组）。图片那套预览链路（MediaStateHolder + Bitmap）完全用不上，
 * 所以这里单独开一条最轻的通道：谁取到就 `publish`，界面观察 `text` 后弹窗展示。
 *
 * 用共享单例而不是各页 UiState 字段的原因与 [sh.hnet.comfychair.media.MediaStateHolder]
 * 一致：文生图与图生图两条链路共用一个取结果函数，塞进各自的 UiState 就要把提取逻辑抄两遍。
 * 语义上它也只是"最近一次生成的文本"，多页共存时不会互相覆盖出有意义的分歧。
 */
object SkillTextResult {

    private val _text = MutableStateFlow<String?>(null)
    private val _dialogVisible = MutableStateFlow(false)
    private val _hasHidden = MutableStateFlow(false)
    private val _ownerWorkflow = MutableStateFlow<String?>(null)

    /** 最近一次技能流的文本产物；null 表示还没有结果。 */
    val text: StateFlow<String?> = _text.asStateFlow()

    /** 弹窗该不该显示。关掉它**不清结果** —— 页面上会留一个按钮随时再打开。 */
    val dialogVisible: StateFlow<Boolean> = _dialogVisible.asStateFlow()

    /** 有结果但弹窗已关掉 —— 此时页面上显示「查看生成的提示词」按钮。 */
    val hasHiddenResult: StateFlow<Boolean> = _hasHidden.asStateFlow()

    /**
     * 这份结果是**哪个工作流**产出的。
     *
     * 常驻按钮必须跟着工作流走：用户在「Qwen2.1-Skill提示词强化」里生成完，
     * 切到别的流之后按钮还赖在界面上（实测反馈"画面很拥挤"）；切回去才该重新出现
     * 上一次的结果。归属由界面在结果首次出现时绑定（[bindOwner]）—— 生成链路那边
     * 拿不到工作流名，硬塞一个参数下去要把 5 个提交入口全改一遍。
     */
    val ownerWorkflow: StateFlow<String?> = _ownerWorkflow.asStateFlow()

    /** 发布结果；传 null 表示清除。有新结果就自动弹出，归属等待界面重新绑定。 */
    fun publish(value: String?) {
        _text.value = value
        _dialogVisible.value = value != null
        _hasHidden.value = false
        _ownerWorkflow.value = null
    }

    /**
     * 界面侧调用：把"还没有归属"的结果绑定到当前工作流。
     *
     * 只在结果刚出现、归属还是空的时候生效 —— 否则用户切一次工作流就会把归属改走，
     * 切回来时那次结果再也找不着了。
     */
    fun bindOwner(workflowName: String) {
        if (_text.value != null && _ownerWorkflow.value == null) {
            _ownerWorkflow.value = workflowName
        }
    }

    /**
     * 用户关掉弹窗：只收起，**保留**结果。
     *
     * 原先关闭等于清空，于是那段提示词再也找不回来 —— 用户想复制时只能重跑一次生成
     * （本地模型改写动辄几十秒）。现在结果留在通道里，页面上的常驻按钮可以随时打开。
     */
    fun dismiss() {
        _dialogVisible.value = false
        _hasHidden.value = _text.value != null
    }

    /** 点常驻按钮：重新打开弹窗（没有结果时什么都不做）。 */
    fun reopen() {
        if (_text.value == null) return
        _dialogVisible.value = true
        _hasHidden.value = false
    }

    /** 彻底清除结果（换工作流/换生成目标时用）。 */
    fun clear() {
        _text.value = null
        _dialogVisible.value = false
        _hasHidden.value = false
    }

    /**
     * 从 `/history` 的 `outputs` 里提取文本产物；没有则返回 null。
     *
     * 认两种键（都是 TE_text_display 的输出形态），取第一个非空白值 ——
     * 普通出图流的 outputs 里只有 `images`，这里会安静地返回 null。
     *
     * **v4.8.2 修复**：保存类节点（`BatchImageSaverCN` / `SaveImage`）**同样**会在
     * outputs 的 `text` 字段里返回一段话，内容是"已保存 N 张图片到：<路径>（共 X KB）"。
     * 原实现只判"非空白"，于是这段保存信息被当成技能流提示词弹了出来 ——
     * 弹窗标题写着"生成的提示词"，正文却是 `F:\ComfyUI\output\ComfyChair`（实测截图确认）。
     * 现在先按内容特征把保存回执排除掉。
     */
    fun extract(outputs: JSONObject?): String? {
        if (outputs == null) return null
        for (nodeId in outputs.keys()) {
            val node = outputs.optJSONObject(nodeId) ?: continue
            for (key in TEXT_KEYS) {
                val array = node.optJSONArray(key) ?: continue
                val value = array.optString(0, "")
                if (value.isNotBlank() && !isSaveReceipt(value)) return value
            }
        }
        return null
    }

    /** 保存节点的回执文本（中/英两种形态），不是提示词，必须排除 */
    internal fun isSaveReceipt(value: String): Boolean {
        if (value.contains("张图片到")) return true          // 已保存 1 张图片到：…
        if (value.contains("张图已保存")) return true
        if (value.startsWith("已保存")) return true
        val lower = value.lowercase()
        if (lower.contains("saved") && lower.contains("image")) return true  // Saved N image(s) to …
        if (lower.contains("image") && lower.contains("saved to")) return true
        return false
    }

    private val TEXT_KEYS = listOf("text", "input_text")
}
