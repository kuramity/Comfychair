package sh.hnet.comfychair.workflow.convert

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.workflow.InputDefinition
import sh.hnet.comfychair.workflow.NodeTypeDefinition
import sh.hnet.comfychair.workflow.OutputSlot

/**
 * rgthree GetNode/SetNode（变量节点）支持的单测。
 *
 * 这两个节点是纯前端 JS 实现，后端 /object_info 里查不到，不能产出 API 节点：
 * - SetNode 把一个值发布成具名变量（widgets_values[0]），值来自自己的上游连线或 widget 值；
 * - GetNode（widgets_values[0] = 变量名）的输出在语义上等于对应 SetNode 的输入。
 *
 * 转换器必须：消解所有指向 GetNode 的连线（改写到 SetNode 的上游或内联其 widget 字面量），
 * 丢弃 GetNode/SetNode 本体，并在变量缺失/来源缺失/字段塞不进字面量时留下显式 warning。
 */
class LiteGraphConverterRgthreeVarTest {

    /** KSamplerAdvanced 的近似 /object_info 定义（widget 顺序与真实节点一致） */
    private val ksamplerAdvancedDef = NodeTypeDefinition(
        classType = "KSamplerAdvanced",
        category = "sampling",
        inputs = listOf(
            InputDefinition("model", "MODEL", true),
            InputDefinition("add_noise", "BOOLEAN", true),
            InputDefinition("noise_seed", "INT", true),
            InputDefinition("steps", "INT", true),
            InputDefinition("cfg", "FLOAT", true),
            InputDefinition("sampler_name", "COMBO", true),
            InputDefinition("scheduler", "COMBO", true),
            InputDefinition("start_at_step", "INT", true),
            InputDefinition("end_at_step", "INT", true),
            InputDefinition("return_with_leftover_noise", "BOOLEAN", true)
        ),
        outputs = listOf(OutputSlot("LATENT", "LATENT"))
    )

    /** 只有 KSamplerAdvanced 有定义，其余节点 widget 映射会跳过，但连线消解照样执行 */
    private fun converter() = LiteGraphConverter { classType ->
        if (classType == "KSamplerAdvanced") ksamplerAdvancedDef else null
    }

    private fun nodesOf(result: LiteGraphConverter.ConversionResult): JSONObject =
        JSONObject(result.jsonContent).getJSONObject("nodes")

    private fun classTypesOf(nodes: JSONObject): Set<String> =
        nodes.keys().asSequence().map { nodes.getJSONObject(it).optString("class_type") }.toSet()

    // ================================================================
    // 基本消解：上游连线型 SetNode
    // ================================================================

    @Test
    fun `指向 GetNode 的连线消解到 SetNode 的上游节点`() {
        val graph = JSONObject(
            """
            {
              "nodes": [
                {"id": 1, "type": "CheckpointLoaderSimple", "inputs": [], "outputs": []},
                {"id": 2, "type": "SetNode", "widgets_values": ["MODEL"],
                 "inputs": [{"name": "MODEL", "type": "MODEL", "link": 1}], "outputs": []},
                {"id": 3, "type": "GetNode", "widgets_values": ["MODEL"], "inputs": [],
                 "outputs": [{"name": "MODEL", "type": "MODEL", "links": [2]}]},
                {"id": 4, "type": "KSamplerAdvanced", "inputs": [{"name": "model", "type": "MODEL", "link": 2}], "outputs": []}
              ],
              "links": [
                [1, 1, 0, 2, 0, "MODEL"],
                [2, 3, 0, 4, 0, "MODEL"]
              ]
            }
            """.trimIndent()
        )

        val result = converter().convert(graph)
        val nodes = nodesOf(result)

        assertFalse("SetNode 不应出现在输出里", nodes.has("2"))
        assertFalse("GetNode 不应出现在输出里", nodes.has("3"))
        val model = nodes.getJSONObject("4").getJSONObject("inputs").getJSONArray("model")
        assertEquals("消费者的输入源必须是 SetNode 的上游节点 1", "1", model.getString(0))
        assertEquals("源槽位必须是上游的输出槽 0", 0, model.getInt(1))
        assertTrue(
            "正常消解不应留下 rgthree 相关告警，实际：${result.warnings}",
            result.warnings.none { it.contains("rgthree") }
        )
    }

    @Test
    fun `直接以 SetNode 输出为源的连线也能消解`() {
        // rgthree 的 SetNode 自带一个 "*" 输出槽，个别工作流会直接从它连线
        val graph = JSONObject(
            """
            {
              "nodes": [
                {"id": 1, "type": "CheckpointLoaderSimple", "inputs": [], "outputs": []},
                {"id": 2, "type": "SetNode", "widgets_values": ["MODEL"],
                 "inputs": [{"name": "MODEL", "type": "MODEL", "link": 1}],
                 "outputs": [{"name": "*", "type": "*", "links": [3]}]},
                {"id": 4, "type": "KSamplerAdvanced", "inputs": [{"name": "model", "type": "MODEL", "link": 3}], "outputs": []}
              ],
              "links": [
                [1, 1, 0, 2, 0, "MODEL"],
                [3, 2, 0, 4, 0, "MODEL"]
              ]
            }
            """.trimIndent()
        )

        val result = converter().convert(graph)
        val nodes = nodesOf(result)

        assertFalse(nodes.has("2"))
        val model = nodes.getJSONObject("4").getJSONObject("inputs").getJSONArray("model")
        assertEquals("SetNode 是透传节点，源必须消解到它的上游 1", "1", model.getString(0))
    }

    // ================================================================
    // widget 值型 SetNode：字面量内联与拒绝
    // ================================================================

    @Test
    fun `SetNode 仅持 widget 值时内联字面量到 widget 字段`() {
        val graph = JSONObject(
            """
            {
              "nodes": [
                {"id": 2, "type": "SetNode", "widgets_values": ["STEPS", 20], "inputs": [], "outputs": []},
                {"id": 3, "type": "GetNode", "widgets_values": ["STEPS"], "inputs": [],
                 "outputs": [{"name": "STEPS", "type": "INT", "links": [2]}]},
                {"id": 4, "type": "KSamplerAdvanced", "inputs": [{"name": "steps", "type": "INT", "link": 2}], "outputs": []}
              ],
              "links": [
                [2, 3, 0, 4, 0, "INT"]
              ]
            }
            """.trimIndent()
        )

        val result = converter().convert(graph)
        val nodes = nodesOf(result)

        assertFalse(nodes.has("2"))
        assertFalse(nodes.has("3"))
        val inputs = nodes.getJSONObject("4").getJSONObject("inputs")
        assertEquals("widget 值 20 必须内联到消费者的 steps 字段", 20, inputs.getInt("steps"))
        assertTrue(result.warnings.none { it.contains("rgthree") })
    }

    @Test
    fun `SetNode widget 值无法塞进纯连接字段时断开并记录`() {
        val graph = JSONObject(
            """
            {
              "nodes": [
                {"id": 2, "type": "SetNode", "widgets_values": ["MODEL_BASE", "not-a-model"], "inputs": [], "outputs": []},
                {"id": 3, "type": "GetNode", "widgets_values": ["MODEL_BASE"], "inputs": [],
                 "outputs": [{"name": "MODEL", "type": "MODEL", "links": [2]}]},
                {"id": 4, "type": "KSamplerAdvanced", "inputs": [{"name": "model", "type": "MODEL", "link": 2}], "outputs": []}
              ],
              "links": [
                [2, 3, 0, 4, 0, "MODEL"]
              ]
            }
            """.trimIndent()
        )

        val result = converter().convert(graph)
        val nodes = nodesOf(result)

        val inputs = nodes.getJSONObject("4").getJSONObject("inputs")
        assertFalse("MODEL 是纯连接类型，字面量塞不进去，必须断开", inputs.has("model"))
        assertTrue(
            "必须留下显式 warning，实际：${result.warnings}",
            result.warnings.any { it.contains("cannot accept the rgthree SetNode widget value") }
        )
    }

    // ================================================================
    // 失败要显式
    // ================================================================

    @Test
    fun `GetNode 的变量名找不到 SetNode 时记录并断开`() {
        val graph = JSONObject(
            """
            {
              "nodes": [
                {"id": 2, "type": "GetNode", "widgets_values": ["GHOST"], "inputs": [],
                 "outputs": [{"name": "MODEL", "type": "MODEL", "links": [2]}]},
                {"id": 4, "type": "KSamplerAdvanced", "inputs": [{"name": "model", "type": "MODEL", "link": 2}], "outputs": []}
              ],
              "links": [
                [2, 2, 0, 4, 0, "MODEL"]
              ]
            }
            """.trimIndent()
        )

        val result = converter().convert(graph)
        val nodes = nodesOf(result)

        assertFalse(nodes.has("2"))
        assertFalse("找不到变量的连线必须断开", nodes.getJSONObject("4").getJSONObject("inputs").has("model"))
        assertTrue(
            "必须记录找不到 SetNode 的原因，实际：${result.warnings}",
            result.warnings.any { it.contains("no SetNode defines variable \"GHOST\"") }
        )
    }

    @Test
    fun `SetNode 值来源既非连线也非 widget 值时记录并断开`() {
        val graph = JSONObject(
            """
            {
              "nodes": [
                {"id": 2, "type": "SetNode", "widgets_values": ["X"], "inputs": [], "outputs": []},
                {"id": 3, "type": "GetNode", "widgets_values": ["X"], "inputs": [],
                 "outputs": [{"name": "X", "type": "*", "links": [2]}]},
                {"id": 4, "type": "KSamplerAdvanced", "inputs": [{"name": "model", "type": "MODEL", "link": 2}], "outputs": []}
              ],
              "links": [
                [2, 3, 0, 4, 0, "*"]
              ]
            }
            """.trimIndent()
        )

        val result = converter().convert(graph)

        assertFalse(nodesOf(result).getJSONObject("4").getJSONObject("inputs").has("model"))
        assertTrue(
            "必须记录值来源缺失的原因，实际：${result.warnings}",
            result.warnings.any { it.contains("value source is neither an upstream link nor a widget value") }
        )
    }

    @Test
    fun `SetNode 变量链成环时断开并记录`() {
        // SetNode 2 的上游又是引用同一变量的 GetNode 3 → 成环
        val graph = JSONObject(
            """
            {
              "nodes": [
                {"id": 2, "type": "SetNode", "widgets_values": ["X"],
                 "inputs": [{"name": "X", "type": "MODEL", "link": 1}], "outputs": []},
                {"id": 3, "type": "GetNode", "widgets_values": ["X"], "inputs": [],
                 "outputs": [{"name": "X", "type": "MODEL", "links": [1, 2]}]},
                {"id": 4, "type": "KSamplerAdvanced", "inputs": [{"name": "model", "type": "MODEL", "link": 2}], "outputs": []}
              ],
              "links": [
                [1, 3, 0, 2, 0, "MODEL"],
                [2, 3, 0, 4, 0, "MODEL"]
              ]
            }
            """.trimIndent()
        )

        val result = converter().convert(graph)

        val inputs = nodesOf(result).getJSONObject("4").getJSONObject("inputs")
        assertFalse("成环的变量链必须断开而不是死循环", inputs.has("model"))
        assertTrue(
            "必须记录成环原因，实际：${result.warnings}",
            result.warnings.any { it.contains("SetNode chain forms a cycle") }
        )
    }

    // ================================================================
    // 跨变量链：SetNode 的值来源是另一个变量的 GetNode（两跳消解）
    // ================================================================

    @Test
    fun `跨变量链两跳消解后落到真实上游节点`() {
        // 链路：loader → SetNode(Y) → GetNode(Y) → SetNode(X) → GetNode(X) → consumer
        // SetNode(X) 的值来源是 GetNode(Y) 的连线，消费 X 要穿透两个 SetNode 两跳落到 loader
        val graph = JSONObject(
            """
            {
              "nodes": [
                {"id": 1, "type": "CheckpointLoaderSimple", "inputs": [], "outputs": []},
                {"id": 2, "type": "SetNode", "widgets_values": ["Y"],
                 "inputs": [{"name": "Y", "type": "MODEL", "link": 1}], "outputs": []},
                {"id": 3, "type": "GetNode", "widgets_values": ["Y"], "inputs": [],
                 "outputs": [{"name": "Y", "type": "MODEL", "links": [2]}]},
                {"id": 4, "type": "SetNode", "widgets_values": ["X"],
                 "inputs": [{"name": "X", "type": "MODEL", "link": 2}], "outputs": []},
                {"id": 5, "type": "GetNode", "widgets_values": ["X"], "inputs": [],
                 "outputs": [{"name": "X", "type": "MODEL", "links": [3]}]},
                {"id": 6, "type": "KSamplerAdvanced", "inputs": [{"name": "model", "type": "MODEL", "link": 3}], "outputs": []}
              ],
              "links": [
                [1, 1, 0, 2, 0, "MODEL"],
                [2, 3, 0, 4, 0, "MODEL"],
                [3, 5, 0, 6, 0, "MODEL"]
              ]
            }
            """.trimIndent()
        )

        val result = converter().convert(graph)
        val nodes = nodesOf(result)

        assertFalse("SetNode(Y) 不应出现在输出里", nodes.has("2"))
        assertFalse("GetNode(Y) 不应出现在输出里", nodes.has("3"))
        assertFalse("SetNode(X) 不应出现在输出里", nodes.has("4"))
        assertFalse("GetNode(X) 不应出现在输出里", nodes.has("5"))
        val model = nodes.getJSONObject("6").getJSONObject("inputs").getJSONArray("model")
        assertEquals("两跳跨变量链必须消解到 loader(#1)，而非任何 Get/Set 节点", "1", model.getString(0))
        assertEquals("源槽位必须是 loader 的输出槽 0", 0, model.getInt(1))
        assertTrue(
            "合法的跨变量长链不应被防环误断，实际：${result.warnings}",
            result.warnings.none { it.contains("rgthree") }
        )
    }

    @Test
    fun `跨变量成环_X与Y互相引用时被防环断开`() {
        // 与上一条同构，唯一区别：SetNode(Y) 的值来源改成 GetNode(X)，形成 X→Y→X 的真环
        val graph = JSONObject(
            """
            {
              "nodes": [
                {"id": 2, "type": "SetNode", "widgets_values": ["Y"],
                 "inputs": [{"name": "Y", "type": "MODEL", "link": 4}], "outputs": []},
                {"id": 3, "type": "GetNode", "widgets_values": ["Y"], "inputs": [],
                 "outputs": [{"name": "Y", "type": "MODEL", "links": [2]}]},
                {"id": 4, "type": "SetNode", "widgets_values": ["X"],
                 "inputs": [{"name": "X", "type": "MODEL", "link": 2}], "outputs": []},
                {"id": 5, "type": "GetNode", "widgets_values": ["X"], "inputs": [],
                 "outputs": [{"name": "X", "type": "MODEL", "links": [3, 4]}]},
                {"id": 6, "type": "KSamplerAdvanced", "inputs": [{"name": "model", "type": "MODEL", "link": 3}], "outputs": []}
              ],
              "links": [
                [2, 3, 0, 4, 0, "MODEL"],
                [3, 5, 0, 6, 0, "MODEL"],
                [4, 5, 0, 2, 0, "MODEL"]
              ]
            }
            """.trimIndent()
        )

        val result = converter().convert(graph)

        val inputs = nodesOf(result).getJSONObject("6").getJSONObject("inputs")
        assertFalse("跨变量真环必须断开而不是死循环", inputs.has("model"))
        assertTrue(
            "必须记录成环原因（证明防环区分得开合法长链与真环），实际：${result.warnings}",
            result.warnings.any { it.contains("SetNode chain forms a cycle") }
        )
    }

    // ================================================================
    // 变量映射规则
    // ================================================================

    @Test
    fun `同名变量被多个 SetNode 定义时取 id 最小的`() {
        val graph = JSONObject(
            """
            {
              "nodes": [
                {"id": 1, "type": "CheckpointLoaderSimple", "inputs": [], "outputs": []},
                {"id": 2, "type": "CheckpointLoaderSimple", "inputs": [], "outputs": []},
                {"id": 9, "type": "SetNode", "widgets_values": ["X"],
                 "inputs": [{"name": "X", "type": "MODEL", "link": 1}], "outputs": []},
                {"id": 5, "type": "SetNode", "widgets_values": ["X"],
                 "inputs": [{"name": "X", "type": "MODEL", "link": 2}], "outputs": []},
                {"id": 3, "type": "GetNode", "widgets_values": ["X"], "inputs": [],
                 "outputs": [{"name": "X", "type": "MODEL", "links": [3]}]},
                {"id": 4, "type": "KSamplerAdvanced", "inputs": [{"name": "model", "type": "MODEL", "link": 3}], "outputs": []}
              ],
              "links": [
                [1, 1, 0, 9, 0, "MODEL"],
                [2, 2, 0, 5, 0, "MODEL"],
                [3, 3, 0, 4, 0, "MODEL"]
              ]
            }
            """.trimIndent()
        )

        val result = converter().convert(graph)
        val nodes = nodesOf(result)

        assertFalse("两个 SetNode 都不应出现在输出里", nodes.has("5"))
        assertFalse(nodes.has("9"))
        val model = nodes.getJSONObject("4").getJSONObject("inputs").getJSONArray("model")
        assertEquals("必须解析到 id 最小的 SetNode(#5) 的上游节点 2", "2", model.getString(0))
    }

    @Test
    fun `没有任何 GetNode 引用的 SetNode 直接丢弃`() {
        val graph = JSONObject(
            """
            {
              "nodes": [
                {"id": 1, "type": "CheckpointLoaderSimple", "inputs": [], "outputs": []},
                {"id": 2, "type": "SetNode", "widgets_values": ["UNUSED"],
                 "inputs": [{"name": "UNUSED", "type": "MODEL", "link": 1}], "outputs": []}
              ],
              "links": [
                [1, 1, 0, 2, 0, "MODEL"]
              ]
            }
            """.trimIndent()
        )

        val result = converter().convert(graph)
        val nodes = nodesOf(result)

        assertFalse(nodes.has("2"))
        assertTrue("未引用的 SetNode 不产生任何告警", result.warnings.none { it.contains("rgthree") })
        assertEquals("节点 1 不受影响", "CheckpointLoaderSimple", nodes.getJSONObject("1").optString("class_type"))
    }

    // ================================================================
    // 与 Reroute 的组合
    // ================================================================

    @Test
    fun `经 Reroute 中转消费 GetNode 也能穿透消解`() {
        val graph = JSONObject(
            """
            {
              "nodes": [
                {"id": 1, "type": "CheckpointLoaderSimple", "inputs": [], "outputs": []},
                {"id": 2, "type": "SetNode", "widgets_values": ["X"],
                 "inputs": [{"name": "X", "type": "MODEL", "link": 1}], "outputs": []},
                {"id": 3, "type": "GetNode", "widgets_values": ["X"], "inputs": [],
                 "outputs": [{"name": "X", "type": "MODEL", "links": [3]}]},
                {"id": 4, "type": "Reroute", "inputs": [{"name": "", "type": "*", "link": 3}], "outputs": []},
                {"id": 5, "type": "KSamplerAdvanced", "inputs": [{"name": "model", "type": "MODEL", "link": 4}], "outputs": []}
              ],
              "links": [
                [1, 1, 0, 2, 0, "MODEL"],
                [3, 3, 0, 4, 0, "MODEL"],
                [4, 4, 0, 5, 0, "MODEL"]
              ]
            }
            """.trimIndent()
        )

        val result = converter().convert(graph)

        val model = nodesOf(result).getJSONObject("5").getJSONObject("inputs").getJSONArray("model")
        assertEquals("Reroute 链停在 GetNode 上时要继续按变量消解", "1", model.getString(0))
    }

    @Test
    fun `SetNode 的上游经过 Reroute 时穿透到真实源`() {
        val graph = JSONObject(
            """
            {
              "nodes": [
                {"id": 1, "type": "CheckpointLoaderSimple", "inputs": [], "outputs": []},
                {"id": 8, "type": "Reroute", "inputs": [{"name": "", "type": "*", "link": 1}], "outputs": []},
                {"id": 2, "type": "SetNode", "widgets_values": ["X"],
                 "inputs": [{"name": "X", "type": "MODEL", "link": 2}], "outputs": []},
                {"id": 3, "type": "GetNode", "widgets_values": ["X"], "inputs": [],
                 "outputs": [{"name": "X", "type": "MODEL", "links": [3]}]},
                {"id": 4, "type": "KSamplerAdvanced", "inputs": [{"name": "model", "type": "MODEL", "link": 3}], "outputs": []}
              ],
              "links": [
                [1, 1, 0, 8, 0, "MODEL"],
                [2, 8, 0, 2, 0, "MODEL"],
                [3, 3, 0, 4, 0, "MODEL"]
              ]
            }
            """.trimIndent()
        )

        val result = converter().convert(graph)

        val model = nodesOf(result).getJSONObject("4").getJSONObject("inputs").getJSONArray("model")
        assertEquals("SetNode 的上游是 Reroute 时要穿透到节点 1", "1", model.getString(0))
    }

    // ================================================================
    // 子图边界
    // ================================================================

    @Test
    fun `主图 GetNode 作为子图外部输入时消解到真实上游`() {
        val graph = JSONObject(
            """
            {
              "nodes": [
                {"id": 1, "type": "CheckpointLoaderSimple", "inputs": [], "outputs": []},
                {"id": 2, "type": "SetNode", "widgets_values": ["X"],
                 "inputs": [{"name": "X", "type": "MODEL", "link": 1}], "outputs": []},
                {"id": 3, "type": "GetNode", "widgets_values": ["X"], "inputs": [],
                 "outputs": [{"name": "X", "type": "MODEL", "links": [2]}]},
                {"id": 10, "type": "sg-a", "inputs": [{"name": "x", "type": "MODEL", "link": 2}], "outputs": []}
              ],
              "links": [
                [1, 1, 0, 2, 0, "MODEL"],
                [2, 3, 0, 10, 0, "MODEL"]
              ],
              "definitions": {
                "subgraphs": [
                  {
                    "id": "sg-a", "name": "A",
                    "inputNode": {"id": -10}, "outputNode": {"id": -20},
                    "inputs": [{"name": "x", "type": "MODEL", "linkIds": [11]}], "outputs": [],
                    "nodes": [{"id": 1, "type": "KSamplerAdvanced", "inputs": [{"name": "model", "type": "MODEL", "link": 11}], "outputs": []}],
                    "links": [
                      {"id": 11, "origin_id": -10, "origin_slot": 0, "target_id": 1, "target_slot": 0, "type": "MODEL"}
                    ]
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val result = converter().convert(graph)
        val nodes = nodesOf(result)

        val samplerId = nodes.keys().asSequence().first {
            nodes.getJSONObject(it).optString("class_type") == "KSamplerAdvanced"
        }
        val model = nodes.getJSONObject(samplerId).getJSONObject("inputs").getJSONArray("model")
        assertEquals("子图外部输入同样要消解 GetNode，指向 SetNode 的上游 1", "1", model.getString(0))
    }

    @Test
    fun `子图内的 GetNode 被丢弃且引用连线断开`() {
        val graph = JSONObject(
            """
            {
              "nodes": [{"id": 10, "type": "sg-a", "inputs": [], "outputs": []}],
              "links": [],
              "definitions": {
                "subgraphs": [
                  {
                    "id": "sg-a", "name": "A",
                    "inputNode": {"id": -10}, "outputNode": {"id": -20},
                    "inputs": [], "outputs": [],
                    "nodes": [
                      {"id": 1, "type": "GetNode", "widgets_values": ["X"], "inputs": [],
                       "outputs": [{"name": "X", "type": "MODEL", "links": [1]}]},
                      {"id": 2, "type": "KSamplerAdvanced", "inputs": [{"name": "model", "type": "MODEL", "link": 1}], "outputs": []}
                    ],
                    "links": [
                      {"id": 1, "origin_id": 1, "origin_slot": 0, "target_id": 2, "target_slot": 0, "type": "MODEL"}
                    ]
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val result = converter().convert(graph)
        val nodes = nodesOf(result)

        assertFalse("GetNode 不能出现在输出里", classTypesOf(nodes).contains("GetNode"))
        assertTrue(
            "子图内的 GetNode 暂不支持消解，必须显式记录，实际：${result.warnings}",
            result.warnings.any { it.contains("not supported inside subgraphs") }
        )
        val samplerId = nodes.keys().asSequence().first {
            nodes.getJSONObject(it).optString("class_type") == "KSamplerAdvanced"
        }
        assertFalse(
            "引用被丢弃 GetNode 的连线必须断开而不是留悬空引用",
            nodes.getJSONObject(samplerId).getJSONObject("inputs").has("model")
        )
    }
}
