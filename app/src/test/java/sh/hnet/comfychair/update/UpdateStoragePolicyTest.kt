package sh.hnet.comfychair.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 更新下载「磁盘剩余 0」误报修复的四层契约测试（v4.20.x 派工单第三节，防再犯核心）。
 *
 * 钉住的语义（错任何一条都会让「剩余 0 B」类误报复活）：
 * 1. 目录解析绝不产出相对路径（相对路径的 usableSpace 恒 0 是事故根源）；
 * 2. 空间查询失败（null）≠ 磁盘真满（INSUFFICIENT），UNKNOWN 放行记 warning；
 * 3. `getAllocatableBytes` 语义上不成立的 0 要转 null（查询无效），
 *    而 StatFs 实测的 0 是有效数值 → 必须判 INSUFFICIENT —— 两种 0 分开测；
 * 4. 存储不可用（目录失效/探针失败）走 STORAGE_UNAVAILABLE，不可重试，
 *    与 DISK_FULL 分型隔离；
 * 5. 新文案 key 六语种齐全（TranslationCompletenessTest 之外的定向保险）；
 * 6. 回退目录 filesDir/update/ 有 FileProvider 声明（否则安装链路崩溃）。
 *
 * 全部为纯 JVM 用例（java.io + 源码文本接线），不 mock Android 框架。
 */
class UpdateStoragePolicyTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private val mb = 1024L * 1024L

    // ---------------- 第 1 层：resolveUpdateDir ----------------

    /** 派工单清单 1：external=null（getExternalFilesDir 可为 null 的现实形态）→ 回退内部目录 */
    @Test
    fun `外部目录为 null 时回退内部目录并标记 usedFallback`() {
        val internal = temp.newFolder("internal")
        val result = UpdateDownloadWorker.resolveUpdateDir(null, internal)
        assertTrue("应回退成功", result is UpdateDirResult.Ok)
        result as UpdateDirResult.Ok
        assertTrue("回退必须打标记（日志/诊断依赖它）", result.usedFallback)
        assertEquals(File(internal, "update").absoluteFile, result.dir)
        assertTrue("解析成功的目录必须已实际存在", result.dir.isDirectory)
    }

    /** 派工单清单 2：外部路径存在但无法用作目录（此处用「被普通文件占用」构造）→ 回退 */
    @Test
    fun `外部目录不可用时回退内部目录`() {
        // File(普通文件, "update") 永远建不出来：mkdirs 失败且不是目录
        val blocker = temp.newFile("external-blocker")
        val internal = temp.newFolder("internal2")
        val result = UpdateDownloadWorker.resolveUpdateDir(blocker, internal)
        assertTrue("外部不可用必须回退而不是失败", result is UpdateDirResult.Ok)
        result as UpdateDirResult.Ok
        assertTrue(result.usedFallback)
        assertEquals(File(internal, "update").absoluteFile, result.dir)
    }

    /** 派工单清单 2 变体：外部目录存在但**不可写** → 回退（仅支持 POSIX 权限的跑） */
    @Test
    fun `外部目录存在但不可写时回退`() {
        val externalRoot = temp.newFolder("ext3")
        val updateDir = File(externalRoot, "update").apply { mkdirs() }
        val setOk = updateDir.setWritable(false)
        assumeTrue(
            "平台不支持把目录设为不可写（Windows 只置只读属性，canWrite 仍为 true）",
            setOk && !updateDir.canWrite()
        )
        try {
            val internal = temp.newFolder("internal3")
            val result = UpdateDownloadWorker.resolveUpdateDir(externalRoot, internal)
            assertTrue(result is UpdateDirResult.Ok)
            assertTrue("不可写的外部目录必须触发回退", (result as UpdateDirResult.Ok).usedFallback)
            assertEquals(File(internal, "update").absoluteFile, result.dir)
        } finally {
            updateDir.setWritable(true)
        }
    }

    /** 「已存在时 mkdirs 也返 false」的口径：外部目录已建好 → 直接命中，不算回退 */
    @Test
    fun `目录已存在时 mkdirs 返回 false 仍属正常`() {
        val externalRoot = temp.newFolder("ext4").apply { File(this, "update").mkdirs() }
        val internal = temp.newFolder("internal4")
        val result = UpdateDownloadWorker.resolveUpdateDir(externalRoot, internal)
        assertTrue(result is UpdateDirResult.Ok)
        assertFalse("外部可用绝不能标回退", (result as UpdateDirResult.Ok).usedFallback)
        assertEquals(File(externalRoot, "update").absoluteFile, result.dir)
    }

    /** 派工单清单 3：外部、内部都建不出来 → Unavailable，且带非空原因 */
    @Test
    fun `两者都不可用时判 Unavailable`() {
        val externalBlocker = temp.newFile("blocked-ext")
        val internalBlocker = temp.newFile("blocked-int")
        val result = UpdateDownloadWorker.resolveUpdateDir(externalBlocker, internalBlocker)
        assertTrue("两路都失败必须判不可用", result is UpdateDirResult.Unavailable)
        val reason = (result as UpdateDirResult.Unavailable).reason
        assertTrue("失败原因必须可读（进日志/异常信息）", reason.isNotBlank())
    }

    /** 派工单清单 4：任何成功结果都是绝对路径（绝不相对 —— 事故根因） */
    @Test
    fun `解析结果绝不产出相对路径`() {
        val internal = temp.newFolder("internalAbs")
        val viaFallback = UpdateDownloadWorker.resolveUpdateDir(null, internal)
        assertTrue((viaFallback as UpdateDirResult.Ok).dir.isAbsolute)

        val external = temp.newFolder("externalAbs")
        val viaExternal = UpdateDownloadWorker.resolveUpdateDir(external, internal)
        assertTrue((viaExternal as UpdateDirResult.Ok).dir.isAbsolute)
    }

    // ---------------- 第 2 层：decideSpaceOutcome（纯判定） ----------------

    /** 派工单清单 5：查询失败（null）→ UNKNOWN，绝不与「真满」混同 */
    @Test
    fun `空间未知判 UNKNOWN 不拦截`() {
        assertEquals(SpaceOutcome.UNKNOWN, UpdateDownloadWorker.decideSpaceOutcome(null, 161 * mb, 32 * mb))
    }

    /** 派工单清单 6：0 是有效测得的数值 → INSUFFICIENT（与 selectUsable 把 API 的 0 转 null 是两回事） */
    @Test
    fun `可用为 0 是有效数值判 INSUFFICIENT`() {
        assertEquals(SpaceOutcome.INSUFFICIENT, UpdateDownloadWorker.decideSpaceOutcome(0L, 161 * mb, 32 * mb))
    }

    /** 派工单清单 7：100MB 可用、需 161MB+32MB → INSUFFICIENT */
    @Test
    fun `低于需要量加余量判 INSUFFICIENT`() {
        assertEquals(SpaceOutcome.INSUFFICIENT, UpdateDownloadWorker.decideSpaceOutcome(100 * mb, 161 * mb, 32 * mb))
    }

    /** 派工单清单 8：500MB 可用、需 161MB+32MB → ENOUGH */
    @Test
    fun `高于需要量加余量判 ENOUGH`() {
        assertEquals(SpaceOutcome.ENOUGH, UpdateDownloadWorker.decideSpaceOutcome(500 * mb, 161 * mb, 32 * mb))
    }

    /** 派工单清单 9：边界 usable == needed + margin → ENOUGH；再少 1 字节 → INSUFFICIENT */
    @Test
    fun `边界值恰好等于需要量加余量判 ENOUGH`() {
        val needed = 100L
        val margin = 32L
        assertEquals(SpaceOutcome.ENOUGH, UpdateDownloadWorker.decideSpaceOutcome(needed + margin, needed, margin))
        assertEquals(SpaceOutcome.INSUFFICIENT, UpdateDownloadWorker.decideSpaceOutcome(needed + margin - 1, needed, margin))
    }

    // ---------------- 第 2 层：selectUsable（「0 的两种语义」在这里分家） ----------------

    /** getAllocatableBytes 返回 0 视为**查询无效** → 走 StatFs 回退 */
    @Test
    fun `allocatable 返回 0 视为无效并回退 statFs`() {
        assertEquals(500 * mb, UpdateDownloadWorker.selectUsable(0L, 500 * mb))
        assertNull("allocatable=0 且 statFs 失败时才是真未知", UpdateDownloadWorker.selectUsable(0L, null))
    }

    /** StatFs 实测 0 是有效数值（真满），必须原样返回 —— 与上一条共同构成派工单清单 6 的 ⚠️ 项 */
    @Test
    fun `statFs 实测 0 是有效数值`() {
        assertEquals(0L, UpdateDownloadWorker.selectUsable(null, 0L))
        assertEquals(0L, UpdateDownloadWorker.selectUsable(0L, 0L))
    }

    /** allocatable 有效（>0）时优先（考虑分配配额），负值无效 */
    @Test
    fun `allocatable 正值优先且负值视为无效`() {
        assertEquals(1024 * mb, UpdateDownloadWorker.selectUsable(1024 * mb, 500 * mb))
        assertEquals(500 * mb, UpdateDownloadWorker.selectUsable(-1L, 500 * mb))
        assertNull(UpdateDownloadWorker.selectUsable(null, null))
        assertNull("statFs 负值同样无效", UpdateDownloadWorker.selectUsable(null, -5L))
    }

    // ---------------- 第 4 层：写探针 ----------------

    /** 探针成功返回 null，且探针文件用完即删（目录不残留） */
    @Test
    fun `探针在可写目录通过并清理自身`() {
        val dir = temp.newFolder("probe-ok")
        assertNull(UpdateDownloadWorker.probeWritable(dir))
        val leftovers = dir.list()?.toList().orEmpty()
        assertEquals("探针文件必须删除干净，实际残留：$leftovers", emptyList<String>(), leftovers.sorted())
    }

    /** 目录不存在 → 探针失败返回原因（绝不静默放行） */
    @Test
    fun `探针在缺失目录上失败并给出原因`() {
        val missing = File(temp.root, "no-such-dir")
        val failure = UpdateDownloadWorker.probeWritable(missing)
        assertNotNull("目录不存在必须判探针失败", failure)
        assertTrue(failure!!.isNotBlank())
    }

    /** 「目录」位置被普通文件占据 → 探针失败（对应回退链全断的极端形态） */
    @Test
    fun `探针在被文件占据的路径上失败`() {
        val file = temp.newFile("not-a-dir")
        assertNotNull(UpdateDownloadWorker.probeWritable(file))
    }

    // ---------------- 第 3 层：分型隔离 ----------------

    /** StorageUnavailableException → STORAGE_UNAVAILABLE 且**不可重试**（重试解决不了路径失效） */
    @Test
    fun `存储不可用分型且不可重试`() {
        val e = DownloadPolicy.StorageUnavailableException("dir=/x/y: 写探针 IO 失败")
        assertEquals(DownloadPolicy.FailureKind.STORAGE_UNAVAILABLE, DownloadPolicy.classifyFailure(e))
        assertFalse(DownloadPolicy.isRetryable(e))
    }

    /** DiskFullException 的分型语义必须原样保留（本次修复不许稀释它） */
    @Test
    fun `磁盘满分型不回归`() {
        val e = DownloadPolicy.DiskFullException(usableBytes = 10 * mb, neededBytes = 200 * mb)
        assertEquals(DownloadPolicy.FailureKind.DISK_FULL, DownloadPolicy.classifyFailure(e))
        assertFalse(DownloadPolicy.isRetryable(e))
        assertTrue("异常信息必须带真实数值", e.message!!.contains("10"))
    }

    // ---------------- 接线（源码文本，与项目 Wiring 测试同风格） ----------------

    /** Worker 必须走新链路：目录解析、探针、语义化空间判定缺一不可 */
    @Test
    fun `Worker 接线到目录复检 探针与空间分型`() {
        val body = readSource("src/main/java/sh/hnet/comfychair/update/UpdateDownloadWorker.kt")
        assertTrue("updateDir 必须返回 UpdateDirResult", body.contains("fun updateDir(context: Context): UpdateDirResult"))
        assertTrue("必须保留 resolveUpdateDir 纯函数", body.contains("fun resolveUpdateDir(externalFilesDir: File?, internalFilesDir: File): UpdateDirResult"))
        assertTrue("下载前必须做目录可用性检查", body.contains("is UpdateDirResult.Unavailable ->"))
        assertTrue("下载前必须跑写探针", body.contains("probeWritable(dirResult.dir)"))
        assertTrue("空间预检必须走 decideSpaceOutcome", body.contains("decideSpaceOutcome(detail.usableBytes, needed, SPACE_MARGIN_BYTES)"))
        assertTrue("失败横幅必须有存储不可用分支", body.contains("R.string.update_error_storage_unavailable"))
        assertFalse(
            "旧缺陷（单源 usableSpace 直接拦截）不得复活",
            body.contains("if (usable < needed + 32L * 1024 * 1024)")
        )
    }

    /** 探针必须排在真实下载调用之前（先自检再烧流量） */
    @Test
    fun `探针接线必须排在下载调用之前`() {
        val body = readSource("src/main/java/sh/hnet/comfychair/update/UpdateDownloadWorker.kt")
        val probe = body.indexOf("probeWritable(dirResult.dir)")
        val download = body.indexOf("total = downloadWithResume(")
        assertTrue("找不到探针调用", probe >= 0)
        assertTrue("找不到下载调用", download >= 0)
        assertTrue("探针（$probe）必须排在首个下载调用（$download）之前", probe < download)
    }

    /** DownloadPolicy 的 STORAGE_UNAVAILABLE 必须进分型器与「不可重试」名单两处 */
    @Test
    fun `DownloadPolicy 接线分型与重试名单`() {
        val body = readSource("src/main/java/sh/hnet/comfychair/update/DownloadPolicy.kt")
        assertTrue(body.contains("is StorageUnavailableException -> FailureKind.STORAGE_UNAVAILABLE"))
        val retryBlock = body.substring(body.indexOf("fun isRetryable("))
        assertTrue(
            "STORAGE_UNAVAILABLE 必须落在不可重试分支",
            retryBlock.contains("FailureKind.STORAGE_UNAVAILABLE,")
        )
    }

    /** 新文案 key 六个语言目录齐全（TranslationCompletenessTest 的定向镜像，报错更好读） */
    @Test
    fun `存储不可用文案六语种齐全`() {
        for (dir in listOf("values", "values-zh", "values-de", "values-es", "values-fr", "values-pl")) {
            val xml = readSource("src/main/res/$dir/strings.xml")
            assertTrue(
                "$dir 缺少 update_error_storage_unavailable（缺了 TranslationCompletenessTest 会红）",
                xml.contains("""name="update_error_storage_unavailable"""")
            )
        }
    }

    /** 回退目录必须声明 FileProvider root，否则内部目录里的 update.apk 安装时 getUriForFile 抛异常 */
    @Test
    fun `回退目录有 FileProvider 声明`() {
        val xml = readSource("src/main/res/xml/file_paths.xml")
        assertTrue(
            "filesDir/update/ 必须有 files-path 声明（仅 update 子目录）",
            xml.contains("""<files-path name="update_files_internal" path="update/" />""")
        )
        // 注释里本来就写着「禁止 path="."」，只能匹配真实标签形态（path="." 紧跟自闭合）
        assertFalse(
            "仍禁止整目录共享（path=\".\" 出现在真实标签上）",
            Regex("""path="\."\s*/>""").containsMatchIn(xml)
        )
    }

    private fun readSource(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到源文件 $relative，尝试过：${candidates.map { it.absolutePath }}")
        return file.readText(Charsets.UTF_8)
    }
}
