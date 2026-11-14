package sh.hnet.comfychair.update

import java.util.Locale

/**
 * 更新模块的数值格式化（v4.7.1）。
 *
 * 全部为纯函数、不依赖 Android 框架 —— 通知栏、弹窗、日志共用同一套口径，
 * 且单元测试可直接覆盖（进度条纹案的每个分支都能断言）。
 */
object UpdateFormat {

    /** 字节数 → 人类可读（B / KB / MB / GB），与弹窗原有口径一致 */
    fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1L shl 20))
        bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes.toDouble() / (1L shl 10))
        else -> "$bytes B"
    }

    /** 速度文案：0 表示还没测出来（窗口不足 2 个采样点） */
    fun formatSpeed(bytesPerSec: Long): String =
        if (bytesPerSec > 0) "${formatBytes(bytesPerSec)}/s" else "—"

    /**
     * 剩余时间文案。
     * - 负数 / 未知 → "—"
     * - <1 分钟 → "45s"
     * - <1 小时 → "3m20s"
     * - 其余 → "1h05m"
     */
    fun formatEta(seconds: Long): String = when {
        seconds < 0 -> "—"
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> {
            val m = seconds / 60
            val s = seconds % 60
            "${m}m${s}s"
        }
        else -> {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            String.format(Locale.US, "%dh%02dm", h, m)
        }
    }

    /**
     * 进度百分比（0~100）。
     * 总长未知（0 或负）时返回 [unknownPercent]，由调用方决定用不确定进度条还是隐藏进度。
     */
    const val unknownPercent: Int = -1

    fun percent(downloaded: Long, total: Long): Int {
        if (total <= 0 || downloaded < 0) return unknownPercent
        val raw = (downloaded * 100 / total).toInt()
        return raw.coerceIn(0, 100)
    }
}
