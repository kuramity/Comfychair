package sh.hnet.comfychair.viewmodel.base

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B4.2-B 视频对合并（TTV/ITV → BaseVideoGenerationViewModel）的源码级钉子。
 *
 * 为什么需要这种（确实不优雅的）测试：两个视频页 ViewModel 要 Android 环境，跑不进
 * JVM 单测，"重复代码有没有真的收敛到基类、时序红线有没有被搬丢"这一层纯逻辑测试够不着。
 * 这里用**读源码文本**的方式钉住五件事：
 *
 * 1. 基类存在且承载 ≥10 个代表性 SAME 函数（onWorkflowChange 不在列表里——它是 DIFF
 *    函数，钉在守卫测试里）；
 * 2. TTV/ITV 里这些函数不再是 `private fun`（已上提，子类不再各自持有一份）；
 * 3. onWorkflowChange 的重选守卫（行为增强）在基类里；
 * 4. init 的 modelCache 监听体里「空快照守卫 + deferred 清 null」同在（时序红线 1：
 *    拆开迁移会让 P1-7 的保护失效）；
 * 5. TTI/ITI 未被本波触碰（ITI 有 FeatureWiringContractTest 的文本级钉子，动了必红）。
 * 断言前把空白折叠成单空格，所以换行与缩进调整不会误报。
 */
class VideoPairMergeContractTest {

    private val basePath =
        "src/main/java/sh/hnet/comfychair/viewmodel/base/BaseVideoGenerationViewModel.kt"
    private val supportPath =
        "src/main/java/sh/hnet/comfychair/viewmodel/base/VideoGenerationSupport.kt"
    private val ttvPath =
        "src/main/java/sh/hnet/comfychair/viewmodel/TextToVideoViewModel.kt"
    private val itvPath =
        "src/main/java/sh/hnet/comfychair/viewmodel/ImageToVideoViewModel.kt"
    private val ttiPath =
        "src/main/java/sh/hnet/comfychair/viewmodel/TextToImageViewModel.kt"
    private val itiPath =
        "src/main/java/sh/hnet/comfychair/viewmodel/ImageToImageViewModel.kt"

    /** 读源码；单测工作目录是模块根（`app/`），与 `FeatureWiringContractTest` 同一口径。 */
    private fun source(path: String): String {
        val file = File(path)
        assertTrue(
            "源文件不存在：${file.absolutePath} —— 单测工作目录应为 app/ 模块根；" +
                "若文件被移动，请同步更新本测试里的路径",
            file.exists()
        )
        return file.readText()
    }

    /**
     * 取出一个函数/语句块的正文：从签名起，到下一个 4 空格缩进的成员声明之前
     * （**原样保留空白**，由调用方决定按整行还是折叠后断言）。
     */
    private fun bodyOf(sourceText: String, signature: String): String {
        val start = sourceText.indexOf(signature)
        assertTrue("源码里找不到函数：$signature", start >= 0)
        val rest = sourceText.substring(start + signature.length)
        val nextMember = Regex("""\n {4}(private |internal |public |protected |override |abstract |open |companion|init\b|@)?(suspend )?(override )?(private |internal |public |protected )?(fun|val|var|companion|init)\b""")
            .find(rest)
        return if (nextMember != null) rest.substring(0, nextMember.range.first) else rest
    }

    /** 折叠空白：用于断言跨行表达式，换行/缩进调整不会误报。 */
    private fun folded(text: String): String = text.replace(Regex("""\s+"""), " ").trim()

    // ------------------------------------------------------------ ① 基类承载共享函数

    @Test
    fun `基类承载视频页共享函数`() {
        val base = source(basePath)
        // 代表性 SAME 函数（≥10，onWorkflowChange 除外——它在守卫钉子里单测）
        val signatures = listOf(
            "fun loadWorkflows(",
            "fun restorePreferences(",
            "fun saveWorkflowValues(",
            "fun savePreferences(",
            "fun loadLastGeneratedVideo(",
            "fun onCheckpointChange(",
            "fun onClip3Change(",
            "fun onWidthChange(",
            "fun onSeedChange(",
            "fun onRandomizeSeed(",
            "fun resetPromptToDefault(",
            "fun handleGenerationEvent(",
            "fun fetchGeneratedVideo(",
            "fun saveLastPreviewImage("
        )
        val missing = signatures.filterNot { base.contains(it) }
        assertTrue(
            "BaseVideoGenerationViewModel 缺少共享函数：$missing",
            missing.isEmpty()
        )
    }

    // ------------------------------------------------------------ ② 子类不再各自持有重复定义

    @Test
    fun `子类不再重复定义已上提的函数`() {
        // 上提后 TTV/ITV 里不该再有任何一份（private 或其它可见性）定义；
        // 若有人往子类加回同名定义（遮蔽基类行为），这里立刻变红。
        val lifted = listOf(
            "fun loadWorkflows(",
            "fun savePreferences(",
            "fun restorePreferences(",
            "fun onCheckpointChange(",
            "fun onClip3Change(",
            "fun loadLastGeneratedVideo("
        )
        listOf(ttvPath, itvPath).forEach { path ->
            val text = source(path)
            val leaked = lifted.filter { text.contains(it) }
            assertTrue(
                "$path 仍重复定义已上提的函数：$leaked（应继承 BaseVideoGenerationViewModel）",
                leaked.isEmpty()
            )
        }
    }

    // ------------------------------------------------------------ ③ 重选守卫（行为增强，两侧统一）

    @Test
    fun `onWorkflowChange 必须带重选守卫`() {
        // "手滑重选自己"会经 loadWorkflowValues 把调好的值全部重置回工作流默认。
        // ITV 原本有这道守卫，TTV 补齐后统一上提基类；守卫必须是副作用（save/load）之前
        // 的语句，否则守卫形同虚设。
        val base = source(basePath)
        val body = folded(bodyOf(base, "fun onWorkflowChange("))
        assertTrue(
            "onWorkflowChange 必须含重选守卫 if (workflowItem.id == state.selectedWorkflowId) return",
            body.contains("if (workflowItem.id == state.selectedWorkflowId) return")
        )
        // 链序红线：守卫之后必须仍是 saveWorkflowValues(旧) → loadWorkflowValues(新) → savePreferences
        val guardPos = body.indexOf("if (workflowItem.id == state.selectedWorkflowId) return")
        val savePos = body.indexOf("saveWorkflowValues(state.selectedWorkflowId)")
        val loadPos = body.indexOf("loadWorkflowValues(workflowItem)")
        val prefsPos = body.indexOf("savePreferences()")
        assertTrue("链序红线：saveWorkflowValues(旧流) 必须在 loadWorkflowValues(新流) 之前", savePos in 0 until loadPos)
        assertTrue("链序红线：loadWorkflowValues(新流) 必须在 savePreferences() 之前", loadPos in 0 until prefsPos)
        assertTrue("重选守卫必须先于 save/load 链（守卫要在一切副作用之前）", guardPos in 0 until savePos)
    }

    // ------------------------------------------------------------ ④ 空快照守卫与 deferred 清 null 同监听体（时序红线 1）

    @Test
    fun `modelCache 监听体里空快照守卫与 deferred 清空同在`() {
        // P1-7：空模型缓存快照直接 return，避免把已选模型 / LoRA 链 / deferred 值清掉；
        // deferred 清 null 必须与守卫在同一监听函数体里（拆开迁移 = 守卫失效）。
        val base = source(basePath)
        val body = bodyOf(base, "ConnectionManager.modelCache.collect")
        assertTrue(
            "监听体必须保留空快照守卫 ValidationUtils.isUsableModelCache(cache)",
            body.contains("ValidationUtils.isUsableModelCache(cache)")
        )
        assertTrue(
            "监听体必须保留 deferred 清 null（deferredCheckpoint = null）",
            body.contains("deferredCheckpoint = null")
        )
        // 守卫要在清 null 之前出现（原样保留保护顺序）
        assertTrue(
            "空快照守卫必须先于 deferred 清空",
            body.indexOf("ValidationUtils.isUsableModelCache(cache)") <
                body.indexOf("deferredCheckpoint = null")
        )
    }

    // ------------------------------------------------------------ ⑤ TTI/ITI 未被触碰

    @Test
    fun `文生图与图生图页面未被本波合并触碰`() {
        // ITI 有 FeatureWiringContractTest 的文本级钉子（private fun saveWorkflowValues 里
        // 的 isActiveMode 判据），动了必红且反证零覆盖；TTI 同理。
        // 这里断言两文件的关键私有函数原样在场——若有人把视频对的合并"顺手"扩到图对，
        // 这个测试与 FeatureWiringContractTest 会一起变红。
        assertTrue(
            "TextToImageViewModel 必须仍持有 private fun loadWorkflowValues(",
            source(ttiPath).contains("private fun loadWorkflowValues(")
        )
        assertTrue(
            "ImageToImageViewModel 必须仍持有 private fun saveWorkflowValues(",
            source(itiPath).contains("private fun saveWorkflowValues(")
        )
    }

    // ------------------------------------------------------------ ⑥ 数据类归一与接口扩展

    @Test
    fun `工作流条目归一为共享类型且接口扩展 deferred 字段`() {
        // TtvWorkflowItem / ItvWorkflowItem 归一后逐字节相同 → 提取共享 VideoWorkflowItem，
        // 两侧用 typealias 保持调用点零改动。
        val ttv = source(ttvPath)
        val itv = source(itvPath)
        assertTrue(
            "TTV 侧必须用 typealias TtvWorkflowItem = VideoWorkflowItem",
            ttv.contains("typealias TtvWorkflowItem = VideoWorkflowItem")
        )
        assertTrue(
            "ITV 侧必须用 typealias ItvWorkflowItem = VideoWorkflowItem",
            itv.contains("typealias ItvWorkflowItem = VideoWorkflowItem")
        )
        // VideoGenerationState 扩入 deferred* 全系列（口径保持现状：deferredCheckpoint = savedValues?.model，
        // 不带 defaults 中间级——与 TTI/ITI 的条件+defaults 口径不同是有意为之，不许"顺手统一"）
        val support = source(supportPath)
        val deferredFields = listOf(
            "deferredCheckpoint", "deferredUnet", "deferredLoraName",
            "deferredHighnoiseUnet", "deferredLownoiseUnet", "deferredHighnoiseLora",
            "deferredLownoiseLora", "deferredVae", "deferredClip", "deferredClip1",
            "deferredClip2", "deferredClip3", "deferredClip4", "deferredTextEncoder",
            "deferredLatentUpscaleModel"
        )
        val missing = deferredFields.filterNot { support.contains("val $it: String?") }
        assertTrue("VideoGenerationState 缺少 deferred 字段：$missing", missing.isEmpty())
        assertTrue(
            "VideoGenerationState 缺少 workflowPlaceholders 字段",
            support.contains("val workflowPlaceholders: Set<String>")
        )
    }
}
