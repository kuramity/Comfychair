package sh.hnet.comfychair.util

import android.content.Context
import android.os.Build
import android.util.Log
import sh.hnet.comfychair.BuildConfig
import sh.hnet.comfychair.R
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 全局未捕获异常落盘（v4.16.13 之后 P0 崩溃的观测入口）。
 *
 * 背景：App 此前**完全没有**崩溃捕获（全仓无 `setDefaultUncaughtExceptionHandler`），
 * 用户反馈的「删空图库后再进图库闪退」在线上没有任何堆栈可查。本类只做一件事：
 *
 * 1. 安装一个全局 handler，崩溃时把现场写成文件（时间、异常、完整堆栈、线程、版本、设备）；
 * 2. **无条件**把异常交回给系统原来的 handler，不吞、不改系统默认行为
 *    （系统的崩溃弹窗/日志/后续行为与未安装时完全一致）；
 * 3. 只保留最近 [MAX_CRASH_FILES] 份，避免长期使用后堆满应用私有目录。
 *
 * 目录：`filesDir/crash/`（应用私有，卸载即清）。
 * 文件名：`crash-yyyyMMdd-HHmmss.txt`；同一秒内多次崩溃时追加 `-1`、`-2` 后缀，绝不覆盖。
 *
 * 写入必须原子（与 `GalleryMetadataCache.writeTextAtomically` 同款思路）：
 * 先写 `.tmp` → `flush()` + `fd.sync()` → `renameTo`。崩溃现场最怕的就是
 * "写了一半的日志"——下次排查时它会把真正的堆栈截断。
 *
 * 本类内部的任何异常都必须自己吞掉：崩溃处理里再抛异常会掩盖原始崩溃。
 */
object CrashLogger {

    private const val TAG = "CrashLogger"

    /** 崩溃日志目录名（直接位于 `context.filesDir` 下） */
    internal const val CRASH_DIR_NAME = "crash"

    /** 最多保留的崩溃日志份数 */
    internal const val MAX_CRASH_FILES = 10

    private const val FILE_PREFIX = "crash-"
    private const val FILE_SUFFIX = ".txt"
    private const val TMP_SUFFIX = ".tmp"

    /** 防止重复安装（Application.onCreate 只会调一次，但保留幂等更安全） */
    private val installed = AtomicBoolean(false)

    /**
     * 安装全局崩溃处理器。
     *
     * 必须在 `Application.onCreate()` 的最前面调用 —— 越晚安装，越可能漏掉启动期崩溃。
     * 已经安装过（同进程第二次调用）时直接返回，不会把旧 handler 套成链。
     */
    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return

        val appContext = context.applicationContext ?: context
        // 先取出系统（或既有框架）的 handler，崩了之后必须原样交还
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        try {
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    writeCrashReport(appContext, thread, throwable)
                } catch (ignored: Throwable) {
                    // 崩溃处理里绝不允许再抛：写盘失败只记一条系统日志
                    try {
                        Log.e(TAG, "写入崩溃日志失败", ignored)
                    } catch (_: Throwable) {
                        // Log 本身也可能不可用（极早期崩溃），彻底放弃
                    }
                }
                // 无条件交回系统默认行为（弹窗、打印、进程终止）。
                // 外面再包一层 try/catch：极端情况下系统 handler 自身也可能抛，
                // 绝不能让"处理崩溃时再崩一次"掩盖原始堆栈。
                try {
                    previous?.uncaughtException(thread, throwable)
                } catch (_: Throwable) {
                    // 系统 handler 抛出时只能忽略（原始异常已经落盘）
                }
            }
        } catch (t: Throwable) {
            // setDefaultUncaughtExceptionHandler 在极端环境下可能抛 SecurityException
            try {
                Log.e(TAG, "安装崩溃处理器失败", t)
            } catch (_: Throwable) {
                // 忽略
            }
        }
    }

    /**
     * 列出当前保存的崩溃日志文件，按修改时间**倒序**（最新在前）。
     *
     * 只返回本类生成的 `.txt`（`.tmp` 半成品、其它文件一律不算）。
     * 任何 IO 异常都返回空列表，绝不向 UI 抛。
     */
    fun listCrashFiles(context: Context): List<File> {
        return try {
            val dir = crashDir(context)
            dir.listFiles()
                ?.filter { it.isFile && isCrashFileName(it.name) }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * 把所有崩溃日志拼成一份文本（最新在前），供设置页"复制"使用。
     *
     * 每份文件带文件名 / 大小 / 修改时间标题与分隔线；单份读取失败只写一行提示，
     * 不影响其它文件。没有任何日志时返回一句说明文案。
     */
    fun exportAll(context: Context): String {
        val files = listCrashFiles(context)
        // 空态文案走已翻译资源：这是用户在设置页点「复制」后可能看到的明文
        if (files.isEmpty()) return context.getString(R.string.msg_debug_log_empty)

        val builder = StringBuilder()
        for ((index, file) in files.withIndex()) {
            if (index > 0) {
                builder.appendLine()
                builder.appendLine(SEPARATOR)
            }
            // 正文是**开发者排查用的导出文件**，字段名与 DebugLogger.exportToString()
            // 的既有英文格式保持一致（非中文用户复制出来也不会是一屏中文）。
            // 面向用户的 Compose 标签仍然全部走 R.string，见 ApplicationSettingsScreen。
            builder.appendLine("File: ${file.name}")
            builder.appendLine("Size: ${file.length()} bytes")
            builder.appendLine("Modified: ${formatTimestamp(file.lastModified())}")
            builder.appendLine()
            try {
                builder.append(file.readText())
            } catch (t: Throwable) {
                builder.appendLine("Read failed: ${t.message ?: "unknown"}")
            }
            if (!builder.endsWith("\n")) builder.appendLine()
        }
        return builder.toString()
    }

    // ============================================================
    // 内部实现
    // ============================================================

    private const val SEPARATOR = "============================================================"

    private fun crashDir(context: Context): File =
        File(context.filesDir, CRASH_DIR_NAME)

    private fun isCrashFileName(name: String): Boolean =
        name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX)

    /**
     * 写一份崩溃日志。
     *
     * 返回写入的文件（失败返回 null）。所有异常由调用方（handler 里的 try/catch）兜住，
     * 这里不额外吞异常，方便日志里留一条失败记录。
     */
    private fun writeCrashReport(
        context: Context,
        thread: Thread,
        throwable: Throwable
    ): File? {
        val dir = crashDir(context)
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "无法创建崩溃日志目录: ${dir.absolutePath}")
            return null
        }

        val timestamp = System.currentTimeMillis()
        val target = allocateCrashFile(dir, timestamp)
        val tmp = File(dir, target.name + TMP_SUFFIX)
        val content = buildCrashContent(context, thread, throwable, timestamp)

        // 原子写：先落 .tmp 并 fsync，再改名覆盖。任何时刻目标文件要么完整、要么不存在
        FileOutputStream(tmp).use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            // 个别文件系统 renameTo 失败：删目标后重试，仍失败则退化为整份拷贝
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }

        // 写入成功后裁剪，只留最近 MAX_CRASH_FILES 份
        trimCrashFiles(dir)
        return target
    }

    /**
     * 分配一个未占用的文件名：同一秒内多次崩溃时追加序号，绝不覆盖已有日志。
     */
    private fun allocateCrashFile(dir: File, timestamp: Long): File {
        var index = 0
        while (index < 1000) {
            val candidate = File(dir, crashFileName(timestamp, index))
            if (!candidate.exists()) return candidate
            index++
        }
        // 极端情况下同秒崩溃上千次：退化为带纳秒时间戳的名字，保证不覆盖
        return File(dir, "crash-$timestamp.txt")
    }

    private fun buildCrashContent(
        context: Context,
        thread: Thread,
        throwable: Throwable,
        timestamp: Long
    ): String {
        val versionName = try {
            BuildConfig.VERSION_NAME
        } catch (_: Throwable) {
            "unknown"
        }
        val versionCode = try {
            BuildConfig.VERSION_CODE.toString()
        } catch (_: Throwable) {
            "unknown"
        }
        return buildString {
            // 与 DebugLogger.exportToString() 同口径：崩溃现场是**开发者产物**，
            // 字段名用稳定的英文，避免非中文用户按「复制」后拿到整页中文。
            appendLine("=== ComfyChair Crash Report ===")
            appendLine("Time: ${formatTimestamp(timestamp)}")
            appendLine("Exception: ${throwable.javaClass.name}")
            appendLine("Message: ${throwable.message ?: "(none)"}")
            appendLine("Thread: ${thread.name}")
            appendLine("App version: $versionName ($versionCode)")
            appendLine("Android SDK: ${Build.VERSION.SDK_INT}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Package: ${context.packageName}")
            appendLine()
            appendLine("--- Stack trace ---")
            appendLine(Log.getStackTraceString(throwable))
        }
    }

    /** 按 lastModified 从旧到新删除超额部分；单份删除失败忽略（下次再删） */
    private fun trimCrashFiles(dir: File) {
        try {
            val files = dir.listFiles()
                ?.filter { it.isFile && isCrashFileName(it.name) }
                ?: return
            for (file in crashFilesToDelete(files)) {
                try {
                    file.delete()
                } catch (_: Throwable) {
                    // 忽略：文件被占用时下次启动再清理
                }
            }
        } catch (_: Throwable) {
            // 裁剪失败不影响本次崩溃日志已落盘
        }
    }

    private fun formatTimestamp(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(millis))

    // ============================================================
    // 可独立单测的纯逻辑（不依赖 Android / 文件系统）
    // ============================================================

    /**
     * 崩溃日志文件名：`crash-yyyyMMdd-HHmmss.txt`。
     *
     * @param timestampMillis 崩溃时间（本地时区格式化）
     * @param collisionIndex  同一秒内的第几次碰撞：0 = 无后缀，1 = `-1`，2 = `-2`…
     */
    internal fun crashFileName(timestampMillis: Long, collisionIndex: Int = 0): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(timestampMillis))
        return if (collisionIndex <= 0) {
            "$FILE_PREFIX$stamp$FILE_SUFFIX"
        } else {
            "$FILE_PREFIX$stamp-$collisionIndex$FILE_SUFFIX"
        }
    }

    /**
     * 裁剪逻辑（纯函数）：给定现有日志，返回**应该删除**的那些文件。
     *
     * 排序键是 `lastModified()`（旧的先删），不依赖文件名字典序 —— 文件名只到秒，
     * 同一秒的碰撞序号与真实时序无关。数量不超过 [maxFiles] 时返回空列表。
     * 函数本身不删除任何文件，删除动作由调用方执行，便于单测钉住边界。
     */
    internal fun crashFilesToDelete(
        files: List<File>,
        maxFiles: Int = MAX_CRASH_FILES
    ): List<File> {
        val limit = maxFiles.coerceAtLeast(0)
        if (files.size <= limit) return emptyList()
        val overflow = files.size - limit
        return files.sortedBy { it.lastModified() }.take(overflow)
    }
}