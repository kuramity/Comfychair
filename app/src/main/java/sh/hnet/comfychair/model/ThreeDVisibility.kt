package sh.hnet.comfychair.model

/**
 * 「3D 分段该不该出现」的判定（纯函数，单独抽出来做单测）。
 *
 * 真机 bug（v4.2.2，用户实测）：生成过一次 3D 后，切到**任何**普通出图流，
 * 分段仍然是「3D 模型 / 渲染图 / 原图」三段，把原来的「原图 / 预览」两段挤掉，
 * 反复切换还会闪退。根因是判据写成 `threeDResult != null`，而
 * [ThreeDResultHolder] 是**全局单例** —— 它一直有值，与"当前在看哪个工作流"无关。
 *
 * 正确判据是两条同时成立：
 * 1. **当前工作流本身是 3D 流**（只有 TTI_3D / ITI_3D 才可能产出 glb）；
 * 2. **这个结果是当前工作流产出的**（[boundWorkflowId] 等于当前流 id）——
 *    否则在两个不同的 3D 流之间切换时，会看到"另一个流的模型"。
 *
 * @param result 全局 3D 结果（[ThreeDResultHolder.result] 的当前值）
 * @param is3DWorkflow 当前选中的工作流是否为 3D 流（`capabilities.is3DWorkflow`）
 * @param boundWorkflowId 结果归属的工作流 id（页面在收到结果时记录）
 * @param currentWorkflowId 当前选中的工作流 id
 * @return 应当展示的 3D 结果；null 表示不显示 3D 分段
 */
object ThreeDVisibility {

    /**
     * 顶部导航栏那个 3D 控件**该不该出现**（v4.4.0 语义，用户要求）。
     *
     * 只看「当前选中的工作流是不是 3D 流」，**与有没有生成结果无关**。
     * v4.3.0 及以前控件挂在底部操作栏、且用 `threeDResult != null` 判显隐 ——
     * 于是用户必须先成功生成一次 3D 才能看见入口，且该入口与工作流脱节
     * （生成过一次后切到普通流仍在）。现在的行为：下拉里选中 3D 流的那一刻
     * 控件就出现，切回普通出图流立刻消失。
     */
    fun controlVisible(is3DWorkflow: Boolean): Boolean = is3DWorkflow

    /**
     * 点击顶部 3D 控件后**是打开查看器还是给空状态提示**。
     *
     * 没有结果时不能进查看器（那里必须有一个真实的 glb 文件），
     * 也不允许把 null 往下传 —— 查看器内部任何 `!!` 解包都会在切流瞬间
     * 炸成闪退（v4.3.0 修过一轮，不能在新入口上重犯）。
     */
    fun opensViewer(result: ThreeDResultHolder.Result?): Boolean = result != null

    /**
     * 选出「当前 3D 结果该归属到哪个工作流 id」。
     *
     * 图生图页有**两个**独立的工作流选择器：修复模式一个、编辑模式一个。
     * 3D 流（IDOL / Hunyuan 四视图 / Pixal3D）全部挂在**编辑模式**下，
     * 而 v4.3.0 的归属判定只读修复模式那个字段 —— 编辑模式下它是空串，
     * 于是 [visible] 的 `currentWorkflowId.isBlank()` 分支直接吞掉结果：
     * 生成成功、`/history` 里 `3d` 字段也在位，界面却只剩两段、顶部入口点开弹"暂无"。
     *
     * 这是 v4.4.1 真机前实测复现的真缺陷（模拟器 + 真实服务器 IDOL 生成）。
     */
    fun activeWorkflowId(isEditingMode: Boolean, plainId: String, editingId: String): String =
        if (isEditingMode) editingId else plainId

    /**
     * 「结果区该不该按 3D 呈现」——显示内嵌 3D 预览、以及决定分段按钮是三段还是两段。
     *
     * 与 [controlVisible] 的区别：控件（入口）只看工作流类型，结果区（内容）
     * 必须真有属于**这个流**的产物，否则会出现"3D 分段里空空如也、
     * 把原图/预览两段挤掉"——那正是 v4.3.0 修的真机 bug。
     */
    fun visible(
        result: ThreeDResultHolder.Result?,
        is3DWorkflow: Boolean,
        boundWorkflowId: String?,
        currentWorkflowId: String
    ): ThreeDResultHolder.Result? {
        if (result == null) return null
        if (!is3DWorkflow) return null
        // 空串必须单独排除：工作流还没加载完时 selectedWorkflowId 是 ""，
        // 而"两个空串相等"会让判断误通过 → 一进页面就冒出上次的模型。
        // （这条是 ThreeDVisibilityTest.emptyWorkflowIdsDoNotMatch 抓出来的真缺陷）
        if (currentWorkflowId.isBlank()) return null
        // 未绑定（页面刚创建、还没见过结果）或绑定到别的流，都不显示
        if (boundWorkflowId.isNullOrBlank() || boundWorkflowId != currentWorkflowId) return null
        return result
    }
}
