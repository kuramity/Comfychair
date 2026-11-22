package sh.hnet.comfychair.storage

import android.content.Context
import org.json.JSONObject
import sh.hnet.comfychair.util.DebugLogger
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Singleton for caching /object_info responses to disk per server.
 * Uses GZIP compression since object_info can be 500KB-1MB+.
 * Files are stored in context.filesDir for persistence across app restarts.
 */
object ObjectInfoCache {
    private const val TAG = "ObjectInfoCache"
    private const val CACHE_PREFIX = "object_info_"
    private const val CACHE_SUFFIX = ".json.gz"
    private const val TIMESTAMP_SUFFIX = ".timestamp"

    private const val TMP_SUFFIX = ".tmp"
    /** 解析失败时保留证据的备份后缀前缀：`<原名>.corrupt-yyyyMMdd-HHmmss` */
    private const val CORRUPT_SUFFIX_PREFIX = ".corrupt-"

    /**
     * Save object_info JSON to disk cache for the specified server.
     * @param context Application context
     * @param serverId Unique server identifier
     * @param json The object_info JSON response
     */
    fun saveObjectInfo(context: Context, serverId: String, json: JSONObject) {
        try {
            val cacheFile = getCacheFile(context, serverId)
            val timestampFile = getTimestampFile(context, serverId)

            // Write compressed JSON（原子写：.tmp → fsync → 原子改名覆盖，见 writeGzipAtomically）
            writeGzipAtomically(cacheFile, json)

            // Write timestamp
            timestampFile.writeText(System.currentTimeMillis().toString())

            DebugLogger.i(TAG, "Object info cached for server $serverId, size: ${cacheFile.length()} bytes")
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to save object info cache: ${e.message}")
        }
    }

    /**
     * Load object_info JSON from disk cache for the specified server.
     * @param context Application context
     * @param serverId Unique server identifier
     * @return The cached JSON object, or null if not found or corrupted
     */
    fun loadObjectInfo(context: Context, serverId: String): JSONObject? {
        val cacheFile = getCacheFile(context, serverId)
        if (!cacheFile.exists()) {
            DebugLogger.d(TAG, "No cache file found for server $serverId")
            return null
        }

        return try {
            val jsonString = FileInputStream(cacheFile).use { fis ->
                GZIPInputStream(fis).use { gzip ->
                    gzip.bufferedReader(Charsets.UTF_8).readText()
                }
            }
            val json = JSONObject(jsonString)
            DebugLogger.i(TAG, "Object info loaded from cache for server $serverId")
            json
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to load object info cache: ${e.message}")
            // 读取/解析失败：坏文件（进程中途被杀留下的截断 gzip 属此类）先备份留证据再返回
            // null —— 否则离线模式下坏文件永远占着位置、整份 object_info 缓存不可用也无从排查。
            // 照抄 GalleryMetadataCache.backupCorruptFile 的做法：只复制不删原文件，同一秒重复
            // 解析同名覆盖，不会堆出无数份。
            GalleryMetadataCache.backupCorruptFile(cacheFile)
            null
        }
    }

    /**
     * Check if a cache exists for the specified server.
     * @param context Application context
     * @param serverId Unique server identifier
     * @return true if a valid cache file exists
     */
    fun hasCache(context: Context, serverId: String): Boolean {
        val cacheFile = getCacheFile(context, serverId)
        return cacheFile.exists() && cacheFile.length() > 0
    }

    /**
     * Get the timestamp when the cache was last updated.
     * @param context Application context
     * @param serverId Unique server identifier
     * @return Timestamp in milliseconds, or 0 if not available
     */
    fun getCacheTimestamp(context: Context, serverId: String): Long {
        val timestampFile = getTimestampFile(context, serverId)
        return if (timestampFile.exists()) {
            try {
                timestampFile.readText().toLong()
            } catch (e: Exception) {
                0L
            }
        } else {
            0L
        }
    }

    /**
     * Clear the cache for the specified server.
     * @param context Application context
     * @param serverId Unique server identifier
     */
    fun clearCache(context: Context, serverId: String) {
        try {
            val cacheFile = getCacheFile(context, serverId)
            val timestampFile = getTimestampFile(context, serverId)

            if (cacheFile.exists()) cacheFile.delete()
            if (timestampFile.exists()) timestampFile.delete()

            DebugLogger.i(TAG, "Cache cleared for server $serverId")
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to clear cache: ${e.message}")
        }
    }

    /**
     * Clear all cached object_info files.
     * @param context Application context
     */
    fun clearAllCaches(context: Context) {
        try {
            val filesDir = context.filesDir
            filesDir.listFiles()?.forEach { file ->
                if (file.name.startsWith(CACHE_PREFIX)) {
                    file.delete()
                }
            }
            DebugLogger.i(TAG, "All object info caches cleared")
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to clear all caches: ${e.message}")
        }
    }

    /**
     * Get the list of server IDs that have cached object_info.
     * @param context Application context
     * @return Set of server IDs with caches
     */
    fun getCachedServerIds(context: Context): Set<String> {
        return try {
            val filesDir = context.filesDir
            filesDir.listFiles()
                ?.filter { it.name.startsWith(CACHE_PREFIX) && it.name.endsWith(CACHE_SUFFIX) }
                ?.map { it.name.removePrefix(CACHE_PREFIX).removeSuffix(CACHE_SUFFIX) }
                ?.toSet()
                ?: emptySet()
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to get cached server IDs: ${e.message}")
            emptySet()
        }
    }

    /**
     * gzip 内容的原子写（照抄同包 [GalleryMetadataCache] 的 writeTextAtomically 模式）。
     *
     * 修复前直接写目标文件：先截断再写，进程在中间被杀（或写到一半 ENOSPC）就会留下
     * 半截 gzip —— 下次启动解析失败，离线模式整份不可用。现在写 `.tmp` → fsync →
     * 原子改名覆盖目标：任何时刻目标文件要么是旧的完整内容、要么是新的完整内容。
     *
     * 注意 fsync 时机：GZIPOutputStream.close() 会连底层流一起关掉，先 close 再 sync
     * 就没有机会了 —— 这里手动 finish()（写出 gzip trailer、底层流保持打开）后
     * fsync，再走 close 收尾。
     */
    private fun writeGzipAtomically(cacheFile: File, json: JSONObject) {
        val tmpFile = File(cacheFile.parentFile, cacheFile.name + TMP_SUFFIX)
        FileOutputStream(tmpFile).use { fos ->
            val gzip = GZIPOutputStream(fos)
            gzip.write(json.toString().toByteArray(Charsets.UTF_8))
            gzip.finish()
            fos.flush()
            // fsync：把页缓存刷到磁盘，否则"rename 成功但数据还在缓存里"同样会丢
            fos.fd.sync()
            gzip.close()
        }
        try {
            Files.move(
                tmpFile.toPath(),
                cacheFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (e: Exception) {
            // 个别文件系统不支持 ATOMIC_MOVE（或目标被占用）：退化为"先删目标再改名"，
            // 至少不会留下半截文件；仍失败则整份拷贝兜底
            DebugLogger.w(TAG, "Atomic move failed for ${cacheFile.name}, falling back: ${e.message}")
            if (!tmpFile.renameTo(cacheFile)) {
                cacheFile.delete()
                if (!tmpFile.renameTo(cacheFile)) {
                    tmpFile.copyTo(cacheFile, overwrite = true)
                    tmpFile.delete()
                }
            }
        }
    }

    private fun getCacheFile(context: Context, serverId: String): File {
        return File(context.filesDir, "$CACHE_PREFIX$serverId$CACHE_SUFFIX")
    }

    private fun getTimestampFile(context: Context, serverId: String): File {
        return File(context.filesDir, "$CACHE_PREFIX$serverId$TIMESTAMP_SUFFIX")
    }
}
