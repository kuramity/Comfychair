package sh.hnet.comfychair.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R1：一个版本只主动弹一次（v4.18.0）。
 *
 * 用户原话：「只要有新版本推出，每次进入都会弹一次，应该点击一次忽略后需要手动点击
 * 检查更新才会弹出更新弹窗（只一次），然后一个版本只主动弹一次，不管用户对这次
 * 主动弹的更新弹窗有什么操作，后续都不弹出来了」。
 *
 * 修复前的判定只存在于 `UpdateDialogHost` 的 `remember { mutableStateOf(false) }` 里，
 * 进程一换就重置 → 每次进 App 都弹。这里钉住三件事：
 * 1. 纯判定：同版本第二次自动检查不弹；出现更高版本重新获得配额；手动检查不受限；
 * 2. 存储：键名是派工单约定的 `update.lastPromptedVersionCode`；
 * 3. 接线：自动检查必须先读配额、落盘后才 publishAvailable；手动检查不走配额闸门。
 */
class UpdatePromptPolicyTest {

    private fun readSource(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到源文件 $relative，尝试过：${candidates.map { it.absolutePath }}")
        return file.readText()
    }

    // ---------- 1. 纯判定 ----------

    @Test
    fun `同一版本第二次自动检查不再弹`() {
        val prompted = UpdatePromptPolicy.markPrompted(139)

        // 第一次：从未提示过（0）→ 弹
        assertTrue(UpdatePromptPolicy.shouldPromptAutomatically(139, 0))
        // 第二次：同版本已提示过 → 不弹
        assertFalse(UpdatePromptPolicy.shouldPromptAutomatically(139, prompted))
        assertEquals(
            UpdatePromptPolicy.PromptDecision.SKIP,
            UpdatePromptPolicy.decide(manual = false, latestCode = 139, lastPromptedCode = prompted)
        )
    }

    @Test
    fun `出现更高版本时重新获得一次主动提示配额`() {
        val prompted = UpdatePromptPolicy.markPrompted(139)
        assertTrue(UpdatePromptPolicy.shouldPromptAutomatically(140, prompted))
        assertTrue(UpdatePromptPolicy.shouldPromptAutomatically(200, prompted))
        assertEquals(
            UpdatePromptPolicy.PromptDecision.PROMPT,
            UpdatePromptPolicy.decide(manual = false, latestCode = 140, lastPromptedCode = prompted)
        )
    }

    @Test
    fun `更旧的版本号不会重新弹`() {
        // 回滚/清单缓存过期等情况下远端 code 变小，不该再打扰用户
        assertFalse(UpdatePromptPolicy.shouldPromptAutomatically(138, 139))
        assertFalse(UpdatePromptPolicy.shouldPromptAutomatically(139, 140))
    }

    @Test
    fun `手动检查不受配额限制`() {
        val prompted = UpdatePromptPolicy.markPrompted(139)
        // 自动路径已经弹过并记录 → 自动检查跳过
        assertEquals(
            UpdatePromptPolicy.PromptDecision.SKIP,
            UpdatePromptPolicy.decide(manual = false, latestCode = 139, lastPromptedCode = prompted)
        )
        // 同一个版本，用户自己点「检查更新」→ 仍然弹
        assertEquals(
            UpdatePromptPolicy.PromptDecision.PROMPT,
            UpdatePromptPolicy.decide(manual = true, latestCode = 139, lastPromptedCode = prompted)
        )
    }

    @Test
    fun `markPrompted 只记录版本号本身`() {
        // 记录的是 versionCode（整型），不是 versionName 字符串 —— 后者字典序比较会出错
        assertEquals(139, UpdatePromptPolicy.markPrompted(139))
        assertEquals(0, UpdatePromptPolicy.DEFAULT_LAST_PROMPTED_VERSION_CODE)
    }

    // ---------- 2. 存储键名 ----------

    @Test
    fun `配额键名与派工单约定一致且走 SharedPreferences 持久化`() {
        val body = readSource("src/main/java/sh/hnet/comfychair/update/UpdatePromptPolicy.kt")
        assertTrue(
            "键名必须是 update.lastPromptedVersionCode（派工单指定）",
            body.contains("const val KEY_LAST_PROMPTED_VERSION_CODE = \"update.lastPromptedVersionCode\"")
        )
        assertTrue(
            "必须用 SharedPreferences 持久化（换进程/杀后台都不能重置）",
            body.contains("getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)")
        )
        assertTrue("写盘必须落 apply()/commit()", body.contains(".apply()"))
    }

    // ---------- 3. 仓库接线 ----------

    @Test
    fun `自动检查先读配额、落盘后才发布弹窗`() {
        val body = readSource("src/main/java/sh/hnet/comfychair/update/UpdateRepository.kt")
        val start = body.indexOf("fun autoCheckIfDue(")
        assertTrue("找不到 autoCheckIfDue", start >= 0)
        val end = body.indexOf("fun manualCheck(", start)
        assertTrue("找不到 manualCheck", end > start)
        val autoBody = body.substring(start, end)

        val readQuota = autoBody.indexOf("UpdatePromptStore.lastPromptedVersionCode(")
        val decideCall = autoBody.indexOf("UpdatePromptPolicy.decide(")
        val mark = autoBody.indexOf("UpdatePromptStore.markPrompted(")
        val publish = autoBody.indexOf("publishAvailable(")

        assertTrue("自动检查必须读已提示配额", readQuota >= 0)
        assertTrue("自动检查必须走 UpdatePromptPolicy.decide 判定", decideCall >= 0)
        assertTrue("弹出前必须先把配额落盘（杀进程也不重复弹）", mark >= 0)
        assertTrue("读配额（$readQuota）必须排在判定（$decideCall）之前", decideCall > readQuota)
        assertTrue(
            "落盘（$mark）必须排在发布弹窗（$publish）之前",
            publish > mark
        )
        // 反证：把 shouldPromptAutomatically 改成恒 true，UpdatePromptPolicyTest 的
        // 「同一版本第二次自动检查不再弹」会直接红。
    }

    @Test
    fun `手动检查走 manualCheck 且不受配额闸门约束`() {
        val body = readSource("src/main/java/sh/hnet/comfychair/update/UpdateRepository.kt")
        val start = body.indexOf("fun manualCheck(")
        assertTrue("找不到 manualCheck", start >= 0)
        val manualBody = body.substring(start)

        assertTrue(
            "手动检查命中更新时必须直接 publishAvailable（不经过 shouldPromptAutomatically）",
            manualBody.contains("publishAvailable(result.info)")
        )
        assertFalse(
            "手动检查不得用 UpdatePromptPolicy.decide 把用户自己发起的检查挡掉",
            manualBody.contains("PromptDecision.SKIP")
        )
    }

    @Test
    fun `设置页的检查更新按钮改走 manualCheck`() {
        val body = readSource("src/main/java/sh/hnet/comfychair/ui/screens/AboutSettingsScreen.kt")
        assertTrue(
            "AboutSettingsScreen 必须调用 UpdateRepository.manualCheck（统一 R1/R3/R4 入口）",
            body.contains("UpdateRepository.manualCheck(")
        )
        assertFalse(
            "不得再绕过仓库直接调 UpdateChecker.checkForUpdate（那样不会清理残留任务）",
            body.contains("UpdateChecker.checkForUpdate(")
        )
        assertTrue(
            "「已是最新」提示必须由 ManualCheckResult.AlreadyLatest 驱动",
            body.contains("ManualCheckResult.AlreadyLatest")
        )
    }

    @Test
    fun `忽略弹窗后状态收回 IDLE，不会回到主界面又弹一次`() {
        // 只隐藏弹窗会把状态留在 AVAILABLE，主界面的 UpdateDialogHost 会再弹一次
        val host = readSource("src/main/java/sh/hnet/comfychair/update/UpdateDialogHost.kt")
        assertTrue(
            "主界面弹窗的忽略必须调用 UpdateRepository.reset()",
            host.contains("UpdateRepository.reset()")
        )
        val about = readSource("src/main/java/sh/hnet/comfychair/ui/screens/AboutSettingsScreen.kt")
        assertTrue(
            "设置页弹窗的忽略同样必须 reset（否则返回主界面会再弹）",
            about.contains("UpdateRepository.reset()")
        )
    }
}