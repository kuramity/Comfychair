package sh.hnet.comfychair.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 钉住 ViewModel 层的依赖边界：ViewModel 不得依赖 Compose UI 层。
 *
 * 背景（Task 6.4）：4 个生成页 ViewModel（TextToImage / ImageToImage / TextToVideo / ImageToVideo）
 * 原本各自 `import sh.hnet.comfychair.ui...`（共 8 处）：`CommonGenerationState` 与
 * `WorkflowItemBase` 两个契约类型定义在 ui 包里。两个类型已搬到
 * `model/contract/`（纯 Kotlin，不依赖 Compose），这条测试防止 UI 类型再回流到 ViewModel。
 */
class ViewModelUiDependencyTest {

    /** 禁止出现在 viewmodel 源码里的 import 前缀（按行首匹配，避免误判注释里的说明文字） */
    private val forbiddenImportPrefix = "import sh.hnet.comfychair.ui"

    @Test
    fun `viewmodel 包不得 import ui`() {
        // Gradle 单元测试的工作目录是 app 模块根目录（与现有 File("src/main/...") 测试一致）
        val viewModelDir = File("src/main/java/sh/hnet/comfychair/viewmodel")
        assertTrue("找不到 viewmodel 源码目录：${viewModelDir.absolutePath}", viewModelDir.isDirectory)

        val ktFiles = viewModelDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        assertTrue("viewmodel 目录下没有找到任何 .kt 文件：${viewModelDir.absolutePath}", ktFiles.isNotEmpty())

        val violations = mutableListOf<String>()
        for (file in ktFiles) {
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith(forbiddenImportPrefix)) {
                    violations.add("${file.name}:${index + 1}  $trimmed")
                }
            }
        }

        assertTrue(
            "viewmodel 包出现对 ui 层的 import（共 ${violations.size} 处），" +
                "请把需要的类型移到 model/contract 等非 UI 包：\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }
}
