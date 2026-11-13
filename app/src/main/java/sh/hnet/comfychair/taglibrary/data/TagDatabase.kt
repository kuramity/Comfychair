package sh.hnet.comfychair.taglibrary.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

/**
 * 标签库数据库管理（只读）。
 *
 * 数据库文件随 APK 打包在 `assets/databases/danbooru_tags.db.gz`（gzip 压缩，
 * 约 56MB，解压后约 103MB、170.7 万行），首次启动或版本升级时解压到
 * `context.getDatabasePath()` 下的应用私有目录。
 *
 * 设计要点：
 * - **只读打开**：`OPEN_READONLY`，杜绝端上误写（需求 8.2）
 * - **版本管理**：assets 内的版本号与本地记录比对，不一致才重新解压（需求 6.1.3）
 * - **原子落盘**：先写 .tmp 再 rename，解压中断不会留下半截文件
 * - **不用 Room/SQLiteOpenHelper**：预制库无需 schema 迁移，且 SQLiteOpenHelper
 *   默认以可写方式打开，与"只读"要求冲突；直接用 openDatabase 更贴合场景
 */
object TagDatabase {

    private const val TAG = "TagDatabase"

    /** 未压缩形态的 asset 路径（aapt2 打包时会自动解开 .gz，优先按此读取）。 */
    private const val ASSET_DB = "databases/danbooru_tags.db"

    /** 源码仓库中的压缩形态（部分构建配置下不会被自动解开，作为回退）。 */
    private const val ASSET_DB_GZ = "databases/danbooru_tags.db.gz"
    private const val ASSET_VERSION = "databases/danbooru_tags.version"
    private const val DB_NAME = "danbooru_tags.db"
    private const val PREFS_NAME = "tag_library_prefs"
    private const val KEY_DB_VERSION = "db_version"

    /** 解压缓冲区大小（64KB，平衡内存与速度）。 */
    private const val BUFFER_SIZE = 64 * 1024

    @Volatile
    private var database: SQLiteDatabase? = null

    /**
     * 确保数据库就绪并返回只读实例。
     *
     * 该方法应在 IO 线程调用（首次解压约需 2-4 秒）。
     *
     * @return 成功返回数据库实例；失败返回异常（供上层展示重试提示）
     */
    @Synchronized
    fun open(context: Context): Result<SQLiteDatabase> {
        database?.let { if (it.isOpen) return Result.success(it) }

        return try {
            val dbFile = context.getDatabasePath(DB_NAME)
            val assetVersion = readAssetVersion(context)
            val localVersion = context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_DB_VERSION, null)

            val needExtract = !dbFile.exists() || assetVersion != localVersion
            if (needExtract) {
                Log.i(TAG, "解压标签库: asset=$assetVersion local=$localVersion")
                extractDatabase(context, dbFile)
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_DB_VERSION, assetVersion)
                    .apply()
            }

            val db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
            database = db
            Result.success(db)
        } catch (e: Exception) {
            Log.e(TAG, "标签库打开失败", e)
            Result.failure(e)
        }
    }

    /** 关闭数据库（应用退出或需要重新解压时调用）。 */
    @Synchronized
    fun close() {
        database?.let { if (it.isOpen) it.close() }
        database = null
    }

    /** 数据库文件是否已解压完成。 */
    fun isExtracted(context: Context): Boolean = context.getDatabasePath(DB_NAME).exists()

    /** 读取 assets 中的数据库版本号（读取失败时用时间戳兜底，确保会触发一次解压）。 */
    private fun readAssetVersion(context: Context): String {
        return try {
            context.assets.open(ASSET_VERSION).use { input ->
                input.readBytes().decodeToString().trim()
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取 assets 版本号失败，按未知版本处理", e)
            "unknown"
        }
    }

    /**
     * 从 assets 解压/复制数据库到应用私有目录（原子落盘）。
     *
     * 两种来源都兼容：
     * - AGP/aapt2 打包时会自动解开 assets 中的 .gz（APK 本身即压缩容器，实测产物为
     *   未压缩的 `danbooru_tags.db`），此时直接复制即可，无需再解 gzip；
     * - 若构建配置保留了 .gz 原样，则回退到 GZIPInputStream 解压。
     */
    private fun extractDatabase(context: Context, target: File) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "$DB_NAME.tmp")
        try {
            val plainAsset = runCatching { context.assets.open(ASSET_DB) }.getOrNull()
            if (plainAsset != null) {
                plainAsset.use { input ->
                    FileOutputStream(tmp).use { out ->
                        input.copyTo(out, BUFFER_SIZE)
                        out.flush()
                        out.fd.sync()
                    }
                }
            } else {
                context.assets.open(ASSET_DB_GZ).use { assetInput ->
                    GZIPInputStream(assetInput, BUFFER_SIZE).use { gzInput ->
                        FileOutputStream(tmp).use { out ->
                            gzInput.copyTo(out, BUFFER_SIZE)
                            out.flush()
                            out.fd.sync()
                        }
                    }
                }
            }
            if (target.exists() && !target.delete()) {
                Log.w(TAG, "旧数据库删除失败，尝试直接覆盖")
            }
            if (!tmp.renameTo(target)) {
                // rename 失败时回退为复制，保证可用性
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete()  // 清理半截文件
            throw e
        }
    }
}
