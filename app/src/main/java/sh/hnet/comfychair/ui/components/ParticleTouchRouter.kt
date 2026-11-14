package sh.hnet.comfychair.ui.components

import java.util.concurrent.atomic.AtomicReference

/**
 * 触摸坐标中转站（v4.8.0）。
 *
 * **为什么需要它（v4.7.0 点击失效的真因）**
 *
 * `Modifier.pointerInput` 挂在全屏覆盖层上会参与 Compose 的命中测试，而命中测试默认
 * `sharePointerInputWithSiblings = false` —— 最上层的指针节点命中后，**下层兄弟节点
 * 不再被命中**。v4.7.0 把 `pointerInput` 放在 `Box` 最上层的全屏 Canvas 上，于是整个
 * 业务 UI 收不到任何触摸：按钮点不动、下拉无响应、输入框无法聚焦（而特效仍能在触点出现，
 * 因为事件恰好被顶层拿走）。
 *
 * 注意这与"是否调用 `consume()`"无关：不消费也照样挡，因为挡发生在**命中阶段**，不是分发阶段。
 *
 * **现在的做法**：覆盖层只绘制、零触摸修饰符；坐标改由 Activity 的
 * `dispatchTouchEvent` 读一眼 ACTION_DOWN（**返回 super，不消费、不改写**），
 * 事件完整进入 Compose 分发链，业务控件行为与没有特效层时完全一致。
 */
object ParticleTouchRouter {

    private val sink = AtomicReference<((Float, Float) -> Unit)?>(null)

    /** 由 [ParticleOverlay] 在组合期注册，离开组合时置空 */
    fun register(handler: ((Float, Float) -> Unit)?) {
        sink.set(handler)
    }

    /** Activity 侧调用：只转发坐标，不参与任何事件决策 */
    fun dispatch(x: Float, y: Float) {
        sink.get()?.invoke(x, y)
    }
}
