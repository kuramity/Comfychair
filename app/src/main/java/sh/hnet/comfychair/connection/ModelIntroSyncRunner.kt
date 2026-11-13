package sh.hnet.comfychair.connection

import sh.hnet.comfychair.util.SingleFlight
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「把服务器上的模型介绍数据同步到本地」的执行编排：并发闸门 + 异常兜底 + 收尾。
 *
 * 为什么单拎成一个对象：`ConnectionManager` 依赖 Android（Context / OkHttp / 主线程调度器），
 * 整段跑不进 JVM 单测，而这里恰好是热更新**最危险**的一段——
 *
 * 1. **闸门必须在四条路径上都复位**：拉取成功、拉取失败、回调里抛出、以及最容易被漏掉的
 *    「发起阶段就同步抛出」（地址拼装 / 请求构造 / `enqueue`）。漏掉任何一条 = 本进程内
 *    热更新永久失效，而界面上看不出任何异常（数据只是再也不更新）；
 * 2. **任何异常都不许冒泡回调用方**：调用点在 `/object_info` 的回调里，那里紧接着还要
 *    `extractModelLists()` 并装配模型列表；被异常中断的后果是"模型下拉为空"，
 *    比"没有介绍"严重得多（介绍只是锦上添花，模型列表不是）。
 *
 * 抽成不依赖 Android 的小对象之后，这两条承诺由 `ModelIntroSyncRunnerTest` 逐条钉住。
 * 否则「接线还在不在」只能靠人眼读代码 —— 终审的反证实验证明：把调用删掉，全量单测仍全绿。
 *
 * 线程模型：`fetch` 内部是异步的（OkHttp 回调），所以闸门的正常释放点在**回调**里；
 * 只有 `fetch` 自己同步抛出时才由 [run] 收尾。两条路径用一次性标记去重，
 * 避免"回调已经释放、catch 又释放一次"错放掉别人刚拿到的执行权。
 */
class ModelIntroSyncRunner(
    private val gate: SingleFlight = SingleFlight(),
    private val logInfo: (String) -> Unit = {},
    private val logWarn: (String) -> Unit = {},
) {

    /**
     * 触发一次同步。重复触发（已有任务在飞）直接跳过，不排队、不抛异常。
     *
     * @param fetch 发起拉取：拿到可用 JSON 时以文本回调；任何失败都以 `null` 回调
     * @param apply 拿到可用 JSON 后落盘并重载内存表（失败只记日志）
     */
    fun run(fetch: (callback: (String?) -> Unit) -> Unit, apply: (String) -> Unit) {
        if (!gate.tryEnter()) {
            logInfo("介绍数据同步已在进行中，跳过本次")
            return
        }

        // 幂等释放：回调的 finally 与发起阶段抛出的 catch 都可能走到这里，真正的复位只发生一次
        val released = AtomicBoolean(false)
        fun release() {
            if (released.compareAndSet(false, true)) gate.exit()
        }

        try {
            fetch { json ->
                try {
                    if (json == null) {
                        logWarn("介绍数据拉取失败（保持本地版本）")
                    } else {
                        apply(json)
                    }
                } catch (e: Exception) {
                    // apply 内部已各自兜底，这里是最后一道防线
                    logWarn("介绍数据同步异常（忽略）：${e.message}")
                } finally {
                    // 成功 / 失败 / 异常三条路径都必须走到这里
                    release()
                }
            }
        } catch (e: Exception) {
            // 发起阶段就抛出：自己收尾后吞掉异常。
            // 这里刻意**不是** finally —— `enqueue` 一返回就会执行 finally，那等于在异步回调
            // 到来之前提前放闸（保护彻底失效）；释放点只能落在"回调完成"或"这里抛出"两处之一。
            logWarn("介绍数据同步在发起阶段异常（忽略）：${e.message}")
            release()
        }
    }
}
