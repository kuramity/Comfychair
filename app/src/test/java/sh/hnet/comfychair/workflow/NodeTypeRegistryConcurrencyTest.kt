package sh.hnet.comfychair.workflow

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Q3-1（v4.16.5）并发解析回归测试。
 *
 * 根因（R2 取证已闭合）：parseObjectInfo 会被磁盘预填协程（IO）与网络回调
 * （OkHttp）并发调用。旧实现开头 `clear + 逐 put` 到**同一份**可变表且无任何
 * 同步原语，两个 parse 交错时互相清空对方刚写入的节点 → 表残缺 →
 * 「UNET 下拉空、VAE/CLIP 有值」（UNET 来源节点只有两三个，丢一个就全空）。
 *
 * 本测试用 8 线程 × 20 轮同时解析同一份放大样本，断言最终表与单线程基线
 * 完全一致、各模型选项非空。**反证**：把 parseObjectInfo 改回"先 clear 后
 * 直接 put 共享表"（去掉局部 map 原子替换），本测试必须变红——实测交错
 * 丢失节点后节点数 < 基线（见交付报告自测节）。
 */
class NodeTypeRegistryConcurrencyTest {

    /** 与 NodeTypeRegistryOptionsTest 同源：真机 /object_info 缓存抽出的 8 个节点 */
    private fun loadSample(): JSONObject {
        val stream = javaClass.classLoader!!.getResourceAsStream("object_info_sample.json")
            ?: error("测试资源缺失: object_info_sample.json")
        return stream.bufferedReader().use { JSONObject(it.readText()) }
    }

    /** 把 8 节点样本放大成 8 × copies 个节点，拉长解析窗口让写交错更可复现 */
    private fun buildLargeObjectInfo(copies: Int): JSONObject {
        val sample = loadSample()
        val entries = mutableListOf<Pair<String, JSONObject>>()
        val keys = sample.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            entries.add(k to sample.getJSONObject(k))
        }
        val large = JSONObject()
        repeat(copies) { copy ->
            entries.forEach { (name, def) ->
                large.put("${name}__$copy", JSONObject(def.toString()))
            }
        }
        return large
    }

    @Test
    fun `多线程并发解析后注册表完整且各模型选项非空`() {
        val json = buildLargeObjectInfo(copies = 25)   // 8 × 25 = 200 节点
        val expectedSize = json.length()

        // 单线程基线：同一份数据顺序解析的结果就是"正确答案"
        val baseline = NodeTypeRegistry()
        baseline.parseObjectInfo(JSONObject(json.toString()))
        val expectedCkptOptions = baseline.getOptionsForField("ckpt_name").size

        val registry = NodeTypeRegistry()
        val threads = 8
        val rounds = 20
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val done = CountDownLatch(threads)
        repeat(threads) {
            pool.submit {
                ready.countDown()
                // 全员就绪后同时开跑，最大化 clear/put 的交错概率
                ready.await()
                repeat(rounds) {
                    registry.parseObjectInfo(JSONObject(json.toString()))
                }
                done.countDown()
            }
        }
        assertTrue("并发解析应在 60s 内完成", done.await(60, TimeUnit.SECONDS))
        pool.shutdown()

        // 最终表必须完整：与单线程基线逐项对齐，任何"半个表"都过不了这组断言
        assertEquals(
            "并发解析后节点数应与单线程基线一致",
            expectedSize, registry.getAllNodeTypes().size
        )
        assertTrue("registry 应处于就绪态", registry.isPopulated())
        assertEquals(
            "ckpt 选项数应与单线程基线一致",
            expectedCkptOptions, registry.getOptionsForField("ckpt_name").size
        )
        assertTrue("unet 选项不应为空", registry.getOptionsForField("unet_name").isNotEmpty())
        assertTrue("vae 选项不应为空", registry.getOptionsForField("vae_name").isNotEmpty())
        assertTrue("clip 选项不应为空", registry.getOptionsForFieldPrefix("clip_name").isNotEmpty())
    }

    @Test
    fun `解析中途的读方拿到的也是完整代次快照`() {
        // 一个线程持续解析，另一个线程高频读：读到的每次节点数要么 0（尚未解析）
        // 要么等于完整数量，绝不允许出现中间值（旧实现 clear+put 的半成品表）
        val json = buildLargeObjectInfo(copies = 25)
        val expectedSize = json.length()
        val registry = NodeTypeRegistry()

        val pool = Executors.newFixedThreadPool(2)
        val writer = pool.submit {
            repeat(40) { registry.parseObjectInfo(JSONObject(json.toString())) }
        }
        val reader = pool.submit {
            var reads = 0
            while (reads < 5000) {
                val size = registry.getAllNodeTypes().size
                assertTrue(
                    "读到了半成品表：$size 不在 {0, $expectedSize} 中",
                    size == 0 || size == expectedSize
                )
                reads++
            }
        }
        assertTrue("写入线程应在 60s 内完成", writer.get(60, TimeUnit.SECONDS) == null)
        assertTrue("读取线程应在 60s 内完成", reader.get(60, TimeUnit.SECONDS) == null)
        pool.shutdown()
    }
}
