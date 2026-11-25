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
 * 性能约束：精确/前缀匹配走 tag 主键索引；子串匹配（`%关键词%`）没有可用索引，
 * 只作为内存索引未就绪时的兜底，调用方必须限制关键词长度与返回条数。
 *
 * 线程安全约束（方案 A）：本类的构造函数捕获了 `SQLiteDatabase` 句柄，而
 * [TagDatabase.close] 可能随时被上层请求。因此**每一条** `rawQuery` 都必须在
 * [guardedQuery] 里执行 —— 进入前加引用计数、退出时在 finally 里减。这样
 * close 与查询之间有了互斥语义：close 落在查询中间时只会延迟到最后一个查询离开，
 * 落在查询之前则被门挡住，本类返回空结果而不是抛
 * `IllegalStateException: attempt to re-open an already-closed object`。
 * 新增查询点时忘了套 [guardedQuery] 就等于把这个互斥丢掉（单测
 * `TagCloseGuardWiringTest` 会钉住「不存在裸 rawQuery」）。
 */
class TagDao(private val db: SQLiteDatabase) {

    companion object {
        /** 默认返回上限（需求 6.3：如前 200 条）。 */
        const val DEFAULT_LIMIT = 200

        /** 内存索引预载热度阈值（需求 6.2：count > 100 的高频标签）。 */
        const val HOT_TAG_THRESHOLD = 100

        private const val COLUMNS = "tag, category, count, alias"
    }

    /**
     * 在引用计数保护下执行一次数据库访问（方案 A 的唯一入口）。
     *
     * @param onClosed 关闭已请求 / 句柄已关闭时返回的兜底值（正常路径用不到）
     * @param query    真正的查询，参数是同一个 [db] 句柄
     */
    private inline fun <R> guardedQuery(onClosed: () -> R, query: (SQLiteDatabase) -> R): R {
        if (!TagDatabase.acquire()) return onClosed()
        return try {
            query(db)
        } finally {
            TagDatabase.release()
        }
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
        return guardedQuery(onClosed = { emptyList() }) { database ->
            database.rawQuery(sql, arrayOf(keyword, limit.toString())).use { c ->
                c.toTagList()
            }
        }
    }

    /**
     * 前缀匹配标签名（走主键索引范围扫描，避免全表扫描）。
     *
     * 不用 `LIKE ? ESCAPE '\'`：SQLite 的 LIKE 索引优化在带 ESCAPE 子句时直接放弃
     * （官方限制），实测 170.7 万行上退化为 `SCAN tags` + 临时排序（约 140ms，
     * 手机上更慢）。库内标签名全部是小写（实测 `tag != lower(tag)` 为 0 行），
     * 因此把关键词转小写后用 `tag >= 前缀 AND tag < 前缀上界` 走主键自动索引
     * （实测 `SEARCH tags USING INDEX sqlite_autoindex_tags_1`，约 15ms），
     * 且范围比较是字面量语义，`%` / `_` 无需再转义。
     */
    fun searchByNamePrefix(keyword: String, limit: Int = DEFAULT_LIMIT): List<Tag> {
        val prefix = keyword.lowercase()
        if (prefix.isEmpty()) return emptyList()
        val sql = "SELECT $COLUMNS FROM tags WHERE tag >= ? AND tag < ? " +
                "ORDER BY count DESC LIMIT ?"
        return guardedQuery(onClosed = { emptyList() }) { database ->
            database.rawQuery(
                sql,
                arrayOf(prefix, prefixUpperBound(prefix), limit.toString())
            ).use { c -> c.toTagList() }
        }
    }

    /**
     * 前缀的排他上界：把最后一个字符加一（如 `ab` → `ac`），
     * 这样 `[前缀, 上界)` 恰好覆盖所有以前缀开头的字符串，不依赖字符编码细节。
     */
    private fun prefixUpperBound(prefix: String): String {
        val last = prefix.last()
        if (last == Char.MAX_VALUE) return prefix + Char.MAX_VALUE
        return prefix.dropLast(1) + (last + 1)
    }

    /**
     * 子串匹配标签名（无索引，代价高，调用方应限制数量或仅在高频区间使用）。
     */
    fun searchByNameContains(keyword: String, limit: Int = DEFAULT_LIMIT): List<Tag> {
        val sql = "SELECT $COLUMNS FROM tags WHERE tag LIKE ? ESCAPE '\\' " +
                "ORDER BY count DESC LIMIT ?"
        return guardedQuery(onClosed = { emptyList() }) { database ->
            database.rawQuery(sql, arrayOf("%" + escapeLike(keyword) + "%", limit.toString())).use { c ->
                c.toTagList()
            }
        }
    }

    /**
     * 别名包含匹配（英文/日文路径补充；别名不是独立列，无法走索引，故限制条数）。
     */
    fun searchByAliasContains(keyword: String, limit: Int = DEFAULT_LIMIT): List<Tag> {
        val sql = "SELECT $COLUMNS FROM tags WHERE alias LIKE ? ESCAPE '\\' " +
                "ORDER BY count DESC LIMIT ?"
        return guardedQuery(onClosed = { emptyList() }) { database ->
            database.rawQuery(sql, arrayOf("%" + escapeLike(keyword) + "%", limit.toString())).use { c ->
                c.toTagList()
            }
        }
    }

    /**
     * 批量按标签名取详情（搜索结果按别名命中置顶后回填用）。
     *
     * @param names 标签名列表（去重后长度需 > 0）
     */
    fun getByNames(names: List<String>): List<Tag> {
        // 空列表根本不打库，也就不需要占用引用计数
        if (names.isEmpty()) return emptyList()
        val placeholders = names.joinToString(",") { "?" }
        val sql = "SELECT $COLUMNS FROM tags WHERE tag IN ($placeholders)"
        return guardedQuery(onClosed = { emptyList() }) { database ->
            database.rawQuery(sql, names.toTypedArray()).use { c -> c.toTagList() }
        }
    }

    /**
     * 按分类浏览高频标签（供"未输入关键词"时的默认列表使用）。
     */
    fun loadHotTags(category: TagCategory? = null, limit: Int = DEFAULT_LIMIT): List<Tag> {
        // 一次引用覆盖整个方法：两个分支各自只跑一条 rawQuery，
        // 若在分支内部 acquire，分支选择逻辑就得复制两遍，更容易漏配对。
        return guardedQuery(onClosed = { emptyList() }) { database ->
            if (category == null) {
                val sql = "SELECT $COLUMNS FROM tags WHERE count > ? ORDER BY count DESC LIMIT ?"
                database.rawQuery(sql, arrayOf(HOT_TAG_THRESHOLD.toString(), limit.toString())).use { c ->
                    c.toTagList()
                }
            } else {
                val sql = "SELECT $COLUMNS FROM tags WHERE category = ? " +
                        "ORDER BY count DESC LIMIT ?"
                database.rawQuery(sql, arrayOf(category.code.toString(), limit.toString())).use { c ->
                    c.toTagList()
                }
            }
        }
    }

    /**
     * 预载高频标签（构建内存索引用）：count > 阈值，按热度降序。
     */
    fun loadTagsForMemoryIndex(): List<Tag> {
        val sql = "SELECT $COLUMNS FROM tags WHERE count > ? ORDER BY count DESC"
        return guardedQuery(onClosed = { emptyList() }) { database ->
            database.rawQuery(sql, arrayOf(HOT_TAG_THRESHOLD.toString())).use { c -> c.toTagList() }
        }
    }

    /**
     * 预载全部有别名标签（构建内存别名索引用）。**按热度降序**返回：
     *
     * 内存索引在这些行上顺序灌入中文子串条目，检索时命中上限一到就 break。
     * 若按 rowid（物理写入顺序）返回，截断结果会是「最先存进库的标签」而不是
     * 「最热门的标签」，同一个关键词在不同设备/不同库版本上给出的前几条会不一样，
     * 冷门标签也可能挤掉热门标签。排序后截断即等于「最热命中优先」。
     *
     * 排序代价：仅 70,977 行（实测 `alias != ''` 的行数），一次性建索引时约 230ms，
     * 相对整个索引构建（数秒）可忽略。
     */
    fun loadAliasRows(): List<Pair<String, String>> {
        val sql = "SELECT tag, alias FROM tags WHERE alias != '' ORDER BY count DESC"
        return guardedQuery(onClosed = { emptyList<Pair<String, String>>() }) { database ->
            database.rawQuery(sql, null).use { c ->
                val out = ArrayList<Pair<String, String>>(c.count)
                while (c.moveToNext()) out.add(c.getString(0) to c.getString(1))
                out
            }
        }
    }

    private fun Cursor.toTagList(): List<Tag> {
        val out = ArrayList<Tag>(count)
        while (moveToNext()) out.add(toTag())
        return out
    }
}
