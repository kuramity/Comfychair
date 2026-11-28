package sh.hnet.comfychair.cache

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B4.2-A 件一「MediaGateway 网关接口化」的源码级钉子。
 *
 * 为什么读源码：MediaCache 对网络的全部访问已经收口到 `comfyUIClient` 一个属性上，
 * 接口化（`ComfyUIClient?` → `MediaGateway?`）是纯静态结构变化，JVM 单测既跑不了
 * OkHttp 也插不了假实现；而"接口被删 / 属性类型被改回去 / 离线闸门被挪走"这类
 * 回归在现有行为测试里不会红（行为零变化是本次改造的目标本身）。
 *
 * 口径与 [MediaCacheConcurrencyWiringTest] 一致：单测工作目录是模块根（`app/`）。
 * 签名比对先把连续空白折叠成单个空格，避免缩进/换行风格造成误报。
 */
class MediaGatewayContractTest {

    private val gatewayPath = "src/main/java/sh/hnet/comfychair/cache/MediaGateway.kt"
    private val clientPath = "src/main/java/sh/hnet/comfychair/ComfyUIClient.kt"
    private val cachePath = "src/main/java/sh/hnet/comfychair/cache/MediaCache.kt"

    private fun source(path: String): String {
        val file = File(path)
        assertTrue("源文件不存在：${file.absolutePath} —— 单测工作目录应为 app/ 模块根", file.exists())
        return file.readText()
    }

    /** 把连续空白折叠成单个空格：签名/语句比对对换行与缩进不敏感。 */
    private fun squash(text: String): String = text.replace(Regex("""\s+"""), " ")

    // ── ① 接口文件存在且含三方法签名 ──────────────────────────────────

    @Test
    fun `MediaGateway 接口存在且只含 fetchImage_fetchVideo_viewUrl 三方法`() {
        val gateway = squash(source(gatewayPath))
        // 三个方法签名（与 ComfyUIClient 完全一致，折叠空白后比对）
        assertTrue(
            "MediaGateway 缺 fetchImage 签名",
            gateway.contains(
                "fun fetchImage( filename: String, subfolder: String = \"\", type: String = \"output\", " +
                    "maxDimension: Int = DEFAULT_MAX_DECODE_DIMENSION, preview: String? = null, " +
                    "useThumbnailClient: Boolean = false, " +
                    "callback: (bitmap: Bitmap?, failureType: ConnectionFailure) -> Unit )"
            )
        )
        assertTrue(
            "MediaGateway 缺 fetchVideo 签名",
            gateway.contains(
                "fun fetchVideo( filename: String, subfolder: String = \"\", type: String = \"output\", " +
                    "callback: (bytes: ByteArray?, failureType: ConnectionFailure) -> Unit )"
            )
        )
        assertTrue(
            "MediaGateway 缺 viewUrl 签名",
            gateway.contains(
                "fun viewUrl( filename: String, subfolder: String = \"\", type: String = \"output\", " +
                    "preview: String? = null ): String?"
            )
        )
    }

    @Test
    fun `MediaGateway 接口不直接依赖 OkHttp（窄网关，实现细节留在 ComfyUIClient）`() {
        val gateway = source(gatewayPath)
        assertFalse(
            "MediaGateway 是窄接口：不得出现 OkHttpClient / Request 等传输层类型（实现细节属于 ComfyUIClient）",
            gateway.contains("OkHttpClient") || gateway.contains("Request.Builder")
        )
    }

    // ── ② ComfyUIClient 实现接口 ──────────────────────────────────────

    @Test
    fun `ComfyUIClient 类声明实现 MediaGateway`() {
        val client = squash(source(clientPath))
        assertTrue(
            "ComfyUIClient 类声明缺 \": MediaGateway\"——MediaCache 拿到的必须是网关实现",
            Regex("""class\s+ComfyUIClient\s*\(""").containsMatchIn(client) &&
                client.contains(") : MediaGateway {")
        )
    }

    @Test
    fun `ComfyUIClient 的 maxDimension 默认值与接口常量同步（6144）`() {
        val client = squash(source(clientPath))
        val gateway = squash(source(gatewayPath))
        // 接口侧默认值必须与实现侧 MAX_DECODE_DIMENSION 同值，否则经接口类型调用
        // 的调用点（MediaCache 的三参简写）会静默改变解码采样行为
        assertTrue("ComfyUIClient.MAX_DECODE_DIMENSION 应为 6144", client.contains("MAX_DECODE_DIMENSION = 6144"))
        assertTrue(
            "MediaGateway.DEFAULT_MAX_DECODE_DIMENSION 应为 6144（与实现侧同值）",
            gateway.contains("DEFAULT_MAX_DECODE_DIMENSION = 6144")
        )
    }

    // ── ③ MediaCache 属性类型改为网关 ─────────────────────────────────

    @Test
    fun `MediaCache 的 comfyUIClient 属性类型为 MediaGateway 并从 ConnectionManager 取值`() {
        val cache = squash(source(cachePath))
        assertTrue(
            "MediaCache.comfyUIClient 属性类型应为 MediaGateway?（窄接口依赖）",
            cache.contains("private val comfyUIClient: MediaGateway? get() = ConnectionManager.clientOrNull")
        )
    }

    // ── ④ 7 处离线闸门仍在原位（行为零变化） ──────────────────────────

    @Test
    fun `7 处离线闸门语句仍在 MediaCache 原位`() {
        val cache = source(cachePath)
        val squashed = squash(cache)

        // 六处完整闸门：client == null || ConnectionManager.isOffline
        val fullGateCount = Regex("""client == null \|\| ConnectionManager\.isOffline""").findAll(cache).count()
        assertTrue(
            "完整离线闸门应有 6 处，实际 $fullGateCount（接口化不得挪走/删除离线闸门）",
            fullGateCount == 6
        )

        // getVideoUri 的回落闸门（内存优先模式离线时回落磁盘缓存）
        assertTrue(
            "getVideoUri 的离线回落闸门丢失",
            squashed.contains("videoCache.get(keyStr) ?: if (ConnectionManager.isOffline)")
        )

        // 折叠空白后逐处确认六条完整闸门文本仍在（防拼串改写）
        val gate = "client == null || ConnectionManager.isOffline"
        assertTrue(squashed.contains(gate))
    }

    // ── ⑤ MediaCache 全部网络出口都经网关属性（收口不漏） ─────────────

    @Test
    fun `MediaCache 的 fetch 调用点签名不变（仍是 gateway 三方法）`() {
        val cache = source(cachePath)
        // 14 处直调（client. / client!!.）+ fetchCompressed 参数改网关类型后依旧成立
        val fetchImageCount = Regex("""client!{0,2}\.fetchImage\(""").findAll(cache).count()
        val fetchVideoCount = Regex("""client!{0,2}\.fetchVideo\(""").findAll(cache).count()
        val viewUrlCount = Regex("""client!{0,2}\.viewUrl\(""").findAll(cache).count()
        assertTrue("fetchImage 调用点应有 8 处，实际 $fetchImageCount", fetchImageCount == 8)
        assertTrue("fetchVideo 调用点应有 4 处，实际 $fetchVideoCount", fetchVideoCount == 4)
        assertTrue("viewUrl 调用点应有 2 处，实际 $viewUrlCount", viewUrlCount == 2)
    }
}
