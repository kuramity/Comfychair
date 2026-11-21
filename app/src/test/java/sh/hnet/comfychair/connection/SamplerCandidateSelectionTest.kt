package sh.hnet.comfychair.connection

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.workflow.NodeTypeRegistry
import sh.hnet.comfychair.workflow.TemplateKeyRegistry

/**
 * 钉住「采样器 / 调度器候选从哪个节点取」的优先级。
 *
 * 背景（用户报）：下拉里混进了 `dpm++` / `GITS[coeff=1.2]` / `OSS FLUX` / `AYS SD1`
 * 这类自定义采样节点的取值 —— 列表里"合法"，选完提交给原生 KSampler 就 400
 * （`Value not in list`）。根因是 `getOptionsForField` 按**字段名**跨所有节点求并集，
 * 而 `sampler_name` / `scheduler` 是通用字段名，GITS / OSS FLUX / ays 等节点也声明了它们。
 *
 * 现在的优先级（[selectNodeTypedOptions]）：KSampler → KSamplerAdvanced → 并集兜底。
 *
 * 反证（两个方向都钉住）：
 * - 把 [selectNodeTypedOptions] 改成恒用并集 → 「KSampler 有候选时只用它」必红
 *   （并集含 `dpm++`）；
 * - 改成恒用 primary → 「都空时退回并集」必红（会返回空表，下拉全空）。
 */
class SamplerCandidateSelectionTest {

    private val samplerKey = TemplateKeyRegistry.getJsonKeyForPlaceholder("sampler_name")
    private val schedulerKey = TemplateKeyRegistry.getJsonKeyForPlaceholder("scheduler")

    // ───────────────────────── 纯函数：优先级本身 ─────────────────────────

    @Test
    fun `KSampler 有候选时只用它`() {
        val ks = listOf("euler", "dpmpp_2m")
        val advanced = listOf("euler_ancestral")
        val all = listOf("AYS SD1", "GITS[coeff=1.2]", "OSS FLUX", "dpm++", "dpmpp_2m", "euler")

        val picked = selectNodeTypedOptions(ks, advanced, all)

        assertEquals("必须原样使用 KSampler 的候选（含顺序）", ks, picked)
        assertFalse("并集里的自定义值不能漏进来", picked.contains("dpm++"))
        assertTrue("前提校验：并集里确实有脏值，否则本条断言没有意义", all.contains("dpm++"))
    }

    @Test
    fun `KSampler 为空时用 KSamplerAdvanced`() {
        val advanced = listOf("euler", "dpmpp_2m_sde")
        val all = listOf("dpm++", "euler", "dpmpp_2m_sde")

        val picked = selectNodeTypedOptions(emptyList(), advanced, all)

        assertEquals(advanced, picked)
        assertFalse(picked.contains("dpm++"))
    }

    @Test
    fun `两个原生节点都为空时退回并集`() {
        val all = listOf("AYS SD1", "GITS[coeff=1.2]", "OSS FLUX", "dpm++")

        val picked = selectNodeTypedOptions(emptyList(), emptyList(), all)

        // 没有原生采样节点可参照时，宁可多给候选也不要下拉全空（用户连选都选不了）
        assertEquals(all, picked)
    }

    // ───────────────────── 与真实注册表串联：登记表语义 ─────────────────────

    @Test
    fun `真实 KSampler 样本加上自定义节点后不再混入自定义值`() {
        // 真机 /object_info 抽出来的 8 个节点（含 KSampler 的完整候选），再手工并进一个
        // 声明了同名 sampler_name / scheduler 的自定义采样节点 —— 复现用户看到的混入。
        val objectInfo = loadSample().apply {
            put("GITS_Sampler", CUSTOM_SAMPLER_NODE)
        }
        val registry = NodeTypeRegistry().also { it.parseObjectInfo(objectInfo) }

        val allSamplers = registry.getOptionsForField(samplerKey)
        val allSchedulers = registry.getOptionsForField(schedulerKey)
        // 前提校验：按字段名取并集时，自定义值确实混进来了（这就是本次要修的 bug）
        assertTrue("前提校验：并集里应混入 dpm++（实际 ${allSamplers.size} 项）", allSamplers.contains("dpm++"))
        assertTrue("前提校验：并集里应混入 OSS FLUX", allSchedulers.contains("OSS FLUX"))

        val samplers = selectNodeTypedOptions(
            primary = registry.getOptionsForNodeInput("KSampler", samplerKey),
            secondary = registry.getOptionsForNodeInput("KSamplerAdvanced", samplerKey),
            fallback = allSamplers
        )
        val schedulers = selectNodeTypedOptions(
            primary = registry.getOptionsForNodeInput("KSampler", schedulerKey),
            secondary = registry.getOptionsForNodeInput("KSamplerAdvanced", schedulerKey),
            fallback = allSchedulers
        )

        // 改后拿到的就是 KSampler 自己那一份：真实样本里 40+ 个采样器、9 个调度器
        assertEquals(registry.getOptionsForNodeInput("KSampler", samplerKey), samplers)
        assertTrue("KSampler 候选应当有几十项（实际 ${samplers.size} 项）", samplers.size >= 30)
        assertEquals(registry.getOptionsForNodeInput("KSampler", schedulerKey), schedulers)
        assertTrue("KSampler 调度器候选不应为空（实际 ${schedulers.size} 项）", schedulers.size >= 5)

        // 用户报的那几个值一个都不能留
        for (dirty in listOf("dpm++", "GITS[coeff=1.2]", "OSS FLUX", "AYS SD1")) {
            assertFalse("采样器候选不应含 $dirty", samplers.contains(dirty))
            assertFalse("调度器候选不应含 $dirty", schedulers.contains(dirty))
        }
        assertTrue("合法的原生值必须保留", samplers.contains("euler"))
        assertTrue("合法的原生值必须保留", schedulers.contains("normal"))
    }

    @Test
    fun `没有 KSampler 但只有 KSamplerAdvanced 时用 Advanced 的候选`() {
        val objectInfo = JSONObject(
            """
            {
              "KSamplerAdvanced": {
                "input": {
                  "required": {
                    "sampler_name": [["euler", "dpmpp_2m"], {}],
                    "scheduler": [["normal", "karras"], {}]
                  }
                },
                "output": ["LATENT"],
                "output_name": ["LATENT"]
              },
              "GITS_Sampler": {
                "input": {
                  "required": {
                    "sampler_name": [["dpm++"], {}],
                    "scheduler": [["OSS FLUX"], {}]
                  }
                },
                "output": ["LATENT"],
                "output_name": ["LATENT"]
              }
            }
            """.trimIndent()
        )
        val registry = NodeTypeRegistry().also { it.parseObjectInfo(objectInfo) }

        assertEquals("前提校验：没有 KSampler 时首选候选应为空", emptyList<String>(), registry.getOptionsForNodeInput("KSampler", samplerKey))

        val samplers = selectNodeTypedOptions(
            primary = registry.getOptionsForNodeInput("KSampler", samplerKey),
            secondary = registry.getOptionsForNodeInput("KSamplerAdvanced", samplerKey),
            fallback = registry.getOptionsForField(samplerKey)
        )
        assertEquals(listOf("euler", "dpmpp_2m"), samplers)
        assertFalse(samplers.contains("dpm++"))
    }

    @Test
    fun `完全没有任何原生采样节点时才退回并集`() {
        val objectInfo = JSONObject(
            """
            {
              "GITS_Sampler": {
                "input": {
                  "required": {
                    "sampler_name": [["dpm++", "GITS[coeff=1.2]"], {}],
                    "scheduler": [["OSS FLUX", "AYS SD1"], {}]
                  }
                },
                "output": ["LATENT"],
                "output_name": ["LATENT"]
              }
            }
            """.trimIndent()
        )
        val registry = NodeTypeRegistry().also { it.parseObjectInfo(objectInfo) }

        val samplers = selectNodeTypedOptions(
            primary = registry.getOptionsForNodeInput("KSampler", samplerKey),
            secondary = registry.getOptionsForNodeInput("KSamplerAdvanced", samplerKey),
            fallback = registry.getOptionsForField(samplerKey)
        )

        // 兜底路径：下拉不能是空的（否则这种工作流用户根本选不了采样器）
        assertEquals(setOf("dpm++", "GITS[coeff=1.2]"), samplers.toSet())
        assertEquals(2, samplers.size)
    }

    private fun loadSample(): JSONObject {
        val stream = javaClass.classLoader!!.getResourceAsStream("object_info_sample.json")
            ?: error("测试资源缺失: object_info_sample.json")
        return stream.use { JSONObject(it.readBytes().decodeToString()) }
    }

    private companion object {
        /**
         * 自定义采样节点的最小可解析定义（形状照 /object_info：input.required.<字段> = [选项数组, {元数据}]）。
         * 取值直接照用户报的脏值。
         */
        val CUSTOM_SAMPLER_NODE: JSONObject = JSONObject(
            """
            {
              "input": {
                "required": {
                  "sampler_name": [["dpm++", "GITS[coeff=1.2]"], {}],
                  "scheduler": [["OSS FLUX", "AYS SD1"], {}]
                }
              },
              "output": ["LATENT"],
              "output_name": ["LATENT"],
              "category": "sampling/custom_sampling"
            }
            """.trimIndent()
        )
    }
}