package sh.hnet.comfychair.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import sh.hnet.comfychair.util.DebugLogger
import java.io.File

/**
 * 安装触发器（v4.6.0）—— ACTION_VIEW + FileProvider 拉起系统安装器。
 *
 * 硬性约束 17/18/19 的落地：
 * - **前台直接安装**：App 在前台时构造 `ACTION_VIEW` + `FLAG_GRANT_READ_URI_PERMISSION`
 *   直接触发。
 * - **未知来源引导**：Android 8+ 先查 [canRequestPackageInstalls]，没有权限时跳转
 *   设置页让用户勾选（引导而非静默安装）。
 * - **禁止静默安装**：全程只用系统安装器 UI，不碰任何无障碍/反射安装通道。
 * - 文件通过 [FileProvider]（authorities 与项目既有的 image 分享共用，
 *   file_paths.xml 里补 external-files-path 的 update 子目录）。
 */
object AppInstaller {

    private const val TAG = "AppInstaller"

    /** 与 AndroidManifest 里 ${applicationId}.fileprovider 一致 */
    private const val AUTHORITIES_SUFFIX = ".fileprovider"

    /** update.apk 在 FileProvider 里的共享路径名（对应 file_paths.xml 的 external-files-path） */
    private const val PROVIDER_PATH = "update_files"

    /** 这个 APK 是否已就绪可安装（文件存在且非空） */
    fun isApkReady(context: Context): Boolean {
        val f = UpdateDownloadWorker.finalFile(context)
        return f.isFile && f.length() > 0
    }

    /**
     * 触发安装。返回 false 表示被权限/环境挡住（调用方按需引导）。
     *
     * @return INSTALL_STARTED / NEED_UNKNOWN_SOURCE_PERMISSION / FAILED
     */
    fun install(context: Context): InstallResult {
        val apk = UpdateDownloadWorker.finalFile(context)
        if (!apk.isFile || apk.length() == 0L) {
            DebugLogger.w(TAG, "install called but apk missing")
            return InstallResult.FAILED
        }

        // Android 8+ 未知来源安装权限（引导用户去开，不静默绕过）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            return InstallResult.NEED_UNKNOWN_SOURCE_PERMISSION
        }

        val uri: Uri = FileProvider.getUriForFile(
            context,
            context.packageName + AUTHORITIES_SUFFIX,
            apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            InstallResult.INSTALL_STARTED
        } catch (e: ActivityNotFoundException) {
            DebugLogger.w(TAG, "No installer activity: ${e.message}")
            InstallResult.FAILED
        }
    }

    /** 跳转到"允许安装未知应用"的系统设置页（引导，非自动授权） */
    fun openUnknownSourceSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
                .onFailure { DebugLogger.w(TAG, "unknown-source settings failed: ${it.message}") }
        }
    }

    enum class InstallResult { INSTALL_STARTED, NEED_UNKNOWN_SOURCE_PERMISSION, FAILED }
}
