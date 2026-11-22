package sh.hnet.comfychair.ui.components

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Singleton manager for a shared ExoPlayer instance.
 *
 * Since only one video plays at a time in the app, we can reuse a single
 * ExoPlayer instance across all video playback locations. This eliminates
 * the initialization delay and surface setup issues that occur when creating
 * new ExoPlayer instances.
 *
 * Uses reference counting to track active consumers. Playback is only paused
 * when all consumers have released the player.
 *
 * Benefits:
 * - No stretched first frame during player initialization
 * - Faster video switching (no player creation overhead)
 * - Lower memory usage (single player instance)
 * - Smooth transitions between preview and fullscreen modes
 */
object SharedVideoPlayer {

    private const val TAG = "SharedVideoPlayer"

    /** 空闲释放的延迟窗口（ms）：[releaseWhenIdle] 的默认延迟，覆盖"预览 → 全屏"这类快速切换，避免整个播放器被来回重建 */
    private const val RELEASE_WHEN_IDLE_DELAY_MS = 250L

    /**
     * 消费者注销宽限（ms）：最后一个消费者 [unregisterConsumer] 时延迟这么久才真正 [ExoPlayer.stop]。
     *
     * 比 [RELEASE_WHEN_IDLE_DELAY_MS] 短是刻意为之：unregister 走的是轻量 stop（视频重置、
     * 解码器保留），只需兜住同一次组合切换里 unregister → register 的瞬时空窗；
     * 而空闲释放走的是重量级 release（解码器 + Surface 全部归还），才需要 250ms。
     * 拉长到 250 只会让用户关掉视频后声音/资源多占一会儿，不改数值。
     */
    private const val UNREGISTER_GRACE_MS = 100L

    /** 可压低音量场景（通知提示音等）下的播放音量 */
    private const val DUCK_VOLUME = 0.2f

    private var exoPlayer: ExoPlayer? = null
    private var currentUri: Uri? = null
    private var isInitialized = false
    private var consumerCount = 0
    private val handler = Handler(Looper.getMainLooper())
    private var pendingStopRunnable: Runnable? = null

    /**
     * 音频焦点（P2-7）。
     *
     * 修复前播放器**从不申请音频焦点**：视频与来电、导航播报、其他音乐 App
     * 同时出声，来电时视频也不会自己停。现在由这里统一申请/放弃。
     */
    private var audioFocus: PlayerAudioFocus? = null

    /** 焦点丢失前是否在播（瞬时丢失恢复后据此决定要不要续播） */
    private var wasPlayingBeforeFocusLoss = false

    /** 本次失焦是"瞬时"（可恢复）还是"永久"（由其他应用接管，不自动续播） */
    private var focusLossIsTransient = false

    /**
     * 退到后台前是否正在播放（P1-30）。
     *
     * 只有"本来在播"才在回前台时恢复；用户自己按暂停的，回前台不能擅自开播。
     */
    private var resumeAfterBackground = false

    /**
     * Get or create the shared ExoPlayer instance.
     * Must be called from a context that has access to Application context.
     */
    fun getPlayer(context: Context): ExoPlayer {
        if (exoPlayer == null) {
            val app = context.applicationContext
            // 音频焦点由本对象自己管（见 PlayerAudioFocus），所以 Media3 侧的
            // handleAudioFocus 必须为 false —— 两边都申请会互相抢、恢复逻辑打架。
            // setAudioAttributes 本身仍然必要：决定音频路由/音量键归属与系统音量分组。
            exoPlayer = ExoPlayer.Builder(app)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    /* handleAudioFocus = */ false
                )
                .build()
                .apply {
                    repeatMode = Player.REPEAT_MODE_ALL
                    playWhenReady = false
                }
            isInitialized = true
            audioFocus = PlayerAudioFocus(app) { change -> handleAudioFocusChange(change) }
        }
        return exoPlayer!!
    }

    /**
     * Register a consumer (VideoPlayer component) as active.
     * Call this when a VideoPlayer enters composition.
     * Cancels any pending stop since there's an active consumer.
     */
    fun registerConsumer(context: Context): ExoPlayer {
        consumerCount++
        // Cancel any pending stop since someone is using the player
        pendingStopRunnable?.let { handler.removeCallbacks(it) }
        pendingStopRunnable = null
        return getPlayer(context)
    }

    /**
     * Unregister a consumer. When count reaches 0, schedules a delayed stop
     * to allow for transitions (e.g., switching from preview to fullscreen).
     * Video is stopped (not paused) so it resets to the beginning on next play.
     */
    fun unregisterConsumer() {
        consumerCount--
        if (consumerCount <= 0) {
            consumerCount = 0
            // Schedule delayed stop to allow for transitions
            pendingStopRunnable?.let { handler.removeCallbacks(it) }
            pendingStopRunnable = Runnable {
                if (consumerCount == 0) {
                    // Stop (not pause) so video resets to beginning on next play
                    exoPlayer?.stop()
                    // 已经没有消费者：连音频焦点一起放掉，别让别的应用以为我们还在播
                    abandonAudioFocus()
                    currentUri = null  // Clear URI so prepareVideo will reload
                }
                pendingStopRunnable = null
            }
            handler.postDelayed(pendingStopRunnable!!, UNREGISTER_GRACE_MS)
        }
    }

    /**
     * Prepare a video from the given URI without starting playback.
     * Call startPlayback() after the surface is ready to begin from frame 0.
     * Always resets to the beginning, even if the same URI is already loaded.
     */
    fun prepareVideo(context: Context, uri: Uri) {
        // Cancel any pending stop
        pendingStopRunnable?.let { handler.removeCallbacks(it) }
        pendingStopRunnable = null

        val player = getPlayer(context)

        if (currentUri != uri) {
            // New video - stop current, load new
            player.stop()
            abandonAudioFocus()
            player.setMediaItem(MediaItem.fromUri(uri))
            player.playWhenReady = false  // Don't auto-play
            player.prepare()
            currentUri = uri
        } else {
            // Same video - just reset to beginning and ensure it's ready
            player.playWhenReady = false
            player.seekTo(0)
        }
    }

    /**
     * Start playback from the beginning.
     * Call this after the video surface is ready to ensure playback starts from frame 0.
     */
    fun startPlayback() {
        exoPlayer?.let { player ->
            player.seekTo(0)
            startWithAudioFocus(player)
        }
    }

    /**
     * Pause playback without releasing resources.
     * Called when a consumer is done but video might be reused.
     */
    fun pause() {
        exoPlayer?.pause()
        // 用户主动暂停：放弃音频焦点，让音乐/导航等应用正常出声
        abandonAudioFocus()
    }

    /**
     * Resume playback if a video is loaded and there are active consumers.
     */
    fun resume() {
        exoPlayer?.let { player ->
            if (currentUri != null && consumerCount > 0) {
                startWithAudioFocus(player)
            }
        }
    }

    /**
     * Check if a specific URI is currently loaded.
     */
    fun isCurrentUri(uri: Uri?): Boolean {
        return uri != null && currentUri == uri
    }

    /**
     * Get the currently loaded URI.
     */
    fun getCurrentUri(): Uri? = currentUri

    /**
     * Release all resources. Call when app is being destroyed.
     */
    fun release() {
        pendingStopRunnable?.let { handler.removeCallbacks(it) }
        pendingStopRunnable = null
        exoPlayer?.release()
        exoPlayer = null
        currentUri = null
        isInitialized = false
        consumerCount = 0
        resumeAfterBackground = false
        abandonAudioFocus()
        audioFocus = null
        wasPlayingBeforeFocusLoss = false
        focusLossIsTransient = false
    }

    // ============================================================
    // 生命周期（P1-30）
    // ============================================================

    /**
     * 宿主（Activity）进入后台：暂停播放。
     *
     * 修复前没有任何调用点处理这件事 —— ExoPlayer 的 playWhenReady 不会因为
     * Activity 不可见而自动停下，退到后台视频仍在出声、继续解码耗电。
     *
     * 只暂停不释放：回前台还要接着播，释放会让 surface 重新走一遍首帧流程。
     */
    fun onHostStopped() {
        val player = exoPlayer ?: return
        // 记录"本来在播"，回前台据此决定要不要恢复（用户手动暂停的不恢复）
        if (player.playWhenReady || player.isPlaying) {
            resumeAfterBackground = true
        }
        player.pause()
        // 退后台就放掉音频焦点：否则来电/其他播放器会以为前台仍有人在放音频
        abandonAudioFocus()

        // 退后台时已经没有任何消费者（例如视频页早就离开、只是占着播放器）：
        // 顺手安排一次空闲释放，别让解码器与 Surface 一直挂到进程结束
        if (consumerCount <= 0) {
            releaseWhenIdle()
        }
    }

    /**
     * 宿主回到前台：恢复此前被 [onHostStopped] 暂停的播放。
     *
     * 三个前提全都满足才恢复：之前确实在播、当前仍有人使用、视频已加载。
     * 任一不满足（如用户已离开视频页）就什么都不做。
     */
    fun onHostStarted() {
        if (!resumeAfterBackground) return
        resumeAfterBackground = false
        val player = exoPlayer ?: return
        if (consumerCount > 0 && currentUri != null) {
            startWithAudioFocus(player)
        }
    }

    // ============================================================
    // 音频焦点（P2-7）
    // ============================================================

    /**
     * 申请焦点成功后开播。
     *
     * 焦点被拒（通话中、其他应用持有独占焦点）时**不强行出声**：宁可这一帧不播，
     * 也不要和正在进行的通话抢扬声器。下一次播放请求会再试一次。
     */
    private fun startWithAudioFocus(player: ExoPlayer) {
        val focus = audioFocus
        if (focus != null && !focus.request()) {
            Log.w(TAG, "音频焦点申请被拒，暂不开播")
            player.pause()
            return
        }
        player.play()
    }

    /** 放弃音频焦点（幂等）。 */
    private fun abandonAudioFocus() {
        audioFocus?.abandon()
    }

    /**
     * 焦点变化（主线程回调）。
     *
     * - 永久丢失：暂停且**不自动续播**（音频已被别的应用接管）；
     * - 瞬时丢失：暂停，等 [AudioManager.AUDIOFOCUS_GAIN] 回来自动续播；
     * - 可压低音量：只把音量压到 [DUCK_VOLUME]，不打断观看，恢复时还原。
     */
    private fun handleAudioFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                val player = exoPlayer ?: return
                wasPlayingBeforeFocusLoss = player.playWhenReady || player.isPlaying
                focusLossIsTransient = false
                player.pause()
                // 永久丢失后不再占着焦点，让接管方拿到干净状态
                abandonAudioFocus()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                val player = exoPlayer ?: return
                wasPlayingBeforeFocusLoss = player.playWhenReady || player.isPlaying
                focusLossIsTransient = true
                player.pause()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                exoPlayer?.volume = DUCK_VOLUME
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                val player = exoPlayer ?: return
                player.volume = 1f
                if (wasPlayingBeforeFocusLoss && focusLossIsTransient && consumerCount > 0) {
                    player.play()
                }
                wasPlayingBeforeFocusLoss = false
                focusLossIsTransient = false
            }
        }
    }

    /**
     * 空闲即释放（P1-30）：延迟一小段时间后，如果已经没有任何消费者，真正 [release]。
     *
     * 为什么要延迟：从预览切到全屏查看器时，旧组件的 onDispose 与新组件的
     * registerConsumer 之间隔着一次组合，立即释放会把正在进行的切换打断。
     *
     * 修复前 [release] 全仓零调用点 —— 播放器（连同解码器与 Surface）永不释放，
     * 每次播放都复用同一份资源，直到进程结束。
     */
    fun releaseWhenIdle(delayMs: Long = RELEASE_WHEN_IDLE_DELAY_MS) {
        handler.postDelayed({
            if (consumerCount == 0) {
                release()
            }
        }, delayMs)
    }

    /**
     * Check if player is initialized.
     */
    fun isReady(): Boolean = isInitialized && exoPlayer != null
}

/**
 * 播放器的音频焦点封装（P2-7）。
 *
 * 为什么自己管而不是用 Media3 的 `handleAudioFocus = true`：本对象是全 App 唯一的
 * 播放器，焦点需要和"消费者计数 / 退后台暂停"两套既有状态协同（例如退后台后放弃焦点、
 * 回前台再申请），自己持有一个 [AudioFocusRequest] 最直观，也便于在焦点被拒时明确
 * 选择"不出声"。
 *
 * minSdk 33，[AudioFocusRequest]（API 26+）无需降级分支。
 */
private class PlayerAudioFocus(
    context: Context,
    onFocusChange: (Int) -> Unit
) {
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** 平台音频属性：媒体用途 + 影视内容（决定音量分组与路由策略） */
    private val attributes = android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
        .build()

    private val focusRequest: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { change -> onFocusChange(change) }
            .build()

    /** @return true = 已持有（或本次拿到）焦点 */
    fun request(): Boolean =
        audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    /** 放弃焦点（幂等，重复调用无副作用）。 */
    fun abandon() {
        runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
    }
}
