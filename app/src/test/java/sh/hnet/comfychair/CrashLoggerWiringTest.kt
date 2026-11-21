package sh.hnet.comfychair

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 崩溃日志链路的「接线还在不在」源码级钉子（P0-R5）。
 *
 * [sh.hnet.comfychair.util.CrashLoggerTest] 只覆盖了文件名生成与裁剪纯函数；
 * 如果有人把 `Application` 里的 install、系统 handler 的交还、或者设置页的
 * list/exportAll 接线删掉，纯函数单测仍然全绿。这里按 [PagerInertiaWiringTest]
 * 同款写法读源码，钉住四段关键接线：
 *
 * 1. ComfyChairApp.onCreate 在 super.onCreate 之前 install；
 * 2. AndroidManifest 挂上了自定义 Application；
 * 3. CrashLogger 保存并**无条件交还**系统原 handler，写盘是 .tmp + fsync + rename；
 * 4. 设置页真的调用了 listCrashFiles / exportAll 且用 ClipboardManager。
 *
 * 崩溃处理器本身是 Android 运行时对象，JVM 单测不能真的 install（会污染测试进程），
 * 所以"有没有装、有没有把异常交回去"只能在源码层钉。
 */
class CrashLoggerWiringTest {

    private val appPath = "src/main/java/sh/hnet/comfychair/ComfyChairApp.kt"
    private val manifestPath = "src/main/AndroidManifest.xml"
    private val loggerPath = "src/main/java/sh/hnet/comfychair/util/CrashLogger.kt"
    private val settingsPath = "src/main/java/sh/hnet/comfychair/ui/screens/ApplicationSettingsScreen.kt"

    /** 单测工作目录是模块根（app/），与 PagerInertiaWiringTest 同口径。 */
    private fun source(path: String): String {
        val file = File(path)
        assertTrue(
            "源文件不存在：${file.absolutePath} —— 单测工作目录应为 app/ 模块根",
            file.exists()
        )
        return file.readText()
    }

    private fun normalized(body: String): String =
        body.replace(Regex("\\s+"), " ").trim()

    private fun containsNormalized(body: String, fragment: String): Boolean =
        normalized(body).contains(normalized(fragment))

    @Test
    fun `Application 在 super 之前安装崩溃处理器`() {
        val body = normalized(source(appPath))
        val installAt = body.indexOf("CrashLogger.install(this)")
        val superAt = body.indexOf("super.onCreate()")
        assertTrue("ComfyChairApp 必须调用 CrashLogger.install(this)", installAt >= 0)
        assertTrue("ComfyChairApp 必须调用 super.onCreate()", superAt >= 0)
        assertTrue(
            "CrashLogger.install 必须在 super.onCreate() 之前 —— 越晚安装越可能漏掉启动期崩溃",
            installAt in 0 until superAt
        )
    }

    @Test
    fun `清单挂上了自定义 Application`() {
        assertTrue(
            "AndroidManifest 的 <application> 必须声明 android:name=\".ComfyChairApp\"",
            containsNormalized(source(manifestPath), "android:name=\".ComfyChairApp\"")
        )
    }

    @Test
    fun `崩溃处理器保存并无条件交还系统原 handler`() {
        val body = source(loggerPath)
        assertTrue(
            "install 必须先把系统默认 handler 存到 previous",
            containsNormalized(body, "val previous = Thread.getDefaultUncaughtExceptionHandler()")
        )
        assertTrue(
            "必须无条件调用 previous?.uncaughtException(thread, throwable)，不许吞掉系统默认行为",
            containsNormalized(body, "previous?.uncaughtException(thread, throwable)")
        )
        assertTrue(
            "必须安装到 Thread.setDefaultUncaughtExceptionHandler",
            containsNormalized(body, "Thread.setDefaultUncaughtExceptionHandler")
        )
    }

    @Test
    fun `崩溃日志是原子写且只保留十份`() {
        val body = source(loggerPath)
        assertTrue(
            "写盘必须 flush + fd.sync() 后再改名（.tmp 原子写）",
            containsNormalized(body, "out.flush()") &&
                containsNormalized(body, "out.fd.sync()") &&
                containsNormalized(body, "tmp.renameTo(target)")
        )
        assertTrue(
            "最多保留 10 份",
            containsNormalized(body, "MAX_CRASH_FILES = 10")
        )
        assertTrue(
            "日志目录必须是 filesDir/crash",
            containsNormalized(body, "File(context.filesDir, CRASH_DIR_NAME)")
        )
    }

    @Test
    fun `设置页接了崩溃日志的列出与导出`() {
        val body = source(settingsPath)
        assertTrue(
            "设置页必须列出 CrashLogger.listCrashFiles",
            containsNormalized(body, "CrashLogger.listCrashFiles(context)")
        )
        assertTrue(
            "复制按钮必须导出 CrashLogger.exportAll",
            containsNormalized(body, "CrashLogger.exportAll(context)")
        )
        assertTrue(
            "复制必须走 ClipboardManager",
            containsNormalized(body, "ClipboardManager::class.java")
        )
    }
}