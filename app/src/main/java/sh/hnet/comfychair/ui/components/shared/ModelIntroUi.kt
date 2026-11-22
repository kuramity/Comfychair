package sh.hnet.comfychair.ui.components.shared

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.LoraEffectTable
import sh.hnet.comfychair.model.ModelIntro
import sh.hnet.comfychair.model.splitIntroText
import sh.hnet.comfychair.ui.screens.sourceLabel
import sh.hnet.comfychair.ui.util.withStableKeys

/**
 * 副标题内容：底模 · 类型 · 作者（只拼有值的部分）。
 *
 * @param missingBadge 本地文件已移除时追加的标记文案（传空串表示不标）；
 *        标记走参数传而不是写死在这里，是因为本函数要保持纯函数、可被非 Composable 复用
 */
internal fun modelSubtitle(intro: ModelIntro?, missingBadge: String = ""): String? {
    if (intro == null) return null
    val parts = ArrayList<String>(3)
    intro.base?.let { parts.add(it) }
    intro.type?.let { parts.add(it) }
    intro.author?.let { parts.add("作者 $it") }
    if (parts.isEmpty()) {
        // 没有结构化字段时，退一步用作者推荐 tag 让用户知道这模型是干嘛的
        if (intro.tags.isNotEmpty()) return "推荐 tag：" + intro.tags.take(3).joinToString("、")
        // tag 也没有时，至少把「已移除」这个事实透出去（否则列表项看起来就是空白的）
        return if (intro.missing && missingBadge.isNotEmpty()) missingBadge else null
    }
    val text = parts.joinToString(" · ")
    return if (intro.missing && missingBadge.isNotEmpty()) "$text · $missingBadge" else text
}

/**
 * 模型介绍详情（**底部抽屉**）。
 *
 * 为什么不是居中弹窗：介绍内容可能很长（作者原文可达数百字），而实测发现 AlertDialog
 * 的 `text` 槽位里 `heightIn` 不生效、内容超出后滚不动。底部抽屉天然贴着屏幕底边、
 * 高度约束正确，长内容正常上下滑动，还能拖拽高度、下滑关闭。
 *
 * 内容全部来自预生成的中文数据（见 [sh.hnet.comfychair.model.ModelIntroTable]），这里只排版。
 *
 * v4.17 视觉统一：与详情页（ModelIntroDetailScreen）共用同一套排版语言——
 * 区块标题 titleMedium + onSurface（中性色）、长文行高 ×1.75（中文可读性）、
 * 参数两列键值 + tnum。抽屉保留为快速预览，完整 20 区块版走 [onOpenDetail]。
 *
 * @param onOpenDetail 「查看完整详情」回调（进二级页面）；null 时不显示该入口
 *        （保持旧签名兼容——不接导航的调用点行为不变）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelIntroSheet(intro: ModelIntro, onDismiss: () -> Unit, onOpenDetail: (() -> Unit)? = null) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        // 整页可选中复制：用户看到推荐参数 / 触发词 / 作者原文时最想做的是"抄下来"，
        // 原先 Text 不可选，只能靠系统截图识别（实测反馈要长按可复制）。
        // 包在最外层一次即可覆盖全部子文本。
        SelectionContainer {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, bottom = 28.dp)
            ) {
            Text(
                text = intro.name ?: intro.file,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            // 文件已移除：先说这件事，再说它曾经是什么。用户照着参数配了半天、
            // 最后才发现本地根本没这个文件，是这个页面最容易踩的坑。
            if (intro.missing) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.model_intro_missing_notice),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = intro.summary ?: stringResource(R.string.model_intro_none),
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.5.sp)
            )

            // 分类：数据侧的中文枚举（大模型/扩散模型/LoRA/…），未知新类型原样显示，
            // 空值整行不显示（kindLabel 返回 null）
            intro.kindLabel()?.let {
                Spacer(Modifier.height(12.dp))
                SectionTitle(stringResource(R.string.model_intro_kind))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            // 「实际效果」排在推荐参数前面：用户最先想知道的是"加上会怎样"。
            // 条目自身没有 effect 时退到独立维护的效果档案里兜底；两处都有值时以条目为准
            // （数据构建链已把档案内容合并进 effect，兜底只服务没合并上的条目）
            (intro.effect ?: LoraEffectTable.lookup(intro.file))?.let {
                Spacer(Modifier.height(12.dp))
                SectionTitle(stringResource(R.string.model_intro_effect))
                Text(it, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.5.sp))
            }

            // 文件已移除的条目不再显示「实际使用参数」——那是指向一个已经删掉的模型的
            // 幽灵推荐，照着配怎么都跑不通（下面的 usedIn 同理）
            if (intro.params.isNotEmpty() && !intro.missing) {
                Spacer(Modifier.height(12.dp))
                SectionTitle(stringResource(R.string.model_intro_params))
                KeyValueRows(intro.params)
            }
            // 作者在简介里写的推荐设置（抽得到才显示）。与上面的「实际使用」分开两块：
            // 一个是作者建议、一个是你自己工作流的实际值，混在一起用户分不清该信哪个
            if (intro.authorParams.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                SectionTitle(stringResource(R.string.model_intro_author_params))
                KeyValueRows(intro.authorParams)
            }
            intro.companions?.let {
                Spacer(Modifier.height(12.dp))
                SectionTitle(stringResource(R.string.model_intro_companions))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            if (intro.tags.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                SectionTitle(stringResource(R.string.model_intro_tags))
                Text(intro.tags.joinToString("、"), style = MaterialTheme.typography.bodySmall)
            }
            if (intro.usedIn.isNotEmpty() && !intro.missing) {
                Spacer(Modifier.height(12.dp))
                SectionTitle(stringResource(R.string.model_intro_used_in))
                Text(intro.usedIn.joinToString("、"), style = MaterialTheme.typography.bodySmall)
            }

            // 数据来源：3D 模型（Pixal3D 全家桶）来自 HuggingFace，Civitai 上没有它们。
            // unknown 与空值都不显示 —— 宁可少一行，不给用户看"未知"。
            // （sourceLabel 已提升为详情页同款 internal 纯函数，两处共用一份映射）
            sourceLabel(intro.source)?.let { label ->
                Spacer(Modifier.height(12.dp))
                SectionTitle(stringResource(R.string.model_intro_source))
                Text(label, style = MaterialTheme.typography.bodySmall)
            }

            // 完整文件名与存放路径：列表里为省宽度只显示文件名，长名字还会折行，
            // 这里给一个能对着文件系统找的位置
            Spacer(Modifier.height(12.dp))
            SectionTitle(stringResource(R.string.model_intro_path))
            Text(
                text = "models/" + (intro.rel ?: intro.file),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            intro.introOriginal?.let { original ->
                Spacer(Modifier.height(12.dp))
                SectionTitle(stringResource(R.string.model_intro_original))
                // 独立滚动框：作者原文动辄几千字（抓取层已不再截断），跟整页一起滚会
                // 把上面的参数区顶得老远。固定高度 + 自己的滚动，页面永远只占一块；
                // 外层 Column 也是 verticalScroll，内层必须给固定高度——LazyColumn 拿到
                // infinity 高度约束会直接抛异常。
                // 整段塞给一个 Text 时最长 5 万多字符单次排版慢到肉眼可见，所以切成
                // 小片段逐段排版（splitIntroText，拼接恒等于原文），首屏只排可见的那几段。
                val chunks = remember(original) { splitIntroText(original) }
                // 以片段原文本身当业务标识（相同段落重复出现时按出现顺序消歧）；
                // 不用下标：原文重抓后长度变化会让下标指向另一段，行状态（选区、滚动锚点）会错位。
                val keyedChunks = remember(chunks) { chunks.withStableKeys { it } }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(AUTHOR_TEXT_HEIGHT)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(8.dp)
                        )
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        items(keyedChunks, key = { it.key }) { item ->
                            Text(
                                text = item.value,
                                // 长文行高覆盖：12sp × 1.75 = 21sp（保持字号，只动行距）
                                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 21.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            intro.url?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(20.dp))
            if (onOpenDetail != null) {
                TextButton(
                    onClick = onOpenDetail,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.model_intro_open_detail))
                }
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(stringResource(R.string.model_intro_close))
            }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
}

/**
 * 数据来源的展示名；`unknown` 与空值返回 null（界面整块不显示）。
 *
 * v4.17 起实现移到 ModelIntroDetailScreen.kt（internal，详情页与抽屉共用一份）。
 * 品牌名不进 strings.xml：它们不是可翻译文本，翻译成"拥抱脸"只会更糟。
 */

/** 作者原文滚动框的固定高度：给个上界，内层才滚得动，也不至于一屏塞不下别的。 */
private val AUTHOR_TEXT_HEIGHT = 300.dp

/**
 * 键值对逐行展示（v4.17 起两列布局）。
 *
 * 用户明确要求"一个字段一行"—— 原先拼成一整行
 * "28 步 · CFG 1 · euler"，长参数名挤在一起没法看。
 * v4.17 统一为详情页同款两列：键 labelMedium + onSurfaceVariant（min 72dp、左对齐）、
 * 值 bodyMedium + tnum + onSurface（关键信息不用 3:1 的 onSurfaceVariant）。
 */
@Composable
private fun KeyValueRows(entries: Map<String, String>) {
    entries.forEach { (key, value) ->
        Row(modifier = Modifier.padding(vertical = 1.dp)) {
            Text(
                text = key,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(min = 72.dp)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                modifier = Modifier.weight(1f)
            )
        }
    }
}
