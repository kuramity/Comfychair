package sh.hnet.comfychair.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 钉住 util 包的依赖边界：util 只能放纯工具，不得反向依赖业务层。
 *
 * 背景（Task 6.3）：`sh.hnet.comfychair.util` 原本有 6 个文件、23 处 `import ...workflow.*`，
 * 还有 2 个文件依赖 `model.LoraSelection`。业务类已经搬到
 * `workflow/{validation,mapping,convert,layout,graph}` 与 `lora` 包；
 * 这条测试防止以后又有人把业务逻辑塞回 util。
 */
class UtilPackageDependencyTest {

    /** 禁止出现在 util 源码里的 import 前缀（按行首匹配，避免误判注释里的说明文字） */
    private val forbiddenImportPrefixes = listOf(
        "import sh.hnet.comfychair.workflow",
        "import sh.hnet.comfychair.model",
        "import sh.hnet.comfychair.ui",
        "import sh.hnet.comfychair.viewmodel"
    )

    @Test
    fun `util 包不得 import workflow model ui viewmodel`() {
        // Gradle 单元测试的工作目录是 app 模块根目录（与现有 File("src/main/...") 测试一致）
        val utilDir = File("src/main/java/sh/hnet/comfychair/util")
        assertTrue("找不到 util 源码目录：${utilDir.absolutePath}", utilDir.isDirectory)

        val ktFiles = utilDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        assertTrue("util 目录下没有找到任何 .kt 文件：${utilDir.absolutePath}", ktFiles.isNotEmpty())

        val violations = mutableListOf<String>()
        for (file in ktFiles) {
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trimStart()
                val hit = forbiddenImportPrefixes.firstOrNull { trimmed.startsWith(it) }
                if (hit != null) {
                    violations.add("${file.name}:${index + 1}  ${trimmed}")
                }
            }
        }

        assertTrue(
            "util 包出现对业务层的 import（共 ${violations.size} 处），请把相关类移到 workflow/ 或 lora/ 包：\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }
}
