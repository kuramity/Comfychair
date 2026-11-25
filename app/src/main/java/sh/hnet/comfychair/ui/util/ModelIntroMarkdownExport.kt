package sh.hnet.comfychair.ui.util

import android.content.Context
import android.content.Intent
import sh.hnet.comfychair.model.ModelIntro
import sh.hnet.comfychair.model.ModelIntroMarkdown
import sh.hnet.comfychair.util.AtomicFileWriter
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.ShareFileStore
import java.io.File

/**
 * 把一条模型介绍按 [ModelIntroMarkdown] 模板导出成 `.md` 并交给系统分享。
 *
 * 为什么放在 `ui.util` 而不是 `util`：`sh.hnet.comfychair.util` 被架构测试
 * （`UtilPackageDependencyTest`）钉住「不得反向依赖 model/workflow/ui/viewmodel」，
 * 而导出必然要认识 [ModelIntro]。分享是界面动作，放 UI 层符合既有分层。
 *
 * 口径照抄项目既有的对外分享链路（见 [ShareFileStore] 与图库/视频分享）：
 * 载荷只能写进 `cacheDir/share/`（FileProvider 唯一声明的缓存子目录）、
 * 用 content URI + `ACTION_SEND` 交给系统选择器，不往 `Downloads/` 之类外部目录直写
 * （外部存储权限、分区存储兼容与清理都要另做一套，分享链路已经解决过这些）。
 *
 * 落盘走 [AtomicFileWriter]：半截 `.md` 分享出去比没有更糟。
 */
object ModelIntroMarkdownExport {

    private const val TAG = "ModelIntroMarkdownExport"

    /** Markdown 的 MIME（个别接收方只认 text/plain，所以同时附带 EXTRA_TEXT）。 */
    private const val MIME_MARKDOWN = "text/markdown"

    /**
     * 生成分享 Intent（内部完成落盘）。
     *
     * @return 可直接交给 [Intent.createChooser] 的发送意图；落盘失败/路径越界返回 null
     *         （调用方负责提示失败，本对象不弹任何 UI）
     */
    fun shareIntent(context: Context, intro: ModelIntro): Intent? {
        val markdown = ModelIntroMarkdown.buildMarkdown(intro)
        val file = writeShareFile(context, intro, markdown) ?: return null
        val uri = try {
            ShareFileStore.uriFor(context, file)
        } catch (e: Exception) {
            DebugLogger.w(TAG, "介绍导出文件生成 content URI 失败: ${e.message}")
            return null
        }
        return Intent(Intent.ACTION_SEND).apply {
            type = MIME_MARKDOWN
            putExtra(Intent.EXTRA_STREAM, uri)
            // 有些接收方（聊天/记事本）只读 EXTRA_TEXT，不读附件流
            putExtra(Intent.EXTRA_TEXT, markdown)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * 把 Markdown 写进分享目录，返回目标文件。
     *
     * 文件名来自数据（模型名），可能带斜杠/冒号/`..`——[ModelIntroMarkdown.fileNameFor]
     * 已裁成单段安全名，这里再用 canonicalPath 校验一层：**最终路径必须落在
     * `cacheDir/share/` 之内**，否则放弃（绝不因为数据里出现路径片段就写到别处）。
     */
    internal fun writeShareFile(
        context: Context,
        intro: ModelIntro,
        markdown: String = ModelIntroMarkdown.buildMarkdown(intro),
    ): File? = try {
        val root = ShareFileStore.dir(context).canonicalFile
        val target = File(root, ModelIntroMarkdown.fileNameFor(intro)).canonicalFile
        if (!target.path.startsWith(root.path + File.separator)) {
            DebugLogger.w(TAG, "介绍导出路径越界，已拒绝: ${target.path}")
            null
        } else {
            AtomicFileWriter.writeText(target, markdown)
            target
        }
    } catch (e: Exception) {
        DebugLogger.w(TAG, "介绍导出落盘失败: ${e.message}")
        null
    }
}