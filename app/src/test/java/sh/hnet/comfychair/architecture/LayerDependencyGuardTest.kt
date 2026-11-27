package sh.hnet.comfychair.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 镜像版依赖边界守卫（B1）：底层包不得反向依赖 viewmodel / ui / feature.tools。
 *
 * ViewModelUiDependencyTest 钉住「viewmodel 不得 import ui」，本测试钉住反方向——
 * 2026-11-28 盘点发现的 5 处裂纹（GalleryItem/ContentType/MaskPathData 错放 viewmodel 包，
 * 导致 storage/repository/queue/cache 反向 import viewmodel），修复后由本测试永久看守。
 *
 * model 层额外加严：作为最底层，它不得依赖 storage/cache/repository/connection/queue
 * 任何数据/传输层（2026-11-28 实测 model 几乎零出向依赖，此约束防止未来回流）。
 */
class LayerDependencyGuardTest {

    /** 底层包 → 禁止 import 的高层包前缀 */
    private val forbiddenByLayer: Map<String, List<String>> = mapOf(
        "model" to listOf(
            "viewmodel", "ui", "feature.tools",
            "storage", "cache", "repository", "connection", "queue"
        ),
        "storage" to listOf("viewmodel", "ui", "feature.tools"),
        "repository" to listOf("viewmodel", "ui", "feature.tools"),
        "cache" to listOf("viewmodel", "ui", "feature.tools"),
        "queue" to listOf("viewmodel", "ui", "feature.tools")
    )

    @Test
    fun `底层包不得反向依赖 viewmodel 或 ui`() {
        val base = File("src/main/java/sh/hnet/comfychair")
        assertTrue("找不到源码目录：${base.absolutePath}", base.isDirectory)

        val violations = mutableListOf<String>()
        for ((layer, forbidden) in forbiddenByLayer) {
            val dir = File(base, layer)
            if (!dir.isDirectory) continue
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    file.readLines().forEachIndexed { index, line ->
                        val trimmed = line.trimStart()
                        if (!trimmed.startsWith("import sh.hnet.comfychair.")) return@forEachIndexed
                        val target = trimmed.removePrefix("import sh.hnet.comfychair.")
                        if (forbidden.any { target.startsWith(it) }) {
                            violations.add("$layer/${file.name}:${index + 1}  $trimmed")
                        }
                    }
                }
        }

        assertTrue(
            "底层包反向依赖高层包（共 ${violations.size} 处），请把领域数据类移到 model 层：\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }
}
