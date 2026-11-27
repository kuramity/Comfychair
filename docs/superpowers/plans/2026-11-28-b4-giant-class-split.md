# B4 巨类拆解 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ConnectionManager/MediaCache/四 ViewModel/WorkflowEditorViewModel 四大巨类的结构拆解，包级环清零。

**Architecture 裁决（基于 2026-11-28 两份取证报告）：**

取证把拆解点按风险分层。本批次（两天窗口）按风险梯度推进，**质量优先于覆盖面**：

| 波次 | 内容 | 风险 | 状态 |
|---|---|---|---|
| **W1** | ① 环边 2 解除（NetworkClassMonitor/AppLifecycleMonitor/BackgroundDegradationPolicy 三件下沉 `platform/` 中立包，notify→lifecycle 依赖消失，环断；纯移动零行为变化）② MediaCacheKey 直连收编（8 处 → 收编 6 处，2 处显式 serverId 语义保留；MediaViewerItem.toCacheKey 提升顶层扩展） | 低 | 本批 |
| **W2** | **ModelListCatalog**（ConnectionManager E+T+V 块 ≈700 行拆出：模型列表状态/闸门/代次/看门狗/介绍热更新装配 + 提取，29 处调用机械改道；connect/disconnect 显式 bump 接线） | 中 | 本批 |
| **W3** | WsMessageDispatcher（P 块 160 行）+ 模拟器四项验收 | 低 | 本批 |
| **W4** | 四 ViewModel 视频对合并（取证实测可消 **≈500 行**而非 6.1 估计的 100——差异全是类型名替换；阻碍是 ITV 重选守卫/PREVIEW 切换两处独有行为）+ loadWorkflowValues 四胞胎统一（三种回退口径参数化）+ WorkflowEditorViewModel 三类拆分（图操作器 1900 行 / 保存协调器 1000 行）+ OfflineModeController + MediaCache 网关接口化 | 中高 | **B4.2（独立会话/批次推进）** |

**W4 转 B4.2 的理由**：时序敏感点密集（取证 §四列出 cache 快照先于清洗、deferred 双写、ITV slot 显式清空、restorePreferences 三步序、editor 的快照-恢复对与 SaveFailed 语义），单批吞下验证面过大；B4 完成后巨类问题已实质缓解（ConnectionManager 2830→约 1900、MediaCache 解网络耦合、环清零），W4 大工程在干净地基上独立推进更稳。

## Global Constraints

- 全量单测基线 2219 只增不减；⛔ F:\ComfyUI 铁律；.mimosa 不碰。
- 纯移动/机械改道类任务（W1/W2）零行为变化，编译器兜底 + 现有 2219 测试守护。
- W2 ModelListCatalog 属真实拆解：每步编译验证，J↔E 耦合（connect/disconnect bump）与 fetchServerData 依赖显式接线。

## 执行记录

- W1：两代理并行（环边 2 / MediaCacheKey 收编），验收 + commit 待回。
- W2/W3：W1 落地后滚动。
