package sh.hnet.comfychair.model

/**
 * 种子的取值方式。
 *
 * 取代原来的 `randomSeed: Boolean` 二态：加进"每次 +1 / 每次 −1"之后，
 * 随机与递增是**互斥的三种（四种）行为**，用两个布尔表达会出现"既随机又递增"这种
 * 非法组合，所以合成一个枚举。
 *
 * [RANDOM] 是默认值，与改动前 `randomSeed = true` 的默认行为一致。
 */
enum class SeedMode {
    /** 固定：每次都用输入框里那个种子 */
    FIXED,

    /** 随机：每次随机一个（输入框的值被忽略，界面上也禁用） */
    RANDOM,

    /** 每次生成后 +1（输入框的值是起点，生成后可见地往上走） */
    INCREMENT,

    /** 每次生成后 −1 */
    DECREMENT;

    /**
     * 提交这次生成时，要不要让引擎随机生成种子。
     *
     * 只有 [RANDOM] 是"随机"；[INCREMENT] / [DECREMENT] 提交时必须用输入框里的当前值
     * （否则每次都是随机数，"递增"就没有意义了）。
     */
    val usesRandomValue: Boolean get() = this == RANDOM

    /** 每次生成后是否要挪动种子 */
    val advances: Boolean get() = this == INCREMENT || this == DECREMENT

    /** 挪动的步长方向 */
    val step: Int get() = if (this == DECREMENT) -1 else 1

    /** 这个模式下种子输入框是否可编辑（随机模式禁用，其余三种都要能填/能看） */
    val isSeedEditable: Boolean get() = this != RANDOM

    companion object {
        /** 从持久化的名字还原；认不出来时退回 [RANDOM]（与改动前的默认一致） */
        fun fromName(raw: String?): SeedMode =
            entries.firstOrNull { it.name == raw } ?: RANDOM

        /**
         * 从已保存的设置里还原取值方式，兼容旧数据。
         *
         * v0.9.3 之前只存了一个布尔 `randomSeed`：true = 随机、false = 固定。
         * 那时没有递增递减可言，所以老值只能映射到这两档；而"两个键都没有"时
         * 退回 [RANDOM]（与改动前的默认 `randomSeed = true` 一致）。
         */
        fun fromSaved(seedModeName: String?, legacyRandomSeed: Boolean?): SeedMode = when {
            seedModeName != null -> fromName(seedModeName)
            legacyRandomSeed == false -> FIXED
            else -> RANDOM
        }
    }
}

/**
 * 按模式算出"下一次该用的种子"。
 *
 * 抽成纯函数是为了能在 JVM 单测里钉住边界行为（回绕、下限停留），
 * 不必去构造整个 ViewModel。
 *
 * 规则：
 * - [+1][SeedMode.INCREMENT] 超过上限 → 回绕到下限（种子取值范围是个圈，WebUI 也是这么走的）
 * - [-1][SeedMode.DECREMENT] 低于下限 → **停在下限**（不回绕到上限：上限值来自 /object_info 的
 *   Float 精度，回绕过去可能正好落在一个超出节点的值上，服务器直接 400）
 * - [min] / [max] 之外的输入先被夹进区间，保证返回值总是合法的
 */
fun nextSeed(current: Long, step: Int, min: Long, max: Long): Long {
    if (max < min) return current.coerceAtLeast(min)
    val base = current.coerceIn(min, max)
    val next = base + step
    return when {
        next > max -> min
        next < min -> min
        else -> next
    }
}
