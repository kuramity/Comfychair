package sh.hnet.comfychair.navigation

import android.net.Uri

/**
 * Navigation routes for the main container (generation screens).
 * FAB: Gallery
 */
sealed class MainRoute(val route: String) {
    data object TextToImage : MainRoute("text_to_image")
    data object ImageToImage : MainRoute("image_to_image")
    data object TextToVideo : MainRoute("text_to_video")
    data object ImageToVideo : MainRoute("image_to_video")
    data object Gallery : MainRoute("gallery")

    /** 标签库（独立 Tab，需求 3.1）。 */
    data object TagLibrary : MainRoute("tag_library")

    /** 标签详情（二级页面，需求 3.3）。 */
    data object TagDetail : MainRoute("tag_detail/{tagName}") {
        /** 参数名（供 NavHost 取值）。 */
        const val ARG_TAG_NAME = "tagName"

        /** 构造详情页路由（标签名可能含 `/` 等特殊字符，必须编码）。 */
        fun createRoute(tagName: String): String = "tag_detail/${Uri.encode(tagName)}"
    }
}

/**
 * Navigation routes for the settings container.
 * FAB: Back to Generation
 */
sealed class SettingsRoute(val route: String) {
    data object Workflows : SettingsRoute("workflows_settings")
    data object Application : SettingsRoute("application_settings")
    data object Server : SettingsRoute("server_settings")
    data object About : SettingsRoute("about_settings")

    /**
     * 外观配色（调色盘）。
     *
     * 二级页面：从「应用设置」里的外观卡片进入，刻意不放进底部导航 ——
     * 底部那四项是内容分区，配色属于设置项，混进去会被当成一级功能。
     */
    data object Appearance : SettingsRoute("appearance_settings")

    /**
     * 模型介绍浏览页。
     *
     * 进底部导航（一级页面）：它和「工作流」一样是内容分区 —— 一个用来"看"的独立功能，
     * 不是某个设置项的子页面，所以按上面那条原则它该有自己的一格。
     */
    data object ModelIntro : SettingsRoute("model_intro_settings")
}
