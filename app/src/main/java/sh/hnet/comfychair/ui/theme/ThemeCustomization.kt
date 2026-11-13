package sh.hnet.comfychair.ui.theme

import androidx.compose.runtime.Immutable

/**
 * 用户自定义配色：三个可选的基准色。
 *
 * **每一项都允许为 null，表示"这一项用户没动"**，此时对应的那组色槽完全走原厂色板。
 *
 * 为什么必须可空（这是踩过的坑）：初始实现把三项都当成必填、且默认值填的是深色模式的
 * 原厂色。用户在**浅色模式**下只改了强调色、另外两项没碰，保存后就把「深色模式的背景」
 * 当成了自定义值写进存储 —— 而浅色模式读到的含义变成"我要一个近乎全黑的底"，
 * 于是整个浅色主题被压成中灰。可空之后，"没动过"和"设成了原厂色"是两回事，
 * 只改一项就只影响一项。
 */
@Immutable
data class ThemeCustomization(
    /** 强调色：生成按钮、选中边框、选中态底色、链接 */
    val accent: Int? = null,
    /** 底色系：页面背景、卡片、面板、描边 */
    val base: Int? = null,
    /** 文字色：正文与次要说明文字 */
    val text: Int? = null
) {
    /** 三项都没设 = 完全使用原厂色板 */
    val isEmpty: Boolean
        get() = accent == null && base == null && text == null

    /** 取色器打开时的初始色：该项没设过就落在该模式的原厂色上 */
    fun colorOf(slot: ColorSlotKind, mode: ThemeMode): Int = when (slot) {
        ColorSlotKind.ACCENT ->
            accent ?: if (mode.dark) ThemePaletteTransform.DARK_ACCENT_ANCHOR else ThemePaletteTransform.LIGHT_ACCENT_ANCHOR
        ColorSlotKind.BASE ->
            base ?: if (mode.dark) ThemePaletteTransform.DARK_BASE_ANCHOR else ThemePaletteTransform.LIGHT_BASE_ANCHOR
        ColorSlotKind.TEXT ->
            text ?: if (mode.dark) ThemePaletteTransform.DARK_TEXT_ANCHOR else ThemePaletteTransform.LIGHT_TEXT_ANCHOR
    }

    /** 用户是否动过这一项 */
    fun isSet(slot: ColorSlotKind): Boolean = when (slot) {
        ColorSlotKind.ACCENT -> accent != null
        ColorSlotKind.BASE -> base != null
        ColorSlotKind.TEXT -> text != null
    }

    fun withColor(slot: ColorSlotKind, color: Int): ThemeCustomization = when (slot) {
        ColorSlotKind.ACCENT -> copy(accent = color)
        ColorSlotKind.BASE -> copy(base = color)
        ColorSlotKind.TEXT -> copy(text = color)
    }

    /** 把某一项拨回"没动过" */
    fun clearColor(slot: ColorSlotKind): ThemeCustomization = when (slot) {
        ColorSlotKind.ACCENT -> copy(accent = null)
        ColorSlotKind.BASE -> copy(base = null)
        ColorSlotKind.TEXT -> copy(text = null)
    }
}

/** 调色盘开放的三项 */
enum class ColorSlotKind { ACCENT, BASE, TEXT }
