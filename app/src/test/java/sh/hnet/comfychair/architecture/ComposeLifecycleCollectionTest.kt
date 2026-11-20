package sh.hnet.comfychair.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 钉住 Compose 状态订阅的生命周期纪律：界面层必须用 collectAsStateWithLifecycle()。
 *
 * 背景：collectAsState() 在页面退到后台（STOPPED）后仍继续订阅上游 Flow，
 * 后台刷新纯属浪费；collectAsStateWithLifecycle() 会随 Lifecycle 自动暂停/恢复。
 * 本项目 ui/ 与 taglibrary/ 已全量迁移，这条测试防止旧的 collectAsState 再回流。
 *
 * 检测口径：只认行首的 import 语句（trimStart 后前缀匹配），
 * 避免误伤注释里提到的 collectAsState 字样。
 */
class ComposeLifecycleCollectionTest {

    /** 禁止出现的旧 import（按行首前缀匹配） */
    private val forbiddenImport = "import androidx.compose.runtime.collectAsState"

    @Test
    fun `main 源码不得 import compose 的 collectAsState`() {
        // Gradle 单元测试的工作目录是 app 模块根目录（与本目录其它源码扫描测试一致）
        val sourceRoot = File("src/main/java")
        assertTrue("找不到源码根目录：${sourceRoot.absolutePath}", sourceRoot.isDirectory)

        val ktFiles = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        assertTrue("src/main/java 下没有找到任何 .kt 文件：${sourceRoot.absolutePath}", ktFiles.isNotEmpty())

        val violations = mutableListOf<String>()
        for (file in ktFiles) {
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith(forbiddenImport)) {
                    violations.add("${file.path}:${index + 1}  $trimmed")
                }
            }
        }

        assertTrue(
            "以下文件仍在使用旧的 collectAsState（共 ${violations.size} 处），" +
                "请改用 collectAsStateWithLifecycle：\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun `ui 与 taglibrary 不得调用旧的 collectAsState`() {
        val sourceRoots = listOf(
            File("src/main/java/sh/hnet/comfychair/ui"),
            File("src/main/java/sh/hnet/comfychair/taglibrary")
        )

        val violations = mutableListOf<String>()
        for (root in sourceRoots) {
            assertTrue("找不到源码目录：${root.absolutePath}", root.isDirectory)
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    file.readLines().forEachIndexed { index, line ->
                        // 注释里提到的是不带括号的名字，这里只认带括号的真实调用
                        if (line.contains(".collectAsState()")) {
                            violations.add("${file.path}:${index + 1}  ${line.trim()}")
                        }
                    }
                }
        }

        assertTrue(
            "ui/ 与 taglibrary/ 仍有旧的 collectAsState() 调用（共 ${violations.size} 处）：\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }
}
