package sh.hnet.comfychair

import sh.hnet.comfychair.viewmodel.MediaViewerItem

/**
 * 图库 → 查看器之间传递条目列表的**进程内通道**（P1-29）。
 *
 * 修复前整份列表被序列化成 JSON 塞进 Intent（`EXTRA_GALLERY_ITEMS_JSON`）：
 * 每条至少 5 个字段、图库攒到几千条时字符串可达数百 KB 到 MB 级，
 * 而 Binder 事务缓冲上限只有 1MB —— 长期使用必然踩 `TransactionTooLargeException`
 * （崩溃发生在 startActivity，用户看到的是"一点图就闪退"）。
 *
 * 现在改为：列表只存在本进程内存里，Intent 只带一个短 id。
 * 进程仍然存活（旋转、切后台回来）时行为与以前完全一致；进程被系统回收后
 * 重建时 id 查不到，调用方按"无法恢复"处理（见 MediaViewerActivity），
 * 不会出现"传了半份列表"的中间态。
 *
 * 只保留**一个**会话：App 里同时只可能有一个查看器在前台，多份缓存没有意义。
 */
object MediaViewerSession {

    private var sessionId: String? = null
    private var items: List<MediaViewerItem>? = null
    private var counter = 0L

    /**
     * 存入本次要展示的条目，返回会话 id（放进 Intent 的那个短字符串）。
     *
     * 同一时刻只保留最新一份：上一份会被整体覆盖，不会随打开次数增长。
     */
    @Synchronized
    fun put(newItems: List<MediaViewerItem>): String {
        val id = "viewer_${++counter}_${System.currentTimeMillis()}"
        sessionId = id
        items = newItems
        return id
    }

    /** 按 id 取条目；id 不匹配（已过期 / 进程重建）返回 null */
    @Synchronized
    fun get(id: String?): List<MediaViewerItem>? {
        if (id == null || id != sessionId) return null
        return items
    }

    /**
     * 释放会话（查看器关闭 / Activity 销毁时调用）。
     *
     * @param id 传入时只清自己那一份（避免误清后来新开的会话）；null 表示全清
     */
    @Synchronized
    fun clear(id: String? = null) {
        if (id != null && id != sessionId) return
        sessionId = null
        items = null
    }
}