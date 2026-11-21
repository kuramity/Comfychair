package sh.hnet.comfychair.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住 sampler / scheduler 的「提交前最后一道清洗」。
 *
 * 背景（真机证据）：ComfyUI 服务端日志里出现过
 * `Value not in list: scheduler: 'dpm++' not in ['simple', 'sgm_uniform', ...]`，
 * 即 App 把一个非法 scheduler 提交上去，被 validate_inputs() 判成 400。
 * 非法值来自持久化的脏数据，而下拉框的 selectedValue 会原样回显它 —— UI 层拦不住，
 * 只能靠 [SamplerOptions.sanitizeSampler] / [SamplerOptions.sanitizeScheduler] 收口。
 *
 * 这些断言刻意**不**写成恒真形式：把清洗函数改成恒等函数（`return value`）后，
 * 「非法值回退」的那几条会立刻变红（本会话已实测，见交付说明）。
 */
class SamplerOptionsSanitizeTest {

    // ---------- scheduler ----------

    @Test
    fun `非法 scheduler 回退到 normal`() {
        // 这条就是真机日志里的原值，也是最典型的脏数据
        assertEquals("normal", SamplerOptions.sanitizeScheduler("dpm++", emptyList()))
    }

    @Test
    fun `合法 scheduler 原样保留`() {
        assertEquals("karras", SamplerOptions.sanitizeScheduler("karras", emptyList()))
        // 内置清单里的每个值都必须幂等（清洗不能把合法值改掉）
        for (value in SamplerOptions.SCHEDULERS) {
            assertEquals("合法值 $value 不应被改动", value, SamplerOptions.sanitizeScheduler(value, emptyList()))
        }
    }

    @Test
    fun `空串与大小写变体都算非法`() {
        // 持久化里读出的空串已被 takeIf 过滤，但状态层仍可能残留空串 / 手工输入
        assertEquals("normal", SamplerOptions.sanitizeScheduler("", emptyList()))
        assertEquals("normal", SamplerOptions.sanitizeScheduler("Karras", emptyList()))
        assertEquals("normal", SamplerOptions.sanitizeScheduler(" karras", emptyList()))
    }

    @Test
    fun `服务器清单非空时以服务器清单为准`() {
        val serverSchedulers = listOf("simple", "shifted_tangent")
        // 服务器认识的、App 内置清单里没有的值必须放行（内置清单可能落后于服务端）
        assertEquals("shifted_tangent", SamplerOptions.sanitizeScheduler("shifted_tangent", serverSchedulers))
        // 服务器不认识的、哪怕内置清单里有，也必须回退（提交上去一定 400）
        assertEquals("normal", SamplerOptions.sanitizeScheduler("karras", serverSchedulers))
    }

    @Test
    fun `scheduler 默认值与内置清单第 7 项一致`() {
        // 注释与文档都写「默认返回 SCHEDULERS[6]（normal）」，这条把两处钉在一起
        assertEquals("normal", SamplerOptions.SCHEDULERS[6])
        assertEquals("normal", SamplerOptions.DEFAULT_SCHEDULER)
    }

    // ---------- sampler ----------

    @Test
    fun `合法 sampler 原样保留`() {
        assertEquals("euler", SamplerOptions.sanitizeSampler("euler", emptyList()))
        for (value in SamplerOptions.SAMPLERS) {
            assertEquals("合法值 $value 不应被改动", value, SamplerOptions.sanitizeSampler(value, emptyList()))
        }
    }

    @Test
    fun `非法 sampler 回退到 euler`() {
        assertEquals("euler", SamplerOptions.sanitizeSampler("dpm++", emptyList()))
        assertEquals("euler", SamplerOptions.sanitizeSampler("not_a_sampler", emptyList()))
        assertEquals("euler", SamplerOptions.sanitizeSampler("", emptyList()))
    }

    @Test
    fun `sampler 默认值与内置清单首项一致`() {
        assertEquals("euler", SamplerOptions.SAMPLERS.first())
        assertEquals("euler", SamplerOptions.DEFAULT_SAMPLER)
    }

    @Test
    fun `服务器清单非空时以服务器清单为准（sampler）`() {
        val serverSamplers = listOf("euler", "dpmpp_4m_sde_cfg_pp")
        assertEquals("dpmpp_4m_sde_cfg_pp", SamplerOptions.sanitizeSampler("dpmpp_4m_sde_cfg_pp", serverSamplers))
        assertEquals("euler", SamplerOptions.sanitizeSampler("lms", serverSamplers))
    }

    @Test
    fun `清洗不会把非法值悄悄改成另一个非法值`() {
        // 反证的另一面：回退结果必须自己也在清单里（否则「清洗」只是换了个 400）
        val fallbackSampler = SamplerOptions.sanitizeSampler("dpm++", emptyList())
        val fallbackScheduler = SamplerOptions.sanitizeScheduler("dpm++", emptyList())
        assertTrue(fallbackSampler in SamplerOptions.SAMPLERS)
        assertTrue(fallbackScheduler in SamplerOptions.SCHEDULERS)
        // 回退值与非法原值必须不同（否则上面几条断言可能是"恰好撞上"）
        assertNotEquals("dpm++", fallbackScheduler)
    }

    // ---------- 读取口径（清单未知不判废，见 sanitize*OnLoad） ----------

    @Test
    fun `读取时清单未知就不判废`() {
        // 冷启动时 /object_info 还没回来，唯一可用的参照是内置清单，而它可能落后于服务端
        // （真机日志里服务器认 shifted_tangent，内置清单没有）。此时判废 + 写回会把用户
        // 合法的选择永久改掉，所以读取阶段选择"不判断"，交给提交前的严格清洗兜底
        assertEquals("shifted_tangent", SamplerOptions.sanitizeSchedulerOnLoad("shifted_tangent", emptyList()))
        assertEquals(
            "dpmpp_4m_sde_cfg_pp",
            SamplerOptions.sanitizeSamplerOnLoad("dpmpp_4m_sde_cfg_pp", emptyList())
        )
        // 脏数据在清单未知时同样原样放行 —— 这条同时说明"安全网不在读取阶段"
        assertEquals("dpm++", SamplerOptions.sanitizeSchedulerOnLoad("dpm++", emptyList()))
    }

    @Test
    fun `读取时清单已知就严格判废`() {
        // 反证：读取口径如果退化成恒等函数，这三条会红 —— 脏数据永远不会被自愈
        assertEquals("normal", SamplerOptions.sanitizeSchedulerOnLoad("dpm++", listOf("simple", "karras")))
        assertEquals("normal", SamplerOptions.sanitizeSchedulerOnLoad("karras", listOf("simple")))
        assertEquals("euler", SamplerOptions.sanitizeSamplerOnLoad("dpm++", listOf("euler", "lms")))
    }
}