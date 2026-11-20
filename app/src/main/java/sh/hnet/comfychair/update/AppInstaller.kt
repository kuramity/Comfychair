package sh.hnet.comfychair.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import sh.hnet.comfychair.BuildConfig
import sh.hnet.comfychair.R
import sh.hnet.comfychair.util.DebugLogger
import java.io.File
import java.security.MessageDigest

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
 * v4.16.11（P0-7）：**装前校验包名与签名**。SHA-256 只保证"下载的文件没被中间人换掉"，
 * 挡不住"有人直接把任意 APK 放进 update/update.apk 再触发安装"这条旁路
 * （update/ 位于外部私有目录，root 或被篡改的环境可达）。系统安装器不校验包名/签名
 * 是否与当前 App 一致，所以必须在拉起安装器之前自查。
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
            return InstallResult.FAILED
        }

        // P0-7：装前自查包名与签名，不符直接拒绝并提示（不拉起安装器）
        if (!isApkTrusted(context, apk)) {
            DebugLogger.w(TAG, "APK 包名或签名与当前应用不一致，已拒绝安装")
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
            InstallResult.FAILED
        }
    }

    /**
     * 校验待安装 APK 的包名与签名（P0-7）。
     *
     * **这是安装链路的唯一闸门**：弹窗按钮（[install]）与通知栏「点击安装」
     * （[UpdateNotifier] 的 buildInstallIntent）都必须先调用本方法，
     * 否则后者就是绕过校验的旁路。
     *
     * 规则：
     * 1. 包名必须等于本 App 的 applicationId（[BuildConfig.APPLICATION_ID]，
     *    即 `sh.hnet.comfychair.plus`）—— 防同名替换之外的任何换包；
     * 2. 待安装包的每个签名证书 SHA-256 必须出现在「当前已安装自身」的
     *    当前签名或历史签名里 —— 只有同一签名身份才能覆盖升级，仿冒包会被拒绝。
     *
     * 任何一步解析失败（APK 结构损坏、取不到签名信息）都按不通过处理（fail-closed）。
     */
    internal fun isApkTrusted(context: Context, apk: File): Boolean {
        val pm = context.packageManager
        return try {
            val archive = pm.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES
            ) ?: return false

            if (archive.packageName != BuildConfig.APPLICATION_ID) {
                DebugLogger.w(
                    TAG,
                    "包名不匹配：archive=${archive.packageName} expected=${BuildConfig.APPLICATION_ID}"
                )
                return false
            }

            val apkDigests = signerDigests(archive.signingInfo)
            if (apkDigests.isEmpty()) {
                DebugLogger.w(TAG, "待安装 APK 取不到签名信息")
                return false
            }

            val installed = pm.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
            val installedInfo = installed?.signingInfo
            // 当前签名 + 历史签名：兼容密钥轮换（rotation）后的旧签名 APK
            val allowed = signerDigests(installedInfo) + historyDigests(installedInfo)
            if (allowed.isEmpty()) {
                DebugLogger.w(TAG, "取不到本机已安装包的签名信息")
                return false
            }

            val trusted = apkDigests.all { it in allowed }
            if (!trusted) {
                DebugLogger.w(TAG, "签名不匹配：apk=${apkDigests.size} 个证书均不在本机签名链内")
            }
            trusted
        } catch (e: Exception) {
            // 解析失败（损坏/非 APK/签名块异常）一律不通过
            DebugLogger.w(TAG, "APK 校验异常：${e.javaClass.simpleName} ${e.message}")
            false
        }
    }

    /** 当前签名者证书的 SHA-256（小写十六进制） */
    private fun signerDigests(info: SigningInfo?): Set<String> {
        if (info == null) return emptySet()
        return info.apkContentsSigners
            ?.mapNotNull { sha256Of(it.toByteArray()) }
            ?.toSet()
            .orEmpty()
    }

    /** 历史签名（密钥轮换前的旧证书）；APK 本身没有历史签名，只有已安装包才有 */
    private fun historyDigests(info: SigningInfo?): Set<String> {
        if (info == null) return emptySet()
        return info.signingCertificateHistory
            ?.mapNotNull { sha256Of(it.toByteArray()) }
            ?.toSet()
            .orEmpty()
    }

    private fun sha256Of(bytes: ByteArray): String? = try {
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        DebugLogger.w(TAG, "证书摘要计算失败：${e.message}")
        null
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
