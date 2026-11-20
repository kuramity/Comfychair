package sh.hnet.comfychair.cache

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「接线还在不在」的源码级钉子（5.2 媒体落盘 / 视频裁剪修复）。
 *
 * 为什么需要读源码：本轮的四个修复里，纯逻辑部分都已有独立单测
 * （[MediaRestoreLatchTest] / [MediaPersistVersionsTest] / [VideoTrimPolicyTest]），
 * 但「策略有没有真的接到生产路径上」这一层 JVM 单测够不着 ——
 * `MediaStateHolder` 要 Context / Bitmap，跑不进 JVM 测试。删掉一行调用，
 * 上面那三个测试仍会全绿，所以这里把关键调用点按源码文本钉住。
 *
 * 断言前把空白折叠成单空格，换行与缩进调整不会误报；
 * 单测工作目录是模块根（`app/`），与 [FeatureWiringContractTest] 同一口径。
 */
class MediaStateHolderWiringTest {

    private val holderPath = "src/main/java/sh/hnet/comfychair/cache/MediaStateHolder.kt"
    private val mainContainerPath = "src/main/java/sh/hnet/comfychair/MainContainerActivity.kt"

    private fun source(path: String): String {
        val file = File(path)
        assertTrue(
            "源文件不存在：${file.absolutePath} —— 单测工作目录应为 app/ 模块根；" +
                "若文件被移动，请同步更新本测试里的路径",
            file.exists()
        )
        return file.readText()
    }

    /** 取一段源码：从 [from] 起、到其后第一个 [to] 之前。 */
    private fun section(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("源码里找不到起点：$from", start >= 0)
        val end = text.indexOf(to, start + from.length)
        assertTrue("源码里找不到终点：$to（起点 $from 之后）", end >= 0)
        return text.substring(start, end)
    }

    private fun folded(text: String): String = text.replace(Regex("""\s+"""), " ").trim()

    /**
     * 去掉 `//` 行注释与 `/* */` 块注释 —— 注释里**提到**某个符号不代表代码用了它：
     * 例如 evict 的注释写着「见 [versionCounter]」，那不是对发号器的读写。
     */
    private fun codeOnly(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""//[^\n]*"""), " ")

    // ---------------- D3：restored 闩锁按「本次恢复」判断 ----------------

    @Test
    fun `loadFromDisk 用本轮令牌置就绪，而不是无条件置 true`() {
        val body = folded(
            section(
                source(holderPath),
                "suspend fun loadFromDisk(context: Context) {",
                "private suspend fun loadFromDiskInternal("
            )
        )

        assertTrue("开一轮恢复必须先取本轮令牌（复位闩锁）", body.contains("restoreLatch.beginRestore()"))
        assertTrue("只认本轮令牌：迟到完成不能把新一轮标成就绪", body.contains("restoreLatch.markRestored(token)"))
        assertTrue("令牌必须传进恢复主体，写回前才能校验本轮是否仍然有效", body.contains("loadFromDiskInternal(context, token)"))
    }

    @Test
    fun `恢复主体在写回内存前后都校验本轮令牌`() {
        val text = source(holderPath)
        val body = folded(section(text, "private suspend fun loadFromDiskInternal(", "private fun loadLatestVideoFile("))

        assertTrue(
            "换服务器 / 又开一轮之后必须停手，不能把旧服务器的数据写进当前档位（复核第 2 轮 NEW-4）",
            body.contains("restoreLatch.isCurrent(token)")
        )
        // 位图循环与视频回填各有一处校验：只留一处的话，另一处的写入仍会污染新一轮
        val checks = Regex("""restoreLatch\.isCurrent\(token\)""").findAll(body).count()
        assertTrue("至少两处校验（位图循环 + 视频回填），实际 $checks 处", checks >= 2)
    }

    @Test
    fun `清空媒体时闩锁收敛到就绪（避免无限占位）`() {
        val text = source(holderPath)
        val body = folded(section(text, "fun clearAll() {", "fun getCurrentTtvPromptId(): String?"))

        assertTrue(
            "清空后没有任何等待中的内容，必须收敛到就绪；置未就绪会让界面永远停在占位分支",
            body.contains("restoreLatch.settleReady()")
        )
    }

    @Test
    fun `切换服务器会复位闩锁，等新一轮恢复`() {
        val text = source(holderPath)
        val body = folded(
            section(text, "fun setCurrentServerId(serverId: String?) {", "fun getCurrentServerId(): String?")
        )

        assertTrue(
            "换服务器 = 换媒体作用域，上一轮的「已就绪」不再成立",
            body.contains("restoreLatch.resetForNewSession()")
        )
    }

    @Test
    fun `MainContainerActivity 在 setContent 之前同步复位闩锁`() {
        val text = source(mainContainerPath)
        val beginIndex = text.indexOf("MediaStateHolder.beginRestoreSession()")
        val setContentIndex = text.indexOf("setContent {")

        assertTrue("onCreate 必须显式开启新一轮恢复", beginIndex >= 0)
        assertTrue("找不到 setContent {", setContentIndex >= 0)
        assertTrue(
            "复位必须早于组合：loadFromDisk 是异步投递的，首帧可能先于它执行",
            beginIndex < setContentIndex
        )
    }

    // ---------------- D5：版本号全局单调、evict / 清内存不回退 ----------------

    @Test
    fun `版本发号器是全局单例，写入时不再按 key 建表`() {
        val text = source(holderPath)
        val putBitmap = folded(section(text, "fun putBitmap(", "fun markImagePromptId("))
        val putVideo = folded(section(text, "fun putVideoBytes(", "fun getVideoBytes("))

        assertTrue(putBitmap.contains("MediaPersistVersionOps.nextVersion(versionCounter)"))
        assertTrue(putVideo.contains("MediaPersistVersionOps.nextVersion(versionCounter)"))

        val code = codeOnly(text)
        assertFalse(
            "旧实现的按 key 版本表必须彻底消失（它的 evict/清内存删除是版本回退的来源）",
            code.contains("versions.remove") || code.contains("versions.clear()")
        )
    }

    @Test
    fun `evict 与清内存都不重置版本发号器`() {
        val text = source(holderPath)
        val evict = codeOnly(section(text, "fun evict(key: MediaKey) {", "suspend fun evictAndDeleteFromDisk("))
        val clearMemory = codeOnly(section(text, "private fun clearMemoryState() {", "fun clearDiskCache("))

        assertFalse("evict 只清脏标记，碰发号器就会造成版本回退", evict.contains("versionCounter"))
        assertFalse("清内存只清内容与脏标记，发号器必须保持单调", clearMemory.contains("versionCounter"))
    }

    // ---------------- D6：正在播放的视频纳入裁剪保护 ----------------

    @Test
    fun `裁剪同时保护播放方登记的视频与当前展示的视频`() {
        val text = source(holderPath)
        val body = folded(section(text, "private fun trimVideoFiles(", "private fun schedulePersist("))

        assertTrue("按 base 名维护的保护名单必须合并播放登记", body.contains("protectedBaseNames += playingVideoBaseNames"))
        assertTrue("当前展示的 TTV / ITV 仍需保护", body.contains("MediaKey.TtvVideo(it).baseFilename"))
        assertTrue("前缀与策略统一走 planForServer", body.contains("VideoTrimPolicy.planForServer("))
    }

    @Test
    fun `播放方有登记与注销两个入口`() {
        val text = source(holderPath)
        val register = folded(section(text, "fun registerPlayingVideo(", "fun unregisterPlayingVideo("))
        val unregister = folded(section(text, "fun unregisterPlayingVideo(", "private fun trimVideoFiles("))

        assertTrue(register.contains("playingVideoBaseNames +="))
        assertTrue(unregister.contains("playingVideoBaseNames -="))
    }

    // ---------------- D7：裁剪必须在 IO 上跑 ----------------

    @Test
    fun `persistToDisk 的裁剪在 withContext IO 块之内`() {
        val text = source(holderPath)
        val body = section(text, "suspend fun persistToDisk(context: Context) {", "suspend fun loadFromDisk(context: Context) {")

        val ioIndex = body.indexOf("withContext(Dispatchers.IO)")
        val trimIndex = body.indexOf("trimVideoFiles(context, serverId)")
        assertTrue("persistToDisk 必须有 IO 块", ioIndex >= 0)
        assertTrue("persistToDisk 必须裁一次视频", trimIndex >= 0)
        assertTrue(
            "裁剪在 IO 块之外就会落到调用方线程（SettingsViewModel 从主线程直接调 persistToDisk）",
            ioIndex < trimIndex
        )
    }

    // ---------------- D8：onStop 注释与异步实现一致 ----------------

    @Test
    fun `onStop 不再声称同步写盘`() {
        val text = source(mainContainerPath)
        val body = folded(section(text, "override fun onStop() {", "override fun onResume() {"))

        assertFalse("旧注释与新实现矛盾：这里早已是异步投递", body.contains("synchronously to ensure completion"))
        assertTrue(body.contains("MediaStateHolder.persistInBackground("))
    }

    // ---------------- D9 / NEW-7：启动期补裁 ----------------

    @Test
    fun `两条启动路径都走同一个非阻塞的裁剪投递入口`() {
        val text = source(holderPath)
        val memoryFirst = folded(section(text, "private suspend fun loadFromDiskInternal(", "private fun loadLatestVideoFile("))
        val diskFirst = folded(section(text, "fun discoverVideoPromptIds(context: Context) {", "fun hasBitmap("))

        // 内存优先：loadFromDiskInternal 里只投递，不在这里同步裁 ——
        // 同步裁会让它迟迟不返回，loadFromDisk 的 finally 也就不会 markRestored（首帧被拖住）
        assertTrue("内存优先启动路径必须补裁一次", memoryFirst.contains("scheduleStartupTrim(context, serverId)"))
        assertFalse(
            "不能在启动路径里同步裁：那会挡在 markRestored 前面（NEW-7）",
            memoryFirst.contains("trimVideoFiles(context, serverId)")
        )

        // 磁盘优先：同样只投递，且 markRestored 在投递之前
        assertTrue("磁盘优先启动路径必须补裁一次", diskFirst.contains("scheduleStartupTrim(context, serverId)"))
        assertTrue(
            "先置就绪、再投递裁剪：onCreate 是同步跑的，裁剪不能挡首帧",
            diskFirst.indexOf("restoreLatch.markRestored(token)") < diskFirst.indexOf("scheduleStartupTrim(")
        )
    }

    @Test
    fun `启动裁剪在后台作用域的 IO 上跑，且禁用媒体缓存时不动历史文件`() {
        val text = source(holderPath)
        val body = folded(section(text, "private fun scheduleStartupTrim(", "private fun schedulePersist("))

        assertTrue("必须投递到后台作用域，不能阻塞首帧", body.contains("persistScope.launch"))
        assertTrue("枚举与删除必须在 IO 上", body.contains("withContext(Dispatchers.IO)"))
        assertTrue(body.contains("trimVideoFiles(appContext, serverId)"))
        assertTrue("禁用媒体缓存时不裁（SharedPreferences 的读取也留在 IO 上）", body.contains("isMediaCacheDisabled"))
    }
}