package sh.hnet.comfychair.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 安装闸门接线契约（P0-7 及其复核 F1 / R2-L1）。
 *
 * 历史：装前包名校验原先只在 [AppInstaller.install] 里做，而通知栏
 * 「更新已就绪」的 PendingIntent 自己 `buildInstallIntent`（FileProvider + ACTION_VIEW）
 * 直接拉起系统安装器 —— 同一份 APK 换个入口就绕过校验（F1）。
 * 补上通知侧校验后仍有 TOCTOU：URI 在发通知时就固定，文件到点击时才被读取（R2-L1）。
 *
 * 2026-11-20：装前校验从「包名 + 签名证书比对」瘦身为「仅包名」——读待装 APK 签名
 * 是国产引擎的高分误报特征，且系统安装时会自己校验签名。这里同时钉住「签名读取
 * 不得加回来」，防止回归。
 *
 * 现在两道入口共用一条链，这里用源码文本断言把它钉住：
 * 1. UpdateNotifier 不自己构造 FileProvider 安装 URI，安装意图指向 UpdateInstallActivity；
 * 2. UpdateInstallActivity 在点击时调用 AppInstaller.install（即点击时校验）；
 * 3. AppInstaller.install 先校验再拉起安装器，且 isApkTrusted 对同 module 可见；
 * 4. 清单里该中转 Activity 必须存在且不导出（PendingIntent 由本应用自身启动即可）。
 */
class UpdateInstallGateWiringTest {

    private fun readSource(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到源文件 $relative，尝试过：${candidates.map { it.absolutePath }}")
        return file.readText()
    }

    /**
     * 去掉 Kotlin 注释后再断言。
     *
     * AppInstaller.kt 的 KDoc 里**正面提到** GET_SIGNING_CERTIFICATES / signingInfo
     * 是为了记录"为什么删"，不能因此判定代码里还在用；所以只检查去注释后的代码。
     */
    private fun codeOnly(source: String): String =
        source.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//[^\\n]*"), "")

    private val notifierPath = "src/main/java/sh/hnet/comfychair/update/UpdateNotifier.kt"
    private val installerPath = "src/main/java/sh/hnet/comfychair/update/AppInstaller.kt"
    private val trampolinePath = "src/main/java/sh/hnet/comfychair/update/UpdateInstallActivity.kt"

    @Test
    fun `通知栏不再自己构造安装 URI 而是走点击时校验的中转 Activity`() {
        val body = readSource(notifierPath)

        assertFalse(
            "UpdateNotifier 不得再自己构造 FileProvider 安装 URI（发通知时固定 URI 会留下 TOCTOU 窗口）",
            body.contains("FileProvider.getUriForFile(")
        )
        assertFalse(
            "UpdateNotifier 不得再直接构造 APK 的 ACTION_VIEW 意图",
            body.contains("application/vnd.android.package-archive")
        )
        assertTrue(
            "通知的安装意图必须指向 UpdateInstallActivity（点击时才校验）",
            body.contains("Intent(context, UpdateInstallActivity::class.java)")
        )
    }

    @Test
    fun `中转 Activity 在点击时调用 AppInstaller`() {
        val body = readSource(trampolinePath)
        assertTrue(
            "UpdateInstallActivity 必须调用 AppInstaller.install（其中会先跑 isApkTrusted）",
            body.contains("AppInstaller.install(this)")
        )
    }

    @Test
    fun `弹窗安装路径同样过闸门且校验函数对同 module 可见`() {
        val body = readSource(installerPath)

        val gate = body.indexOf("if (!isApkTrusted(context, apk))")
        val provider = body.indexOf("FileProvider.getUriForFile(")
        assertTrue("AppInstaller.install 必须先校验", gate >= 0)
        assertTrue(
            "必须在校验通过后才生成 FileProvider URI（gate=$gate, provider=$provider）",
            provider >= 0 && gate < provider
        )
        assertTrue(
            "isApkTrusted 必须保持 internal（退回 private 会让通知侧无法复用同一实现）",
            body.contains("internal fun isApkTrusted(")
        )
    }

    @Test
    fun `装前校验只读包名，不读 APK 签名（国产引擎误报特征，勿加回）`() {
        val code = codeOnly(readSource(installerPath))

        assertFalse(
            "AppInstaller 不得再请求签名证书：getPackageArchiveInfo + GET_SIGNING_CERTIFICATES " +
                "是国产引擎的高分误报特征，且系统安装时会自己校验签名一致性",
            code.contains("GET_SIGNING_CERTIFICATES") || code.contains("GET_SIGNATURES")
        )
        assertFalse(
            "AppInstaller 不得再读取 signingInfo / 计算签名摘要（签名一致性由系统安装器保证）",
            code.contains("signingInfo") || code.contains("MessageDigest")
        )
        assertTrue(
            "包名比对必须保留（系统不拦包名不同的 APK）：getPackageArchiveInfo 仍要以仅取包名的 flags 调用",
            code.contains("getPackageArchiveInfo(apk.absolutePath, 0)")
        )
    }

    @Test
    fun `中转 Activity 已注册且不导出`() {
        val manifest = readSource("src/main/AndroidManifest.xml")
        assertTrue(
            "AndroidManifest 缺少 .update.UpdateInstallActivity 声明（否则通知点击会 ActivityNotFound）",
            manifest.contains("android:name=\".update.UpdateInstallActivity\"")
        )
        // 取出该 activity 标签并确认 exported=false
        val block = manifest.substringAfter("android:name=\".update.UpdateInstallActivity\"")
            .substringBefore("/>")
        assertTrue("中转 Activity 必须 exported=false", block.contains("android:exported=\"false\""))
    }
}
