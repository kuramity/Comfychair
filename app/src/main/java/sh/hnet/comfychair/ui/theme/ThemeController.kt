package sh.hnet.comfychair.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import sh.hnet.comfychair.storage.AppSettings

/**
 * 自定义配色的全局单一事实来源。
 *
 * 为什么要有这个东西：App 里有 8 个 Activity，每个都在顶层调用 [ComfyChairTheme]。
 * 用户在调色页保存后返回主界面，主界面并不知情 —— 让 8 个 Activity 各自去读一遍
 * SharedPreferences 既啰嗦又容易漏。这里用一条 StateFlow 广播：
 * 调色页写入 → 所有 [ComfyChairTheme] 同时重组，变色立即在全站生效。
 *
 * 内存值与磁盘值始终同步：保存走 [apply]，两个一起更新。
 */
object ThemeController {

    private val _customization = MutableStateFlow<ThemeCustomization?>(null)

    /** null 表示未自定义，界面用原厂色板 */
    val customization: StateFlow<ThemeCustomization?> = _customization.asStateFlow()

    @Volatile
    private var restored = false

    /**
     * 从磁盘恢复一次（进程启动后的第一次调用生效，之后直接返回）。
     *
     * 之所以只恢复一次：进程存活期间 [customization] 就是权威值，
     * 反复从磁盘覆盖反而会把用户刚在调色页做的改动冲掉。
     */
    fun restore(context: Context) {
        if (restored) return
        restored = true
        val stored = AppSettings.getThemeCustomColors(context.applicationContext)
        _customization.value = stored?.let { colors ->
            ThemeCustomization(
                accent = colors.colorOrNull(0),
                base = colors.colorOrNull(1),
                text = colors.colorOrNull(2)
            )
        }?.takeIf { !it.isEmpty }
    }

    /** 保存并立即全站生效；全空（或 null）即「重置为原始配色」。 */
    fun apply(context: Context, customization: ThemeCustomization?) {
        restored = true
        val normalized = customization?.takeIf { !it.isEmpty }
        _customization.value = normalized
        AppSettings.setThemeCustomColors(
            context.applicationContext,
            normalized?.let {
                intArrayOf(
                    it.accent ?: AppSettings.UNSET_COLOR,
                    it.base ?: AppSettings.UNSET_COLOR,
                    it.text ?: AppSettings.UNSET_COLOR
                )
            }
        )
    }

    private fun IntArray.colorOrNull(index: Int): Int? =
        getOrNull(index)?.takeIf { it != AppSettings.UNSET_COLOR }
}
