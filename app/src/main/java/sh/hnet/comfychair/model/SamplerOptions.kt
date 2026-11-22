package sh.hnet.comfychair.model

object SamplerOptions {
    val SAMPLERS = listOf(
        "euler", "euler_cfg_pp", "euler_ancestral", "euler_ancestral_cfg_pp",
        "heun", "heunpp2", "dpm_2", "dpm_2_ancestral", "lms", "dpm_fast",
        "dpm_adaptive", "dpmpp_2s_ancestral", "dpmpp_2s_ancestral_cfg_pp",
        "dpmpp_sde", "dpmpp_sde_gpu", "dpmpp_2m", "dpmpp_2m_cfg_pp",
        "dpmpp_2m_sde", "dpmpp_2m_sde_gpu", "dpmpp_2m_sde_heun",
        "dpmpp_2m_sde_heun_gpu", "dpmpp_3m_sde", "dpmpp_3m_sde_gpu",
        "ddpm", "lcm", "ipndm", "ipndm_v", "deis", "res_multistep",
        "res_multistep_cfg_pp", "res_multistep_ancestral",
        "res_multistep_ancestral_cfg_pp", "gradient_estimation",
        "gradient_estimation_cfg_pp", "er_sde", "seeds_2", "seeds_3",
        "sa_solver", "sa_solver_pece"
    )

    val SCHEDULERS = listOf(
        "simple", "sgm_uniform", "karras", "exponential", "ddim_uniform",
        "beta", "normal", "linear_quadratic", "kl_optimal"
    )

    val UPSCALE_METHODS = listOf(
        "nearest-exact", "bilinear", "area", "bicubic", "lanczos"
    )

    /** 清洗失败时的 scheduler 兜底值（= `SCHEDULERS[6]`），与各页面 UiState / [GenerationParameters] 缺省一致。 */
    const val DEFAULT_SCHEDULER = "normal"

    /** 清洗失败时的 sampler 兜底值（= `SAMPLERS.first()`），与各页面 UiState / [GenerationParameters] 缺省一致。 */
    const val DEFAULT_SAMPLER = "euler"

    /**
     * 把 sampler 取值清洗成「服务器一定认识的值」（纯函数，可 JVM 单测）。
     *
     * 为什么需要：持久化里可能残留非法值（用户实测存了 `dpm++`，下拉框照样显示，
     * 一提交就被 ComfyUI 的 `validate_inputs()` 判成 `Value not in list` 返回 400）。
     * 非法值无法靠 UI 拦住 —— 下拉框的 `selectedValue` 是原样回显的。
     *
     * @param value 待清洗的取值（可能来自持久化 / 记忆状态）
     * @param available 服务器 `/object_info` 报的可用清单；**空表表示还没同步**，此时用内置 [SAMPLERS]
     * @return 合法则原样返回；否则优先回退 [DEFAULT_SAMPLER]（`euler`），清单里没有 euler
     *         时取清单第一个值（清单为空则原样返回，绝不引入新的非法值）
     */
    fun sanitizeSampler(value: String, available: List<String> = emptyList()): String {
        val valid = available.ifEmpty { SAMPLERS }
        if (value in valid) return value
        // 回退值本身也要在候选清单里确认（清单非空时即服务器清单）：服务器没有 euler 时，
        // 硬回退 euler 只会换来第二次 400 —— 此时退而取清单第一个值
        if (DEFAULT_SAMPLER in valid) return DEFAULT_SAMPLER
        // 清单为空（仅当内置清单被误改空的极端情形）无从清洗：保持原值不动，
        // 交给服务器报错 —— 绝不引入一个自己都不认识的新非法值
        return valid.firstOrNull() ?: value
    }

    /**
     * 把 scheduler 取值清洗成「服务器一定认识的值」（纯函数，可 JVM 单测）。
     *
     * 见 [sanitizeSampler] 的来由：`dpm++` 就是从这里漏进 KSampler 的
     * （服务端日志：`scheduler: 'dpm++' not in [...]`）。
     *
     * @param value 待清洗的取值
     * @param available 服务器报的可用清单；空表表示还没同步，此时用内置 [SCHEDULERS]
     * @return 合法则原样返回；否则优先回退 `normal`（与 UiState 缺省一致），清单里没有 normal
     *         时取清单第一个值（清单为空则原样返回）
     */
    fun sanitizeScheduler(value: String, available: List<String> = emptyList()): String {
        val valid = available.ifEmpty { SCHEDULERS }
        if (value in valid) return value
        // 与 [sanitizeSampler] 同口径：回退值 normal 不在服务器清单时，取清单第一个
        if (DEFAULT_SCHEDULER in valid) return DEFAULT_SCHEDULER
        return valid.firstOrNull() ?: value
    }

    /**
     * **读取持久化时**的 sampler 清洗：只有服务器清单已同步（`available` 非空）才判废。
     *
     * 与 [sanitizeSampler] 的区别在清单口径，理由是冷启动时序：载入持久化时
     * `/object_info` 往往还没回来（`modelCache` 为空），此时唯一可用的参照是内置 [SAMPLERS]。
     * 而内置清单**可能落后于服务端**（真机日志里服务器认 `shifted_tangent`，内置清单没有），
     * 拿它判废会把合法值误判成脏数据 —— 更糟的是这个函数配合的是"写回磁盘"，
     * 误判一次就永久把用户的选择改掉，用户每次冷启动都要重选一遍。
     *
     * 所以清单未知时**原样返回**：真正保证 400 不发生的是提交前的严格清洗
     * （[sanitizeSampler]，服务器清单为空时才退回内置清单），那里没有"误判后写回"的副作用。
     *
     * @param available 服务器 `/object_info` 报的可用清单；**空表 = 未知，不做判断**
     */
    fun sanitizeSamplerOnLoad(value: String, available: List<String>): String =
        if (available.isEmpty()) value else sanitizeSampler(value, available)

    /**
     * **读取持久化时**的 scheduler 清洗：只有服务器清单已同步（`available` 非空）才判废。
     *
     * 见 [sanitizeSamplerOnLoad] 的时序说明：清单未知时原样返回，
     * 避免把「服务器有、内置清单没有」的合法值（如 `shifted_tangent`）误清成 `normal` 并写回。
     */
    fun sanitizeSchedulerOnLoad(value: String, available: List<String>): String =
        if (available.isEmpty()) value else sanitizeScheduler(value, available)
}
