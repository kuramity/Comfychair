# B1 地基修复 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 5 处底层包反向依赖 viewmodel 的地基裂纹，解除 viewmodel.base↔viewmodel 纠缠与 notify↔lifecycle 双向环，并落地反向约束测试钉死边界。

**Architecture:** 三个领域数据类（GalleryItem/ContentType/MaskPathData）从 viewmodel 包搬入 model 包（纯移动，不改字段与方法体）；`GalleryItem.toCacheKey()` 因依赖 cache.MediaCacheKey 外移为 cache 包顶层扩展函数（保持 model 零 cache 依赖）；base 类的 `generationViewModelRef` 接口化；notify↔lifecycle 环边 1（AppLifecycleMonitor → GenerationNotifier）移到装配根 ComfyChairApp 接线。

**Tech Stack:** Kotlin 2.4.21 / JVM 单测（JUnit4）/ 无 DI（object 单例 + 手写装配）

## Global Constraints（来自 spec，逐条遵守）

- 依赖方向铁律：`model ← storage/cache/repository ← connection/queue ← workflow引擎 ← viewmodel ← ui`，底层永不反向依赖高层。
- 行为不变：本批次零用户可见行为变化；字段/方法体/KDoc 原样迁移。
- 全量单测基线 2202 例 0 失败，完成后只增不减。
- 版本号/CHANGELOG 本批次不动（发版是 B6 收口的交付决策）。
- ⛔ 绝不终止 F:\ComfyUI 相关任何进程；⛔ 不动 F:\ComfyUI 文件。
- 工作目录：`E:\Zcode\ZcodeProject\comfychair`（主仓库，main 直提）。
- 测试命令：`./gradlew testDebugUnitTest --console=plain`（约 31s）。
- .mimosa/ 目录下的 baseline/缓存文件一律不读不改。

## 并行分工与文件认领（同文件禁并行，按此清单划分零交集）

| 执行者 | 认领文件域 |
|---|---|
| 主会话（我） | 新建 `app/src/test/java/sh/hnet/comfychair/architecture/LayerDependencyGuardTest.kt`；Task 6 改 `lifecycle/AppLifecycleMonitor.kt`、`ComfyChairApp.kt` |
| cf-builder A | GalleryItem 域：新建 `model/GalleryItem.kt`；改 `viewmodel/GalleryViewModel.kt`、`viewmodel/MediaViewerViewModel.kt`、`viewmodel/SourceMissingPolicy.kt`、`ui/screens/GalleryScreen.kt`、`ui/screens/MediaViewerScreen.kt`、`ui/components/MainNavigationBar.kt`、`cache/MediaCache.kt`、`cache/MediaCacheFileNames.kt`、`repository/GalleryRepository.kt`、`storage/GalleryTrashStorage.kt`、`storage/GalleryMetadataCache.kt` |
| cf-builder B | ContentType + MaskPathData 域：新建 `model/ContentType.kt`、`model/MaskPathData.kt`；改 `viewmodel/GenerationViewModel.kt`、`queue/JobRegistry.kt`、`ui/components/SharedVideoPlayer.kt`、`ui/screens/ImageToVideoScreen.kt`、`ui/screens/TextToVideoScreen.kt`、`viewmodel/ImageToImageViewModel.kt`、`cache/MaskEditorStateHolder.kt`、`ui/components/MaskPaintCanvas.kt`、`ui/components/MaskPreview.kt` |
| 主会话（串行段，cf 完成后） | Task 5 改 `viewmodel/base/BaseGenerationViewModel.kt`、新建 `viewmodel/base/GenerationCompletionRelay.kt`、`viewmodel/GenerationViewModel.kt`（加接口实现） |

---

### Task 1: 反向约束测试先行（TDD RED）— 主会话

**Files:**
- Create: `app/src/test/java/sh/hnet/comfychair/architecture/LayerDependencyGuardTest.kt`

**Interfaces:**
- Consumes: 无（纯文件扫描测试，模式参照既有 `ViewModelUiDependencyTest`）
- Produces: 后续任何批次都会跑它；violation 消息格式 `${file.name}:${line}  ${trimmed}`

- [ ] **Step 1: 写测试**

```kotlin
package sh.hnet.comfychair.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 镜像版依赖边界守卫（B1）：底层包不得反向依赖 viewmodel / ui / feature.tools。
 *
 * ViewModelUiDependencyTest 钉住「viewmodel 不得 import ui」，本测试钉住反方向——
 * 2026-11-28 盘点发现的 5 处裂纹（GalleryItem/ContentType/MaskPathData 错放 viewmodel 包），
 * 修复后由本测试永久看守。
 */
class LayerDependencyGuardTest {

    /** 底层包：出现对这些高层包的 import 即违规 */
    private val forbiddenByLayer: Map<String, List<String>> = mapOf(
        "model" to listOf("viewmodel", "ui", "feature.tools", "storage", "cache", "repository", "connection", "queue"),
        "storage" to listOf("viewmodel", "ui", "feature.tools"),
        "repository" to listOf("viewmodel", "ui", "feature.tools"),
        "cache" to listOf("viewmodel", "ui", "feature.tools"),
        "queue" to listOf("viewmodel", "ui", "feature.tools")
    )

    @Test
    fun `底层包不得反向依赖 viewmodel 或 ui`() {
        val base = File("src/main/java/sh/hnet/comfychair")
        assertTrue("找不到源码目录：${base.absolutePath}", base.isDirectory)

        val violations = mutableListOf<String>()
        for ((layer, forbidden) in forbiddenByLayer) {
            val dir = File(base, layer)
            if (!dir.isDirectory) continue
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    file.readLines().forEachIndexed { index, line ->
                        val trimmed = line.trimStart()
                        if (!trimmed.startsWith("import sh.hnet.comfychair.")) return@forEachIndexed
                        val target = trimmed.removePrefix("import sh.hnet.comfychair.")
                        if (forbidden.any { target.startsWith(it) }) {
                            violations.add("$layer/${file.name}:${index + 1}  $trimmed")
                        }
                    }
                }
        }

        assertTrue(
            "底层包反向依赖高层包（共 ${violations.size} 处），请把领域数据类移到 model 层：\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }
}
```

- [ ] **Step 2: 跑测试确认红（5 处违规）**

Run: `./gradlew testDebugUnitTest --tests "sh.hnet.comfychair.architecture.LayerDependencyGuardTest" --console=plain`
Expected: FAIL，violation 清单恰好 5 处：`storage/GalleryMetadataCache.kt:8`、`storage/GalleryTrashStorage.kt:9`、`repository/GalleryRepository.kt:31`、`queue/JobRegistry.kt:13`、`cache/MaskEditorStateHolder.kt:8`
若红的位置与这份清单不一致 → 先核对磁盘实况再继续，不得凭预期猜。

---

### Task 2: GalleryItem 搬家 — cf-builder A

**Files:** 见认领表 A 行。

**Interfaces:**
- Consumes: Task 1 的红测试（修完即绿一半）
- Produces: `sh.hnet.comfychair.model.GalleryItem`（字段与 KDoc 与原版逐字相同）；`sh.hnet.comfychair.cache.toCacheKey(receiver: GalleryItem): MediaCacheKey`（顶层扩展函数）

- [ ] **Step 1: 新建 `model/GalleryItem.kt`**

把 `viewmodel/GalleryViewModel.kt` 中 `data class GalleryItem(...)` 整块（含 KDoc、含 `threeD`/`sourceMissing` 全部字段注释）原样迁出，文件头：

```kotlin
package sh.hnet.comfychair.model

import android.graphics.Bitmap
import sh.hnet.comfychair.cache.MediaCacheKey
import sh.hnet.comfychair.model.ThreeDResultHolder.Result
```

⚠️ 原类体内的 `fun toCacheKey() = MediaCacheKey.of(promptId, filename, type)` **不迁移**（见 Step 2）。`Bitmap` import 是否需要以实际字段为准核对（`threeD` 用的是 `ThreeDResultHolder.Result`；若类体无 Bitmap 引用则不 import）。
⚠️ `ThreeDResultHolder` 目前在 model 包（`model/ThreeDResultHolder.kt`），import 写 `sh.hnet.comfychair.model.ThreeDResultHolder`（同包可省略，按编译器提示最终定）。

- [ ] **Step 2: `cache/MediaCache.kt` 加顶层扩展函数**

```kotlin
/**
 * GalleryItem → 缓存键（B1 自 viewmodel.GalleryItem.toCacheKey() 外移）。
 * 放 cache 包而非 model：MediaCacheKey 是缓存域概念，model 层不得依赖 cache。
 */
fun GalleryItem.toCacheKey(): MediaCacheKey = MediaCacheKey.of(promptId, filename, type)
```

（与 `MediaCacheKey` 同文件顶层、类外；GalleryItem 改 import `sh.hnet.comfychair.model.GalleryItem`）

- [ ] **Step 3: GalleryViewModel.kt 删除类定义 + 全文件修正**

删除 `GalleryItem` data class 整块；文件加 `import sh.hnet.comfychair.model.GalleryItem` 与 `import sh.hnet.comfychair.cache.toCacheKey`；删除原 GalleryItem 独占的 import（`ThreeDResultHolder` 若无他用）。
其余 10 个引用文件逐一：加 `import sh.hnet.comfychair.model.GalleryItem`；凡调用了 `toCacheKey()` 的文件再加 `import sh.hnet.comfychair.cache.toCacheKey`（调用方清单：GalleryViewModel、MediaViewerViewModel、SourceMissingPolicy、GalleryScreen、MediaViewerScreen、MainNavigationBar、MediaCache 内部若有）。storage/repository/cache 三文件原本没有 viewmodel import 的（如 MediaCacheFileNames 只引类型），只加 model import。

- [ ] **Step 4: 编译验证**

Run: `./gradlew :app:compileDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL。若报 unresolved reference → 检查遗漏的 import 文件，逐个补。

- [ ] **Step 5: 跑 guard 测试确认 storage/repository/cache 三处裂纹消除**

Run: `./gradlew testDebugUnitTest --tests "sh.hnet.comfychair.architecture.LayerDependencyGuardTest" --console=plain`
Expected: 仍 FAIL，但 violations 只剩 `queue/JobRegistry.kt:13` 与 `cache/MaskEditorStateHolder.kt:8` 两处（属 cf-builder B 范围）。

---

### Task 3: ContentType 搬家 — cf-builder B（先做这个，文件更少）

**Files:** 新建 `model/ContentType.kt`；改 `viewmodel/GenerationViewModel.kt`、`queue/JobRegistry.kt`、`ui/components/SharedVideoPlayer.kt`、`ui/screens/ImageToVideoScreen.kt`、`ui/screens/TextToVideoScreen.kt`

- [ ] **Step 1: 新建 `model/ContentType.kt`**

```kotlin
package sh.hnet.comfychair.model

/**
 * Content type for generation (determines which event to dispatch on completion)
 */
enum class ContentType {
    IMAGE,
    VIDEO
}
```

- [ ] **Step 2: GenerationViewModel.kt 删除定义 + 加 import**（import `sh.hnet.comfychair.model.ContentType`）

- [ ] **Step 3: 其余 4 文件加 import**（JobRegistry.kt 加 import 后其反向依赖即消除）

- [ ] **Step 4: 编译验证** `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL

---

### Task 4: MaskPathData 搬家 — cf-builder B（接 Task 3）

**Files:** 新建 `model/MaskPathData.kt`；改 `viewmodel/ImageToImageViewModel.kt`、`cache/MaskEditorStateHolder.kt`、`ui/components/MaskPaintCanvas.kt`、`ui/components/MaskPreview.kt`

- [ ] **Step 1: 新建 `model/MaskPathData.kt`**

```kotlin
package sh.hnet.comfychair.model

import android.graphics.Path

/**
 * Data class representing a path drawn on the mask canvas
 *
 * @param isFill 该路径是否**整块填充**而不是描边（P1-25）。
 *   反相蒙版需要"全图覆盖"这一笔：修复前用一个 1px 笔宽 + STROKE 的整图矩形来表示，
 *   实际只画出 1px 的边框，图内几乎全是黑色 —— 反相后等于"什么都没蒙"。
 *   填充笔必须单独标记，绘制与生成两边都按 FILL 处理。
 */
data class MaskPathData(
    val path: Path,
    val isEraser: Boolean,
    val brushSize: Float,
    val isFill: Boolean = false
)
```

（KDoc 以 `viewmodel/ImageToImageViewModel.kt:53-66` 现文为准逐字迁移）

- [ ] **Step 2: ImageToImageViewModel.kt 删除定义 + 4 文件加 import** `sh.hnet.comfychair.model.MaskPathData`

- [ ] **Step 3: 编译验证 + guard 测试**

Run: `./gradlew testDebugUnitTest --tests "sh.hnet.comfychair.architecture.LayerDependencyGuardTest" --console=plain`
Expected: **PASS**（5 处裂纹全部消除）

---

### Task 5: viewmodel.base 解纠缠 — 主会话（串行段，等 cf A/B 完成后）

**Files:** 新建 `viewmodel/base/GenerationCompletionRelay.kt`；改 `viewmodel/base/BaseGenerationViewModel.kt:50`、`viewmodel/GenerationViewModel.kt`（类声明行 + 1562 行方法）

**Interfaces:**
- Produces: `interface GenerationCompletionRelay { fun completeGeneration(promptId: String?) }`；`GenerationViewModel : GenerationCompletionRelay`

- [ ] **Step 1: 新建接口（方法带默认参数，保证既有无参调用不破坏）**

```kotlin
package sh.hnet.comfychair.viewmodel.base

/**
 * 生成页 → 总控的事件汇入口（B1 自 BaseGenerationViewModel 解纠缠）。
 * base 依赖本接口而非 GenerationViewModel 具体类，使 viewmodel.base 可独立演进；
 * 仅暴露 base 实际用到的方法（grep 实证只有 completeGeneration 一处）。
 */
interface GenerationCompletionRelay {
    fun completeGeneration(promptId: String? = null)
}
```

- [ ] **Step 2: BaseGenerationViewModel.kt**：`import sh.hnet.comfychair.viewmodel.GenerationViewModel` 删除；`:50` 行类型改 `protected var generationViewModelRef: GenerationCompletionRelay? = null`。`:102` 的 `generationViewModelRef == generationViewModel` 引用相等判断保持（同实例）。

- [ ] **Step 3: GenerationViewModel.kt**：类声明加 `: GenerationCompletionRelay`（`import sh.hnet.comfychair.viewmodel.base.GenerationCompletionRelay`）；`:1562` 的 `fun completeGeneration(promptId: String? = null)` 加 `override` 且**去掉默认参数**（Kotlin 规则：override 不允许重复声明默认值，默认值由接口声明承载）。

- [ ] **Step 4: 全量编译 + guard 测试** → SUCCESSFUL / PASS

---

### Task 6: notify↔lifecycle 双向环解除 — 主会话（可与 cf 并行，文件无交集）

**Files:** 改 `lifecycle/AppLifecycleMonitor.kt`（删 import + 删订阅块）、`ComfyChairApp.kt`（加接线）

- [ ] **Step 1: AppLifecycleMonitor.kt**：删 `import sh.hnet.comfychair.notify.GenerationNotifier`（:15）；删除 attach 订阅内 `GenerationNotifier.setAppInForeground(app, state == AppVisibility.FOREGROUND)` 调用块（:117 附近）；KDoc 中「接管 setAppInForeground 驱动」的表述同步改为「由 ComfyChairApp 装配根接线驱动」。
- [ ] **Step 2: ComfyChairApp.kt**：attach 之后接线（collect StateFlow 天然先发当前值，与原 attach 订阅语义等价）：

```kotlin
// B1：notify↔lifecycle 环解除——前台信号由装配根接线，AppLifecycleMonitor 回归「只喂信号」职责
applicationScope.launch {
    AppLifecycleMonitor.visibility.collect { state ->
        GenerationNotifier.setAppInForeground(this@ComfyChairApp, state == AppVisibility.FOREGROUND)
    }
}
```

（scope 名以 ComfyChairApp 现有协程作用域为准；若其 attach 订阅原本在主线程 scope，接线放同 scope。）
- [ ] **Step 3: 编译 + 跑 guard 测试**

---

### Task 7: B1 全量验证与提交 — 主会话

- [ ] **Step 1: 全量单测** `./gradlew testDebugUnitTest --console=plain` → 2202+1 例（guard 新增 1）0 失败
- [ ] **Step 2: 行为抽查**（编译产物层面）：`./gradlew :app:assembleDebug --console=plain` → BUILD SUCCESSFUL
- [ ] **Step 3: cf-reviewer 独立审查**（对照 spec §4 原则 + 本计划验收线）
- [ ] **Step 4: commit（一次聚合）**

```bash
git add -A ':!app/src/main/assets' ':!.mimosa'
git commit -m "refactor: B1 foundation repair — move domain data classes to model, break base/notify cycles, add layer guard test"
```

⚠️ `git add` 用路径限定，避免把无关未跟踪文件卷入；提交前 `git status -s` 人工核对一次。

---

## Self-Review 结论

- Spec 覆盖：spec §5 B1 的 ①②③④ 四项 = Task 2/3/4（搬家）、Task 5（解纠缠）、Task 6（双向环）、Task 1（反向约束测试）✓
- 无占位符：所有代码块给出实际内容；不确定处均标注了「以编译器/磁盘实况为准」的核对动作 ✓
- 类型一致性：`GenerationCompletionRelay.completeGeneration(promptId: String?)` 与现实现 `:1562` 签名对齐（默认参数上移接口）✓
