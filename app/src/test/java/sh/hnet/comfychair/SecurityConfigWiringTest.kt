package sh.hnet.comfychair

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P2-12 / P2-13 的配置与接线契约。
 *
 * 这两条靠「配置对不对」生效，普通单测覆盖不到运行时，所以用源码/资源文本断言钉住：
 * - file_paths.xml 不得再有 `path="."`（整个 cacheDir/filesDir 当可共享根）；
 * - 全部 FileProvider URI 只能从两个专用目录产出（分享载荷 share/、更新包 update/）；
 * - 分享载荷只写 cacheDir/share/（ShareFileStore）；
 * - 明文闸门必须接在 ComfyUIClient 的统一 client 构造上；
 * - network_security_config 不得信任用户安装的 CA。
 */
class SecurityConfigWiringTest {

    private fun readSource(relative: String): String {
        // 单测工作目录是 app 模块根；仓库根目录下的文件（如 SECURITY.md）要往上找一层
        val candidates = listOf(File(relative), File("app/$relative"), File("../$relative"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到文件 $relative，尝试过：${candidates.map { it.absolutePath }}")
        return file.readText()
    }

    /**
     * 去掉 XML 注释后再断言：注释里会解释「原来的 path="." / 不要加 user 证书」，
     * 直接对全文断言会把说明文字误判成配置本身。
     */
    private fun readXmlWithoutComments(relative: String): String =
        readSource(relative).replace(Regex("<!--[\\s\\S]*?-->"), "")

    @Test
    fun `FileProvider 不再声明整个 cacheDir 与 filesDir 为可共享根`() {
        val xml = readXmlWithoutComments("src/main/res/xml/file_paths.xml")

        assertFalse(
            "file_paths.xml 不得再出现 path=\".\"（整个目录当可共享根）",
            xml.contains("path=\".\"")
        )
        assertFalse("file_paths.xml 不得再声明 files-path 根", xml.contains("<files-path"))
        assertTrue(
            "分享载荷目录 cacheDir/share/ 必须声明",
            xml.contains("<cache-path name=\"share\" path=\"share/\"")
        )
        assertTrue(
            "更新包目录 update/ 必须保留",
            xml.contains("<external-files-path name=\"update_files\" path=\"update/\"")
        )
    }

    @Test
    fun `FileProvider URI 只从专用目录产出`() {
        val root = File("src/main/java/sh/hnet/comfychair").let {
            if (it.isDirectory) it else File("app/src/main/java/sh/hnet/comfychair")
        }
        assertTrue("找不到源码目录：${root.absolutePath}", root.isDirectory)

        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("FileProvider.getUriForFile(") }
            .map { it.name }
            .toSet()

        // 只允许两处：分享目录封装（share/）与安装包（update/）
        assertTrue(
            "FileProvider URI 只能由 ShareFileStore 与 AppInstaller 产出，实际：$offenders",
            offenders == setOf("ShareFileStore.kt", "AppInstaller.kt")
        )
    }

    @Test
    fun `分享载荷统一写 cacheDir share 子目录`() {
        val share = readSource("src/main/java/sh/hnet/comfychair/util/ShareFileStore.kt")
        assertTrue("分享目录名必须是 share", share.contains("DIR_NAME = \"share\""))
        assertTrue("分享 URI 必须复用 AppInstaller 同款 authority",
            share.contains("${'"'}${'$'}{context.packageName}.fileprovider${'"'}"))
    }

    @Test
    fun `播放链路不再依赖 FileProvider`() {
        val mediaCache = readSource("src/main/java/sh/hnet/comfychair/cache/MediaCache.kt")
        val stateHolder = readSource("src/main/java/sh/hnet/comfychair/cache/MediaStateHolder.kt")
        assertFalse(
            "MediaCache 的播放 URI 不得再用 FileProvider（否则必须为 cacheDir 根声明可共享路径）",
            mediaCache.contains("FileProvider.getUriForFile(")
        )
        assertFalse(
            "MediaStateHolder 的播放 URI 不得再用 FileProvider",
            stateHolder.contains("FileProvider.getUriForFile(")
        )
    }

    @Test
    fun `明文闸门以网络拦截器接在 ComfyUIClient 的统一 client 构造上`() {
        val client = readSource("src/main/java/sh/hnet/comfychair/ComfyUIClient.kt")
        // P2-13-redirect：必须是网络拦截器。OkHttp 的 30x 跳转只重走「连接+网络拦截器」，
        // 应用拦截器对初始请求只执行一次，挂错层会让「302 到公网 http」绕过闸门。
        assertTrue(
            "secured() 必须以 addNetworkInterceptor 挂 CleartextGuardInterceptor",
            client.contains("builder.addNetworkInterceptor(CleartextGuardInterceptor)")
        )
        assertFalse(
            "不得改回应用拦截器 addInterceptor(CleartextGuardInterceptor)（看不到跳转后的请求）",
            client.contains("addInterceptor(CleartextGuardInterceptor)")
        )

        val interceptor = readSource("src/main/java/sh/hnet/comfychair/util/CleartextGuardInterceptor.kt")
        assertTrue(
            "拦截器必须按 NetworkPolicy 判定明文是否允许",
            interceptor.contains("NetworkPolicy.isCleartextAllowed(url.host)")
        )
        assertTrue(
            "只拦明文，不能拦 https",
            interceptor.contains("!url.isHttps")
        )
        assertTrue(
            "拦截器注释必须写明必须用网络拦截器注册（防止后人改回应用层）",
            interceptor.contains("addNetworkInterceptor")
        )
    }

    @Test
    fun `网络安全配置不信任用户安装的 CA`() {
        val xml = readXmlWithoutComments("src/main/res/xml/network_security_config.xml")
        assertFalse(
            "不得信任用户安装的 CA（MITM 风险）",
            xml.contains("<certificates src=\"user\"")
        )
        assertTrue(
            "必须显式只信任系统证书",
            xml.contains("<certificates src=\"system\"")
        )
    }

    @Test
    fun `平台层明文放行的残余风险必须写成已知边界`() {
        // F5：P2-13 的边界在 OkHttp 层，平台层仍允许明文（NSC 不支持 CIDR）。
        // 这条残余面必须写在安全说明里，且不能被静默删除，否则后人会误以为已全域收口。
        val doc = readSource("SECURITY.md")
        assertTrue(
            "SECURITY.md 必须明确平台层仍有 cleartextTrafficPermitted=\"true\"",
            doc.contains("cleartextTrafficPermitted=\"true\"")
        )
        assertTrue(
            "SECURITY.md 必须点名 WebView 等不在闸门内的链路",
            doc.contains("WebView")
        )
        assertTrue(
            "SECURITY.md 必须给出新增链路的正确做法",
            doc.contains("CleartextGuardInterceptor")
        )
        assertTrue(
            "SECURITY.md 必须说明更新包 sha256 的信任模型（本地只校验包名，签名由系统安装器校验）",
            doc.contains("签名认证由系统安装器在安装时校验")
        )
        assertFalse(
            "装前签名比对已移除，SECURITY.md 不得再宣称本地做「包名 + 签名」校验",
            doc.contains("认证性由包名 + 签名校验承担")
        )

        // 配置文件本体也应把读者指回安全说明
        val rawXml = readSource("src/main/res/xml/network_security_config.xml")
        assertTrue(
            "network_security_config.xml 必须指向 SECURITY.md 的残余风险说明",
            rawXml.contains("SECURITY.md")
        )
    }
}