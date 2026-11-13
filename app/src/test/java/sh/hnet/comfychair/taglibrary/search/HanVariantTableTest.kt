package sh.hnet.comfychair.taglibrary.search

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 繁简/异体字归一化测试。
 *
 * 真实映射表从 assets 直接读（JVM 单测没有 Android Context），
 * 用它与用户实际遇到的写法做回归：搜「长乳首」要能命中别名里的「長乳首」。
 */
class HanVariantTableTest {

    private val realMapping: Map<Char, Char> by lazy { loadRealMapping() }

    @Before
    fun setUp() {
        HanVariantTable.resetForTest()
    }

    @After
    fun tearDown() {
        HanVariantTable.resetForTest()
    }

    @Test
    fun `未加载映射表时原样返回`() {
        assertEquals("長乳首", HanVariantTable.simplify("長乳首"))
    }

    @Test
    fun `繁体压成简体`() {
        HanVariantTable.setMappingForTest(mapOf('長' to '长', '陰' to '阴', '觸' to '触'))
        assertEquals("长乳首", HanVariantTable.simplify("長乳首"))
        assertEquals("阴毛", HanVariantTable.simplify("陰毛"))
        assertEquals("触手", HanVariantTable.simplify("觸手"))
    }

    @Test
    fun `日文新字体压成简体`() {
        HanVariantTable.setMappingForTest(
            mapOf('長' to '长', '髪' to '发', '気' to '气', '絵' to '绘')
        )
        assertEquals("长发", HanVariantTable.simplify("長髪"))
        assertEquals("气", HanVariantTable.simplify("気"))
        assertEquals("绘", HanVariantTable.simplify("絵"))
    }

    @Test
    fun `非汉字与英文原样保留`() {
        HanVariantTable.setMappingForTest(mapOf('長' to '长'))
        assertEquals("1girl", HanVariantTable.simplify("1girl"))
        // 只有映射表里的汉字被替换，符号/数字/英文一概不动
        assertEquals("长-pose_123", HanVariantTable.simplify("長-pose_123"))
    }

    @Test
    fun `无变化时返回同一个对象实例`() {
        HanVariantTable.setMappingForTest(mapOf('長' to '长'))
        val text = "1girl"
        assertSame(text, HanVariantTable.simplify(text))
    }

    @Test
    fun `真实映射表已加载且覆盖关键异体字`() {
        assertTrue("assets 里的映射表没读到", realMapping.isNotEmpty())
        // 用户实际报过的那个词
        assertEquals('长', realMapping['長'])
        // 日文新字体（zhconv 处理不了，靠手工补的那批）
        assertEquals('发', realMapping['髪'])
        assertEquals('气', realMapping['気'])
        assertEquals('绘', realMapping['絵'])
    }

    @Test
    fun `简体关键词能命中繁体别名（用户报的场景）`() {
        HanVariantTable.setMappingForTest(realMapping)
        // 模拟 TagMemoryIndex 构建：别名先归一化再进索引
        val aliasIndex = listOf("長乳首", "長髪", "長靴", "長袖", "陰毛", "ロングヘアー")
            .map { HanVariantTable.simplify(it).lowercase() }
        // 模拟用户输入简体后的子串匹配
        assertTrue(aliasIndex.any { HanVariantTable.simplify("长乳首") in it })
        assertTrue(aliasIndex.any { HanVariantTable.simplify("长发") in it })
        assertTrue(aliasIndex.any { HanVariantTable.simplify("长靴") in it })
        assertTrue(aliasIndex.any { HanVariantTable.simplify("长袖") in it })
        assertTrue(aliasIndex.any { HanVariantTable.simplify("阴毛") in it })
    }

    @Test
    fun `输入繁体同样能被归一化命中`() {
        HanVariantTable.setMappingForTest(realMapping)
        val aliasIndex = listOf("长乳首", "长发").map { HanVariantTable.simplify(it) }
        assertTrue(aliasIndex.any { HanVariantTable.simplify("長乳首") in it })
        assertTrue(aliasIndex.any { HanVariantTable.simplify("長髪") in it })
    }

    /** 从仓库里的 assets 读真实映射表（JSON 是扁平的 \"繁\":\"简\" 单字对）。 */
    private fun loadRealMapping(): Map<Char, Char> {
        val candidates = listOf(
            File("src/main/assets/databases/han_variants.json"),
            File("app/src/main/assets/databases/han_variants.json")
        )
        val file = candidates.firstOrNull { it.exists() } ?: return emptyMap()
        val text = file.readText()
        val re = Regex("\"(.{1})\":\"(.{1})\"")
        val map = HashMap<Char, Char>()
        for (m in re.findAll(text)) {
            map[m.groupValues[1][0]] = m.groupValues[2][0]
        }
        return map
    }
}
