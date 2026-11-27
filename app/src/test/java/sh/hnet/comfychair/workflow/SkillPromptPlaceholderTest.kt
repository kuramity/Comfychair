package sh.hnet.comfychair.workflow

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 技能流占位符（提示词增强模板）替换链路的钉子。
 *
 * 线上故障：模板里新加了 `{{skill_model}}` / `{{skill_mmproj}}`（模型加载器的「主模型 /
 * 视觉投影mmproj」字段），但 `WorkflowManager.applySkillPrompt` 的判据只认 `{{skill_file`、
 * 替换也只有两个 md 字段 —— 占位符以字面量发给服务器
 * （`Value not in list: 视觉投影mmproj: '{{skill_mmproj}}'`）。
 *
 * 四个测试各守一层：
 * 1. 注册表：两个模型字段必须算「已识别」，否则动态参数引擎会把它们当普通文本框渲染，
 *    用户一滚动面板就被持久化成空串，提交时先行替换掉占位符、注入空内容；
 * 2. 替换接线：`applySkillPrompt` 是 private 且走 Android assets（JVM 单测够不着，
 *    项目也没有 Robolectric），所以按 `FeatureWiringContractTest` 的先例用**读源码文本**
 *    的方式钉住判据前缀、四个 `.replace` 调用与 defaults 取值路径（改动破坏接线即红）；
 * 3. 取值路径：模板 defaults 里必须带 skill_model / skill_mmproj 键 ——
 *    公开版规范值留空（用户自选模型），键存在即可被替换链路读取；
 * 4. 入口收口：两套模板必须留在 assets/tool_templates —— 回流 prebuilt_workflows 会被
 *    `seedPrebuiltWorkflows` 同步进生成页的工作流下拉，违反「唯一入口是小工具页」。
 */
class SkillPromptPlaceholderTest {

    // ---------------- ① 注册表：技能流的四个字段必须算已识别 ----------------

    @Test
    fun `skill_model 与 skill_mmproj 必须算已识别占位符`() {
        listOf("skill_file_name", "skill_file_content", "skill_model", "skill_mmproj").forEach { name ->
            assertTrue(
                "$name 必须算已识别（不登记的话动态参数引擎会渲染出多余文本框，滚动面板即持久化空串）",
                TemplateKeyRegistry.isRecognizedPlaceholder(name)
            )
        }
    }

    // ---------------- ② 替换接线（读源码钉住，FeatureWiringContractTest 同口径） ----------------

    private fun workflowManagerSource(): String {
        val file = listOf(
            File("src/main/java/sh/hnet/comfychair/WorkflowManager.kt"),
            File("app/src/main/java/sh/hnet/comfychair/WorkflowManager.kt")
        ).firstOrNull { it.isFile }
        assumeTrue("WorkflowManager.kt 不可用时跳过（单测工作目录应为 app/ 模块根）", file != null)
        return file!!.readText()
    }

    /** 取出函数正文：从签名起，到下一个 4 空格缩进的成员声明之前（与 FeatureWiringContractTest 同口径）。 */
    private fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("源码里找不到函数：$signature", start >= 0)
        val rest = source.substring(start + signature.length)
        val nextMember = Regex("""\n {4}(private |internal |public |protected )?(fun|val|var)""").find(rest)
        return if (nextMember != null) rest.substring(0, nextMember.range.first) else rest
    }

    /** 折叠空白：断言跨行表达式时换行/缩进调整不会误报。 */
    private fun folded(text: String): String = text.replace(Regex("""\s+"""), " ").trim()

    /**
     * 是否存在以 `.replace("$placeholder"` 开头的**替换语句**。
     * 不用 contains：注释里出现同名字样也会满足它（FeatureWiringContractTest 踩过的坑）。
     */
    private fun hasReplaceCall(body: String, placeholder: String): Boolean =
        body.lines().any { it.trim().startsWith(""".replace("$placeholder",""") }

    @Test
    fun `applySkillPrompt 的判据必须覆盖 skill_ 前缀并替换全部四个占位符`() {
        val body = bodyOf(workflowManagerSource(), "private fun applySkillPrompt(")
        assertTrue(
            "判据必须是 {{skill_ 前缀（只认 {{skill_file 会漏掉 skill_model / skill_mmproj，" +
                "占位符以字面量发给服务器 → Value not in list）",
            folded(body).contains("""json.contains("{{skill_")""")
        )
        listOf("skill_file_name", "skill_file_content", "skill_model", "skill_mmproj").forEach { name ->
            assertTrue("applySkillPrompt 里必须有 {{$name}} 的替换语句", hasReplaceCall(body, "{{$name}}"))
        }
    }

    @Test
    fun `模型字段的值必须从工作流 defaults 读取`() {
        val body = folded(bodyOf(workflowManagerSource(), "private fun applySkillPrompt("))
        assertTrue(
            "skill_model / skill_mmproj 的值必须来自 getRawDefaultsObject(workflow.id)" +
                "（复用既有 defaults 解析，不许另写一套 assets 解析逻辑）",
            body.contains("getRawDefaultsObject(workflow.id)")
        )
    }

    @Test
    fun `持久化的空串必须回退到默认规则`() {
        val body = folded(bodyOf(workflowManagerSource(), "private fun applySkillPrompt("))
        assertTrue(
            "SkillPromptStore 里的历史脏数据（旧版「（不注入）」写入的空串）必须与 null 同样回退到 " +
                "defaultFor，否则 read(\"\") → 正文为空 → ComfyUI 走「自动选择 Skill」分支必抛错",
            body.contains("""?.takeIf { it.isNotBlank() }""")
        )
    }

    // ---------------- ③ 取值路径：模板 defaults 必须提供模型字段 ----------------

    /** 读提示词增强模板（assets/tool_templates）；单测工作目录是模块根（app/），仓库根口径兜底。 */
    private fun prebuiltSource(fileName: String): String? =
        listOf(
            File("src/main/assets/tool_templates/$fileName"),
            File("app/src/main/assets/tool_templates/$fileName")
        ).firstOrNull { it.isFile }?.readText()

    @Test
    fun `t2i 模板占位符与 defaults 键成对存在且模型值留空`() {
        val text = prebuiltSource("qwen21_skill_t2i.json")
        assumeTrue("qwen21_skill_t2i.json 不可用时跳过", text != null)
        val defaults = JSONObject(text!!).optJSONObject("defaults")
        assertTrue("defaults 对象缺失", defaults != null)
        assertTrue("defaults 里必须有 skill_model", defaults!!.has("skill_model"))
        assertTrue("defaults 里必须有 skill_mmproj", defaults.has("skill_mmproj"))
        // 公开版规范：模型名留空，用户在面板里自选（与预置工作流一致）
        assertEquals("", defaults.optString("skill_model"))
        assertEquals("", defaults.optString("skill_mmproj"))
        // 模板正文里两个占位符必须真实出现（替换接线才有意义）
        assertTrue(text.contains("{{skill_model}}"))
        assertTrue(text.contains("{{skill_mmproj}}"))
    }

    @Test
    fun `edit 模板占位符与 defaults 键成对存在且模型值留空`() {
        val text = prebuiltSource("qwen21_skill_edit.json")
        assumeTrue("qwen21_skill_edit.json 不可用时跳过", text != null)
        val defaults = JSONObject(text!!).optJSONObject("defaults")
        assertTrue("defaults 对象缺失", defaults != null)
        assertTrue("defaults 里必须有 skill_model", defaults!!.has("skill_model"))
        assertTrue("defaults 里必须有 skill_mmproj", defaults.has("skill_mmproj"))
        assertEquals("", defaults.optString("skill_model"))
        assertEquals("", defaults.optString("skill_mmproj"))
        assertTrue(text.contains("{{skill_model}}"))
        assertTrue(text.contains("{{skill_mmproj}}"))
    }

    // ---------------- ④ 防回归：两套模板必须留在 tool_templates ----------------

    /**
     * prebuilt_workflows 目录里的 json 会被 `WorkflowManager.seedPrebuiltWorkflows` 同步进
     * 用户存储，从而出现在「文生图」「图生图」页的工作流下拉里。提示词增强的两套模板
     * 唯一入口是小工具页：谁把它们移回预置目录，本测试必须红。
     */
    @Test
    fun `提示词增强的两套模板不得出现在预置工作流目录里`() {
        val dir = listOf(
            File("src/main/assets/prebuilt_workflows"),
            File("app/src/main/assets/prebuilt_workflows")
        ).firstOrNull { it.isDirectory }
        assumeTrue("prebuilt_workflows 目录不可用时跳过（单测工作目录应为 app/ 模块根）", dir != null)
        listOf(
            "qwen21_skill_edit.json",
            "qwen21_skill_t2i.json"
        ).forEach { fileName ->
            assertTrue(
                "$fileName 是提示词增强页的专用模板，放回 prebuilt_workflows 会被 seedPrebuiltWorkflows " +
                    "同步进用户存储、重新出现在生成页的工作流下拉里；请移回 app/src/main/assets/tool_templates/",
                !File(dir!!, fileName).isFile
            )
        }
    }
}
