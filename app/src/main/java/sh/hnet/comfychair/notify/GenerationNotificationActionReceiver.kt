package sh.hnet.comfychair.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 生成进度通知上「取消」按钮的接收器（Manifest 注册，exported=false）。
 *
 * 只做转发：动作处理在 [GenerationNotifier.requestCancel]（优先 ViewModel 注册的
 * 取消处理器，降级为直接中断当前任务）。Receiver 保持无状态，进程活着就能收到
 * （普通通知的动作广播不要求前台服务）。
 */
class GenerationNotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != GenerationNotifier.ACTION_CANCEL) return
        GenerationNotifier.requestCancel(context.applicationContext)
    }
}
