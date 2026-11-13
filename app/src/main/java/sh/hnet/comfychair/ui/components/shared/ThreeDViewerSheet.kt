package sh.hnet.comfychair.ui.components.shared

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.android.filament.gltfio.FilamentAsset
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.Scene
import io.github.sceneview.node.CameraNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMainLightNode
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes
import io.github.sceneview.rememberOnGestureListener
import sh.hnet.comfychair.R
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.model.AssetReleaseGate
import sh.hnet.comfychair.model.ThreeDFraming
import sh.hnet.comfychair.model.ThreeDResultHolder
import sh.hnet.comfychair.model.ThreeDVisibility
import java.io.File
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 3D 模型查看器（图生3D 的产物）。
 *
 * 三件事刻意分开：
 * 1. **按需下载** —— 实测一个 .glb 有 30~61MB，生成完成就整包拉下来太浪费：
 *    用户可能只想把文件拿走、根本没打算在手机上看。所以下载推迟到打开查看器时。
 * 2. **本地缓存** —— 同一个文件第二次打开直接用缓存，不重复拉。
 * 3. **全屏 Dialog 而不是 ModalBottomSheet**（v4.1.1 修）——
 *    M3 的 `ModalBottomSheet` 会用纵向拖拽手势做展开/收起，**垂直滑动被它吞掉**，
 *    SceneView 只能收到水平事件 → 模型只能左右转、不能上下转，做不到 360° 无死角。
 *    模拟器实测：水平拖动画面变化、垂直拖动画面像素级不变。换成全屏 Dialog
 *    （`usePlatformDefaultWidth = false`）后没有任何 sheet 手势参与竞争。
 */
@Composable
fun ThreeDViewerSheet(
    result: ThreeDResultHolder.Result,
    onDismiss: () -> Unit
) {
    val state = rememberLocalModel3D(result)
    val localFile = state.file
    val errorText = state.error
    val downloading = state.downloading

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶部信息条：标题 + 文件名 + 关闭按钮（不占用渲染区手势）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.three_d_viewer_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = result.filename,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.model_intro_close))
                    }
                }

                val file = localFile
                when {
                    errorText != null -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = errorText!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    file != null -> Model3DScene(
                        file = file,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )

                    else -> Box(
                        modifier = Modifier
                            .fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = if (downloading) {
                                    stringResource(R.string.three_d_downloading)
                                } else {
                                    stringResource(R.string.three_d_loading)
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

/** GLB 的本地缓存目录（filesDir 下，与其它缓存同级）。 */
private const val MODEL3D_DIR = "model3d"
private const val TAG_3D = "ComfyChair3D"

/** 3D 产物的本地文件状态（按需下载 + 缓存复用）。 */
private data class LocalModel3DState(
    val file: File?,
    val error: String?,
    val downloading: Boolean
)

/**
 * 按需下载 + 本地缓存：整个 App 里只有这一处写 `filesDir/model3d/`。
 *
 * 全屏查看器与结果区内嵌预览共用，避免两套下载逻辑各自维护（缓存键是文件名，
 * 同一产物先内嵌看过再全屏打开时不会重复下载）。
 */
@Composable
private fun rememberLocalModel3D(result: ThreeDResultHolder.Result): LocalModel3DState {
    val context = LocalContext.current
    var file by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf(false) }

    LaunchedEffect(result.filename, result.subfolder) {
        val dest = File(File(context.filesDir, MODEL3D_DIR), result.filename)
        if (dest.exists() && dest.length() > 0) {
            file = dest
            return@LaunchedEffect
        }
        val client = ConnectionManager.clientOrNull
        if (client == null) {
            error = context.getString(R.string.three_d_no_connection)
            return@LaunchedEffect
        }
        downloading = true
        client.fetchRawFile(result.filename, result.subfolder, result.type, dest) { ok, _ ->
            downloading = false
            if (ok) {
                file = dest
            } else {
                error = context.getString(R.string.three_d_download_failed)
            }
        }
    }

    return LocalModel3DState(file = file, error = error, downloading = downloading)
}

/**
 * 结果区里的**内嵌** 3D 预览（双视图切换的「3D 模型」一侧）。
 *
 * 为什么要它：3D 流同时产出 GLB 和一张 RenderSplat 渲染图 PNG，App 原来只展示渲染图，
 * 用户点开看到的是"一张可以缩放、但怎么拖都转不动的图片"——那根本不是 3D 查看器
 * （2026-11-09 用户报障的原始现象）。现在结果区默认就渲染真 3D，可切换回渲染图。
 *
 * 手势：整个预览区是固定高度的非滚动容器，SceneView 的 ORBIT manipulator 独占指针事件，
 * 不会再出现 sheet 那种"垂直滑动被父级吃掉"的情况。右上角提供全屏入口（大屏看更舒服）。
 */
@Composable
fun ThreeDInlinePreview(
    result: ThreeDResultHolder.Result,
    modifier: Modifier = Modifier,
    onRequestFullscreen: () -> Unit
) {
    val state = rememberLocalModel3D(result)
    Box(modifier) {
        val file = state.file
        when {
            state.error != null -> Text(
                text = state.error!!,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Center)
            )

            file != null -> Model3DScene(
                file = file,
                modifier = Modifier.fillMaxSize()
            )

            else -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.Center)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = if (state.downloading) {
                        stringResource(R.string.three_d_downloading)
                    } else {
                        stringResource(R.string.three_d_loading)
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // 全屏入口：内嵌区再大也受结果区高度限制，全屏 Dialog 才是"认真看模型"的地方
        if (state.file != null) {
            SmallFloatingActionButton(
                onClick = onRequestFullscreen,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ViewInAr,
                    contentDescription = stringResource(R.string.three_d_viewer_fullscreen)
                )
            }
        }
    }
}

/**
 * 3D 产物的入口按钮（顶部导航栏，v4.4.0 从底部操作栏迁来）。
 *
 * 三条行为要点，逐条对应真机反馈：
 *
 * 1. **显隐只看工作流类型**（[is3DWorkflow]），不看好不好有没有结果 ——
 *    在下拉里选中 3D 流的那一刻控件就出现，切回普通出图流立刻消失。
 *    v4.3.0 及以前用「全局 holder 有值」判显隐，导致必须先成功生成一次
 *    3D 才看得见入口；控件还挂在底部，与「生成」按钮、齿轮挤成一行。
 * 2. **空状态不崩**：3D 流下还没生成过模型时点击 → Toast 提示，不进查看器、
 *    也不把 null 往下传（查看器里任何 `!!` 解包都会在切流瞬间炸成闪退）。
 * 3. **结果归属由调用方判定**：传进来的 [result] 已经是「属于当前工作流」的值
 *    （图生图页用 [ThreeDVisibility.visible] 过滤过），本组件不做二次归属判断。
 *
 * 视觉上把原来的 56dp 圆边框按钮换成顶栏标准的 [IconButton]（48dp 触控区、
 * 24dp 图标），这样才和旁边的内存释放 / 上传 / 菜单图标并排等齐 ——
 * 图标矢量本身没换，比例保持原样。
 *
 * @param is3DWorkflow 当前选中的工作流是否为 3D 流（`capabilities.is3DWorkflow`）
 * @param result 当前工作流产出的 3D 结果；null 表示这次流还没生成过
 */
@Composable
fun ThreeDResultButton(
    is3DWorkflow: Boolean,
    result: ThreeDResultHolder.Result?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showSheet by remember { mutableStateOf(false) }

    // 切流 / 新任务会把 holder 清空，这时必须收起已经打开的查看器，
    // 否则会停在「弹窗开着、模型已经没了」的错位状态（下一帧读取空结果就崩）。
    LaunchedEffect(is3DWorkflow, result) {
        if (!is3DWorkflow || result == null) showSheet = false
    }

    if (!ThreeDVisibility.controlVisible(is3DWorkflow)) return

    IconButton(
        onClick = {
            if (ThreeDVisibility.opensViewer(result)) {
                showSheet = true
            } else {
                // 空状态兜底：提示而不是崩溃（用户验收第 2 条）
                Toast.makeText(
                    context,
                    context.getString(R.string.three_d_no_result_hint),
                    Toast.LENGTH_SHORT
                ).show()
            }
        },
        modifier = modifier
    ) {
        Icon(
            imageVector = Icons.Default.ViewInAr,
            contentDescription = stringResource(R.string.three_d_viewer_title)
        )
    }

    // 用局部变量而不是直接 `result!!`：collectAsState 是异步更新，
    // 组合这一帧拿到的值可能刚从非空变空（v4.3.0 的闪退就死在这上面）。
    val current = result
    if (showSheet && current != null) {
        ThreeDViewerSheet(result = current, onDismiss = { showSheet = false })
    }
}

/**
 * Filament 渲染一个本地 .glb。
 *
 * 用 SceneView 2.x 的 Compose 入口 `Scene(...)`（不是 `SceneView` —— 那是传统 View）。
 * `engine` / `modelLoader` 由 remember 系列管理生命周期，离开组合时自动释放 ——
 * 手动 new Engine 再忘了 destroy 是这类库最常见的显存泄漏来源。
 *
 * v4.1.0 重写（此前黑屏 + 无手势）：
 * 1. **环境光**：黑屏的头号根因是没设 HDR 环境（`Scene` 不设 environment 时没有 IBL 光），
 *    这里内置 `environments/sky_2k.hdr`（SceneView 官方 sample 同款）；文件缺失时退到
 *    `createEnvironment()`，再靠 [mainLightNode] 的默认方向光保底。
 * 2. **异步加载**：`createModel` 是同步阻塞调用，54MB 的 GLB 会卡住主线程 —— 换
 *    `loadModelAsync`，加载中显示不确定进度圈（2.3.0 的 progress 回调不推进，不显示百分比）。
 * 3. **取景**：2.3.0 没有 autoFit/frame，按 `scaleToUnits=1f` 归一后的包围盒手算眼位与注视点。
 * 4. **手势**：`rememberCameraManipulator` 默认 ORBIT —— 单指旋转、双指捏合缩放、双指平移；
 *    它只在触摸后才写回相机，首帧姿态（以及双击复位）由 [LaunchedEffect] 显式摆放。
 * 5. **释放**：asset 由 `ModelLoader.destroy()` 级联销毁（它内部先逐个 destroyModel 再销毁
 *    loader，`rememberModelLoader` 已接管），这里不手动重复销毁。
 */
@Composable
internal fun Model3DScene(
    file: File,
    modifier: Modifier = Modifier,
    /**
     * 手势探针（**只给 instrumented 测试用**，生产传 null 时零开销）。
     *
     * 为什么需要它：SceneView 渲染在 SurfaceView 里，Compose 的 `captureToImage()`
     * 抓不到画面内容，无法用"截图变没变"验证缩放；而模拟器又注入不了多点触控
     * （adb 只有单指）。唯一可靠的判据是"双指事件到底有没有被 ScaleGestureDetector 识别"，
     * 这只能从库的手势回调里取。探针只读、不改变任何渲染行为。
     */
    probe: ((String) -> Unit)? = null
) {
    // 引擎优先用宿主 Activity 提供的共享实例（v4.5.0）。
    // 没有 Provider 时（instrumented 测试直接渲染本组件）退回自建，行为与改造前一致。
    val sharedEngine = LocalFilamentEngine.current
    val sharedModelLoader = LocalFilamentModelLoader.current
    val engine = sharedEngine ?: rememberEngine()
    val modelLoader = sharedModelLoader ?: rememberModelLoader(engine)
    val childNodes = rememberNodes()

    // 环境光（防黑屏——最重要的一行）：HDR 失败时 createEnvironment() 兜底，不让场景裸奔
    val environmentLoader = rememberEnvironmentLoader(engine)
    val environment = rememberEnvironment(environmentLoader) {
        runCatching {
            environmentLoader.createHDREnvironment(HDR_ASSET_LOCATION)
        }.getOrNull() ?: environmentLoader.createEnvironment()
    }
    // 兜底方向光：HDR 缺失时场景里唯一的光源（HDR 在时与 IBL 叠加，只是略亮，无害）
    val mainLightNode = rememberMainLightNode(engine)

    var modelAsset by remember { mutableStateOf<FilamentAsset?>(null) }
    var failed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    // 取景参数（模型加载后算一次）；复位计数变化时重建 manipulator（见下）
    var framing by remember { mutableStateOf<CameraFraming?>(null) }
    // 复位不再用 resetCount 重建 manipulator（见下方 cameraManipulator 注释）

    // ── 中途取消的释放闸门（v4.5.0 修的真泄漏）──
    //
    // `loadModelAsync` 是**回调式** API，不是协程：用户加载到一半退出时，Compose 会取消
    // `LaunchedEffect` 的协程，但**管不到**这个回调 —— 它照样会在 gltfio 线程里跑完并把
    // 模型交回来。此时界面已经销毁，没人接住这个模型 → 不可达且未释放（占着显存）。
    // 闸门在销毁时拉起，回调到达时问一次：被取消就立刻 destroyModel，不挂到节点上。
    val releaseGate = remember { AssetReleaseGate() }

    val cameraNode = rememberCameraNode(engine)

    DisposableEffect(Unit) {
        onDispose {
            releaseGate.cancel()
            // ⚠️ 这里**不能** `destroyModel(modelAsset)`（v4.5.0 曾这么做，2026-11-10 实测崩溃）：
            // 离开组合时 SceneView 的渲染对象还没完全拆掉，此时在主线程销毁资产，
            // Filament 会 SIGSEGV（tombstone：#00 libgltfio-jni.so，tid=主线程，
            // null pointer dereference，fault addr 0x2a8）。runCatching 也捕不到 native 崩溃。
            //
            // 安全的释放路径只有一个：共享 ModelLoader 在 **Activity 销毁**时
            // `ModelLoader.destroy() → clear()` 逐个级联销毁 —— 那时 SurfaceView 已销毁、
            // Engine 还在，实测从未崩过。
            //
            // 代价：换一次 3D 结果 / 切一次流，旧资产会留在 loader 的 models 列表里直到
            // 退出当前 Activity（有界泄漏，非永久）。用"可能泄漏"换"不闪退"，这个取舍是
            // 有意的，不要为了省显存把它改回主动 destroyModel。
            modelAsset = null
        }
    }

    // 异步加载：回调里回主线程更新状态（不显示百分比 —— 2.3.0 的 progress 不推进）
    LaunchedEffect(file.path) {
        // ── 换文件时先摘掉上一份（2026-11-10）──
        //
        // 同一个组合槽位里 `file` 变了（结果区被新一次生成的 3D 结果替换）时，
        // 下面会执行 `modelAsset = <新资产>`，把旧值直接覆盖。旧 ModelNode 会一直留在
        // childNodes 里 → 新旧两个模型叠在同一个 Scene 里同时渲染（可见的重影）。
        //
        // 所以先摘 Scene、再清 childNodes：这会消除重影。
        // **但不在这里 destroyModel 旧资产** —— 与 onDispose 同一个原因：渲染对象还活着时
        // 销毁资产会 SIGSEGV（2026-11-10 实测，tombstone 见 onDispose 注释）。
        // 旧资产留在共享 ModelLoader 的 models 列表里，随 Activity 销毁级联释放。
        framing = null
        if (childNodes.isNotEmpty()) childNodes.clear()
        modelAsset = null

        loading = true
        failed = false
        probe?.invoke("loadStart:${file.name}:${file.length()}")
        // 前置检查（v4.2.0 修）：文件不存在或 0 字节时，`loadModelAsync` **既不成功也不回调**
        // （实测：探针只收到 loadStart 就再无下文，界面永远转圈，用户只能杀进程）。
        // 这里直接判失败，给用户可读提示。
        if (!file.exists() || file.length() <= 0L) {
            loading = false
            failed = true
            probe?.invoke("assetFailed:emptyOrMissing")
            return@LaunchedEffect
        }
        modelLoader.loadModelAsync(file.toURI().toString()) { asset ->
            // 中途取消：用户已经离开，闸门已拉起 → 这个模型没有人会用它，
            // 必须在这里就地销毁，否则它会带着显存活到进程结束。
            if (!releaseGate.tryAdopt()) {
                android.util.Log.w(
                    TAG_3D,
                    "asset arrived after dispose, releasing: ${file.name} (asset=${asset != null})"
                )
                asset?.let { late ->
                    runCatching { modelLoader.destroyModel(late) }
                        .onFailure { android.util.Log.w(TAG_3D, "late destroyModel failed", it) }
                }
                return@loadModelAsync
            }
            if (asset == null) {
                // 实测（模拟器 + v2.3.0）：FileLoader 的 Uri 分支对 file:/ 路径会静默
                // 返回 null（无日志）。降级用 createModel(File) 直读文件系统 —— 它走
                // FileKt.readBuffer(File) 的 FileInputStream，不经过 Uri 解析。
                // 当前产物 ~1.4MB，同步读 <100ms 可接受（54MB 大文件才需要真异步）。
                android.util.Log.w(TAG_3D, "loadModelAsync(uri)=null, fallback to createModel(File): ${file.name} size=${file.length()}")
                runCatching { modelLoader.createModel(file) }
                    .onSuccess { fallback ->
                        loading = false
                        if (fallback != null) {
                            // 同步 fallback 也要过闸门：极端情况下（file 变化触发重载、
                            // 或本次组合刚好被销毁）这里同样可能是最后一次机会
                            if (!releaseGate.tryAdopt()) {
                                runCatching { modelLoader.destroyModel(fallback) }
                                return@onSuccess
                            }
                            modelAsset = fallback
                            probe?.invoke("assetReady:fallback")
                        } else {
                            android.util.Log.e(TAG_3D, "createModel(File) also returned null (gltfio parse failure)")
                            failed = true
                            probe?.invoke("assetFailed:null")
                        }
                    }
                    .onFailure { e ->
                        loading = false
                        failed = true
                        probe?.invoke("assetFailed:${e.javaClass.simpleName}")
                        android.util.Log.e(TAG_3D, "createModel(File) threw", e)
                    }
            } else {
                loading = false
                modelAsset = asset
                probe?.invoke("assetReady:async")
            }
        }
    }

    // 建节点 + 包围盒取景（v2.3.0 无 autoFit/frame，手算）
    // 注意：首次组合时 modelAsset 尚在异步加载（null），不算失败 —— 只有 asset 非 null
    // 却拿不到 instance 才是真解析失败。否则 failed 会被首帧误置且加载成功后不复位，
    // 场景已渲染却叠加着「渲染失败」文案（实测踩过）。
    LaunchedEffect(modelAsset) {
        val asset = modelAsset ?: return@LaunchedEffect
        val instance = asset.instance
        if (instance == null) {
            failed = true
            probe?.invoke("instanceNull")
            return@LaunchedEffect
        }
        probe?.invoke("instanceReady")
        // scaleToUnits=1f 归一尺寸（取景与 GLB 实际大小无关）、中心移到原点（注视点恒为原点）
        val node = ModelNode(
            modelInstance = instance,
            scaleToUnits = 1f,
            centerOrigin = Float3(x = 0f, y = 0f, z = 0f)
        )
        childNodes.add(node)
        probe?.invoke("nodeAdded")
        // 【标定日志】v2.3.0 里 scaleToUnits 是否真生效、node 的包围盒何时可用，
        // 官方文档没写清。这里一次性打出三组尺寸，用来确定取景该用哪个量：
        //   bbox    = asset 的原始包围盒（未应用节点 transform）
        //   nodeHalf/nodeSize = 节点当前（含 scaleToUnits）的包围盒
        // 等 2 帧再读，避免读到 transform 未生效的中间态。
        // 取景距离：**不能读 node.halfExtent**（它返回缩放前的包围盒，而渲染用的是
        // scaleToUnits=1f 之后的尺寸 → 相机落进模型内部）。
        // 公式抽在 [ThreeDFraming] 里并配了单测——这套数学一天内改错过两次，
        // 靠肉眼截图验证不可靠。
        // 注：早期这里有一个 delay(80) 的"标定等待"，已删除——它让每次打开 3D 都白等，
        // 且在 Compose 测试时钟下永不推进（instrumented 测试卡死的元凶）。
        val f = ThreeDFraming.distanceFor(asset.boundingBox.halfExtent)
        val distance = f.distance
        android.util.Log.w(
            TAG_3D,
            "framing: radius=${f.radius} dist=$distance fov=${ThreeDFraming.FOV_DEGREES}"
        )
        // 初始视角：**接近正前方**，只轻微偏右上。
        //
        // 为什么不是漂亮的 3/4 侧视角：单图 3D 重建（IDOL/TRELLIS/Pixal3D 都是）只能
        // 重建出输入图可见的那一面，背面与侧面本身就是空心的。第一眼给 3/4 视角
        // 会直接看到"破碎的空壳"（2026-11-09 实测：3/4 视角下模型像散落的碎片）；
        // 给正面则是信息最完整、最像"模型"的那一面，用户转动后自己发现局限。
        val direction = normalize(Float3(x = 0.12f, y = 0.18f, z = 1f))
        framing = CameraFraming(
            eye = direction * distance,
            target = Float3(x = 0f, y = 0f, z = 0f)
        )
        probe?.invoke("framing:dist=$distance")
    }

    // 相机操作器与 Scene 的创建时机绑定：framing 就绪后才创建 DefaultCameraManipulator
    // 与 Scene —— SceneView 的 setCameraManipulator 只在构造时调用一次，之后每帧用它
    // 的姿态覆盖 cameraNode。若在 framing=null 时先建 Scene（默认取景），模型加载后的
    // 新 manipulator 不会同步进去，单指旋转作用于旧实例、双击复位改的是新实例，
    // 全部脱钩（2026-11-09 模拟器实测踩坑）。
    val currentFraming = framing
    if (currentFraming != null) {
        // ★ 首帧相机位姿必须**显式**摆放（v4.1.1 关键修复）。
        // SceneView 2.3.0 的 sceneview.onFrame 只在 `lastTouchEvent != null` 时才把
        // manipulator 的 transform 写回 cameraNode —— 也就是说，**在用户第一次触摸之前，
        // 相机一直停在 CameraNode 的默认位置 (0,0,1)**。而模型经 scaleToUnits=1f 后
        // 中心在原点、包围球半径约 0.5~0.87，相机 (0,0,1) 距模型表面只有 ~0.1~0.5：
        // 打开即"贴脸裁切"，一碰才跳到正确距离。这才是用户报障"看不到完整模型"的
        // 真正病根（不是 fov、也不是包围盒读错）。
        LaunchedEffect(currentFraming, cameraNode) {
            runCatching {
                cameraNode.position = currentFraming.eye
                cameraNode.lookAt(currentFraming.target)
                android.util.Log.w(
                    TAG_3D,
                    "camera placed: eye=${currentFraming.eye} target=${currentFraming.target}"
                )
            }.onFailure { android.util.Log.e(TAG_3D, "place camera failed", it) }
        }
        val cameraManipulator = remember(currentFraming) {
            io.github.sceneview.gesture.CameraGestureDetector.DefaultCameraManipulator(
                orbitHomePosition = currentFraming.eye,
                targetPosition = currentFraming.target
            )
        }
        // 复位视角：跳回 manipulator 的 home 位姿。同时挂在双击手势与右下角复位按钮上
        // —— 双击走 SceneView 的标准 GestureDetector（间隔需 >40ms 且 <400ms，adb 注入
        // 要精确编排），按钮是用户永远可依赖的兜底入口。
        fun resetCamera() {
            runCatching {
                // getManipulator 是 protected final：getMethod 只查 public 会抛 NoSuchMethod，
                // 必须走 getDeclaredMethod
                val inner = cameraManipulator.javaClass
                    .getDeclaredMethod("getManipulator").apply { isAccessible = true }
                    .invoke(cameraManipulator) as com.google.android.filament.utils.Manipulator
                inner.jumpToBookmark(inner.homeBookmark)
            }.onFailure { android.util.Log.e(TAG_3D, "camera reset failed", it) }
                .onSuccess { android.util.Log.w(TAG_3D, "camera reset ok") }
        }
        val gestureListener = rememberOnGestureListener(
            onDoubleTap = { _, _ ->
                android.util.Log.w(TAG_3D, "gesture onDoubleTap")
                resetCamera()
            },
            // 缩放/滚动探针：只在测试传入 probe 时记录（生产路径 probe=null，零开销）
            onScale = { detector, _, _ ->
                probe?.invoke("scale:${detector.scaleFactor}")
                Unit
            },
            onScaleBegin = { _, _, _ ->
                probe?.invoke("scaleBegin")
                true
            },
            onScaleEnd = { _, _, _ ->
                probe?.invoke("scaleEnd")
                Unit
            },
            onScroll = { _, _, _, distance ->
                probe?.invoke("scroll:${distance.x},${distance.y}")
                Unit
            }
        )

        Box(modifier) {
            Scene(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                environment = environment,
                mainLightNode = mainLightNode,
                cameraNode = cameraNode,
                cameraManipulator = cameraManipulator,
                childNodes = childNodes,
                onGestureListener = gestureListener
            )
            if (!failed) {
                // 复位视角按钮（右下角）：双击手势之外永远可用的兜底入口
                SmallFloatingActionButton(
                    onClick = { resetCamera() },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.three_d_reset_view)
                    )
                }
            }
        }
    } else if (loading) {
        Box(modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 3.dp
            )
        }
    }
    if (failed) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.three_d_render_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** 相机取景参数：眼位与注视点（模型加载后按包围盒算出） */
private data class CameraFraming(val eye: Float3, val target: Float3)

/** 内置 HDR 环境的 assets 路径（SceneView 官方 sample 同款天空） */
private const val HDR_ASSET_LOCATION = "environments/sky_2k.hdr"

/**
 * 相机**垂直**视场角（度）= **46.4°**，由 SceneView 2.3.0 的实现反推而来，不是猜的。
 *
 * 依据（2026-11-09 独立复核，反编译 sceneview-2.3.0 aar 核实）：
 * `CameraNode` 构造器写死 `focalLength = 28.0`（35mm 等效）、filament 走 24mm 传感器，
 * `SceneView` 调 `setLensProjection(28.0, aspect, …)` → 垂直 fov = 2·atan(12/28) ≈ **46.4°**。
 * 取景公式、fov、边距、距离下限都搬到了 [ThreeDFraming]（纯函数 + 单测）。
 * 这里只保留"模型不可用时的默认眼位"。
 */

/** 取景参数就绪前的 manipulator 默认眼位（沿 +z 看原点；仅在 framing 未算出时短暂使用） */
private val DEFAULT_HOME_POSITION = Float3(x = 0f, y = 0f, z = 3f)
