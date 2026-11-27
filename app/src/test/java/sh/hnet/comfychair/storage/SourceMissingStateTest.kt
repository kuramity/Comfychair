package sh.hnet.comfychair.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.model.GalleryItem
import sh.hnet.comfychair.viewmodel.MediaViewerItem
import sh.hnet.comfychair.viewmodel.shouldRequestOriginal

/**
 * 「第三态：源已不存在」的数据层与判定层钉子（纯 JVM）。
 *
 * 覆盖四件事，每一件都是三态模型的地基：
 * 1. **只有 404 才算源已不存在**：超时/断网（-1）/403/5xx 一律不允许进入第三态
 *    —— 地铁里打开图库不能满屏「已删除」；
 * 2. **索引往返**：sourceMissing=true 写入再读出仍是 true；旧索引没有该字段时读出 false
 *    —— 加字段不能让老用户图库变空或把旧索引判成损坏；
 * 3. **刷新合并 OR**：ComfyUI 只删文件不删 /history，服务器条目会把已删除条目带回来；
 *    同键合并时本地 true 必须保持 true，否则角标消失、又变回"模糊 + 白等超时"；
 * 4. **已标记条目不请求原图**：shouldRequestOriginal 是请求层的唯一门。
 */
class SourceMissingStateTest {

    private fun item(
        promptId: String = "prompt-1",
        filename: String = "ComfyUI_00001_.png",
        subfolder: String = "",
        type: String = "output",
        index: Int = 0,
        sourceMissing: Boolean = false
    ) = GalleryItem(
        promptId = promptId,
        filename = filename,
        subfolder = subfolder,
        type = type,
        isVideo = false,
        index = index,
        sourceMissing = sourceMissing
    )

    // ============================================================
    // 1. 判定层：只有 HTTP 404
    // ============================================================

    @Test
    fun `404 才算源已不存在`() {
        assertTrue(ComfyUIClient.isSourceMissingStatus(404))
    }

    @Test
    fun `非 404 一律不算源已不存在`() {
        // 200 是正常响应；403 是权限问题；500 是服务端故障；-1 代表网络异常/超时
        // （OkHttp 链路里网络异常不会进入状态码分支，这里钉住纯函数边界）。
        assertFalse(ComfyUIClient.isSourceMissingStatus(200))
        assertFalse(ComfyUIClient.isSourceMissingStatus(403))
        assertFalse(ComfyUIClient.isSourceMissingStatus(500))
        assertFalse(ComfyUIClient.isSourceMissingStatus(-1))
    }

    @Test
    fun `404 仍映射为 FILE_MISSING 且非 404 不映射`() {
        // 判定口径唯一来源：connectionFailureForHttpCode 内部走 isSourceMissingStatus。
        // 把 isSourceMissingStatus 改成恒 true 时，这条与上面的非 404 用例都会变红。
        assertEquals(
            sh.hnet.comfychair.connection.ConnectionFailure.FILE_MISSING,
            ComfyUIClient.connectionFailureForHttpCode(404)
        )
        assertEquals(
            sh.hnet.comfychair.connection.ConnectionFailure.NETWORK,
            ComfyUIClient.connectionFailureForHttpCode(500)
        )
        assertEquals(
            sh.hnet.comfychair.connection.ConnectionFailure.AUTHENTICATION,
            ComfyUIClient.connectionFailureForHttpCode(403)
        )
    }

    // ============================================================
    // 2. 索引往返与旧格式兼容
    // ============================================================

    @Test
    fun `sourceMissing 写入索引再读出仍为 true`() {
        val text = GalleryMetadataCache.serializeItems(
            listOf(item(filename = "deleted.png", sourceMissing = true))
        )
        val parsed = GalleryMetadataCache.parseItemsText(text)
        assertNotNull("新格式索引必须可解析", parsed)
        assertEquals(1, parsed!!.size)
        assertTrue("sourceMissing=true 必须随索引往返保留", parsed.single().sourceMissing)
    }

    @Test
    fun `未标记条目的 sourceMissing 往返仍为 false`() {
        val text = GalleryMetadataCache.serializeItems(listOf(item(sourceMissing = false)))
        val parsed = GalleryMetadataCache.parseItemsText(text)
        assertNotNull(parsed)
        assertFalse(parsed!!.single().sourceMissing)
    }

    @Test
    fun `旧格式索引没有 sourceMissing 字段时按 false 解析`() {
        // 这是升级兼容的底线：老用户磁盘上的索引不带新字段，必须照常读出条目，
        // 不能被判成"解析失败 → 备份成 .corrupt-* → 图库变空"。
        val legacyJson = """
            [
              {
                "promptId": "legacy-prompt",
                "filename": "ComfyUI_00009_.png",
                "subfolder": "",
                "type": "output",
                "isVideo": false,
                "index": 0
              }
            ]
        """.trimIndent()

        val parsed = GalleryMetadataCache.parseItemsText(legacyJson)
        assertNotNull("旧索引必须能解析", parsed)
        assertEquals(1, parsed!!.size)
        assertEquals("legacy-prompt", parsed.single().promptId)
        assertFalse("缺字段时 sourceMissing 缺省 false", parsed.single().sourceMissing)
    }

    @Test
    fun `applySourceMissing 只标记命中条目且不改其它字段`() {
        val target = item(filename = "a.png", subfolder = "sub", index = 3)
        val other = item(filename = "b.png")
        val keys = setOf(GalleryMetadataCache.itemIdentityKey(target.promptId, target.filename))

        val updated = GalleryMetadataCache.applySourceMissing(listOf(target, other), keys)

        assertTrue(updated[0].sourceMissing)
        assertEquals("sub", updated[0].subfolder)
        assertEquals(3, updated[0].index)
        assertFalse(updated[1].sourceMissing)
    }

    // ============================================================
    // 3. 刷新合并：sourceMissing 本地 OR 服务器
    // ============================================================

    @Test
    fun `合并时本地已标记的条目不会被服务器数据冲掉`() {
        // 场景：A 手机彻底删除后，服务器 /history 仍返回这条记录；
        // B 手机刷新图库时本地 sourceMissing=true，服务器条目默认 false。
        val local = listOf(
            item(subfolder = "local-subfolder", index = 0, sourceMissing = true)
        )
        val server = listOf(
            item(subfolder = "server-subfolder", index = 7, sourceMissing = false)
        )

        val merged = GalleryMetadataCache.mergeItems(local, server)

        assertEquals(1, merged.size)
        assertTrue("本地 true 必须保持 true（逻辑或）", merged.single().sourceMissing)
        // 其它字段仍以服务器为准（那是最新的元数据），index 按合并后位置重排
        assertEquals("server-subfolder", merged.single().subfolder)
        assertEquals(0, merged.single().index)
    }

    @Test
    fun `合并时本地未标记而服务器已标记也保持 true`() {
        // 逻辑或是双向的：服务器条目（理论上不会带该字段，但保持纯函数语义完整）
        val local = listOf(item(sourceMissing = false))
        val server = listOf(item(sourceMissing = true))

        val merged = GalleryMetadataCache.mergeItems(local, server)

        assertTrue(merged.single().sourceMissing)
    }

    @Test
    fun `合并时两边都未标记仍为 false`() {
        val merged = GalleryMetadataCache.mergeItems(
            listOf(item(sourceMissing = false)),
            listOf(item(sourceMissing = false))
        )
        assertFalse(merged.single().sourceMissing)
    }

    // ============================================================
    // 4. 请求层：已标记条目不请求原图
    // ============================================================

    @Test
    fun `已标记的条目不请求原图`() {
        assertFalse(shouldRequestOriginal(item(sourceMissing = true)))
    }

    @Test
    fun `未标记的条目照常请求原图`() {
        assertTrue(shouldRequestOriginal(item(sourceMissing = false)))
    }

    @Test
    fun `查看器条目同样遵守请求门`() {
        val missing = MediaViewerItem(
            promptId = "p",
            filename = "f.png",
            subfolder = "",
            type = "output",
            isVideo = false,
            index = 0,
            sourceMissing = true
        )
        val normal = missing.copy(sourceMissing = false)

        assertFalse(shouldRequestOriginal(missing))
        assertTrue(shouldRequestOriginal(normal))
    }

    @Test
    fun `旧版查看器会话 JSON 缺字段时按 false`() {
        val legacy = org.json.JSONObject(
            """{"promptId":"p","filename":"f.png","subfolder":"","type":"output","isVideo":false,"index":0}"""
        )
        assertFalse(MediaViewerItem.fromJson(legacy).sourceMissing)
    }

    // ============================================================
    // 5. 补记合并（mergeMissingRecord）：仅缓存恢复的条目标记也要落盘
    // ============================================================

    @Test
    fun `索引缺记录时补记带标记的最小条目`() {
        // 仅磁盘缓存恢复的条目不在索引里：404 时必须补记一条带标记的记录，
        // 否则标记只留在内存列表、重启即丢（第三态丢失）
        val existing = listOf(item(filename = "kept.png"))
        val record = item(filename = "recovered.png", index = 0)
        val merged = GalleryMetadataCache.mergeMissingRecord(existing, record)

        assertEquals(2, merged.size)
        // 补记条目排在末尾（本地独有在后，与 mergeItems 的顺序口径一致）
        val recovered = merged.last()
        assertEquals("recovered.png", recovered.filename)
        assertTrue(recovered.sourceMissing)
        // 原有条目不受影响
        assertFalse(merged.first().sourceMissing)
        // 补记后序号连续（renumberByPosition 统一重排）
        assertEquals(listOf(0, 1), merged.map { it.index })
    }

    @Test
    fun `索引已有记录时只置标记不重复补记`() {
        val existing = listOf(item(filename = "kept.png"), item(filename = "dup.png"))
        val record = item(filename = "dup.png")
        val merged = GalleryMetadataCache.mergeMissingRecord(existing, record)

        // 不追加：条目数与顺序原样，只把已有条目的标记置 true（applySourceMissing 原语义）
        assertEquals(2, merged.size)
        assertEquals(listOf("kept.png", "dup.png"), merged.map { it.filename })
        assertTrue(merged.single { it.filename == "dup.png" }.sourceMissing)
        assertFalse(merged.single { it.filename == "kept.png" }.sourceMissing)
    }

    @Test
    fun `补记已带标记的原样追加`() {
        val existing = listOf(item(filename = "kept.png"))
        val record = item(filename = "recovered.png", sourceMissing = true)
        val merged = GalleryMetadataCache.mergeMissingRecord(existing, record)
        assertEquals(2, merged.size)
        assertTrue(merged.last().sourceMissing)
        assertTrue(merged.first().sourceMissing.not())
    }
}