package sh.hnet.comfychair.cache

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * filesDir 清理白名单（[MediaCacheCleanupPolicy]）的数据边界。
 *
 * 背景（v4.16.4 修复的核心）：v4.16.3 及之前「清空缓存」会把 `user_workflows` 连同
 * 提示词一起删掉 —— metadata 丢失后预置流重新随机分配 id，`WorkflowValuesPrefs` 里
 * `{serverId}_{旧id}` 全部孤儿化，用户的提示词全变内置词。这里钉住白名单的判断规则：
 * **媒体缓存只认 `gallery_store` / `gallery_thumbs`，业务数据一律受保护，未知名默认不删**。
 */
class MediaCacheCleanupPolicyTest {

    // ============================================================
    // 可删目录：只有图库媒体缓存两兄弟
    // ============================================================

    @Test
    fun `gallery_store 是可删的媒体缓存目录`() {
        assertTrue(MediaCacheCleanupPolicy.isDeletableDataDir("gallery_store"))
    }

    @Test
    fun `gallery_thumbs 是可删的媒体缓存目录`() {
        assertTrue(MediaCacheCleanupPolicy.isDeletableDataDir("gallery_thumbs"))
    }

    @Test
    fun `user_workflows 绝不可删（P0 红线 - 清缓存曾误删工作流的根因）`() {
        // P0 红线断言：这个目录装着用户工作流文件 + metadata，
        // metadata 一丢提示词就孤儿化。反证实验会把 user_workflows 临时加进
        // deletableDataDirs，本条用例必须立刻变红。
        assertFalse(MediaCacheCleanupPolicy.isDeletableDataDir("user_workflows"))
    }

    @Test
    fun `model3d 不可删（保守保留的业务数据目录）`() {
        assertFalse(MediaCacheCleanupPolicy.isDeletableDataDir("model3d"))
    }

    // ============================================================
    // 受保护目录
    // ============================================================

    @Test
    fun `user_workflows 在保护清单里`() {
        assertTrue(MediaCacheCleanupPolicy.isProtectedDataDir("user_workflows"))
    }

    @Test
    fun `model3d 在保护清单里`() {
        assertTrue(MediaCacheCleanupPolicy.isProtectedDataDir("model3d"))
    }

    @Test
    fun `gallery_store 不在保护清单里（否则清缓存永远清不掉图库）`() {
        assertFalse(MediaCacheCleanupPolicy.isProtectedDataDir("gallery_store"))
        assertFalse(MediaCacheCleanupPolicy.isProtectedDataDir("gallery_thumbs"))
    }

    // ============================================================
    // 受保护文件：前缀命中 + 精确名命中
    // ============================================================

    @Test
    fun `图库索引文件按前缀命中保护（文件名里拼着 serverId）`() {
        assertTrue(MediaCacheCleanupPolicy.isProtectedDataFile("gallery_metadata_123.json"))
    }

    @Test
    fun `图库删除墓碑按前缀命中保护`() {
        assertTrue(MediaCacheCleanupPolicy.isProtectedDataFile("gallery_deleted_srv01.json"))
    }

    @Test
    fun `object_info 缓存按前缀命中保护（gz 压缩形式也算）`() {
        assertTrue(MediaCacheCleanupPolicy.isProtectedDataFile("object_info_abc.json.gz"))
    }

    @Test
    fun `model_intros_json 精确名命中保护`() {
        assertTrue(MediaCacheCleanupPolicy.isProtectedDataFile("model_intros.json"))
    }

    @Test
    fun `其余精确名业务文件命中保护`() {
        assertTrue(MediaCacheCleanupPolicy.isProtectedDataFile("lora_effects.json"))
        assertTrue(MediaCacheCleanupPolicy.isProtectedDataFile("tag_favorites.json"))
        assertTrue(MediaCacheCleanupPolicy.isProtectedDataFile("login_background.jpg"))
    }

    // ============================================================
    // fail-closed：未知名默认不删
    // ============================================================

    @Test
    fun `未知名目录默认不删`() {
        assertFalse(MediaCacheCleanupPolicy.isDeletableDataDir("random_thing"))
    }

    @Test
    fun `未知名目录也不在保护清单里（由默认不删规则兜底）`() {
        assertFalse(MediaCacheCleanupPolicy.isProtectedDataDir("random_thing"))
    }

    @Test
    fun `未知名文件不命中保护（既非精确名也非已知前缀）`() {
        assertFalse(MediaCacheCleanupPolicy.isProtectedDataFile("random_thing.bin"))
    }

    @Test
    fun `前缀判定不会把恰好同头的无关文件误判成保护文件`() {
        // gallery_metadata_ 是前缀；gallery_metadataX（无下划线分隔）不应命中
        assertFalse(MediaCacheCleanupPolicy.isProtectedDataFile("gallery_metadataX.json"))
    }
}
