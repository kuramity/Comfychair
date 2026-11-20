package sh.hnet.comfychair.workflow.convert

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-13 / P1-14：LiteGraph 导入的接线与子图展开。
 *
 * - P1-13：Reroute 节点在转换时被跳过，但连线曾经原样写成 `[rerouteId, slot]`，
 *   输出里指向一个不存在的节点，服务器报 node not found。必须沿 reroute 链上溯到真实源节点。
 * - P1-14：嵌套 subgraph 不展开时，内层 subgraph 的 UUID 会被当成 class_type 写进输出。
 *   必须递归展开，并对循环引用做检测（不能死循环）。
 *
 * 这些用例只依赖 org.json 与纯 Kotlin 逻辑，不碰 Android API。
 */
class LiteGraphConverterNestingTest {

    /** 没有节点定义：widget 映射会跳过，但连线/展开逻辑照样必须正确 */
    private fun converter() = LiteGraphConverter { _ -> null }

    @Test
    fun `指向 Reroute 的连线被上溯到真实源节点`() {
        val graph = JSONObject(
            """
            {
              "nodes": [
                {"id": 1, "type": "CheckpointLoaderSimple", "inputs": [], "outputs": []},
                {"id": 2, "type": "Reroute", "inputs": [{"name": "", "type": "*", "link": 1}], "outputs": []},
                {"id": 3, "type": "CLIPTextEncode", "inputs": [{"name": "clip", "type": "CLIP", "link": 2}], "outputs": []}
              ],
              "links": [
                [1, 1, 0, 2, 0, "CLIP"],
                [2, 2, 0, 3, 1, "CLIP"]
              ]
            }
            """.trimIndent()
        )

        val result = converter().convert(graph)
        val nodes = JSONObject(result.jsonContent).getJSONObject("nodes")

        assertFalse("Reroute 本身不应该出现在输出里", nodes.has("2"))
        val clipLink = nodes.getJSONObject("3").getJSONObject("inputs").getJSONArray("clip")
        assertEquals("源节点必须是真实节点 1（而不是 reroute 2）", "1", clipLink.getString(0))
        assertEquals("源槽位必须是真实节点的槽位 0", 0, clipLink.getInt(1))
    }

    @Test
    fun `多级 Reroute 链也能上溯到真实源节点`() {
        val graph = JSONObject(
            """
            {
              "nodes": [
                {"id": 1, "type": "CheckpointLoaderSimple", "inputs": [], "outputs": []},
                {"id": 2, "type": "Reroute", "inputs": [{"name": "", "type": "*", "link": 1}], "outputs": []},
                {"id": 3, "type": "Reroute", "inputs": [{"name": "", "type": "*", "link": 2}], "outputs": []},
                {"id": 4, "type": "CLIPTextEncode", "inputs": [{"name": "clip", "type": "CLIP", "link": 3}], "outputs": []}
              ],
              "links": [
                [1, 1, 0, 2, 0, "CLIP"],
                [2, 2, 0, 3, 0, "CLIP"],
                [3, 3, 0, 4, 1, "CLIP"]
              ]
            }
            """.trimIndent()
        )

        val result = converter().convert(graph)
        val nodes = JSONObject(result.jsonContent).getJSONObject("nodes")

        val clipLink = nodes.getJSONObject("4").getJSONObject("inputs").getJSONArray("clip")
        assertEquals("多级 reroute 也要上溯到 1", "1", clipLink.getString(0))
    }

    @Test
    fun `嵌套 subgraph 被递归展开且不留下 UUID 类名`() {
        val graph = JSONObject(
            """
            {
              "nodes": [{"id": 10, "type": "sg-outer", "inputs": [], "outputs": []}],
              "links": [],
              "definitions": {
                "subgraphs": [
                  {
                    "id": "sg-outer", "name": "Outer",
                    "inputNode": {"id": -10}, "outputNode": {"id": -20},
                    "inputs": [], "outputs": [], "links": [],
                    "nodes": [{"id": 1, "type": "sg-inner", "inputs": [], "outputs": []}]
                  },
                  {
                    "id": "sg-inner", "name": "Inner",
                    "inputNode": {"id": -10}, "outputNode": {"id": -20},
                    "inputs": [], "outputs": [], "links": [],
                    "nodes": [{"id": 1, "type": "CLIPTextEncode", "inputs": [], "widgets_values": ["hi"]}]
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val result = converter().convert(graph)
        val nodes = JSONObject(result.jsonContent).getJSONObject("nodes")

        val classTypes = nodes.keys().asSequence().map { nodes.getJSONObject(it).optString("class_type") }.toList()
        assertTrue("内层真实节点必须被展开出来，实际：$classTypes", classTypes.contains("CLIPTextEncode"))
        assertFalse("不允许把子图 UUID 当成 class_type", classTypes.contains("sg-inner"))
        assertFalse("不允许把子图 UUID 当成 class_type", classTypes.contains("sg-outer"))
    }

    @Test
    fun `父级连线以嵌套 subgraph 为源时换算到内部真实节点`() {
        // Outer 内部的 CLIPTextEncode 吃的是嵌套 subgraph（sg-inner）的输出。
        // sg-inner 自己不产出节点，所以这条连线必须换算成 Inner 里的真实节点。
        val graph = JSONObject(
            """
            {
              "nodes": [{"id": 10, "type": "sg-outer", "inputs": [], "outputs": []}],
              "links": [],
              "definitions": {
                "subgraphs": [
                  {
                    "id": "sg-outer", "name": "Outer",
                    "inputNode": {"id": -10}, "outputNode": {"id": -20},
                    "inputs": [], "outputs": [],
                    "nodes": [
                      {"id": 1, "type": "sg-inner", "inputs": [], "outputs": []},
                      {"id": 2, "type": "CLIPTextEncode", "inputs": [{"name": "clip", "type": "CLIP", "link": 20}], "outputs": []}
                    ],
                    "links": [
                      {"id": 20, "origin_id": 1, "origin_slot": 0, "target_id": 2, "target_slot": 1, "type": "CLIP"}
                    ]
                  },
                  {
                    "id": "sg-inner", "name": "Inner",
                    "inputNode": {"id": -10}, "outputNode": {"id": -20},
                    "inputs": [], "outputs": [],
                    "nodes": [{"id": 1, "type": "CheckpointLoaderSimple", "inputs": [], "outputs": []}],
                    "links": [
                      {"id": 1, "origin_id": 1, "origin_slot": 0, "target_id": -20, "target_slot": 0, "type": "MODEL"}
                    ]
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val result = converter().convert(graph)
        val nodes = JSONObject(result.jsonContent).getJSONObject("nodes")

        val encoderId = nodes.keys().asSequence().first {
            nodes.getJSONObject(it).optString("class_type") == "CLIPTextEncode"
        }
        val clip = nodes.getJSONObject(encoderId).getJSONObject("inputs").getJSONArray("clip")
        val sourceId = clip.getString(0)

        assertTrue(
            "连线源节点必须是真实存在的节点，实际指向 $sourceId，节点表：${nodes.keys().asSequence().toList()}",
            nodes.has(sourceId)
        )
        assertEquals(
            "源节点必须是 Inner 里的真实节点",
            "CheckpointLoaderSimple",
            nodes.getJSONObject(sourceId).optString("class_type")
        )
    }

    @Test
    fun `子图输出槽以嵌套 subgraph 为源时主图拿到真实节点`() {
        // Outer 的输出槽直接接在嵌套子图 sg-inner 上；主图节点消费该输出，
        // 最终必须指向 Inner 里的真实节点，而不是 sg-inner 的占位 id。
        val graph = JSONObject(
            """
            {
              "nodes": [
                {"id": 10, "type": "sg-outer", "inputs": [], "outputs": []},
                {"id": 20, "type": "CLIPTextEncode", "inputs": [{"name": "clip", "type": "CLIP", "link": 30}], "outputs": []}
              ],
              "links": [[30, 10, 0, 20, 1, "CLIP"]],
              "definitions": {
                "subgraphs": [
                  {
                    "id": "sg-outer", "name": "Outer",
                    "inputNode": {"id": -10}, "outputNode": {"id": -20},
                    "inputs": [], "outputs": [],
                    "nodes": [{"id": 1, "type": "sg-inner", "inputs": [], "outputs": []}],
                    "links": [
                      {"id": 1, "origin_id": 1, "origin_slot": 0, "target_id": -20, "target_slot": 0, "type": "MODEL"}
                    ]
                  },
                  {
                    "id": "sg-inner", "name": "Inner",
                    "inputNode": {"id": -10}, "outputNode": {"id": -20},
                    "inputs": [], "outputs": [],
                    "nodes": [{"id": 1, "type": "CheckpointLoaderSimple", "inputs": [], "outputs": []}],
                    "links": [
                      {"id": 1, "origin_id": 1, "origin_slot": 0, "target_id": -20, "target_slot": 0, "type": "MODEL"}
                    ]
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val result = converter().convert(graph)
        val nodes = JSONObject(result.jsonContent).getJSONObject("nodes")

        val clip = nodes.getJSONObject("20").getJSONObject("inputs").getJSONArray("clip")
        val sourceId = clip.getString(0)

        assertTrue(
            "主图连线必须指向真实节点，实际 $sourceId，节点表：${nodes.keys().asSequence().toList()}",
            nodes.has(sourceId)
        )
        assertEquals(
            "源节点必须是 Inner 里的真实节点",
            "CheckpointLoaderSimple",
            nodes.getJSONObject(sourceId).optString("class_type")
        )
    }

    @Test
    fun `数值型二元字面量即使命中占位 id 也不被当连线改写`() {
        // 构造：嵌套子图 sg-inner 在 Outer 里的占位 id 恰好是 11；
        // 同级的另一个节点有一个 2 元数值字面量输入 [11, 0]（形如连线但不是）。
        // 旧判据「长度 2 且首元素可解析为数字」会把它当作指向 sg-inner 的连线：
        // 找不到输出映射就删除，找得到就改写。这里断言它原样保留。
        val graph = JSONObject(
            """
            {
              "nodes": [
                {
                  "id": 10, "type": "sg-outer", "inputs": [], "outputs": [],
                  "widgets_values": [[11, 0]],
                  "properties": {"proxyWidgets": [["values", "vals"]]}
                }
              ],
              "links": [],
              "definitions": {
                "subgraphs": [
                  {
                    "id": "sg-outer", "name": "Outer",
                    "inputNode": {"id": -10}, "outputNode": {"id": -20},
                    "inputs": [{"name": "vals", "type": "INT", "linkIds": []}],
                    "outputs": [],
                    "nodes": [
                      {"id": 1, "type": "sg-inner", "inputs": [], "outputs": []},
                      {"id": 2, "type": "ListWidgetNode", "inputs": [{"name": "values", "type": "INT", "link": 1}], "outputs": []}
                    ],
                    "links": [
                      {"id": 1, "origin_id": -10, "origin_slot": 0, "target_id": 2, "target_slot": 0, "type": "INT"}
                    ]
                  },
                  {
                    "id": "sg-inner", "name": "Inner",
                    "inputNode": {"id": -10}, "outputNode": {"id": -20},
                    "inputs": [], "outputs": [],
                    "nodes": [{"id": 1, "type": "CheckpointLoaderSimple", "inputs": [], "outputs": []}],
                    "links": []
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val result = converter().convert(graph)
        val nodes = JSONObject(result.jsonContent).getJSONObject("nodes")

        val listNodeId = nodes.keys().asSequence().first {
            nodes.getJSONObject(it).optString("class_type") == "ListWidgetNode"
        }
        val inputs = nodes.getJSONObject(listNodeId).getJSONObject("inputs")

        assertTrue("字面量输入不能被当成连线删除", inputs.has("values"))
        val literal = inputs.getJSONArray("values")
        assertEquals("长度必须保持 2", 2, literal.length())
        assertEquals("首元素必须原样保留", 11, literal.getInt(0))
        assertEquals("次元素必须原样保留", 0, literal.getInt(1))
    }

    @Test
    fun `子图循环引用只跳过不死循环`() {
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
                    "inputs": [], "outputs": [], "links": [],
                    "nodes": [{"id": 1, "type": "sg-b", "inputs": [], "outputs": []}]
                  },
                  {
                    "id": "sg-b", "name": "B",
                    "inputNode": {"id": -10}, "outputNode": {"id": -20},
                    "inputs": [], "outputs": [], "links": [],
                    "nodes": [
                      {"id": 1, "type": "sg-a", "inputs": [], "outputs": []},
                      {"id": 2, "type": "CLIPTextEncode", "inputs": [], "widgets_values": ["hi"]}
                    ]
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val result = converter().convert(graph)
        val nodes = JSONObject(result.jsonContent).getJSONObject("nodes")

        assertTrue(
            "环必须被发现并留下 warning，实际：${result.warnings}",
            result.warnings.any { it.contains("循环引用") }
        )
        val classTypes = nodes.keys().asSequence().map { nodes.getJSONObject(it).optString("class_type") }.toList()
        assertTrue("非环的那一层仍然要展开", classTypes.contains("CLIPTextEncode"))
        assertFalse("环上的子图不能被当节点输出", classTypes.contains("sg-a"))
    }

    @Test
    fun `无上游输入的 Reroute 会断开连线而不是写出悬空引用`() {
        val graph = JSONObject(
            """
            {
              "nodes": [
                {"id": 2, "type": "Reroute", "inputs": [], "outputs": []},
                {"id": 3, "type": "CLIPTextEncode", "inputs": [{"name": "clip", "type": "CLIP", "link": 9}], "outputs": []}
              ],
              "links": [[9, 2, 0, 3, 1, "CLIP"]]
            }
            """.trimIndent()
        )

        val result = converter().convert(graph)
        val nodes = JSONObject(result.jsonContent).getJSONObject("nodes")

        val inputs = nodes.getJSONObject("3").getJSONObject("inputs")
        assertFalse("无法解析来源时不应写出悬空连线", inputs.has("clip"))
        assertNotNull(result.warnings.firstOrNull { it.contains("disconnected") })
    }
}
