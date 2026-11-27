# ComfyChair 结构治理与热更新能力设计（spec）

- 日期：2026-11-28
- 状态：已获用户逐段确认（brainstorming 流程产出）
- 基线：v4.24.0 / code160（未提交工作区），全量单测 2202 例 / 0 失败
- 决策人：用户（所有范围/方向决策均经 AskUserQuestion 确认）

## 1. 背景与动机

用户初始提议「底层 C++ + JS 前端 + py 脚本层」，经澄清后确认：

- 用户内心倾向完全重构+换栈，但接受风险论证，**最终选定保持 Kotlin/Compose 技术栈、只做内部结构治理**；
- 真实动机两项：**可维护性**（巨类/双通道反复造成故障）与**热更新/脚本化能力**（改规则不重装 App）；
- 追加需求：**死函数、无用参数、复杂逻辑（深嵌套循环）审计清理**；
- 结论：C++/JS/py 三层形态是桌面/服务器软件（ComfyUI 本身即是 py 编排 + JS 前端 + CUDA 底层）的形态，对 Android 客户端性价比为负。App 真正欠缺的是**依赖方向、类职责拆分、规则数据驱动**——这三样在现有栈内渐进实现即可达到「底层优化」的实际效果。

## 2. 决策记录

| 决策点 | 用户选择 |
|---|---|
| 治理范围 | P0（地基裂纹）+ P1（巨类/耦合）+ P2（脚本化基建）一次做完 |
| 热更新对象 | 预置工作流下发 + 技能规则 md 下发 + 占位符规则数据化（全要）；标签库已有热更新基建，不动 |
| 脚本化形态 | **数据驱动 + 服务器下发**，不嵌 Python（Chaquopy 会加 20-50MB 运行时且调试链路复杂化，它能做的事数据文件全能做） |
| 实施节奏 | 大版本内分批（B1–B6），每批独立验收 |
| 内置/覆盖层语义 | 内置 assets = 出厂兜底，仅随发版刷新；覆盖层 = 唯一活跃数据源，拉取永不丢；回退仅限「覆盖层损坏/从未拉取」两个场景 |

## 3. 现状盘点结论（2026-11-28 审计）

- 规模：115,107 行主源码 + 37,710 行测试，575 个 Kotlin 文件；单测基线 2202。
- **model 层纪律最好**（32 文件几乎零出向依赖、大量纯函数 JVM 可测），是治理的参照样板。
- **P0 反向依赖 4 处**（领域数据类错放 viewmodel 包）：
  - `storage/GalleryMetadataCache.kt:8`、`storage/GalleryTrashStorage.kt:9` → `import viewmodel.GalleryItem`（定义在 `GalleryViewModel.kt:32`）
  - `repository/GalleryRepository.kt:31` → 同上
  - `queue/JobRegistry.kt:13` → `import viewmodel.ContentType`（定义在 `GenerationViewModel.kt:69`）
  - `cache/MaskEditorStateHolder.kt:8` → `import viewmodel.MaskPathData`
- **P1 巨类**：WorkflowEditorViewModel 3381 / WorkflowGraphCanvas 3231 / WorkflowManager 3156 / ImageToImageViewModel 2914 / ConnectionManager 2830 / MediaCache 2801。
- **P1 耦合问题**：viewmodel.base ↔ viewmodel 互依（`BaseGenerationViewModel.kt:17`）；notify ↔ lifecycle 双向环；storage → cache 越层（`PreferencesMaintenance.kt:5`）；cache → connection（`MediaCache.kt:21`）；ConnectionManager 宽耦合面（直接驱动 JobRegistry/GalleryRepository/MediaCache）。
- **P2 双通道占位符**：WorkflowManager 通用链路 + feature-tools `PromptEnhanceScreen.buildWorkflowJson` 两条替换通道并存——v4.23.1 线上 400 故障的结构性根因。
- 已有约束测试 `ViewModelUiDependencyTest`（viewmodel 不得 import ui），缺反向版本。

## 4. 架构原则（全部批次共同遵守）

1. **依赖方向铁律**：`model ← storage/cache/repository ← connection/queue ← workflow引擎 ← viewmodel ← ui`，底层永不反向依赖高层。
2. **反向约束测试**：新增测试——`model/storage/repository/cache/queue` 任一文件出现 `import ...viewmodel.../` 或 `import ...ui.../` 即红。现有正向约束保留。
3. **TDD**：每项改动先写失败测试再动实现（遵循 superpowers:test-driven-development）；纯逻辑拆出 JVM 可测对象（延续 DynamicParamScanner 模式）。
4. **行为不变**：B1/B2/B4 为纯结构改动，不改用户可见行为；每批完成跑全量单测（只增不减）。

## 5. 批次路线图（顺序由依赖关系锁定）

| 批次 | 内容 | 排序理由 | 风险 |
|---|---|---|---|
| **B1 地基修复** | ① `GalleryItem`/`ContentType`/`MaskPathData` 搬入 model 层（建议 `model/gallery/` 等按域分包），修正全部 import；② viewmodel.base 解纠缠（base 不再 import GenerationViewModel，公共依赖下沉或接口化）；③ notify ↔ lifecycle 双向环解除（通知撤除动作改为事件流/接口注入）；④ 落地反向约束测试 | 纯移动 + import 修正，编译器兜底；不先修，B4 拆解会把裂纹进一步扩散 | 低 |
| **B2 死代码与复杂度审计** | 静态扫描（编译器警告 + 交叉引用核查）产出死函数/无用参数清单；圈复杂度热点清单（深嵌套循环点）；逐项人工确认后清理。产出审计报告 | 拆解之前先删，拆的量更小；审计结论不被后续拆解动作作废 | 低 |
| **B3 规则数据化 + 双通道合一** | ① 占位符识别表（`TemplateKeyRegistry.RECOGNIZED_PLACEHOLDERS`）、替换路径归属（按工作流类型）、阶段前缀分组（`SamplerStageRegistry`）外置为 `assets/rules/placeholder_rules.json`；② `RuleTable` 加载器（启动加载，schema 校验失败回退内置默认，不阻塞启动）；③ 抽 `PlaceholderPipeline` 作为唯一替换入口，WorkflowManager 通用链路与 feature-tools 通道都走它；④ WorkflowManager（3156 行）借机拆出替换管线/加载/校验职责 | 双通道合一是 B5 下发的前提（下发工作流必须与内置走同一套规则解析）；v4.23.1 故障的结构性根因在此 | 中 |
| **B4 巨类拆解** | ① ConnectionManager 四职责分离（连接状态机 / 模型列表 / 介绍热更新编排 / 缓存驱动回调）；② MediaCache 网络逻辑下沉（经接口注入客户端）；③ WorkflowEditorViewModel（3381 行）按编辑域拆分；④ 四 ViewModel 视频对整函数合并 + `stopListening` 上移基类（6.1 既有结论：≈100 行合并，禁走 GenerationParamsDelegate 方案）；⑤ storage → cache、cache → connection 两处越层按接口反转 | 依赖 B1 地基与 B3 的 WorkflowManager 先动 | 中高 |
| **B5 资产下发** | ① 通用 `AssetDistributor`（manifest 拉取 → 版本比对 → SHA-256 校验 → 覆盖层落盘，复用 ModelIntroSync/UpdateChecker 成熟模式）；② 资产类型注册表 `AssetKind`：`PREBUILT_WORKFLOW`（过 WorkflowPreflight 五条硬规则准入）/ `SKILL_MD`（编码校验）/ `RULE_TABLE`（schema 校验）；③ 预置流列表刷新时合并（内置 + 下发，重名下发优先）；④ 设置页「恢复内置版」入口（删覆盖层即回退） | 依赖 B3 规则数据化 | 中 |
| **B6 收口** | 全量回归 + 模拟器四项验收（见 §9）+ 真机验证 + 发版 | — | — |

## 6. 内置与覆盖层的分层语义（用户确认版）

```
服务器（真·活数据）──刷新──→ 覆盖层（设备上的最新数据）──→ App 日常使用
                                    │ 仅当「覆盖层损坏(SHA 不过)」或「从未拉取成功」
                                    ▼
                              内置 assets（出厂快照，纯兜底）
```

- 内置 assets **不做运行时同步**：仅随发版刷新一次，角色 = 新用户起点 + 异常底线。
- 覆盖层拉取成功后即为唯一日常数据源，历次更新累积、永不丢失（清 App 数据除外）。
- 「恢复内置版」= 删除覆盖层，显式动作，不出现在自动路径里。
- 发版周期（周/月）与数据更新周期（随时）解耦：改规则 = 服务器改文件 + App 刷新，无需发版。

## 7. 规则文件格式（B3，示意）

```json
{
  "version": 3,
  "placeholders": [
    { "key": "positive_prompt", "channels": ["tti", "ttv", "iti", "itv"] },
    { "key": "image_filename",  "channels": ["iti", "itv"] },
    { "key_prefix": "skill_",   "channels": ["skill"] },
    { "key": "width", "channels": ["tti", "ttv", "itv"],
      "forbidden_in": ["iti_editing"], "note": "编辑类用 megapixels，混用必 400" }
  ]
}
```

要点：
- `key_prefix` 承载 `{{skill_` 式前缀判据（v4.23.1 教训：逐个点名判据会在新增字段时漏改）；
- `channels` 数据化「哪些替换路径认领哪些占位符」，新增占位符从「改三处代码」变为「改一处数据」；
- `forbidden_in` 把 docs/预置工作流规范.md 第三条硬规则（尺寸机制不混用）内置为机器可校验项；
- 动态参数类型解析仍读服务器 object_info（运行时数据），只有**静态规则**进规则文件。

## 8. B2 审计方法

1. 死函数/无用参数：Kotlin 编译器 unused 警告 + 全仓交叉引用 grep 双重确认；**只报不删**，清单经人工确认后进清理批次。
2. 复杂度热点：嵌套深度 ≥ 4 或单函数 > 150 行的清单，逐项标注「可拆 / 可简化 / 保留理由」。
3. 审计报告落 `docs/audit/`，作为 B4 拆解的输入。

## 9. 验证策略

### 每批通用
- 全量单测绿（基线 2202 起步只增不减），新增测试先红后绿；
- B1/B3/B5 各配专项单测（B5：坏 SHA 清单不采纳 / 版本回退 / 内置+下发同名合并正确性三类）。

### 模拟器四项验收（B4、B6 各一轮；用户明确要求）

工具链（tools 总索引 + 既有记忆）：
- 启动模拟器：`tools/android-test/启动cf_test模拟器.bat`（AVD `cf_test`，320×640；注意软键盘从 y≈390 起遮挡底部按钮）；
- 桩服务器：`tools/comfychair/stubs/fake_comfyui_min.py:18188`（脱机环境）/ `delay_object_info_stub.py:18189`（延迟场景）；
- 构建+安装：ZCode 内置 android-emulator 插件 `android_build_and_run`，或 `tools/comfychair/build/build.bat`（注意 `%*` 是追加语义）；
- **① 新增界面检测**：`uiautomator dump`（判非空）对比关键页面控件树——预置流下拉、底栏六入口、生成页参数面板、小工具两页，任何计划外的新增/消失控件即记 finding；
- **② 恶性崩溃检测**：`adb logcat -b crash` 全程监听，零崩溃为验收线；顺带核对全局崩溃落盘文件无新增；
- **③ 流畅度**：`adb shell dumpsys gfxinfo sh.hnet.comfychair` 采帧统计（janky frames 比例与治理前对比不劣化），画廊滚动 / 画布拖拽 / 翻页三条路径实测。**B1 开工前先在模拟器采一次治理基线**（同三条路径），存 `docs/audit/perf-baseline-<日期>.md`，后续对比以它为准；
- **④ 前端视觉**：`android_screenshot` 截关键页（亮屏内容遵守远程操控隐私规则——本 App 自身页面均为安全内容），直接目视比对无错位/黑块/文字截断。

### 真机（B6）
- iQOO Neo10 Pro+ 实装：安装确认弹窗需用户在场配合（发一次，不重试）；
- 重点路径：连接 → 选预置流 → 提交 → 图库查看；B5 覆盖层下发全流程。

## 10. 边界（不做什么 / YAGNI）

- 不换编程语言、不引入 NDK、不嵌 Python、不引入 WebView/RN 渲染；
- 不动 UI 视觉与交互手感（翻页/回弹/拖选等已调优行为原样保留）；
- 不动标签库热更新基建（58MB db.gz 通道现状保留）；
- 不引入 DI 框架（手写装配现状保留，model 层纯函数模式已够用；是否引入留给后续版本再议）；
- 不动 `F:\ComfyUI` 任何进程与文件（铁律）。

## 11. 风险与回退

| 风险 | 缓解 |
|---|---|
| B1 搬类引发大面积 import 冲突 | 编译器兜底 + 每步编译验证；类搬家不动任何字段与方法体 |
| B3 规则外置后旧预置流行为变化 | 迁移时逐占位符对照原 `RECOGNIZED_PLACEHOLDERS` 与各替换函数，等价性单测钉死（规则表 vs 原代码行为 diff 必须为空） |
| B4 拆解破坏隐蔽时序（如 MediaCache 预取队列） | 拆解前先为该模块补行为快照测试；真机回归覆盖 |
| B5 下发脏数据进工作流列表 | WorkflowPreflight 五条硬规则准入 + SHA-256 必验 + 坏文件不落盘 + 「恢复内置版」一键回退 |
| 治理期间线上出修复需求 | 批次粒度小、每批独立可发布；紧急修复可随时插队，不阻塞批次 |

## 12. 后续步骤

本 spec 获用户过目后，进入 superpowers:writing-plans 产出实施计划（B1 先行）。
