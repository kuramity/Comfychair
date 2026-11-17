package sh.hnet.comfychair.connection

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 模型下拉四态判定的逐条钉住（Q3-3，v4.16.5）。
 *
 * 背景：/object_info 失败后旧实现只置 lastError 且 UI 零消费——「失败」与
 * 「空」在界面上无法区分。判定优先级：LOADING > FAILED > EMPTY > LOADED。
 */
class ModelListUiStateTest {

    @Test
    fun `加载中一律优先`() {
        // 请求在飞时一切以"加载中"示人——哪怕磁盘预填已铺上旧数据、哪怕上次失败留了错误
        assertEquals(
            ModelListUiState.LOADING,
            resolveModelListUiState(isLoading = true, isLoaded = false, lastError = null, optionCount = 0)
        )
        assertEquals(
            ModelListUiState.LOADING,
            resolveModelListUiState(isLoading = true, isLoaded = false, lastError = "x", optionCount = 0)
        )
        assertEquals(
            ModelListUiState.LOADING,
            resolveModelListUiState(isLoading = true, isLoaded = true, lastError = null, optionCount = 42)
        )
    }

    @Test
    fun `失败态必须可见`() {
        assertEquals(
            ModelListUiState.FAILED,
            resolveModelListUiState(isLoading = false, isLoaded = false, lastError = "Failed to fetch server data", optionCount = 0)
        )
    }

    @Test
    fun `失败但磁盘预填有旧数据时仍判失败`() {
        // 失败不清已加载列表（Q3-4 红线）：选项照常可显示，但"刷新失败"这个事实
        // 必须可见（UI 端据此在菜单尾附加重试行），不能被旧数据伪装成一切正常
        assertEquals(
            ModelListUiState.FAILED,
            resolveModelListUiState(isLoading = false, isLoaded = true, lastError = "Failed to fetch server data", optionCount = 18)
        )
    }

    @Test
    fun `已加载且列表空判为服务器无模型`() {
        // 与失败严格区分：此时重试也拉不出东西，不该误导用户反复重试
        assertEquals(
            ModelListUiState.EMPTY,
            resolveModelListUiState(isLoading = false, isLoaded = true, lastError = null, optionCount = 0)
        )
    }

    @Test
    fun `正常加载完成判为可用`() {
        assertEquals(
            ModelListUiState.LOADED,
            resolveModelListUiState(isLoading = false, isLoaded = true, lastError = null, optionCount = 66)
        )
    }

    @Test
    fun `从未拉取的空列表维持既有空框行为`() {
        // 未连接 / 从未成功拉取（isLoaded=false、无错误、非加载中）：既不能谎称
        // "服务器没模型"也不该弹失败，落 LOADED → UI 端空菜单（与旧版一致）
        assertEquals(
            ModelListUiState.LOADED,
            resolveModelListUiState(isLoading = false, isLoaded = false, lastError = null, optionCount = 0)
        )
    }
}
