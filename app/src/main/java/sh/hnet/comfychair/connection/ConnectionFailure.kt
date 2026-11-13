package sh.hnet.comfychair.connection

/**
 * Types of connection failures that can occur when connecting to ComfyUI server.
 * Used to distinguish recoverable failures (network issues) from non-recoverable ones (auth).
 */
enum class ConnectionFailure {
    /** No failure - connection succeeded */
    NONE,
    /** Authentication failed (401/403) - credentials are invalid */
    AUTHENTICATION,
    /** Network or other recoverable error - retry may succeed */
    NETWORK,
    /** Server is not a valid ComfyUI server */
    INVALID_SERVER,
    /** Transfer stalled - no data transferred for extended period */
    STALLED,
    /**
     * 文件超过 App 的下载上限（见 ComfyUIClient.MAX_DOWNLOAD_BYTES）。
     *
     * 与 NETWORK 分开是有必要的：网络错误值得重试，而**"文件太大"重试多少次结果都一样**。
     * 混在一起会让图库对同一张 200 MB 的图反复发起下载，把链路占满 ——
     * 那正是"生成一张放大图之后整个图库都变慢"的成因之一。
     */
    FILE_TOO_LARGE,
    /**
     * 服务器上已经没有这个文件（/view 返回 404）—— 典型场景是电脑端清理了 output 目录，
     * 而图库索引（/history）里还留着这条记录。
     *
     * 与 NETWORK 分开的理由同 FILE_TOO_LARGE：文件没了重试也不会回来，
     * 图库对这类条目应该直接显示"文件不存在"占位并停止重试，而不是反复打服务器。
     */
    FILE_MISSING
}
