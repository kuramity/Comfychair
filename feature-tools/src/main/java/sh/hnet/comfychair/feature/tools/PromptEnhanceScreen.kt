@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package sh.hnet.comfychair.feature.tools

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** 模式下标：与顶部两个标签的下标一致。 */
private const val ModeText = 0
private const val ModeImage = 1

/** 等待态四段流程的段数。 */
private const val StageCount = 4

/** 当前段呼吸动画单程时长（1400ms 单程，往返即 RepeatMode.Reverse）。 */
private const val PulseHalfPeriodMs = 1400

/** 自动推进段位的时间：模型加载、技能加载各占一段。 */
private const val StageStepMs = 2600L

/** 连接线充能到 0.90 的时长；上一段完成时再 240ms 补到 1.00。 */
private const val ConnectorChargeMs = 2600
private const val ConnectorCompleteMs = 240

/** 结果出来后先把「输出」段闪一下，再开始逐字显现。 */
private const val OutputStageMs = 400L

/** 单次生成的超时（本地 LLM 首次加载模型 30~60 秒属正常）。 */
private const val GenerationTimeoutMs = 180_000L

/** 轮询间隔。 */
private const val PollIntervalMs = 1000L

/** 逐字显现：18ms/字基准，>600 字 2 字/拍，>1500 字 4 字/拍，总时长 12 秒上限。 */
private const val TypeBaseDelayMs = 18L
private const val TypeMaxTotalMs = 12_000L
private const val TypeSlowThreshold = 600
private const val TypeFastThreshold = 1500

/** 显存曲线：60 槽环形缓冲、每秒一个点。 */
private const val VramSlotCount = 60
private const val VramSampleIntervalMs = 1000L

/** 复制按钮「已复制」保持时长。 */
private const val CopiedRevertMs = 1400L

/** 紧凑屏阈值：宽度小于 360dp 时整体降一档。 */
private const val CompactScreenWidthDp = 360

/**
 * 提示词增强的网络能力，由 app 侧用现有的 ComfyUIClient 实现并注入。
 *
 * 本模块没有网络依赖，也不允许新增网络库，所以提交/轮询/上传都收在这里。
 */
interface PromptEnhanceGateway {

    /**
     * 提交工作流，返回 ComfyUI 的 promptId。
     *
     * 传入的是**完整工作流 JSON**（assets 里 prebuilt_*.json 的原文形状，含 `nodes`、
     * `defaults`），占位符已替换完毕；app 侧直接交给 `ComfyUIClient.submitPrompt`
     * 即可（它自己会抽出 `nodes` 并补 client_id）。
     */
    suspend fun submit(workflowJson: String): Result<String>

    /**
     * 轮询结果：返回 null 表示还在跑；非 null 是 `TE_text_display` 的输出文本。
     *
     * 失败时请把 ComfyUI 的 `exception_message` 放进异常 message —— 界面会原样显示，
     * 不吞错误。
     */
    suspend fun poll(promptId: String): Result<String?>

    /**
     * 把用户选的图片上传到 ComfyUI 的 input 目录，返回服务端文件名（填进 `{{image_filename}}`）。
     *
     * 只有「文字+图片→文字」模式会用到。默认实现直接失败：宿主没接线时图文模式会给出
     * 明确报错，而不是发一个占位符没替换的坏工作流。
     */
    suspend fun uploadImage(uri: String): Result<String> =
        Result.failure(UnsupportedOperationException("PromptEnhanceGateway.uploadImage is not implemented"))

    /**
     * 结构级占位符替换（B3 双通道合一）：递归遍历 [root] 树，只替换字符串叶子值。
     *
     * [values] 迭代序即替换序（先派生值、后用户值，防二次替换——方向性语义见
     * app 侧的 `PlaceholderPipeline`）。实现委托 `PlaceholderPipeline.substituteTree`。
     */
    fun substituteInTree(root: JSONObject, values: LinkedHashMap<String, String>)
}

/**
 * 用户选中的图片。
 *
 * @param displayName 界面上展示的名字（一般是文件名）
 * @param uri 图片的 URI 字符串（content:// 或 file://），原样交给
 *        [PromptEnhanceGateway.uploadImage] 上传
 */
data class PromptEnhanceImage(
    val displayName: String,
    val uri: String,
)

/**
 * 单个模式的独立状态：jobId / 阶段 / 结果 / 错误 / 规则文件各有一套。
 *
 * 两套槽位互不干扰，生成中切标签不会打断任何一边；切回来直接读这里的当前值。
 */
private class EnhanceSlot {
    var ruleFile by mutableStateOf<String?>(null)
    var running by mutableStateOf(false)

    /** -1 空闲；0..3 当前段；4 四段全部完成。 */
    var stage by mutableIntStateOf(-1)
    var result by mutableStateOf("")

    /** 逐字显现已经显示到第几个字。 */
    var revealed by mutableIntStateOf(0)
    var error by mutableStateOf<String?>(null)

    /** 生成中被切走（切回后在结果区顶部提示「后台仍在生成」）。 */
    var backgrounded by mutableStateOf(false)

    /** 每次开始生成自增，用来重启等待态动画。 */
    var runToken by mutableIntStateOf(0)

    /** 当前生成协程（页面销毁时随 scope 一起取消，这里只用于重新开始时兜底）。 */
    var job: Job? = null

    fun resetForRun() {
        running = true
        stage = 0
        result = ""
        revealed = 0
        error = null
        backgrounded = false
        runToken += 1
    }

    fun fail(message: String) {
        error = message
        running = false
        stage = -1
    }
}

/**
 * 提示词增强页。
 *
 * 两个模式（文字→文字 / 文字+图片→文字）各自持有一套生成状态，生成中可以自由切标签：
 * 切走的那一边在后台继续跑，切回来显示当前阶段或已经完成的结果。
 *
 * 本模块读不到 app 的 assets，也拿不到网络能力，所以下面这些全部由 app 侧注入：
 * 规则文件清单（[ruleFiles]）+ 正文读取（[ruleFileContent]）、两份工作流 JSON
 * （[textWorkflowJson] / [imageWorkflowJson]）、选图回调与结果（[onPickImage] /
 * [pickedImage]）、提交与轮询（[gateway]）、显存曲线数据（[vramUsageFlow]）。
 *
 * @param onBack 顶栏返回：由调用方接到「退回小工具列表」（系统返回键走 MainContainerActivity
 *        的逐级返回特判，两者落点一致）
 * @param ruleFiles 规则文件下拉的数据源：`(显示名, 文件名)`；空列表时整个下拉隐藏。
 *        「（不注入）」选项会被过滤掉（选它会触发 ComfyUI 的自动选择分支并报错）
 * @param ruleFileContent 按文件名读正文；用来填 `{{skill_file_content}}`。
 *        填不上（返回 null）会直接报错，不发请求
 * @param textWorkflowJson 「文字→文字」工作流 JSON（assets 原文，含 defaults / nodes）
 * @param imageWorkflowJson 「文字+图片→文字」工作流 JSON（同上）
 * @param onPickImage 点「选图片」时向外抛，由 app 弹相册/图库双来源选择
 * @param pickedImage app 选完回传的图片；图文模式下为空时「开始生成」禁用
 * @param gateway 提交 / 轮询 / 上传图片的实现，由 app 侧用 ComfyUIClient 注入
 * @param vramUsageFlow ComfyUI 的显存占用（0f..1f）；为 null 或还没数据时整块曲线不显示
 */
@Composable
fun PromptEnhanceScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    ruleFiles: List<Pair<String, String>> = emptyList(),
    ruleFileContent: ((String) -> String?)? = null,
    textWorkflowJson: String = "",
    imageWorkflowJson: String = "",
    onPickImage: (() -> Unit)? = null,
    pickedImage: PromptEnhanceImage? = null,
    gateway: PromptEnhanceGateway? = null,
    vramUsageFlow: Flow<Float?>? = null,
) {
    var selectedTab by remember { mutableIntStateOf(ModeText) }
    var prompt by remember { mutableStateOf("") }
    val slots = remember { arrayOf(EnhanceSlot(), EnhanceSlot()) }
    val scope = rememberCoroutineScope()
    val compact = LocalConfiguration.current.screenWidthDp < CompactScreenWidthDp

    // 「（不注入）」会触发 ComfyUI 的自动选择分支并报错，直接不给这个选项
    val usableRuleFiles = remember(ruleFiles) {
        ruleFiles.filter { it.second.isNotBlank() && !it.first.contains("不注入") }
    }
    LaunchedEffect(usableRuleFiles) {
        if (usableRuleFiles.isEmpty()) return@LaunchedEffect
        slots.forEachIndexed { index, slot ->
            if (slot.ruleFile == null) slot.ruleFile = defaultRuleFile(usableRuleFiles, index)
        }
    }

    val msgTimeout = stringResource(R.string.tools_error_timeout)
    val msgEmpty = stringResource(R.string.tools_error_empty_result)
    val msgUnknown = stringResource(R.string.tools_error_unknown)
    val msgRuleFile = stringResource(R.string.tools_error_rule_file)

    val currentSlot = slots[selectedTab]
    val otherSlot = slots[1 - selectedTab]
    val currentTemplate = if (selectedTab == ModeImage) imageWorkflowJson else textWorkflowJson
    val canGenerate = gateway != null &&
        currentTemplate.isNotBlank() &&
        !currentSlot.running &&
        (selectedTab != ModeImage || pickedImage != null)

    /** 切标签：把正在跑的这边标记为「后台」；两边状态本来就互不干扰。 */
    fun selectTab(index: Int) {
        if (index == selectedTab) return
        if (slots[selectedTab].running) slots[selectedTab].backgrounded = true
        selectedTab = index
    }

    /** 开始生成（只用当前标签那一套状态）。 */
    fun startGeneration() {
        val mode = selectedTab
        val slot = slots[mode]
        val gateway = gateway ?: return
        val template = if (mode == ModeImage) imageWorkflowJson else textWorkflowJson
        if (slot.running || template.isBlank()) return
        val image = if (mode == ModeImage) pickedImage else null
        if (mode == ModeImage && image == null) return
        val ruleFileName = slot.ruleFile.orEmpty()
        val ruleText = if (ruleFileName.isBlank()) "" else ruleFileContent?.invoke(ruleFileName)
        if (ruleFileName.isNotBlank() && ruleText == null) {
            // skill_file_content 是硬要求，填不上就别发请求（发了会走「自动选择」分支报错）
            slot.fail(msgRuleFile)
            return
        }

        slot.job?.cancel()
        slot.resetForRun()
        val promptSnapshot = prompt
        slot.job = scope.launch {
            try {
                val imageFileName = if (image != null) {
                    gateway.uploadImage(image.uri).getOrThrow()
                } else {
                    null
                }
                val workflowJson = buildWorkflowJson(
                    template = template,
                    userPrompt = promptSnapshot,
                    ruleFileName = ruleFileName,
                    ruleFileContent = ruleText.orEmpty(),
                    imageFileName = imageFileName,
                    gateway = gateway,
                )
                val promptId = gateway.submit(workflowJson).getOrThrow()

                // 段位推进：没有真实进度事件，按时间往前走两段，推理段一直停到结果回来
                val stageJob = launch {
                    delay(StageStepMs)
                    if (slot.stage == 0) slot.stage = 1
                    delay(StageStepMs)
                    if (slot.stage == 1) slot.stage = 2
                }
                val text = try {
                    withTimeout(GenerationTimeoutMs) {
                        var polled: String? = null
                        while (true) {
                            val value = gateway.poll(promptId).getOrThrow()
                            if (value != null) {
                                polled = value
                                break
                            }
                            delay(PollIntervalMs)
                        }
                        polled.orEmpty()
                    }
                } finally {
                    stageJob.cancel()
                }

                if (text.isBlank()) {
                    slot.fail(msgEmpty)
                    return@launch
                }
                slot.stage = 3
                delay(OutputStageMs)
                slot.result = text
                slot.revealed = 0
                typewrite(text) { shown -> slot.revealed = shown }
                slot.stage = StageCount
                slot.running = false
            } catch (e: TimeoutCancellationException) {
                slot.fail(msgTimeout)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                slot.fail(e.message?.takeIf { it.isNotBlank() } ?: msgUnknown)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tools_prompt_enhance_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.tools_back),
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == ModeText,
                    onClick = { selectTab(ModeText) },
                    text = { Text(stringResource(R.string.tools_prompt_enhance_tab_text)) }
                )
                Tab(
                    selected = selectedTab == ModeImage,
                    onClick = { selectTab(ModeImage) },
                    text = { Text(stringResource(R.string.tools_prompt_enhance_tab_image)) }
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = if (compact) 12.dp else 16.dp,
                        vertical = 12.dp,
                    )
            ) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.tools_input_hint)) },
                    minLines = 4,
                    maxLines = 5,
                    textStyle = MaterialTheme.typography.bodyMedium,
                )

                if (usableRuleFiles.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    RuleFileDropdown(
                        files = usableRuleFiles,
                        selectedFileName = currentSlot.ruleFile,
                        onSelect = { fileName -> currentSlot.ruleFile = fileName },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (selectedTab == ModeImage) {
                    Spacer(modifier = Modifier.height(8.dp))
                    ImagePickerRow(
                        image = pickedImage,
                        enabled = !currentSlot.running && onPickImage != null,
                        onPickImage = onPickImage,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = { startGeneration() },
                    enabled = canGenerate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    if (currentSlot.running) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = LocalContentColor.current,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        stringResource(
                            if (currentSlot.running) R.string.tools_generating else R.string.tools_generate
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                key(selectedTab) {
                    ResultSection(
                        stage = currentSlot.stage,
                        running = currentSlot.running,
                        result = currentSlot.result,
                        revealed = currentSlot.revealed,
                        error = currentSlot.error,
                        runToken = currentSlot.runToken,
                        compact = compact,
                        showBackgroundNote = otherSlot.running || (currentSlot.running && currentSlot.backgrounded),
                        vramUsageFlow = vramUsageFlow,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/** 按模式预选规则文件：文字模式找「文生图」，图文模式找「图像编辑」，找不到用第一项。 */
private fun defaultRuleFile(files: List<Pair<String, String>>, mode: Int): String {
    val keyword = if (mode == ModeImage) "图像编辑" else "文生图"
    val matched = files.firstOrNull { it.second.contains(keyword) || it.first.contains(keyword) }
    return (matched ?: files.first()).second
}

/**
 * 结果区：标题 + 复制 + 固定大小的内容容器（空态 / 等待态 / 结果文本 / 错误）。
 *
 * 结果绝不弹窗；文本可滚动、可长按选中，复制始终取完整原文（不是逐字显现到一半的内容）。
 */
@Composable
private fun ResultSection(
    stage: Int,
    running: Boolean,
    result: String,
    revealed: Int,
    error: String?,
    runToken: Int,
    compact: Boolean,
    showBackgroundNote: Boolean,
    vramUsageFlow: Flow<Float?>?,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val scrollState = rememberScrollState()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(CopiedRevertMs)
            copied = false
        }
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.tools_result_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(result))
                    copied = true
                },
                enabled = result.isNotEmpty(),
            ) {
                Text(
                    stringResource(if (copied) R.string.tools_copied else R.string.tools_copy),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                if (showBackgroundNote) {
                    Text(
                        text = stringResource(R.string.tools_background_note),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    when {
                        error != null -> Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState),
                        )

                        result.isNotEmpty() -> Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                        ) {
                            SelectionContainer {
                                Text(
                                    text = result.take(revealed.coerceIn(0, result.length)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }

                        running -> WaitingPanel(
                            stage = stage,
                            runToken = runToken,
                            compact = compact,
                            vramUsageFlow = vramUsageFlow,
                            modifier = Modifier.fillMaxSize(),
                        )

                        else -> Text(
                            text = stringResource(R.string.tools_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
            }
        }
    }
}

/** 等待态：四段流程 + 可选显存曲线。 */
@Composable
private fun WaitingPanel(
    stage: Int,
    runToken: Int,
    compact: Boolean,
    vramUsageFlow: Flow<Float?>?,
    modifier: Modifier = Modifier,
) {
    var samples by remember { mutableStateOf<List<Float>>(emptyList()) }
    val latestSample = remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(vramUsageFlow, runToken) {
        samples = emptyList()
        latestSample.value = null
        val flow = vramUsageFlow ?: return@LaunchedEffect
        coroutineScope {
            launch {
                flow.collect { value -> latestSample.value = value }
            }
            while (true) {
                delay(VramSampleIntervalMs)
                val value = latestSample.value ?: continue
                samples = (samples + value.coerceIn(0f, 1f)).takeLast(VramSlotCount)
            }
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        GenerationFlow(stage = stage, runToken = runToken, compact = compact)
        // 拿不到显存数据就整块不显示，不拿 0 凑数
        if (samples.isNotEmpty()) {
            Spacer(modifier = Modifier.height(if (compact) 8.dp else 12.dp))
            VramChart(
                samples = samples,
                height = if (compact) 40.dp else 56.dp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 四段流程：每段一个圆角方块，段间一条 2dp 连接线（连接线随进度充能）。 */
@Composable
private fun GenerationFlow(
    stage: Int,
    runToken: Int,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val boxSize: Dp = if (compact) 46.dp else 56.dp
    val labels = listOf(
        stringResource(R.string.tools_stage_model),
        stringResource(R.string.tools_stage_skill),
        stringResource(R.string.tools_stage_infer),
        stringResource(R.string.tools_stage_output),
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        for (index in 0 until StageCount) {
            StageNode(
                index = index,
                stage = stage,
                runToken = runToken,
                boxSize = boxSize,
                compact = compact,
                label = labels[index],
                modifier = Modifier.weight(1f),
            )
            if (index < StageCount - 1) {
                FlowConnector(
                    index = index,
                    stage = stage,
                    runToken = runToken,
                    modifier = Modifier
                        .weight(ConnectorWeight)
                        .padding(top = (boxSize - 2.dp) / 2, start = 2.dp, end = 2.dp),
                )
            }
        }
    }
}

/** 连接线在 Row 里的相对宽度（比段窄很多，靠权重自适应窄屏）。 */
private const val ConnectorWeight = 0.36f

/** 单段：当前段呼吸、已完成段换成 ✓ 并弹一下、未开始段保持静默。 */
@Composable
private fun StageNode(
    index: Int,
    stage: Int,
    runToken: Int,
    boxSize: Dp,
    compact: Boolean,
    label: String,
    modifier: Modifier = Modifier,
) {
    val done = stage > index
    val active = stage == index

    val pulse = remember { Animatable(0f) }
    LaunchedEffect(active, runToken) {
        if (!active) {
            pulse.snapTo(0f)
            return@LaunchedEffect
        }
        while (true) {
            pulse.animateTo(1f, tween(PulseHalfPeriodMs, easing = FastOutSlowInEasing))
            pulse.animateTo(0f, tween(PulseHalfPeriodMs, easing = FastOutSlowInEasing))
        }
    }

    val doneScale = remember { Animatable(1f) }
    LaunchedEffect(done, runToken) {
        if (!done) {
            doneScale.snapTo(1f)
            return@LaunchedEffect
        }
        doneScale.snapTo(0.94f)
        doneScale.animateTo(1.04f, tween(170, easing = FastOutSlowInEasing))
        doneScale.animateTo(1f, tween(150, easing = FastOutSlowInEasing))
    }

    val shape = RoundedCornerShape(14.dp)
    val background = when {
        done -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f)
        active -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f + 0.08f * pulse.value)
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val borderColor = when {
        done -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f)
        active -> MaterialTheme.colorScheme.primary.copy(alpha = 0.65f + 0.35f * pulse.value)
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val contentColor = when {
        done -> MaterialTheme.colorScheme.tertiary
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(boxSize)
                .graphicsLayer {
                    val scale = if (done) doneScale.value else 1f
                    scaleX = scale
                    scaleY = scale
                }
                .background(background, shape)
                .border(1.dp, borderColor, shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (done) Icons.Default.Check else StageIcons[index],
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(if (compact) 20.dp else 22.dp),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 段图标（完成时统一换成 ✓）。 */
private val StageIcons: List<ImageVector> = listOf(
    Icons.Outlined.Memory,
    Icons.Outlined.Bolt,
    Icons.Outlined.Psychology,
    Icons.Outlined.Description,
)

/**
 * 连接线：底槽 outlineVariant，已充能部分 primary。
 *
 * 当前段在进行时 0 → 0.90 用 2600ms；上一段完成时 0.90 → 1.00 用 240ms。
 * 充到 1.0 是「已完成」语义，所以进行中只到 0.90。
 */
@Composable
private fun FlowConnector(
    index: Int,
    stage: Int,
    runToken: Int,
    modifier: Modifier = Modifier,
) {
    val progress = remember {
        Animatable(if (stage > index) 1f else 0f)
    }
    LaunchedEffect(stage, runToken) {
        when {
            stage > index -> progress.animateTo(1f, tween(ConnectorCompleteMs, easing = FastOutSlowInEasing))
            stage == index -> progress.animateTo(0.9f, tween(ConnectorChargeMs, easing = FastOutSlowInEasing))
            else -> progress.snapTo(0f)
        }
    }
    val rounded = RoundedCornerShape(1.dp)
    Box(
        modifier = modifier
            .height(2.dp)
            .background(MaterialTheme.colorScheme.outlineVariant, rounded),
    ) {
        val fraction = progress.value.coerceIn(0f, 1f)
        if (fraction > 0.001f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(MaterialTheme.colorScheme.primary, rounded),
            )
        }
    }
}

/**
 * 实时显存曲线：60 槽环形的折线，最新的点贴右边缘。
 *
 * 只在等待态显示；[samples] 为空时整块不画（不显示 0）。
 */
@Composable
private fun VramChart(
    samples: List<Float>,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val fillColor = lineColor.copy(alpha = 0.12f)

    Canvas(modifier = modifier.height(height)) {
        val width = size.width
        val chartHeight = size.height
        drawLine(
            color = trackColor,
            start = Offset(0f, chartHeight - 0.5f),
            end = Offset(width, chartHeight - 0.5f),
            strokeWidth = 1f,
        )
        if (samples.isEmpty() || width <= 0f) return@Canvas

        val slotWidth = width / (VramSlotCount - 1).toFloat()
        val points = samples.mapIndexed { i, value ->
            Offset(
                x = width - (samples.size - 1 - i) * slotWidth,
                y = chartHeight - value.coerceIn(0f, 1f) * chartHeight,
            )
        }
        if (points.size == 1) {
            drawCircle(color = lineColor, radius = 2.dp.toPx(), center = points.first())
            return@Canvas
        }

        val linePath = Path().apply {
            moveTo(points.first().x, points.first().y)
            for (i in 1 until points.size) {
                lineTo(points[i].x, points[i].y)
            }
        }
        val areaPath = Path().apply {
            addPath(linePath)
            lineTo(points.last().x, chartHeight)
            lineTo(points.first().x, chartHeight)
            close()
        }
        drawPath(areaPath, fillColor)
        drawPath(linePath, lineColor, style = Stroke(width = 1.5.dp.toPx()))
    }
}

/** 规则文件下拉：label 用 tools_rule_file，选项是注入进来的 (显示名, 文件名)。 */
@Composable
private fun RuleFileDropdown(
    files: List<Pair<String, String>>,
    selectedFileName: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = files.firstOrNull { it.second == selectedFileName }?.first.orEmpty()

    Box(modifier = modifier) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.tools_rule_file)) },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        // 透明覆盖层：输入框只是外观，点击整块展开下拉
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { expanded = true }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            files.forEach { (displayName, fileName) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = displayName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = {
                        onSelect(fileName)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** 「选图片」按钮 + 已选文件名（不加载缩略图，避免给模块加图片加载依赖）。 */
@Composable
private fun ImagePickerRow(
    image: PromptEnhanceImage?,
    enabled: Boolean,
    onPickImage: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = { onPickImage?.invoke() },
            enabled = enabled && onPickImage != null,
        ) {
            Icon(
                imageVector = Icons.Outlined.Image,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.tools_pick_image))
        }
        if (image != null) {
            Text(
                text = image.displayName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 占位符替换：在解析后的 JSON 上逐值替换，正文里的引号/换行由序列化统一转义。
 *
 * 取值顺序：模型与 mmproj 用工作流自带的 `defaults`；用户输入优先，空了退回 defaults 的
 * positive_prompt。`skill_file_name` / `skill_file_content` 必须填 —— 不填会走 ComfyUI 的
 * 「自动选择」分支并直接抛错。
 */
private fun buildWorkflowJson(
    template: String,
    userPrompt: String,
    ruleFileName: String,
    ruleFileContent: String,
    imageFileName: String?,
    gateway: PromptEnhanceGateway,
): String {
    val root = JSONObject(template)
    val defaults = root.optJSONObject("defaults")
    // 用户输入放最后：正文里万一含 {{xxx}} 也不会被二次替换
    val values = linkedMapOf(
        "skill_model" to defaults?.optString("skill_model").orEmpty(),
        "skill_mmproj" to defaults?.optString("skill_mmproj").orEmpty(),
        "skill_file_name" to ruleFileName,
        "skill_file_content" to ruleFileContent,
        "image_filename" to imageFileName.orEmpty(),
        "positive_prompt" to userPrompt.ifBlank { defaults?.optString("positive_prompt").orEmpty() },
    )
    gateway.substituteInTree(root, values)
    return root.toString()
}

/**
 * 逐字显现：18ms/字基准；>600 字 2 字/拍；>1500 字 4 字/拍。
 *
 * 总时长超过 12 秒时按拍数压缩间隔（硬上限），长文也不会拖到一分钟。
 */
private suspend fun typewrite(text: String, onProgress: (Int) -> Unit) {
    val length = text.length
    if (length == 0) return
    val step = when {
        length > TypeFastThreshold -> 4
        length > TypeSlowThreshold -> 2
        else -> 1
    }
    val beats = (length + step - 1) / step
    val total = beats * TypeBaseDelayMs
    val delayMs = if (total > TypeMaxTotalMs) {
        (TypeMaxTotalMs / beats).coerceAtLeast(1L)
    } else {
        TypeBaseDelayMs
    }
    var shown = 0
    while (shown < length) {
        shown = minOf(length, shown + step)
        onProgress(shown)
        delay(delayMs)
    }
}