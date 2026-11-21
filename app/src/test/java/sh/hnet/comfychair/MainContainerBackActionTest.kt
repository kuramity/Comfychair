package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import sh.hnet.comfychair.navigation.MainRoute

/**
 * 钉住「主界面按返回键该干什么」的判定。
 *
 * 背景（用户报）：主界面按返回直接退到桌面。根因是登录页进主容器时用了
 * NEW_TASK + CLEAR_TASK（LoginScreen.kt:111-115），主容器是任务栈唯一根，
 * 系统默认返回就是 finish()。现在的行为：
 * - 不在起始 tab（含 tab 二级页）→ 把回退栈弹回起始 tab；
 * - 就在起始 tab → 回连接页（不登出、不清队列）。
 *
 * 判定抽成纯函数 [backActionAt] / [tabIndexOfRoute]，因为真实回调跑在
 * `onBackPressedDispatcher` + NavController 上，JVM 单测碰不到。
 *
 * 反证（两个方向都钉住，不存在恒真写法）：
 * - 把 [backActionAt] 改成恒 `ReturnToLogin` → 「非起始 tab 切回起始 tab」那几条会红；
 * - 改成恒 `SwitchToStartTab` → 「起始 tab 回连接页」那几条会红（用户会永远出不去）。
 */
class MainContainerBackActionTest {

    @Test
    fun `在起始 tab 按返回回连接页`() {
        // 起始页可能是四个生成 tab 里的任意一个（由上次生成任务的 ownerId 决定）
        for (start in 0..3) {
            assertEquals(
                "起始页是第 $start 个 tab 时应回连接页",
                BackAction.ReturnToLogin,
                backActionAt(tabIndex = start, startIndex = start)
            )
        }
        // 退化情形：既不在 tab 上、起始也不是 tab → 也必须能退出，不能空转
        assertEquals(
            BackAction.ReturnToLogin,
            backActionAt(tabIndex = NOT_A_TAB_INDEX, startIndex = NOT_A_TAB_INDEX)
        )
    }

    @Test
    fun `非起始 tab 按返回切回起始 tab`() {
        // 起始页 = 文生图（第 0 个）：在其它 tab 上按返回都要先回文生图
        assertEquals(BackAction.SwitchToStartTab, backActionAt(tabIndex = 1, startIndex = 0))
        assertEquals(BackAction.SwitchToStartTab, backActionAt(tabIndex = 2, startIndex = 0))
        assertEquals(BackAction.SwitchToStartTab, backActionAt(tabIndex = 3, startIndex = 0))
        assertEquals(
            "标签库是第 5 个 tab，也要先回起始页",
            BackAction.SwitchToStartTab,
            backActionAt(tabIndex = 4, startIndex = 0)
        )
        // 起始页 = 图生视频（第 3 个）：在文生图上按返回要回图生视频（不能写死成第一个）
        assertEquals(BackAction.SwitchToStartTab, backActionAt(tabIndex = 0, startIndex = 3))
        // 二级页（不在任何 tab 上）按返回同样先回起始页，真实弹栈由 popBackStack 完成
        assertEquals(
            BackAction.SwitchToStartTab,
            backActionAt(tabIndex = NOT_A_TAB_INDEX, startIndex = 0)
        )
    }

    @Test
    fun `tab 下标映射与底栏顺序一致`() {
        assertEquals(0, tabIndexOfRoute(MainRoute.TextToImage.route))
        assertEquals(1, tabIndexOfRoute(MainRoute.ImageToImage.route))
        assertEquals(2, tabIndexOfRoute(MainRoute.TextToVideo.route))
        assertEquals(3, tabIndexOfRoute(MainRoute.ImageToVideo.route))
        assertEquals(4, tabIndexOfRoute(MainRoute.TagLibrary.route))
        assertEquals("tab 顺序不能重复", 5, MAIN_TAB_ROUTES.toSet().size)
    }

    @Test
    fun `二级页与未就绪路由折算为非 tab`() {
        // 标签详情是二级页：destination.route 是带占位符的模板串
        assertEquals(NOT_A_TAB_INDEX, tabIndexOfRoute(MainRoute.TagDetail.route))
        // 导航图还没就绪
        assertEquals(NOT_A_TAB_INDEX, tabIndexOfRoute(null))
        // 图库是独立 Activity 的 route，不属于底栏 tab
        assertEquals(NOT_A_TAB_INDEX, tabIndexOfRoute(MainRoute.Gallery.route))
        // 未知路由不能意外撞上某个 tab
        assertEquals(NOT_A_TAB_INDEX, tabIndexOfRoute("no_such_route"))
    }

    @Test
    fun `二级页与起始页的判定是相反的两类`() {
        // 直接钉住「二级页 ≠ 起始页」这件事：一旦有人把 NOT_A_TAB_INDEX 当成
        // 「等于任何下标」，二级页按返回就会错误地回连接页而不是回起始页。
        assertNotEquals(tabIndexOfRoute(MainRoute.TagDetail.route), tabIndexOfRoute(MainRoute.TextToImage.route))
        assertEquals(
            BackAction.SwitchToStartTab,
            backActionAt(
                tabIndex = tabIndexOfRoute(MainRoute.TagDetail.route),
                startIndex = tabIndexOfRoute(MainRoute.TextToImage.route)
            )
        )
    }
}