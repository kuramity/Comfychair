package sh.hnet.comfychair

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「接线还在不在」的源码级钉子。
 *
 * 为什么需要这种（确实不优雅的）测试：本轮风险最高的两处逻辑，**纯函数本身**都已被单测钉死，
 * 但"策略有没有被正确接到生产代码上"这一层，JVM 单测够不着 ——
 * `ConnectionManager` 要 Context/OkHttp，`ImageToImageViewModel` 要 Android 环境，
 * 两者都跑不进 JVM 测试。终审的反证实验因此证明：
 *
 * - 把 `fetchServerData` 里那行 `syncModelIntros()` 删掉（热更新彻底没接线）→ 全量 473 测试仍全绿；
 * - 把 ITI 修复流的 `isActiveMode = state.mode == ImageToImageMode.INPAINTING` 改成恒 `true`
 *   （编辑流会污染修复流记录）→ 同样全绿。
 *
 * 也就是说"最危险的两处"零覆盖。这里用**读源码文本**的方式把它们钉住：改动只要破坏了这两个
 * 表达式，测试立刻变红（反证见 `execution-report.md` 的 Fix Round 1 节）。
 * 断言前把空白折叠成单空格，所以换行与缩进调整不会误报。
 */
class FeatureWiringContractTest {

    private val connectionManagerPath =
        "src/main/java/sh/hnet/comfychair/connection/ConnectionManager.kt"
    private val modelListCatalogPath =
        "src/main/java/sh/hnet/comfychair/connection/ModelListCatalog.kt"
    private val imageToImageViewModelPath =
        "src/main/java/sh/hnet/comfychair/viewmodel/ImageToImageViewModel.kt"

    /** 读源码；单测工作目录是模块根（`app/`），与 `HiresPrebuiltContractTest` 读资产同一口径。 */
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
     * 取出一个函数的正文：从签名起，到下一个 4 空格缩进的成员声明之前（**原样保留空白**，
     * 由调用方决定是按"整行语句"还是按"折叠后的表达式"来断言）。
     */
    private fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("源码里找不到函数：$signature", start >= 0)
        val rest = source.substring(start + signature.length)
        val nextMember = Regex("""\n {4}(private |internal |public |protected )?(fun|val|var)""").find(rest)
        return if (nextMember != null) rest.substring(0, nextMember.range.first) else rest
    }

    /** 折叠空白：用于断言**跨行表达式**（如 `isActiveMode = ...`），换行/缩进调整不会误报。 */
    private fun folded(text: String): String = text.replace(Regex("""\s+"""), " ").trim()

    /**
     * 是否存在**恰好这一条语句**（忽略行首缩进）。
     *
     * 为什么不用 `contains`：注释里出现同名字样也会满足它 —— 本测试第一版就是这样漏过
     * 反证实验 B3 的（破坏点写成注释 `// ... syncModelIntros() 调用已删除`，断言仍然通过）。
     */
    private fun hasStatement(body: String, statement: String): Boolean =
        body.lines().any { it.trim() == statement }

    // ------------------------------------------------------------ ① 模型介绍热更新的接线

    @Test
    fun `刷新模型列表时必须触发介绍数据同步`() {
        // 需求 0 的唯一触发路径：连接成功与用户手点刷新都汇到 fetchServerData。
        // 删掉这一行 = 热更新形同不存在（用户新下载的模型永远看不到介绍），
        // 而既有的 ModelIntroFetchTest / ModelIntroSyncTest 全是纯逻辑测试，一条都不会红。
        // B4-W2 起 fetchServerData/applyObjectInfo/syncModelIntros 拆入 ModelListCatalog
        // （ConnectionManager 经转发 API 委托），钉子目标源码随拆解同步切换，意图不变。
        val sourceText = source(modelListCatalogPath)
        val body = bodyOf(sourceText, "fun fetchServerData(")
        assertTrue(
            "fetchServerData 里必须有一条 syncModelIntros 语句（终审反证 B3：删掉它，全量测试仍全绿；B4-W2 起带 client/context 参数）",
            hasStatement(body, "syncModelIntros(client, context)")
        )
        // 该调用必须排在模型列表装配之前：它要用「刚装好的本地表」做比对基准。
        // Q3-1（v4.16.5）演进：parse + 就绪信号 + extract 收进了 applyObjectInfo
        // 原子段（与磁盘预填链路共用一把锁，消除并发解析竞态），fetchServerData
        // 里改为接线该原子段——列表装配本身仍必须存在（钉在原子段内部）。
        // 旧断言字面（"val models = extractModelLists()" 在 fetchServerData 里）
        // 随实现演进同步更新，钉子意图不变：列表装配不许被删
        assertTrue(
            "fetchServerData 里必须接线 applyObjectInfo 原子段（Q3-1）",
            hasStatement(body, "val models = applyObjectInfo(objectInfo)")
        )
        val applyBody = bodyOf(sourceText, "private fun applyObjectInfo(")
        assertTrue(
            "applyObjectInfo 原子段内仍要做模型列表装配",
            hasStatement(applyBody, "return extractModelLists()")
        )
        assertTrue(
            "applyObjectInfo 原子段内仍要写注册表就绪信号",
            hasStatement(applyBody, "_nodeTypeRegistryReady.value = nodeTypeRegistry.isPopulated()")
        )
    }

    @Test
    fun `介绍同步必须走带异常兜底的编排对象`() {
        // 闸门 + 异常边界都在 ModelIntroSyncRunner 里（见 ModelIntroSyncRunnerTest）。
        // 若有人把接线改回"自己 tryEnter、自己在回调里 try/catch"，
        // 「发起阶段同步抛出」这条路就又没人兜底了：闸门卡死 + 异常冒泡进 /object_info 回调。
        // B4-W2：syncModelIntros 拆入 ModelListCatalog（带 client/context 参数），钉子随迁
        val sourceText = source(modelListCatalogPath)
        val body = folded(bodyOf(sourceText, "private fun syncModelIntros("))
        assertTrue(
            "syncModelIntros 必须把拉取与落盘交给 ModelIntroSyncRunner（异常兜底在那里）",
            body.contains("introSyncRunner.run(")
        )
        assertTrue("拉取仍要真的发出去", body.contains("fetchModelIntros"))
        assertTrue("拿到数据仍要落盘并重载", body.contains("ModelIntroSync.apply("))
        assertFalse(
            "不许在 syncModelIntros 里自己 tryEnter —— 闸门必须由 runner 统一收尾",
            body.contains("introSyncInFlight.tryEnter()")
        )
        assertTrue(
            "runner 必须复用手写的那个闸门实例",
            sourceText.contains("introSyncRunner = ModelIntroSyncRunner(") &&
                sourceText.contains("gate = introSyncInFlight")
        )
    }

    // ------------------------------------------------------------ ① 资产下发（B5）的接线

    @Test
    fun `资产下发同步必须接线在模型列表刷新之后`() {
        // B5 的唯一触发路径：与介绍同步同点触发（/object_info 回调里、syncModelIntros 之后）。
        // 删掉这一行 = 预置流 / 技能规则 / 规则表的覆盖层永远不下发（纯逻辑单测仍全绿，
        // 与介绍同步钉子同一教训：接线只能靠源码钉）。
        val sourceText = source(modelListCatalogPath)
        val body = bodyOf(sourceText, "fun fetchServerData(")
        assertTrue(
            "fetchServerData 里必须有一条 syncDistributedAssets 触发语句（B5：syncModelIntros 之后）",
            hasStatement(body, "context?.let { syncDistributedAssets(client, it) }")
        )
        val syncBody = folded(bodyOf(sourceText, "private fun syncDistributedAssets("))
        assertTrue(
            "资产同步必须交给 AssetDistributor（闸门与异常兜底在里面）",
            syncBody.contains("assetDistributor.sync(")
        )
        assertTrue(
            "拉取必须走同源 /view（fetchRawBytes），subfolder 用共享常量（不引入任意 URL 下载面）",
            syncBody.contains("fetchRawBytes") && syncBody.contains("AssetDistributor.REMOTE_SUBFOLDER")
        )
        assertTrue(
            "预置流落盘后必须触发工作流重装配（否则下发流在重启前不可见）",
            syncBody.contains("WorkflowManager.notifyDistributedAssetsChanged()")
        )
        assertTrue(
            "规则表落盘后必须触发重载（否则新规则要重启才生效）",
            syncBody.contains("RuleTable.load(")
        )
    }

    // ------------------------------------------------------------ ② ITI 双流隔离的接线

    @Test
    fun `图生图的修复流保存必须以修复模式作为激活判据`() {
        // 图生图只有一个正面输入框、却有两套工作流记录（修复流 / 编辑流），
        // savePreferences 无条件调用两套保存 —— 判据一旦恒 true，编辑流打的字会被抄进修复流记录。
        // 恒 true 这个破坏点在终审反证 B4 里"全量 473 全绿"，所以这里必须有一条钉子。
        val body = folded(bodyOf(source(imageToImageViewModelPath), "private fun saveWorkflowValues("))
        assertTrue(
            "修复流保存必须写 isActiveMode = state.mode == ImageToImageMode.INPAINTING," +
                "（终审反证 B4：改成恒 true，全量测试仍全绿）",
            body.contains("isActiveMode = state.mode == ImageToImageMode.INPAINTING,")
        )
        assertFalse(
            "修复流的保存不得用编辑模式作为激活判据",
            body.contains("isActiveMode = state.mode == ImageToImageMode.EDITING,")
        )
    }

    @Test
    fun `图生图的编辑流保存必须以编辑模式作为激活判据`() {
        val body = folded(bodyOf(source(imageToImageViewModelPath), "private fun saveEditingWorkflowValues("))
        assertTrue(
            "编辑流保存必须写 isActiveMode = state.mode == ImageToImageMode.EDITING,",
            body.contains("isActiveMode = state.mode == ImageToImageMode.EDITING,")
        )
        assertFalse(
            "编辑流的保存不得用修复模式作为激活判据（两条流互为镜像，写反就是互相污染）",
            body.contains("isActiveMode = state.mode == ImageToImageMode.INPAINTING,")
        )
    }
}
