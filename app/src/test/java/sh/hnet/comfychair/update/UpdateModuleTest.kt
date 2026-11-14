package sh.hnet.comfychair.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.ui.components.shared.formatBytes

/**
 * 内置更新模块的纯逻辑单测（v4.6.0）。
 *
 * 覆盖硬性约束的四个可测核心：
 * 1. versionCode 整型比较（约束 7：禁止 versionName 字符串比较）
 * 2. update.json 解析（约束 8 的字段契约）
 * 3. Content-Range 总长解析（约束 9：续传总长只信 Content-Range）
 * 4. apkUrl 白名单校验（安全规范）
 */
class UpdateModuleTest {

    // ---------- 约束 7：versionCode 整型比较 ----------

    @Test
    fun `remote newer code triggers update`() {
        assertTrue(UpdateChecker.shouldUpdate(currentVersionCode = 108, remoteVersionCode = 109))
        assertTrue(UpdateChecker.shouldUpdate(107, 200))
    }

    @Test
    fun `equal or older code does not trigger update`() {
        assertFalse(UpdateChecker.shouldUpdate(109, 109))
        assertFalse(UpdateChecker.shouldUpdate(109, 108))
    }

    @Test
    fun `versionCode comparison is numeric not lexicographic`() {
        // versionName 字符串比较的经典错误："v4.10.0" < "v4.9.0"（字典序）。
        // 整型比较不受影响：code 110 > code 109。
        assertTrue(UpdateChecker.shouldUpdate(109, 110))
    }

    // ---------- 约束 8：update.json 解析 ----------

    @Test
    fun `parses complete manifest`() {
        val info = UpdateInfo.fromJson(
            """{"versionCode":109,"versionName":"v4.6.0",""" +
                """"apkUrl":"https://updates.example.com/apk/update/a.apk",""" +
                """"apkSize":159305371,"sha256":"abc123","force":false}"""
        )
        assertEquals(109, info.versionCode)
        assertEquals("v4.6.0", info.versionName)
        assertEquals("https://updates.example.com/apk/update/a.apk", info.apkUrl)
        assertEquals(159305371L, info.apkSize)
        assertEquals("abc123", info.sha256)
        assertFalse(info.force)
    }

    @Test
    fun `defaults for optional fields`() {
        val info = UpdateInfo.fromJson(
            """{"versionCode":109,"apkUrl":"https://updates.example.com/a.apk"}"""
        )
        assertEquals(109, info.versionCode)
        assertEquals("", info.versionName)
        assertEquals(-1L, info.apkSize)
        assertEquals("", info.sha256)
        assertFalse(info.force)
        // v4.10.1：老清单没有 changelog / notes 也不能炸，弹窗那边按"空"处理
        assertTrue(info.changelog.isEmpty())
        assertEquals("", info.notes)
    }

    // ---------- v4.10.1：更新说明（notes + changelog） ----------

    @Test
    fun `parses notes and changelog array`() {
        val info = UpdateInfo.fromJson(
            """{"versionCode":118,"versionName":"v4.10.1",""" +
                """"apkUrl":"https://updates.example.com/a.apk",""" +
                """"notes":"第一段说明\n第二段说明",""" +
                """"changelog":["修复 A","修复 B"]}"""
        )
        assertEquals("第一段说明\n第二段说明", info.notes)
        assertEquals(listOf("修复 A", "修复 B"), info.changelog)
    }

    @Test
    fun `changelog accepts a single string`() {
        val info = UpdateInfo.fromJson(
            """{"versionCode":118,"apkUrl":"https://updates.example.com/a.apk",""" +
                """"changelog":"只有一句话"}"""
        )
        assertEquals(listOf("只有一句话"), info.changelog)
    }

    @Test
    fun `changelog array drops blank entries`() {
        val info = UpdateInfo.fromJson(
            """{"versionCode":118,"apkUrl":"https://updates.example.com/a.apk",""" +
                """"changelog":["有内容","   ",""]}"""
        )
        // 手工维护 json 时多打的逗号不该在弹窗里留空行
        assertEquals(listOf("有内容"), info.changelog)
    }

    @Test
    fun `blank notes falls back to empty string`() {
        val info = UpdateInfo.fromJson(
            """{"versionCode":118,"apkUrl":"https://updates.example.com/a.apk","notes":"   "}"""
        )
        assertEquals("", info.notes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `missing versionCode rejects`() {
        UpdateInfo.fromJson("""{"apkUrl":"https://updates.example.com/a.apk"}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `missing apkUrl rejects`() {
        UpdateInfo.fromJson("""{"versionCode":109}""")
    }

    @Test(expected = Exception::class)
    fun `malformed json rejects`() {
        UpdateInfo.fromJson("not json at all")
    }

    // ---------- 约束 9：Content-Range 解析 ----------

    @Test
    fun `parses total from standard content-range`() {
        assertEquals(159305371L, UpdateDownloadWorker.parseTotalFromContentRange("bytes 100-159305370/159305371"))
        assertEquals(1L, UpdateDownloadWorker.parseTotalFromContentRange("bytes 0-0/1"))
    }

    @Test
    fun `unknown total returns null`() {
        // 服务器返回 bytes start-end/* 时总长未知 → 退回 Content-Length 兜底
        assertNull(UpdateDownloadWorker.parseTotalFromContentRange("bytes 100-200/*"))
    }

    @Test
    fun `missing or malformed header returns null`() {
        assertNull(UpdateDownloadWorker.parseTotalFromContentRange(null))
        assertNull(UpdateDownloadWorker.parseTotalFromContentRange(""))
        assertNull(UpdateDownloadWorker.parseTotalFromContentRange("bytes 100-200"))
        assertNull(UpdateDownloadWorker.parseTotalFromContentRange("garbage"))
        assertNull(UpdateDownloadWorker.parseTotalFromContentRange("bytes 0-1/0")) // 非正数
    }

    // ---------- 安全：apkUrl 白名单 ----------

    /** 测试用白名单（构建期注入了真实域名时也不依赖它，测试自带一份） */
    private val testHosts = setOf("updates.example.com")

    @Test
    fun `allowed https host accepted`() {
        assertTrue(
            UpdateChecker.isApkUrlAllowed(
                "https://updates.example.com/apk/update/a.apk",
                testHosts
            )
        )
    }

    @Test
    fun `non-https or foreign host or malformed rejected`() {
        assertFalse(
            UpdateChecker.isApkUrlAllowed(
                "http://updates.example.com/apk/update/a.apk",
                testHosts
            )
        )
        assertFalse(UpdateChecker.isApkUrlAllowed("https://evil.example.com/a.apk", testHosts))
        assertFalse(UpdateChecker.isApkUrlAllowed("ftp://updates.example.com/a.apk", testHosts))
        assertFalse(UpdateChecker.isApkUrlAllowed("not a url", testHosts))
        assertFalse(UpdateChecker.isApkUrlAllowed("", testHosts))
    }

    @Test
    fun `empty whitelist rejects everything`() {
        // 开源版默认没有配置更新源 → host 集合为空 → 任何地址都不允许
        assertFalse(
            UpdateChecker.isApkUrlAllowed("https://updates.example.com/a.apk", emptySet())
        )
    }

    // ---------- UpdateState ----------

    @Test
    fun `percent calculation`() {
        assertEquals(50, UpdateState(downloadedBytes = 50, totalBytes = 100).percent)
        assertEquals(100, UpdateState(downloadedBytes = 150, totalBytes = 100).percent)
        assertEquals(0, UpdateState(downloadedBytes = 10, totalBytes = 0).percent)
    }

    @Test
    fun `resuming state keeps partial bytes`() {
        val s = UpdateState.resuming(partBytes = 1024, totalBytes = 100)
        assertEquals(UpdatePhase.DOWNLOADING, s.phase)
        assertEquals(1024L, s.downloadedBytes)
        assertEquals(100L, s.totalBytes)
    }

    // ---------- formatBytes / speedEtaLine（UI 文案的纯函数部分） ----------

    @Test
    fun `formatBytes scales correctly`() {
        // internal 函数在同一 module 内跨包可见，直接调用
        assertEquals("1.50 GB", formatBytes(1610612736L))
        assertEquals("1.5 MB", formatBytes(1572864L))
        assertEquals("512 KB", formatBytes(524288L))
        assertEquals("3 B", formatBytes(3L))
    }
}
