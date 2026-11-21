package sh.hnet.comfychair.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * A-B1 新增：更新下载稳定性纯逻辑单测。
 *
 * 覆盖五类判定（全部不依赖 Android 框架，不放宽断言）：
 * 1. 异常分型映射（SocketTimeout/Connect → 可恢复；HTTP 404 → 不可恢复；416 → 特殊处理）
 * 2. 416 / `.part == total` 判定
 * 3. 重试上限（attemptCount 4 → 可重试，5 → 不可重试）
 * 4. 停滞看门狗的时间判定
 * 5. total 缺失时的百分比降级
 */
class DownloadPolicyTest {

    // ---------- 1. 异常分型映射 ----------

    @Test
    fun `network exceptions map to retryable kinds`() {
        assertEquals(
            DownloadPolicy.FailureKind.TRANSFER_TIMEOUT,
            DownloadPolicy.classifyFailure(SocketTimeoutException("read timed out"))
        )
        assertEquals(
            DownloadPolicy.FailureKind.CONNECT_FAILED,
            DownloadPolicy.classifyFailure(ConnectException("connection refused"))
        )
        assertEquals(
            DownloadPolicy.FailureKind.CONNECT_FAILED,
            DownloadPolicy.classifyFailure(UnknownHostException("no such host"))
        )
        assertEquals(
            DownloadPolicy.FailureKind.CONNECT_FAILED,
            DownloadPolicy.classifyFailure(SSLException("handshake failed"))
        )
        assertTrue(DownloadPolicy.isRetryable(SocketTimeoutException("x")))
        assertTrue(DownloadPolicy.isRetryable(ConnectException("x")))
        assertTrue(DownloadPolicy.isRetryable(UnknownHostException("x")))
        assertTrue(DownloadPolicy.isRetryable(SSLException("x")))
    }

    @Test
    fun `http 5xx is retryable while 4xx is not`() {
        val serverError = DownloadPolicy.DownloadHttpException(503)
        assertEquals(DownloadPolicy.FailureKind.SERVER_ERROR, DownloadPolicy.classifyFailure(serverError))
        assertTrue(DownloadPolicy.isRetryable(serverError))

        val notFound = DownloadPolicy.DownloadHttpException(404)
        assertEquals(DownloadPolicy.FailureKind.HTTP_REJECTED, DownloadPolicy.classifyFailure(notFound))
        assertFalse(DownloadPolicy.isRetryable(notFound))
    }

    @Test
    fun `http 416 falls through to unrecoverable after self-heal`() {
        // 416 在 Worker 里有专门的自愈分支（删 .part 只全量重下一次）；
        // 走到分型器说明已自愈过一次仍失败 → 按 4xx 判不可恢复，绝不无限循环
        val e = DownloadPolicy.DownloadHttpException(416)
        assertEquals(DownloadPolicy.FailureKind.HTTP_REJECTED, DownloadPolicy.classifyFailure(e))
        assertFalse(DownloadPolicy.isRetryable(e))
    }

    @Test
    fun `disk full is fatal and stalled transfer is retryable`() {
        val disk = DownloadPolicy.DiskFullException(usableBytes = 1L, neededBytes = 160L * 1024 * 1024)
        assertEquals(DownloadPolicy.FailureKind.DISK_FULL, DownloadPolicy.classifyFailure(disk))
        assertFalse(DownloadPolicy.isRetryable(disk))

        val stalled = DownloadPolicy.StalledTransferException(lastByteAtMs = 0L, detectedAtMs = 120_000L)
        assertEquals(DownloadPolicy.FailureKind.TRANSFER_TIMEOUT, DownloadPolicy.classifyFailure(stalled))
        assertTrue(DownloadPolicy.isRetryable(stalled))
    }

    @Test
    fun `unknown io stays retryable while non-io does not`() {
        // 保持旧行为口径：IO 类但不在已知清单 → 可重试（受上限约束）；
        // 非 IO 异常 → 不可恢复（对应原 catch(Exception) → failure）
        assertTrue(DownloadPolicy.isRetryable(IOException("stream was reset")))
        assertFalse(DownloadPolicy.isRetryable(IllegalStateException("bug")))
    }

    // ---------- 2. 416 / .part == total 判定 ----------

    @Test
    fun `part exactly at total counts as complete`() {
        assertTrue(DownloadPolicy.isPartComplete(partLength = 100L, total = 100L))
        assertTrue(DownloadPolicy.isPartComplete(partLength = 159_305_371L, total = 159_305_371L))
    }

    @Test
    fun `part below or beyond total is not treated as complete`() {
        assertFalse(DownloadPolicy.isPartComplete(99L, 100L))
        assertFalse(DownloadPolicy.isPartComplete(101L, 100L))
    }

    @Test
    fun `unknown total or empty part never counts as complete`() {
        assertFalse(DownloadPolicy.isPartComplete(100L, 0L))
        assertFalse(DownloadPolicy.isPartComplete(100L, -1L))
        assertFalse(DownloadPolicy.isPartComplete(0L, 100L))
    }

    // ---------- 3. 重试上限 ----------

    @Test
    fun `attempt 0 and 1 can retry attempt 2 cannot`() {
        // v4.18.0（R5）：上限由 5 降到 2 —— 用户实测「重试 5 次仍失败」烧掉大量流量。
        // 反证：把 MAX_AUTO_RETRIES 改回 5（或任何 >2 的值），这个断言必须变红。
        assertTrue(DownloadPolicy.shouldRetryAutomatically(0))
        assertTrue(DownloadPolicy.shouldRetryAutomatically(1))
        assertFalse(DownloadPolicy.shouldRetryAutomatically(2))
        assertFalse(DownloadPolicy.shouldRetryAutomatically(3))
    }

    @Test
    fun `legacy switch also has a hard retry cap`() {
        // R5 第 3 条：回退开关不允许存在「无次数上限」的分支（那是流量黑洞）。
        // 反证：把 LEGACY_MAX_AUTO_RETRIES 改成 99，这两条断言必须变红。
        assertTrue(DownloadPolicy.shouldRetryLegacy(0))
        assertTrue(DownloadPolicy.shouldRetryLegacy(2))
        assertFalse(DownloadPolicy.shouldRetryLegacy(3))
        assertFalse(DownloadPolicy.shouldRetryLegacy(99))
        // 统一入口按模式分派，两条路径都必然是有限值
        assertFalse(DownloadPolicy.shouldRetry(2, legacyMode = false))
        assertFalse(DownloadPolicy.shouldRetry(3, legacyMode = true))
    }

    // ---------- 4. 停滞看门狗 ----------

    @Test
    fun `stall detected only at or after threshold`() {
        val lastByteAt = 1_000_000L
        val threshold = DownloadPolicy.STALL_THRESHOLD_MS
        assertFalse(DownloadPolicy.isStalled(lastByteAt, lastByteAt + threshold - 1, threshold))
        assertTrue(DownloadPolicy.isStalled(lastByteAt, lastByteAt + threshold, threshold))
        assertTrue(DownloadPolicy.isStalled(lastByteAt, lastByteAt + threshold + 5_000, threshold))
    }

    @Test
    fun `no byte received yet never counts as stalled`() {
        // 0/负值表示一个字节都没收到（连接刚建立），停滞判定交给 readTimeout 管
        assertFalse(DownloadPolicy.isStalled(0L, 999_999L, DownloadPolicy.STALL_THRESHOLD_MS))
        assertFalse(DownloadPolicy.isStalled(-1L, 999_999L, DownloadPolicy.STALL_THRESHOLD_MS))
    }

    @Test
    fun `default threshold is the published 120 seconds`() {
        val lastByteAt = 50_000_000L
        assertTrue(DownloadPolicy.isStalled(lastByteAt, lastByteAt + 120_000L))
        assertFalse(DownloadPolicy.isStalled(lastByteAt, lastByteAt + 119_999L))
    }

    // ---------- 5. total 缺失时的进度降级 ----------

    @Test
    fun `unknown total falls back to indeterminate percent`() {
        assertEquals(UpdateFormat.unknownPercent, UpdateFormat.percent(100L, 0L))
        assertEquals(UpdateFormat.unknownPercent, UpdateFormat.percent(100L, -1L))
        // 已知总长时正常计算
        assertEquals(10, UpdateFormat.percent(100L, 1000L))
    }

    @Test
    fun `download http exception carries status code for banner`() {
        assertEquals(503, DownloadPolicy.DownloadHttpException(503).code)
        assertEquals(416, DownloadPolicy.DownloadHttpException(416).code)
    }

    // ---------- 6. SHA-256 完整性校验（P0-7） ----------

    /** 一个格式合法的 64 位十六进制校验值（内容无所谓，只用来看分支） */
    private val validSha = "0123456789abcdef".repeat(4)

    @Test
    fun `清单缺少 sha256 时必须判缺失而不是放行`() {
        // 修复前 Worker 的判据是 `expectedSha256.isEmpty() || equals(...)` → 空串直接通过；
        // 这里钉住新口径：缺失一律 MISSING，由调用方判失败
        assertEquals(
            DownloadPolicy.Sha256Verdict.MISSING,
            DownloadPolicy.verifySha256(null, validSha)
        )
        assertEquals(
            DownloadPolicy.Sha256Verdict.MISSING,
            DownloadPolicy.verifySha256("", validSha)
        )
        assertEquals(
            DownloadPolicy.Sha256Verdict.MISSING,
            DownloadPolicy.verifySha256("   ", validSha)
        )
        // 格式不合法（长度不对 / 非十六进制）同样按缺失处理，不能"格式错就跳过"
        assertEquals(
            DownloadPolicy.Sha256Verdict.MISSING,
            DownloadPolicy.verifySha256("abc", validSha)
        )
        assertEquals(
            DownloadPolicy.Sha256Verdict.MISSING,
            DownloadPolicy.verifySha256("z".repeat(64), validSha)
        )
    }

    @Test
    fun `sha256 规范化拒绝非法值并统一小写`() {
        assertNull(DownloadPolicy.normalizeSha256(null))
        assertNull(DownloadPolicy.normalizeSha256(""))
        assertNull(DownloadPolicy.normalizeSha256("12345678"))
        assertEquals(validSha, DownloadPolicy.normalizeSha256("  " + validSha + "  "))
        assertEquals(validSha, DownloadPolicy.normalizeSha256(validSha.uppercase()))
    }

    @Test
    fun `sha256 匹配与不匹配`() {
        assertEquals(
            DownloadPolicy.Sha256Verdict.MATCH,
            DownloadPolicy.verifySha256(validSha, validSha)
        )
        // 大小写不敏感（服务器有时输出大写）
        assertEquals(
            DownloadPolicy.Sha256Verdict.MATCH,
            DownloadPolicy.verifySha256(validSha.uppercase(), validSha)
        )
        assertEquals(
            DownloadPolicy.Sha256Verdict.MISMATCH,
            DownloadPolicy.verifySha256(validSha, "f".repeat(64))
        )
    }

    // ---------- 7. 重定向白名单（P0-7） ----------

    private val allowedHosts = setOf("update.example.com")

    @Test
    fun `重定向目标必须仍在白名单内`() {
        assertTrue(
            DownloadPolicy.isRedirectTargetAllowed(
                "https://update.example.com/apk/a.apk", allowedHosts
            )
        )
        // 换域名 → 拒绝
        assertFalse(
            DownloadPolicy.isRedirectTargetAllowed(
                "https://evil.example.net/apk/a.apk", allowedHosts
            )
        )
        // 同域名但降级成明文 → 拒绝
        assertFalse(
            DownloadPolicy.isRedirectTargetAllowed(
                "http://update.example.com/apk/a.apk", allowedHosts
            )
        )
        // 白名单为空（未配置更新源）→ 一律拒绝
        assertFalse(
            DownloadPolicy.isRedirectTargetAllowed(
                "https://update.example.com/apk/a.apk", emptySet()
            )
        )
        assertFalse(DownloadPolicy.isRedirectTargetAllowed(null, allowedHosts))
        assertFalse(DownloadPolicy.isRedirectTargetAllowed("not a url", allowedHosts))
    }

    @Test
    fun `相对重定向按基地址解析`() {
        assertEquals(
            "https://update.example.com/apk/c.apk",
            DownloadPolicy.resolveRedirect(
                "https://update.example.com/apk/dir/a.apk", "../c.apk"
            )
        )
        assertEquals(
            "https://cdn.example.com/b.apk",
            DownloadPolicy.resolveRedirect(
                "https://update.example.com/apk/a.apk", "https://cdn.example.com/b.apk"
            )
        )
        assertNull(DownloadPolicy.resolveRedirect("https://update.example.com/a.apk", null))
        assertNull(DownloadPolicy.resolveRedirect("https://update.example.com/a.apk", "  "))
    }

    @Test
    fun `重定向越界异常不可重试`() {
        val e = DownloadPolicy.InsecureRedirectException("https://evil.example.net/a.apk")
        assertEquals(
            DownloadPolicy.FailureKind.INSECURE_REDIRECT,
            DownloadPolicy.classifyFailure(e)
        )
        assertFalse(DownloadPolicy.isRetryable(e))
    }
}
