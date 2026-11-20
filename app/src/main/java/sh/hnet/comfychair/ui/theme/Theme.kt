package sh.hnet.comfychair.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ComfyChair 自定义配色（深空蓝灰 + 青蓝）
//
// 这里把 M3 的语义色槽全部填成自定义色板，页面里所有 MaterialTheme.colorScheme.* 的引用
// 就会自动跟着变，无需逐个页面改。色值定义与语义说明见 Color.kt。

/** 深色方案：深空蓝灰底 + 青蓝强调。
 *  可见性为 internal 而非 private：调色盘的色板映射测试要拿这套真实色板当基准，
 *  换用 M3 默认色板会与锚点错配（锚点是按本文件这套色板定的），测出来全是假失败。 */
internal val DarkColorScheme = darkColorScheme(
    // 强调色（按钮 / 选中）
    primary = DarkPalette.Primary,
    onPrimary = DarkPalette.OnPrimary,
    primaryContainer = DarkPalette.PrimaryContainer,
    onPrimaryContainer = DarkPalette.OnPrimaryContainer,
    inversePrimary = DarkPalette.InversePrimary,

    // 警告 / 进行中
    secondary = DarkPalette.Secondary,
    onSecondary = DarkPalette.OnSecondary,
    // 选中态容器刻意用青蓝浅底，让底部导航选中项等"选中"表现与强调色一致
    secondaryContainer = DarkPalette.SecondaryContainer,
    onSecondaryContainer = DarkPalette.OnSecondaryContainer,

    // 成功
    tertiary = DarkPalette.Tertiary,
    onTertiary = DarkPalette.OnTertiary,
    tertiaryContainer = DarkPalette.TertiaryContainer,
    onTertiaryContainer = DarkPalette.OnTertiaryContainer,

    // 背景与表面
    // 注意：Scaffold 默认 containerColor = background、contentColor = onBackground，
    // 也就是说 onBackground 是全站默认文字色，必须设置，否则会退回 M3 默认色。
    background = DarkPalette.Background,
    onBackground = DarkPalette.OnSurface,
    surface = DarkPalette.Surface,
    onSurface = DarkPalette.OnSurface,
    surfaceVariant = DarkPalette.SurfaceVariant,
    onSurfaceVariant = DarkPalette.OnSurfaceVariant,

    // 表面层级
    surfaceDim = DarkPalette.SurfaceDim,
    surfaceBright = DarkPalette.SurfaceBright,
    surfaceContainerLowest = DarkPalette.SurfaceLowest,
    surfaceContainerLow = DarkPalette.SurfaceContainerLow,
    surfaceContainer = DarkPalette.SurfaceContainer,
    surfaceContainerHigh = DarkPalette.SurfaceContainerHigh,
    surfaceContainerHighest = DarkPalette.SurfaceContainerHighest,

    // surfaceTint 只在有 tonalElevation 的地方生效（项目里仅 2~3dp 的三处），
    // 只影响颜色深浅、不产生阴影，与本项目"零阴影"的底子不冲突。
    surfaceTint = DarkPalette.Primary,

    // 描边与分割线
    outline = DarkPalette.Outline,
    outlineVariant = DarkPalette.OutlineVariant,
    scrim = DarkPalette.Scrim,

    // 错误 / 危险
    error = DarkPalette.Error,
    onError = DarkPalette.OnError,
    errorContainer = DarkPalette.ErrorContainer,
    onErrorContainer = DarkPalette.OnErrorContainer,

    // 反色（Snackbar 等）
    inverseSurface = DarkPalette.InverseSurface,
    inverseOnSurface = DarkPalette.InverseOnSurface,

    // 固定色
    primaryFixed = DarkPalette.PrimaryFixed,
    primaryFixedDim = DarkPalette.PrimaryFixedDim,
    onPrimaryFixed = DarkPalette.OnPrimaryFixed,
    onPrimaryFixedVariant = DarkPalette.OnPrimaryFixedVariant,
    secondaryFixed = DarkPalette.SecondaryFixed,
    secondaryFixedDim = DarkPalette.SecondaryFixedDim,
    onSecondaryFixed = DarkPalette.OnSecondaryFixed,
    onSecondaryFixedVariant = DarkPalette.OnSecondaryFixedVariant,
    tertiaryFixed = DarkPalette.TertiaryFixed,
    tertiaryFixedDim = DarkPalette.TertiaryFixedDim,
    onTertiaryFixed = DarkPalette.OnTertiaryFixed,
    onTertiaryFixedVariant = DarkPalette.OnTertiaryFixedVariant
)

/** 浅色方案：冷调灰白底 + 深青蓝强调（与深色同色相，明度反转） */
internal val LightColorScheme = lightColorScheme(
    // 强调色：浅色模式下压暗到 #0369A1，保证作为文字在浅底上可读
    primary = LightPalette.Primary,
    onPrimary = LightPalette.OnPrimary,
    primaryContainer = LightPalette.PrimaryContainer,
    onPrimaryContainer = LightPalette.OnPrimaryContainer,
    inversePrimary = LightPalette.InversePrimary,

    // 警告 / 进行中
    secondary = LightPalette.Secondary,
    onSecondary = LightPalette.OnSecondary,
    secondaryContainer = LightPalette.SecondaryContainer,
    onSecondaryContainer = LightPalette.OnSecondaryContainer,

    // 成功
    tertiary = LightPalette.Tertiary,
    onTertiary = LightPalette.OnTertiary,
    tertiaryContainer = LightPalette.TertiaryContainer,
    onTertiaryContainer = LightPalette.OnTertiaryContainer,

    // 背景与表面
    background = LightPalette.Background,
    onBackground = LightPalette.OnSurface,
    surface = LightPalette.Surface,
    onSurface = LightPalette.OnSurface,
    surfaceVariant = LightPalette.SurfaceVariant,
    onSurfaceVariant = LightPalette.OnSurfaceVariant,

    // 表面层级
    surfaceDim = LightPalette.SurfaceDim,
    surfaceBright = LightPalette.SurfaceBright,
    surfaceContainerLowest = LightPalette.SurfaceLowest,
    surfaceContainerLow = LightPalette.SurfaceContainerLow,
    surfaceContainer = LightPalette.SurfaceContainer,
    surfaceContainerHigh = LightPalette.SurfaceContainerHigh,
    surfaceContainerHighest = LightPalette.SurfaceContainerHighest,

    surfaceTint = LightPalette.Primary,

    // 描边与分割线
    outline = LightPalette.Outline,
    outlineVariant = LightPalette.OutlineVariant,
    scrim = LightPalette.Scrim,

    // 错误 / 危险
    error = LightPalette.Error,
    onError = LightPalette.OnError,
    errorContainer = LightPalette.ErrorContainer,
    onErrorContainer = LightPalette.OnErrorContainer,

    // 反色
    inverseSurface = LightPalette.InverseSurface,
    inverseOnSurface = LightPalette.InverseOnSurface,

    // 固定色
    primaryFixed = LightPalette.PrimaryFixed,
    primaryFixedDim = LightPalette.PrimaryFixedDim,
    onPrimaryFixed = LightPalette.OnPrimaryFixed,
    onPrimaryFixedVariant = LightPalette.OnPrimaryFixedVariant,
    secondaryFixed = LightPalette.SecondaryFixed,
    secondaryFixedDim = LightPalette.SecondaryFixedDim,
    onSecondaryFixed = LightPalette.OnSecondaryFixed,
    onSecondaryFixedVariant = LightPalette.OnSecondaryFixedVariant,
    tertiaryFixed = LightPalette.TertiaryFixed,
    tertiaryFixedDim = LightPalette.TertiaryFixedDim,
    onTertiaryFixed = LightPalette.OnTertiaryFixed,
    onTertiaryFixedVariant = LightPalette.OnTertiaryFixedVariant
)

@Composable
fun ComfyChairTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // 动态取色（Android 12+ 按壁纸取色）。默认关闭：
    // 一旦开启会整片覆盖上面的自定义配色，导致"改了主题却看不到变化"。
    // 需要时由调用方显式传 true 打开。
    dynamicColor: Boolean = false,
    // Override status bar appearance (null = auto based on theme, true = light icons, false = dark icons)
    forceDarkStatusBar: Boolean? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    // 从磁盘恢复用户自定义配色（进程内只做一次，之后由 ThemeController 广播维持）
    LaunchedEffect(Unit) { ThemeController.restore(context) }
    val customization by ThemeController.customization.collectAsStateWithLifecycle()

    // 用户调过色 → 在原色板基础上按规则重算；没调过就用原色板（少一次 HSL 往返，颜色更准）
    val themedScheme = remember(customization, darkTheme) {
        val baseScheme = if (darkTheme) DarkColorScheme else LightColorScheme
        val mode = if (darkTheme) ThemeMode.DARK else ThemeMode.LIGHT
        customization?.let { buildCustomColorScheme(baseScheme, it, mode) } ?: baseScheme
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> themedScheme
    }

    // Update status bar icon colors based on theme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val insetsController = WindowCompat.getInsetsController(window, view)
            // Light status bar = dark icons (for light theme)
            // Dark status bar = light icons (for dark theme)
            // forceDarkStatusBar overrides automatic behavior
            insetsController.isAppearanceLightStatusBars = when (forceDarkStatusBar) {
                true -> false  // Force light icons (dark status bar)
                false -> true  // Force dark icons (light status bar)
                null -> !darkTheme  // Auto based on theme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = Shapes,
        typography = Typography,
        content = content
    )
}
