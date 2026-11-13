package sh.hnet.comfychair.model

import org.json.JSONObject

/**
 * 「从 ComfyUI 服务器取回介绍数据」的**纯逻辑**部分：地址拼接 + 响应校验。
 *
 * 为什么单拎出来：真正发请求的那半段（OkHttp 回调）依赖 Android/OkHttp 环境、
 * 在 JVM 单测里跑不起来，但**判断一份响应能不能用**恰恰是最容易出错、最该被测的部分
 * （服务器会返回 HTML 错误页、被中间代理截断的半截 JSON、`models` 为空的空表）。
 * 把这些判断留在这里，[sh.hnet.comfychair.ComfyUIClient.fetchModelIntros] 只负责搬运字节。
 *
 * 校验口径与 [ModelIntroTable] 的读取路径保持一致：**只认有内容的表**——
 * `models` 必须是「非空对象」。理由同 [ModelIntroSync]：接受空表等于把用户手上
 * 已有的介绍全清空，而介绍数据本身就只是锦上添花，缺了它一切照常。
 */
object ModelIntroFetch {

    /**
     * 介绍数据允许的最大响应体。
     *
     * 正常数据实测约 250KB；留 16 倍余量只为挡住「服务器返回了别的东西」
     * （错误页被塞长、代理换回一整页 HTML）这类异常，不是为了限制正常增长。
     */
    const val MAX_INTRO_BYTES: Long = 4L * 1024 * 1024

    /**
     * 取用地址 = baseUrl + [ModelIntroSync.REMOTE_PATH]。
     *
     * 地址整体引用常量而不是在这里手拼查询串：一旦服务器端改了文件名/子目录，
     * 只需要动 `ModelIntroSync` 一处，不会出现「改了常量、漏改手拼串」导致的线上 404。
     *
     * @param baseUrl 形如 `http://192.168.1.10:8188`（结尾带不带斜杠都行）
     */
    fun buildUrl(baseUrl: String): String =
        baseUrl.trimEnd('/') + ModelIntroSync.REMOTE_PATH

    /**
     * 把响应字节解码成「可用的介绍 JSON 文本」，不可用返回 null。
     *
     * 解码用 UTF-8 且**错误字节替换而非抛出**：服务器把二进制内容原样吐回来时，
     * 这里只是判成不可用，不该让调用方收到异常。
     */
    fun decodePayload(bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        // BOM 去掉后再交出去：下游 ModelIntroSync 会直接丢给 JSONObject 解析，留着它就解析不了
        val text = bytes.toString(Charsets.UTF_8).trim().removePrefix(BOM).trim()
        if (text.isEmpty()) return null
        return text.takeIf { isUsablePayload(it) }
    }

    /**
     * 这份文本是不是一份可用的介绍表。
     *
     * 两道关：先看首尾是不是一对花括号（HTML 错误页、半截响应在这一步就被挡下，
     * 省掉一次完整解析），再真正解析并确认 `models` 非空。
     */
    fun isUsablePayload(text: String): Boolean {
        val trimmed = text.trim().removePrefix(BOM)
        if (trimmed.length < 2) return false
        if (trimmed.first() != '{' || trimmed.last() != '}') return false
        return try {
            val models = JSONObject(trimmed).optJSONObject("models") ?: return false
            models.length() > 0
        } catch (_: Exception) {
            // 半截 JSON（如被代理截断）解析会抛异常 —— 判为不可用即可
            false
        }
    }

    /** UTF-8 BOM（部分编辑器/代理会给文件加上，直接解析会失败）。 */
    private const val BOM = "\uFEFF"
}
