package sh.hnet.comfychair.taglibrary.model

import androidx.annotation.StringRes
import sh.hnet.comfychair.R

/**
 * Danbooru 标签分类。
 *
 * 与数据库 tags 表的 category 字段一一对应：
 * - 0 通用（GENERAL）
 * - 1 画师（ARTIST）
 * - 3 作品（COPYRIGHT）
 * - 4 角色（CHARACTER）
 * - 5 元标签（META，如 highres / absurdres）
 *
 * 注意：数据库中没有 category = 2（Danbooru 官方已废弃该分类），
 * 因此解析时遇到未知值统一归入 GENERAL。
 */
enum class TagCategory(val code: Int, @StringRes val labelRes: Int) {
    GENERAL(0, R.string.tag_category_general),
    ARTIST(1, R.string.tag_category_artist),
    COPYRIGHT(3, R.string.tag_category_copyright),
    CHARACTER(4, R.string.tag_category_character),
    META(5, R.string.tag_category_meta);

    companion object {
        /** 按数据库 category 数值解析（未知值回落到通用）。 */
        fun fromCode(code: Int): TagCategory =
            entries.firstOrNull { it.code == code } ?: GENERAL
    }
}
