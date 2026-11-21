package sh.hnet.comfychair

import android.app.Application
import sh.hnet.comfychair.util.CrashLogger

/**
 * 全局 Application（AndroidManifest 的 `<application android:name=".ComfyChairApp">`）。
 *
 * 目前只做一件事：**最先**安装崩溃日志处理器。它必须早于任何业务初始化 ——
 * 启动期崩溃同样要被记录下来，晚一步就可能漏掉最关键的现场。
 */
class ComfyChairApp : Application() {

    override fun onCreate() {
        // 崩溃捕获必须最先安装（早于 super.onCreate 之后的任何初始化）
        CrashLogger.install(this)
        super.onCreate()
    }
}