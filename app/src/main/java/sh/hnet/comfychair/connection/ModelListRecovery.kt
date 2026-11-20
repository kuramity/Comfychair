package sh.hnet.comfychair.connection

/**
 * 模型列表「三类分别终态 + 后台返回该不该重拉」的纯判定（G7 / G8，v4.16.10 起）。
 *
 * 抽成零 Android 依赖的纯函数，是为了把历史上「改过但没生效」的两条根因钉死在 JVM 单测里
 * （任务书 12.3 / 12.4）：
 *
 * - **G7（历史 Q3 复发）**：长时间后台返回后模型列表留空。旧 onResume 的补拉判据有两个洞：
 *   ① 只看「四类核心列表是否全空」，**部分空（如只剩 UNET 空）不会触发重拉**；
 *   ② 要求 `!cache.isLoading` 才补拉，于是「加载态卡住不收敛」（E7）反而**永远不会被救回**。
 *   本文件的 [decideModelListReload] 把判据改成：
 *   连接收敛后，「三核心类未全部终态」或「有失败」或「全空」都触发**一次**重拉，
 *   而「正在真在飞」与「已有数据且健康」绝不重拉，也绝不借重拉清空已有数据。
 *
 * - **G8（退出重连 UNET 空、VAE/CLIP 正常）**：三类模型此前共用一份
 *   `isLoaded/isLoading/lastError`，单类提取失败（UNET 来源节点缺失/字段缺失）被整体
 *   「成功」掩盖成一个沉默的空下拉。本文件给出单类终态枚举 [CategoryLoadState]、
 *   失败原因枚举 [CategoryFailureReason] 与诊断纯函数 [diagnoseCategory]，
 *   让「分别上报完成状态、任一失败可见并可重试」有据可依。
 *
 * 注释与日志一律简体中文（本轮共同约束）。
 */

/**
 * 三类核心模型（UNET / VAE / CLIP）。G8 要求这三类分别上报完成状态。
 *
 * checkpoint / lora 等其余类别不在本枚举内：它们要么与核心类同源于 `/object_info`、
 * 要么在 G7/G8 现象里没有独立失败问题；不扩大改动面。
 */
enum class CoreCategory {
    UNET,
    VAE,
    CLIP
}

/**
 * 单类别的加载终态。
 *
 * 只有 [PENDING] 不是终态；其余三态都算「已进入终态」，据此判定加载态能否清除（G8 指令 2）。
 */
enum class CategoryLoadState {
    /** 未进入终态：请求在飞、或从未发起 */
    PENDING,

    /** 终态·成功且拿到了候选 */
    OK,

    /**
     * 终态·成功但该类别候选为空，且服务器**确实有这个来源字段**（COMBO 选项为空）。
     * 与 [FAILED] 严格区分：这种空是「服务器真没放这类模型」，重试也拉不出东西（Q3-3 口径）。
     */
    EMPTY_OK,

    /** 终态·失败：请求失败，或提取来源缺失。必须可见、可单独重试（G8 指令 1/3） */
    FAILED
}

/** 已进入终态的类别集合（除 PENDING 外） */
private val TERMINAL_STATES = setOf(
    CategoryLoadState.OK,
    CategoryLoadState.EMPTY_OK,
    CategoryLoadState.FAILED
)

/** 单类别是否已进入终态 */
fun CategoryLoadState.isTerminal(): Boolean = this in TERMINAL_STATES

/**
 * 单类别提取失败的具体原因（G8 指令 3：UNET 提取失败需带原因，便于用户与排查定位）。
 */
enum class CategoryFailureReason {
    /** 无失败（配合 OK / EMPTY_OK / PENDING 使用） */
    NONE,

    /** `/object_info` 整体请求失败：网络层问题，三类都会带上它 */
    REQUEST_FAILED,

    /**
     * 来源节点缺失：整个 `/object_info` 里**没有任何节点声明该输入字段**
     * （如 UNET 的 unet_name）。对 UNET 而言意味着服务器没装/没注册 UNET 加载器节点。
     */
    NODE_MISSING,

    /**
     * 字段缺失 / 形态不符：某节点声明了该输入名，但它不是「带选项的 COMBO」，
     * 因而提不出候选（例如被改成了 forceInput 连接型输入、或节点定义异常）。
     */
    FIELD_MISSING
}

/** 单类别的诊断结果：终态 + 失败原因（原因在 FAILED 时无意义，保持 NONE） */
data class CategoryDiagnosis(
    val state: CategoryLoadState,
    val reason: CategoryFailureReason
)

/**
 * 判定单个核心类别的终态与失败原因（G8 指令 3）。
 *
 * 输入全部是「已归约后的事实」，不碰 registry / OkHttp，因此可在 JVM 单测逐条钉住：
 *
 * 1. [requestFailed] 为真 → [CategoryFailureReason.REQUEST_FAILED] + FAILED；
 *    （整体请求挂了，三类都落这里）
 * 2. [optionCount] 大于 0 → OK + NONE；（拿到了候选，皆大欢喜）
 * 3. [optionCount] 为 0 且 [fieldHasOptions] 为真 → EMPTY_OK + NONE；
 *    （字段是带选项的 COMBO、只是选项列表为空 = 服务器真没这类模型）
 * 4. [optionCount] 为 0、字段存在（[inputFieldExists]）但不带选项 → FIELD_MISSING + FAILED；
 *    （节点声明了字段却提不出 COMBO 候选 = 字段缺失/形态不符）
 * 5. 其余（字段在任意节点都没出现）→ NODE_MISSING + FAILED。
 *    （来源节点缺失）
 *
 * 3→4→5 的顺序即「服务器没模型」与「提取失败」的分水岭：前者不该重试，后者必须可重试。
 */
fun diagnoseCategory(
    requestFailed: Boolean,
    inputFieldExists: Boolean,
    fieldHasOptions: Boolean,
    optionCount: Int
): CategoryDiagnosis = when {
    requestFailed -> CategoryDiagnosis(CategoryLoadState.FAILED, CategoryFailureReason.REQUEST_FAILED)
    optionCount > 0 -> CategoryDiagnosis(CategoryLoadState.OK, CategoryFailureReason.NONE)
    fieldHasOptions -> CategoryDiagnosis(CategoryLoadState.EMPTY_OK, CategoryFailureReason.NONE)
    inputFieldExists -> CategoryDiagnosis(CategoryLoadState.FAILED, CategoryFailureReason.FIELD_MISSING)
    else -> CategoryDiagnosis(CategoryLoadState.FAILED, CategoryFailureReason.NODE_MISSING)
}

/**
 * 三核心类是否全部进入终态（任务书点名要抽的第一个纯函数）。
 *
 * 「加载态仅在全部类型进入终态后清除」（G8 指令 2）判的就是这个：只要还有一类是 PENDING，
 * 整体就不能说「加载完成」。
 */
fun allCoreCategoriesTerminal(
    unet: CategoryLoadState,
    vae: CategoryLoadState,
    clip: CategoryLoadState
): Boolean = unet.isTerminal() && vae.isTerminal() && clip.isTerminal()

/**
 * 后台返回「该不该重拉」的决策（任务书点名要抽的第二个纯函数）。
 *
 * 返回值区分「为什么不做」，便于日志与单测断言，也避免把不同原因糊成同一个布尔。
 */
enum class ModelListReloadDecision {
    /** 触发一次重拉（不排队、不清空已有数据） */
    RELOAD,

    /** 连接还没收敛到稳定态：此刻发起会再次被过渡态吞掉（G7 指令 3），跳过 */
    SKIP_TRANSIENT_CONNECTION,

    /** 已有一次 `/object_info` 真正在飞：不叠加（G7 场景「部分有值且加载中不重拉」） */
    SKIP_IN_FLIGHT,

    /** 三核心类全终态、无失败、且有数据：一切正常，不打扰 */
    SKIP_HEALTHY
}

/**
 * 决策入参：都是「已归约的事实」，可直接在单测里手搓。
 *
 * @param connectionSettled 连接状态机是否收敛到稳定态（Connected / OfflineConnected 且有可用 client）。
 *   G7 指令 3 要求「重拉复用连接状态机收敛后的状态，不得在过渡态下发起而再次被吞」。
 * @param inFlight 是否真的有一个 `/object_info` 在飞（闸门占用）。这是 E7「加载态长期驻留」的解药：
 *   只要 [inFlight]=false，哪怕 [isLoading]=true 也判定为「僵尸加载」，允许重拉把卡住的界面救回来。
 * @param unetState/vaeState/clipState 三核心类当前终态（来自 [CategoryLoadState]）
 * @param hasAnyCoreData 三核心类是否**至少有一个候选**（UNET/VAE/CLIP 任一非空）。
 *   用于区分「服务器真没模型」（全 EMPTY_OK、无数据、不重拉）与「还没拉到数据」（重拉）。
 */
fun decideModelListReload(
    connectionSettled: Boolean,
    inFlight: Boolean,
    unetState: CategoryLoadState,
    vaeState: CategoryLoadState,
    clipState: CategoryLoadState,
    hasAnyCoreData: Boolean
): ModelListReloadDecision {
    // 1. 过渡态下绝不发起（G7 指令 3）
    if (!connectionSettled) return ModelListReloadDecision.SKIP_TRANSIENT_CONNECTION

    // 2. 真在飞：不叠加请求（覆盖「部分有值且加载中不重拉」）
    if (inFlight) return ModelListReloadDecision.SKIP_IN_FLIGHT

    // 3. 有任一类卡在非终态、又没有在飞请求 = 僵尸加载态（E7「正在加载不收敛」）：重拉救回
    if (!allCoreCategoriesTerminal(unetState, vaeState, clipState)) {
        return ModelListReloadDecision.RELOAD
    }

    // 4. 全终态但有失败（含单类提取失败）：可见 + 给一次重试机会（G8 指令 1）
    if (unetState == CategoryLoadState.FAILED ||
        vaeState == CategoryLoadState.FAILED ||
        clipState == CategoryLoadState.FAILED
    ) {
        return ModelListReloadDecision.RELOAD
    }

    // 5. 全终态、无失败，但一点核心数据都没有：G7「列表为空」触发一次重拉
    //    （服务器真没模型的情况，重拉也只是确认一次，由 SingleFlight 天然限流，不循环）
    if (!hasAnyCoreData) return ModelListReloadDecision.RELOAD

    // 6. 其余：数据健康，什么都不做，绝不清空已有列表（G7 指令 1「已有数据不得被清空」）
    return ModelListReloadDecision.SKIP_HEALTHY
}

/**
 * 取某个核心类别的终态（UI 与判定共用，避免 `when` 到处散落）。
 *
 * 类别之外（LoRA / Checkpoint / 采样器…）没有独立状态字段，调用方应退回整体口径。
 */
fun ModelCache.stateOf(category: CoreCategory): CategoryLoadState = when (category) {
    CoreCategory.UNET -> unetState
    CoreCategory.VAE -> vaeState
    CoreCategory.CLIP -> clipState
}

/** 取某个核心类别的失败原因（仅 FAILED 时有意义） */
fun ModelCache.failureOf(category: CoreCategory): CategoryFailureReason = when (category) {
    CoreCategory.UNET -> unetFailure
    CoreCategory.VAE -> vaeFailure
    CoreCategory.CLIP -> clipFailure
}

/** 该类别是否有候选可显示 */
fun ModelCache.hasOptionsFor(category: CoreCategory): Boolean = when (category) {
    CoreCategory.UNET -> unets.isNotEmpty()
    CoreCategory.VAE -> vaes.isNotEmpty()
    CoreCategory.CLIP -> clips.isNotEmpty()
}

/** 三核心类是否至少有一个候选（[decideModelListReload] 的 hasAnyCoreData 口径） */
fun ModelCache.hasAnyCoreModel(): Boolean =
    unets.isNotEmpty() || vaes.isNotEmpty() || clips.isNotEmpty()

/**
 * 由缓存快照算「该不该重拉」的便捷入口（onResume 必检用）。
 *
 * 把 [decideModelListReload] 的六个入参从 [ModelCache] 与连接侧事实里取出来，
 * 调用方只需提供 `connectionSettled` 与 `inFlight` 两个它自己才知道的事实。
 */
fun ModelCache.reloadDecision(
    connectionSettled: Boolean,
    inFlight: Boolean
): ModelListReloadDecision = decideModelListReload(
    connectionSettled = connectionSettled,
    inFlight = inFlight,
    unetState = unetState,
    vaeState = vaeState,
    clipState = clipState,
    hasAnyCoreData = hasAnyCoreModel()
)

