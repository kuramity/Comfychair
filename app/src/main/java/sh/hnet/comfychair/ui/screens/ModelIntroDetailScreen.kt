package sh.hnet.comfychair.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.CommunityParam
import sh.hnet.comfychair.model.LoraEffectTable
import sh.hnet.comfychair.model.LoraWeightSuggestion
import sh.hnet.comfychair.model.LoraWeightShape
import sh.hnet.comfychair.model.ModelIntro
import sh.hnet.comfychair.model.ModelIntroTable
import sh.hnet.comfychair.model.splitIntroText
import sh.hnet.comfychair.ui.components.ripple.rippleFeedback
import sh.hnet.comfychair.ui.theme.Dimensions
import sh.hnet.comfychair.ui.util.withStableKeys
import kotlin.math.roundToInt

/**
 * 模型介绍详情页（二级页面，v4.17）。
 *
 * 从介绍抽屉（[sh.hnet.comfychair.ui.components.shared.ModelIntroSheet]）的
 * 「查看完整详情」进入，按 20 个候选区块完整呈现一条介绍；字段为空的区块整块不渲染
 * （列表见各区块组件的 KDoc）。整页 LazyColumn，每个区块一个 item（带业务 key 与
 * contentType），折叠状态沉到各区块组件内部（rememberSaveable），不做页面级展开集合。
 *
 * 数据来自介绍表（[ModelIntroTable]），离线可看；「一键应用参数」因参数键（中文）与
 * 工作流占位符（英文带阶段前缀）之间没有可靠映射而降级为「复制推荐参数」——宁可少
 * 功能，不做假功能（结论依据见数据类 KDoc 与设计记录）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelIntroDetailScreen(file: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var intro by remember { mutableStateOf<ModelIntro?>(null) }

    // 与浏览页同一装载纪律（P2-17）：513KB 的表不许在主线程解析；ensureLoaded 幂等，
    // 已装好时直接返回，所以从抽屉进来时这里不会重复读盘
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { ModelIntroTable.ensureLoaded(context) }
        intro = ModelIntroTable.lookup(file)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.model_intro_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.button_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        val loaded = intro
        if (loaded == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.model_intro_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }

        DetailContent(intro = loaded, modifier = Modifier.padding(padding))
    }
}

/** 详情页主体（拆出来便于阅读：装载/空态在上面的入口，区块编排在这里）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailContent(intro: ModelIntro, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        // 卡片间距 12dp；语义大组（参数组/社区组…）的第一个 item 再自带 top 12dp，
        // 合起来组间 = 24dp，三层关系「内边距 16 > 卡间 12 < 组间 24」成立
        verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing12),
        contentPadding = PaddingValues(
            start = Dimensions.Spacing16,
            end = Dimensions.Spacing16,
            top = Dimensions.Spacing16,
            bottom = Dimensions.Spacing24,
        )
    ) {
        // ---- 头部区（坐背景上，不进卡）----
        item(key = "header", contentType = "header") {
            DetailHeader(intro = intro)
        }

        // ---- 组 1：介绍卡（简介 + 效果）----
        item(key = "intro_card", contentType = "card") {
            IntroCard(intro = intro, modifier = Modifier.padding(top = Dimensions.Spacing12))
        }

        // ---- 组 2：权重 + 参数 + 复制 ----
        if (intro.loraWeight != null) {
            item(key = "lora_weight", contentType = "card") {
                LoraWeightCard(lw = intro.loraWeight!!, modifier = Modifier.padding(top = Dimensions.Spacing12))
            }
        }
        if (intro.authorParams.isNotEmpty()) {
            item(key = "author_params", contentType = "card") {
                ParamCard(
                    title = stringResource(R.string.model_intro_author_params),
                    params = intro.authorParams,
                    modifier = Modifier.padding(top = Dimensions.Spacing12)
                )
            }
        }
        if (intro.params.isNotEmpty() && !intro.missing) {
            item(key = "workflow_params", contentType = "card") {
                ParamCard(
                    title = stringResource(R.string.model_intro_params),
                    params = intro.params,
                    modifier = Modifier.padding(top = Dimensions.Spacing12)
                )
            }
        }
        if (intro.authorParams.isNotEmpty() || (intro.params.isNotEmpty() && !intro.missing)) {
            item(key = "copy_action", contentType = "action") {
                CopyParamsButton(intro = intro, modifier = Modifier.padding(top = Dimensions.Spacing12))
            }
        }

        // ---- 组 3：触发词 + 社区 ----
        if (intro.tags.isNotEmpty()) {
            item(key = "trigger_tags", contentType = "card") {
                TagsCard(intro = intro, modifier = Modifier.padding(top = Dimensions.Spacing12))
            }
        }
        if (intro.communityParams.isNotEmpty() && (intro.communitySamples?.withParams ?: 0) > 0) {
            item(key = "community_params", contentType = "community") {
                CommunityParamsCard(
                    params = intro.communityParams,
                    withParams = intro.communitySamples!!.withParams,
                    modifier = Modifier.padding(top = Dimensions.Spacing12)
                )
            }
        }
        intro.communityPrompt?.positive?.let { positive ->
            item(key = "community_positive", contentType = "community") {
                CommunityPromptCard(
                    title = stringResource(R.string.model_intro_community_prompt),
                    text = positive,
                    modifier = Modifier.padding(top = Dimensions.Spacing12)
                )
            }
        }
        intro.communityPrompt?.negative?.let { negative ->
            item(key = "community_negative", contentType = "community") {
                CommunityPromptCard(
                    title = stringResource(R.string.model_intro_negative_prompt),
                    text = negative,
                    modifier = Modifier.padding(top = Dimensions.Spacing12)
                )
            }
        }

        // ---- 组 4：使用信息 ----
        intro.companions?.let { companions ->
            item(key = "companions", contentType = "card") {
                PlainTextCard(
                    title = stringResource(R.string.model_intro_companions),
                    text = companions,
                    modifier = Modifier.padding(top = Dimensions.Spacing12)
                )
            }
        }
        if (intro.usedIn.isNotEmpty() && !intro.missing) {
            item(key = "used_in", contentType = "card") {
                PlainTextCard(
                    title = stringResource(R.string.model_intro_used_in),
                    text = intro.usedIn.joinToString("、"),
                    modifier = Modifier.padding(top = Dimensions.Spacing12)
                )
            }
        }

        // ---- 组 5：作者原文 ----
        intro.introOriginal?.let { original ->
            item(key = "original_text", contentType = "original") {
                OriginalTextCard(text = original, modifier = Modifier.padding(top = Dimensions.Spacing12))
            }
        }

        // ---- 组 6：归档卡（路径恒显示；置信度 / 缺介绍占位有值才显示）----
        item(key = "archive", contentType = "archive") {
            ArchiveCard(intro = intro, modifier = Modifier.padding(top = Dimensions.Spacing12))
        }
    }
}

// ============================================================
// 头部区（区块 1-4：模型名 / 类型徽章 / 元信息行 / 风格 chips）
// ============================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailHeader(intro: ModelIntro) {
    val sourceText = sourceLabel(intro.source)
        ?.let { stringResource(R.string.model_intro_source_from, it) }

    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing8)) {
        Text(
            text = intro.name ?: intro.file,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface
        )

        // 文件已移除：先说这件事，再说它曾经是什么（与抽屉同一文案键）
        if (intro.missing) {
            Text(
                text = stringResource(R.string.model_intro_missing_notice),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        // 类型徽章：恒显示（数据侧的中文类型，「大模型（Checkpoint）」）
        intro.type?.let { type ->
            Surface(
                shape = RoundedCornerShape(Dimensions.Radius8),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Text(
                    text = type,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = Dimensions.Spacing8, vertical = Dimensions.Spacing4)
                )
            }
        }

        // 元信息行：version · base · source（缺项跳过，全缺整行不渲染）
        val metaLine = listOfNotNull(intro.version, intro.base, sourceText).joinToString(" · ")
        if (metaLine.isNotBlank()) {
            Text(
                text = metaLine,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 风格标签 chips：local_positioning 按「+」拆（「动漫插画 + 真实皮肤质感」→ 两枚）
        val styleTags = remember(intro.localPositioning) { splitStyleTags(intro.localPositioning) }
        if (styleTags.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing8),
                verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing4)
            ) {
                styleTags.forEach { tag ->
                    Surface(
                        shape = RoundedCornerShape(Dimensions.Radius8),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ) {
                        Text(
                            text = tag,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = Dimensions.Spacing8, vertical = Dimensions.Spacing4)
                        )
                    }
                }
            }
        }
    }
}

// ============================================================
// 介绍卡（区块 5-6：简介 + 效果）—— SelectionContainer 作用域
// ============================================================

@Composable
private fun IntroCard(intro: ModelIntro, modifier: Modifier = Modifier) {
    SelectionContainer {
        DetailCard(modifier = modifier) {
            // 简介：恒显示（表未收录时上层根本进不来这个页面，因此必有数据）
            Text(
                text = intro.summary ?: stringResource(R.string.model_intro_none),
                style = longBodyStyle(),
                color = MaterialTheme.colorScheme.onSurface
            )
            // 效果：有值才显示；数据里自带「效果：」前缀，剥掉（标题就是它，重复读一遍很难受）。
            // 条目自身没有 effect 时退到独立维护的效果档案里兜底（与抽屉同一兜底链）
            (intro.effect ?: LoraEffectTable.lookup(intro.file))?.let { effect ->
                Spacer(Modifier.height(Dimensions.Spacing8))
                SectionTitle(stringResource(R.string.model_intro_effect))
                Text(
                    text = stripEffectPrefix(effect),
                    style = longBodyStyle(),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/** 长文样式（简介/效果）：bodyLarge + 行高 28sp（16×1.75，中文最佳可读区间）。 */
@Composable
private fun longBodyStyle() = MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp)

// ============================================================
// LoRA 权重卡（区块 10：五种形态，绝不把单边界画成区间）
// ============================================================

@Composable
private fun LoraWeightCard(lw: LoraWeightSuggestion, modifier: Modifier = Modifier) {
    DetailCard(modifier = modifier) {
        SectionTitle(stringResource(R.string.model_intro_lora_weight))
        // 权重条静态（无入场动画，任务书明令）
        when (lw.shape) {
            LoraWeightShape.RANGE, LoraWeightShape.RANGE_WITH_DEFAULT ->
                WeightBarCanvas(
                    lw = lw,
                    highlightFrom = lw.min,
                    highlightTo = lw.max,
                    fadeFrom = null,
                )
            LoraWeightShape.MIN_ONLY ->
                WeightBarCanvas(lw = lw, highlightFrom = null, highlightTo = null, fadeFrom = lw.min)
            LoraWeightShape.MAX_ONLY ->
                WeightBarCanvas(
                    lw = lw,
                    highlightFrom = null,
                    highlightTo = null,
                    fadeFrom = lw.max,
                    fadeToRight = false
                )
            LoraWeightShape.DEFAULT_ONLY -> DefaultOnlyWeightBar(lw = lw)
            LoraWeightShape.EMPTY -> {}
        }
        Text(
            text = weightCaption(lw),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 权重条画法参数化：区间高亮 / 单锚点渐隐（向右 = 下限，向左 = 上限）共用一支画笔。 */
@Composable
private fun WeightBarCanvas(
    lw: LoraWeightSuggestion,
    highlightFrom: Double?,
    highlightTo: Double?,
    fadeFrom: Double?,
    fadeToRight: Boolean = true,
) {
    val domain = remember(lw) { loraWeightDomain(lw) }
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.fillMaxWidth().height(24.dp)) {
        val lo = domain.start
        val span = domain.endInclusive - domain.start
        fun x(v: Double): Float = (((v - lo) / span) * size.width).toFloat()
        val trackH = 8.dp.toPx()
        val trackTop = center.y - trackH / 2

        // 轨道（中性底）
        drawRoundRect(
            color = trackColor,
            topLeft = Offset(0f, trackTop),
            size = Size(size.width, trackH),
            cornerRadius = CornerRadius(trackH / 2, trackH / 2),
        )
        // 高亮区间（min→max）：实心 primary 段
        if (highlightFrom != null && highlightTo != null && highlightTo > highlightFrom) {
            drawRoundRect(
                color = accent,
                topLeft = Offset(x(highlightFrom), trackTop),
                size = Size(x(highlightTo) - x(highlightFrom), trackH),
                cornerRadius = CornerRadius(trackH / 2, trackH / 2),
            )
        }
        // 单边界渐隐：「不低于 x」向右渐淡、「不超过 x」向左渐淡——不画实心区间，
        // 否则会把开放边界画成封闭区间（误导用户以为另一侧不能调）
        if (fadeFrom != null) {
            val brush = if (fadeToRight) {
                Brush.horizontalGradient(
                    listOf(accent.copy(alpha = 0.45f), accent.copy(alpha = 0.05f)),
                    startX = x(fadeFrom),
                    endX = size.width,
                )
            } else {
                Brush.horizontalGradient(
                    listOf(accent.copy(alpha = 0.05f), accent.copy(alpha = 0.45f)),
                    startX = 0f,
                    endX = x(fadeFrom),
                )
            }
            drawRoundRect(
                brush = brush,
                topLeft = Offset(if (fadeToRight) x(fadeFrom) else 0f, trackTop),
                size = Size(
                    if (fadeToRight) size.width - x(fadeFrom) else x(fadeFrom),
                    trackH
                ),
                cornerRadius = CornerRadius(trackH / 2, trackH / 2),
            )
        }
        // 锚点：min/max 小点，default 大点（区间态下 default 叠画在区间条上）
        val anchorR = 3.dp.toPx()
        lw.min?.let { drawCircle(accent, anchorR, Offset(x(it), center.y)) }
        lw.max?.let { drawCircle(accent, anchorR, Offset(x(it), center.y)) }
        lw.default?.let { drawCircle(accent, anchorR * 4f / 3f, Offset(x(it), center.y)) }
    }
}

/** 只有 default 的形态：中性轨道 + 单点标记（无渐隐无区间，「推荐 0.8」是一个点不是一段）。 */
@Composable
private fun DefaultOnlyWeightBar(lw: LoraWeightSuggestion) {
    val domain = remember(lw) { loraWeightDomain(lw) }
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.fillMaxWidth().height(24.dp)) {
        val span = domain.endInclusive - domain.start
        val x = (((lw.default!! - domain.start) / span) * size.width).toFloat()
        val trackH = 8.dp.toPx()
        val trackTop = center.y - trackH / 2
        drawRoundRect(
            color = trackColor,
            topLeft = Offset(0f, trackTop),
            size = Size(size.width, trackH),
            cornerRadius = CornerRadius(trackH / 2, trackH / 2),
        )
        drawCircle(accent, radius = 5.dp.toPx(), center = Offset(x, center.y))
    }
}

/** 权重条下方的文案（形态决定措辞，绝不把单边界说成区间）。 */
@Composable
private fun weightCaption(lw: LoraWeightSuggestion): String = when (lw.shape) {
    LoraWeightShape.RANGE ->
        stringResource(R.string.model_intro_lora_weight_range, formatWeight(lw.min!!), formatWeight(lw.max!!))
    LoraWeightShape.RANGE_WITH_DEFAULT ->
        stringResource(
            R.string.model_intro_lora_weight_range_default,
            formatWeight(lw.min!!), formatWeight(lw.max!!), formatWeight(lw.default!!)
        )
    LoraWeightShape.MIN_ONLY ->
        stringResource(R.string.model_intro_lora_weight_min_only, formatWeight(lw.min!!))
    LoraWeightShape.MAX_ONLY ->
        stringResource(R.string.model_intro_lora_weight_max_only, formatWeight(lw.max!!))
    LoraWeightShape.DEFAULT_ONLY ->
        stringResource(R.string.model_intro_lora_weight_default_only, formatWeight(lw.default!!))
    LoraWeightShape.EMPTY -> ""
}

// ============================================================
// 参数卡（区块 7-8：作者推荐 / 你的工作流）—— SelectionContainer 作用域
// ============================================================

@Composable
private fun ParamCard(title: String, params: Map<String, String>, modifier: Modifier = Modifier) {
    SelectionContainer {
        DetailCard(modifier = modifier) {
            SectionTitle(title)
            params.forEach { (key, value) -> ParamRow(key = key, value = value) }
        }
    }
}

/**
 * 两列键值行：键列 min 72dp 左对齐、值列 weight(1f) 左对齐（不右对齐键——
 * 键值间隙会忽大忽小）。值用 bodyMedium + tnum（表格数字对齐）+ onSurface（关键信息）。
 */
@Composable
private fun ParamRow(key: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = key,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 72.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

// ============================================================
// 复制按钮（区块 9：「一键应用」的降级形态，理由见文件头 KDoc）
// ============================================================

@Composable
private fun CopyParamsButton(intro: ModelIntro, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val authorLabel = stringResource(R.string.model_intro_author_params)
    val workflowLabel = stringResource(R.string.model_intro_params)
    val copiedLabel = stringResource(R.string.model_intro_copied)
    val copyLabel = stringResource(R.string.model_intro_copy_params)
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    val interactionSource = remember { MutableInteractionSource() }
    Button(
        onClick = {
            buildParamsCopyText(intro, authorLabel, workflowLabel)?.let { text ->
                clipboard.setText(AnnotatedString(text))
                copied = true
            }
        },
        shape = RoundedCornerShape(Dimensions.Radius28),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ),
        interactionSource = interactionSource,
        modifier = modifier
            .fillMaxWidth()
            .height(Dimensions.ButtonHeight)
            // 按压缩放 0.96 复用统一规格；皮肤要素（渐变/白边/辉光/点阵）全关，
            // 这里只要 graphicsLayer 里的 scale 手感（与生成主按钮同一常量）
            .rippleFeedback(
                interactionSource = interactionSource,
                shape = RoundedCornerShape(Dimensions.Radius28),
                showBorder = false,
                showHalo = false,
                showTouchGlow = false,
                showDots = false,
            )
    ) {
        Text(
            text = if (copied) copiedLabel else copyLabel,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = Dimensions.ButtonTextSize)
        )
    }
}

// ============================================================
// 触发词卡（区块 11）与通用文本卡（区块 16-17）
// ============================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsCard(intro: ModelIntro, modifier: Modifier = Modifier) {
    DetailCard(modifier = modifier) {
        SectionTitle(stringResource(R.string.model_intro_tags))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing8),
            verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing4)
        ) {
            intro.tags.forEach { tag ->
                Surface(
                    shape = RoundedCornerShape(Dimensions.Radius8),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    Text(
                        text = tag,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = Dimensions.Spacing8, vertical = Dimensions.Spacing4)
                    )
                }
            }
        }
    }
}

@Composable
private fun PlainTextCard(title: String, text: String, modifier: Modifier = Modifier) {
    // 不包 SelectionContainer：规范限定可选中复制只覆盖介绍卡/参数卡/社区卡三块
    DetailCard(modifier = modifier) {
        SectionTitle(title)
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// ============================================================
// 社区卡（区块 12-14：降级三重信号 = 低一级容器 + 小一号字 + 固定前缀）
// ============================================================

@Composable
private fun CommunityParamsCard(
    params: Map<String, CommunityParam>,
    withParams: Int,
    modifier: Modifier = Modifier,
) {
    SelectionContainer {
        DetailCard(modifier = modifier, container = MaterialTheme.colorScheme.surfaceContainerLow) {
            CommunityNotice()
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(stringResource(R.string.model_intro_community_params))
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.model_intro_community_base, withParams),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            params.forEach { (key, item) ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = communityKeyLabel(key),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.widthIn(min = 72.dp)
                    )
                    Text(
                        text = item.value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    // count < 3 不显示数字：两三张图撑不起「常用」二字
                    if (item.count >= 3) {
                        Text(
                            text = stringResource(R.string.model_intro_community_count, item.count),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** 社区提示词卡（正向/负向共用一套）：长文，默认 4 行，展开/收起状态自持（rememberSaveable）。 */
@Composable
private fun CommunityPromptCard(title: String, text: String, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    SelectionContainer {
        DetailCard(modifier = modifier, container = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(
                modifier = Modifier.animateContentSize(tween(200)),
                verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing8)
            ) {
                CommunityNotice()
                SectionTitle(title)
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (expanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(onClick = { expanded = !expanded }) {
                    Text(stringResource(if (expanded) R.string.model_intro_collapse else R.string.model_intro_expand))
                }
            }
        }
    }
}

/** 社区卡固定前缀（降级不靠颜色的第三重信号）。 */
@Composable
private fun CommunityNotice() {
    Text(
        text = stringResource(R.string.model_intro_community_notice),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

// ============================================================
// 作者原文卡（区块 15）—— 结构照搬 ModelIntroUi.kt，只升级样式
// ============================================================

@Composable
private fun OriginalTextCard(text: String, modifier: Modifier = Modifier) {
    DetailCard(modifier = modifier, container = MaterialTheme.colorScheme.surfaceContainerLow) {
        SectionTitle(stringResource(R.string.model_intro_original))
        // 独立滚动框：原文动辄几千字，跟整页一起滚会把参数区顶得老远。固定高度 + 自己的
        // 滚动（外层 LazyColumn 不能嵌无限高度的滚动容器，item 内固定高度没问题）；整段塞
        // 一个 Text 最长 5 万多字符单次排版慢到肉眼可见，切成小片段逐段排版（拼接恒等于原文）。
        val chunks = remember(text) { splitIntroText(text) }
        val keyedChunks = remember(chunks) { chunks.withStableKeys { it } }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .border(
                    Dimensions.Stroke1,
                    MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(Dimensions.Radius8)
                )
        ) {
            LazyColumn(modifier = Modifier.fillMaxWidth().padding(Dimensions.Spacing12)) {
                items(keyedChunks, key = { it.key }, contentType = { "original_chunk" }) { item ->
                    Text(
                        text = item.value,
                        // 长文行高覆盖：12sp 正文 × 1.75 = 21sp（中文可读性，字号保持）
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 21.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ============================================================
// 归档卡（区块 18-20：路径 / 置信度 / 缺介绍占位）
// ============================================================

@Composable
private fun ArchiveCard(intro: ModelIntro, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(Dimensions.Radius16)
            )
            .padding(Dimensions.Spacing16),
        verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing8)
    ) {
        // 路径：恒显示（列表里只给文件名，这里给能对着文件系统找的位置）
        Text(
            text = stringResource(R.string.model_intro_path),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "models/" + (intro.rel ?: intro.file),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // 置信度说明：有值才显示——它是在告诉用户"这条介绍可能认错了模型"
        intro.confidenceNote?.let { note ->
            Text(
                text = stringResource(R.string.model_intro_confidence_note),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // 缺介绍占位：数据侧标记只有套话没有完整介绍，说明白而不是硬渲染空架子
        if (intro.introMissing) {
            Text(
                text = stringResource(R.string.model_intro_intro_missing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // 来源链接：有值才显示（浏览原文/下载页用）
        intro.url?.let { url ->
            Text(
                text = url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// ============================================================
// 通用容器与标题
// ============================================================

/**
 * 详情页标准卡：surfaceContainerHighest（M3 Card 默认）底、16dp 圆角、16dp 内边距、
 * 条目间距 8dp。社区卡/原文卡按色槽表传各自容器色（归档卡不是卡，是 surfaceVariant 圆角块）。
 */
@Composable
private fun DetailCard(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Column(
            modifier = Modifier.padding(Dimensions.Spacing16),
            verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing8),
            content = content
        )
    }
}

/** 区块标题：titleMedium + onSurface（中性色，不用 primary——见视觉规范）。 */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
}

// ============================================================
// 纯逻辑（internal 供 JVM 单测钉住行为）
// ============================================================

/** 数据来源的展示名（unknown 与空返回 null，整块不显示——宁可少一行不给用户看"未知"）。 */
internal fun sourceLabel(source: String?): String? = when (source?.trim()?.lowercase()) {
    "civitai" -> "Civitai"
    "huggingface" -> "HuggingFace"
    else -> null
}

/** 剥掉效果段自带的「效果：」前缀（数据里自带，标题已经写明 What it does）。 */
internal fun stripEffectPrefix(effect: String): String =
    effect.trim().removePrefix("效果：").removePrefix("效果:").trim()

/** 风格定位拆 chips：「+」与「、」都是分隔符（数据侧人工概括的惯用连接符）。 */
internal fun splitStyleTags(positioning: String?): List<String> {
    if (positioning.isNullOrBlank()) return emptyList()
    return positioning.split('+', '、')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}

/** 权重数值的显示格式：整数省小数（1.0→1）、其余一位小数（0.7→0.7），负值照实。 */
internal fun formatWeight(v: Double): String {
    val rounded = v.roundToInt().toDouble()
    return if (v == rounded) rounded.toInt().toString() else v.toString()
}

/**
 * 权重条的归一化域：至少覆盖常规区间 [0,1]，数据越界（负权重 / 超 1）时扩展到数据端点。
 * 纯函数，单测钉住「单边界形态的锚点必须落在域内」。
 */
internal fun loraWeightDomain(lw: LoraWeightSuggestion): ClosedFloatingPointRange<Double> {
    val values = buildList {
        add(0.0)
        add(1.0)
        lw.min?.let { add(it) }
        lw.max?.let { add(it) }
        lw.default?.let { add(it) }
    }
    return values.min()..values.max()
}

/** 社区参数键的中文标签（数据键是英文枚举，界面按固定映射显示）。 */
internal fun communityKeyLabel(key: String): String = when (key) {
    "sampler" -> "采样器"
    "cfg" -> "CFG"
    "steps" -> "步数"
    "scheduler" -> "调度器"
    else -> key
}

/**
 * 复制到剪贴板的参数文本（纯函数，供单测）。
 *
 * 两块参数并列、分节标注（作者推荐 / 你的工作流实际值），冲突时并列不合并；
 * 只有一块时省掉分节标题，全空返回 null（按钮根本不该出现，防御到 UI 之外一层）。
 */
internal fun buildParamsCopyText(intro: ModelIntro, authorLabel: String, workflowLabel: String): String? {
    val sections = ArrayList<String>(2)
    if (intro.authorParams.isNotEmpty()) {
        sections.add(authorLabel + "\n" + intro.authorParams.entries.joinToString("\n") { "${it.key}：${it.value}" })
    }
    if (intro.params.isNotEmpty() && !intro.missing) {
        sections.add(workflowLabel + "\n" + intro.params.entries.joinToString("\n") { "${it.key}：${it.value}" })
    }
    return sections.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
}
