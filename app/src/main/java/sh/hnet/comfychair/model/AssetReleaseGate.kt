package sh.hnet.comfychair.model

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 3D 资产的「归属闸门」：决定一个异步加载回来的模型由谁负责释放。
 *
 * ## 它解决的是什么问题（白话）
 *
 * 打开 3D 查看器时，模型是**异步**从磁盘/网络读进来的。读的这段时间里，用户完全可能
 * 直接退出——比如觉得网慢、点错了、或者从图库点进来又按返回。这时候负责这棵界面的
 * 组件已经被销毁了，但**读模型的那个回调不受销毁影响，它照样会跑完并把模型交回来**。
 *
 * 交回来的这个模型，如果没有地方接管，就会变成一个"看不见却占着显存"的孤儿：
 * 用户界面上什么都没有，内存/显存却降不下来。这个类就是那道闸门——销毁时拉起闸门，
 * 之后到达的模型一律判为"无主"，由回调方立即销毁。
 *
 * ## 为什么不能只靠 `LaunchedEffect`
 *
 * `loadModelAsync` 是**回调式** API，不是协程。Compose 离开组合时会取消 `LaunchedEffect`
 * 启动的协程，但**管不到**这个回调——回调依然会在 gltfio 的线程里执行。
 * 所以必须有一个自己维护的标志，不能指望协程取消来兜底。
 *
 * ## 线程说明
 *
 * `cancel()` 在 Compose 的销毁回调（主线程）执行，`tryAdopt()` 在加载线程执行，
 * 所以用 [AtomicBoolean] 而不是普通 Boolean —— 需要跨线程的可见性与原子性。
 */
class AssetReleaseGate {

    private val cancelled = AtomicBoolean(false)

    /**
     * 关闭闸门（离开组合时调用）。幂等：多次调用无副作用。
     */
    fun cancel() {
        cancelled.set(true)
    }

    /**
     * 加载回调到达时问一次：
     * - `true`  → 调用方可以接管这个模型（正常路径）
     * - `false` → 已被取消，**调用方必须立刻 `destroyModel(asset)`**，不能挂到节点上
     *
     * 注意名字是 `tryAdopt` 而不是 `canAdopt`：它表达的是"竞争一次看能不能接管"，
     * 调用方拿到 false 时是有义务的（释放），不是"随便问问"。
     */
    fun tryAdopt(): Boolean = !cancelled.get()

    /** 只读观察，给日志/测试用。 */
    val isCancelled: Boolean
        get() = cancelled.get()
}
