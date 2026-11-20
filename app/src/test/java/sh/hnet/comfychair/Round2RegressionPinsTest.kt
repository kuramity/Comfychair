package sh.hnet.comfychair

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第 2 轮复核三项的源码级钉子：R2-F1（按钮文字基线）、R2-F3（查看器配置变化）、
 * R2-F5（孤儿媒体清理接入维护流程）。
 *
 * 三项都落在 JVM 单测够不着的位置：
 * - R2-F1 是 Compose 样式派生链，只有真机截图能验观感；这里钉住「基线必须是 labelLarge」；
 * - R2-F3 是 Activity 生命周期，JVM 上跑不了 recreation；这里钉住 Manifest 的 configChanges；
 * - R2-F5 是启动维护流程的接线，这里钉住「调用点在 cleanFileCaches 内、且用 validServerIds」。
 *
 * 口径与 [sh.hnet.comfychair.cache.MediaStateHolderWiringTest] 一致：
 * 单测工作目录是模块根（`app/`），断言前把空白折叠。
 */
class Round2RegressionPinsTest {

    private fun source(path: String): String {
        val file = File(path)
        assertTrue("源文件不存在：${file.absolutePath} —— 单测工作目录应为 app/ 模块根", file.exists())
        return file.readText()
    }

    private fun folded(text: String): String = text.replace(Regex("""\s+"""), " ").trim()

    /** 去掉注释，避免「注释里提到旧写法」被当成实际代码。 */
    private fun codeOnly(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""//[^\n]*"""), " ")

    // ============================================================
    // R2-F1：主 CTA 文字基线必须是 labelLarge（SemiBold），不是 bodyLarge（Regular）
    // ============================================================

    private val buttonFiles = listOf(
        "src/main/java/sh/hnet/comfychair/ui/components/ServerConnectButton.kt",
        "src/main/java/sh/hnet/comfychair/ui/components/ConnectionSplitButton.kt",
        "src/main/java/sh/hnet/comfychair/ui/components/GenerationButton.kt"
    )

    @Test
    fun `三处主 CTA 的文字样式从 labelLarge 派生`() {
        for (path in buttonFiles) {
            val code = codeOnly(source(path))
            assertTrue(
                "$path 必须用 typography.labelLarge.copy(...) 派生 —— " +
                    "本项目 labelLarge 是 SemiBold/行高 20sp，bodyLarge 是正文 Regular/24sp",
                Regex("""MaterialTheme\.typography\.labelLarge\.copy\(""").containsMatchIn(code)
            )
            assertFalse(
                "$path 不得用 bodyLarge 派生按钮文字（会把字重掉到 Regular）",
                Regex("""MaterialTheme\.typography\.bodyLarge""").containsMatchIn(code)
            )
        }
    }

    @Test
    fun `按钮文字字号仍走 Dimensions 的 token（不是硬编码 18f）`() {
        for (path in buttonFiles) {
            val code = codeOnly(source(path))
            assertTrue(
                "$path 的字号必须仍取自 Dimensions.ButtonTextSize",
                Regex("""fontSize = Dimensions\.ButtonTextSize""").containsMatchIn(code)
            )
        }
    }

    // ============================================================
    // R2-F3：单图查看器在语言 / 深色模式切换时不得重建
    // ============================================================

    @Test
    fun `MediaViewerActivity 声明了 locale-layoutDirection-uiMode 的 configChanges`() {
        val manifest = folded(codeOnly(source("src/main/AndroidManifest.xml")))
        val start = manifest.indexOf("android:name=\".MediaViewerActivity\"")
        assertTrue("Manifest 里找不到 MediaViewerActivity", start >= 0)
        // 取该 activity 标签的剩余部分（到自闭合 /> 为止）
        val tagEnd = manifest.indexOf("/>", start)
        assertTrue("MediaViewerActivity 标签没有正常闭合", tagEnd > start)
        val tag = manifest.substring(start, tagEnd)

        assertTrue("缺少 android:configChanges，语言/深色模式切换会重建查看器、图片丢失",
            tag.contains("android:configChanges="))
        for (flag in listOf("locale", "layoutDirection", "uiMode")) {
            assertTrue("configChanges 缺少 $flag", tag.contains(flag))
        }
        assertTrue("screenOrientation 必须保留（横竖屏切换仍交给系统锁定）",
            tag.contains("android:screenOrientation"))
    }

    // ============================================================
    // R2-F5：孤儿媒体清理接到启动维护流程里，且只在 decide 通过后
    // ============================================================

    @Test
    fun `cleanFileCaches 里调用了 cleanupOrphanedMediaFiles 并传入 validServerIds`() {
        val code = folded(codeOnly(source("src/main/java/sh/hnet/comfychair/storage/PreferencesMaintenance.kt")))
        assertTrue(
            "cleanFileCaches 必须调用 MediaStateHolder.cleanupOrphanedMediaFiles(context, validServerIds)",
            Regex(
                """MediaStateHolder\.cleanupOrphanedMediaFiles\(context, validServerIds\)"""
            ).containsMatchIn(code)
        )
    }

    @Test
    fun `清理调用排在 Abort 判定之后（performMaintenance 先判 decide 再清理）`() {
        val code = folded(codeOnly(source("src/main/java/sh/hnet/comfychair/storage/PreferencesMaintenance.kt")))
        val decideAt = code.indexOf("MaintenancePlan.decide(")
        val abortAt = code.indexOf("is MaintenancePlan.Decision.Abort")
        val cleanAt = code.indexOf("cleanFileCaches(context, validServerIds)")

        assertTrue("找不到 MaintenancePlan.decide 调用", decideAt >= 0)
        assertTrue("找不到 Abort 早退分支", abortAt >= 0)
        assertTrue("找不到 cleanFileCaches 调用", cleanAt >= 0)
        assertTrue("decide 必须排在 Abort 判定之前", decideAt < abortAt)
        assertTrue("清理必须在 Abort 早退之后才执行", abortAt < cleanAt)
    }
}