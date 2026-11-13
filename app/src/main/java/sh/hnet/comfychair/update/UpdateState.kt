package sh.hnet.comfychair.update

/**
 * 下载阶段（v4.6.0 内置更新模块）。
 * 顺序：IDLE → (检查出更新) AVAILABLE → DOWNLOADING → VERIFYING → READY_TO_INSTALL；
 * 任何阶段出错进 FAILED（可手动重试）。
 */
enum class UpdatePhase {
    /** 空闲（未检查或无更新） */
    IDLE,

    /** 已发现新版本，等待用户确认下载 */
    AVAILABLE,

    /** 后台下载中（断点续传） */
    DOWNLOADING,

    /** 下载完成，正在流式计算 SHA-256 */
    VERIFYING,

    /** 校验通过，update.apk 就绪，可触发安装 */
    READY_TO_INSTALL,

    /** 失败（网络/校验不匹配/磁盘不足），可手动重试 */
    FAILED
}

/**
 * 更新流程的完整状态（UI 常驻展示的全部字段，见硬性约束 12）。
 *
 * @param phase 当前阶段
 * @param downloadedBytes 已下载字节（VERIFYING 及之后 = 全量）
 * @param totalBytes 总字节（优先取服务器 Content-Range 解析出的总长，其次取 update.json 的 apkSize）
 * @param speedBytesPerSec 滑动窗口速度（字节/秒；非 DOWNLOADING 时为 0）
 * @param etaSeconds 预计剩余秒数（-1 = 无法估算）
 * @param error FAILED 时的失败原因（人类可读中文）
 */
data class UpdateState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long = -1L,
    val error: String? = null
) {
    /** 0~100 的整数百分比（totalBytes 未知时返回 0） */
    val percent: Int
        get() = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0

    companion object {
        /** 从 .part 文件与已知总长恢复"下载中"状态（App 重启后的续传恢复） */
        fun resuming(partBytes: Long, totalBytes: Long) = UpdateState(
            phase = UpdatePhase.DOWNLOADING,
            downloadedBytes = partBytes,
            totalBytes = totalBytes
        )
    }
}
