package sh.hnet.comfychair

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P2 长尾 + 7.3 的「接线还在不在」源码级钉子。
 *
 * 这些改动的共同点：核心逻辑都被抽成了可单测的纯函数 / 纯状态机
 * （[sh.hnet.comfychair.taglibrary.state.PromptBridge]、
 * [sh.hnet.comfychair.ui.components.ParticleFrameLoopGate]、
 * [sh.hnet.comfychair.taglibrary.data.TagDatabase] 的空间算式），
 * 但「生产代码有没有真的接上去」这一层，JVM 单测够不着：
 * 四个生成 ViewModel、Compose 屏幕、Manifest 都要 Android 环境。
 *
 * 因此沿用 [FeatureWiringContractTest] 的做法：读源码文本，把最容易被"顺手改回去"
 * 的那几行钉住。断言前不会折叠空白，只按**整行语句**比较，注释里出现同名字样不算数。
 */
class P2LongTailWiringTest {

    /** 单测工作目录是模块根（app/），与 FeatureWiringContractTest 同口径。 */
    private fun source(path: String): String {
        val file = File(path)
        assertTrue(
            "源文件不存在：${file.absolutePath} —— 单测工作目录应为 app/ 模块根",
            file.exists()
        )
        return file.readText()
    }

    /** 取一个成员函数的正文：从签名起，到下一处 4 空格缩进的右花括号为止。 */
    private fun memberBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("源码里找不到 $signature", start >= 0)
        val rest = source.substring(start)
        val end = rest.indexOf("\n    }")
        return if (end >= 0) rest.substring(0, end) else rest
    }

    /** 是否存在**恰好这一条语句**（忽略行首缩进）。 */
    private fun hasStatement(body: String, statement: String): Boolean =
        body.lines().any { it.trim() == statement }

    // ------------------------------------------------------------ P2-20

    @Test
    fun `四个生成页面的 ViewModel 销毁时必须注销 PromptBridge`() {
        // 修复前只有 init 里的 register，onCleared 不注销 —— 静态 map 长期钉住
        // 已销毁的 ViewModel。只加在 PromptBridge 里没用，必须四个 VM 都接上。
        val viewModels = listOf(
            "src/main/java/sh/hnet/comfychair/viewmodel/TextToImageViewModel.kt",
            "src/main/java/sh/hnet/comfychair/viewmodel/ImageToImageViewModel.kt",
            "src/main/java/sh/hnet/comfychair/viewmodel/TextToVideoViewModel.kt",
            "src/main/java/sh/hnet/comfychair/viewmodel/ImageToVideoViewModel.kt"
        )
        for (path in viewModels) {
            val text = source(path)
            assertTrue("$path 缺少 register（桥接本身没了）", text.contains("PromptBridge.register("))
            val body = memberBody(text, "override fun onCleared()")
            assertTrue(
                "$path 的 onCleared 必须有 PromptBridge.unregister(OWNER_ID)（P2-20 的修复点）",
                hasStatement(body, "PromptBridge.unregister(OWNER_ID)")
            )
            assertTrue(
                "$path 的 onCleared 必须先调用 super.onCleared()",
                hasStatement(body, "super.onCleared()")
            )
        }
    }

    // ------------------------------------------------------------ P2-19

    @Test
    fun `粒子帧循环在没有活跃特效时必须退出`() {
        // 修复前 while (true) 常驻，空闲也每帧唤醒。现在的退出条件是
        // update() 返回 false —— 删掉这行 break 就退回空转。
        val text = source("src/main/java/sh/hnet/comfychair/ui/components/ParticleOverlay.kt")
        assertTrue(
            "帧循环必须以 update() 的返回值作为退出判据（P2-19 的修复点）",
            hasStatement(text, "if (!alive) break")
        )
        assertTrue(
            "退出判据必须来自 system.update(dt)",
            hasStatement(text, "val alive = system.update(dt)")
        )
        assertFalse(
            "旧的「永远不退出」写法不许回来",
            hasStatement(text, "if (system.update(dt)) frameTick++")
        )
        // 空闲退出后必须有一次点击能重新拉起循环的入口
        assertTrue(
            "触点回调必须通过 gate 把循环重新拉起",
            text.contains("if (frameGate.requestFrame()) frameLoopTicket++")
        )
    }

    // ------------------------------------------------------------ P2-9

    @Test
    fun `设置页统计轮询必须跟 STARTED 生命周期走`() {
        val text = source("src/main/java/sh/hnet/comfychair/ui/screens/ServerSettingsScreen.kt")
        assertTrue(
            "ON_STOP 必须停掉统计轮询（P2-9：退后台仍在每 2 秒打 /system_stats）",
            text.lines().any { it.trim() == "Lifecycle.Event.ON_STOP -> viewModel.stopResourceAutoRefresh()" }
        )
        assertTrue(
            "ON_START 必须重新开始统计轮询",
            text.lines().any { it.trim() == "Lifecycle.Event.ON_START -> viewModel.startResourceAutoRefresh()" }
        )
    }

    // ------------------------------------------------------------ P2-17

    @Test
    fun `模型介绍表的装载必须在 IO 线程`() {
        val text = source("src/main/java/sh/hnet/comfychair/ui/screens/ModelIntroScreen.kt")
        val ioIndex = text.indexOf("withContext(Dispatchers.IO)")
        val loadIndex = text.indexOf("ModelIntroTable.ensureLoaded(context)")
        assertTrue("ModelIntroScreen 必须把装载放进 withContext(Dispatchers.IO)", ioIndex >= 0)
        assertTrue("ModelIntroScreen 里找不到 ensureLoaded 调用", loadIndex >= 0)
        assertTrue(
            "ensureLoaded 必须在 withContext(Dispatchers.IO) 之后（513KB JSON 不许在主线程解析）",
            ioIndex < loadIndex
        )
    }

    // ------------------------------------------------------------ P2-21 / P2-22

    @Test
    fun `解压前必须做空间预检`() {
        val text = source("src/main/java/sh/hnet/comfychair/taglibrary/data/TagDatabase.kt")
        val checkIndex = text.indexOf("ensureEnoughSpaceForExtract(target)")
        val writeIndex = text.indexOf("FileOutputStream(tmp)")
        assertTrue("TagDatabase 必须调用 ensureEnoughSpaceForExtract", checkIndex >= 0)
        assertTrue("找不到解压写盘语句", writeIndex >= 0)
        assertTrue(
            "空间预检必须发生在写 .tmp 之前（P2-21）",
            checkIndex < writeIndex
        )
        assertTrue("预检必须真的量可用空间（StatFs）", text.contains("StatFs(dir.path)"))
        assertTrue("预检失败必须抛带数字的可读错误", text.contains("存储空间不足"))
    }

    @Test
    fun `数据库版本号必须在 openDatabase 成功之后才写`() {
        val text = source("src/main/java/sh/hnet/comfychair/taglibrary/data/TagDatabase.kt")
        val openIndex = text.indexOf("val db = SQLiteDatabase.openDatabase(")
        val writeIndex = text.indexOf(".putString(KEY_DB_VERSION, assetVersion)")
        assertTrue("找不到 openDatabase 调用", openIndex >= 0)
        assertTrue("找不到版本号写入", writeIndex >= 0)
        assertTrue(
            "版本号必须写在 openDatabase 之后（P2-22：先写后开会让坏库被永久保留）",
            openIndex < writeIndex
        )
        assertTrue(
            "打开失败时必须清掉版本号，下次强制重新解压",
            memberBody(text, "fun open(context: Context): Result<SQLiteDatabase>").contains("clearVersion(context)")
        )
    }

    // ------------------------------------------------------------ 7.3 竖屏 / 仅手机

    @Test
    fun `Manifest 必须保留 portrait 并声明只面向手机`() {
        val manifest = source("src/main/AndroidManifest.xml")

        // 1) 既有的 portrait 声明不能删：它是 API < 36 设备上的运行时约束。
        //    只数"整行就是该属性"的行（允许同一行以 `/>` 收尾的写法）——
        //    注释里提到同名字样不算，否则删掉实现也能通过。
        val portraitLine = Regex("""^android:screenOrientation="portrait"\s*(/>)?$""")
        val portraitCount = manifest.lines().count { portraitLine.matches(it.trim()) }
        assertTrue(
            "portrait 声明被删了（当前应有 8 个 Activity 声明，实际 $portraitCount），7.3 方案 B 要求保留",
            portraitCount >= 8
        )

        // 2) supports-screens 必须是 manifest 的直接子元素，不能塞进 application。
        //    用「换行 + 4 空格缩进」定位真正的元素 —— 注释里也出现过 <application> 字样，
        //    裸 indexOf 会命中注释（第一版就是这么误判的）。
        val supportsIndex = manifest.indexOf("\n    <supports-screens")
        val applicationIndex = manifest.indexOf("\n    <application")
        assertTrue("Manifest 缺少 <supports-screens> 声明", supportsIndex >= 0)
        assertTrue("Manifest 缺少 <application> 元素", applicationIndex >= 0)
        assertTrue("<supports-screens> 是 <manifest> 的子元素，必须写在 <application> 之前", supportsIndex < applicationIndex)

        // 3) 「仅手机」语义
        assertTrue("smallScreens 必须为 true", manifest.contains("""android:smallScreens="true""""))
        assertTrue("normalScreens 必须为 true", manifest.contains("""android:normalScreens="true""""))
        assertTrue(
            "largeScreens 必须为 false（平板/折叠屏展开态未适配）",
            manifest.contains("""android:largeScreens="false"""")
        )
        assertTrue(
            "xlargeScreens 必须为 false（平板未适配）",
            manifest.contains("""android:xlargeScreens="false"""")
        )
        assertTrue(
            "requiresSmallestWidthDp 必须声明手机设计基准 320dp",
            manifest.contains("""android:requiresSmallestWidthDp="320"""")
        )

        // 4) Android 16（API 36）起，targetSdk>=36 的应用在 sw>=600dp 的屏幕上会被
        //    系统忽略 screenOrientation —— 没有这个临时 opt-out，大屏上竖屏锁定失效。
        //    官方说明它在 API 37 后不再生效，但在此之前必须声明着。
        //    注意：按**属性名**定位而不是裸单词 —— 上面的注释里也提到了这个属性名，
        //    用裸 indexOf 会命中注释（第一版就是这么误判的）。
        val propertyAttr =
            "android:name=\"android.window.PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY\""
        val propertyIndex = manifest.indexOf(propertyAttr)
        assertTrue(
            "必须声明 android.window.PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY，" +
                "否则 targetSdk 36 在大屏（sw>=600dp）上会忽略 portrait",
            propertyIndex >= 0
        )
        assertTrue(
            "该 property 必须写在 <application> 内（application 级 opt-out）",
            propertyIndex > applicationIndex
        )
        val propertyWindow = manifest.substring(
            propertyIndex,
            minOf(propertyIndex + propertyAttr.length + 120, manifest.length)
        )
        assertTrue(
            "该 property 的值必须为 true",
            propertyWindow.contains("""android:value="true"""")
        )
    }
}