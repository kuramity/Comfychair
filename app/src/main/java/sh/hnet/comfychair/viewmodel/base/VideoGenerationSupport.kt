package sh.hnet.comfychair.viewmodel.base

import android.graphics.Bitmap
import android.net.Uri
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.cache.MediaStateHolder
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.model.LoraSelection
import sh.hnet.comfychair.model.WorkflowValues
import sh.hnet.comfychair.model.contract.CommonGenerationState
import sh.hnet.comfychair.model.nextSeed
import sh.hnet.comfychair.workflow.WorkflowConstraintsProvider
import sh.hnet.comfychair.workflow.dynamic.DynamicParam

/**
 * 视频页（文生视频 / 图生视频）的 UiState 共同字段。
 *
 * 只是 [CommonGenerationState] 的超集声明；两个视频页的 UiState 用 `override` 声明这些字段
 * （Kotlin 要求显式 override，不会因为属性同名自动满足接口）。
 * 用它把两个视频页里逐字节相同的整函数计算（工作流值快照 / 动态参数重算 / 种子推进）
 * 收敛到 [VideoGenerationSupport] 的纯函数里，而状态写入与保存仍留在各自 ViewModel。
 *
 * B4.2-B 视频对合并：扩入 deferred* 全系列、workflowPlaceholders、工作流条目表与
 * 页面级展示字段（positivePrompt / previewBitmap 等），让 [BaseVideoGenerationViewModel]
 * 的共享函数可以读写这些字段——两个 UiState 本来就有同名字段，补 `override` 即可。
 */
interface VideoGenerationState : CommonGenerationState {
    val selectedWorkflow: String
    val selectedWorkflowId: String
    /** 工作流条目表（共享 [VideoWorkflowItem]，原 TtvWorkflowItem / ItvWorkflowItem 归一） */
    val availableWorkflows: List<VideoWorkflowItem>
    /** 从 {{占位符}} 探测出的工作流占位符（决定字段可见性） */
    val workflowPlaceholders: Set<String>
    val width: String
    val height: String
    val length: String
    val fps: String
    val negativePrompt: String
    /** 百万像素 / 宽高比 / 加速模式：视频持久化要写库，故在接口里强制实现（不依赖 CommonGenerationState 的默认值） */
    override val megapixels: String
    override val aspectRatio: String
    override val speedMode: String
    /** 主 LoRA 权重（{{lora_strength}}） */
    val loraStrength: Float
    val selectedHighnoiseUnet: String
    val selectedLownoiseUnet: String
    val selectedHighnoiseLora: String
    val selectedLownoiseLora: String
    val highnoiseLoraChain: List<LoraSelection>
    val lownoiseLoraChain: List<LoraSelection>
    val dynamicParams: List<DynamicParam>
    val dynamicParamValues: Map<String, String>
    val dynamicParamsRegistryReady: Boolean

    // 页面级展示字段（原两 UiState 同名同型，补 override 即可）
    /** 正向提示词（全局偏好，不随工作流持久化） */
    val positivePrompt: String
    /** 生成中的预览帧 */
    val previewBitmap: Bitmap?
    /** 最近一次生成成功的视频 */
    val currentVideoUri: Uri?
    /** 正在从服务器拉取生成结果 */
    val isFetching: Boolean
    // 尺寸/时长/帧率的校验错误（这几个只属于视频页，CommonGenerationState 没有声明）
    val widthError: String?
    val heightError: String?
    val lengthError: String?
    val fpsError: String?

    // Deferred model selections（模型列表异步到达后要恢复的选择值）
    // deferredCheckpoint 口径保持现状：= savedValues?.model，不带 defaults 中间级
    // （与 TTI/ITI 的条件+defaults 口径不同是有意为之，不许"顺手统一"）
    val deferredCheckpoint: String?
    val deferredUnet: String?
    val deferredLoraName: String?
    val deferredHighnoiseUnet: String?
    val deferredLownoiseUnet: String?
    val deferredHighnoiseLora: String?
    val deferredLownoiseLora: String?
    val deferredVae: String?
    val deferredClip: String?
    val deferredClip1: String?
    val deferredClip2: String?
    val deferredClip3: String?
    val deferredClip4: String?
    val deferredTextEncoder: String?
    val deferredLatentUpscaleModel: String?
}

/**
 * 文生视频 / 图生视频两个 ViewModel 共用的纯计算。
 *
 * 这里只做「输入字段 → 输出新值」，不持有 UiState、不写状态、不保存：
 * 写 `_uiState.value` 与 `savePreferences()` 仍由两个 ViewModel 各自完成
 * （两页的保存时机可以独立演进）。
 */
object VideoGenerationSupport {

    /**
     * 把当前 UiState 组装成按工作流持久化的 [WorkflowValues]。
     *
     * @param existing 该工作流已存的值，用于保留 Workflow Editor 写入的 nodeAttributeEdits
     */
    fun buildWorkflowValues(state: VideoGenerationState, existing: WorkflowValues?): WorkflowValues =
        WorkflowValues(
            width = state.width.toIntOrNull(),
            height = state.height.toIntOrNull(),
            length = state.length.toIntOrNull(),
            frameRate = state.fps.toIntOrNull(),
            // 步数 / 主 LoRA 权重按工作流持久化（否则切工作流就丢回缺省）
            steps = state.steps.toIntOrNull(),
            loraStrength = state.loraStrength,
            // 百万像素 / 宽高比 / 加速模式按工作流持久化
            megapixels = state.megapixels.toFloatOrNull(),
            aspectRatio = state.aspectRatio.takeIf { it.isNotEmpty() },
            speedMode = state.speedMode.takeIf { it.isNotEmpty() },
            // 种子手动控制：与文生图一样按工作流持久化
            seed = state.seed.toLongOrNull(),
            randomSeed = state.randomSeed,
            // 新键（四态取值方式）；老版本读上面那个布尔
            seedMode = state.seedMode.name,
            negativePrompt = state.negativePrompt.takeIf { it.isNotEmpty() },
            // Single-model patterns
            model = state.selectedCheckpoint.takeIf { it.isNotEmpty() },
            unetModel = state.selectedUnet.takeIf { it.isNotEmpty() },
            loraModel = state.selectedLoraName.takeIf { it.isNotEmpty() },
            // Dual-model patterns
            highnoiseUnetModel = state.selectedHighnoiseUnet.takeIf { it.isNotEmpty() },
            lownoiseUnetModel = state.selectedLownoiseUnet.takeIf { it.isNotEmpty() },
            highnoiseLoraModel = state.selectedHighnoiseLora.takeIf { it.isNotEmpty() },
            lownoiseLoraModel = state.selectedLownoiseLora.takeIf { it.isNotEmpty() },
            // Common models
            vaeModel = state.selectedVae.takeIf { it.isNotEmpty() },
            clipModel = state.selectedClip.takeIf { it.isNotEmpty() },
            clip1Model = state.selectedClip1.takeIf { it.isNotEmpty() },
            clip2Model = state.selectedClip2.takeIf { it.isNotEmpty() },
            clip3Model = state.selectedClip3.takeIf { it.isNotEmpty() },
            clip4Model = state.selectedClip4.takeIf { it.isNotEmpty() },
            textEncoderModel = state.selectedTextEncoder.takeIf { it.isNotEmpty() },
            latentUpscaleModel = state.selectedLatentUpscaleModel.takeIf { it.isNotEmpty() },
            highnoiseLoraChain = LoraSelection.toJsonString(state.highnoiseLoraChain)
                .takeIf { state.highnoiseLoraChain.isNotEmpty() },
            lownoiseLoraChain = LoraSelection.toJsonString(state.lownoiseLoraChain)
                .takeIf { state.lownoiseLoraChain.isNotEmpty() },
            nodeAttributeEdits = existing?.nodeAttributeEdits,
            // 动态节点参数（通用节点参数引擎）
            dynamicParams = state.dynamicParamValues
        )

    /** 动态参数重算结果（只在确有变化时产出） */
    data class DynamicParamsUpdate(
        val params: List<DynamicParam>,
        val values: Map<String, String>,
        val registryReady: Boolean
    )

    /**
     * 注册表就绪后重算当前工作流的动态参数（控件清单 + 值，见
     * [ConnectionManager.nodeTypeRegistryReady]）。用户已输入的值优先保留：空串在
     * [sh.hnet.comfychair.workflow.dynamic.DynamicParamValues.resolveValue] 里视为"没有"，
     * 会回退到 defaults / 节点默认值；类型与值都没变时返回 null（调用方不写状态）。
     */
    fun refreshDynamicParams(state: VideoGenerationState): DynamicParamsUpdate? {
        if (state.selectedWorkflowId.isEmpty()) return null
        val snapshot = WorkflowManager.loadDynamicParams(state.selectedWorkflowId, state.dynamicParamValues)
        val ready = ConnectionManager.nodeTypeRegistry.isPopulated()
        if (snapshot.params == state.dynamicParams &&
            snapshot.values == state.dynamicParamValues &&
            ready == state.dynamicParamsRegistryReady
        ) {
            return null
        }
        return DynamicParamsUpdate(snapshot.params, snapshot.values, ready)
    }

    /**
     * 生成提交成功后的种子推进：只在「每次+1 / 每次−1」下动作，把种子挪一格。
     *
     * @return 新种子字符串；不需要推进时返回 null
     */
    fun advanceSeed(state: VideoGenerationState): String? {
        if (!state.seedMode.advances) return null
        val constraints = WorkflowConstraintsProvider.getConstraints("seed", state.selectedWorkflow)
        return nextSeed(
            current = state.seed.toLongOrNull() ?: 0L,
            step = state.seedMode.step,
            min = constraints.min.toLong(),
            max = constraints.max.toLong()
        ).toString()
    }
}

/**
 * 「当前正在播放的视频键」登记器（5.2）：拿到 URI 时登记、切走 / 清空时注销。
 *
 * 磁盘优先模式下 [MediaStateHolder.getVideoUri] 返回的就是 filesDir 里那个文件，
 * 只靠「每类最近一次 prompt」保护不住它 —— 用户再生成一个视频，旧 promptId 立刻被顶掉，
 * 正在播放的文件就可能被磁盘裁剪删掉。登记后裁剪会整组跳过（见 trimVideoFiles）。
 *
 * 行为与原先两个 ViewModel 各自持有的字段 + 两个私有方法完全一致：
 * 同一键重复登记不重复登记；换键先注销旧键再登记新键。
 */
class PlayingVideoRegistry {

    private var key: MediaStateHolder.MediaKey? = null

    /** 登记「这个视频正在播放」；同一键重复调用不会重复登记。 */
    fun mark(newKey: MediaStateHolder.MediaKey) {
        if (key == newKey) return
        key?.let { MediaStateHolder.unregisterPlayingVideo(it) }
        key = newKey
        MediaStateHolder.registerPlayingVideo(newKey)
    }

    /** 注销当前播放登记（播放已切走 / 清空）。 */
    fun clear() {
        key?.let { MediaStateHolder.unregisterPlayingVideo(it) }
        key = null
    }
}