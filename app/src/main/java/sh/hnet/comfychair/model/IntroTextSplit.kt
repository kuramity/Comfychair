package sh.hnet.comfychair.model

/**
 * 把超长的作者原文切成适合逐段渲染的片段。
 *
 * 为什么不整段塞给一个 [androidx.compose.material3.Text]：Compose 的 Text 必须一次性
 * 排版全部字符——最长的原文有 5 万多字符，单次 measure/layout 慢到肉眼可见。
 * 切成小片段交给 LazyColumn 逐段排版后，首屏只排可见的那几段。
 *
 * **硬约束：所有片段按顺序拼接起来必须与原文完全相等**——不丢字符、不丢换行、
 * 不改任何标点（"完整不截断"是 UI 呈现的既定约定）。这条由单测守住。
 *
 * 切分策略：优先在换行（段边界）处断开，让段落显示保持完整；单个段落本身超过
 * [maxCharsPerChunk] 时（没有段边界可借）再按定长硬切。
 */
fun splitIntroText(text: String, maxCharsPerChunk: Int = 1200): List<String> {
    // 钳到 >=1 而不是抛异常：本函数在 UI 渲染路径上（详情页逐段排版），抛异常会让详情页
    // 直接崩；0/负数只可能是调用方笔误，钳成 1 退化为逐字符切分，拼接仍等于原文。
    val max = maxCharsPerChunk.coerceAtLeast(1)
    // 短文本（含空串）整体一个片段：拼接回来还是原文，调用方也无须分支
    if (text.length <= max) return listOf(text)

    val out = ArrayList<String>()
    var start = 0
    while (start < text.length) {
        val remaining = text.length - start
        if (remaining <= max) {
            out.add(text.substring(start))
            break
        }
        // 窗口 [start, start+max) 内回找最后一个段边界，换行符归前一段；
        // 窗口里没有换行（超长单段）才按定长硬切
        val windowEnd = start + max
        val newline = text.lastIndexOf('\n', windowEnd - 1)
        val cut = if (newline >= start) newline + 1 else windowEnd
        out.add(text.substring(start, cut))
        start = cut
    }
    return out
}
