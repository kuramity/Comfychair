package sh.hnet.comfychair.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.hnet.comfychair.connection.ConnectionFailure

/**
 * 查看器加载失败链路的纯逻辑单测（C-B1-a，H1-a/H1-b/H6）：
 *
 * 1. [ViewerLoadPolicy.categorizeFailure] —— ComfyUIClient 的失败分型 → 三档文案。
 *    FILE_MISSING（重试也没用）与 NETWORK（重试可能有用）必须分开，
 *    STALLED 本质是链路问题归 NETWORK，其余全归 GENERIC。
 *
 * 2. [ViewerLoadPolicy.resolveShowState] —— 「是否显示失败态」判定。
 *    核心规则：内容缺失且**确定不在加载**才算失败。修复前视频页拿
 *    "currentVideoUri == null" 当"还在加载"的信号（死转圈），图片页静默黑屏。
 */
class ViewerLoadPolicyTest {

    // ---------- 失败分型 → 文案档位 ----------

    @Test
    fun `FILE_MISSING maps to file-missing copy`() {
        assertEquals(
            ViewerFailureCategory.FILE_MISSING,
            ViewerLoadPolicy.categorizeFailure(ConnectionFailure.FILE_MISSING)
        )
    }

    @Test
    fun `NETWORK and STALLED map to network copy`() {
        // 传输停滞（隧道挂掉后的典型表现）本质也是链路问题，跟 NETWORK 同文案
        assertEquals(
            ViewerFailureCategory.NETWORK,
            ViewerLoadPolicy.categorizeFailure(ConnectionFailure.NETWORK)
        )
        assertEquals(
            ViewerFailureCategory.NETWORK,
            ViewerLoadPolicy.categorizeFailure(ConnectionFailure.STALLED)
        )
    }

    @Test
    fun `remaining failure types map to generic copy`() {
        // 认证失败、服务器不合法、超限、无失败 —— 都不属于前两档的语义，一律通用文案
        assertEquals(
            ViewerFailureCategory.GENERIC,
            ViewerLoadPolicy.categorizeFailure(ConnectionFailure.AUTHENTICATION)
        )
        assertEquals(
            ViewerFailureCategory.GENERIC,
            ViewerLoadPolicy.categorizeFailure(ConnectionFailure.INVALID_SERVER)
        )
        assertEquals(
            ViewerFailureCategory.GENERIC,
            ViewerLoadPolicy.categorizeFailure(ConnectionFailure.FILE_TOO_LARGE)
        )
        assertEquals(
            ViewerFailureCategory.GENERIC,
            ViewerLoadPolicy.categorizeFailure(ConnectionFailure.NONE)
        )
    }

    // ---------- 是否显示失败态（isVideo, hasContent, isLoading → ShowState） ----------

    @Test
    fun `content present always wins - both image and video`() {
        // 有内容就是 CONTENT，与 isVideo / isLoading 无关
        assertEquals(
            ViewerShowState.CONTENT,
            ViewerLoadPolicy.resolveShowState(isVideo = false, hasContent = true, isLoading = false)
        )
        assertEquals(
            ViewerShowState.CONTENT,
            ViewerLoadPolicy.resolveShowState(isVideo = true, hasContent = true, isLoading = false)
        )
        assertEquals(
            ViewerShowState.CONTENT,
            ViewerLoadPolicy.resolveShowState(isVideo = false, hasContent = true, isLoading = true)
        )
        assertEquals(
            ViewerShowState.CONTENT,
            ViewerLoadPolicy.resolveShowState(isVideo = true, hasContent = true, isLoading = true)
        )
    }

    @Test
    fun `no content while loading shows spinner - both image and video`() {
        // 加载中转圈（修复前视频页即使加载结束也停在这个状态）
        assertEquals(
            ViewerShowState.LOADING,
            ViewerLoadPolicy.resolveShowState(isVideo = true, hasContent = false, isLoading = true)
        )
        assertEquals(
            ViewerShowState.LOADING,
            ViewerLoadPolicy.resolveShowState(isVideo = false, hasContent = false, isLoading = true)
        )
    }

    @Test
    fun `no content and not loading is the video failure state (H1-a)`() {
        // 修复前：视频 fetch 失败后 currentVideoUri 永远是 null → 无条件转圈（死转圈）
        assertEquals(
            ViewerShowState.FAILED,
            ViewerLoadPolicy.resolveShowState(isVideo = true, hasContent = false, isLoading = false)
        )
    }

    @Test
    fun `no content and not loading is the image failure state (H1-b)`() {
        // 修复前：图片失败静默走黑屏分支，无提示无重试
        assertEquals(
            ViewerShowState.FAILED,
            ViewerLoadPolicy.resolveShowState(isVideo = false, hasContent = false, isLoading = false)
        )
    }
}
