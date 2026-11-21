package sh.hnet.comfychair.update

import android.app.Activity
import android.os.Bundle
import sh.hnet.comfychair.util.DebugLogger

/**
 * 通知栏「安装」的中转 Activity（P0-7 复核 R2-L1）。
 *
 * **为什么需要它**：通知的 PendingIntent 里携带的是 FileProvider 的 content URI，
 * 文件内容要等用户点击、系统安装器打开时才真正被读取。如果包名校验只发生在
 * **发通知时**（旧实现），那么「通知已发出、用户还没点」这段窗口里把
 * `update/update.apk` 换掉，安装器读到的就是未校验的文件（TOCTOU）。
 *
 * 把 PendingIntent 指向本 Activity 后，校验发生在**点击的这一刻**：调用
 * [AppInstaller.install] 内部会先走 [AppInstaller.isApkTrusted]（只校验包名 =
 * `sh.hnet.comfychair.plus`；签名由系统安装时校验），只有通过才会
 * 拉起系统安装器。此时校验与 `startActivity` 之间只剩一次函数调用，与弹窗按钮
 * （UpdateDialog → AppInstaller.install）完全同构，不再有额外窗口。
 *
 * **无界面**：清单位于 `@android:style/Theme.Translucent.NoTitleBar`，校验完立刻
 * `finish()`；校验失败时 [AppInstaller.install] 会自己弹 Toast 说明原因。
 */
class UpdateInstallActivity : Activity() {

    companion object {
        private const val TAG = "UpdateInstallActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            when (val result = AppInstaller.install(this)) {
                // 没有「安装未知应用」权限：与旧的通知行为一致，把用户送到授权页
                AppInstaller.InstallResult.NEED_UNKNOWN_SOURCE_PERMISSION ->
                    AppInstaller.openUnknownSourceSettings(this)
                else -> DebugLogger.i(TAG, "通知栏安装结果：$result")
            }
        } catch (e: Exception) {
            DebugLogger.w(TAG, "通知栏安装异常：${e.javaClass.simpleName} ${e.message}")
        } finally {
            finish()
        }
    }
}
