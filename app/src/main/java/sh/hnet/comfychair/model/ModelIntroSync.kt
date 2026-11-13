package sh.hnet.comfychair.model

import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * 模型介绍数据的**服务器热更新**：把 ComfyUI 服务器上的 `model_intros.json` 同步到本地。
 *
 * 为什么需要它：介绍数据原本只能随 APK 内置发布——用户新下载了模型，手机端刷新模型列表
 * 也看不到它的介绍，非得重新出包装机不可。电脑端的数据链路（civitai 双站反查 + 中文规范化）
 * 一直在更新同一个文件，App 只需要在刷新模型列表时顺带把它取回来。
 *
 * 设计要点（三条，都是"介绍只是锦上添花"这条原则的推论）：
 * 1. **不打扰主流程**：同步由调用方放在后台线程，失败只记日志，绝不抛出、绝不阻塞模型列表；
 * 2. **没更新就不动**：比对 `generated_at`（桌面端每次构建都会重写它）与 `version`，
 *    只有**远端比本地新**才落盘；相同、更旧、拿不到时间戳但版本号也没变，都连磁盘都不碰
 *    —— 刷新列表是高频动作，不能每次都写几百 KB；
 * 3. **坏数据不采纳**：半截 JSON、HTML 错误页、`models` 为空的表一律拒绝，
 *    宁可继续用旧介绍，也不能让用户手上的介绍凭空消失。
 *
 * 这个对象刻意不持有 Context：落盘目标是一个 [File]，比对基准是几个值，
 * 于是整套判断逻辑能在 JVM 单测里跑（见 `ModelIntroSyncTest`）。
 */
object ModelIntroSync {

    private const val TAG = "ModelIntroSync"

    /** 服务器上的介绍文件名（ComfyUI 输出目录下）。 */
    const val REMOTE_FILENAME = "model_intros.json"

    /** 服务器上的子目录（对应 ComfyUI 的 `output/comfychair/`）。 */
    const val REMOTE_SUBFOLDER = "comfychair"

    /** ComfyUI 文件类型（output 目录）。 */
    const val REMOTE_TYPE = "output"

    /** `/view` 的相对路径，拼在 baseUrl 之后。 */
    const val REMOTE_PATH = "/view?filename=$REMOTE_FILENAME&subfolder=$REMOTE_SUBFOLDER&type=$REMOTE_TYPE"

    /** 同步结果（调用方只用于记日志，不做流程分支）。 */
    enum class Result {
        /** 已落盘并重载：界面上能查到新模型了 */
        UPDATED,

        /** 服务器上那份与本地一致，什么都没做 */
        SKIPPED,

        /** 数据不可用（非 JSON / 结构不对 / 空表），本地保持原样 */
        INVALID,
    }

    private data class Header(val generatedAt: String?, val version: Int)

    /**
     * 该不该把服务器上这份表装进来。
     *
     * 判据优先 `generated_at`：桌面端每次构建都会重写这个时间戳，比版本号精确
     * （同一版数据反复构建时版本号可能不变）。**只认"远端更新"**——`generated_at`
     * 是 `YYYY-MM-DD HH:MM:SS`，字典序即时间序，所以直接字符串比较：
     *
     * - 远端更新 → 落盘重载；
     * - 远端相同或**更旧** → 一个字节都不动。远端更旧是真实会发生的：APK 内置资产
     *   随新版本发布后就会比服务器上那份新，若按"不相等就覆盖"处理，用户升级后的
     *   首次刷新会把表**回退**成服务端的旧版本（旧版可能缺新模型的介绍）。
     *
     * 远端没有时间戳时退化为比 `version`——**宁可多同步一次，不可漏同步**：
     * 本地连时间戳都没有（老版 APK 内置的表）也一律去取。
     */
    fun shouldApply(
        remoteGeneratedAt: String?,
        remoteVersion: Int,
        localGeneratedAt: String?,
        localVersion: Int,
    ): Boolean {
        val remoteAt = remoteGeneratedAt?.takeIf { it.isNotBlank() }
        if (remoteAt != null) {
            val localAt = localGeneratedAt?.takeIf { it.isNotBlank() } ?: return true
            return remoteAt > localAt
        }
        return remoteVersion != localVersion || localGeneratedAt.isNullOrBlank()
    }

    /**
     * 把服务器上取回的一份 JSON 同步到本地缓存并重载内存表。
     *
     * 顺序是「先校验并装载、再落盘」：JSON 装不成非空表就立刻返回，磁盘一个字节都不动；
     * 装载成功后内存里已经生效（本次会话就能看到新介绍），落盘只为下次冷启动准备。
     * 落盘失败（磁盘满、权限异常）只记日志——介绍本次会话已经可用了，不值得再打扰用户。
     *
     * @param remoteJson 服务器返回的原文（调用方原样传入，不预解析）
     * @param cacheFile 落盘目标（App 私有目录，见 [ModelIntroTable.cacheFile]）
     * @param localGeneratedAt 本地当前表的生成时间，默认取已装载的表
     * @param localVersion 本地当前表的版本号，默认取已装载的表
     */
    fun apply(
        remoteJson: String,
        cacheFile: File,
        localGeneratedAt: String? = ModelIntroTable.generatedAt,
        localVersion: Int = ModelIntroTable.version,
    ): Result {
        val header = try {
            readHeader(remoteJson)
        } catch (e: Exception) {
            logW("服务器返回的介绍数据不是合法 JSON，保持本地原样", e)
            null
        } ?: return Result.INVALID

        if (!shouldApply(header.generatedAt, header.version, localGeneratedAt, localVersion)) {
            return Result.SKIPPED
        }

        // 装不上（结构不对 / models 是空的）就整体放弃：不接受比"旧介绍"更差的结果
        if (!ModelIntroTable.reloadFrom(remoteJson)) {
            logW("服务器返回的介绍数据装不成表（models 为空或结构不对），保持本地原样")
            return Result.INVALID
        }

        writeAtomically(remoteJson, cacheFile)
        return Result.UPDATED
    }

    /**
     * 读表头（生成时间 + 版本号），顺带把「这份数据能不能用」判掉。
     *
     * 只认有内容的结构：`models` 必须是**非空对象**——服务器构建脚本异常时会写出
     * `"models": {}` 这种表，接受它等于把用户已有的介绍全清空。
     */
    private fun readHeader(json: String): Header? {
        val root = JSONObject(json)
        val models = root.optJSONObject("models") ?: return null
        if (models.length() == 0) return null
        val version = if (root.has("version")) root.optInt("version", 0) else 0
        val generatedAt = root.optString("generated_at", "").takeIf { it.isNotBlank() }
        return Header(generatedAt, version)
    }

    /**
     * 先写临时文件再改名——避免"写到一半进程被杀"留下半截 JSON，
     * 下次冷启动读到的就是坏缓存（[ModelIntroTable.resolveInitialJson] 会回退内置表，
     * 但那意味着又一次介绍整体降级，不值得冒这个险）。
     */
    private fun writeAtomically(json: String, cacheFile: File) {
        try {
            cacheFile.parentFile?.mkdirs()
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmp.writeText(json)
            if (tmp.renameTo(cacheFile)) return
            // 改名失败（目标被占用等）：退回直接写，别留着临时文件
            logW("介绍数据改名失败，改为直接写入")
            cacheFile.writeText(json)
            tmp.delete()
        } catch (e: Exception) {
            logW("介绍数据落盘失败（本次会话内存里已生效，下次冷启动仍是旧数据）", e)
        }
    }

    /** 单测环境没有 `android.util.Log` 实现（调用即抛），包一层保证日志本身不会成为故障源。 */
    private fun logW(message: String, e: Exception? = null) {
        try {
            if (e != null) Log.w(TAG, message, e) else Log.w(TAG, message)
        } catch (_: Throwable) {
            // 无 Log 实现，忽略
        }
    }
}
