package sh.hnet.comfychair.update

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.hnet.comfychair.BuildConfig
import sh.hnet.comfychair.update.AppInstaller.ApkPackageCheck

/**
 * 装前包名校验的纯函数单测（P0-7；2026-11-20 去掉签名比对后）。
 *
 * 背景：v4.16.13 起小米对「读待安装 APK 的签名证书再与已装应用比对」这段逻辑报
 * 「【高风险】包含病毒：a.gray.BulimiaTGen.h」启发式误报。Android 安装 APK 时
 * 自己会校验签名一致性（不符直接 INSTALL_FAILED_UPDATE_INCOMPATIBLE），手动比对
 * 没有额外防护，已删除；**包名比对保留**——系统不拦包名不同的 APK（会装成另一个 App）。
 *
 * 这里钉住保留部分的判定：包名一致才通过，不一致 / 读不出都失败（fail-closed）。
 * 不依赖任何 Android 接口，也不需要 mock PackageManager（项目里没有 mockito）。
 */
class AppInstallerPackageCheckTest {

    @Test
    fun `包名与本应用 applicationId 一致则通过`() {
        assertEquals(
            ApkPackageCheck.OK,
            AppInstaller.checkApkPackageName(
                BuildConfig.APPLICATION_ID,
                BuildConfig.APPLICATION_ID
            )
        )
    }

    @Test
    fun `包名不一致则失败`() {
        assertEquals(
            ApkPackageCheck.MISMATCH,
            AppInstaller.checkApkPackageName("com.example.evil", BuildConfig.APPLICATION_ID)
        )
    }

    @Test
    fun `包名读不出来则失败`() {
        assertEquals(
            ApkPackageCheck.ARCHIVE_UNREADABLE,
            AppInstaller.checkApkPackageName(null, BuildConfig.APPLICATION_ID)
        )
    }

    @Test
    fun `空包名与全空白包名同样按读不出处理`() {
        assertEquals(
            ApkPackageCheck.ARCHIVE_UNREADABLE,
            AppInstaller.checkApkPackageName("", BuildConfig.APPLICATION_ID)
        )
        assertEquals(
            ApkPackageCheck.ARCHIVE_UNREADABLE,
            AppInstaller.checkApkPackageName("   ", BuildConfig.APPLICATION_ID)
        )
    }

    @Test
    fun `包名比较区分大小写且不去空格`() {
        assertEquals(
            ApkPackageCheck.MISMATCH,
            AppInstaller.checkApkPackageName(
                "sh.hnet.comfychair.PLUS",
                BuildConfig.APPLICATION_ID
            )
        )
        assertEquals(
            ApkPackageCheck.MISMATCH,
            AppInstaller.checkApkPackageName(
                " sh.hnet.comfychair.plus",
                BuildConfig.APPLICATION_ID
            )
        )
    }
}