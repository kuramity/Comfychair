package sh.hnet.comfychair.update

import java.io.File
import java.security.MessageDigest

/**
 * 冷启动恢复 update.apk 时的完整性判据（P2-11）。
 *
 * 背景：`restoreFromDisk` 原先只判断「文件存在且非空」就置 READY_TO_INSTALL ——
 * 冷启动时清单早已不在内存，代码根本没有可比的期望值，于是把「磁盘上有这个文件」
 * 当成了「这个文件可信」。update/ 在应用私有目录（外部 `getExternalFilesDir`，
 * 不可用时回退 `filesDir`，见 UpdateDownloadWorker.resolveUpdateDir；v4.20.x），
 * root 或被篡改的环境
 * 可以直接替换文件，重启后 App 就会把它当成官方安装包呈现给用户。
 *
 * 现在的约定：
 * - [UpdateDownloadWorker] 在 SHA-256 校验通过、原子就位后，把清单里的 sha256
 *   写进同目录的旁车文件（[UpdateDownloadWorker.SHA_FILE_NAME]）；
 * - 冷启动恢复时由本对象重新流式计算磁盘文件的 SHA-256，与旁车文件比对；
 * - 缺旁车（老版本下载的包）、不一致、读不出来，一律**不可信**，由调用方丢弃文件。
 *
 * 纯 Kotlin（只依赖 java.io / java.security），可在 JVM 单测里直接覆盖。
 */
object UpdateRestorePolicy {

    /** 恢复复验的结论 */
    enum class Verdict {
        /** 清单 sha256 与磁盘文件一致 → 可信，允许进入安装链路的「就绪」态 */
        VERIFIED,

        /** 没有可用的清单 sha256（旁车缺失/损坏）→ 无法复验，按不可信处理 */
        NO_EXPECTED_SHA,

        /** 磁盘文件的 SHA-256 与清单不一致 → 文件被替换或损坏 */
        MISMATCH
    }

    /**
     * 复验结论。
     *
     * @param expectedSha256 清单里的 sha256（旁车文件内容；大小写/空白不敏感）
     * @param actualSha256   磁盘文件实算的 sha256（[sha256Of] 的结果；读取失败为 null）
     */
    fun verify(expectedSha256: String?, actualSha256: String?): Verdict {
        // 复用下载链路的规范化：只有 64 位十六进制才算「有期望值」，
        // 空串/畸形值不能让校验直接放行（与 P0-7 的 fail-closed 口径一致）
        val expected = DownloadPolicy.normalizeSha256(expectedSha256) ?: return Verdict.NO_EXPECTED_SHA
        val actual = actualSha256?.trim()?.lowercase()
        if (actual.isNullOrEmpty()) return Verdict.MISMATCH
        return if (expected == actual) Verdict.VERIFIED else Verdict.MISMATCH
    }

    /**
     * 流式计算文件 SHA-256（64KB buffer，不把整包读进内存）。
     * 文件不存在 / 读取异常返回 null（调用方按不可信处理）。
     */
    fun sha256Of(file: File): String? = try {
        if (!file.isFile) {
            null
        } else {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    } catch (_: Exception) {
        null
    }

    // ---------------- R4：已下载包的复用 ----------------

    /** 本地已下载包的处理结论 */
    enum class LocalPackageVerdict {
        /** 可直接复用 → 状态置 READY_TO_INSTALL，不重新下载 */
        REUSE,

        /** 不可复用（校验不过 / 版本不比已安装的新 / 包名不符）→ 丢弃 */
        DISCARD
    }

    /**
     * 冷启动 / 重进 App 时，磁盘上的 `update.apk` 能不能直接当「待安装」用（R4）。
     *
     * 四个条件缺一不可：
     * 1. [shaVerdict] 为 [Verdict.VERIFIED]（与清单 sha256 一致）；
     * 2. 有落盘的 versionCode 元数据（老版本下载的包没有，无法证明「更新」，按不可信处理）；
     * 3. 该 versionCode **大于**当前已安装版本 —— 否则就是「已经装好了还在提示安装」，
     *    正是用户看到的「安装进程一直挂起」的一部分；
     * 4. 包名校验通过（[AppInstaller.isApkTrusted]，签名由系统安装时校验）。
     *
     * 纯函数：不碰文件、不碰 Android，单测直接覆盖每种组合。
     */
    fun decideLocalPackage(
        shaVerdict: Verdict,
        packageVersionCode: Int?,
        installedVersionCode: Int,
        packageTrusted: Boolean
    ): LocalPackageVerdict =
        if (shaVerdict == Verdict.VERIFIED &&
            packageVersionCode != null &&
            packageVersionCode > installedVersionCode &&
            packageTrusted
        ) {
            LocalPackageVerdict.REUSE
        } else {
            LocalPackageVerdict.DISCARD
        }

    /**
     * 冷启动后应该呈现的阶段（R2：进程重启后**不得**是「下载中」）。
     *
     * - 本地有可复用包 → [UpdatePhase.READY_TO_INSTALL]（只差一步安装）；
     * - 本进程内确实有正在跑的下载会话（Activity 重建，不是进程重启）→ 保留 DOWNLOADING；
     * - 其余（含「.part 存在」）→ [UpdatePhase.IDLE]。
     *
     * `partExists` 只作为语义说明参数：**残留的 .part 永远不能把状态恢复成下载中**，
     * 它只是下次用户手动点「下载更新」时断点续传的素材。
     */
    fun coldStartPhase(
        localPackageReusable: Boolean,
        downloadSessionActive: Boolean,
        @Suppress("UNUSED_PARAMETER") partExists: Boolean
    ): UpdatePhase = when {
        localPackageReusable -> UpdatePhase.READY_TO_INSTALL
        downloadSessionActive -> UpdatePhase.DOWNLOADING
        else -> UpdatePhase.IDLE
    }
}