package sh.hnet.comfychair.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P2-10：强制离线时不得访问更新服务器。
 *
 * 修复前 `autoCheckIfDue` 没有任何离线判断，`MainContainerActivity.onCreate` 更是直接调
 * `UpdateChecker.checkForUpdate` —— 用户点了「离线模式」，回前台/重连仍然会打一次更新清单请求。
 * 这里用源码断言钉住两条路径都受同一个离线闸门约束。
 */
class UpdateAutoCheckOfflineTest {

    private fun readSource(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到源文件 $relative，尝试过：${candidates.map { it.absolutePath }}")
        return file.readText()
    }

    @Test
    fun `自动检查在发请求前先判离线`() {
        val body = readSource("src/main/java/sh/hnet/comfychair/update/UpdateRepository.kt")

        val offlineGuard = body.indexOf("if (isOfflineBlocked()) return")
        // v4.18.0：检查入口统一成 checkDetailed（要区分「已是最新」与「检查失败」，R3），
        // 但它仍是同一个网络请求，必须排在离线短路之后。
        val networkCall = body.indexOf("UpdateChecker.checkDetailed(")
        assertTrue("autoCheckIfDue 必须调用 isOfflineBlocked()", offlineGuard >= 0)
        assertTrue(
            "离线短路（$offlineGuard）必须排在发请求（$networkCall）之前",
            networkCall > offlineGuard
        )
        assertTrue(
            "isOfflineBlocked 必须用 ConnectionManager.isOffline（实际离线态，含用户强制离线）",
            body.contains("ConnectionManager.isOffline")
        )
    }

    @Test
    fun `启动路径改走统一的自动检查入口`() {
        val body = readSource("src/main/java/sh/hnet/comfychair/MainContainerActivity.kt")
        assertFalse(
            "MainContainerActivity 不得再绕过离线闸门直接调 UpdateChecker.checkForUpdate",
            body.contains("UpdateChecker.checkForUpdate(")
        )
        assertTrue(
            "启动路径必须走 UpdateRepository.autoCheckIfDue（自带离线短路与节流），" +
                "且已装版本必须用 PackageManager 口径（F2 复核：BuildConfig 在旧进程里是过期值）",
            body.contains("UpdateRepository.autoCheckIfDue(") &&
                body.contains("UpdateCleanupPolicy.installedVersionCode(")
        )
        // 反证（F2-RELAXED-TEST-ONRESUME）：onResume 的 autoCheckIfDue 曾继续传
        // BuildConfig.VERSION_CODE，旧断言用 `contains("BuildConfig.VERSION_CODE")`
        // 反而把这条未修的路径钉成了"必须存在"。这里改成严格禁止。
        assertFalse(
            "MainContainerActivity 不得再用 BuildConfig.VERSION_CODE 作为已装版本（onResume 曾漏改）",
            body.contains("BuildConfig.VERSION_CODE")
        )
        // v4.18.0：自动检查需要 context 读「已提示配额」（R1）并按 R3 清理残留任务，
        // 因此签名调整为 (context, currentVersionCode)；两条路径都必须传 context。
        assertTrue(
            "autoCheckIfDue 必须带 context 调用（R1 配额 / R3 清理都需要它）",
            body.contains("this@MainContainerActivity,")
        )
    }

    @Test
    fun `自动检查不触发任何下载（R2）`() {
        val body = readSource("src/main/java/sh/hnet/comfychair/update/UpdateRepository.kt")
        val start = body.indexOf("fun autoCheckIfDue(")
        assertTrue("找不到 autoCheckIfDue", start >= 0)
        val end = body.indexOf("fun manualCheck(", start)
        assertTrue("找不到 manualCheck（autoCheckIfDue 的边界）", end > start)
        val autoBody = body.substring(start, end)

        assertFalse(
            "自动检查路径不得出现 startDownload（下载只能由用户显式点击触发）",
            autoBody.contains("startDownload(")
        )
        assertFalse(
            "自动检查路径不得直接入队 WorkManager（R2：去掉任何『进 App 就自动跑下载』）",
            autoBody.contains("enqueueUniqueWork(")
        )
    }
}