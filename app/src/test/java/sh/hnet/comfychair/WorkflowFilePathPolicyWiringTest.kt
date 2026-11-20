package sh.hnet.comfychair

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F4（P1-18 的收尾）：WorkflowManager 里三处「以 metadata 里的 filename 拼路径」的点
 * 必须走 [sh.hnet.comfychair.storage.WorkflowFilePolicy]，否则被旧版恢复写脏的 metadata
 * （如 `"filename": "../../../shared_prefs/AppSettings.xml"`）仍能：
 * - 编辑该工作流时把内容写到 `filesDir/user_workflows` 之外；
 * - 删除该工作流时删掉目录外的文件；
 * - 启动加载时读到目录外的内容。
 *
 * 这三处都依赖 Android `Context`（filesDir / SharedPreferences），无法用纯 JVM 直接跑，
 * 所以按仓库既有做法（见 architecture/UtilPackageDependencyTest）做**源码级契约**：
 * 断言危险的拼接方式已经消失、且每个 metadata 读取点附近都调用了安全策略。
 * [sh.hnet.comfychair.storage.WorkflowFilePolicy] 自身的行为由 WorkflowFilePolicyTest 覆盖。
 */
class WorkflowFilePathPolicyWiringTest {

    private val source: String =
        File("src/main/java/sh/hnet/comfychair/WorkflowManager.kt").readText()

    @Test
    fun `读 写 删 三处都走安全策略`() {
        val occurrences = source.split("WorkflowFilePolicy.resolveInDir").size - 1
        assertTrue("应至少 3 处调用 WorkflowFilePolicy.resolveInDir（读/写/删），实际 $occurrences 处", occurrences >= 3)
    }

    @Test
    fun `删除路径不再直接拼接 metadata 文件名`() {
        assertFalse(
            "删除工作流时不能再用 File(filesDir, \"user_workflows/\" + filename) 这种拼接",
            source.contains("\"\$USER_WORKFLOWS_DIR/\$it\"")
        )
        assertFalse(
            "不能保留 user_workflows/ 与变量直接拼接的写法",
            source.contains("\$USER_WORKFLOWS_DIR/\$")
        )
    }

    @Test
    fun `每个 metadata 文件名读取点附近都做了校验`() {
        val marker = "getString(\"filename\")"
        var index = source.indexOf(marker)
        var count = 0
        while (index >= 0) {
            count++
            val window = source.substring(index, minOf(source.length, index + 3000))
            assertTrue(
                "位置 $index 读取了 metadata 的 filename，但附近没有 WorkflowFilePolicy.resolveInDir 校验",
                window.contains("WorkflowFilePolicy.resolveInDir")
            )
            index = source.indexOf(marker, index + 1)
        }
        assertTrue("应至少有 2 个 metadata filename 读取点（加载与更新），实际 $count", count >= 2)
    }
}
