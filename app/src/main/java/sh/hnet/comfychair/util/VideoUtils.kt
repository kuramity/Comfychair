package sh.hnet.comfychair.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.R
import sh.hnet.comfychair.cache.MediaStateHolder
import sh.hnet.comfychair.connection.ConnectionFailure
import sh.hnet.comfychair.connection.ConnectionManager

/**
 * Utility functions for video operations shared across video generation screens.
 */
object VideoUtils {

    /**
     * Fetches a generated video from ComfyUI history and saves it to internal storage.
     *
     * @param context Application context
     * @param client ComfyUI API client
     * @param promptId The prompt ID to fetch history for
     * @param filePrefix Prefix for the local video file (e.g., "last_generated_video_" or "image_to_video_last_generated_")
     * @param onComplete Callback with the video URI, or null if failed
     */
    fun fetchVideoFromHistory(
        context: Context,
        client: ComfyUIClient,
        promptId: String,
        filePrefix: String,
        onComplete: (Uri?) -> Unit
    ) {
        val mainHandler = Handler(Looper.getMainLooper())

        client.fetchHistory(promptId) { historyJson ->
            if (historyJson == null) {
                mainHandler.post { onComplete(null) }
                return@fetchHistory
            }

            // Parse video info from history
            val promptHistory = historyJson.optJSONObject(promptId)
            val outputs = promptHistory?.optJSONObject("outputs")

            if (outputs == null) {
                mainHandler.post { onComplete(null) }
                return@fetchHistory
            }

            // 统一挑选视频（P2-6）：遍历所有节点、检查 videos/gifs/images 三个数组、
            // 按扩展名过滤。旧实现 `videos ?: gifs ?: images` 后取 [0] 会在遇到空数组时
            // 提前结束，还会把 images 里的 png 当视频下载。
            val video = VideoOutputSelector.first(outputs)
            if (video == null) {
                mainHandler.post { onComplete(null) }
                return@fetchHistory
            }

            // Fetch video bytes
            client.fetchVideo(video.filename, video.subfolder, video.type) { videoBytes, failureType ->
                if (videoBytes == null) {
                    if (failureType == ConnectionFailure.STALLED) {
                        mainHandler.post {
                            ConnectionManager.showConnectionAlert(context, failureType)
                            onComplete(null)
                        }
                    } else {
                        mainHandler.post { onComplete(null) }
                    }
                    return@fetchVideo
                }

                // Store in memory/disk based on caching mode
                val mediaKey = when (filePrefix) {
                    FilePrefix.TEXT_TO_VIDEO -> MediaStateHolder.MediaKey.TtvVideo(promptId)
                    FilePrefix.IMAGE_TO_VIDEO -> MediaStateHolder.MediaKey.ItvVideo(promptId)
                    else -> null
                }

                if (mediaKey == null) {
                    mainHandler.post { onComplete(null) }
                    return@fetchVideo
                }

                // Pass context for disk-first mode support
                MediaStateHolder.putVideoBytes(mediaKey, videoBytes, context)

                // Get URI for playback (creates temp file in cacheDir)
                CoroutineScope(Dispatchers.Main).launch {
                    val uri = MediaStateHolder.getVideoUri(context, mediaKey)
                    onComplete(uri)
                }
            }
        }
    }

    /**
     * Saves a video from a content URI to the device's Movies gallery.
     *
     * @param context Application context
     * @param videoUri The source video URI to save
     * @param filenamePrefix Prefix for the saved file (e.g., "ComfyChair" or "ComfyChair_ITV")
     */
    suspend fun saveVideoToGallery(
        context: Context,
        videoUri: Uri?,
        filenamePrefix: String = "ComfyChair"
    ) {
        if (videoUri == null) return

        withContext(Dispatchers.IO) {
            // P1-22：走统一的相册写入（IS_PENDING → 发布），失败时必须回滚并报失败。
            // 修复前 insert 失败静默无提示、openOutputStream 返回 null 时仍弹"已保存"。
            val saved = PhotoManager.saveVideo(
                context = context,
                displayName = "${filenamePrefix}_${System.currentTimeMillis()}.mp4"
            ) { outputStream ->
                // P2-12：播放 URI 已改为 file://（进程内播放，不经 FileProvider），
                // contentResolver.openInputStream 读不了 file://，统一走 ShareFileStore
                ShareFileStore.openMediaInput(context, videoUri)?.use { inputStream ->
                    inputStream.copyTo(outputStream)
                } ?: throw java.io.IOException("无法读取视频源：$videoUri")
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    context.getString(
                        if (saved) R.string.msg_video_saved_to_gallery else R.string.error_save_video
                    ),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Shares a video via Android's share sheet.
     *
     * @param context Application context
     * @param videoUri The video URI to share
     */
    fun shareVideo(context: Context, videoUri: Uri?) {
        if (videoUri == null) return

        // P2-12：播放 URI 现在是 file://，不能直接放进 ACTION_SEND
        //（Intent 离开进程时 file:// 会抛 FileUriExposedException）。
        // 先按需复制一份到 cacheDir/share/，分享它的 content URI。
        // 复制可能涉及几十 MB，放 IO 线程，避免点分享时卡住 UI。
        CoroutineScope(Dispatchers.Main).launch {
            val shareUri = withContext(Dispatchers.IO) {
                runCatching { ShareFileStore.exportVideoForShare(context, videoUri) }.getOrNull()
            }
            if (shareUri == null) {
                Toast.makeText(
                    context,
                    context.getString(R.string.error_share_video),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            launchShareChooser(context, shareUri)
        }
    }

    /** 拉起系统分享选择器（已在主线程） */
    private fun launchShareChooser(context: Context, shareUri: Uri) {
        try {
            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_STREAM, shareUri)
                type = "video/mp4"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(
                Intent.createChooser(
                    shareIntent,
                    context.getString(R.string.share_video)
                )
            )
        } catch (e: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.error_share_video),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Constants for video file prefixes used by different screens.
     */
    object FilePrefix {
        const val TEXT_TO_VIDEO = "last_generated_video_"
        const val IMAGE_TO_VIDEO = "image_to_video_last_generated_"
    }

    /**
     * Constants for gallery filename prefixes.
     */
    object GalleryPrefix {
        const val TEXT_TO_VIDEO = "ComfyChair"
        const val IMAGE_TO_VIDEO = "ComfyChair_ITV"
    }
}
