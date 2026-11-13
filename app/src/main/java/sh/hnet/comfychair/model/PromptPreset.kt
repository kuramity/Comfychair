package sh.hnet.comfychair.model

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.R
import sh.hnet.comfychair.util.UuidUtils

/**
 * Screen types for prompt preset categorization.
 * Each generation screen has its own separate preset library.
 */
enum class ScreenType {
    TEXT_TO_IMAGE,
    IMAGE_TO_IMAGE,  // Shared for both Inpainting and Editing modes
    TEXT_TO_VIDEO,
    IMAGE_TO_VIDEO
}

/**
 * Get the localized display name for this screen type.
 */
@Composable
fun ScreenType.displayName(): String = when (this) {
    ScreenType.TEXT_TO_IMAGE -> stringResource(R.string.screen_type_text_to_image)
    ScreenType.IMAGE_TO_IMAGE -> stringResource(R.string.screen_type_image_to_image)
    ScreenType.TEXT_TO_VIDEO -> stringResource(R.string.screen_type_text_to_video)
    ScreenType.IMAGE_TO_VIDEO -> stringResource(R.string.screen_type_image_to_video)
}

/**
 * Represents a saved prompt preset.
 */
@Immutable
data class PromptPreset(
    val id: String,
    val screenType: ScreenType,
    val name: String,
    val prompt: String,
    val tags: List<String>,
    val isFavorite: Boolean,
    val createdAt: Long,
    /**
     * 负面提示词，与正面**分开存**在同一个标签名下。
     *
     * 允许为空串，而且字段始终存在：有些工作流根本不需要负面提示词
     * （BasicGuider 那类流连负面输入口都没有），那时它就是空的 ——
     * 「有字段」与「有内容」是两件事。
     *
     * 放在参数表末尾是为了不破坏既有的位置调用（`PromptPreset(id, screenType, …)`）。
     */
    val negativePrompt: String = ""
) {
    companion object {
        /**
         * Create a new preset with auto-generated UUID.
         */
        fun create(
            screenType: ScreenType,
            name: String,
            prompt: String,
            tags: List<String> = emptyList(),
            negativePrompt: String = ""
        ): PromptPreset {
            return PromptPreset(
                id = UuidUtils.generateRandomId(),
                screenType = screenType,
                name = name,
                prompt = prompt,
                tags = tags,
                isFavorite = false,
                createdAt = System.currentTimeMillis(),
                negativePrompt = negativePrompt
            )
        }

        /**
         * Parse preset from JSON object.
         * Returns null if required fields are missing or invalid.
         */
        fun fromJson(json: JSONObject): PromptPreset? {
            val id = json.optString("id").takeIf { it.isNotEmpty() } ?: return null
            val screenTypeStr = json.optString("screenType").takeIf { it.isNotEmpty() } ?: return null
            val screenType = try {
                ScreenType.valueOf(screenTypeStr)
            } catch (e: IllegalArgumentException) {
                return null
            }
            val name = json.optString("name").takeIf { it.isNotEmpty() } ?: return null
            val prompt = json.optString("prompt", "")
            val tagsArray = json.optJSONArray("tags")
            val tags = if (tagsArray != null) {
                (0 until tagsArray.length()).mapNotNull {
                    tagsArray.optString(it).takeIf { s -> s.isNotEmpty() }
                }
            } else {
                emptyList()
            }
            val isFavorite = json.optBoolean("isFavorite", false)
            val createdAt = json.optLong("createdAt", System.currentTimeMillis())
            // 老数据里没有这个键 → 读成空串（=「这条预设没有负面」）。
            // 用户下次编辑并保存这条预设时，字段就会被补上
            val negativePrompt = json.optString("negativePrompt", "")

            return PromptPreset(id, screenType, name, prompt, tags, isFavorite, createdAt, negativePrompt)
        }
    }

    /**
     * Convert preset to JSON object.
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("screenType", screenType.name)
            put("name", name)
            put("prompt", prompt)
            put("negativePrompt", negativePrompt)
            put("tags", JSONArray(tags))
            put("isFavorite", isFavorite)
            put("createdAt", createdAt)
        }
    }
}
