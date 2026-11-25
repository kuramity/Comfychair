package sh.hnet.comfychair.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P0-R2 / R10 的「写入结果有没有被检查」源码级钉子。
 *
 * ## 为什么需要这一层测试
 *
 * 写后读回校验只在**返回值被检查**时才有意义：文件层单测（GalleryTrashWriteGuardTest）
 * 能证明 `saveAllToFile` 失败会回滚，但证明不了调用方没有把 Boolean 丢掉继续打成功日志。
 * 这三条路径都需要 Android Context（Application / filesDir），JVM 单测起不了仓储层，
 * 所以按 [sh.hnet.comfychair.PagerInertiaWiringTest] 同款写法直接读源码，钉住：
 * 1. 回收站落盘失败 → 取消本次移入回收站（内存回退、不删列表、不写墓碑）；
 * 2. 墓碑落盘失败 → 记 error 而不是 "Recorded ..." 成功日志；
 * 3. 索引落盘结果被上传（mergeMetadataInto / removeFromIndex 都检查 Boolean）。
 *
 * 断言按**空白归一化后的子串**匹配：调缩进/换行不会误报，删掉检查必红。
 */
class GalleryWriteResultWiringTest {

    /** 单测工作目录是模块根（app/），与 PagerInertiaWiringTest 同口径。 */
    private fun source(path: String): String {
        val file = File(path)
        assertTrue(
            "源文件不存在：${file.absolutePath} —— 单测工作目录应为 app/ 模块根",
            file.exists()
        )
        return file.readText()
    }

    private fun normalized(body: String): String = body.replace(Regex("\\s+"), " ").trim()

    private fun assertContains(body: String, fragment: String, message: String) {
        assertTrue(message, normalized(body).contains(normalized(fragment)))
    }

    private val repositoryPath =
        "src/main/java/sh/hnet/comfychair/repository/GalleryRepository.kt"
    private val cachePath =
        "src/main/java/sh/hnet/comfychair/storage/GalleryMetadataCache.kt"
    private val trashPath =
        "src/main/java/sh/hnet/comfychair/storage/GalleryTrashStorage.kt"
    private val clientPath =
        "src/main/java/sh/hnet/comfychair/ComfyUIClient.kt"
    private val galleryScreenPath =
        "src/main/java/sh/hnet/comfychair/ui/screens/GalleryScreen.kt"

    @Test
    fun `回收站落盘失败时取消本次删除`() {
        val body = source(repositoryPath)

        assertContains(
            body,
            "val persisted = applicationContext?.let { GalleryTrashStorage.saveAll(it, merged) } ?: false",
            "moveToTrash 必须接住回收站落盘结果（saveAll 返回 Boolean；context 为空按失败处理）"
        )
        assertFalse(
            "context 为空不能当成落盘成功：`?: true` 会让调用方在磁盘没有记录时继续提示成功",
            normalized(body).contains("?: true")
        )
        assertContains(
            body,
            "if (!persisted) { _trashEntries.value = previousEntries " +
                "DebugLogger.e(TAG, \"回收站落盘失败，已取消本次移入回收站（列表/墓碑保持不动）\") " +
                "return emptyList() }",
            "回收站落盘失败必须回退内存列表并取消删除（不能继续写墓碑隐藏图片）"
        )
    }

    @Test
    fun `墓碑落盘失败要记 error 而不是成功日志`() {
        val body = source(cachePath)

        assertContains(
            body,
            "fun addTombstones(context: Context, serverId: String, keys: Set<String>): Boolean",
            "addTombstones 必须把落盘结果返回给调用方"
        )
        assertContains(
            body,
            "val ok = writeTextAtomicallyVerified( file = target, content = tombstoneJson(merged), " +
                "label = \"删除墓碑\", verify = { text -> isTombstoneDocumentConsistent(text, merged) } )",
            "墓碑写入必须经过写后读回校验"
        )
        assertContains(
            body,
            "if (ok) { DebugLogger.i(TAG, \"Recorded",
            "\"Recorded N deletion tombstone(s)\" 只能在真正落盘成功后打印"
        )
        assertContains(
            body,
            "} else { DebugLogger.e( TAG, \"删除墓碑写入失败",
            "墓碑写入失败必须记 error，不能沉默"
        )
    }

    @Test
    fun `墓碑结果在删除路径被检查`() {
        val body = source(repositoryPath)

        assertContains(
            body,
            "val tombstonePersisted = GalleryMetadataCache.addTombstones(context, serverId, keys)",
            "removeFromGallery 必须接住墓碑落盘结果"
        )
        assertContains(
            body,
            "if (!tombstonePersisted) {",
            "墓碑没落盘必须明确记失败（重启后图片会复现）"
        )
    }

    @Test
    fun `索引落盘结果在合并与移除路径被检查`() {
        val cache = source(cachePath)
        val repository = source(repositoryPath)

        assertContains(
            cache,
            "if (!writeMetadataToFile(getCacheFile(filesDir, serverId), merged)) {",
            "mergeMetadataInto 必须检查索引落盘结果（R10）"
        )
        assertContains(
            cache,
            "val ok = writeMetadata(context, serverId, renumberByPosition(kept), allowEmpty = true)",
            "removeFromIndex 必须接住索引写入结果"
        )
        assertContains(
            cache,
            "DebugLogger.e( TAG, \"索引移除未能落盘",
            "索引移除失败必须记 error，不能打成功日志"
        )
        assertContains(
            repository,
            "if (indexMerged == null && recovered.isEmpty()) {",
            "索引读不出来时仍要先走磁盘缓存恢复（R1），只有恢复不出东西才保留旧列表"
        )
    }

    @Test
    fun `回收站与墓碑的落盘接口都返回 Boolean`() {
        assertContains(
            source(trashPath),
            "fun saveAll(context: Context, entries: List<GalleryTrashEntry>): Boolean",
            "saveAll 必须返回落盘结果"
        )
        assertContains(
            source(cachePath),
            "fun removeTombstones(context: Context, serverId: String, keys: Set<String>): Boolean",
            "removeTombstones 必须返回是否全部清理成功（恢复失败要能取消）"
        )
    }

    @Test
    fun `删除链路的两个回调适配器都走 awaitCallbackOnce`() {
        val repo = source(repositoryPath)

        assertContains(
            repo,
            "private suspend fun ComfyUIClient.probeBrowserDeleteSupportAwait(): Pair<Int?, Boolean> = " +
                "awaitCallbackOnce { deliver ->",
            "能力探测适配器必须有 isActive 守卫（无条件 resume 会 Already resumed）"
        )
        assertContains(
            repo,
            "private suspend fun ComfyUIClient.deleteBrowserFileAwait(",
            "删除文件适配器必须存在"
        )
        assertContains(
            repo,
            "awaitCallbackOnce { deliver -> deleteBrowserFile(filename, folderPath, folderType)",
            "删除文件适配器也必须走 awaitCallbackOnce"
        )
        assertFalse(
            "旧的 suspendCancellableCoroutine + 无条件 resume 写法必须消失",
            normalized(repo).contains(
                "suspendCancellableCoroutine { cont -> probeBrowserDeleteSupport"
            )
        )
    }

    @Test
    fun `两个删除相关 client 回调都有只投递一次守卫`() {
        val client = source(clientPath)

        assertContains(
            client,
            "fun probeBrowserDeleteSupport(callback: (httpCode: Int?, networkFailed: Boolean) -> Unit) {",
            "probeBrowserDeleteSupport 必须存在"
        )
        assertContains(
            client,
            "val delivery = SingleDeliveryGuard { DebugLogger.w(TAG, \"probeBrowserDeleteSupport 忽略重复的回调投递\") }",
            "能力探测回调必须有 SingleDeliveryGuard"
        )
        assertContains(
            client,
            "val delivery = SingleDeliveryGuard { DebugLogger.w(TAG, \"deleteBrowserFile 忽略重复的回调投递\") }",
            "删除文件回调必须有 SingleDeliveryGuard"
        )
    }

    @Test
    fun `删除入口在 IO 线程执行且异常被边界拦截`() {
        val body = source(repositoryPath)

        assertContains(
            body,
            "withContext(Dispatchers.IO) { moveToTrash(listOf(item)) }",
            "单条删除的落盘必须离开 Main 线程（写后读回 + fsync 会放大卡顿）"
        )
        assertContains(
            body,
            "withContext(Dispatchers.IO) { moveToTrash(items) }",
            "批量删除的落盘必须离开 Main 线程"
        )
        assertContains(
            body,
            "try { moveToTrashInternal(items) } catch (e: Exception) {",
            "moveToTrash 必须有兜底 catch（任何漏网异常不能把协程炸成进程崩溃）"
        )
    }

    @Test
    fun `图库网格 key 走 itemIdentityKey`() {
        // P2-E 给 itemsIndexed 补了 contentType，调用从"一行写完"变成多参数多行，
        // 所以这里钉住的是 key 表达式本身（口径不变：仍必须是 itemIdentityKey，
        // 也就是 NUL 分隔；换成 "_" 拼接仍会让 (a, b_c) 与 (a_b, c) 撞键）。
        assertContains(
            source(galleryScreenPath),
            "key = { _, item -> " +
                "GalleryMetadataCache.itemIdentityKey(item.promptId, item.filename) }",
            "LazyGrid key 必须与仓储去重口径同为 NUL 分隔（下划线撞键会抛 IllegalArgumentException）"
        )
    }
}