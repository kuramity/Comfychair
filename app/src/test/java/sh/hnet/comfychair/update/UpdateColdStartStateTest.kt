package sh.hnet.comfychair.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R2 + R4：下载不自动续传、已下载的包可复用（v4.18.0）。
 *
 * 用户原话：「我希望安装时进度条只需要跑一次，相当于我在下载到一半时退出了再进去
 * 需要手动点击下载更新才会继续下载」「下载完只需要点击安装就好，如果在安装前退出
 * 安装程序，只需要再次点击检查更新就可以把之前安装好的包给拉出来」。
 *
 * 两个方向的判据都在 [UpdateRestorePolicy] 里（纯函数），这里钉住：
 * 1. 进程重启后**绝不可能**恢复成「下载中」——.part 存在也不行；
 * 2. 只有「sha 一致 + 版本号更高 + 包名可信」的已下载包才会被复用为待安装；
 * 3. 仓库/Worker 的接线：冷启动不联网、安装包旁车必须包含 versionCode。
 */
class UpdateColdStartStateTest {

    private fun readSource(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到源文件 $relative，尝试过：${candidates.map { it.absolutePath }}")
        return file.readText()
    }

    // ---------- 1. R2：冷启动不得是「下载中」 ----------

    @Test
    fun `冷启动时残留 part 绝不能被恢复成下载中`() {
        for (partExists in listOf(true, false)) {
            val phase = UpdateRestorePolicy.coldStartPhase(
                localPackageReusable = false,
                downloadSessionActive = false,
                partExists = partExists
            )
            assertNotEquals(
                "part=$partExists 时不得恢复成 DOWNLOADING（R2：不自动续传）",
                UpdatePhase.DOWNLOADING,
                phase
            )
            assertEquals(
                "没有可复用包、本进程也没在下载 → 状态必须是 IDLE",
                UpdatePhase.IDLE,
                phase
            )
        }
    }

    @Test
    fun `只有本进程内确实在下载时才保留下载中`() {
        // Activity 重建（切服务器、登出再登录）不该打断用户刚发起的下载
        assertEquals(
            UpdatePhase.DOWNLOADING,
            UpdateRestorePolicy.coldStartPhase(
                localPackageReusable = false,
                downloadSessionActive = true,
                partExists = true
            )
        )
    }

    @Test
    fun `有可复用包时状态是待安装`() {
        assertEquals(
            UpdatePhase.READY_TO_INSTALL,
            UpdateRestorePolicy.coldStartPhase(
                localPackageReusable = true,
                downloadSessionActive = false,
                partExists = false
            )
        )
    }

    // ---------- 2. R4：已下载包的复用判据 ----------

    @Test
    fun `校验通过且版本更高且包名可信的包直接复用`() {
        assertEquals(
            UpdateRestorePolicy.LocalPackageVerdict.REUSE,
            UpdateRestorePolicy.decideLocalPackage(
                shaVerdict = UpdateRestorePolicy.Verdict.VERIFIED,
                packageVersionCode = 140,
                installedVersionCode = 139,
                packageTrusted = true
            )
        )
    }

    @Test
    fun `版本不高于已安装版本的包一律丢弃`() {
        // 这两条正是「已经装好新版本还提示安装」的根源
        assertEquals(
            UpdateRestorePolicy.LocalPackageVerdict.DISCARD,
            UpdateRestorePolicy.decideLocalPackage(
                UpdateRestorePolicy.Verdict.VERIFIED, 139, 139, packageTrusted = true
            )
        )
        assertEquals(
            UpdateRestorePolicy.LocalPackageVerdict.DISCARD,
            UpdateRestorePolicy.decideLocalPackage(
                UpdateRestorePolicy.Verdict.VERIFIED, 138, 139, packageTrusted = true
            )
        )
    }

    @Test
    fun `缺版本号旁车的包不可复用`() {
        // 旧版本下载的包没有 version 旁车 → 无法证明「更新」，fail-closed
        assertEquals(
            UpdateRestorePolicy.LocalPackageVerdict.DISCARD,
            UpdateRestorePolicy.decideLocalPackage(
                UpdateRestorePolicy.Verdict.VERIFIED, null, 139, packageTrusted = true
            )
        )
    }

    @Test
    fun `校验不通过或包名不可信的包不可复用`() {
        assertEquals(
            UpdateRestorePolicy.LocalPackageVerdict.DISCARD,
            UpdateRestorePolicy.decideLocalPackage(
                UpdateRestorePolicy.Verdict.MISMATCH, 140, 139, packageTrusted = true
            )
        )
        assertEquals(
            UpdateRestorePolicy.LocalPackageVerdict.DISCARD,
            UpdateRestorePolicy.decideLocalPackage(
                UpdateRestorePolicy.Verdict.NO_EXPECTED_SHA, 140, 139, packageTrusted = true
            )
        )
        assertEquals(
            UpdateRestorePolicy.LocalPackageVerdict.DISCARD,
            UpdateRestorePolicy.decideLocalPackage(
                UpdateRestorePolicy.Verdict.VERIFIED, 140, 139, packageTrusted = false
            )
        )
    }

    // ---------- 3. 接线 ----------

    @Test
    fun `冷启动恢复不联网、不入队、也不把 part 当下载中`() {
        val body = readSource("src/main/java/sh/hnet/comfychair/update/UpdateRepository.kt")
        val start = body.indexOf("fun restoreFromDisk(")
        assertTrue("找不到 restoreFromDisk", start >= 0)
        val end = body.indexOf("fun restoreLocalPackage(", start)
        assertTrue("找不到 restoreLocalPackage", end > start)
        val restoreBody = body.substring(start, end)

        assertTrue(
            "冷启动必须走 coldStartPhase 判定（而不是『有 .part 就下载中』）",
            restoreBody.contains("UpdateRestorePolicy.coldStartPhase(")
        )
        assertTrue(
            "冷启动必须显式取消残留 Worker（R2）",
            restoreBody.contains("cancelUpdateWork(context)")
        )
        assertTrue(
            "冷启动必须清更新通知",
            restoreBody.contains("cancelUpdateNotificationsIfShown(context)")
        )
        assertTrue("冷启动不得发起网络检查", !restoreBody.contains("checkDetailed("))
        assertTrue("冷启动不得入队下载", !restoreBody.contains("enqueueUniqueWork("))
        assertTrue("冷启动不得调用 startDownload", !restoreBody.contains("startDownload("))
    }

    @Test
    fun `本地包复用路径不触发下载也不触发网络`() {
        val body = readSource("src/main/java/sh/hnet/comfychair/update/UpdateRepository.kt")
        val start = body.indexOf("fun restoreLocalPackage(")
        assertTrue("找不到 restoreLocalPackage", start >= 0)
        val end = body.indexOf("fun clearResidualUpdate(", start)
        assertTrue("找不到 clearResidualUpdate", end > start)
        val reuseBody = body.substring(start, end)

        assertTrue("必须走 decideLocalPackage 的四条件判定", reuseBody.contains("UpdateRestorePolicy.decideLocalPackage("))
        assertTrue("复用命中必须直接置待安装", reuseBody.contains("phase = UpdatePhase.READY_TO_INSTALL"))
        assertTrue("复用路径不得联网", !reuseBody.contains("UpdateChecker."))
        assertTrue("复用路径不得入队下载", !reuseBody.contains("enqueueUniqueWork("))
        assertTrue("复用路径不得调用 startDownload", !reuseBody.contains("startDownload("))
    }

    @Test
    fun `Worker 把 versionCode 与安装包一起落盘`() {
        val body = readSource("src/main/java/sh/hnet/comfychair/update/UpdateDownloadWorker.kt")
        assertTrue(
            "必须存在 versionCode 旁车文件名常量",
            body.contains("const val VERSION_FILE_NAME")
        )
        assertTrue(
            "下载完成后必须把目标 versionCode 写进旁车（否则冷启动无法证明『更新』）",
            body.contains("versionFile(applicationContext).writeText(")
        )
        assertTrue(
            "必须从入参读取目标 versionCode",
            body.contains("inputData.getInt(KEY_VERSION_CODE, 0)")
        )
    }

    @Test
    fun `startDownload 把 versionCode 会话代次一并写进入参`() {
        val body = readSource("src/main/java/sh/hnet/comfychair/update/UpdateRepository.kt")
        assertTrue(
            "入队时必须带 KEY_VERSION_CODE（Worker 的 R3 闸门依赖它）",
            body.contains("UpdateDownloadWorker.KEY_VERSION_CODE to info.versionCode")
        )
        assertTrue(
            "入队时必须带会话代次（迟到回调识别）",
            body.contains("UpdateDownloadWorker.KEY_SESSION_GENERATION to generation")
        )
        assertTrue(
            "任务必须打上 UPDATE_WORK_TAG（R3 统计 pending 数量）",
            body.contains(".addTag(UPDATE_WORK_TAG)")
        )
    }

    @Test
    fun `安装失败的三种原因都有明确提示且仍走原生安装器`() {
        val body = readSource("src/main/java/sh/hnet/comfychair/update/AppInstaller.kt")
        // §4.6：包名不符 / 文件不可读 / 系统安装器不可用 —— 三种都要给用户看得见的原因
        assertTrue("包名不符要提示", body.contains("R.string.update_error_install_invalid"))
        assertTrue("文件不可读要提示", body.contains("R.string.update_error_install_missing"))
        assertTrue("系统安装器不可用要提示", body.contains("R.string.update_error_install_unavailable"))
        // 安装仍走原生 ACTION_VIEW，不自己实现安装界面
        assertTrue("必须仍用 ACTION_VIEW 拉起系统安装器", body.contains("Intent(Intent.ACTION_VIEW)"))
    }
}