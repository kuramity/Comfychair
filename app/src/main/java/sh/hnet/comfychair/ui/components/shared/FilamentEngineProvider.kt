package sh.hnet.comfychair.ui.components.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import com.google.android.filament.Engine
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader

/**
 * 全 App 共享的 Filament **Engine** 与 **ModelLoader**（v4.5.0）。
 *
 * ## 为什么必须共享（白话）
 *
 * Filament 的 `Engine` 是一个很重的对象：它自带 OpenGL 上下文、渲染线程池、着色器编译缓存。
 * 建一个约 200~400ms，销毁时要把这些全部拆掉。
 *
 * 改之前 `rememberEngine()` 直接写在 `Model3DScene` 里，而 `Model3DScene` 有两个入口：
 * **结果区的内嵌预览** 和 **全屏查看器**。这带来三个问题：
 *
 * 1. **两个引擎同时活着**：点"全屏"时，内嵌预览并没有从组合里移除（只是被 Dialog 盖住），
 *    于是同一时刻有 2 个 Engine、2 套渲染线程、2 份着色器缓存，显存直接翻倍。
 * 2. **反复进出反复建**：图库点开 3D 再返回算一次完整生命周期，来回 5 次就建/拆 5 次，
 *    每次都要重编一遍着色器（首次打开明显卡顿的来源）。
 * 3. **分类切换**：图生图 ↔ 文生图各有一份内嵌预览，切一次就拆一个建一个。
 *
 * 把 Engine 提到 Activity 级别后：整个页面存活期只建 **1 个**，内嵌与全屏共用它；
 * 切分类只重建上层 UI，引擎不动；只有退出到别的 Activity/进程死亡时才销毁。
 *
 * ## 为什么用 CompositionLocal 而不是 ViewModel
 *
 * `Engine` 持有 GL 资源，生命周期必须严格绑定"有界面存在"的那段时间。
 * ViewModel 会跨配置变更（旋转屏幕）存活，而 GL 上下文在配置变更时会被系统回收 ——
 * 让 ViewModel 持有 Engine 是典型的一放就崩。`CompositionLocal` 由 Compose 的
 * 组合生命周期管理，`rememberEngine` 内部的 `DisposableEffect` 会在宿主销毁时
 * `safeDestroy(engine)` 并回收 EGL 上下文，正好对上。
 *
 * ## 为什么用可空 CompositionLocal
 *
 * `null` 表示"这棵组合树没有提供共享引擎"（比如 instrumented 测试直接渲染
 * `Model3DScene`）。此时 `Model3DScene` 会退回调用 `rememberEngine()`，
 * 行为与改造前一致 —— 测试不需要额外包一层 Provider 就能跑。
 */
val LocalFilamentEngine = compositionLocalOf<Engine?> { null }

/** 与 [LocalFilamentEngine] 配套的共享 [ModelLoader]（同一引擎必须配同一 loader）。 */
val LocalFilamentModelLoader = compositionLocalOf<ModelLoader?> { null }

/**
 * 在宿主 Activity 的根组合中调用一次，向下提供共享的 Engine / ModelLoader。
 *
 * 必须包在**所有可能显示 3D 内容的界面**外面：主容器（图生图/文生图 + 全屏 Dialog）
 * 与图库容器（图库 3D 条目 + 全屏 Dialog）。
 */
@Composable
fun ProvideFilamentEngine(content: @Composable () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    CompositionLocalProvider(
        LocalFilamentEngine provides engine,
        LocalFilamentModelLoader provides modelLoader,
        content = content
    )
}
