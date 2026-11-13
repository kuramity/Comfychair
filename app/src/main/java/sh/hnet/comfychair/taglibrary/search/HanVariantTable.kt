package sh.hnet.comfychair.taglibrary.search

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * 繁简 / 异体字归一化表。
 *
 * 背景：danbooru 的 alias 字段里繁简混杂——日文社区写「長乳首」、中文翻译写「长乳首」，
 * 日文新字体（髪 / 気 / 絵）更是成套出现。手机上输入简体「长乳首」时，
 * 子串匹配 <code>"长乳首" in "長乳首"</code> 为 false，标签就搜不出来。
 *
 * 做法：把「繁体 + 日文新字体 + 异体字」统一压成简体，**索引侧与查询侧各过一遍**，
 * 两边写法不同也能对上（長乳首 → 长乳首 == 用户输入的长乳首）。
 *
 * 数据随 APK 打包在 `assets/databases/han_variants.json`（约 950 条单字映射，11KB），
 * 由 `tools/comfyui-scripts/build_han_variants.py` 生成。
 */
object HanVariantTable {

    private const val TAG = "HanVariantTable"
    private const val ASSET_PATH = "databases/han_variants.json"

    @Volatile
    private var charMap: Map<Char, Char>? = null

    /** 是否已加载（未加载时 [simplify] 原样返回）。 */
    val isLoaded: Boolean get() = charMap != null

    /** 懒加载映射表（线程安全，失败时退化为"不归一化"，不影响搜索主流程）。 */
    fun ensureLoaded(context: Context) {
        if (charMap != null) return
        synchronized(this) {
            if (charMap != null) return
            charMap = try {
                val json = context.assets.open(ASSET_PATH).use { input ->
                    input.readBytes().decodeToString()
                }
                val obj = JSONObject(json)
                val map = HashMap<Char, Char>(obj.length() * 4 / 3 + 1)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = obj.optString(key)
                    // 只接受单字映射，多字/空值一律忽略
                    if (key.length == 1 && value.length == 1) map[key[0]] = value[0]
                }
                map
            } catch (e: Exception) {
                Log.w(TAG, "繁简映射表加载失败，搜索将不做繁简归一化", e)
                emptyMap()
            }
        }
    }

    /** 测试注入（JVM 单测没有 assets）。 */
    internal fun setMappingForTest(map: Map<Char, Char>) {
        charMap = map
    }

    /** 测试清理。 */
    internal fun resetForTest() {
        charMap = null
    }

    /**
     * 把文本中的繁体/日文新字体/异体字压成简体。
     *
     * 无变化时返回原字符串（避免无谓的分配——这个方法在索引构建里要跑十万次）。
     */
    fun simplify(text: String): String {
        val table = charMap ?: return text
        if (text.isEmpty() || table.isEmpty()) return text

        // 第一遍只找"第一个需要改写的字符"，不分配任何对象。
        // 绝大多数文本里一个繁体字都没有（这个方法光索引构建就要跑十万次），
        // 原先无条件建 StringBuilder 逐字符 append 再丢弃，纯属白付。
        var firstChange = -1
        for (i in text.indices) {
            val mapped = table[text[i]]
            if (mapped != null && mapped != text[i]) {
                firstChange = i
                break
            }
        }
        if (firstChange < 0) return text

        // 确有需要改写的字符，才构造新串（变化点之前的部分整段复制）
        val sb = StringBuilder(text.length)
        sb.append(text, 0, firstChange)
        for (i in firstChange until text.length) {
            val ch = text[i]
            val mapped = table[ch]
            sb.append(mapped ?: ch)
        }
        return sb.toString()
    }
}
