package sh.hnet.comfychair.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 「下载前是否要先问一句」的网络判定（v4.18.0，R6）。
 *
 * 用户实测：后台重试把移动流量烧掉了。控流的第一道闸不是限制下载，而是
 * **在真正开始下载前如实告诉用户「当前是移动网络、这个包约 N MB」**，让用户自己决定。
 *
 * 判定与读取拆开：
 * - [canStartDownload] / [needsConfirmation] 是纯函数（JVM 单测覆盖三种组合）；
 * - [currentType] 才碰 Android 的 ConnectivityManager。
 *
 * 只做「下载前确认」，不做「WiFi 才允许下载」的硬限制 —— 用户可能就是要用流量。
 */
object UpdateNetworkPolicy {

    /** 当前承载网络的大致类型（只区分「要不要提醒」） */
    enum class NetworkType {
        /** WiFi（不计移动流量） */
        WIFI,

        /** 蜂窝网络（移动流量） */
        CELLULAR,

        /** 其他/未知（有线、VPN 或取不到信息；按需要提醒处理，宁可多问一句） */
        OTHER
    }

    /**
     * 是否允许直接开始下载。
     *
     * @param networkType 当前网络类型
     * @param userConfirmed 用户是否已经在本轮确认过「就用移动网络下」
     */
    fun canStartDownload(networkType: NetworkType, userConfirmed: Boolean): Boolean =
        networkType == NetworkType.WIFI || userConfirmed

    /** 是否需要在下载前弹一次确认（= 不允许直接开始） */
    fun needsConfirmation(networkType: NetworkType, userConfirmed: Boolean): Boolean =
        !canStartDownload(networkType, userConfirmed)

    /**
     * 读当前网络的类型。
     *
     * VPN（本机常见：Clash TUN）在 `NetworkCapabilities` 里往往只报 TRANSPORT_VPN，
     * 拿不到底层的 WIFI/CELLULAR —— 这时退回 `isActiveNetworkMetered`：
     * 非计费网络按 WiFi 处理（VPN over WiFi 不弹确认），计费网络按蜂窝处理。
     */
    fun currentType(context: Context): NetworkType {
        val manager = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkType.OTHER
        val network = manager.activeNetwork ?: return NetworkType.OTHER
        val caps = manager.getNetworkCapabilities(network) ?: return NetworkType.OTHER
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            // 有线网络同样不计移动流量，不打扰用户
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.WIFI
            else -> if (manager.isActiveNetworkMetered) {
                NetworkType.CELLULAR
            } else {
                NetworkType.WIFI
            }
        }
    }
}