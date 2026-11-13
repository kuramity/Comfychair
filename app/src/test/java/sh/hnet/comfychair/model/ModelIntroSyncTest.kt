package sh.hnet.comfychair.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 「模型介绍数据从服务器热更新」的同步逻辑测试。
 *
 * 守的是四条承诺：
 * 1. **内容没变一个字节都不动**（不重复落盘、不重复重载）——刷新模型列表是高频动作，
 *    不能每次都写 250KB 到磁盘、更不能把内存里已装好的表重新解析一遍；
 * 2. **内容变了就落盘 + 重载**，且新条目立刻可查；
 * 3. **任何异常静默降级**——非 JSON、HTML 错误页、空响应都不能破坏既有介绍；
 * 4. **首次安装没有缓存时仍然回退到 APK 内置那份**。
 */
class ModelIntroSyncTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 服务器上的新版数据（比 setUp 里装的本地版新一天）。 */
    private val remoteJson = """
    {
      "version": 1,
      "generated_at": "2026-09-23 12:09:33",
      "count": 1,
      "models": {
        "newmodel_v1.safetensors": {
          "file": "NewModel_v1.safetensors",
          "rel": "checkpoints/SDXL/new/NewModel_v1.safetensors",
          "name": "NewModel",
          "base": "Illustrious",
          "author": "someone",
          "summary": "「NewModel」是新下载的模型…"
        }
      }
    }
    """.trimIndent()

    /** 本地已装好的旧版表（内存里），generated_at 比远端旧。 */
    private val localJson = """
    {
      "version": 1,
      "generated_at": "2026-09-22 08:00:00",
      "count": 1,
      "models": {
        "oldmodel_v1.safetensors": {
          "file": "OldModel_v1.safetensors",
          "name": "OldModel"
        }
      }
    }
    """.trimIndent()

    private lateinit var cacheFile: File

    @Before
    fun setUp() {
        cacheFile = File(tmp.root, ModelIntroTable.CACHE_FILE_NAME)
        ModelIntroTable.setTableJsonForTest(localJson)
    }

    @After
    fun tearDown() {
        ModelIntroTable.clearForTest()
    }

    // ---------------- ① 版本相同 → 不落盘、不重载 ----------------

    @Test
    fun `时间戳与版本都相同_跳过且不碰磁盘不重载表`() {
        // 磁盘上是另一份「哨兵」内容：一旦被覆盖就说明重复落盘了
        val sentinel = """{"version":1,"generated_at":"2026-09-22 08:00:00","models":{"sentinel.safetensors":{"file":"sentinel.safetensors"}}}"""
        cacheFile.writeText(sentinel)
        val mtimeBefore = cacheFile.lastModified()

        val result = ModelIntroSync.apply(
            remoteJson = localJson,
            cacheFile = cacheFile,
            localGeneratedAt = "2026-09-22 08:00:00",
            localVersion = 1,
        )

        assertEquals(ModelIntroSync.Result.SKIPPED, result)
        assertEquals("跳过时不得写盘", sentinel, cacheFile.readText())
        assertEquals("跳过时不得动文件时间戳", mtimeBefore, cacheFile.lastModified())
        // 内存里的表也不该被重载：本地独有的条目仍在，远端独有条目仍查不到
        assertNotNull(ModelIntroTable.lookup("OldModel_v1.safetensors"))
        assertNull(ModelIntroTable.lookup("NewModel_v1.safetensors"))
    }

    @Test
    fun `仅有版本号相同_时间戳不同时仍视为新数据`() {
        // 桌面端每次构建都会重写 generated_at —— 时间戳变了就是新内容
        assertTrue(
            ModelIntroSync.shouldApply(
                remoteGeneratedAt = "2026-09-23 12:09:33",
                remoteVersion = 1,
                localGeneratedAt = "2026-09-22 08:00:00",
                localVersion = 1,
            )
        )
    }

    @Test
    fun `远端时间戳更旧时不得拉取`() {
        // 只比"不等"的旧判据在这里会返回 true，把表**回退**成服务端的旧版本：
        // 发版把更新的数据写进 assets 之后（本地比服务器新），用户首次刷新就会撞上这条。
        // generated_at 是 YYYY-MM-DD HH:MM:SS，字典序即时间序。
        assertFalse(
            ModelIntroSync.shouldApply(
                remoteGeneratedAt = "2026-09-21 23:59:59",
                remoteVersion = 1,
                localGeneratedAt = "2026-09-22 08:00:00",
                localVersion = 1,
            )
        )
        assertFalse(
            "远端版本号更高也不能推翻更旧的时间戳（时间戳是主判据）",
            ModelIntroSync.shouldApply(
                remoteGeneratedAt = "2026-09-21 23:59:59",
                remoteVersion = 99,
                localGeneratedAt = "2026-09-22 08:00:00",
                localVersion = 1,
            )
        )
    }

    @Test
    fun `远端时间戳相同时不得拉取`() {
        assertFalse(
            ModelIntroSync.shouldApply(
                remoteGeneratedAt = "2026-09-22 08:00:00",
                remoteVersion = 1,
                localGeneratedAt = "2026-09-22 08:00:00",
                localVersion = 1,
            )
        )
    }

    @Test
    fun `远端时间戳比本地旧时_一个字节都不动`() {
        // 磁盘上是「哨兵」内容：一旦被覆盖就说明更旧的数据也能写盘（表被回退）
        val sentinel = """{"version":1,"generated_at":"2026-09-22 08:00:00","models":{"sentinel.safetensors":{"file":"sentinel.safetensors"}}}"""
        cacheFile.writeText(sentinel)
        val mtimeBefore = cacheFile.lastModified()

        val staleRemote = """
        {
          "version": 1,
          "generated_at": "2026-09-21 23:59:59",
          "count": 1,
          "models": { "stale.safetensors": { "file": "stale.safetensors", "name": "Stale" } }
        }
        """.trimIndent()

        val result = ModelIntroSync.apply(
            remoteJson = staleRemote,
            cacheFile = cacheFile,
            localGeneratedAt = "2026-09-22 08:00:00",
            localVersion = 1,
        )

        assertEquals("更旧的服务器数据必须 SKIP", ModelIntroSync.Result.SKIPPED, result)
        assertEquals("跳过时不得写盘", sentinel, cacheFile.readText())
        assertEquals("跳过时不得动文件时间戳", mtimeBefore, cacheFile.lastModified())
        // 内存里的表也不该被重载成旧版：旧条目仍在，服务端那份的新键不该出现
        assertNotNull(ModelIntroTable.lookup("OldModel_v1.safetensors"))
        assertNull("更旧的数据不得装进内存表", ModelIntroTable.lookup("stale.safetensors"))
        // 自查：这条 null 不是因为查错键 —— 手动装一遍就能查到（否则上面的断言等于没测）
        assertTrue(ModelIntroTable.reloadFrom(staleRemote))
        assertNotNull("键名写错会让上面的 null 变成假阴性", ModelIntroTable.lookup("stale.safetensors"))
    }

    // ---------------- ② 版本不同 → 落盘 + 重载 ----------------

    @Test
    fun `远端更新时落盘内容正确且表被重载`() {
        val result = ModelIntroSync.apply(
            remoteJson = remoteJson,
            cacheFile = cacheFile,
            localGeneratedAt = "2026-09-22 08:00:00",
            localVersion = 1,
        )

        assertEquals(ModelIntroSync.Result.UPDATED, result)
        assertTrue("应把服务器数据写到 filesDir 缓存", cacheFile.exists())
        assertEquals("落盘内容必须与远端一字不差", remoteJson, cacheFile.readText())
        // 表被重载：新条目可查、旧条目消失
        assertEquals("NewModel", ModelIntroTable.lookup("NewModel_v1.safetensors")?.name)
        assertNull(ModelIntroTable.lookup("OldModel_v1.safetensors"))
        assertEquals("2026-09-23 12:09:33", ModelIntroTable.generatedAt)
        assertEquals(1, ModelIntroTable.version)
    }

    @Test
    fun `缓存文件不存在时也能落盘并重载`() {
        assertFalse(cacheFile.exists())
        val result = ModelIntroSync.apply(
            remoteJson = remoteJson,
            cacheFile = cacheFile,
            localGeneratedAt = null,
            localVersion = 0,
        )
        assertEquals(ModelIntroSync.Result.UPDATED, result)
        assertEquals(remoteJson, cacheFile.readText())
        assertNotNull(ModelIntroTable.lookup("NewModel_v1.safetensors"))
    }

    @Test
    fun `落盘不残留临时文件`() {
        ModelIntroSync.apply(remoteJson, cacheFile, "2026-09-22 08:00:00", 1)
        val leftovers = tmp.root.listFiles()?.filter { it.name != ModelIntroTable.CACHE_FILE_NAME }
        assertTrue("缓存目录里不该有中间文件：$leftovers", leftovers.isNullOrEmpty())
    }

    // ---------------- ③ 失败静默降级 ----------------

    @Test
    fun `非 JSON 响应被拒绝且既有表不受影响`() {
        for (bad in listOf("", "   ", "<!DOCTYPE html><html>502 Bad Gateway</html>", "{ this is not json ")) {
            val result = ModelIntroSync.apply(
                remoteJson = bad,
                cacheFile = cacheFile,
                localGeneratedAt = "2026-09-22 08:00:00",
                localVersion = 1,
            )
            assertEquals("坏响应应判为无效：$bad", ModelIntroSync.Result.INVALID, result)
        }
        assertFalse("坏响应不得落盘", cacheFile.exists())
        assertNotNull("既有表必须原样保留", ModelIntroTable.lookup("OldModel_v1.safetensors"))
        assertEquals("2026-09-22 08:00:00", ModelIntroTable.generatedAt)
    }

    @Test
    fun `空模型表不得覆盖既有介绍`() {
        // 服务器构建脚本异常时会写出 models 为空的表 —— 接受它等于把用户已有的介绍全清空
        val empty = """{"version": 2, "generated_at": "2026-09-24 00:00:00", "count": 0, "models": {}}"""
        val result = ModelIntroSync.apply(empty, cacheFile, "2026-09-22 08:00:00", 1)
        assertEquals(ModelIntroSync.Result.INVALID, result)
        assertFalse(cacheFile.exists())
        assertNotNull(ModelIntroTable.lookup("OldModel_v1.safetensors"))
    }

    @Test
    fun `缺 models 字段的 JSON 被拒绝`() {
        val result = ModelIntroSync.apply(
            """{"version": 2, "generated_at": "2026-09-24 00:00:00"}""",
            cacheFile, "2026-09-22 08:00:00", 1,
        )
        assertEquals(ModelIntroSync.Result.INVALID, result)
    }

    @Test
    fun `坏缓存文件不阻塞下一份好数据的写入`() {
        cacheFile.writeText("{ 半截写入 ")
        val result = ModelIntroSync.apply(remoteJson, cacheFile, null, 0)
        assertEquals(ModelIntroSync.Result.UPDATED, result)
        assertEquals(remoteJson, cacheFile.readText())
        assertNotNull(ModelIntroTable.lookup("NewModel_v1.safetensors"))
    }

    // ---------------- ④ 比对判据边界 ----------------

    @Test
    fun `本地无生成时间时应当拉取`() {
        // 老版本 APK 内置的表没有 generated_at —— 必须视为「老数据」，去服务器拿
        assertTrue(
            ModelIntroSync.shouldApply(
                remoteGeneratedAt = "2026-09-23 12:09:33",
                remoteVersion = 1,
                localGeneratedAt = null,
                localVersion = 1,
            )
        )
    }

    @Test
    fun `本地为空字符串生成时间时也应当拉取`() {
        assertTrue(
            ModelIntroSync.shouldApply(
                remoteGeneratedAt = "2026-09-23 12:09:33",
                remoteVersion = 1,
                localGeneratedAt = "",
                localVersion = 1,
            )
        )
    }

    @Test
    fun `远端无生成时间时退化为版本号比较`() {
        // 远端同版本 → 不拉；远端版本更高 → 拉
        assertFalse(ModelIntroSync.shouldApply(null, 1, "2026-09-22 08:00:00", 1))
        assertTrue(ModelIntroSync.shouldApply(null, 2, "2026-09-22 08:00:00", 1))
        assertTrue("本地无时间戳时版本号相同也应拉取", ModelIntroSync.shouldApply(null, 1, null, 1))
    }

    // ---------------- ⑤ 首次安装回退 assets ----------------

    @Test
    fun `无缓存时用内置 assets 装载表`() {
        var assetRead = 0
        val chosen = ModelIntroTable.resolveInitialJson(null) { assetRead++; localJson }
        assertEquals("首次安装应回退到 APK 内置表", localJson, chosen)
        assertEquals(1, assetRead)
    }

    @Test
    fun `缓存可用时不读 assets`() {
        var assetRead = 0
        val chosen = ModelIntroTable.resolveInitialJson(remoteJson) { assetRead++; localJson }
        assertEquals(remoteJson, chosen)
        assertEquals("缓存可用就不该白读 assets", 0, assetRead)
    }

    @Test
    fun `缓存损坏时回退 assets`() {
        // 缓存写坏（半截写入 / 服务器返回的 HTML 被存了下来）不能让介绍整体消失
        for (broken in listOf("{ 半截写入 ", "<html>502</html>", """{"version":2,"models":{}}""")) {
            val chosen = ModelIntroTable.resolveInitialJson(broken) { localJson }
            assertEquals("缓存损坏应回退内置表：$broken", localJson, chosen)
        }
    }

    @Test
    fun `缓存与内置都不可用时返回 null`() {
        assertNull(ModelIntroTable.resolveInitialJson("{ 坏 ") { null })
        assertNull(ModelIntroTable.resolveInitialJson(null) { null })
    }

    @Test
    fun `resolveInitialJson 选中的内容能直接装表`() {
        val chosen = ModelIntroTable.resolveInitialJson(null) { remoteJson }
        ModelIntroTable.reloadFrom(chosen!!)
        assertNotNull(ModelIntroTable.lookup("NewModel_v1.safetensors"))
    }

    // ---------------- ⑥ 公开重载入口 ----------------

    @Test
    fun `reloadFrom 装载成功返回 true 并更新版本信息`() {
        assertTrue(ModelIntroTable.reloadFrom(remoteJson))
        assertEquals("2026-09-23 12:09:33", ModelIntroTable.generatedAt)
        assertEquals("NewModel", ModelIntroTable.lookup("NewModel_v1.safetensors")?.name)
    }

    @Test
    fun `reloadFrom 遇到坏数据返回 false 且保留原表`() {
        assertFalse(ModelIntroTable.reloadFrom("{ 坏数据 "))
        assertFalse(ModelIntroTable.reloadFrom("""{"version":3,"models":{}}"""))
        assertNotNull("坏数据不得清空既有介绍", ModelIntroTable.lookup("OldModel_v1.safetensors"))
    }

    // ---------------- ⑦ 远端地址 ----------------

    @Test
    fun `远端地址指向 output 下的介绍文件`() {
        assertEquals(
            "/view?filename=model_intros.json&subfolder=comfychair&type=output",
            ModelIntroSync.REMOTE_PATH,
        )
    }
}
