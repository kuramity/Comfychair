package sh.hnet.comfychair.update

import android.content.Context
import sh.hnet.comfychair.BuildConfig
import sh.hnet.comfychair.util.DebugLogger

/**
 * 「已经是新版本时该做哪些清理」的纯逻辑（v4.18.0，R3）。
 *
 * 用户实测事故：手机上已经装好新版本，再点「检查更新」/再进 App，**旧的下载 Worker
 * 还在后台跑**（WorkManager 会把未完成的 OneTimeWork 跨进程、跨重启恢复），
 * 于是 160MB 的包被一遍遍续传重试，流量被烧掉；同时那条 `setOngoing(true)` 的
 * 进度通知永远撤不掉，看起来就像「安装进程一直挂起」。
 *
 * 根因不是清单比较写错了（[UpdateChecker.shouldUpdate] 一直是对的），
 * 而是「已是最新」这个结论**从来没有被用来取消残留任务 / 清通知**。
 *
 * 这里把「该做什么」抽成纯函数，Worker、检查链路、冷启动恢复共用同一份判定，
 * 单测可以在 JVM 上直接覆盖，不用起 Android 环境。
 */
object UpdateCleanupPolicy {

    private const val TAG = "UpdateCleanupPolicy"

    /** 已是最新时需要执行的动作 */
    enum class Action {
        /** 取消所有残留的更新下载 Worker */
        CANCEL_PENDING_WORK,

        /** 清掉更新相关通知（进度条 / 已就绪 / 失败横幅） */
        CLEAR_UPDATE_NOTIFICATIONS
    }

    /**
     * 目标版本是否已经不再需要下载（R3 / Worker 侧的同一条闸门）。
     *
     * @param targetVersionCode 本次任务要下载的 versionCode（<=0 表示未知，不做判断）
     * @param installedVersionCode 当前已安装的 versionCode
     */
    fun isObsolete(targetVersionCode: Int, installedVersionCode: Int): Boolean =
        targetVersionCode > 0 && installedVersionCode > 0 &&
            targetVersionCode <= installedVersionCode

    /**
     * 目标 versionCode 未知时是否必须中止下载（fail-closed）。
     *
     * 背景：v4.18.0 之前入队的下载任务没有 `KEY_VERSION_CODE` 入参，WorkManager 在应用
     * 升级后仍会把它们恢复执行，`getInt(KEY_VERSION_CODE, 0)` 读成 0。旧实现把它交给
     * [isObsolete]，而后者要求 `targetVersionCode > 0` 才可能返回 true —— 闸门因此
     * fail-open，旧包会在用户打开 App 前继续下载、继续烧流量。
     *
     * 判据只有一条：**拿不到目标版本号，就无法证明这个任务还需要跑**，必须中止。
     * 该判断只覆盖「缺参数」这一种历史任务形态；正常路径（入队时必带 versionCode）
     * 仍然由 [isObsolete] 判断，行为不变。
     */
    fun shouldAbortMissingTarget(targetVersionCode: Int): Boolean = targetVersionCode <= 0

    /**
     * 读取当前**已安装**的 versionCode（PackageManager 口径）。
     *
     * 刻意不用 [BuildConfig.VERSION_CODE]：它是编译期常量，装完新版本后旧进程可能
     * 还在跑，读出来的是旧值 —— 版本闸门会因此把已经装好的版本当成「待下载」。
     * [UpdateDownloadWorker] 内部早就改用了 PackageManager，这里抽成共享入口，
     * 让界面层（启动恢复 / 手动检查）与 Worker 用同一口径。
     *
     * 读取失败（极少数：包被系统替换中 / PackageManager 异常）时回退到 BuildConfig
     * 并记 warning：此时至少还有本进程的编译期版本可比，不会让闸门整体失效。
     */
    fun installedVersionCode(context: Context): Long = try {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    } catch (e: Exception) {
        DebugLogger.w(TAG, "读取已安装 versionCode 失败，回退 BuildConfig：${e.javaClass.simpleName}")
        BuildConfig.VERSION_CODE.toLong()
    }

    /**
     * 给定「清单版本」与「已安装版本」，算出必须执行的动作集合。
     *
     * @param latestVersionCode 清单里的 versionCode
     * @param installedVersionCode 当前已安装的 versionCode
     * @param pendingWorkCount 尚未结束的更新下载任务数（按 tag 统计）
     * @param updateNotificationsShown 当前是否存在更新相关通知
     * @return 需要执行的动作；仍有更新时为空集（什么都不做）
     */
    fun actionsFor(
        latestVersionCode: Int,
        installedVersionCode: Int,
        pendingWorkCount: Int,
        updateNotificationsShown: Boolean
    ): Set<Action> {
        // 只有「不需要更新」才清理；有新版本时绝不取消正在进行的下载
        if (latestVersionCode > installedVersionCode) return emptySet()
        return buildSet {
            if (pendingWorkCount > 0) add(Action.CANCEL_PENDING_WORK)
            if (updateNotificationsShown) add(Action.CLEAR_UPDATE_NOTIFICATIONS)
        }
    }
}