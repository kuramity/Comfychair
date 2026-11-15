package sh.hnet.comfychair

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import sh.hnet.comfychair.ui.screens.MediaViewerScreen
import sh.hnet.comfychair.ui.theme.ComfyChairTheme
import sh.hnet.comfychair.viewmodel.MediaViewerItem
import sh.hnet.comfychair.viewmodel.MediaViewerViewModel
import sh.hnet.comfychair.viewmodel.ViewerMode
/**
 * Simple in-memory cache for passing bitmaps between activities.
 * Avoids expensive PNG compression/decompression when launching MediaViewer.
 */
object BitmapCache {
    private var cachedBitmap: Bitmap? = null

    fun put(bitmap: Bitmap) {
        cachedBitmap = bitmap
    }

    fun get(): Bitmap? {
        return cachedBitmap
    }

    fun clear() {
        cachedBitmap = null
    }
}

/**
 * Activity for fullscreen media viewing.
 * Supports two modes:
 * - Gallery mode: Swipe navigation between items from ComfyUI history
 * - Single mode: Single preview from generation screens
 */
class MediaViewerActivity : ComponentActivity() {

    private val viewModel: MediaViewerViewModel by viewModels()
    private lateinit var insetsController: WindowInsetsControllerCompat

    companion object {
        // Intent extras
        const val EXTRA_MODE = "mode"
        const val MODE_GALLERY = "gallery"
        const val MODE_SINGLE = "single"

        // Gallery mode extras
        const val EXTRA_HOSTNAME = "hostname"
        const val EXTRA_PORT = "port"
        const val EXTRA_GALLERY_ITEMS_JSON = "gallery_items_json"
        const val EXTRA_INITIAL_INDEX = "initial_index"

        // Single mode extras
        const val EXTRA_IS_VIDEO = "is_video"
        const val EXTRA_VIDEO_URI = "video_uri"

        // Single mode file info (for metadata extraction)
        const val EXTRA_FILENAME = "filename"
        const val EXTRA_SUBFOLDER = "subfolder"
        const val EXTRA_TYPE = "type"

        // Result
        const val RESULT_ITEM_DELETED = "item_deleted"

        // 回传给列表的"离开时正在看哪一张"稳定键（"${promptId}_${filename}"，
        // 与 MediaViewerScreen 的 itemsKey / GalleryScreen 的 grid key 同格式）。
        // 不回传裸索引：删除后 items 会变，索引会错位
        const val EXTRA_CURRENT_ITEM_KEY = "current_item_key"

        /**
         * Create intent for gallery mode (swipe navigation between items)
         */
        fun createGalleryIntent(
            context: Context,
            hostname: String,
            port: Int,
            items: List<MediaViewerItem>,
            initialIndex: Int
        ): Intent {
            return Intent(context, MediaViewerActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_GALLERY)
                putExtra(EXTRA_HOSTNAME, hostname)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_GALLERY_ITEMS_JSON, MediaViewerItem.listToJson(items))
                putExtra(EXTRA_INITIAL_INDEX, initialIndex)
            }
        }

        /**
         * Create intent for single image mode (from generation screen preview)
         *
         * @param hostname Server hostname for metadata extraction (optional)
         * @param port Server port for metadata extraction (optional)
         * @param filename Server filename for metadata extraction (optional)
         * @param subfolder Server subfolder for metadata extraction (optional)
         * @param type Server type for metadata extraction (optional)
         */
        fun createSingleImageIntent(
            context: Context,
            bitmap: Bitmap,
            hostname: String? = null,
            port: Int? = null,
            filename: String? = null,
            subfolder: String? = null,
            type: String? = null
        ): Intent {
            // Store bitmap in memory cache (avoids expensive PNG compression/decompression)
            BitmapCache.put(bitmap)

            return Intent(context, MediaViewerActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_SINGLE)
                putExtra(EXTRA_IS_VIDEO, false)
                // Add server and file info for metadata extraction
                hostname?.let { putExtra(EXTRA_HOSTNAME, it) }
                port?.let { putExtra(EXTRA_PORT, it) }
                filename?.let { putExtra(EXTRA_FILENAME, it) }
                subfolder?.let { putExtra(EXTRA_SUBFOLDER, it) }
                type?.let { putExtra(EXTRA_TYPE, it) }
            }
        }

        /**
         * Create intent for single video mode (from generation screen preview)
         *
         * Note: For videos, metadata can be extracted directly from the video file,
         * so server info is optional but can be provided for consistency.
         */
        fun createSingleVideoIntent(
            context: Context,
            videoUri: Uri,
            hostname: String? = null,
            port: Int? = null,
            filename: String? = null,
            subfolder: String? = null,
            type: String? = null
        ): Intent {
            return Intent(context, MediaViewerActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_SINGLE)
                putExtra(EXTRA_IS_VIDEO, true)
                putExtra(EXTRA_VIDEO_URI, videoUri.toString())
                // Add server and file info for metadata extraction
                hostname?.let { putExtra(EXTRA_HOSTNAME, it) }
                port?.let { putExtra(EXTRA_PORT, it) }
                filename?.let { putExtra(EXTRA_FILENAME, it) }
                subfolder?.let { putExtra(EXTRA_SUBFOLDER, it) }
                type?.let { putExtra(EXTRA_TYPE, it) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Setup system bars control for hide/show behavior
        insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_SINGLE

        when (mode) {
            MODE_GALLERY -> initializeGalleryMode()
            MODE_SINGLE -> initializeSingleMode()
        }

        setContent {
            val uiState by viewModel.uiState.collectAsState()

            // Control system bars based on UI visibility
            LaunchedEffect(uiState.isUiVisible) {
                if (uiState.isUiVisible) {
                    insetsController.show(WindowInsetsCompat.Type.systemBars())
                } else {
                    insetsController.hide(WindowInsetsCompat.Type.systemBars())
                }
            }

            ComfyChairTheme(forceDarkStatusBar = true) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MediaViewerScreen(
                        viewModel = viewModel,
                        onClose = {
                            // 结果（删除标记 + 当前位置键）统一在 finish() 里构造，
                            // 捏合关闭/下滑关闭/返回键/FAB 全部汇聚到这一个出口
                            finish()
                        }
                    )
                }
            }
        }
    }

    override fun finish() {
        // 无论哪条路径退出（捏合、下滑、返回键、系统回收），都回传当前浏览位置：
        // 列表侧据此把网格滚动到用户离开时正在看的那一张。
        // currentItem 取自删除后已更新的 items（ViewModel 删除时会先移除再调 currentIndex），
        // 所以这里不会回传刚被删除的项；全部删完时 currentItem 为 null，自然跳过
        val resultIntent = Intent()
        if (viewModel.hasDeletedItems.value) {
            resultIntent.putExtra(RESULT_ITEM_DELETED, true)
        }
        viewModel.uiState.value.currentItem?.let { item ->
            resultIntent.putExtra(EXTRA_CURRENT_ITEM_KEY, "${item.promptId}_${item.filename}")
        }
        setResult(Activity.RESULT_OK, resultIntent)
        super.finish()

        // 关闭过渡（C2）：列表侧保持不动，查看器内容缩小淡出。
        // 按官方要求紧跟 finish() 之后调用
        overridePendingTransition(R.anim.gallery_stay, R.anim.viewer_fade_out_shrink)
    }

    private fun initializeGalleryMode() {
        val hostname = intent.getStringExtra(EXTRA_HOSTNAME) ?: ""
        val port = intent.getIntExtra(EXTRA_PORT, 8188)
        val itemsJson = intent.getStringExtra(EXTRA_GALLERY_ITEMS_JSON) ?: "[]"
        val initialIndex = intent.getIntExtra(EXTRA_INITIAL_INDEX, 0)

        val items = MediaViewerItem.listFromJson(itemsJson)

        viewModel.initialize(
            context = this,
            hostname = hostname,
            port = port,
            mode = ViewerMode.GALLERY,
            items = items,
            initialIndex = initialIndex
        )
    }

    private fun initializeSingleMode() {
        val isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)

        // Extract server and file info for metadata extraction
        val hostname = intent.getStringExtra(EXTRA_HOSTNAME) ?: ""
        val port = intent.getIntExtra(EXTRA_PORT, 0)
        val filename = intent.getStringExtra(EXTRA_FILENAME) ?: ""
        val subfolder = intent.getStringExtra(EXTRA_SUBFOLDER) ?: ""
        val type = intent.getStringExtra(EXTRA_TYPE) ?: "output"

        if (isVideo) {
            val videoUriString = intent.getStringExtra(EXTRA_VIDEO_URI)
            val videoUri = videoUriString?.let { Uri.parse(it) }

            // Create a single item for the video with file info
            val item = MediaViewerItem(
                promptId = "",
                filename = filename,
                subfolder = subfolder,
                type = type,
                isVideo = true,
                index = 0
            )

            viewModel.initialize(
                context = this,
                hostname = hostname,
                port = port,
                mode = ViewerMode.SINGLE,
                items = listOf(item),
                initialIndex = 0,
                singleVideoUri = videoUri
            )
        } else {
            // Retrieve bitmap from memory cache (avoids file I/O)
            val bitmap = BitmapCache.get()
            BitmapCache.clear()

            // Create a single item for the image with file info
            val item = MediaViewerItem(
                promptId = "",
                filename = filename,
                subfolder = subfolder,
                type = type,
                isVideo = false,
                index = 0
            )

            viewModel.initialize(
                context = this,
                hostname = hostname,
                port = port,
                mode = ViewerMode.SINGLE,
                items = listOf(item),
                initialIndex = 0,
                singleBitmap = bitmap
            )
        }
    }
}
