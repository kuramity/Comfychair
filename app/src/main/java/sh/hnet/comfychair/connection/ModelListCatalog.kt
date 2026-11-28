package sh.hnet.comfychair.connection

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.distribute.AssetDistributor
import sh.hnet.comfychair.distribute.AssetKind
import sh.hnet.comfychair.model.LoraSelection
import sh.hnet.comfychair.model.ModelIntroSync
import sh.hnet.comfychair.model.ModelIntroTable
import sh.hnet.comfychair.storage.ObjectInfoCache
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.SingleFlight
import sh.hnet.comfychair.workflow.NodeTypeRegistry
import sh.hnet.comfychair.workflow.TemplateKeyRegistry
import sh.hnet.comfychair.workflow.rules.RuleTable
import java.io.File

/**
 * 模型列表子系统：节点类型注册表 + 模型列表缓存 + `/object_info` 网络装配（B4-W2）。
 *
 * 从 [ConnectionManager]（拆出前 2830 行）整体迁入三个取证批次：
 * - **E 块**（原 :259-401）：状态与闸门 —— 注册表 / 模型缓存 / 就绪信号 / SingleFlight
 *   闸门 / 会话代次 / 看门狗 / 介绍同步编排；
 * - **T 块**（原 :1882-2217）：网络装配 —— [fetchServerData] 及其私有辅助（磁盘预填、
 *   介绍同步、分类失败日志）与「必检」入口 [ensureModelListLoadedAfterResume]；
 * - **V 块**（原 :2657-2798）：提取 —— [extractModelLists] / [markCategoriesFailed]，
 *   以及顶层纯函数 [selectNodeTypedOptions]（同包单测 SamplerCandidateSelectionTest
 *   直接调用，搬到本文件顶层后零改动）。
 *
 * 委托关系（B4-W2 约定）：
 * - [ConnectionManager] 保留对外 API（nodeTypeRegistry / modelCache / nodeTypeRegistryReady /
 *   refreshServerData / ensureModelListLoadedAfterResume / retryCoreCategory），签名不变、
 *   转发到本类 —— 全仓调用方与 JVM 单测零改动；
 * - 本类**不持有** [ConnectionManager] 引用（避免反向纠缠）：会话侧资源（client /
 *   applicationContext / serverId）由 [ConnectionManager] 在调用点**显式传参**；连接收敛
 *   判定（connectionSettled）同样由转发入口算好传入；
 * - 会话生命周期接线（J↔E 耦合点）：[ConnectionManager.connect] / disconnect 调
 *   [bumpGeneration] 作废旧会话在飞的装配结果；invalidateAll 调 [clear]；
 *   loadFromOfflineCache 调 [applyOfflineObjectInfo]。
 *
 * 行为等价说明：除日志 TAG 从 "Connection" 改为本类独立 TAG（观测性差异，行为不变）外，
 * 本类成员为纯搬运；唯一语义增强是 [applyOfflineObjectInfo]（离线加载）现在与网络回调共用
 * [registryApplyLock] 原子段 —— 拆出前离线路径不加锁，单线程语义不变；并发对手出现时
 * 加锁保护正是 [applyObjectInfo]（Q3-1）的设计本意。
 */
class ModelListCatalog {

    companion object {
        private const val TAG = "ModelListCatalog"

        /** 看门狗：`isLoading` 的最长驻留时间。超过即强制三类进入 FAILED 终态，杜绝「正在加载」不收敛。 */
        internal const val MODEL_LIST_WATCHDOG_MS = 60_000L
    }

    // ── 状态与闸门（E 块，B4-W2 自 ConnectionManager :259-401 原样搬入）────────────

    // Shared node type registry (populated from /object_info)
    // 经 ConnectionManager.nodeTypeRegistry 转发暴露（调用方零改动）
    val nodeTypeRegistry = NodeTypeRegistry()

    // Q3-1（v4.16.5）："parse + ready 信号 + extract"原子段的锁（见 [applyObjectInfo]）。
    // 磁盘预填（IO 协程）与网络回调（OkHttp 线程）两条链路共用
    private val registryApplyLock = Any()

    // Shared model cache (populated from /object_info)
    private val _modelCache = MutableStateFlow(ModelCache())
    val modelCache: StateFlow<ModelCache> = _modelCache.asStateFlow()

    // 节点类型注册表是否已就绪（/object_info 已解析完成）。
    // 通用节点参数引擎的类型推导依赖它：冷启动时 /object_info 是异步拉的（约数秒），
    // 首次解析动态参数时注册表还是空的 → 控件全部退化成文本框；
    // 订阅这个信号可以在就绪后**重算**当前工作流的动态参数，无需用户切走再切回。
    private val _nodeTypeRegistryReady = MutableStateFlow(false)
    val nodeTypeRegistryReady: StateFlow<Boolean> = _nodeTypeRegistryReady.asStateFlow()

    /**
     * 模型介绍数据同步的并发闸门。
     *
     * 连接成功与用户手点刷新都会走到 [fetchServerData]，两条链路可能在几毫秒内先后触发；
     * 拉取本身幂等，重复做一遍没有任何收益，所以用跳过式闸门保证同一时刻至多一个在飞
     * （顺带也就实现了"防连点"）。
     */
    private val introSyncInFlight = SingleFlight()

    /**
     * /object_info 拉取的跳过式闸门（Q3-2，v4.16.5）。
     *
     * 连接成功、onResume 补拉、设置页手点刷新三条链路都汇到 [fetchServerData]，
     * 8MB 的请求同一时刻至多一个在飞。成功、失败、回调内异常都会释放（回调
     * finally 里 exit），失败后重试不会被拦。
     */
    private val objectInfoFetchGate = SingleFlight()

    /**
     * 模型列表装配的**连接会话代次**（G7 指令 3 / G8 指令 2，v4.16.10 起）。
     *
     * 每次连接建立 / 断开都自增（[ConnectionManager] 的 connect / disconnect 调
     * [bumpGeneration]）。网络回调与磁盘预填协程都捕获发起时的代次，
     * 回来后若代次已变（用户退出重连 / 切了服务器 / 看门狗已判失败），
     * **直接丢弃、不写 [\_modelCache]**：
     * - 防止「过渡态 / 上一台服务器」的迟到响应把新连接刚铺好的列表盖回去（再次被吞）；
     * - 防止磁盘预填的 IO 协程把已经被网络结果收敛掉的 isLoading 又顶回 true
     *   （这正是 E7「UNET 长期正在加载不收敛」的一条真实路径：网络先回、置 isLoading=false，
     *   随后读盘协程 `_modelCache.value = models.copy(isLoaded = true, isLoading = true)`
     *   把加载态重新点亮，而这条链路上再也没有第二个回调来熄灭它）。
     */
    @Volatile
    private var modelListGeneration = 0L

    /**
     * 看门狗协程句柄（同一时刻至多一个；每次发起拉取重建，回调/代次变化时取消）。
     *
     * 跨线程读写：`armModelListWatchdog` 由发起线程调用、`cancelModelListWatchdog` 由
     * OkHttp 回调线程调用，不加 @Volatile 时后者可能读到旧句柄、取消不掉已收敛的看门狗。
     */
    @Volatile
    private var modelListWatchdog: Job? = null

    /**
     * /object_info 闸门的**请求序号**（每次成功进闸自增）。
     *
     * 与 [modelListGeneration]（连接会话代次）不同：它按"进闸请求"计数，用来识别
     * 看门狗提前放行后谁是当前闸门的持有者。看门狗 60 秒触发 [SingleFlight.exit] 时
     * 原回调可能仍在飞；此后新请求 [fetchServerData] 成功 tryEnter，旧回调迟到时
     * 若在 finally 里无条件 exit，会把新请求持有的闸门错放出去（第三条链路即可真正
     * 并发在飞，两个回调交错写 [\_modelCache]）。自增只发生在闸门持有者内
     * （tryEnter 互斥），@Volatile 保证 OkHttp 回调线程读到最新值。
     */
    @Volatile
    private var objectInfoFetchSerial = 0L

    /**
     * 介绍数据热更新的执行编排（闸门 + 异常兜底 + 收尾都在里面）。
     *
     * 为什么不直接在 [syncModelIntros] 里写 try/catch：`fetchModelIntros` 是异步的，
     * 异常边界必须同时覆盖"回调内部"与"发起阶段同步抛出"两条路径，而后者一旦漏掉
     * 就会**卡死闸门 + 中断 `/object_info` 回调的模型列表装配**。这段逻辑抽到
     * [ModelIntroSyncRunner] 后可以在 JVM 单测里逐条钉住（见 `ModelIntroSyncRunnerTest`）。
     */
    private val introSyncRunner = ModelIntroSyncRunner(
        gate = introSyncInFlight,
        logInfo = { DebugLogger.i(TAG, it) },
        logWarn = { DebugLogger.w(TAG, it) },
    )

    /**
     * 资产下发（B5）的执行编排（闸门 + 异常兜底都在 [AssetDistributor] 里）。
     * 与 [introSyncRunner] 同款实例化模式：日志注入 + 独立闸门实例。
     */
    private val assetDistributor = AssetDistributor(
        gate = SingleFlight(),
        logInfo = { DebugLogger.i(TAG, it) },
        logWarn = { DebugLogger.w(TAG, it) },
    )

    /**
     * 看门狗与磁盘预填协程的作用域（B4-W2：与 ConnectionManager 自身的 scope 同配置，
     * 主线程 + SupervisorJob；拆出前看门狗/预填借用 ConnectionManager 的 scope）。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── 会话生命周期接线（J↔E 耦合点与离线发布，ConnectionManager 调用）────────────

    /**
     * 自增模型列表会话代次并取消看门狗。
     *
     * 在 [ConnectionManager.connect] 建立新会话与 [ConnectionManager.disconnect] 断开时
     * 调用（B4-W2 前为 ConnectionManager 私有的 bumpModelListGeneration）：此后任何在飞的
     * `/object_info` 回调与磁盘预填协程都会因代次不符被丢弃。
     */
    fun bumpGeneration() {
        modelListGeneration += 1
        modelListWatchdog?.cancel()
        modelListWatchdog = null
    }

    /**
     * 清空模型列表与节点注册表（[ConnectionManager.invalidateAll] 的模型列表部分）。
     *
     * 拆出前 invalidateAll 直接写这三个成员（缓存复位 / 注册表 clear / 就绪信号置 false），
     * 现在整体委托过来，语义不变。
     */
    fun clear() {
        _modelCache.value = ModelCache()
        nodeTypeRegistry.clear()
        _nodeTypeRegistryReady.value = false
    }

    /**
     * 把离线缓存的 /object_info 应用到注册表并发布模型列表
     * （[ConnectionManager.loadFromOfflineCache] 的模型列表部分）。
     *
     * parse → 就绪信号 → 提取走与网络回调同一把锁的原子段（见 [applyObjectInfo]），
     * 随后按离线语义发布：isLoaded=true、isLoading=false（拆出前这四行在
     * loadFromOfflineCache 里手写、不走锁；单线程语义不变，见类注释的等价性说明）。
     *
     * @return 装配好的模型列表（调用方用于日志与后续初始化）
     */
    fun applyOfflineObjectInfo(objectInfo: JSONObject): ModelCache {
        val models = applyObjectInfo(objectInfo)
        _modelCache.value = models.copy(isLoaded = true, isLoading = false)
        return models
    }

    // ── 网络装配（T 块，B4-W2 自 ConnectionManager :1882-2217 原样搬入）────────────

    /**
     * Fetch server data (node types and model lists) from /object_info.
     * Called automatically on connection and can be triggered manually via
     * [ConnectionManager.refreshServerData]（转发入口）。
     *
     * Q3-2（v4.16.5）：加 SingleFlight 跳过式守卫——连接成功、onResume 补拉、
     * 设置页手点刷新三条链路可能在几毫秒内先后触发，8MB 的 /object_info 同一
     * 时刻只允许一个在飞，后到的直接跳过（不排队）。失败时回调里 [SingleFlight.exit]
     * 释放闸门，下一次补拉/手点可以立即重试，不会被永久拦住。
     *
     * B4-W2：`client` / `context` / `serverId` 由 [ConnectionManager] 在调用点显式传入
     * （本类不持 ConnectionManager 引用）。三者是发起时刻的快照：回调真正读它们之前
     * 已有代次守卫拦下跨会话迟到响应，且 applicationContext 全进程唯一，故快照与
     * 拆出前的实时读取行为等价。
     */
    fun fetchServerData(
        client: ComfyUIClient?,
        context: Context?,
        serverId: String?
    ) {
        if (client == null) {
            DebugLogger.w(TAG, "fetchServerData: Client not available")
            return
        }
        // 已有一个 /object_info 在飞：直接跳过（幂等请求，重复拉没有收益）
        if (!objectInfoFetchGate.tryEnter()) {
            DebugLogger.i(TAG, "fetchServerData: 已有请求在飞，跳过本次触发")
            return
        }

        DebugLogger.i(TAG, "Fetching server data from /object_info")
        // 本次进闸的请求序号：自增只发生在闸门持有者内（tryEnter 互斥，天然串行）。
        // 回调 finally 里用它判断"自己是否仍是闸门持有者"——看门狗会提前 exit 放行
        // 新请求，迟到回调不能再无条件 exit（否则错放新请求的闸门）。
        val serialAtStart = ++objectInfoFetchSerial
        // 本次拉取的会话代次：回调与磁盘预填都按它判新旧（G7 指令 3「不得在过渡态下
        // 发起而再次被吞」的落点 —— 过渡态/旧会话的迟到响应一律丢弃）
        val generationAtStart = modelListGeneration
        // G8 指令 1：三类分别置 PENDING，让「加载态仅在全部类型进入终态后清除」有据可依；
        // 这里只置状态、绝不清空列表（G7 指令 1「已有数据不得被清空」）
        _modelCache.value = _modelCache.value.copy(
            isLoading = true,
            lastError = null,
            unetState = CategoryLoadState.PENDING,
            vaeState = CategoryLoadState.PENDING,
            clipState = CategoryLoadState.PENDING,
            unetFailure = CategoryFailureReason.NONE,
            vaeFailure = CategoryFailureReason.NONE,
            clipFailure = CategoryFailureReason.NONE,
            generation = generationAtStart
        )
        // G8 指令 2 的兜底：回调若因任何原因没回来（隧道挂起、进程被冻结后网络悬空），
        // 看门狗到点强制三类落 FAILED，绝不让「正在加载模型列表…」长期驻留
        armModelListWatchdog(generationAtStart)

        // 【秒出】先用磁盘缓存把列表铺上，网络最新版回来后再静默替换
        primeModelCacheFromDisk(generationAtStart, context, serverId)

        client.fetchFullObjectInfo { objectInfo ->
            try {
                // 代次已变：这次响应属于上一个连接会话（退出重连 / 切服务器期间到达），
                // 丢掉即可 —— 新会话自己会发起拉取，绝不能用旧列表把新界面盖回去
                if (generationAtStart != modelListGeneration) {
                    DebugLogger.i(
                        TAG,
                        "丢弃迟到的 /object_info 响应：会话代次 $generationAtStart 已作废" +
                                "（当前 $modelListGeneration）"
                    )
                    // 只跳过响应处理；finally 仍会释放闸门（本回调确实已结束）
                    return@fetchFullObjectInfo
                }
                if (objectInfo != null) {
                    // Q3-1：解析 + 就绪信号 + 列表提取收成原子段（见 [applyObjectInfo]）
                    val models = applyObjectInfo(objectInfo)

                    // 模型介绍表：与模型列表同批加载（刷新模型列表也就等于刷新介绍）。
                    // 表本身随 APK 内置、之后可被服务器上的版本覆盖，加载失败只是没有介绍，
                    // 不影响模型选择。
                    context?.let { ModelIntroTable.ensureLoaded(it) }

                    // 模型介绍**热更新**：把服务器上的介绍数据取回来（新下载的模型不必等发版）。
                    // 必须排在 ensureLoaded 之后 —— 同步要用刚装好的本地表做比对基准；
                    // 放在回调里而不是 fetchServerData 开头，是为了不跟 8MB 的 /object_info 抢带宽，
                    // 也避免列表列表拉取失败时白拉一次介绍。
                    syncModelIntros(client, context)

                    // 资产下发（B5）：预置工作流 / 技能规则 / 占位符规则表的覆盖层同步，
                    // 与介绍同步同点触发、同为同源 /view 拉取；闸门防重入与异常兜底全在
                    // AssetDistributor 里，失败只记日志，绝不影响模型列表装配。
                    context?.let { syncDistributedAssets(client, it) }

                    // Cache object_info to disk for offline mode
                    if (context != null && serverId != null) {
                        ObjectInfoCache.saveObjectInfo(context, serverId, objectInfo)
                    }

                    cancelModelListWatchdog()
                    // 三类终态已在 extractModelLists 里逐类算好（G8 指令 1），这里只收整体标志：
                    // 加载态仅在全部类型进入终态后清除（G8 指令 2）
                    _modelCache.value = models.copy(
                        isLoaded = true,
                        isLoading = false,
                        generation = generationAtStart
                    )
                    DebugLogger.i(TAG, "Server data loaded: ${models.checkpoints.size} checkpoints, " +
                            "${models.unets.size} unets, ${models.vaes.size} vaes, " +
                            "${models.clips.size} clips, ${models.loras.size} loras")
                    // 同时打到 logcat：DebugLogger 只存在内存里，现场排查「模型下拉为空」时
                    // 需要能直接从 adb logcat 看到节点定义与各列表的规模
                    android.util.Log.i(
                        "ComfyChairModels",
                        "节点定义已加载: nodes=${nodeTypeRegistry.getAllNodeTypes().size}, " +
                                "ckpt=${models.checkpoints.size}, unet=${models.unets.size}, " +
                                "vae=${models.vaes.size}, clip=${models.clips.size}, lora=${models.loras.size}"
                    )
                    // G8 指令 1/3：单类提取失败必须可见、带原因 —— 整体成功不等于三类都成功
                    logCategoryFailures(models)
                } else {
                    DebugLogger.w(TAG, "Failed to fetch server data")
                    cancelModelListWatchdog()
                    // 失败不清已加载列表（可能还有磁盘预填的旧数据可显示），只置错误
                    // 供 UI 区分「失败（可重试）」与「空（服务器真没模型）」（Q3-3）；
                    // 三类一并落 FAILED(请求失败)，不再让某一类独自停在 PENDING 变成「正在加载」
                    markCategoriesFailed("Failed to fetch server data")
                }
            } finally {
                // 成功、失败、回调内异常三条路径都要释放闸门——忘记复位会让之后所有
                // 补拉/手点被永久跳过（见 [SingleFlight] 类注释）。但仅当自己仍是最后一个
                // 进闸的请求时才释放：看门狗可能已提前 exit 并放行了新请求（序号已变），
                // 迟到回调此时无条件 exit 会把新请求持有的闸门错放出去，第三条链路
                // 就能真正并发在飞。序号相等时本回调就是持有者（exit 幂等，重复释放无害）。
                if (serialAtStart == objectInfoFetchSerial) {
                    objectInfoFetchGate.exit()
                } else {
                    DebugLogger.i(
                        TAG,
                        "跳过迟到回调的闸门释放：请求序号 $serialAtStart 已过期（当前 $objectInfoFetchSerial），闸门归新请求"
                    )
                }
            }
        }
    }

    /**
     * G7 指令 1 的「必检」入口：后台返回 / 重连收敛后，按纯函数判定要不要重拉模型列表。
     *
     * B4-W2：调用点仍是两个（[ConnectionManager.ensureModelListLoadedAfterResume] 转发，
     * 上游为 MainContainerActivity.onResume 与 WebSocket onOpen），会话侧判定
     * `connectionSettled` 由转发入口算好传入（client 非空且连接状态为 Connected）。
     * 判据全部交给 [ModelCache.reloadDecision]（纯函数、有单测）：
     * - 已有数据且三类健康 → SKIP_HEALTHY，**绝不清空已有列表**；
     * - 三类未全终态（E7 僵尸加载）或任一 FAILED 或核心列表全空 → RELOAD 一次。
     */
    fun ensureModelListLoadedAfterResume(
        client: ComfyUIClient?,
        connectionSettled: Boolean,
        context: Context?,
        serverId: String?
    ) {
        // 是否真有 /object_info 在飞：闸门占用即视为在飞
        val inFlight = objectInfoFetchGate.isInFlight

        val cache = _modelCache.value
        when (cache.reloadDecision(connectionSettled = connectionSettled, inFlight = inFlight)) {
            ModelListReloadDecision.RELOAD -> {
                DebugLogger.i(
                    TAG,
                    "模型列表必检：触发重拉（unet=${cache.unetState}, vae=${cache.vaeState}, " +
                            "clip=${cache.clipState}, isLoaded=${cache.isLoaded}, 核心有数据=${cache.hasAnyCoreModel()}）"
                )
                fetchServerData(client, context, serverId)
            }
            ModelListReloadDecision.SKIP_TRANSIENT_CONNECTION ->
                DebugLogger.d(TAG, "模型列表必检：连接未收敛，跳过（等 onOpen 再检）")
            ModelListReloadDecision.SKIP_IN_FLIGHT ->
                DebugLogger.d(TAG, "模型列表必检：已有 /object_info 在飞，跳过叠加")
            ModelListReloadDecision.SKIP_HEALTHY ->
                DebugLogger.d(TAG, "模型列表必检：三类均已终态且有数据，保留现状不重拉")
        }
    }

    /**
     * 启动模型列表加载看门狗（G8 指令 2「正在加载不得长期驻留」的最后防线）。
     *
     * 回调正常返回时会先于看门狗把三类置终态；看门狗只在「代次未变但 [\_modelCache]
     * 仍处于 isLoading」时触发，把三类统一落到 `FAILED(REQUEST_FAILED)`，
     * **保留已有列表**（不清数据），让界面从「正在加载」变成「加载失败，可重试」。
     */
    private fun armModelListWatchdog(generationAtStart: Long) {
        modelListWatchdog?.cancel()
        modelListWatchdog = scope.launch {
            delay(MODEL_LIST_WATCHDOG_MS)
            // 代次变了说明已有新的连接/拉取接管，本看门狗作废（新会话会各自起看门狗）
            if (generationAtStart != modelListGeneration) return@launch
            if (!_modelCache.value.isLoading) return@launch
            DebugLogger.w(
                TAG,
                "模型列表加载看门狗超时（${MODEL_LIST_WATCHDOG_MS}ms 未收敛），" +
                        "强制三类落 FAILED(请求失败)，保留已有列表"
            )
            // 失败态可见 + 三类终态 + 清除 isLoading（禁静默留空 / 禁「正在加载」驻留），不清列表
            _modelCache.value = _modelCache.value.copy(
                isLoading = false,
                lastError = "模型列表加载超时",
                unetState = CategoryLoadState.FAILED,
                vaeState = CategoryLoadState.FAILED,
                clipState = CategoryLoadState.FAILED,
                unetFailure = CategoryFailureReason.REQUEST_FAILED,
                vaeFailure = CategoryFailureReason.REQUEST_FAILED,
                clipFailure = CategoryFailureReason.REQUEST_FAILED
            )
            // 看门狗触发即视为本次拉取结束，释放闸门，让手点/补拉不被永久拦
            objectInfoFetchGate.exit()
        }
    }

    /** 取消模型列表看门狗（回调已自行收敛三类终态时调用）。 */
    private fun cancelModelListWatchdog() {
        modelListWatchdog?.cancel()
        modelListWatchdog = null
    }

    /**
     * 把一份 /object_info JSON **原子地**应用到节点注册表并装配模型列表（Q3-1，v4.16.5）。
     *
     * 磁盘预填（IO 协程）与网络回调（OkHttp 线程）两条链路都会走这里。解析、
     * 就绪信号与列表提取在 [registryApplyLock] 内完成：两条链路先后进入时完全
     * 串行，不会出现"A 线程 parse 完、B 线程又 parse 覆盖之后 A 才 extract"的
     * 交错（那种交错会把 B 的数据装配进 A 的结果里，产生与自身代次不符的列表）。
     * 注册表本身的并发安全由 NodeTypeRegistry 的局部 map + 原子替换保证（读方
     * 拿到的永远是完整代次的快照）；这里再串行化两条调用链，保证"谁后完成谁生效"
     * 且 parse 与 extract 严格同代。
     *
     * @return 装配好的模型列表（调用方自行写 StateFlow，保持 loading/loaded 语义）
     */
    private fun applyObjectInfo(objectInfo: JSONObject): ModelCache {
        synchronized(registryApplyLock) {
            nodeTypeRegistry.parseObjectInfo(objectInfo)
            // 注册表就绪信号（动态参数类型推导的可用前提），订阅者据此重算。
            // 放锁内：防止另一条链路 parse 后、本条信号尚未写出时被读回旧值
            _nodeTypeRegistryReady.value = nodeTypeRegistry.isPopulated()
            return extractModelLists()
        }
    }

    /**
     * 把服务器上的模型介绍数据同步到本地（热更新）。
     *
     * 触发点只有一个：[fetchServerData] 的 `/object_info` 回调里（连接成功、用户手点刷新都汇到这里），
     * 满足"刷新模型列表时顺带刷新介绍"这条需求，又不必额外引入后台定时任务。
     *
     * 三条不打扰主流程的约定（前两条由 [ModelIntroSyncRunner] 兑现，见其类注释）：
     * 1. **跳过式并发保护**：同一时刻至多一个在飞，重复触发直接返回；
     * 2. **失败只记日志**：拉不到、装不上、甚至发起阶段抛出，都继续用本地那份（缓存 / 内置），
     *    绝不抛出、绝不弹错误，也绝不影响调用点后续的模型列表装配；
     * 3. **在这里解析并落盘是安全的**：回调运行在 OkHttp 的调度线程，不是主线程
     *    （`ModelIntroSync.apply` 要解析 250KB JSON 并写盘，放主线程会卡顿）。
     */
    private fun syncModelIntros(client: ComfyUIClient?, context: Context?) {
        if (context == null) return
        if (client == null) return

        // 闸门、异常兜底与收尾全在 runner 里（见 [ModelIntroSyncRunner] 的类注释）：
        // 无论拉取失败、数据不可用，还是发起阶段/回调内部抛出，都只记日志、绝不冒泡 ——
        // 本函数的调用点在 `/object_info` 回调中，那里紧接着还要装配模型列表。
        introSyncRunner.run(
            fetch = { callback -> client.fetchModelIntros(callback) },
            apply = { json ->
                val result = ModelIntroSync.apply(json, ModelIntroTable.cacheFile(context))
                DebugLogger.i(TAG, "介绍数据同步结果：$result")
            },
        )
    }

    /**
     * 资产下发同步（B5）：把服务器上的 asset_manifest.json 及其列出的资产拉回本地覆盖层。
     *
     * 排在 [syncModelIntros] 之后触发——同为同源 /view 小文件拉取，不抢 /object_info 带宽。
     * 落盘副作用在 [AssetDistributor] 的完成回调里接线：预置流 → WorkflowManager 重装配
     * （bump workflowsVersion，订阅 VM 自动刷新）；规则表 → RuleTable 重载；SKILL_MD 无需
     * 主动副作用（下次读时生效）。回调线程是 OkHttp 线程，重装配已由
     * WorkflowManager.notifyDistributedAssetsChanged 的 @Synchronized 保证写-写互斥。
     */
    private fun syncDistributedAssets(client: ComfyUIClient, context: Context) {
        assetDistributor.sync(
            overlayRoot = File(context.filesDir, AssetDistributor.OVERLAY_DIR),
            fetch = { filename, callback ->
                client.fetchRawBytes(filename, AssetDistributor.REMOTE_SUBFOLDER, "output") { bytes, _ ->
                    callback(bytes)
                }
            },
            onComplete = { result ->
                if (result.outcome != AssetDistributor.Outcome.COMPLETED) return@sync
                if (AssetKind.PREBUILT_WORKFLOW in result.writtenKinds) {
                    WorkflowManager.notifyDistributedAssetsChanged()
                }
                if (AssetKind.RULE_TABLE in result.writtenKinds) {
                    RuleTable.load(context.assets, RuleTable.overlayFile(context))
                }
            },
        )
    }

    /**
     * 用磁盘上的 `/object_info` 缓存**预填**模型列表（不联网、不阻塞）。
     *
     * 为什么需要它：这个接口实测 **8.1MB** —— 局域网上要十几秒，经 Cloudflare 隧道在移动网络
     * 下可能半分钟。这段时间里模型下拉是空的、LoRA 只剩「不使用」、采样器/调度器退化成纯文本
     * 输入框，用户会以为功能坏了。缓存里就是上次成功拉取的同一份数据，先用它把界面点亮。
     *
     * 只填一次（当前已有数据就跳过，也防旧缓存覆盖刚回来的新数据）；`isLoading` 保持 true，
     * 界面据此知道后台仍在刷新。
     */
    private fun primeModelCacheFromDisk(
        generation: Long,
        context: Context?,
        serverId: String?
    ) {
        if (_modelCache.value.isLoaded) return
        if (context == null) return
        if (serverId == null) return
        if (!ObjectInfoCache.hasCache(context, serverId)) {
            DebugLogger.i(TAG, "无缓存可预填，等待网络返回")
            return
        }
        scope.launch(Dispatchers.IO) {
            val cached = ObjectInfoCache.loadObjectInfo(context, serverId)
            if (cached == null) {
                DebugLogger.w(TAG, "缓存读取失败，等待网络返回")
                return@launch
            }
            // 读盘期间网络可能已经回来了 —— 别用旧缓存盖掉新数据
            if (_modelCache.value.isLoaded) return@launch
            // 代次已变（用户在这期间退出重连 / 切了服务器）：这份缓存属于旧会话，
            // 铺上去会盖掉新会话的结果，直接丢弃
            if (generation != modelListGeneration) {
                DebugLogger.i(TAG, "磁盘预填作废：会话代次 $generation 已不再是当前代次")
                return@launch
            }

            // Q3-1：parse + 就绪信号 + 提取走与网络回调同一把锁的原子段，
            // 两条链路不再交错清空/覆盖同一份节点表
            val models = applyObjectInfo(cached)
            ModelIntroTable.ensureLoaded(context)
            // 预填后的加载标志（G8 指令 2）：
            // 三类终态由 applyObjectInfo → extractModelLists 逐类算好（磁盘那份同样是终态，
            // 因为它是上次成功拉取的完整 /object_info），因此预填**不**把整体 isLoading 强设为 true。
            //
            // 旧实现无条件 `copy(isLoaded = true, isLoading = true)`，含义是「后台仍在拉最新」；
            // 保留这个含义的唯一正确依据是**闸门是否还占着**（真有请求在飞）：
            // - 在飞 → isLoading=true，界面继续显示加载语义，网络版回来后静默替换；
            // - 已不占（网络回调先跑完并释放了闸门）→ 必须写 false。旧写法在这种交错下会把
            //   已经收敛的加载态重新点亮，而这条链路上再没有第二个回调来熄灭它 ——
            //   这正是 E7「UNET 长期显示正在加载模型列表…」不收敛的一条真实成因。
            _modelCache.value = models.copy(
                isLoaded = true,
                isLoading = objectInfoFetchGate.isInFlight,
                generation = generation
            )
            android.util.Log.i(
                "ComfyChairModels",
                "已用磁盘缓存预填模型列表（后台仍在拉最新）: ckpt=${models.checkpoints.size}, " +
                        "unet=${models.unets.size}, vae=${models.vaes.size}, " +
                        "clip=${models.clips.size}, lora=${models.loras.size}"
            )
        }
    }

    /**
     * 把「某一类模型提取失败」写进日志（G8 指令 3：UNET 提取失败需带原因）。
     *
     * 现场排查全靠这几行 logcat：整体 isLoaded=true 时，界面上只有那一类是空的，
     * 没有日志根本分不清是节点没装、字段改名，还是服务器真没放模型。
     */
    private fun logCategoryFailures(models: ModelCache) {
        CoreCategory.entries.forEach { category ->
            val state = models.stateOf(category)
            if (state != CategoryLoadState.FAILED) return@forEach
            android.util.Log.w(
                "ComfyChairModels",
                "模型提取失败: 类别=${category.name}, 原因=${models.failureOf(category).name}, " +
                        "候选数=${when (category) {
                            CoreCategory.UNET -> models.unets.size
                            CoreCategory.VAE -> models.vaes.size
                            CoreCategory.CLIP -> models.clips.size
                        }}"
            )
        }
    }

    // ── 提取（V 块，B4-W2 自 ConnectionManager :2657-2798 原样搬入）────────────────

    /**
     * Extract model lists from NodeTypeRegistry by field name.
     * Automatically discovers models from any loader node (standard, GGUF, future plugins).
     *
     * Uses TemplateKeyRegistry to translate placeholder names to actual ComfyUI field names.
     * For example, "text_encoder_name" placeholder maps to "text_encoder" field in LTXAVTextEncoderLoader.
     */
    private fun extractModelLists(): ModelCache {
        val base = ModelCache(
            checkpoints = nodeTypeRegistry.getOptionsForField(
                TemplateKeyRegistry.getJsonKeyForPlaceholder("ckpt_name")
            ),
            unets = nodeTypeRegistry.getOptionsForField(
                TemplateKeyRegistry.getJsonKeyForPlaceholder("unet_name")
            ),
            vaes = nodeTypeRegistry.getOptionsForField(
                TemplateKeyRegistry.getJsonKeyForPlaceholder("vae_name")
            ),
            clips = nodeTypeRegistry.getOptionsForFieldPrefix("clip_name"),
            // 这里取的是**所有**节点 lora_name 选项的并集，其中 CR Load LoRA /
            // Lora Loader / Efficient Loader / easy *_Loader 等节点都带一个字面量 "None"
            // 表示"不加 LoRA"。它并进 LoRA 下拉后会被用户当成一个真实模型选中，
            // 提交给 LoraLoaderModelOnly 时它并不在服务器的 66 项里，直接 400：
            //   Value not in list: lora_name: 'None' not in (list of length 66)
            // 过滤器里的这类"未选择"取值一律剔除（真正的 LoRA 文件名带扩展名，不受影响）。
            loras = nodeTypeRegistry.getOptionsForField(
                TemplateKeyRegistry.getJsonKeyForPlaceholder("lora_name")
            ).filterNot { LoraSelection.normalizeName(it).isEmpty() },
            upscaleMethods = nodeTypeRegistry.getOptionsForField(
                TemplateKeyRegistry.getJsonKeyForPlaceholder("upscale_method")
            ),
            textEncoders = nodeTypeRegistry.getOptionsForField(
                TemplateKeyRegistry.getJsonKeyForPlaceholder("text_encoder_name")
            ),
            // Use node-specific extraction for latent upscale models because "model_name"
            // is a generic field used by multiple unrelated nodes (UpscaleModelLoader, KlingOmniPro*, etc.)
            latentUpscaleModels = nodeTypeRegistry.getOptionsForNodeInput(
                "LatentUpscaleModelLoader",
                TemplateKeyRegistry.getJsonKeyForPlaceholder("latent_upscale_model")
            ),
            // Same for regular upscale models (UpscaleModelLoader) - "model_name" is generic
            upscaleModels = nodeTypeRegistry.getOptionsForNodeInput(
                "UpscaleModelLoader",
                TemplateKeyRegistry.getJsonKeyForPlaceholder("upscale_model")
            ),
            // "sampler_name" / "scheduler" 同样是**通用字段名**（同 aspectRatios / speedModes 的先例），
            // 必须按节点类型取：GITS / OSS FLUX / ays 等自定义采样节点也声明了这两个名字，
            // 按字段名取并集会把 "dpm++"、"GITS[coeff=1.2]"、"OSS FLUX"、"AYS SD1" 混进下拉。
            // 用户在下拉里选一个（列表里"合法"），提交给原生 KSampler 就 400：
            //   Value not in list: sampler_name: 'dpm++' not in (list of length ...)
            // 所以：KSampler 优先 → KSamplerAdvanced 兜底 → 并集兜底（没有 KSampler 的工作流）。
            // 注意这层只管**候选从哪来**；持久化脏数据的清洗仍由 SamplerOptions.sanitize*
            // 在提交前把关（两层互补，缺一不可）。
            samplers = selectNodeTypedOptions(
                primary = nodeTypeRegistry.getOptionsForNodeInput(
                    "KSampler",
                    TemplateKeyRegistry.getJsonKeyForPlaceholder("sampler_name")
                ),
                secondary = nodeTypeRegistry.getOptionsForNodeInput(
                    "KSamplerAdvanced",
                    TemplateKeyRegistry.getJsonKeyForPlaceholder("sampler_name")
                ),
                fallback = nodeTypeRegistry.getOptionsForField(
                    TemplateKeyRegistry.getJsonKeyForPlaceholder("sampler_name")
                )
            ),
            schedulers = selectNodeTypedOptions(
                primary = nodeTypeRegistry.getOptionsForNodeInput(
                    "KSampler",
                    TemplateKeyRegistry.getJsonKeyForPlaceholder("scheduler")
                ),
                secondary = nodeTypeRegistry.getOptionsForNodeInput(
                    "KSamplerAdvanced",
                    TemplateKeyRegistry.getJsonKeyForPlaceholder("scheduler")
                ),
                fallback = nodeTypeRegistry.getOptionsForField(
                    TemplateKeyRegistry.getJsonKeyForPlaceholder("scheduler")
                )
            ),
            // "aspect_ratio" 与 "mode" 都是通用字段名（多个节点都有 mode），必须按节点类型取，
            // 否则会把无关节点的选项并进来（同 upscaleModels 的先例）
            aspectRatios = nodeTypeRegistry.getOptionsForNodeInput("ResolutionSelector", "aspect_ratio"),
            speedModes = nodeTypeRegistry.getOptionsForNodeInput("TESpeedMiniMaxH3", "mode")
        )

        // ── G8 指令 1 / 3：三类核心模型分别上报终态，UNET 失败带原因 ──────────────
        //
        // 这里只做「提取层」的诊断（请求已经成功，故 requestFailed=false）。
        // UNET 与 VAE/CLIP 来源字段不同，单类来源缺失会让 UNET 落 FAILED 而另两类照例 OK，
        // 不再被整体「成功」掩盖成「UNET 沉默留空」。
        val unetKey = TemplateKeyRegistry.getJsonKeyForPlaceholder("unet_name")
        val vaeKey = TemplateKeyRegistry.getJsonKeyForPlaceholder("vae_name")
        // CLIP 用前缀匹配（clip_name / clip_name1…），诊断口径与之对齐
        val clipPrefix = TemplateKeyRegistry.getJsonKeyForPlaceholder("clip_name")

        val unetDiag = diagnoseCategory(
            requestFailed = false,
            inputFieldExists = nodeTypeRegistry.anyNodeDeclaresField(unetKey),
            fieldHasOptions = nodeTypeRegistry.anyNodeFieldHasOptions(unetKey),
            optionCount = base.unets.size
        )
        val vaeDiag = diagnoseCategory(
            requestFailed = false,
            inputFieldExists = nodeTypeRegistry.anyNodeDeclaresField(vaeKey),
            fieldHasOptions = nodeTypeRegistry.anyNodeFieldHasOptions(vaeKey),
            optionCount = base.vaes.size
        )
        val clipDiag = diagnoseCategory(
            requestFailed = false,
            inputFieldExists = nodeTypeRegistry.anyNodeDeclaresField(clipPrefix, matchPrefix = true),
            fieldHasOptions = nodeTypeRegistry.anyNodeFieldHasOptions(clipPrefix, matchPrefix = true),
            optionCount = base.clips.size
        )

        return base.copy(
            unetState = unetDiag.state,
            vaeState = vaeDiag.state,
            clipState = clipDiag.state,
            unetFailure = unetDiag.reason,
            vaeFailure = vaeDiag.reason,
            clipFailure = clipDiag.reason
        )
    }

    /**
     * 请求失败时把三类统一置为 FAILED(请求失败)（G8 指令 1）。
     *
     * **只改状态、不动列表**：磁盘预填或上一次成功可能已经铺了可显示的候选，
     * 失败只是「这次没拿到最新」，不该把已有数据抹掉（Q3-4 / G7 指令 1 红线）。
     */
    private fun markCategoriesFailed(requestError: String) {
        _modelCache.value = _modelCache.value.copy(
            isLoading = false,
            lastError = requestError,
            unetState = CategoryLoadState.FAILED,
            vaeState = CategoryLoadState.FAILED,
            clipState = CategoryLoadState.FAILED,
            unetFailure = CategoryFailureReason.REQUEST_FAILED,
            vaeFailure = CategoryFailureReason.REQUEST_FAILED,
            clipFailure = CategoryFailureReason.REQUEST_FAILED
        )
    }
}

/**
 * 按「节点类型优先、并集兜底」挑选下拉候选（纯函数，可 JVM 单测）。
 *
 * 为什么需要：`getOptionsForField` 是按**字段名**跨所有节点收集并集，只适合
 * 「这个字段名只有一类节点声明」的模型加载器（ckpt_name 之类）。而 `sampler_name` /
 * `scheduler` 是通用字段名 —— GITS / OSS FLUX / ays 等自定义采样节点也声明了它们，
 * 并集里就会出现原生 KSampler 不认识的取值（`dpm++`、`GITS[coeff=1.2]`、`OSS FLUX`、
 * `AYS SD1` …）。用户在这些"看起来合法"的选项里挑一个提交 → ComfyUI 的
 * `validate_inputs()` 判 `Value not in list` → 400。
 *
 * 选择策略（与同文件 aspectRatios / speedModes 的「按节点类型取」同一思路）：
 * 1. [primary] 非空（服务器上有 KSampler）→ **只用它**：原生节点的候选就是原生节点能接受的值；
 * 2. [primary] 空、[secondary] 非空（只有 KSamplerAdvanced）→ 用它；
 * 3. 两者都空（工作流里没有原生采样节点，例如某些第三方采样器工作流）→ 退回并集 [fallback]，
 *    宁可多给候选也不要下拉全空（下拉全空用户连选都选不了）。
 *
 * @param primary 首选节点（KSampler）该输入声明的候选
 * @param secondary 次选节点（KSamplerAdvanced）该输入声明的候选
 * @param fallback 跨所有节点的并集（最后兜底）
 */
internal fun selectNodeTypedOptions(
    primary: List<String>,
    secondary: List<String>,
    fallback: List<String>
): List<String> = when {
    primary.isNotEmpty() -> primary
    secondary.isNotEmpty() -> secondary
    else -> fallback
}
