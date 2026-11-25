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

    /**
     * 模型介绍浏览页。
     *
     * 入口在主容器底栏（标签库右边、图库 FAB 左边），与生成页共用导航图和底栏。
     * 路由字符串保持不变，详情页 / 隐藏页 / 编辑器均在同一返回栈内导航。
     */
    data object ModelIntro : MainRoute("model_intro_settings")

    /**
     * 模型介绍详情页（v4.17，二级页面）：在介绍列表点条目直接进入。
     *
     * 参数是介绍条目的 file 键（表的主键，稳定），模型文件名可能含中文/空格/`:` 等，
     * 必须编码。
     */
    data object ModelIntroDetail : MainRoute("model_intro_detail/{file}") {
        /** 参数名（供 NavHost 取值）。 */
        const val ARG_FILE = "file"

        /** 构造详情页路由（文件名可能含 `/` 等特殊字符，必须编码）。 */
        fun createRoute(file: String): String = "model_intro_detail/${Uri.encode(file)}"
    }

    /**
     * 已卸载的模型介绍（v4.21，二级页面）：从介绍页顶栏菜单的「已卸载（N）」进入。
     *
     * 卸载只是本地隐藏（写用户覆盖层，见 [sh.hnet.comfychair.model.ModelIntroOverrides]），
     * 这个页面用来逐条恢复；恢复后回到介绍页，条目立即重新出现。
     */
    data object ModelIntroHidden : MainRoute("model_intro_hidden")

    /**
     * 介绍文档编辑器（v4.21，二级页面）：新建 / 编辑一条介绍。
     *
     * **可选参数** `file`：有值 = 编辑该条目（从列表长按菜单「编辑这条」进入），
     * 无值 = 新建（从列表顶栏「＋」进入，先选模板）。文件名可能含中文/空格/`:`，
     * 与 [ModelIntroDetail] 同一套 `Uri.encode` 纪律；新建路径直接用 [route] 本身。
     */
    data object ModelIntroEditor : MainRoute("model_intro_editor?file={file}") {
        /** 参数名（供 NavHost 取值）。 */
        const val ARG_FILE = "file"

        /** 新建模式的路由（不带参数）。 */
        const val ROUTE_NEW = "model_intro_editor"

        /** 构造编辑模式路由（文件名必须编码，见 [ModelIntroDetail.createRoute] 同一理由）。 */
        fun createRoute(file: String): String = "model_intro_editor?file=${Uri.encode(file)}"
    }
}

/** 主容器内介绍模块的全部路由（含列表、详情、隐藏页与编辑器）。 */
internal val MODEL_INTRO_ROUTES: Set<String> = setOf(
    MainRoute.ModelIntro.route,
    MainRoute.ModelIntroDetail.route,
    MainRoute.ModelIntroHidden.route,
    MainRoute.ModelIntroEditor.route,
)

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
     * 更新日志（离线只读 assets/CHANGELOG.md）。
     *
     * 二级页面：从「关于」页的更新日志卡片进入，不进底部导航 ——
     * 它是关于页的一个信息入口，不是内容分区，与「外观」同理。
     */
    data object Changelog : SettingsRoute("changelog_settings")
}
