package sh.hnet.comfychair.util

import sh.hnet.comfychair.model.LoraSelection

/**
 * Centralized LoRA chain management utilities.
 * Reduces duplicate LoRA chain logic across ViewModels.
 */
object LoraChainManager {

    /**
     * Add a new LoRA to the chain.
     *
     * 新项的 name 是**空串** = 下拉里的「（不使用 LoRA）」：必须由用户主动挑一个 LoRA
     * 才会被注入。旧实现取 `availableLoras.first()`，用户点了"添加"却什么都没选，
     * 就已经把一个自己没要的 LoRA 悄悄提交上去了。
     *
     * @param chain Current LoRA chain
     * @param availableLoras List of available LoRA models
     * @param maxSize Maximum chain size (default 5)
     * @return New chain with added LoRA, or original chain if at max size or no available LoRAs
     */
    fun addLora(
        chain: List<LoraSelection>,
        availableLoras: List<String>,
        maxSize: Int = LoraSelection.MAX_CHAIN_LENGTH
    ): List<LoraSelection> {
        if (chain.size >= maxSize) return chain
        if (availableLoras.isEmpty()) return chain

        val newLora = LoraSelection(
            name = "",
            strength = LoraSelection.DEFAULT_STRENGTH
        )
        return chain + newLora
    }

    /**
     * Remove a LoRA from the chain at the specified index.
     * @param chain Current LoRA chain
     * @param index Index of LoRA to remove
     * @return New chain with LoRA removed, or original chain if index is invalid
     */
    fun removeLora(chain: List<LoraSelection>, index: Int): List<LoraSelection> {
        if (index !in chain.indices) return chain
        return chain.toMutableList().apply { removeAt(index) }
    }

    /**
     * Update the name of a LoRA at the specified index.
     * 名字统一经 [LoraSelection.normalizeName] 归一化：下拉里的「（不使用 LoRA）」
     * 与历史遗留的 "None" 都会落成空串（= 该项不注入）。
     * @param chain Current LoRA chain
     * @param index Index of LoRA to update
     * @param name New LoRA name
     * @return New chain with updated LoRA, or original chain if index is invalid
     */
    fun updateLoraName(chain: List<LoraSelection>, index: Int, name: String): List<LoraSelection> {
        if (index !in chain.indices) return chain
        val normalized = LoraSelection.normalizeName(name)
        return chain.toMutableList().apply {
            this[index] = this[index].copy(name = normalized)
        }
    }

    /**
     * Update the strength of a LoRA at the specified index.
     * @param chain Current LoRA chain
     * @param index Index of LoRA to update
     * @param strength New strength value (will be clamped to valid range)
     * @return New chain with updated LoRA, or original chain if index is invalid
     */
    fun updateLoraStrength(chain: List<LoraSelection>, index: Int, strength: Float): List<LoraSelection> {
        if (index !in chain.indices) return chain
        val clampedStrength = strength.coerceIn(LoraSelection.MIN_STRENGTH, LoraSelection.MAX_STRENGTH)
        return chain.toMutableList().apply {
            this[index] = this[index].copy(strength = clampedStrength)
        }
    }

    /**
     * Filter out LoRAs that are no longer available.
     *
     * 保留还没选名字的空项（用户在链条里加了一行、"（不使用 LoRA）"待选）：
     * 空项被这里顺手删掉的话，用户刚加的行会在服务器列表刷新后凭空消失。
     *
     * @param chain Current LoRA chain
     * @param availableLoras List of currently available LoRA models
     * @return Filtered chain containing only available LoRAs (以及未选名的空项)
     */
    fun filterUnavailable(chain: List<LoraSelection>, availableLoras: List<String>): List<LoraSelection> {
        return chain.filter { it.name.isEmpty() || it.name in availableLoras }
    }
}
