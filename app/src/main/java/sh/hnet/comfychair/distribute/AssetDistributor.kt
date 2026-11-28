package sh.hnet.comfychair.distribute

import org.json.JSONObject
import sh.hnet.comfychair.model.ModelIntroSync
import sh.hnet.comfychair.update.DownloadPolicy
import sh.hnet.comfychair.util.AtomicFileWriter
import sh.hnet.comfychair.util.SingleFlight
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 资产下发管线（B5）：把服务器上的预置工作流 / 技能规则 / 占位符规则表同步到本地覆盖层。
 *
 * ## 管线
 *
 * 拉 → 比 → 验 → 落盘，每一步都有独立闸门：
 * 1. **拉**：manifest 与资产全部走同源 `/view`（`fetchRawBytes`，subfolder 固定 comfychair），
 *    不引入任何新下载面；网络拉取以 [fetch] 参数注入（照 ModelIntroSyncRunner 的 fetch/apply
 *    分离），本类不碰 Android，全部决策可 JVM 单测；
 * 2. **比**：版本只进不退——远端 `generated_at` 优先（字典序即时间序）、退化比 version，
 *    复用 [ModelIntroSync.shouldApply]（模型介绍热更新同款语义，避免两份漂移）；
 *    远端旧/相同 → 整体 SKIPPED，本地一个字节都不动（远端更旧是真实场景：APK 内置资产
 *    随新版本发布后就会比服务器上那份新）；
 * 3. **验**：每条资产 SHA-256 校验（缺失/不匹配即拒，DownloadPolicy 同款口径）+ 内容准入
 *    （[AssetAdmission]，坏内容即使 SHA 对也绝不落盘）；
 * 4. **落盘**：`filesDir/overlay/<kind.dirName>/<filename>` 原子写（AtomicFileWriter 同款
 *    .tmp → fsync → rename），`filesDir/overlay/` 不在任何清理白名单里（fail-closed 天然安全）。
 *
 * ## manifest_state 的写盘时机（最终一致）
 *
 * **全部条目成功**才写 `overlay/manifest_state.json`；任一条目失败 → 不写（下次连接重试
 * 整份 manifest，服务器修复后自动收敛）。已落盘的合法资产保留（它们本身有效，重试只补
 * 失败的那部分）。
 *
 * ## 副作用
 *
 * 本类不直接触发业务重载——落盘了哪些 kind 通过 [SyncResult.writtenKinds] 交给 onComplete
 * 的调用方（接线层决定：预置流 → WorkflowManager 重装配；规则表 → RuleTable 重载；
 * SKILL_MD 无需主动副作用，下次读时生效）。
 *
 * ## 并发
 *
 * 多条链路（连接成功 / 手点刷新）可能同时触发，[SingleFlight] 跳过式闸门防重入；成功、
 * 失败、异常三条路径都复位闸门（与 ModelIntroSyncRunner 同款纪律——发起阶段同步抛出时
 * 刻意不在 finally 里释放，那会在异步回调到来之前提前放闸）。
 */
class AssetDistributor(
    private val gate: SingleFlight = SingleFlight(),
    private val logInfo: (String) -> Unit = {},
    private val logWarn: (String) -> Unit = {},
) {

    enum class Outcome { COMPLETED, SKIPPED, FAILED }

    /** 一次同步的终态：落盘了哪些 kind（COMPLETED 才可能非空）。 */
    data class SyncResult(val outcome: Outcome, val writtenKinds: Set<AssetKind>)

    /**
     * 触发一次资产同步。重复触发（已有任务在飞）直接跳过，不排队、不抛异常。
     *
     * @param overlayRoot 本地覆盖层根目录（`filesDir/overlay`）
     * @param fetch 发起同源拉取：拿到字节时以字节回调；任何失败（网络/404/超限）以 null 回调
     * @param onComplete 同步终态回调（在最后一次网络回调的线程上执行；闸门释放后调用）
     */
    fun sync(
        overlayRoot: File,
        fetch: (filename: String, callback: (ByteArray?) -> Unit) -> Unit,
        onComplete: (SyncResult) -> Unit = {},
    ) {
        if (!gate.tryEnter()) {
            logInfo("资产下发同步已在进行中，跳过本次触发")
            return
        }

        // 幂等释放：与 ModelIntroSyncRunner 同款——真正的复位只发生一次
        val released = AtomicBoolean(false)
        fun release() {
            if (released.compareAndSet(false, true)) gate.exit()
        }
        fun finish(result: SyncResult) {
            release()
            try {
                onComplete(result)
            } catch (e: Exception) {
                logWarn("资产同步完成回调异常（忽略）：${e.message}")
            }
        }

        try {
            fetch(MANIFEST_FILENAME) { manifestBytes ->
                try {
                    if (manifestBytes == null) {
                        logWarn("资产清单拉取失败（保持本地版本）")
                        finish(SyncResult(Outcome.FAILED, emptySet()))
                        return@fetch
                    }
                    val manifest = try {
                        AssetManifestParser.parse(decodeStrictUtf8(manifestBytes).orEmpty())
                    } catch (e: Exception) {
                        null
                    }
                    if (manifest == null) {
                        logWarn("资产清单解析失败（非法 JSON / 顶层结构缺失），保持本地版本")
                        finish(SyncResult(Outcome.FAILED, emptySet()))
                        return@fetch
                    }
                    val local = readLocalState(overlayRoot)
                    if (!shouldApply(manifest, local)) {
                        logInfo(
                            "资产清单版本不比本地新（远端 v${manifest.version}/${manifest.generatedAt ?: "-"}，" +
                                "本地 v${local.version}/${local.generatedAt ?: "-"}），整体跳过"
                        )
                        finish(SyncResult(Outcome.SKIPPED, emptySet()))
                        return@fetch
                    }
                    if (manifest.entries.isEmpty()) {
                        // 版本比本地新但零有效条目：服务器端脚本异常的信号——零条目同步成功
                        // 只会把 state 锁死在坏版本上，按失败处理（不写 state，下次连接重试）。
                        // 注意这个检查必须排在版本比对之后：版本相同的零条目清单是正常幂等
                        // 场景（无新资产），走上面的 SKIPPED。
                        logWarn("资产清单零有效条目（服务器端脚本异常？），保持本地版本")
                        finish(SyncResult(Outcome.FAILED, emptySet()))
                        return@fetch
                    }

                    logInfo("资产清单 v${manifest.version}（${manifest.generatedAt ?: "无时间戳"}），" +
                        "共 ${manifest.entries.size} 条待同步")
                    downloadEntries(overlayRoot, manifest, startIndex = 0, written = mutableSetOf(), fetch = fetch, onDone = ::finish)
                } catch (e: Exception) {
                    // 最后一道防线：apply 内部已各自兜底，这里保证异常不冒泡回 /view 回调
                    logWarn("资产同步异常（忽略）：${e.message}")
                    finish(SyncResult(Outcome.FAILED, emptySet()))
                }
            }
        } catch (e: Exception) {
            // 发起阶段就抛出：自己收尾后吞掉。刻意**不是** finally——fetch 的 enqueue 一返回
            // 就会执行 finally，等于在异步回调到来之前提前放闸（闸门保护彻底失效）。
            logWarn("资产同步在发起阶段异常（忽略）：${e.message}")
            release()
        }
    }

    /**
     * 逐条拉取并落盘（链式串行：上一条的回调里发起下一条，避免并发拉取打乱写入顺序）。
     * 任一条失败 → 停止后续条目（已落盘的合法资产保留）；全部成功 → 写 manifest_state。
     */
    private fun downloadEntries(
        overlayRoot: File,
        manifest: AssetManifest,
        startIndex: Int,
        written: MutableSet<AssetKind>,
        fetch: (filename: String, callback: (ByteArray?) -> Unit) -> Unit,
        onDone: (SyncResult) -> Unit,
    ) {
        if (startIndex >= manifest.entries.size) {
            // 全部条目成功：记录远端版本（下次同版本 → SKIPPED），并报告落盘了哪些 kind
            writeLocalState(overlayRoot, manifest.version, manifest.generatedAt)
            logInfo("资产同步完成：${written.size} 类落盘（v${manifest.version}）")
            onDone(SyncResult(Outcome.COMPLETED, written.toSet()))
            return
        }
        val entry = manifest.entries[startIndex]
        fetch(entry.filename) { bytes ->
            try {
                if (bytes == null) {
                    logWarn("资产拉取失败（跳过其后全部条目）：${entry.filename}")
                    onDone(SyncResult(Outcome.FAILED, written.toSet()))
                    return@fetch
                }

                // SHA-256 校验：缺失/不匹配一律拒（清单给了校验值就必须对上）
                val verdict = DownloadPolicy.verifySha256(entry.sha256, sha256Hex(bytes))
                if (verdict != DownloadPolicy.Sha256Verdict.MATCH) {
                    logWarn("资产 SHA 校验不过（$verdict），拒收：${entry.filename}")
                    onDone(SyncResult(Outcome.FAILED, written.toSet()))
                    return@fetch
                }

                // 内容准入：SHA 对也可能是坏内容（清单生成错 / 服务器文件被换）
                val admitted = AssetAdmission.admit(entry.kind, bytes)
                if (admitted is AdmissionResult.Rejected) {
                    logWarn("资产准入拒绝：${entry.filename} — ${admitted.reason}")
                    onDone(SyncResult(Outcome.FAILED, written.toSet()))
                    return@fetch
                }

                val target = File(File(overlayRoot, entry.kind.dirName), entry.filename)
                AtomicFileWriter.writeBytes(target, bytes)
                written.add(entry.kind)
                logInfo("资产已落盘：${target.name}（${bytes.size} 字节）")
                downloadEntries(overlayRoot, manifest, startIndex + 1, written, fetch, onDone)
            } catch (e: Exception) {
                logWarn("资产处理异常（${entry.filename}）：${e.message}")
                onDone(SyncResult(Outcome.FAILED, written.toSet()))
            }
        }
    }

    // ---------------- 版本比对（纯函数，ModelIntroSync.shouldApply 同款语义） ----------------

    /** 本地已应用的 manifest 版本状态（`overlay/manifest_state.json`；不存在/损坏 = 全新状态）。 */
    internal data class LocalManifestState(val version: Int, val generatedAt: String?)

    internal fun shouldApply(manifest: AssetManifest, local: LocalManifestState): Boolean =
        ModelIntroSync.shouldApply(
            remoteGeneratedAt = manifest.generatedAt,
            remoteVersion = manifest.version,
            localGeneratedAt = local.generatedAt,
            localVersion = local.version,
        )

    internal fun readLocalState(overlayRoot: File): LocalManifestState {
        val file = File(overlayRoot, STATE_FILENAME)
        if (!file.isFile) return LocalManifestState(version = 0, generatedAt = null)
        return try {
            val obj = JSONObject(file.readText())
            LocalManifestState(
                version = obj.optInt("version", 0),
                generatedAt = obj.optString("generated_at", "").takeIf { it.isNotBlank() },
            )
        } catch (e: Exception) {
            // state 坏了按「从未同步过」处理：宁可多同步一次，不可漏同步
            LocalManifestState(version = 0, generatedAt = null)
        }
    }

    internal fun writeLocalState(overlayRoot: File, version: Int, generatedAt: String?) {
        val obj = JSONObject()
        obj.put("version", version)
        if (generatedAt != null) obj.put("generated_at", generatedAt)
        AtomicFileWriter.writeBytes(
            File(overlayRoot, STATE_FILENAME),
            obj.toString().toByteArray(Charsets.UTF_8),
        )
    }

    companion object {

        /** 本地覆盖层根目录名（`filesDir/overlay`；不在 MediaCacheCleanupPolicy 任何清单里，fail-closed 安全）。 */
        const val OVERLAY_DIR = "overlay"

        /** manifest 在服务器 output/ 下的文件名。 */
        const val MANIFEST_FILENAME = "asset_manifest.json"

        /** 同源 /view 的 subfolder（与 model_intros.json 同一目录：`output/comfychair/`）。 */
        const val REMOTE_SUBFOLDER = "comfychair"

        /** 本地版本状态文件名（overlay 根下）。 */
        const val STATE_FILENAME = "manifest_state.json"

        /** 字节流 SHA-256（小写十六进制；与 UpdateDownloadWorker.sha256Of 同款语义，输入是内存字节）。 */
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
