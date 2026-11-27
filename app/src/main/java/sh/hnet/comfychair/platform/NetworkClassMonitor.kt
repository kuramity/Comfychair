package sh.hnet.comfychair.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import sh.hnet.comfychair.util.DebugLogger

/**
 * 默认网络的**计量类别**监视器（v4.18.x 后台省流量的第二信号源）。
 *
 * 只跟踪**默认网络**（[ConnectivityManager.registerDefaultNetworkCallback]），
 * 回调频率天然低（WiFi↔蜂窝切换、能力变化才会来）；回调里只做分类 + 去重
 * （StateFlow 相等不发射），**不做任何重活** —— 重活（重评策略/断连/放宽心跳）
 * 由 [BackgroundDegradationCoordinator] 收集 [networkClass] 执行。
 *
 * 分类口径：`NET_CAPABILITY_NOT_METERED` 有 = 不限量（WiFi/家宽等），无 = 计量
 * （蜂窝/热点）。同一默认网络切换（WiFi A → WiFi B）也会走 onCapabilitiesChanged，
 * 协调器据此在**网络切换时重新评估**当前降级策略。
 */
object NetworkClassMonitor {

    private const val TAG = "NetworkClass"

    private val _networkClass = MutableStateFlow(BackgroundDegradationPolicy.NetworkClass.UNKNOWN)
    val networkClass: StateFlow<BackgroundDegradationPolicy.NetworkClass> = _networkClass.asStateFlow()

    @Volatile
    private var attached = false

    /**
     * 安装网络回调（Application.onCreate 调用一次，重复调用幂等）。
     * 初始值在注册前同步读一次 activeNetwork，避免首个策略决策拿到 UNKNOWN。
     */
    fun attach(context: Context) {
        if (attached) {
            DebugLogger.w(TAG, "attach: 已安装，跳过重复注册")
            return
        }
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            DebugLogger.w(TAG, "attach: ConnectivityManager 不可用，保持 UNKNOWN")
            return
        }
        attached = true

        // 初始值：注册前同步读一次（避免首决策踩 UNKNOWN）
        currentClassOf(cm)?.let { initial ->
            _networkClass.value = initial
            DebugLogger.d(TAG, "初始网络类别: $initial")
        }

        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    update(classify(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)))
                }

                override fun onLost(network: Network) {
                    // 默认网络丢失：回 UNKNOWN（保守侧）。系统选出新默认网络后
                    // 会立刻回调 onCapabilitiesChanged 修正。
                    update(BackgroundDegradationPolicy.NetworkClass.UNKNOWN)
                }
            })
        } catch (e: Exception) {
            // 注册失败（极端 ROM 差异）：不崩、允许下次 attach 重试，
            // 保持 UNKNOWN（策略上视同 METERED，保守）
            DebugLogger.e(TAG, "registerDefaultNetworkCallback 失败: ${e.message}")
            attached = false
        }
    }

    /** 分类 + 去重（值不变时 StateFlow 不发射，下游不重复触发）。 */
    private fun update(next: BackgroundDegradationPolicy.NetworkClass) {
        if (next != _networkClass.value) {
            DebugLogger.d(TAG, "网络类别: ${_networkClass.value} -> $next")
            _networkClass.value = next
        }
    }

    /** 纯分类（JVM 单测钉住）：有 NOT_METERED 能力 = 不限量，否则计量。 */
    internal fun classify(hasNotMeteredCapability: Boolean): BackgroundDegradationPolicy.NetworkClass =
        if (hasNotMeteredCapability) {
            BackgroundDegradationPolicy.NetworkClass.UNMETERED
        } else {
            BackgroundDegradationPolicy.NetworkClass.METERED
        }

    /** 当前默认网络的类别；无默认网络或能力读取失败时返回 null（调用方保持旧值）。 */
    private fun currentClassOf(cm: ConnectivityManager): BackgroundDegradationPolicy.NetworkClass? {
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        return classify(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }
}
