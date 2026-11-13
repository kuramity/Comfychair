package sh.hnet.comfychair.util

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「同一时刻至多跑一个」的**跳过式**闸门。
 *
 * 用途：后台同步类任务（如模型介绍数据热更新）可能被多条链路触发——连接成功、
 * 用户手点刷新、磁盘预填——它们之间没有先后关系，谁先到谁先跑。
 *
 * 为什么是跳过而不是排队：这些任务本身是**幂等**的（拉同一份数据、覆盖同一份缓存），
 * 排队只会把同一个请求重复做 N 遍；跳过还能顺手达到「防连点」的效果。
 *
 * 使用约定（唯一的坑在这里）：拿到执行权后，**成功、失败、异常三条路径都必须走到
 * [exit]**——通常在回调的 `finally` 里。忘记复位会让后续所有同步被永久跳过，
 * 而界面上看不出任何异常（只是数据再也不更新）。
 *
 * 刻意不持有任何 Android 依赖：守卫的正确性靠 JVM 单测守（见 `ModelIntroFetchTest`）。
 */
class SingleFlight {

    private val inFlight = AtomicBoolean(false)

    /**
     * 尝试进入。
     *
     * @return true 表示拿到执行权（调用方结束后**必须**调用 [exit]）；
     *         false 表示已有同任务在飞，本次应当直接跳过
     */
    fun tryEnter(): Boolean = inFlight.compareAndSet(false, true)

    /**
     * 释放执行权。重复调用是安全的（幂等），便于在多层 catch/finally 里随手兜底。
     */
    fun exit() {
        inFlight.set(false)
    }

    /** 当前是否有任务在飞（供日志与测试观察）。 */
    val isInFlight: Boolean
        get() = inFlight.get()
}
