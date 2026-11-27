# B3 规则数据化 + 双通道合一 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]` syntax for tracking.

**Goal:** 占位符「已识别清单 + 元数据」外置为数据文件（RuleTable 加载，失败回退内置默认），替换机制统一为 PlaceholderPipeline 单入口（文本级 + 结构级双 API），v4.23.1 式「两通道改一漏一」结构性故障根除；ITI 通道 {{width}}/{{height}} 混用获得机器预检。

**Architecture 裁决（基于 2026-11-28 取证报告的 11 条风险点）：**
- **引擎留代码**：5 个 prepareXxx 入口的编排（lora 节点删除、图位注入、魔法文件名、[input] 后缀、尾链物化器顺序、入口级条件替换）全部保留在代码——它们是引擎不是规则，数据化它们 = 翻译事故温床（取证报告 §五.3/4/9/11）。
- **数据表数据化**：RECOGNIZED_PLACEHOLDERS 完整清单（40+7）+ 编号型正则模式 + 占位符元数据（type/channels/forbidden_in）进 `assets/rules/placeholder_rules.json`。新占位符从「改三处代码」变「改一处数据 + 一处传值」。
- **机制统一**：PlaceholderPipeline.substituteText（文本级，PlaceholderLiterals 语义）+ substituteTree（结构级，原 feature-tools 语义）双 API；WorkflowManager.replaceCommonPlaceholders 走前者，feature-tools.buildWorkflowJson 经 gateway 走后者。
- `KEY_TO_PLACEHOLDER` 反向表与 PLACEHOLDER_TO_KEY 扁平映射**不数据化**（防误推约束与 object_info 强相关，取证 §五.8）。

**Tech Stack:** Kotlin / org.json / JVM 单测

## Global Constraints

- 行为等价：迁移后替换输出与原通道逐字节一致（等价性测试钉死；含「带引号数值模板」案例）。
- 规则文件加载失败 → 回退内置默认表 + DebugLogger.w，不阻塞启动。
- 单测基线 2203 只增不减；⛔ F:\ComfyUI 铁律；.mimosa 不碰。

## Task 1（主会话）: 规则文件 + RuleTable + PlaceholderPipeline + 等价性测试

**Files:**
- Create: `app/src/main/assets/rules/placeholder_rules.json`
- Create: `app/src/main/java/sh/hnet/comfychair/workflow/rules/RuleTable.kt`
- Create: `app/src/main/java/sh/hnet/comfychair/workflow/PlaceholderPipeline.kt`
- Test: `app/src/test/java/sh/hnet/comfychair/workflow/rules/RuleTableTest.kt`
- Test: `app/src/test/java/sh/hnet/comfychair/workflow/PlaceholderPipelineTest.kt`

- [ ] Step 1: 写等价性测试（先红）——RuleTableTest 断言规则文件解析的 recognized 集合 == TemplateKeyRegistry.RECOGNIZED_PLACEHOLDERS 现值（改造前它是硬编码，测试写好后改造时它防漂移）；PlaceholderPipelineTest 覆盖：数值带引号模板 `"{{steps}}"`→数字、字符串带引号→字符串、树级替换顺序（后值含前值占位符不回代）
- [ ] Step 2: 写规则文件（数据源 = TemplateKeyRegistry:115-134 现值 + PLACEHOLDER_TO_KEY.keys 40 条 + patterns 2 条 + 元数据 channels 按 5 入口取证表填）
- [ ] Step 3: 实现 RuleTable（load(assets)：解析→schema 校验（version/占位符字段完整）→失败回退 DEFAULT_TABLE（编译期内置现值）→提供 recognizedPlaceholders/patterns/metadata(key)/forbiddenIn(key, channel)）
- [ ] Step 4: 实现 PlaceholderPipeline（substituteText/substituteTree，见上）
- [ ] Step 5: 跑测试 → 绿；commit 中间态不单独提交（与 Task 2/3 合并 commit）

## Task 2（cf-builder A）: WorkflowManager 接入

**Files:** Modify `WorkflowManager.kt`
- replaceCommonPlaceholders 内部重构：19 个可选参数构建 `values: List<Pair<String, Any?>>` → `PlaceholderPipeline.substituteText(json, values)`（seed/noise_seed 无条件 + legacy `"seed": 0` 模式保留）；**5 个入口签名与编排完全不动**
- `RECOGNIZED_PLACEHOLDERS` 引用侧不变（Task 3 委托后自动切数据源）
- applySkillPrompt 的 4 个 replace 改 `PlaceholderPipeline.substituteText`（值 list：skill_ 四件）

## Task 3（cf-builder B）: feature-tools gateway 接入

**Files:** Modify feature-tools `PromptEnhanceGateway.kt` + `PromptEnhanceScreen.kt`；app `tools/PromptEnhanceGatewayImpl.kt`
- gateway 接口加：`fun substituteInTree(root: org.json.JSONObject, values: LinkedHashMap<String, String>)`（JSONObject 跨模块直传，同一运行时 org.json）
- PromptEnhanceScreen.buildWorkflowJson：删除本地 substituteInJson/applyPlaceholders（1028-1062 行），改调 gateway
- PromptEnhanceGatewayImpl：实现调 `PlaceholderPipeline.substituteTree`
- 等价性：替换顺序（defaults 派生值在前、用户输入在后）语义保持

## Task 4（主会话）: TemplateKeyRegistry 委托 + 预检增强 + 全量验证

- RECOGNIZED_PLACEHOLDERS/isRecognizedPlaceholder 改为委托 RuleTable（调用方 API 不变）
- WorkflowPreflight 增强：ITI_EDITING/INPAINTING 工作流含 `{{width}}`/`{{height}}` 占位符 → 新增校验失败类（规格文档第三条硬规则的机器化）
- 全量单测（预期 2203+新增）→ assembleDebug → cf-reviewer 审查 → commit

## Self-Review

- spec §5 B3 四项全覆盖：规则外置 ✓（Task1/4）、RuleTable ✓（Task1）、双通道合一 ✓（Task2/3）、WorkflowManager 拆解——**范围裁决：本批只下沉替换管线（replaceCommonPlaceholders 55 行 → Pipeline），入口编排保留；3156 行巨类的全面拆解与 6.1 结论合并进 B4**（诚实缩小，理由见 Architecture 裁决）。
- 无占位符省略；类型一致：substituteText(String, List<Pair<String,Any?>>) / substituteTree(JSONObject, LinkedHashMap<String,String>) 全文一致。

## 实施回填（审查后）

- **applySkillPrompt 未改走 Pipeline**（Task 2 原列项）：刻意收窄——它是 v4.24.0 刚修复的线上故障点（裸 replace + escapeForJson 语义与 Pipeline.string 有微妙差异：裸形态场景原实现产出非法 JSON、新实现会补引号），按「刚修的不碰」原则保留原实现；它与 feature-tools 通道不同，不构成"双通道"。
- **WorkflowPreflight 增强未在本批**：现有第一道检查（占位符残留）已兜住 ITI 通道 {{width}} 残留场景，增强的增量价值仅是更准确文案 → 挪到 B5（资产下发准入校验的主战场，forbidden_in 数据已就绪待消费）。
- 审查 P2 全部处置：死字段 RECOGNIZED_PLACEHOLDER_PATTERNS 已删（运行时判据统一走 RuleTable）、计数注释已修、load 主线程 IO 与 schema 类型断言转 B5、本节即文档回填。
