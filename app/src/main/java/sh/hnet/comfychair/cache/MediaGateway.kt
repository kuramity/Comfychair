package sh.hnet.comfychair.cache

import android.graphics.Bitmap
import sh.hnet.comfychair.connection.ConnectionFailure

/**
 * 媒体网关：[MediaCache] 访问服务端媒体数据的窄接口（B4.2-A 件一）。
 *
 * MediaCache 自身零 OkHttp——它的网络出口在接口化之前就收口在
 * `comfyUIClient` 一个属性上（`ConnectionManager.clientOrNull`）；本接口把这个
 * 属性的**类型**也收窄到只有媒体链路真正用到的三个方法，此后：
 * - MediaCache 对连接层的依赖只剩这三个签名（拿不到 WebSocket、探测、协议等能力）；
 * - 连接层向缓存层暴露什么，由实现类（[sh.hnet.comfychair.ComfyUIClient]）决定，
 *   缓存层不再看见连接层的全部内部。
 *
 * 三个方法的签名（含默认参数值）与 ComfyUIClient 上的实现**逐字一致**——
 * MediaCache 的多处调用走的是三参简写（依赖默认参数），经接口类型引用时
 * 默认值取自接口侧，两处必须同值：
 * - [DEFAULT_MAX_DECODE_DIMENSION] ↔ ComfyUIClient.MAX_DECODE_DIMENSION（6144，与
 *   "大图不进内存缓存"的 32 MB 预算配套，改小会让那条推理失效）；
 * - `subfolder` / `type` / `preview` 的默认值两侧均为 `""` / `"output"` / `null`。
 */
interface MediaGateway {

    companion object {
        /**
         * 图片解码的最大边长默认值。
         *
         * 与 ComfyUIClient.MAX_DECODE_DIMENSION 保持同值（6144）：接口无法引用
         * 实现类的 private const，所以这里以同值常量承接默认参数——改动任何一侧
         * 都必须同步另一侧（钉子 MediaGatewayContractTest 会检查两侧一致）。
         */
        const val DEFAULT_MAX_DECODE_DIMENSION = 6144
    }

    /**
     * 下载并（按需采样）解码一张图片，异步回调。
     *
     * 参数与回调语义见 ComfyUIClient.fetchImage（同签名，KDoc 不重复）。
     */
    fun fetchImage(
        filename: String,
        subfolder: String = "",
        type: String = "output",
        maxDimension: Int = DEFAULT_MAX_DECODE_DIMENSION,
        preview: String? = null,
        useThumbnailClient: Boolean = false,
        callback: (bitmap: Bitmap?, failureType: ConnectionFailure) -> Unit
    )

    /**
     * 下载一段视频的完整字节，异步回调。
     *
     * 参数与回调语义见 ComfyUIClient.fetchVideo（同签名，KDoc 不重复）。
     */
    fun fetchVideo(
        filename: String,
        subfolder: String = "",
        type: String = "output",
        callback: (bytes: ByteArray?, failureType: ConnectionFailure) -> Unit
    )

    /**
     * 直接可用的 `/view` 地址（查询参数已编码）。
     *
     * 供不经过 OkHttp 请求链的下游使用（MediaMetadataRetriever 远程抽帧）；
     * 认证方式较特殊的服务器上可能返回 null，调用方按"失败即回退整段下载"处理。
     */
    fun viewUrl(
        filename: String,
        subfolder: String = "",
        type: String = "output",
        preview: String? = null
    ): String?
}
