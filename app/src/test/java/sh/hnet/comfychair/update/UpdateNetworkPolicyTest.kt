package sh.hnet.comfychair.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R6：非 WiFi 下载前必须确认（v4.18.0）。
 *
 * 用户实测：移动流量被大量消耗。这里只做「下载前确认」，**不做** WiFi 硬限制 ——
 * 用户可能就是要用流量，给他选择权。
 *
 * 三种组合必须覆盖：
 * - WiFi + 未确认 → 可以下载（不打扰）；
 * - 移动网络 + 未确认 → 必须先确认；
 * - 移动网络 + 已确认 → 可以下载。
 */
class UpdateNetworkPolicyTest {

    private fun readSource(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到源文件 $relative，尝试过：${candidates.map { it.absolutePath }}")
        return file.readText()
    }

    // ---------- 三种组合 ----------

    @Test
    fun `WiFi 未确认也直接下载`() {
        assertTrue(
            UpdateNetworkPolicy.canStartDownload(
                UpdateNetworkPolicy.NetworkType.WIFI,
                userConfirmed = false
            )
        )
        assertFalse(
            UpdateNetworkPolicy.needsConfirmation(
                UpdateNetworkPolicy.NetworkType.WIFI,
                userConfirmed = false
            )
        )
    }

    @Test
    fun `移动网络未确认时不允许开始下载`() {
        assertFalse(
            UpdateNetworkPolicy.canStartDownload(
                UpdateNetworkPolicy.NetworkType.CELLULAR,
                userConfirmed = false
            )
        )
        assertTrue(
            UpdateNetworkPolicy.needsConfirmation(
                UpdateNetworkPolicy.NetworkType.CELLULAR,
                userConfirmed = false
            )
        )
    }

    @Test
    fun `移动网络用户确认后允许下载`() {
        assertTrue(
            UpdateNetworkPolicy.canStartDownload(
                UpdateNetworkPolicy.NetworkType.CELLULAR,
                userConfirmed = true
            )
        )
        assertFalse(
            UpdateNetworkPolicy.needsConfirmation(
                UpdateNetworkPolicy.NetworkType.CELLULAR,
                userConfirmed = true
            )
        )
    }

    @Test
    fun `未知或取不到网络类型时按需要确认处理（宁可多问一句）`() {
        assertFalse(
            UpdateNetworkPolicy.canStartDownload(
                UpdateNetworkPolicy.NetworkType.OTHER,
                userConfirmed = false
            )
        )
        assertTrue(
            UpdateNetworkPolicy.canStartDownload(
                UpdateNetworkPolicy.NetworkType.OTHER,
                userConfirmed = true
            )
        )
    }

    // ---------- 接线 ----------

    @Test
    fun `弹窗在 startDownload 之前先过网络闸门`() {
        val body = readSource("src/main/java/sh/hnet/comfychair/ui/components/shared/UpdateDialog.kt")
        val requestStart = body.indexOf("fun requestDownload(")
        assertTrue("找不到 requestDownload（R6 的下载入口）", requestStart >= 0)
        val gate = body.indexOf("UpdateNetworkPolicy.canStartDownload(", requestStart)
        val startNow = body.indexOf("startDownloadNow(info)", requestStart)
        assertTrue("requestDownload 必须先过网络闸门", gate >= 0)
        assertTrue("WiFi 分支要在闸门通过后才真正开始下载", startNow > gate)
        assertTrue(
            "闸门要用真实网络类型判定",
            body.contains("UpdateNetworkPolicy.currentType(context)")
        )
        // 下载按钮必须走 requestDownload（带闸门），不得直接调 UpdateRepository.startDownload
        assertTrue(
            "主按钮必须走 requestDownload",
            body.contains("onClick = { pending?.let { requestDownload(it) } }")
        )
        assertTrue(
            "确认框的「继续」也必须走同一条下载入口",
            body.contains("startDownloadNow(confirmInfo)")
        )
        assertFalse(
            "按钮不得绕过闸门直接 startDownload",
            body.contains("onClick = { pending?.let { UpdateRepository.startDownload")
        )
    }

    @Test
    fun `确认框正文带体积与移动网络提示`() {
        val body = readSource("src/main/java/sh/hnet/comfychair/ui/components/shared/UpdateDialog.kt")
        assertTrue(
            "确认框必须显示体积（约 N MB）",
            body.contains("R.string.update_cellular_confirm_message,") &&
                body.contains("formatBytes(confirmInfo.apkSize)")
        )
        assertTrue(
            "确认框必须有取消按钮（取消则什么都不做）",
            body.contains("R.string.update_action_cancel")
        )
        assertTrue(
            "体积未知时也要有明确提示",
            body.contains("R.string.update_cellular_confirm_message_unknown")
        )
    }

    @Test
    fun `manifest 声明 ACCESS_NETWORK_STATE`() {
        val manifest = readSource("src/main/AndroidManifest.xml")
        assertTrue(
            "缺少 ACCESS_NETWORK_STATE 会导致 ConnectivityManager 查询受限",
            manifest.contains("android.permission.ACCESS_NETWORK_STATE")
        )
    }

    @Test
    fun `网络判定用 NetworkCapabilities 的 WiFi 与蜂窝传输类型`() {
        val body = readSource("src/main/java/sh/hnet/comfychair/update/UpdateNetworkPolicy.kt")
        assertTrue(body.contains("NetworkCapabilities.TRANSPORT_WIFI"))
        assertTrue(body.contains("NetworkCapabilities.TRANSPORT_CELLULAR"))
        // VPN（Clash TUN）场景下要能退回计费判定，否则 WiFi 上也会反复弹确认
        assertTrue(body.contains("isActiveNetworkMetered"))
    }
}