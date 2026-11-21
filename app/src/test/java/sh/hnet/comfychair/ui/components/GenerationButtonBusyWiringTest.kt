package sh.hnet.comfychair.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 忙碌态皮肤的**接线**钉子（v4.16.14 复核 BUSY-05）。
 *
 * 为什么需要：`RippleSkinStateTest` 只能钉纯函数本身 —— 把 `busy = busy` 从
 * `rippleFeedback` 调用里删掉、把渐变条件改回只判 `enabled`、去掉 `busyDim` 乘子，
 * 纯函数测试仍然全绿（复核已做反证）。这一层"生产代码有没有真的接上去"，JVM 单测
 * 够不着 Compose 组装，沿用本项目既有的源码级契约测试做法（[sh.hnet.comfychair.FeatureWiringContractTest]）：
 * 读源码文本，按**整行语句**或**折叠后的表达式**断言，防止后续被"顺手改回去"。
 *
 * 单测工作目录是模块根（app/），与既有契约测试同口径。
 */
class GenerationButtonBusyWiringTest {

    private val generationButtonPath =
        "src/main/java/sh/hnet/comfychair/ui/components/GenerationButton.kt"
    private val rippleModifierPath =
        "src/main/java/sh/hnet/comfychair/ui/components/ripple/RippleFeedbackModifier.kt"
    private val generationViewModelPath =
        "src/main/java/sh/hnet/comfychair/viewmodel/GenerationViewModel.kt"

    private fun source(path: String): String {
        val file = File(path)
        assertTrue(
            "源文件不存在：${file.absolutePath} —— 单测工作目录应为 app/ 模块根；" +
                "若文件被移动，请同步更新本测试里的路径",
            file.exists()
        )
        return file.readText()
    }

    /** 是否存在**恰好这一条语句**（忽略行首缩进；注释里出现同名字样不算数）。 */
    private fun hasStatement(body: String, statement: String): Boolean =
        body.lines().any { it.trim() == statement }

    /** 折叠空白：用于断言跨行表达式，换行/缩进调整不会误报。 */
    private fun folded(text: String): String = text.replace(Regex("""\s+"""), " ").trim()

    /** 取组件函数体：从签名到下一个 4 空格缩进的 `}` 之前（本文件的函数都按此缩进收尾）。 */
    private fun generationButtonBody(): String {
        val text = source(generationButtonPath)
        val start = text.indexOf("fun GenerationButton(")
        assertTrue("GenerationButton.kt 里找不到 fun GenerationButton(", start >= 0)
        val rest = text.substring(start)
        val end = rest.indexOf("\n}")
        return if (end >= 0) rest.substring(0, end) else rest
    }

    /** 取主按钮（leading）那一次 rippleFeedback 调用的参数段：第一个 .rippleFeedback( 到下一个之前。 */
    private fun leadingRippleCall(): String {
        val body = generationButtonBody()
        val start = body.indexOf(".rippleFeedback(")
        assertTrue("GenerationButton 函数体里找不到 .rippleFeedback( 调用", start >= 0)
        val next = body.indexOf(".rippleFeedback(", start + 1)
        return if (next >= 0) body.substring(start, next) else body.substring(start)
    }

    // ------------------------------------------------------------ ① 忙碌判定与皮肤接线

    @Test
    fun `忙碌判定必须走纯函数且包含isSubmitting`() {
        val body = generationButtonBody()
        assertTrue(
            "GenerationButton 必须用 resolveGenerationButtonSkin 统一判定（离线过滤 + 忙碌）",
            hasStatement(body, "val skin = resolveGenerationButtonSkin(")
        )
        assertTrue(
            "isSubmitting 必须参与忙碌判定（页面侧已按 ownerId 过滤后再传入）",
            hasStatement(body, "isSubmitting = isSubmitting,")
        )
        assertTrue("busy 必须取纯函数结果", hasStatement(body, "val busy = skin.busy"))
        assertTrue("skinActive 必须取纯函数结果", hasStatement(body, "val skinActive = skin.skinActive"))
        assertTrue(
            "忙碌容器透明判定必须取纯函数结果",
            hasStatement(body, "val busyContainerTransparent = skin.busyContainerTransparent")
        )
    }

    @Test
    fun `主按钮必须把busy接进rippleFeedback（复核反证点）`() {
        val leading = leadingRippleCall()
        assertTrue(
            "leading rippleFeedback 必须传 busy = busy（删掉它，忙碌态水波又会消失）",
            hasStatement(leading, "busy = busy,")
        )
        assertTrue(
            "leading 渐变必须由 skinActive 决定（真禁用时 null）",
            folded(leading).contains("gradientColors = if (skinActive) {")
        )
    }

    @Test
    fun `忙碌态禁用容器必须透明且文字走BUSY_CONTENT_ALPHA`() {
        val body = generationButtonBody()
        assertTrue(
            "忙碌态 disabledContainerColor 必须透明（露出渐变）",
            folded(body).contains("disabledContainerColor = if (busyContainerTransparent) { Color.Transparent")
        )
        assertTrue(
            "忙碌态文字必须用 onPrimary@BUSY_CONTENT_ALPHA",
            folded(body).contains("contentColor.copy(alpha = BUSY_CONTENT_ALPHA)")
        )
    }

    // ------------------------------------------------------------ ② 皮肤层：绘制与登记拆开

    @Test
    fun `忙碌态继续绘制点阵但不得登记新波纹`() {
        val text = source(rippleModifierPath)
        assertTrue(
            "绘制/帧循环门控必须用 dotsVisible(showDots, enabled, busy)",
            hasStatement(
                text,
                "val dotsActive = RippleSkinState.dotsVisible(showDots, enabled, busy)"
            )
        )
        assertTrue(
            "新波纹登记必须仍要求 enabled（BUSY-01：disabled 按钮不能给假反馈）",
            hasStatement(text, "val dotsRegister = RippleSkinState.dotsRegister(showDots, enabled)")
        )
        assertTrue(
            "pointerInput 必须挂在 dotsRegister 上（不能跟着 dotsActive 放宽）",
            hasStatement(text, "if (dotsRegister) {")
        )
    }

    /**
     * `dotsActive` 的两个**使用点**（v4.16.14 复核 Minor，第 2 轮 R2-L1 再收紧）。
     *
     * 只钉 `val dotsActive = ...` 与 `if (dotsRegister) {` 不够 —— 把帧循环或绘制层任意一处
     * 改回 `if (dotsActive && enabled)`，忙碌态水波就只剩一处生效（另一处卡死 / 消失），
     * 旧断言仍然全绿。这里按行扫描 `if (dotsActive) {` 的命中行号并要求：
     * - 两处都落在**皮肤函数 `fun Modifier.rippleFeedback(` 的函数体内**；
     * - 一处的所在块含 `withFrameNanos`（帧循环）；
     * - 另一处落在该函数体内的 DrawScope 绘制块（本文件是 `.drawWithContent { }`），
     *   且**所在块里真的有 `shouldDrawDot(`**（点阵绘制本体）。
     *
     * R2-L1 的教训：只按"文件里任一 DrawScope 块"判绘制区过宽 —— 顶栏按钮
     * `RippleIconButton` 的 `.drawBehind {`（本文件 845-851）与点阵毫无关系，
     * 删掉主绘制层门控、在那个块里补一个 `if (dotsActive) {` 就能骗过旧断言。
     * 加上"必须在皮肤函数体内"+"所在块必须含 shouldDrawDot("两道锁后，那种挪法必红。
     */
    @Test
    fun `dotsActive必须在皮肤函数的帧循环与点阵绘制层各用一次（复核反证点）`() {
        val lines = source(rippleModifierPath).lines()
        // 注释里出现同名字样不算数：按整行比较（忽略行首缩进）
        val hits = lines.indices.filter { lines[it].trim() == "if (dotsActive) {" }
        fun where(idx: Int) = "第 ${idx + 1} 行"
        val hitText = if (hits.isEmpty()) "无" else hits.joinToString("、") { where(it) }

        assertTrue(
            "`if (dotsActive)` 必须出现 ≥2 次（帧循环 + 绘制层各一次），实际命中：$hitText；" +
                "把任意一处改回 `if (dotsActive && enabled)` 都会让忙碌态水波卡死或消失",
            hits.size >= 2
        )

        // 皮肤函数体：两处门控都必须在它内部（顶栏按钮 RippleIconButton 与点阵无关）
        val skinStart = lines.indexOfFirst { it.trimStart().startsWith("fun Modifier.rippleFeedback(") }
        assertTrue(
            "找不到 fun Modifier.rippleFeedback( —— 皮肤函数被改名或搬走时请同步本测试的锚点",
            skinStart >= 0
        )
        val skinRegion = regionOf(lines, skinStart)

        // 帧循环：只认行首是 withFrameNanos 的调用行，避开 import 与 KDoc 里的同名字样
        assertTrue(
            "RippleFeedbackModifier.kt 里找不到 withFrameNanos（帧循环被删了？）",
            lines.any { codeOf(it).trimStart().startsWith("withFrameNanos") }
        )

        // 绘制块只看**皮肤函数体内**的 DrawScope lambda
        val drawRegionStarts = lines.indices.filter { i ->
            if (i !in skinRegion) return@filter false
            val t = codeOf(lines[i]).trimStart()
            DRAW_MARKERS.any { t.startsWith(it) }
        }
        assertTrue(
            "皮肤函数体（第 ${skinRegion.first + 1}-${skinRegion.last + 1} 行）内找不到 DrawScope 绘制块" +
                "（标记：${DRAW_MARKERS.joinToString()}）",
            drawRegionStarts.isNotEmpty()
        )
        val drawRegions = drawRegionStarts.map { regionOf(lines, it) }

        fun enclosingRegion(idx: Int) = regionOf(lines, idx)
        fun enclosingBlockText(idx: Int): String {
            val r = enclosingRegion(idx)
            return lines.subList(r.first, r.last + 1).joinToString("\n")
        }
        fun enclosingBlockSpan(idx: Int): String {
            val r = enclosingRegion(idx)
            return "第 ${idx + 1} 行（所在块 ${r.first + 1}-${r.last + 1} 行）"
        }

        val insideSkin = hits.filter { it in skinRegion }
        val inFrame = insideSkin.filter { enclosingBlockText(it).contains("withFrameNanos") }
        val inDraw = insideSkin.filter { idx ->
            drawRegions.any { idx in it } && enclosingBlockText(idx).contains("shouldDrawDot(")
        }

        assertTrue(
            "两处 `if (dotsActive)` 都必须落在皮肤函数 rippleFeedback 体内（第 ${skinRegion.first + 1}-" +
                "${skinRegion.last + 1} 行）；实际命中：$hitText",
            insideSkin.size >= 2
        )
        assertTrue(
            "帧循环区（所在块含 withFrameNanos）必须有一处 `if (dotsActive)`" +
                "（否则提交流程中已扩散的水波会卡在半路）；实际命中：$hitText",
            inFrame.isNotEmpty()
        )
        assertTrue(
            "绘制区必须有一处 `if (dotsActive)`，且它所在块要真的包住点阵绘制（含 shouldDrawDot(）；" +
                "皮肤函数体内的 DrawScope 块起始：${drawRegionStarts.map { where(it) }}，实际命中：$hitText。" +
                "把门控挪到与点阵无关的绘制块（例如顶栏图标的 drawBehind）不算 —— R2-L1",
            inDraw.isNotEmpty()
        )
        assertTrue(
            "两处必须分属不同区域（帧循环一处、点阵绘制层一处）；" +
                "实际：帧循环命中 ${inFrame.map { enclosingBlockSpan(it) }}，" +
                "绘制区命中 ${inDraw.map { enclosingBlockSpan(it) }}",
            inFrame.intersect(inDraw.toSet()).isEmpty()
        )
    }

    /** 去掉行尾注释与行尾空白（结构判定用；保留行首缩进）。空行/纯注释行返回空串。 */
    private fun codeOf(line: String): String = line.trimEnd().substringBefore("//").trimEnd()

    /** 行首空格数（本项目 Kotlin 一律用空格缩进）。 */
    private fun leadingSpaces(line: String): Int = line.takeWhile { it == ' ' }.count()

    /**
     * 逐行**大括号净增量**：跨行状态机跳过块注释、行注释、字符串与字符字面量，
     * 注释与字符串里的花括号不参与配对（与 [sh.hnet.comfychair.ViewerDismissWiringTest] 同口径）。
     */
    private fun braceDeltas(lines: List<String>): IntArray {
        val deltas = IntArray(lines.size)
        var inBlockComment = false
        var inString = false
        var inChar = false
        for ((row, raw) in lines.withIndex()) {
            var i = 0
            var delta = 0
            while (i < raw.length) {
                val c = raw[i]
                val n = if (i + 1 < raw.length) raw[i + 1] else ' '
                when {
                    inBlockComment -> if (c == '*' && n == '/') {
                        inBlockComment = false
                        i++
                    }
                    inString -> when {
                        c == '\\' -> i++
                        c == '"' -> inString = false
                    }
                    inChar -> when {
                        c == '\\' -> i++
                        c == '\'' -> inChar = false
                    }
                    // 行注释：本行剩余全部忽略
                    c == '/' && n == '/' -> i = raw.length
                    c == '/' && n == '*' -> {
                        inBlockComment = true
                        i++
                    }
                    c == '"' -> inString = true
                    c == '\'' -> inChar = true
                    c == '{' -> delta++
                    c == '}' -> delta--
                }
                i++
            }
            deltas[row] = delta
        }
        return deltas
    }

    /**
     * 取 [startIdx] 那一行起的块区间（行号 0 起算，闭区间）。
     *
     * R1-L3 + R2-L2 两轮加固：块边界由**大括号配平**决定，并要求收尾行缩进与签名行一致；
     * 配不平或缩进异常一律抛 AssertionError —— 既不退回 `startIdx..(size-1)`（会把邻近代码
     * 无声吞进块里、让"帧循环区"与"绘制区"重叠），也不允许"真收尾被改缩进/被删后配到后面
     * 别的同缩进 `}`"。
     */
    private fun regionOf(lines: List<String>, startIdx: Int): IntRange {
        val deltas = braceDeltas(lines)
        val want = leadingSpaces(lines[startIdx])
        var depth = 0
        var started = false
        for (i in startIdx until lines.size) {
            depth += deltas[i]
            if (depth > 0) started = true
            if (started && depth == 0) {
                if (leadingSpaces(lines[i]) != want) {
                    throw AssertionError(
                        "第 ${startIdx + 1} 行起的块：按大括号配平得到的收尾在第 ${i + 1} 行" +
                            "（缩进 ${leadingSpaces(lines[i])}），与签名行缩进 $want 不一致 —— " +
                            "收尾被重排或漏配，块边界不可信"
                    )
                }
                return startIdx..i
            }
        }
        throw AssertionError(
            "第 ${startIdx + 1} 行起的块大括号配不平（深度没有归零）—— " +
                "区域判定不可信；若源码结构被重排，请同步 regionOf 的配对规则"
        )
    }

    private companion object {
        /** DrawScope 绘制 lambda 的行首标记（本文件的点阵绘制挂在 `.drawWithContent { }`）。 */
        val DRAW_MARKERS = listOf(
            ".drawWithContent {",
            "drawWithContent {",
            ".drawBehind {",
            "drawBehind {",
            "Canvas(",
            "DrawScope",
        )
    }

    @Test
    fun `忙碌态渐变与白边必须照画并乘busyDim`() {
        val text = source(rippleModifierPath)
        assertTrue(
            "渐变绘制条件必须含 (enabled || busy)",
            folded(text).contains("colors.size >= 2 && (enabled || busy)")
        )
        assertTrue(
            "白边 alpha 必须乘 busyDim",
            folded(text).contains("alpha = (borderAlpha * borderBoost * busyDim).coerceAtMost(1f)")
        )
    }

    @Test
    fun `忙碌态投影与外辉光必须照画并乘busyDim（BUSY-02）`() {
        val text = source(rippleModifierPath)
        assertTrue(
            "向下投影门控必须含 (enabled || busy)",
            folded(text).contains("if (showHalo && haloDownward && (enabled || busy))")
        )
        assertTrue(
            "环形辉光门控必须含 (enabled || busy)",
            folded(text).contains("if (showHalo && !haloDownward && (enabled || busy))")
        )
        assertTrue(
            "投影 alpha 必须乘 busyDim",
            folded(text).contains("alpha = shadowAlphas[idx] * busyDim")
        )
        assertTrue(
            "辉光 alpha 必须乘 busyDim",
            folded(text).contains("alpha = haloAlphas[idx] * busyDim")
        )
    }

    // ------------------------------------------------------------ ③ 别页提交不得污染本页（BUSY-03）

    @Test
    fun `四个生成页的isSubmitting必须按ownerId过滤`() {
        val pages = mapOf(
            "src/main/java/sh/hnet/comfychair/ui/screens/TextToImageScreen.kt" to "TextToImageViewModel.OWNER_ID",
            "src/main/java/sh/hnet/comfychair/ui/screens/ImageToImageScreen.kt" to "ImageToImageViewModel.OWNER_ID",
            "src/main/java/sh/hnet/comfychair/ui/screens/TextToVideoScreen.kt" to "TextToVideoViewModel.OWNER_ID",
            "src/main/java/sh/hnet/comfychair/ui/screens/ImageToVideoScreen.kt" to "ImageToVideoViewModel.OWNER_ID"
        )
        for ((path, ownerId) in pages) {
            val text = source(path)
            assertTrue(
                "$path 必须收集 submitOwner",
                hasStatement(text, "val submitOwner by generationViewModel.submitOwnerId.collectAsStateWithLifecycle()")
            )
            assertTrue(
                "$path 的 isSubmitting 必须按本页 ownerId 过滤（别页提交不得点亮本页忙碌皮肤）",
                hasStatement(text, "isSubmitting = isSubmitting && submitOwner == $ownerId,")
            )
        }
    }

    @Test
    fun `GenerationViewModel必须暴露提交方ownerId`() {
        val text = source(generationViewModelPath)
        assertTrue(
            "必须暴露 submitOwnerId",
            hasStatement(text, "val submitOwnerId: StateFlow<String?> = _submitOwnerId.asStateFlow()")
        )
        assertTrue(
            "startGeneration 抢到闸门后必须登记提交方 ownerId",
            hasStatement(text, "_submitOwnerId.value = ownerId")
        )
    }
}