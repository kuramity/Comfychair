package sh.hnet.comfychair.util

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * ComfyUI `/view` 请求 URL 的**唯一**构造入口（P2-4）。
 *
 * 修复前是 `"$baseUrl/view?filename=$filename&subfolder=$subfolder&type=$type"`：
 * `filename` / `subfolder` / `type` 来自 `/history` 响应，属于外部输入，里面只要带
 * `&`、`?`、`#` 或中文空格，就会改写查询串（甚至注入额外参数）；带 `../` 时
 * 由服务端自行解释成路径。这里改用 OkHttp 的 [okhttp3.HttpUrl.Builder] 逐项
 * `addQueryParameter`，每个值都会做查询参数编码，结构由代码固定、值只作为值。
 *
 * 为什么单独抽成无 Android 依赖的 object：JVM 单测能直接钉住编码规则，
 * 不必启动整个 [sh.hnet.comfychair.ComfyUIClient]。
 *
 * 协议只允许 http / https（与服务器配置层的校验口径一致）；应用本身就是连接
 * 用户自建/局域网内的 ComfyUI，因此这里**不做**私网地址拒绝。
 */
internal object ComfyViewUrl {

    /**
     * @param baseUrl `http(s)://host:port`（不含结尾斜杠，见 ComfyUIClient.getBaseUrl）
     * @param preview 非空时追加 `preview=` 参数（服务端压缩预览，如 `jpeg;75`）
     * @return 合法 URL；baseUrl 非法或协议不是 http(s) 时返回 null
     */
    fun build(
        baseUrl: String,
        filename: String,
        subfolder: String,
        type: String,
        preview: String? = null
    ): String? {
        val viewUrl = "$baseUrl/view".toHttpUrlOrNull() ?: return null
        if (viewUrl.scheme != "http" && viewUrl.scheme != "https") return null
        val builder = viewUrl.newBuilder()
            .addQueryParameter("filename", filename)
            .addQueryParameter("subfolder", subfolder)
            .addQueryParameter("type", type)
        if (preview != null) {
            builder.addQueryParameter("preview", preview)
        }
        return builder.build().toString()
    }
}