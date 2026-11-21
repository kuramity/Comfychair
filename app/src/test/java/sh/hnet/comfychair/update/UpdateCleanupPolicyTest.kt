package sh.hnet.comfychair.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R3：已经是新版本时不得再跑任何下载 / 安装（v4.18.0）。
 *
 * 用户事故：「已经安装好了新版本然后再点击更新，之前残留的没搞完的更新进程会继续，
 * 导致流量消耗巨大，同时安装进程也会一直挂起」。根因不是清单比较写错了
 * （[UpdateChecker.shouldUpdate] 一直是对的），而是「已是最新」这个结论从来没有
 * 被用来取消残留 Worker / 清通知 —— WorkManager 会把未完成的 OneTimeWork
 * 跨进程、跨重启恢复，于是旧任务一直在后台续传重试。
 *
 * 这里钉住：
 * 1. 纯判定：已是最新 + 有 pending worker → {取消任务, 清通知}；
 * 2. 有更新时绝不取消（不能误杀正在进行的下载）；
 * 3. Worker 侧的过时闸门（目标 code <= 已安装 code）；
 * 4. 仓库接线：autoCheck / manualCheck 的「已是最新」分支必须调用清理。
 */
class UpdateCleanupPolicyTest {

    private fun readSource(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到源文件 $relative，尝试过：${candidates.map { it.absolutePath }}")
        return file.readText()
    }

    // ---------- 1. 动作集合 ----------

    @Test
    fun `已是最新且有残留任务与通知时产出取消加清通知`() {
        val actions = UpdateCleanupPolicy.actionsFor(
            latestVersionCode = 139,
            installedVersionCode = 139,
            pendingWorkCount = 1,
            updateNotificationsShown = true
        )
        assertEquals(
            setOf(
                UpdateCleanupPolicy.Action.CANCEL_PENDING_WORK,
                UpdateCleanupPolicy.Action.CLEAR_UPDATE_NOTIFICATIONS
            ),
            actions
        )
    }

    @Test
    fun `本地版本比清单还新时同样清理`() {
        // 用户回滚/手动装了更高的包：清单里的「新版」其实更旧，残留任务必须停
        val actions = UpdateCleanupPolicy.actionsFor(
            latestVersionCode = 138,
            installedVersionCode = 139,
            pendingWorkCount = 2,
            updateNotificationsShown = false
        )
        assertEquals(setOf(UpdateCleanupPolicy.Action.CANCEL_PENDING_WORK), actions)
    }

    @Test
    fun `没有残留任务时只清通知`() {
        assertEquals(
            setOf(UpdateCleanupPolicy.Action.CLEAR_UPDATE_NOTIFICATIONS),
            UpdateCleanupPolicy.actionsFor(139, 139, pendingWorkCount = 0, updateNotificationsShown = true)
        )
    }

    @Test
    fun `什么残留都没有时动作集合为空`() {
        assertTrue(
            UpdateCleanupPolicy.actionsFor(139, 139, pendingWorkCount = 0, updateNotificationsShown = false)
                .isEmpty()
        )
    }

    @Test
    fun `仍有新版本时绝不清理`() {
        // 反证：把 actionsFor 的「已是最新」判断写错（例如恒清理），这条会红
        assertTrue(
            UpdateCleanupPolicy.actionsFor(
                latestVersionCode = 140,
                installedVersionCode = 139,
                pendingWorkCount = 1,
                updateNotificationsShown = true
            ).isEmpty()
        )
    }

    // ---------- 2. Worker 侧的过时闸门 ----------

    @Test
    fun `目标版本不高于已安装版本即视为过时`() {
        assertTrue(UpdateCleanupPolicy.isObsolete(targetVersionCode = 139, installedVersionCode = 139))
        assertTrue(UpdateCleanupPolicy.isObsolete(targetVersionCode = 138, installedVersionCode = 139))
        assertTrue(UpdateCleanupPolicy.isObsolete(targetVersionCode = 1, installedVersionCode = 139))
    }

    @Test
    fun `目标版本更新或版本号未知时 isObsolete 不拦`() {
        assertFalse(UpdateCleanupPolicy.isObsolete(targetVersionCode = 140, installedVersionCode = 139))
        // 读不到版本号（<=0）时 isObsolete 只负责说「本函数不判定为过时」；
        // 这类历史任务由 Worker 的 fail-closed 闸门 shouldAbortMissingTarget 直接中止
        // （UpdateDownloadWorker.kt:233-244），不存在「宁可下完再校验」的 fail-open 路径。
        assertFalse(UpdateCleanupPolicy.isObsolete(targetVersionCode = 0, installedVersionCode = 139))
        assertTrue(UpdateCleanupPolicy.shouldAbortMissingTarget(0))
        assertFalse(UpdateCleanupPolicy.isObsolete(targetVersionCode = 140, installedVersionCode = 0))
    }

    // ---------- 3. 仓库接线 ----------

    @Test
    fun `自动与手动检查都在已是最新时清理`() {
        val body = readSource("src/main/java/sh/hnet/comfychair/update/UpdateRepository.kt")

        val autoStart = body.indexOf("fun autoCheckIfDue(")
        val manualStart = body.indexOf("fun manualCheck(")
        assertTrue("找不到 autoCheckIfDue", autoStart >= 0)
        assertTrue("找不到 manualCheck", manualStart > autoStart)

        val autoBody = body.substring(autoStart, manualStart)
        val manualBody = body.substring(manualStart)

        assertTrue(
            "autoCheckIfDue 的 UpToDate 分支必须调用 clearResidualUpdate",
            autoBody.contains("is UpdateChecker.CheckResult.UpToDate") &&
                autoBody.contains("clearResidualUpdate(")
        )
        assertTrue(
            "manualCheck 的 UpToDate 分支必须调用 clearResidualUpdate",
            manualBody.contains("is UpdateChecker.CheckResult.UpToDate") &&
                manualBody.contains("clearResidualUpdate(")
        )
    }

    @Test
    fun `检查失败不得触发清理（避免误杀进行中的下载）`() {
        val body = readSource("src/main/java/sh/hnet/comfychair/update/UpdateRepository.kt")
        val autoStart = body.indexOf("fun autoCheckIfDue(")
        val manualStart = body.indexOf("fun manualCheck(")
        val autoBody = body.substring(autoStart, manualStart)

        val failedBranch = autoBody.indexOf("is UpdateChecker.CheckResult.Failed ->")
        assertTrue("自动检查必须有 Failed 分支", failedBranch >= 0)
        // Failed 分支必须什么都不做（同一行就是 Unit）；它后面紧跟的就是方法结束
        assertTrue(
            "Failed 分支不得调用清理（断网 ≠ 已是最新）",
            autoBody.substring(failedBranch).contains("is UpdateChecker.CheckResult.Failed -> Unit")
        )
    }

    @Test
    fun `清理动作真的落到 WorkManager 与通知管理器上`() {
        val body = readSource("src/main/java/sh/hnet/comfychair/update/UpdateRepository.kt")
        val start = body.indexOf("fun clearResidualUpdate(")
        assertTrue("找不到 clearResidualUpdate", start >= 0)
        val end = body.indexOf("fun cancelUpdateWork(", start)
        assertTrue("找不到 cancelUpdateWork", end > start)
        val cleanupBody = body.substring(start, end)

        assertTrue(
            "必须按唯一名取消残留任务",
            cleanupBody.contains("cancelUpdateWork(context)") &&
                body.contains("cancelUniqueWork(DOWNLOAD_WORK_NAME)")
        )
        assertTrue(
            "必须清更新通知",
            cleanupBody.contains("UpdateNotifier.cancel(context)")
        )
    }

    @Test
    fun `Worker 在发请求前先比版本号（R3 的最后一道闸）`() {
        val body = readSource("src/main/java/sh/hnet/comfychair/update/UpdateDownloadWorker.kt")
        val guard = body.indexOf("UpdateCleanupPolicy.isObsolete(targetVersionCode, installedCode)")
        val request = body.indexOf("downloadWithResume(")
        assertTrue("Worker 必须有版本闸门", guard >= 0)
        assertTrue("版本闸门（$guard）必须排在真正下载（$request）之前", request > guard)
        assertTrue(
            "Worker 必须用 PackageManager 读已安装版本（BuildConfig 在旧进程里是过期值）；" +
                "F2 复核后统一委托 UpdateCleanupPolicy.installedVersionCode，不再保留第二份实现",
            body.contains("UpdateCleanupPolicy.installedVersionCode(context)")
        )
    }
}