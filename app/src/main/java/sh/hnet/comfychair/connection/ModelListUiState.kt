package sh.hnet.comfychair.connection

/**
 * 模型列表下拉的四种用户可见状态（Q3-3，v4.16.5）。
 *
 * 背景：/object_info 失败后旧实现只置 lastError 且 UI 零消费——「失败」与「空」
 * 在界面上无法区分，用户看到的就是一个沉默的空框。抽成零依赖枚举 + 纯函数，
 * 是为了让四态判定可以在 JVM 单测里逐条钉住（UI 层只消费结果）。
 */
enum class ModelListUiState {
    /** 请求在飞（网络 / 磁盘预填阶段）：下拉字段显示「正在加载…」 */
    LOADING,

    /** 列表可用：正常展示选项 */
    LOADED,

    /** 已加载完成但列表为空：服务器上确实没有模型，不能与失败混淆（不提示重试） */
    EMPTY,

    /** 拉取失败（lastError 非空）：必须可见并提供重试入口 */
    FAILED
}

/**
 * 四态判定（Q3-3，纯函数）。
 *
 * 优先级逐条：
 * 1. isLoading → LOADING：请求在飞时一切以"加载中"示人（哪怕磁盘预填已把
 *    旧数据铺上，下拉仍显示加载语义，网络回来后静默替换——既有行为不变）；
 * 2. lastError != null → FAILED：失败必须可见可重试。列表里可能还有磁盘预填
 *    的旧数据可显示（失败不清列表，Q3-4 红线），UI 端照常渲染选项、仅在菜单
 *    尾部附加失败重试行，不会用失败态盖掉可用数据；
 * 3. isLoaded 且列表空 → EMPTY：服务器真的没有模型，明确告知而不是沉默空框
 *    或误导性重试。isLoaded=false 的空列表（未连接 / 从未拉取）落不到这条，
 *    维持既有"空框"行为，不凭空说"服务器没模型"；
 * 4. 其余 → LOADED。
 *
 * @param isLoading ModelCache.isLoading
 * @param isLoaded ModelCache.isLoaded
 * @param lastError ModelCache.lastError（null = 无错误）
 * @param optionCount 该下拉当前可显示的选项数（各字段自己的 options.size）
 */
fun resolveModelListUiState(
    isLoading: Boolean,
    isLoaded: Boolean,
    lastError: String?,
    optionCount: Int
): ModelListUiState = when {
    isLoading -> ModelListUiState.LOADING
    lastError != null -> ModelListUiState.FAILED
    isLoaded && optionCount == 0 -> ModelListUiState.EMPTY
    else -> ModelListUiState.LOADED
}

/**
 * 分类态 → 四态（G8 指令 1/2 的 UI 侧口径）。
 *
 * 与 [resolveModelListUiState] 的区别只有一个但很关键：判定输入从「整体
 * isLoading / isLoaded / lastError」换成「**这一类**是否已进入终态 + 它的失败原因」。
 * 于是 UNET 因来源节点缺失而失败时，UNET 下拉显示「加载失败（可重试）」，
 * 而 VAE / CLIP 各自照常显示候选 —— 单类失败不再被整体的「成功」掩盖（G8 的现象），
 * 也不会让一个类别的未收敛把别的类别拖成「正在加载」（E7）。
 *
 * @param state 该类别的终态（[CategoryLoadState]）
 * @param optionCount 该下拉当前可显示的选项数（与分类态交叉校验：状态说 OK 但列表空，
 *   说明缓存与选项来源不同步，按 EMPTY 处理比假装正常安全）
 */
fun resolveModelListUiState(
    state: CategoryLoadState,
    optionCount: Int
): ModelListUiState = when (state) {
    CategoryLoadState.PENDING -> ModelListUiState.LOADING
    CategoryLoadState.FAILED -> ModelListUiState.FAILED
    CategoryLoadState.EMPTY_OK -> ModelListUiState.EMPTY
    CategoryLoadState.OK ->
        if (optionCount > 0) ModelListUiState.LOADED else ModelListUiState.EMPTY
}
