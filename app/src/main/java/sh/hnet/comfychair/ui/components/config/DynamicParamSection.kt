package sh.hnet.comfychair.ui.components.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.ui.components.shared.ModelPathText
import sh.hnet.comfychair.workflow.dynamic.DynamicParamRange
import sh.hnet.comfychair.workflow.dynamic.SamplerStageRegistry
import java.math.BigDecimal

/**
 * 「节点参数」分组：通用节点参数引擎的 UI（与「模型选择」「参数」分组并列，可折叠）。
 *
 * 工作流里把某个节点输入的值写成 `{{占位符名}}`，逻辑层扫描 + 推导出控件描述，
 * 这里按 `Number / Text / Bool / Combo` 四类渲染；用户改值调
 * [DynamicParamConfig.onValueChange]（ViewModel 侧改值即持久化，提交时统一替换）。
 *
 * 分组在「没有控件」时整体隐藏；但**有静默失效项时仍然显示**并给出提示条，
 * 否则用户改了工作流却什么都看不到。三处提示见 [IssueRow] 的三个调用点。
 *
 * `registryReady` 来自 [DynamicParamConfig]（= 与 [DynamicParamConfig.fields] 同一次快照的
 * 注册表状态），**不在这里读全局单例**：那样读取不建立订阅，普通 map 变化不会触发重组，
 * 提示条会挂死不消或悄悄消失。
 */
@Composable
fun DynamicParamSection(config: DynamicParamConfig, modifier: Modifier = Modifier) {
    // 扫描只在分组真正渲染时做一次（按 workflowId 缓存；面板关闭后组合被销毁，重开即重扫）
    val issues = remember(config.workflowId) {
        config.workflowId
            .takeIf { it.isNotEmpty() }
            ?.let { WorkflowManager.getWorkflowById(it)?.jsonContent }
            ?.let { DynamicParamIssueScanner.scan(it) }
            ?: DynamicParamIssues()
    }

    val registryReady = config.registryReady

    val hasFields = config.fields.isNotEmpty()
    // 一个控件都没有、又没有需要点明的问题 → 整个分组隐藏
    if (!hasFields && !issues.hasInvalidNames) return

    var expanded by remember(config.workflowId) { mutableStateOf(true) }

    Spacer(modifier = Modifier.height(16.dp))

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.title_node_params),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f)
        )
        if (hasFields) {
            Text(
                text = stringResource(R.string.node_params_count, config.fields.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = stringResource(
                if (expanded) R.string.node_params_collapse else R.string.node_params_expand
            ),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (!expanded) return

    // 问题 3：注册表未就绪 → 类型推导只能退化（设计上"不猜"），必须让用户知道
    if (hasFields && !registryReady) {
        IssueRow(stringResource(R.string.node_params_registry_not_ready), isError = true)
    }

    // 问题 1：占位符名不合法 → 引擎既不索引也不替换，提示哪些名字被跳过了
    if (issues.hasInvalidNames) {
        IssueRow(
            stringResource(
                R.string.node_params_invalid_names,
                issues.invalidNames.size,
                summarize(issues.invalidNames)
            ),
            isError = true
        )
    }

    // 问题 2：已识别的名字被内置参数占用 → 中性说明「这些名字不会在这里再出现一个控件」
    if (hasFields && issues.hasTakenNames) {
        IssueRow(
            stringResource(
                R.string.node_params_taken_names,
                issues.takenNames.size,
                summarize(issues.takenNames)
            ),
            isError = false
        )
    }

    // 采样阶段参数排到前面并按阶段分段：每进入一个新阶段插一行阶段标题
    // （竖叉流的三阶段、Krea2 二采的第二采样器），认不出阶段的参数保持原顺序平铺
    val orderedFields = remember(config.fields) {
        config.fields.sortedBy {
            SamplerStageRegistry.resolve(it.placeholder)?.stage?.index ?: Int.MAX_VALUE
        }
    }
    var lastStageKey: String? = null
    orderedFields.forEach { field ->
        val stageParam = SamplerStageRegistry.resolve(field.placeholder)
        if (stageParam != null && stageParam.stage.key != lastStageKey) {
            lastStageKey = stageParam.stage.key
            SamplerStageHeader(stageParam.stage)
        }
        Spacer(modifier = Modifier.height(12.dp))
        RenderDynamicField(field, config.onValueChange)
    }
}

/**
 * 采样阶段的标题行：把同一阶段的控件归到一段里并标出用途
 * （竖叉流的 comp/struct/detail 对应构图/结构/细节）。
 */
@Composable
private fun SamplerStageHeader(stage: SamplerStageRegistry.Stage) {
    Spacer(modifier = Modifier.height(16.dp))
    val title = stringResource(R.string.sampler_stage_title, stage.index)
    val purpose = samplerStagePurpose(stage.key)?.let { " · $it" } ?: ""
    Text(
        text = "$title$purpose",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth()
    )
}

/** 阶段用途名（认不出的阶段不显示用途）。 */
@Composable
private fun samplerStagePurpose(key: String): String? = when (key) {
    "comp" -> stringResource(R.string.sampler_stage_comp)
    "struct" -> stringResource(R.string.sampler_stage_struct)
    "detail" -> stringResource(R.string.sampler_stage_detail)
    "stage2" -> stringResource(R.string.sampler_stage_stage2)
    "hires" -> stringResource(R.string.sampler_stage_hires)
    "clip" -> stringResource(R.string.sampler_stage_clip)
    "vosr2" -> stringResource(R.string.sampler_stage_vosr2)
    "pe" -> stringResource(R.string.sampler_stage_pe)
    else -> null
}

/** 采样参数的中文标签（"steps" → 「步数」、"steps2" → 「补采 2 步数」）。 */
@Composable
private fun samplerParamLabel(stageParam: SamplerStageRegistry.StageParam): String? {
    // 阶段专属优先：同一个 paramKey 在不同阶段语义不同 —— `on` 在 VOSR2 是「开关放大」、
    // 在 PE 是「开关提示词强化」，`model` / `mmproj` 只属于 PE。按完整占位符先判一次。
    when ("${stageParam.stage.key}_${stageParam.paramKey}") {
        "pe_on" -> return stringResource(R.string.sampler_param_pe_on)
        "pe_model" -> return stringResource(R.string.sampler_param_pe_model)
        "pe_mmproj" -> return stringResource(R.string.sampler_param_pe_mmproj)
        // 第二段采样的手部修复强度：`detail_denoise2` 会被 `detail_` 前缀 + 后缀
        // `denoise2` 误配进竖叉流的「分次补采」标签（「补采 2 去噪强度」），文案完全对不上。
        // 完整占位符名 detail_denoise2 在竖叉流里不存在，此处覆盖不影响该流；
        // 而同工作流的 detail_denoise（面部）与竖叉流同名、语义兼容（都是细节段去噪），
        // 继续走通用「去噪强度」标签，不在本分支覆盖。
        "detail_denoise2" -> return stringResource(R.string.sampler_param_detail_denoise_hand)
    }
    return when (stageParam.paramKey) {
    "steps" -> stringResource(R.string.sampler_param_steps)
    "cfg" -> stringResource(R.string.sampler_param_cfg)
    "denoise" -> stringResource(R.string.sampler_param_denoise)
    "sampler", "sampler_name" -> stringResource(R.string.sampler_param_sampler)
    "scheduler" -> stringResource(R.string.sampler_param_scheduler)
    "scale" -> stringResource(R.string.sampler_param_scale)
    // CLIP 加载器格式门控（prebuilt_01/21 的 clip_gguf）
    "gguf" -> stringResource(R.string.sampler_param_clip_gguf)
    // VOSR2 开关式放大（prebuilt_22/23 的 vosr2_on）
    "on" -> stringResource(R.string.sampler_param_vosr2_on)
    // 分次补采（竖叉流 1~3 次，见 SamplerStageRegistry.REFINE_ROUND_KEYS）：
    // 开关 / 步数 / 强度三个参数名各带轮次后缀
    "on1" -> stringResource(R.string.sampler_param_refine_on1)
    "on2" -> stringResource(R.string.sampler_param_refine_on, 2)
    "on3" -> stringResource(R.string.sampler_param_refine_on, 3)
    "steps1" -> stringResource(R.string.sampler_param_refine_steps, 1)
    "steps2" -> stringResource(R.string.sampler_param_refine_steps, 2)
    "steps3" -> stringResource(R.string.sampler_param_refine_steps, 3)
    "denoise1" -> stringResource(R.string.sampler_param_refine_denoise, 1)
    "denoise2" -> stringResource(R.string.sampler_param_refine_denoise, 2)
    "denoise3" -> stringResource(R.string.sampler_param_refine_denoise, 3)
    else -> null
    }
}

/**
 * 采样参数的**额外说明**（目前只有「预放大倍数」与「CLIP 格式开关」）：
 * - 「预放大倍数」：服务器上限是 8，但那是给 4 倍模型直出 4096×6144 用的，
 *   8 GB 卡填 1 以上大概率爆显存，且它发生在补采总闸之前、关掉补采也救不了；
 * - 「CLIP 格式开关」：`.gguf` 与 fp8 `.safetensors` 的加载器互斥（实测），
 *   选错只会得到一句 `Value not in list`，必须在这里写清什么时候该开。
 * 认不出用途的参数不给说明，避免噪音。
 */
@Composable
private fun samplerParamHint(stageParam: SamplerStageRegistry.StageParam): String? {
    when ("${stageParam.stage.key}_${stageParam.paramKey}") {
        "pe_on" -> return stringResource(R.string.sampler_param_pe_on_hint)
        "pe_model" -> return stringResource(R.string.sampler_param_pe_model_hint)
    }
    return when (stageParam.paramKey) {
    "scale" -> stringResource(R.string.sampler_param_scale_hint)
    "gguf" -> stringResource(R.string.sampler_param_clip_gguf_hint)
    "on" -> stringResource(R.string.sampler_param_vosr2_on_hint)
    else -> null
    }
}

/**
 * App 端固定选项参数的中文标签（参数名级，如模式类占位符 →「出图模式」）。
 * 与逻辑层的固定选项表（DynamicParamTypeResolver.FIXED_COMBO_OPTIONS）配套；
 * 认不出的占位符返回 null，继续显示 `{{占位符}}` 原文。
 */
@Composable
private fun fixedParamLabel(placeholder: String): String? = when (placeholder) {
    "moss_mode" -> stringResource(R.string.dynamic_param_mode_label)
    // 第二段采样（潜空间放大精修）
    "steps2" -> stringResource(R.string.dynamic_param_steps2_label)
    "cfg2" -> stringResource(R.string.dynamic_param_cfg2_label)
    "sampler2" -> stringResource(R.string.dynamic_param_sampler2_label)
    "scheduler2" -> stringResource(R.string.dynamic_param_scheduler2_label)
    // 细节修复的两条独立提示词
    "detail_prompt_face" -> stringResource(R.string.dynamic_param_detail_prompt_face_label)
    "detail_prompt_body" -> stringResource(R.string.dynamic_param_detail_prompt_body_label)
    // 三个 LoRA 槽的开关与权重
    "lora1_on" -> stringResource(R.string.dynamic_param_lora1_on_label)
    "lora1_strength" -> stringResource(R.string.dynamic_param_lora1_strength_label)
    "lora2_on" -> stringResource(R.string.dynamic_param_lora2_on_label)
    "lora2_strength" -> stringResource(R.string.dynamic_param_lora2_strength_label)
    "lora3_on" -> stringResource(R.string.dynamic_param_lora3_on_label)
    "lora3_strength" -> stringResource(R.string.dynamic_param_lora3_strength_label)
    // 惰性放大开关与目标分辨率
    "seedvr2_enabled" -> stringResource(R.string.dynamic_param_seedvr2_enabled_label)
    "seedvr2_size" -> stringResource(R.string.dynamic_param_seedvr2_size_label)
    else -> null
}

/**
 * App 端固定选项的**选项级**中文显示表（值 → 标签；提交时仍用原值）。
 * 在组合期一次算好整张表 —— [FlatOptionDropdown] 的 `displayOf` 是普通 lambda，
 * 不能反过来调 @Composable 的取串函数。没登记的占位符返回空表（显示原值）。
 */
@Composable
private fun fixedComboLabels(placeholder: String): Map<String, String> = when (placeholder) {
    "moss_mode" -> mapOf(
        "moss_25" to stringResource(R.string.mode_opt_25),
        "moss_spec25" to stringResource(R.string.mode_opt_spec25),
        "moss_50" to stringResource(R.string.mode_opt_50),
        "moss_c25" to stringResource(R.string.mode_opt_c25),
        "moss_28" to stringResource(R.string.mode_opt_28)
    )
    else -> emptyMap()
}

/**
 * 数字框下方的说明行：范围 → 参数专属说明 → 越界警告（红色）。
 * 三行都为空时返回 null —— `supportingText = null` 才不占位，返回空 lambda 会留一行空白。
 */
@Composable
private fun numberSupportingText(
    rangeText: String?,
    paramHint: String?,
    violation: DynamicParamRange.OutOfRange?
): (@Composable () -> Unit)? {
    if (rangeText == null && paramHint == null && violation == null) return null
    val outOfRangeText = violation?.let { stringResource(R.string.node_params_out_of_range, it.clamped) }
    return {
        Column {
            rangeText?.let { Text(it) }
            paramHint?.let { Text(it) }
            outOfRangeText?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun RenderDynamicField(field: DynamicParamField, onValueChange: (String, String) -> Unit) {
    val control = field.control
    // 归属节点：逻辑层没保留 tooltip，用 class_type.input_name 当标签；
    // 采样阶段参数已有中文标签与阶段标题，再挂一串 class_type.field 只是噪音，去掉
    val caption = if (SamplerStageRegistry.isStageParam(field.placeholder)) "" else field.nodeLabel
    // 采样阶段参数用中文标签（「步数」），App 端固定选项参数用专属标签，其余保持占位符原文
    val label = SamplerStageRegistry.resolve(field.placeholder)
        ?.let { samplerParamLabel(it) }
        ?: fixedParamLabel(field.placeholder)
        ?: "{{${field.placeholder}}}"
    val onChange: (String) -> Unit = { onValueChange(field.placeholder, it) }

    when (control) {
        is DynamicParamField.Control.Bool -> {
            // 开关也带说明行（目前只有 CLIP 格式门控）：它的取值规则是"和所选文件格式对应"，
            // 光看标签「CLIP 用 GGUF 格式」用户不知道什么时候该开
            val paramHint = SamplerStageRegistry.resolve(field.placeholder)
                ?.let { samplerParamHint(it) }
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = label, style = MaterialTheme.typography.bodyMedium)
                        if (caption.isNotEmpty()) {
                            Text(
                                text = caption,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = control.checked,
                        onCheckedChange = { onChange(it.toString()) }
                    )
                }
                paramHint?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        is DynamicParamField.Control.Number -> {
            NodeCaption(caption)
            // 参数专属提示（目前只有「预放大倍数」的显存陷阱）
            val paramHint = SamplerStageRegistry.resolve(field.placeholder)
                ?.let { samplerParamHint(it) }
            // 越界**只提示、不即时夹紧**：控件内容逐键写回 UiState，边打边夹会把 "0." 这类
            // 中间态吃掉（想输 0.5，打完 "0" 就被改成下界）。真正的夹紧在提交那一刻做
            // （DynamicParamValues → DynamicParamRange），这里让用户先看见"会被取成几"。
            val violation = DynamicParamRange.violationOf(
                field.value, control.isInteger, control.min, control.max
            )
            OutlinedTextField(
                value = field.value,
                onValueChange = onChange,
                label = { Text(label) },
                isError = violation != null,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (control.isInteger) KeyboardType.Number else KeyboardType.Decimal
                ),
                supportingText = numberSupportingText(rangeHint(control), paramHint, violation),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        is DynamicParamField.Control.Text -> {
            NodeCaption(caption)
            OutlinedTextField(
                value = field.value,
                onValueChange = onChange,
                label = { Text(label) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = !control.multiline,
                minLines = if (control.multiline) 2 else 1,
                maxLines = if (control.multiline) 6 else 1
            )
        }

        is DynamicParamField.Control.Combo -> {
            NodeCaption(caption)
            if (control.options.isEmpty()) {
                // 问题 3：没有选项可给 —— 给个禁用框 + 说明，而不是让用户瞎填后吃 400
                OutlinedTextField(
                    value = field.value,
                    onValueChange = {},
                    readOnly = true,
                    enabled = false,
                    label = { Text(label) },
                    supportingText = { Text(stringResource(R.string.node_params_combo_not_ready)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                // 问题 6：有选项但值为空（服务器 default=null）→ 明确的未选态 + 必填提示，
                // 而不是一个空框（直接提交会吃 Value not in list）
                // App 端固定选项的中文显示表（提交的仍是标识符原值）；空表 = 无映射，原样显示
                val displayMap = fixedComboLabels(field.placeholder)
                FlatOptionDropdown(
                    label = label,
                    selectedValue = field.value,
                    options = control.options,
                    // 与逻辑层同一判定：空值对下拉必然非法（除非选项里真有 ""）
                    isMissingSelection = field.value.isEmpty() && "" !in control.options,
                    displayOf = { value -> displayMap[value] ?: value },
                    onValueChange = onChange
                )
            }
        }
    }
}

/**
 * 通用 COMBO 的下拉：**扁平、保持服务器原序**（问题 5）。
 *
 * 不能复用「模型选择」的 `ModelDropdown`：它按 `/` 把选项切成目录层级、并在每层按字母序重排，
 * 那是为模型文件路径设计的。通用 COMBO 的选项**不是文件路径**，且顺序可能带语义
 * （如 `sampler_name` 的采样器顺序、`mode` 的 `standard / 4-step LoRA / 8-step LoRA`），
 * 被重排或被折进目录树都是错的。
 *
 * [displayOf] 只影响**显示**（选中框与菜单项），[onValueChange] 收到的仍是选项原值 ——
 * App 端固定选项用它把标识符换成中文标签。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlatOptionDropdown(
    label: String,
    selectedValue: String,
    options: List<String>,
    isMissingSelection: Boolean,
    displayOf: (String) -> String = { it },
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = displayOf(selectedValue),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            isError = isMissingSelection,
            label = { Text(label) },
            supportingText = if (isMissingSelection) {
                { Text(stringResource(R.string.node_params_combo_unselected)) }
            } else {
                null
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { ModelPathText(displayOf(option)) },
                    onClick = {
                        onValueChange(option)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}

@Composable
private fun NodeCaption(caption: String) {
    if (caption.isEmpty()) return
    Text(
        text = caption,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 2.dp)
    )
}

/**
 * 一条可见的提示。[isError] 区分「必须处理」（警告图标 + 错误色）与「只是告知，无需处理」
 * （信息图标 + 常规色）—— 语义轻的提示用 `Icons.Default.Warning` 会过重。
 */
@Composable
private fun IssueRow(text: String, isError: Boolean) {
    val color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Icon(
            imageVector = if (isError) Icons.Default.Warning else Icons.Outlined.Info,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/**
 * `min – max · step x` 形式的范围提示；服务器没给的部分不显示（不猜）。
 */
@Composable
private fun rangeHint(control: DynamicParamField.Control.Number): String? {
    val min = control.min
    val max = control.max
    val step = control.step
    val range = when {
        min != null && max != null ->
            stringResource(R.string.node_editor_range_min_max, formatNumber(min), formatNumber(max))

        min != null -> stringResource(R.string.node_params_min_only, formatNumber(min))
        max != null -> stringResource(R.string.node_params_max_only, formatNumber(max))
        else -> null
    }
    val stepText = step?.takeIf { it > 0.0 }?.let { stringResource(R.string.node_params_step, formatNumber(it)) }

    return when {
        range != null && stepText != null -> "$range · $stepText"
        else -> range ?: stepText
    }
}

/** 去多余小数位：`1.0` → `1`，`0.1` → `0.1`，`1.0E-5` → `0.00001` */
private fun formatNumber(value: Double): String =
    BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

/** 名字列表拼成一行，最多 3 个，其余用 `+N` 概括，避免提示条撑爆 */
@Composable
private fun summarize(names: List<String>): String {
    val head = names.take(MAX_NAMES_IN_HINT).joinToString(", ") { "{{$it}}" }
    return if (names.size <= MAX_NAMES_IN_HINT) {
        head
    } else {
        stringResource(R.string.node_params_names_more, head, names.size - MAX_NAMES_IN_HINT)
    }
}

private const val MAX_NAMES_IN_HINT = 3
