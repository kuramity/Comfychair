package sh.hnet.comfychair.util

/**
 * Utility for building server URLs consistently across the app.
 */
object ServerUrlUtils {

    private val IP_ADDRESS_PATTERN = Regex(
        "^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$"
    )

    private val HOSTNAME_PATTERN = Regex(
        "^(([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9\\-]*[a-zA-Z0-9])\\.)*([A-Za-z0-9]|[A-Za-z0-9][A-Za-z0-9\\-]*[A-Za-z0-9])$"
    )

    /**
     * Build a full server URL from its components.
     *
     * 主机名先剥掉用户可能残留的协议前缀，否则会拼出 `https://https://host:port`
     * 这类畸形地址（协议已单独指定，这里只接受纯主机名/IP）。
     *
     * @param protocol Either "http" or "https"
     * @param hostname Server hostname or IP address (可带 http:// / https:// 前缀)
     * @param port Server port number
     * @return Full URL string, e.g. "https://192.168.1.100:8188"
     */
    fun buildServerUrl(protocol: String, hostname: String, port: Int): String {
        val bare = splitProtocol(hostname).second
        return "$protocol://$bare:$port"
    }

    /**
     * 拆出显式协议与纯主机名：'http://a.b' -> ("http","a.b"); 'a.b' -> (null,"a.b")。
     *
     * 地址输入框允许用户直接粘贴带 scheme 的地址，协议单独持久化，
     * 主机名校验只针对剥离后的纯主机名/IP。
     */
    fun splitProtocol(raw: String): Pair<String?, String> {
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("http://", ignoreCase = true) ->
                "http" to trimmed.substring(7).trimEnd('/')
            trimmed.startsWith("https://", ignoreCase = true) ->
                "https" to trimmed.substring(8).trimEnd('/')
            else -> null to trimmed.trimEnd('/')
        }
    }

    /**
     * 主机名/IP 校验：先剥离用户可能输入的协议前缀，再走原有的 IP / 主机名正则。
     * 抽成纯函数是为了能在 JVM 单测里钉住（见 ServerUrlUtilsTest）。
     */
    fun isValidHostname(raw: String): Boolean {
        val bare = splitProtocol(raw).second
        return IP_ADDRESS_PATTERN.matches(bare) || HOSTNAME_PATTERN.matches(bare)
    }
}
