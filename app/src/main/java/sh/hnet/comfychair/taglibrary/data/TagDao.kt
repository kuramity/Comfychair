package sh.hnet.comfychair.taglibrary.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import sh.hnet.comfychair.taglibrary.model.Tag
import sh.hnet.comfychair.taglibrary.model.TagCategory
import sh.hnet.comfychair.taglibrary.search.AliasParser

/**
 * 标签库查询接口（只读）。
 *
 * 安全约束：所有 SQL 结构固定，外部输入一律通过 `?` 占位符绑定，
 * 严禁字符串拼接；LIKE 通配符由 [escapeLike] 转义并配合 ESCAPE 子句，
 * 避免用户输入中的 % / _ 被当作通配符。
 *
 * 性能约束：所有查询走 (category, count) 复合索引或 tag 主键索引；
 * 单次返回条数由调用方限制（默认 200），避免大结果集。
 */
class TagDao(private val db: SQLiteDatabase) {

    companion object {
        /** 默认返回上限（需求 6.3：如前 200 条）。 */
        const val DEFAULT_LIMIT = 200

        /** 内存索引预载热度阈值（需求 6.2：count > 100 的高频标签）。 */
        const val HOT_TAG_THRESHOLD = 100

        private const val COLUMNS = "tag, category, count, alias"
    }

    /** LIKE 通配符转义（配合 SQL 中的 ESCAPE '\' 使用）。 */
    private fun escapeLike(input: String): String =
        input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** 游标 → 标签模型。 */
    private fun Cursor.toTag(): Tag {
        val aliasRaw = if (isNull(3)) null else getString(3)
        val aliases = AliasParser.parse(aliasRaw)
        return Tag(
            name = getString(0),
            category = TagCategory.fromCode(getInt(1)),
            count = getLong(2),
            zhName = AliasParser.pickChineseName(aliases),
            aliases = aliases
        )
    }

    /**
     * 精确匹配标签名（英文路径第一步，走主键索引，最快）。
     */
    fun searchByExactName(keyword: String, limit: Int = DEFAULT_LIMIT): List<Tag> {
        val sql = "SELECT $COLUMNS FROM tags WHERE tag = ? ORDER BY count DESC LIMIT ?"
        return db.rawQuery(sql, arrayOf(keyword, limit.toString())).use { c ->
            c.toTagList()
        }
    }

    /**
     * 前缀匹配标签名（走主键索引范围扫描，避免全表扫描）。
     */
    fun searchByNamePrefix(keyword: String, limit: Int = DEFAULT_LIMIT): List<Tag> {
        val sql = "SELECT $COLUMNS FROM tags WHERE tag LIKE ? ESCAPE '\\' " +
                "ORDER BY count DESC LIMIT ?"
        return db.rawQuery(sql, arrayOf(escapeLike(keyword) + "%", limit.toString())).use { c ->
            c.toTagList()
        }
    }

    /**
     * 子串匹配标签名（无索引，代价高，调用方应限制数量或仅在高频区间使用）。
     */
    fun searchByNameContains(keyword: String, limit: Int = DEFAULT_LIMIT): List<Tag> {
        val sql = "SELECT $COLUMNS FROM tags WHERE tag LIKE ? ESCAPE '\\' " +
                "ORDER BY count DESC LIMIT ?"
        return db.rawQuery(sql, arrayOf("%" + escapeLike(keyword) + "%", limit.toString())).use { c ->
            c.toTagList()
        }
    }

    /**
     * 别名包含匹配（英文/日文路径补充；别名不是独立列，无法走索引，故限制条数）。
     */
    fun searchByAliasContains(keyword: String, limit: Int = DEFAULT_LIMIT): List<Tag> {
        val sql = "SELECT $COLUMNS FROM tags WHERE alias LIKE ? ESCAPE '\\' " +
                "ORDER BY count DESC LIMIT ?"
        return db.rawQuery(sql, arrayOf("%" + escapeLike(keyword) + "%", limit.toString())).use { c ->
            c.toTagList()
        }
    }

    /**
     * 批量按标签名取详情（搜索结果按别名命中置顶后回填用）。
     *
     * @param names 标签名列表（去重后长度需 > 0）
     */
    fun getByNames(names: List<String>): List<Tag> {
        if (names.isEmpty()) return emptyList()
        val placeholders = names.joinToString(",") { "?" }
        val sql = "SELECT $COLUMNS FROM tags WHERE tag IN ($placeholders)"
        return db.rawQuery(sql, names.toTypedArray()).use { c -> c.toTagList() }
    }

    /**
     * 按分类浏览高频标签（供"未输入关键词"时的默认列表使用）。
     */
    fun loadHotTags(category: TagCategory? = null, limit: Int = DEFAULT_LIMIT): List<Tag> {
        return if (category == null) {
            val sql = "SELECT $COLUMNS FROM tags WHERE count > ? ORDER BY count DESC LIMIT ?"
            db.rawQuery(sql, arrayOf(HOT_TAG_THRESHOLD.toString(), limit.toString())).use { c ->
                c.toTagList()
            }
        } else {
            val sql = "SELECT $COLUMNS FROM tags WHERE category = ? " +
                    "ORDER BY count DESC LIMIT ?"
            db.rawQuery(sql, arrayOf(category.code.toString(), limit.toString())).use { c ->
                c.toTagList()
            }
        }
    }

    /**
     * 预载高频标签（构建内存索引用）：count > 阈值，按热度降序。
     */
    fun loadTagsForMemoryIndex(): List<Tag> {
        val sql = "SELECT $COLUMNS FROM tags WHERE count > ? ORDER BY count DESC"
        return db.rawQuery(sql, arrayOf(HOT_TAG_THRESHOLD.toString())).use { c -> c.toTagList() }
    }

    /**
     * 预载全部有别名标签（构建内存别名索引用）。
     * 仅取 tag + alias 两列，减少内存开销。
     */
    fun loadAliasRows(): List<Pair<String, String>> {
        val sql = "SELECT tag, alias FROM tags WHERE alias != ''"
        return db.rawQuery(sql, null).use { c ->
            val out = ArrayList<Pair<String, String>>(c.count)
            while (c.moveToNext()) out.add(c.getString(0) to c.getString(1))
            out
        }
    }

    private fun Cursor.toTagList(): List<Tag> {
        val out = ArrayList<Tag>(count)
        while (moveToNext()) out.add(toTag())
        return out
    }
}
