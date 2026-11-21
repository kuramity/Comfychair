package sh.hnet.comfychair.viewmodel

/**
 * 「源已不存在」条目的请求门（纯函数，JVM 单测直接钉住）。
 *
 * 语义：条目已被确认服务器上不存在（/view 404，且标记已持久化）时，
 * **不再发起任何原图请求** —— 请求的结果只可能是又一次 404 或一次无谓的超时等待。
 * 显示与保存一律回落到本机缓存（720 档统一缩略图 / 旧磁盘缓存）。
 *
 * 注意：这里只回答"要不要请求原图"，不负责判定请求结果。
 * 是否「源已不存在」由 HTTP 状态码决定（[sh.hnet.comfychair.ComfyUIClient.isSourceMissingStatus]），
 * 超时/断网/5xx 永远不会让本函数返回 false。
 */
internal fun shouldRequestOriginal(item: GalleryItem): Boolean = !item.sourceMissing

/** [shouldRequestOriginal] 的查看器条目重载（查看器队列是 MediaViewerItem 快照）。 */
internal fun shouldRequestOriginal(item: MediaViewerItem): Boolean = !item.sourceMissing