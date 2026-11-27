package sh.hnet.comfychair.tools

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.feature.tools.PromptEnhanceGateway
import sh.hnet.comfychair.util.UuidUtils
import kotlin.coroutines.resume

/**
 * [PromptEnhanceGateway] 的宿主实现：把「提示词增强」页的提交 / 轮询 / 图片上传
 * 接到现有的 [ComfyUIClient] 上。
 *
 * feature-tools 模块没有网络依赖，网络能力由 app 侧注入；这里**不新建客户端**，
 * 每次调用都通过 [clientProvider] 取当前的共享实例 —— ConnectionManager 重连时
 * 会整体替换 client（ConnectionManager.kt:697 / :2409），持有旧实例会用到已关闭的连接。
 *
 * @param context 仅用于读取用户选的图片（content:// 或 file://）；内部只保留 applicationContext
 * @param clientProvider 当前 ComfyUIClient；默认取 [ConnectionManager.clientOrNull]，未连接时为 null
 */
class PromptEnhanceGatewayImpl(
    context: Context,
    private val clientProvider: () -> ComfyUIClient? = { ConnectionManager.clientOrNull },
) : PromptEnhanceGateway {

    private val appContext: Context = context.applicationContext ?: context

    /**
     * 提交完整工作流 JSON，成功返回 ComfyUI 的 promptId。
     *
     * 工作流 JSON 的解析（抽出 `nodes`、补 `client_id`）由 [ComfyUIClient.submitPrompt]
     * 负责；畸形 JSON 会在那里同步抛出，这里收敛成 [Result.failure] 而不是让异常穿透。
     */
    override suspend fun submit(workflowJson: String): Result<String> {
        val client = clientProvider()
            ?: return Result.failure(IllegalStateException(MSG_NO_CONNECTION))
        return suspendCancellableCoroutine { continuation ->
            try {
                client.submitPrompt(workflowJson, front = false) { success, promptId, errorMessage, _ ->
                    if (continuation.isActive) {
                        val result = if (success && promptId != null && promptId.isNotBlank()) {
                            Result.success(promptId)
                        } else {
                            Result.failure(
                                IllegalStateException(errorMessage ?: MSG_SUBMIT_FAILED)
                            )
                        }
                        continuation.resume(result)
                    }
                }
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resume(Result.failure(e))
            }
        }
    }

    /**
     * 轮询一次结果：null = 还在跑，非 null = `TE_text_display` 的输出文本。
     *
     * ComfyUI 的 `/history/{promptId}` 只在任务结束后才返回该 prompt 的记录，
     * 所以「查不到」就是「还在跑」，不当作失败。
     */
    override suspend fun poll(promptId: String): Result<String?> {
        val client = clientProvider()
            ?: return Result.failure(IllegalStateException(MSG_NO_CONNECTION))
        return suspendCancellableCoroutine { continuation ->
            try {
                client.fetchHistory(promptId) { historyJson ->
                    if (continuation.isActive) {
                        continuation.resume(parseHistory(promptId, historyJson))
                    }
                }
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resume(Result.failure(e))
            }
        }
    }

    /**
     * 上传用户选的图片到 ComfyUI 的 input 目录，返回服务端文件名。
     *
     * 走的是与图生图 / 图生视频同一条现有能力（[ComfyUIClient.uploadImage] + `/upload/image`），
     * 读取 URI 字节的方式也与 ImageToVideoViewModel.uploadLocalFile 一致。
     */
    override suspend fun uploadImage(uri: String): Result<String> {
        val client = clientProvider()
            ?: return Result.failure(IllegalStateException(MSG_NO_CONNECTION))
        val payload = try {
            withContext(Dispatchers.IO) { readImagePayload(Uri.parse(uri)) }
        } catch (e: Exception) {
            return Result.failure(e)
        }
        return suspendCancellableCoroutine { continuation ->
            try {
                client.uploadImage(
                    payload.bytes,
                    payload.filename,
                    mimeType = payload.mimeType
                ) { success, filename, errorMessage, _ ->
                    if (continuation.isActive) {
                        val result = if (success && filename != null && filename.isNotBlank()) {
                            Result.success(filename)
                        } else {
                            Result.failure(
                                IllegalStateException(errorMessage ?: MSG_UPLOAD_FAILED)
                            )
                        }
                        continuation.resume(result)
                    }
                }
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resume(Result.failure(e))
            }
        }
    }

    /**
     * 解析一次 `/history/{promptId}` 的响应。
     *
     * @param historyJson null = 请求失败（网络错误 / 非 2xx / 解析失败）
     */
    private fun parseHistory(promptId: String, historyJson: JSONObject?): Result<String?> {
        if (historyJson == null) {
            return Result.failure(IllegalStateException(MSG_HISTORY_FAILED))
        }
        val entry = historyJson.optJSONObject(promptId)
        val outputs = entry?.optJSONObject("outputs")
        if (entry == null || outputs == null) {
            // 任务还没结束：/history 里没有这条记录（或还没有 outputs）
            return Result.success(null)
        }
        extractDisplayText(outputs)?.let { return Result.success(it) }
        // 执行失败时 history 也会落盘，outputs 通常为空；此时必须把
        // exception_message 抛给界面，否则只能一直轮询到超时（接口约定：不吞错误）。
        extractExecutionError(entry)?.let { return Result.failure(IllegalStateException(it)) }
        return Result.success(null)
    }

    /**
     * 从 outputs 里取文本产物：不写死节点 id，取第一个带 `text` 键的节点（节点 id 会变）。
     *
     * 实测 `TE_text_display` 的输出形如 `{"<nodeId>": {"text": ["..."], "input_text": ["..."]}}`。
     */
    private fun extractDisplayText(outputs: JSONObject): String? {
        val nodeIds = outputs.keys()
        while (nodeIds.hasNext()) {
            val node = outputs.optJSONObject(nodeIds.next()) ?: continue
            val array = node.optJSONArray("text") ?: continue
            // 2026-11-27 修正：找到了 `text` 数组就说明任务已经产出结果，**哪怕内容是空白**
            // 也直接返回（空串）。原先用 isNotBlank 过滤，导致"成功但输出空白"被当成
            // "还在跑"，一路轮询到 180s 超时才报错，界面的空态分支永远走不到。
            return array.optString(0, "")
        }
        return null
    }

    /**
     * 取执行失败的错误文本。
     *
     * ComfyUI 的 history 条目带 `status.status_str`（success/error）与
     * `status.messages`（`[["execution_error", {"exception_message": ...}], ...]`，
     * 见 F:\ComfyUI\execution.py:690 / :714）。非 error 状态返回 null。
     */
    private fun extractExecutionError(entry: JSONObject): String? {
        val status = entry.optJSONObject("status") ?: return null
        if (status.optString("status_str") != "error") return null
        val messages = status.optJSONArray("messages")
        if (messages != null) {
            for (i in 0 until messages.length()) {
                val message = messages.optJSONArray(i) ?: continue
                if (message.optString(0) != "execution_error") continue
                val data = message.optJSONObject(1) ?: continue
                val text = data.optString("exception_message", "")
                if (text.isNotBlank()) return text
            }
        }
        return MSG_EXECUTION_FAILED
    }

    /** 读 URI 字节 + 推导服务端文件名 / MIME（IO 线程调用）。 */
    private fun readImagePayload(uri: Uri): UploadPayload {
        val mimeType = resolveMimeType(uri)
        val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException(MSG_READ_IMAGE_FAILED)
        if (bytes.isEmpty()) throw IllegalStateException(MSG_READ_IMAGE_FAILED)
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            ?: uri.lastPathSegment
                ?.substringAfterLast('.', "")
                ?.takeIf { it.length in 1..5 }
            ?: "png"
        val filename = UuidUtils.generateUniqueUploadFilename("pe_image", extension.lowercase())
        return UploadPayload(bytes, filename, mimeType)
    }

    /**
     * 内容提供者给的 MIME 属于**外部输入**：畸形值（空串、逗号分隔的多个值等）会让
     * OkHttp 的 `toMediaType()` 抛 IllegalArgumentException。校验规则与
     * ImageToVideoViewModel.kt:333 的 MIME_TYPE_PATTERN 一致。
     */
    private fun resolveMimeType(uri: Uri): String {
        val declared = runCatching { appContext.contentResolver.getType(uri) }.getOrNull()
        if (declared != null && MIME_TYPE_PATTERN.matches(declared)) return declared
        val extension = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
        val guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        return if (guessed != null && MIME_TYPE_PATTERN.matches(guessed)) guessed else "image/png"
    }

    /** 一次图片上传的载荷（字节 + 服务端文件名 + 表单 MIME）。 */
    private data class UploadPayload(
        val bytes: ByteArray,
        val filename: String,
        val mimeType: String,
    )

    private companion object {
        private const val MSG_NO_CONNECTION = "未连接到 ComfyUI 服务器"
        private const val MSG_SUBMIT_FAILED = "提交工作流失败"
        private const val MSG_HISTORY_FAILED = "获取生成结果失败（网络错误）"
        private const val MSG_EXECUTION_FAILED = "工作流执行失败"
        private const val MSG_UPLOAD_FAILED = "图片上传失败"
        private const val MSG_READ_IMAGE_FAILED = "无法读取所选图片"

        private val MIME_TYPE_PATTERN =
            Regex("""^[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*$""")
    }
}