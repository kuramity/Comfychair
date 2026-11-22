package sh.hnet.comfychair.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示词预设 ViewModel「纯状态机」部分的 JVM 直测（不碰 Android）。
 *
 * 只覆盖不依赖 storage（需 Context 构造）与 viewModelScope（需 Dispatchers.Main）的函数：
 * 搜索词、标签筛选、收藏过滤、预设库抽屉、保存对话框的打开与复位。
 * 依赖 storage 的函数（refreshPresets/onPresetSelected/onSavePreset 等）见未覆盖清单。
 *
 * 反证：dismissLibrary 若漏掉清空筛选（如忘清 selectedTags/filterFavoritesOnly），
 * 对应测试变红；showSaveDialog 若漏记负面提示词初值，「记录正负面初值」测试变红。
 */
class PromptPresetViewModelStateTest {

    private val vm = PromptPresetViewModel()

    private fun state() = vm.uiState.value

    @Test
    fun `初始状态为空白筛选`() {
        assertTrue(state().allPresets.isEmpty())
        assertEquals("", state().searchQuery)
        assertTrue(state().selectedTags.isEmpty())
        assertFalse(state().filterFavoritesOnly)
        assertNull(state().activePresetId)
        assertFalse(state().showLibrarySideSheet)
        assertFalse(state().showSaveDialog)
    }

    @Test
    fun `onSearchQueryChange更新搜索词`() {
        vm.onSearchQueryChange("nsfw")
        assertEquals("nsfw", state().searchQuery)
    }

    @Test
    fun `onTagToggle第一次加入第二次移除`() {
        vm.onTagToggle("质量")
        assertTrue("第一次切换：标签进入筛选集", "质量" in state().selectedTags)

        vm.onTagToggle("风格")
        assertTrue("质量" in state().selectedTags && "风格" in state().selectedTags)

        vm.onTagToggle("质量")
        assertFalse("第二次切换：同一标签被移除", "质量" in state().selectedTags)
        assertTrue("不影响其他已选标签", "风格" in state().selectedTags)
    }

    @Test
    fun `clearTagFilters清空全部选中标签`() {
        vm.onTagToggle("质量")
        vm.onTagToggle("风格")
        vm.clearTagFilters()
        assertTrue(state().selectedTags.isEmpty())
    }

    @Test
    fun `onToggleFavoritesFilter翻转收藏过滤`() {
        assertFalse(state().filterFavoritesOnly)
        vm.onToggleFavoritesFilter()
        assertTrue(state().filterFavoritesOnly)
        vm.onToggleFavoritesFilter()
        assertFalse(state().filterFavoritesOnly)
    }

    @Test
    fun `showLibrary打开预设库抽屉`() {
        vm.showLibrary()
        assertTrue(state().showLibrarySideSheet)
    }

    @Test
    fun `dismissLibrary关闭抽屉并重置全部筛选`() {
        // 把所有可残留的筛选都打开
        vm.showLibrary()
        vm.onSearchQueryChange("nsfw")
        vm.onTagToggle("质量")
        vm.onTagToggle("风格")
        vm.onToggleFavoritesFilter()

        vm.dismissLibrary()

        assertFalse("抽屉必须收起", state().showLibrarySideSheet)
        assertEquals("搜索词必须清空：下次打开预设库不应带着上次的搜索词", "", state().searchQuery)
        assertTrue("选中标签必须清空", state().selectedTags.isEmpty())
        assertFalse("收藏过滤必须复位", state().filterFavoritesOnly)
    }

    @Test
    fun `showSaveDialog记录正面与负面提示词初值`() {
        vm.showSaveDialog("a cat in space", "blurry")
        assertTrue(state().showSaveDialog)
        assertNull("新建预设时不得处于编辑既有预设态", state().editingPreset)
        assertEquals("a cat in space", state().currentPromptForSave)
        assertEquals(
            "负面提示词初值必须一起记录：存的时候一起存，还原时也要一起还原",
            "blurry",
            state().currentNegativePromptForSave
        )
    }

    @Test
    fun `dismissSaveDialog清空对话框与暂存初值`() {
        vm.showSaveDialog("a cat in space", "blurry")
        vm.dismissSaveDialog()
        assertFalse(state().showSaveDialog)
        assertNull(state().editingPreset)
        assertEquals("", state().currentPromptForSave)
        assertEquals("", state().currentNegativePromptForSave)
    }

    @Test
    fun `未初始化storage时getFilteredPresets返回空列表`() {
        assertTrue("冷 ViewModel 无预设数据：返回空列表而非崩溃", vm.getFilteredPresets().isEmpty())
    }
}
