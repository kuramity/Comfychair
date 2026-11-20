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
        val networkCall = body.indexOf("UpdateChecker.checkForUpdate(")
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
            "启动路径必须走 UpdateRepository.autoCheckIfDue（自带离线短路与节流）",
            body.contains("UpdateRepository.autoCheckIfDue(BuildConfig.VERSION_CODE)")
        )
    }
}