package sh.hnet.comfychair.ui.util

/**
 * Lazy 列表项 + 由业务数据派生的稳定 key。
 *
 * Lazy 列表按 key 复用行槽位：插入、删除、重排后 key 不变的行才会沿用原来的行状态
 * （滚动锚点、展开态、动画进度）。若拿列表下标当 key，数据一变下标就指向另一条数据，
 * 行状态会串行错位。
 */
data class KeyedItem<T>(val key: String, val value: T)

/**
 * 给列表里的每条数据生成稳定且唯一的 key。
 *
 * [basis] 取业务标识（版本号、段落原文、日志正文等），**不掺列表下标**：
 * 同一标识在列表里出现多次时，按出现顺序追加 `#dupN` 后缀（第一次出现不加后缀），
 * 这样既能保证同一列表内 key 不重复，又保证 key 只由数据内容决定。
 *
 * 例：`listOf("A", "B", "A").withStableKeys { it }` → `A`、`B`、`A#dup2`。
 *
 * 注意：重复项改用序号消歧后，若从列表头部删掉一条重复项，剩余重复项的序号会前移
 * （`A#dup2` 变回 `A`），该行会被当作新行使一次状态重建——对纯展示行无影响，
 * 这也是"绝不撞 key"必须付出的代价。
 */
fun <T> List<T>.withStableKeys(basis: (T) -> String): List<KeyedItem<T>> {
    if (isEmpty()) return emptyList()
    val occurrences = HashMap<String, Int>(size)
    return map { item ->
        val raw = basis(item)
        val n = (occurrences[raw] ?: 0) + 1
        occurrences[raw] = n
        KeyedItem(key = if (n == 1) raw else "$raw#dup$n", value = item)
    }
}
