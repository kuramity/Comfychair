package sh.hnet.comfychair.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaCacheKey

/**
 * State holder for lazy-loaded bitmaps from MediaCache.
 *
 * @property bitmap The loaded bitmap, or null if not yet loaded or failed
 * @property isLoading Whether the bitmap is currently being loaded
 */
data class LazyBitmapState(
    val bitmap: Bitmap?,
    val isLoading: Boolean
)

/**
 * Remember a lazy-loaded bitmap from MediaCache.
 *
 * This composable handles the common pattern of:
 * 1. Initial synchronous cache lookup
 * 2. Waiting for prefetch if in progress
 * 3. Falling back to direct fetch if needed
 *
 * @param cacheKey The MediaCacheKey to look up
 * @param isVideo Whether this is a video (affects how the bitmap is fetched)
 * @param subfolder The subfolder for fetching
 * @param type The type for fetching (e.g., "output")
 * @param thumbnail true 时取"服务端压缩版的缩略图"，用于列表格子这类小尺寸展示。
 *   这一项对放大类工作流的输出尤其重要：原图可能 200 MB，而缩略图只有几 MB，
 *   为了一格图去传原图是整条链路最贵的开销（详见 MediaCache.fetchThumbnail）。
 * @param retryTick 重试计数器：加载失败后 UI 让用户点击占位图标重试，
 *   每点一次 +1，这里靠 key 变化重新触发 LaunchedEffect（v4.6.0 ——
 *   以前失败即定格，唯一的恢复手段是把这个格子滑出屏幕再滑回来）。
 * @return LazyBitmapState with the bitmap and loading state
 */
@Composable
fun rememberLazyBitmap(
    cacheKey: MediaCacheKey,
    isVideo: Boolean,
    subfolder: String,
    type: String,
    thumbnail: Boolean = false,
    retryTick: Int = 0
): LazyBitmapState {
    val keyString = cacheKey.keyString

    // State for this specific key - keyed remember ensures fresh state per key
    val state = remember(keyString, thumbnail) {
        // Initial synchronous cache lookup
        val cached = if (thumbnail) {
            MediaCache.getThumbnail(cacheKey)
        } else {
            MediaCache.getBitmap(cacheKey)
        }
        mutableStateOf(LazyBitmapState(cached, cached == null))
    }

    // Load bitmap if not in cache（retryTick 进 key：点击重试时重新执行整段）
    LaunchedEffect(keyString, thumbnail, retryTick) {
        if (state.value.bitmap == null) {
            if (retryTick > 0) {
                // 手动重试：用户可能已经把服务器上的文件放了回去，清掉 404 标记再试
                MediaCache.clearFileMissing(cacheKey)
            }
            // 缩略图不走预取（预取拉的是原图），直接取
            if (!thumbnail && MediaCache.isPrefetchInProgress(cacheKey)) {
                MediaCache.awaitPrefetchCompletion(cacheKey)
                val prefetched = MediaCache.getBitmap(cacheKey)
                if (prefetched != null) {
                    state.value = LazyBitmapState(prefetched, false)
                    return@LaunchedEffect
                }
            }

            // Fetch directly
            val bitmap = if (thumbnail) {
                MediaCache.fetchThumbnail(cacheKey, isVideo, subfolder, type)
            } else {
                MediaCache.fetchBitmap(cacheKey, isVideo, subfolder, type)
            }
            state.value = LazyBitmapState(bitmap, false)
        }
    }

    return state.value
}

/**
 * Remember a lazy-loaded bitmap that retains the previous value until a new one is ready.
 *
 * This is useful for UI elements like FABs where you want to avoid showing
 * a loading state when transitioning between items.
 *
 * "保留旧值"的**范围**要说清楚：它指的是"切到下一个条目时、新图还没加载完"这一小段，
 * 不是"没有数据了也要留着"。所以 [cacheKey] 为 null（图库空了，例如最后一张被删掉）
 * 时必须把已显示的缩略图清掉 —— 否则右下角的图库入口会一直挂着那张已经删掉的图。
 *
 * @param cacheKey The MediaCacheKey to look up, or null to clear the current value
 * @param isVideo Whether this is a video
 * @param subfolder The subfolder for fetching
 * @param type The type for fetching
 * @return The most recent successfully loaded bitmap, or null if none
 */
@Composable
fun rememberRetainedBitmap(
    cacheKey: MediaCacheKey?,
    isVideo: Boolean,
    subfolder: String,
    type: String,
    thumbnail: Boolean = false
): Bitmap? {
    var displayedBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(cacheKey?.keyString, thumbnail) {
        if (cacheKey == null) {
            // 没有条目可显示了：回归默认图标（调用方在 null 时会渲染占位图标）
            displayedBitmap = null
            return@LaunchedEffect
        }

        // Try cache first
        var newBitmap = if (thumbnail) {
            MediaCache.getThumbnail(cacheKey)
        } else {
            MediaCache.getBitmap(cacheKey)
        }

        // Wait for prefetch if in progress（缩略图不走预取，与 rememberLazyBitmap 同口径）
        if (!thumbnail && newBitmap == null && MediaCache.isPrefetchInProgress(cacheKey)) {
            MediaCache.awaitPrefetchCompletion(cacheKey)
            newBitmap = MediaCache.getBitmap(cacheKey)
        }

        // If still null, fetch directly
        if (newBitmap == null) {
            newBitmap = if (thumbnail) {
                MediaCache.fetchThumbnail(cacheKey, isVideo, subfolder, type)
            } else {
                MediaCache.fetchBitmap(cacheKey, isVideo, subfolder, type)
            }
        }

        // Only update displayed bitmap when we have the new one
        if (newBitmap != null) {
            displayedBitmap = newBitmap
        }
    }

    return displayedBitmap
}
