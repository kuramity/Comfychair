package sh.hnet.comfychair.taglibrary.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import sh.hnet.comfychair.taglibrary.model.AppliedTag
import java.util.concurrent.ConcurrentHashMap

/**
 * 提示词桥接器（标签库 ↔ 生成页面）。
 *
 * 设计动机：ComfyChair 的 4 个生成页面各自持有独立 ViewModel 与提示词状态，
 * 标签库作为独立 Tab 无法直接访问其内部状态。本桥接器由生成页面 ViewModel
 * 在初始化时注册一个轻量适配器（读取/写入提示词 + 上报当前工作流），
 * 标签库通过它读写"当前活动工作流"的提示词，实现需求 5.2 的同步规则。
 *
 * 同步语义（严格对应需求 5.2 表格）：
 * - 点击「+正 / -负」：向提示词末尾追加 `tag, ` 或 `(tag:1.2), `
 * - 构建器改权重：按标签名匹配提示词中的第一个匹配项并更新权重
 * - 构建器删标签：**不**改动提示词（单向删除）
 * - 用户手动编辑提示词：标签库不反向同步（读时实时取值即可）
 */
object PromptBridge {

    /** 提示词通道。 */
    enum class Channel { POSITIVE, NEGATIVE }

    /**
     * 生成页面适配器。实现方必须保证方法在主线程安全（读 StateFlow / 触发 VM 方法）。
     */
    interface Target {
        /** 归属 ID，与生成页面 ViewModel 的 OWNER_ID 一致（如 TEXT_TO_IMAGE）。 */
        val ownerId: String

        /** 当前工作流 ID（未选中时为空串），标签库据此切换记录。 */
        val workflowId: StateFlow<String>

        /** 当前工作流显示名（用于标签库标题提示）。 */
        val workflowName: StateFlow<String>

        /** 读取提示词当前内容。 */
        fun readPrompt(channel: Channel): String

        /** 写入提示词（会同步到生成页面的输入框）。 */
        fun writePrompt(channel: Channel, text: String)
    }

    private val targets = ConcurrentHashMap<String, Target>()

    private val _activeOwnerId = MutableStateFlow("")

    /** 当前活动生成页面归属 ID（标签库据此选择同步目标）。 */
    val activeOwnerId: StateFlow<String> = _activeOwnerId.asStateFlow()

    /** 生成页面注册适配器（ViewModel 初始化时调用）。 */
    fun register(target: Target) {
        targets[target.ownerId] = target
        android.util.Log.i("PromptBridge", "register: ${target.ownerId} (total=${targets.size})")
    }

    /** 生成页面注销适配器（ViewModel 销毁时调用）。 */
    fun unregister(ownerId: String) {
        targets.remove(ownerId)
        if (_activeOwnerId.value == ownerId) _activeOwnerId.value = ""
        android.util.Log.i("PromptBridge", "unregister: $ownerId (total=${targets.size})")
    }

    /** 更新当前活动页面（页面切换时由宿主调用）。 */
    fun setActive(ownerId: String) {
        _activeOwnerId.value = ownerId
        android.util.Log.i("PromptBridge", "setActive: $ownerId (registered=${targets.keys})")
    }

    /** 当前活动目标（未注册或未激活时返回 null）。 */
    fun activeTarget(): Target? = targets[_activeOwnerId.value]

    /** 指定归属的目标（详情页固定同步到某个页面时使用）。 */
    fun targetOf(ownerId: String): Target? = targets[ownerId]

    /**
     * 追加标签到提示词末尾。
     *
     * @return true 表示已写入；false 表示当前没有可写入的目标（页面未注册）
     */
    fun appendTag(channel: Channel, tag: String, weight: Float): Boolean {
        val target = activeTarget() ?: return false
        val current = target.readPrompt(channel)
        val token = AppliedTag(tag, weight).toPromptToken()
        val newText = appendToken(current, token)
        target.writePrompt(channel, newText)
        return true
    }

    /**
     * 把标签插入到提示词的指定位置（不只是末尾）。
     *
     * @param segmentIndex 插到第几段**之前**：0 = 最前面，等于段数时等价于追加到末尾
     * @return true 表示已写入；false 表示当前没有可写入的目标
     */
    fun insertTagAt(channel: Channel, tag: String, weight: Float, segmentIndex: Int): Boolean {
        val target = activeTarget() ?: return false
        val current = target.readPrompt(channel)
        val token = AppliedTag(tag, weight).toPromptToken()
        target.writePrompt(channel, insertToken(current, token, segmentIndex))
        return true
    }

    /**
     * 读取提示词当前内容（供"选择插入位置"界面列出各段）。
     *
     * @return 目标不存在时返回 null，调用方据此提示"没有可写入的页面"
     */
    fun readPromptOf(channel: Channel): String? = activeTarget()?.readPrompt(channel)

    /**
     * 把提示词按逗号切成段（去空白、丢空段），供位置选择界面逐段展示。
     */
    fun splitSegments(text: String): List<String> =
        text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * 更新提示词中某个标签的权重（只改第一个匹配项）。
     *
     * @return true 表示找到并更新；false 表示提示词中已无该标签（不报错，需求 5.3）
     */
    fun updateTagWeight(channel: Channel, tag: String, newWeight: Float): Boolean {
        val target = activeTarget() ?: return false
        val current = target.readPrompt(channel)
        val updated = replaceFirstWeight(current, tag, newWeight) ?: return false
        target.writePrompt(channel, updated)
        return true
    }

    /**
     * 在第 [index] 段之前插入 token，输出统一为 `a, b, c, ` 的形式
     * （与 [appendToken] 的尾逗号习惯一致，方便继续追加）。
     */
    private fun insertToken(current: String, token: String, index: Int): String {
        val segments = splitSegments(current)
        val at = index.coerceIn(0, segments.size)
        val merged = segments.toMutableList().apply { add(at, token) }
        return merged.joinToString(", ") + ", "
    }

    /** 提示词拼接：始终追加到末尾，逗号 + 空格分隔。 */
    private fun appendToken(current: String, token: String): String {
        val trimmed = current.trimEnd()
        return when {
            trimmed.isEmpty() -> "$token, "
            trimmed.endsWith(",") -> "$trimmed $token, "
            else -> "$trimmed, $token, "
        }
    }

    /**
     * 替换提示词中该标签（第一个匹配项）的权重。
     *
     * 支持两种既有写法：
     * - 带权重：`(long_hair:1.2)` / `( long_hair : 1.2 )`
     * - 无权重：`long_hair`（前后为非单词字符或首尾）
     *
     * @return 替换后的文本；未找到匹配时返回 null
     */
    private fun replaceFirstWeight(text: String, tag: String, newWeight: Float): String? {
        if (tag.isEmpty()) return null
        val escaped = Regex.escape(tag)
        val token = AppliedTag(tag, newWeight).toPromptToken()

        // 1) 优先替换带权重的写法
        val weighted = Regex("""\(\s*$escaped\s*:\s*[\d.]+\s*\)""")
        if (weighted.containsMatchIn(text)) {
            return weighted.replaceFirst(text, Regex.escapeReplacement(token))
        }

        // 2) 再替换裸标签写法（词边界：前后不能是字母/数字/下划线，避免误伤长标签）
        val plain = Regex("""(?<![\w])$escaped(?![\w])""")
        if (plain.containsMatchIn(text)) {
            return plain.replaceFirst(text, Regex.escapeReplacement(token))
        }
        return null
    }
}
