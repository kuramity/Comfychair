package sh.hnet.comfychair.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.hnet.comfychair.viewmodel.ContentType

/**
 * P6：在途提交认领的纯逻辑（[pickPendingSubmission]）。
 *
 * 这里不初始化 [JobRegistry]（它会碰 SharedPreferences / GalleryRepository），
 * 只钉住"按提交顺序、只认窗口内最早一条"这条规则 —— 它决定了弱网下
 * `execution_start` 能不能被正确接管，以及会不会误吃外部任务的信号。
 */
class PendingSubmissionClaimTest {

    private val t0 = 1_000_000L

    private fun pending(token: Long, atMs: Long, owner: String = "TEXT_TO_IMAGE") =
        PendingSubmission(
            token = token,
            enqueuedAtMs = atMs,
            ownerId = owner,
            contentType = ContentType.IMAGE,
            label = owner
        )

    @Test
    fun `空表不认领`() {
        assertNull(pickPendingSubmission(emptyList(), t0))
    }

    @Test
    fun `认领最早的一条`() {
        val list = listOf(pending(1, t0 - 10_000), pending(2, t0 - 5_000))

        val claimed = pickPendingSubmission(list, t0)

        assertEquals(1L, claimed?.token)
    }

    @Test
    fun `跳过超出窗口的最旧条目`() {
        val list = listOf(
            pending(1, t0 - PENDING_CLAIM_MAX_AGE_MS - 1),
            pending(2, t0 - 3_000)
        )

        val claimed = pickPendingSubmission(list, t0)

        assertEquals("过期的旧提交不得挡住窗口内的新提交", 2L, claimed?.token)
    }

    @Test
    fun `全部超出窗口则不认领`() {
        val list = listOf(
            pending(1, t0 - PENDING_CLAIM_MAX_AGE_MS - 1),
            pending(2, t0 - PENDING_CLAIM_MAX_AGE_MS - 2)
        )

        assertNull(pickPendingSubmission(list, t0))
    }

    @Test
    fun `窗口边界正好等于上限时仍可认领`() {
        val list = listOf(pending(1, t0 - PENDING_CLAIM_MAX_AGE_MS))

        assertEquals(1L, pickPendingSubmission(list, t0)?.token)
    }

    @Test
    fun `时间倒退的异常条目不被认领`() {
        val list = listOf(pending(1, t0 + 10_000), pending(2, t0 - 1_000))

        val claimed = pickPendingSubmission(list, t0)

        assertEquals("未来时间的条目视为无效，认领正常的第二条", 2L, claimed?.token)
    }
}