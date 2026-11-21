package sh.hnet.comfychair.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import sh.hnet.comfychair.BuildConfig
import sh.hnet.comfychair.R
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
 *
 * v4.16.11（P0-7）：**装前校验包名**。SHA-256 只保证"下载的文件没被中间人换掉"，
 * 挡不住"有人直接把任意 APK 放进 update/update.apk 再触发安装"这条旁路
 * （update/ 位于外部私有目录，root 或被篡改的环境可达）。系统安装器不拦包名不同的
 * APK（会装成另一个 App），所以必须在拉起安装器之前自查包名。
 *
 * 签名比对已移除（国产引擎误报治理，2026-11-20）：不再读取待安装 APK 的签名证书，
 * 具体原因与"将来要恢复该怎么办"见 [isApkTrusted] 的注释。
 */
object AppInstaller {

    private const val TAG = "AppInstaller"

    /** 与 AndroidManifest 里 ${applicationId}.fileprovider 一致 */
    private const val AUTHORITIES_SUFFIX = ".fileprovider"

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
            // v4.18.0（派工单 §4.6）：安装失败必须给出明确原因。
            // 原先这里只写日志 —— 用户从通知栏点「安装」时界面一闪而过、什么都不发生，
            // 看起来就是「安装进程挂起」。
            Toast.makeText(context, R.string.update_error_install_missing, Toast.LENGTH_LONG).show()
            return InstallResult.FAILED
        }

        // P0-7：装前只自查包名（签名由系统安装时校验），不符直接拒绝并提示（不拉起安装器）
        if (!isApkTrusted(context, apk)) {
            DebugLogger.w(TAG, "APK 包名与当前应用不一致，已拒绝安装")
            Toast.makeText(
                context,
                R.string.update_error_install_invalid,
                Toast.LENGTH_LONG
            ).show()
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
            // v4.18.0（§4.6）：设备没有系统安装器时也要说清原因，不能静默失败
            Toast.makeText(
                context,
                R.string.update_error_install_unavailable,
                Toast.LENGTH_LONG
            ).show()
            InstallResult.FAILED
        }
    }

    /**
     * 校验待安装 APK 的包名（P0-7）。
     *
     * **这是安装链路的唯一闸门**：弹窗按钮（[install]）、通知栏「点击安装」
     * （[UpdateNotifier] 的 buildInstallIntent）以及冷启动恢复
     * （[UpdateRepository.restoreFromDisk]）都必须先调用本方法，
     * 否则后者就是绕过校验的旁路。
     *
     * 规则：包名必须等于本 App 的 applicationId（[BuildConfig.APPLICATION_ID]，
     * 即 `sh.hnet.comfychair.plus`）—— 防同名替换之外的任何换包。
     * 任何一步解析失败（APK 结构损坏、读不到包名）都按不通过处理（fail-closed）。
     *
     * ## 为什么不再比对签名证书（2026-11-20 移除）
     *
     * 这里原先用 `getPackageArchiveInfo(..., GET_SIGNING_CERTIFICATES)` 取出待装包的
     * 签名证书，再与「当前已安装自身」的当前/历史签名做 SHA-256 比对。删除原因：
     * 1. **系统本来就会校验**：Android 在安装 APK 时自己比对签名身份，不一致会直接
     *    以 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 拒绝升级。手动比对不带来任何额外防护，
     *    只是把同一条错误提示提前到拉起安装器之前。
     * 2. **它是国产安全引擎的高分静态特征**：`getPackageArchiveInfo` +
     *    `GET_SIGNING_CERTIFICATES` / `signingInfo` 的组合是 dropper、假安装器、
     *    包名劫持类样本的常见套路，实测会触发小米「包含病毒」启发式误报。
     *    移除后 App 不再具备「读他人 APK 签名」的特征。
     *
     * **包名比对必须保留**：系统不会拦包名不同的 APK（会静默装成另一个 App），
     * 这部分有实际防护价值。
     *
     * 若将来需要「装完确认到底装成功没有」，应改用
     * [android.content.pm.PackageInstaller.Session] 的提交回调结果判断，
     * **不要**回到「提前读待装包签名」这条老路。
     */
    internal fun isApkTrusted(context: Context, apk: File): Boolean {
        return try {
            // 第二个参数传 0：只要 packageName，不请求任何签名信息
            val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            val archivePackage = archive?.packageName
            when (checkApkPackageName(archivePackage, BuildConfig.APPLICATION_ID)) {
                ApkPackageCheck.OK -> true
                ApkPackageCheck.ARCHIVE_UNREADABLE -> {
                    DebugLogger.w(TAG, "读不出待安装 APK 的包名（结构损坏或非 APK），按不通过处理")
                    false
                }
                ApkPackageCheck.MISMATCH -> {
                    DebugLogger.w(
                        TAG,
                        "包名不匹配：archive=$archivePackage expected=${BuildConfig.APPLICATION_ID}"
                    )
                    false
                }
            }
        } catch (e: Exception) {
            // 解析失败（损坏/非 APK）一律不通过
            DebugLogger.w(TAG, "APK 校验异常：${e.javaClass.simpleName} ${e.message}")
            false
        }
    }

    /** 装前包名校验结论；区分「读不出」与「不一致」只为日志说清原因 */
    internal enum class ApkPackageCheck { OK, ARCHIVE_UNREADABLE, MISMATCH }

    /**
     * 纯函数：装前包名校验（不依赖 Android 接口，可直接单元测试）。
     *
     * @param archivePackageName `getPackageArchiveInfo` 读出的包名；
     *   null 或空白表示读不出，按不通过处理（fail-closed）
     * @param expectedAppId 本 App 的 applicationId
     */
    internal fun checkApkPackageName(
        archivePackageName: String?,
        expectedAppId: String
    ): ApkPackageCheck {
        if (archivePackageName.isNullOrBlank()) return ApkPackageCheck.ARCHIVE_UNREADABLE
        return if (archivePackageName == expectedAppId) {
            ApkPackageCheck.OK
        } else {
            ApkPackageCheck.MISMATCH
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
