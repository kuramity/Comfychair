package sh.hnet.comfychair.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P2 fail-closed：升级前入队的旧下载任务没有 `KEY_VERSION_CODE` 入参。
 *
 * 事故形态：WorkManager 在应用更新后重排历史任务，`getInt(KEY_VERSION_CODE, 0)`
 * 读成 0；旧的版本闸门 [UpdateCleanupPolicy.isObsolete] 要求
 * `targetVersionCode > 0` 才可能返回 true，于是闸门 fail-open，旧包在用户打开
 * App 之前继续下载（继续烧流量、通知栏进度条永远撤不掉）。
 *
 * 修复口径：**拿不到目标版本号就无法证明任务还需要跑** → 必须中止。
 * 这里钉住两件事：
 * 1. 纯判定的边界（0 / 负数 → 中止；正数 → 交给 isObsolete，行为不变）；
 * 2. Worker 真的在发任何网络请求之前接上了这条闸门，且中止分支不只是打日志。
 *
 * 反证：把 `shouldAbortMissingTarget` 改成恒 false，第一组测试立刻变红。
 */
class UpdateMissingTargetGateTest {

    private fun readSource(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到源文件 $relative，尝试过：${candidates.map { it.absolutePath }}")
        return file.readText()
    }

    private val workerPath = "src/main/java/sh/hnet/comfychair/update/UpdateDownloadWorker.kt"

    /** 截取一次调用的参数部分（按括号配对，避免被 `installedCode.toInt()` 的内层括号截断）。 */
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

    // ---------- 1. 纯判定 ----------

    @Test
    fun `目标 versionCode 缺失或非法时必须中止`() {
        assertTrue("0 是 getInt 的默认值，正是历史任务读出来的结果", UpdateCleanupPolicy.shouldAbortMissingTarget(0))
        assertTrue("负数同样不是合法版本号", UpdateCleanupPolicy.shouldAbortMissingTarget(-1))
        assertTrue(UpdateCleanupPolicy.shouldAbortMissingTarget(Int.MIN_VALUE))
    }

    @Test
    fun `有目标 versionCode 时交回原有闸门判断`() {
        // 反证：恒 true 会把所有正常下载都拦掉（正常路径行为必须不变）
        assertFalse(UpdateCleanupPolicy.shouldAbortMissingTarget(1))
        assertFalse(UpdateCleanupPolicy.shouldAbortMissingTarget(139))
        assertFalse(UpdateCleanupPolicy.shouldAbortMissingTarget(Int.MAX_VALUE))
    }

    @Test
    fun `缺失闸门与过时闸门互补且不重叠`() {
        // 缺失 → 中止；有值且 <= 已装 → 过时；有值且 > 已装 → 继续下载。
        assertTrue(UpdateCleanupPolicy.shouldAbortMissingTarget(0))
        assertFalse(UpdateCleanupPolicy.isObsolete(0, 139))
        assertTrue(UpdateCleanupPolicy.isObsolete(139, 139))
        assertFalse(UpdateCleanupPolicy.isObsolete(140, 139))
    }

    // ---------- 2. Worker 接线 ----------

    @Test
    fun `Worker 在发请求前用缺失闸门中止任务`() {
        val source = readSource(workerPath)
        val gate = source.indexOf("UpdateCleanupPolicy.shouldAbortMissingTarget(targetVersionCode)")
        val obsoleteGate = source.indexOf("UpdateCleanupPolicy.isObsolete(targetVersionCode, installedCode)")
        // 用**真实调用点**而不是函数定义：定义总在文件后部，拿它比较会让“闸门挪到
        // 调用之后、定义之前”这种回归照样通过（复核 F1-ORDER-ASSERT-WEAK）。
        // 这里从文件头找唯一的 `total = downloadWithResume(`（416 自愈与递归调用是
        // `return downloadWithResume(`，不会命中），再要求闸门排在它之前。
        val downloadCall = source.indexOf("total = downloadWithResume(")
        assertTrue("必须找到首个真实下载调用", downloadCall >= 0)
        assertTrue("Worker 必须调用 shouldAbortMissingTarget(targetVersionCode)", gate >= 0)
        assertTrue("缺失闸门必须排在过时闸门之前", obsoleteGate > gate)
        assertTrue(
            "缺失闸门（$gate）必须排在首个真实下载调用（$downloadCall）之前",
            gate < downloadCall
        )
    }

    @Test
    fun `缺失闸门的分支必须真的中止并通知用户`() {
        val source = readSource(workerPath)
        val gate = source.indexOf("if (UpdateCleanupPolicy.shouldAbortMissingTarget(targetVersionCode)) {")
        assertTrue("找不到缺失闸门的分支", gate >= 0)
        val obsoleteGate = source.indexOf("// R3：每次启动都再比一次版本号。", gate)
        assertTrue("找不到缺失闸门分支的结束边界", obsoleteGate > gate)
        val branch = source.substring(gate, obsoleteGate)

        assertTrue("必须记 warning 日志", branch.contains("DebugLogger.w("))
        assertTrue(
            "通知文案必须走字符串资源（F1 复核：不得硬编码中文，6 个语言目录都要有）",
            branch.contains("R.string.update_missing_target_cancelled")
        )
        assertFalse(
            "分支里不得再出现硬编码中文文案",
            branch.contains("更新任务信息缺失")
        )
        assertTrue("必须发一条『信息缺失，已取消』的通知", branch.contains("UpdateNotifier.notifyFailed("))
        assertTrue("必须撤掉残留的进度通知", branch.contains("UpdateNotifier.cancelProgress("))
        assertTrue("必须返回 failure（不能落进下载循环）", branch.contains("Result.failure()"))
        assertFalse("缺失分支里不得出现任何下载调用", branch.contains("downloadWithResume("))
    }
}