package sh.hnet.comfychair.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 已安装版本号必须用 PackageManager 读，不能用 BuildConfig（复核 low）。
 *
 * [BuildConfig.VERSION_CODE] 是**编译期常量**：装完新版本后旧进程可能还在跑，
 * 读出来的是旧版本号。后果是把「已经装好的版本」当成待更新/待下载，并让版本闸门
 * 与 Worker 的口径不一致（Worker 早就在用 PackageManager）。
 *
 * 这里钉住：
 * 1. 共享工具真的按 PackageManager 口径实现，异常时才回退 BuildConfig 并记 warning；
 * 2. 冷启动恢复 / onCreate 自动检查 / onResume 自动检查 / 手动检查四处调用点都改用它；
 * 3. Worker 不再保留第二份 catch→0 的实现，统一委托共享工具。
 *
 * 反证：把任一路径改回 `BuildConfig.VERSION_CODE`，第二组测试立刻变红。
 */
class InstalledVersionCodeWiringTest {

    private fun readSource(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到源文件 $relative，尝试过：${candidates.map { it.absolutePath }}")
        return file.readText()
    }

    /** 截取一次调用的参数部分（按括号配对）。 */
    private fun callOf(source: String, call: String): String {
        val start = source.indexOf(call)
        assertTrue("源码里找不到调用 $call", start >= 0)
        var depth = 0
        for (i in start until source.length) {
            when (source[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
        }
        error("调用 $call 的括号没有闭合")
    }

    /** 截取**全部**同名调用的参数部分（按括号配对），用于覆盖多条调用路径。 */
    private fun callsOf(source: String, call: String): List<String> {
        val out = mutableListOf<String>()
        var start = source.indexOf(call)
        while (start >= 0) {
            var depth = 0
            var end = -1
            for (i in start until source.length) {
                when (source[i]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) {
                            end = i
                            break
                        }
                    }
                }
            }
            assertTrue("调用 $call 的括号没有闭合", end > start)
            out.add(source.substring(start, end + 1))
            start = source.indexOf(call, end + 1)
        }
        return out
    }

    private val policyPath = "src/main/java/sh/hnet/comfychair/update/UpdateCleanupPolicy.kt"
    private val workerPath = "src/main/java/sh/hnet/comfychair/update/UpdateDownloadWorker.kt"
    private val mainPath = "src/main/java/sh/hnet/comfychair/MainContainerActivity.kt"
    private val aboutPath = "src/main/java/sh/hnet/comfychair/ui/screens/AboutSettingsScreen.kt"

    // ---------- 1. 共享工具实现 ----------

    @Test
    fun `共享工具用 PackageManager 的 longVersionCode`() {
        val source = readSource(policyPath)
        assertTrue(
            "必须提供 fun installedVersionCode(context: Context): Long",
            source.contains("fun installedVersionCode(context: Context): Long")
        )
        assertTrue(
            "必须读 PackageManager 的 longVersionCode（不接受 BuildConfig 作为主路径）",
            source.contains("getPackageInfo(context.packageName, 0).longVersionCode")
        )
        // 回退分支：只有 catch 里才允许出现 BuildConfig，并且要记 warning
        val catchStart = source.indexOf("catch (e: Exception) {", source.indexOf("fun installedVersionCode("))
        assertTrue("installedVersionCode 必须有异常回退", catchStart >= 0)
        val catchBody = source.substring(catchStart, source.indexOf("\n    }", catchStart))
        assertTrue("回退到 BuildConfig.VERSION_CODE.toLong()", catchBody.contains("BuildConfig.VERSION_CODE.toLong()"))
        assertTrue("回退必须记 warning（不能静默）", catchBody.contains("DebugLogger.w("))
    }

    // ---------- 2. 调用点接线 ----------

    @Test
    fun `所有 autoCheckIfDue 调用与冷启动恢复都不再传 BuildConfig`() {
        val source = readSource(mainPath)

        val restoreArgs = callOf(source, "UpdateRepository.restoreFromDisk(")
        assertTrue(
            "restoreFromDisk 必须用 PackageManager 口径：$restoreArgs",
            restoreArgs.contains("installedCode.toInt()")
        )
        assertFalse("restoreFromDisk 不得再传 BuildConfig.VERSION_CODE", restoreArgs.contains("BuildConfig.VERSION_CODE"))

        // F2 复核：onCreate 与 onResume 两条 autoCheckIfDue 都必须走 PackageManager，
        // 不能只断言第一次调用（旧测试只取第一个，放过了 :441 的 onResume 路径）。
        val autoCalls = callsOf(source, "UpdateRepository.autoCheckIfDue(")
        assertEquals(
            "autoCheckIfDue 应在 onCreate 与 onResume 各调用一次（实际 ${autoCalls.size} 次）",
            2,
            autoCalls.size
        )
        for (args in autoCalls) {
            assertTrue("每次 autoCheckIfDue 都必须用 PackageManager 口径：$args", args.contains("installedCode.toInt()"))
            assertFalse("autoCheckIfDue 不得再传 BuildConfig.VERSION_CODE：$args", args.contains("BuildConfig.VERSION_CODE"))
        }

        assertEquals(
            "MainContainerActivity 不得再出现 BuildConfig.VERSION_CODE（onResume 曾漏改）",
            0,
            Regex("BuildConfig\\.VERSION_CODE").findAll(source).count()
        )
        assertTrue(
            "已装版本必须只读一次并复用（避免两次读取之间版本变化导致参数不一致）",
            source.contains("val installedCode = UpdateCleanupPolicy.installedVersionCode(this@MainContainerActivity)")
        )
    }

    @Test
    fun `Worker 与共享工具共用同一读取口径`() {
        val source = readSource(workerPath)
        // F2 复核：Worker 不能再保留第二份 catch→0 的实现，否则回退语义会分叉。
        assertTrue(
            "Worker 必须委托 UpdateCleanupPolicy.installedVersionCode(context)",
            source.contains("internal fun installedVersionCode(context: Context): Int =") &&
                source.contains("UpdateCleanupPolicy.installedVersionCode(context).toInt()")
        )
        assertFalse(
            "Worker 不得再自己 getPackageInfo（PackageManager 读取只应存在于共享工具）",
            source.contains("getPackageInfo(context.packageName, 0)")
        )
    }

    @Test
    fun `手动检查不再传 BuildConfig`() {
        val source = readSource(aboutPath)
        val args = callOf(source, "UpdateRepository.manualCheck(")
        assertTrue(
            "manualCheck 必须用 PackageManager 口径：$args",
            args.contains("UpdateCleanupPolicy.installedVersionCode(appContext).toInt()")
        )
        assertFalse("manualCheck 不得再传 BuildConfig.VERSION_CODE", args.contains("BuildConfig.VERSION_CODE"))
    }
}