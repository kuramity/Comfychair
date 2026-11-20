package sh.hnet.comfychair.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.model.AuthCredentials
import sh.hnet.comfychair.model.AuthType
import sh.hnet.comfychair.model.Server
import java.io.File

/**
 * P3-6：ServerDialog 的表单初值必须由「当前服务器 + 当前凭据」唯一决定。
 *
 * 回归点：对话框在切换编辑对象时不会离开组合，字段状态若不带 key 就会保留上一台
 * 的值 —— 保存时把上一台的账号/密码/token 写到当前这一台。这里钉住推导函数：
 * 换服务器、换凭据、切到「新增」都必须得到不同的初值。
 */
class ServerDialogInitialStateTest {

    private val serverA = Server(
        id = "6f5a1a3c-8f2e-4b9a-9d31-2c7e4b5a6d10",
        name = "家里",
        hostname = "192.168.1.5",
        port = 8188,
        authType = AuthType.BASIC
    )

    private val serverB = Server(
        id = "9c1d2e3f-4a5b-6c7d-8e9f-0a1b2c3d4e5f",
        name = "机房",
        hostname = "10.0.0.8",
        port = 8189,
        authType = AuthType.BEARER,
        protocol = "https"
    )

    @Test
    fun `Basic 账号密码跟随服务器切换`() {
        val first = serverDialogInitialState(
            serverA,
            AuthCredentials.Basic("alice", "pa")
        )
        val second = serverDialogInitialState(
            serverB,
            AuthCredentials.Bearer("token-b")
        )

        assertEquals("alice", first.username)
        assertEquals("pa", first.password)
        assertNotEquals(first.username, second.username)
        assertNotEquals(first.password, second.password)
        assertEquals("", second.username)
        assertEquals("", second.password)
    }

    @Test
    fun `Bearer token 只填 token 字段`() {
        val state = serverDialogInitialState(serverB, AuthCredentials.Bearer("token-b"))

        assertEquals("token-b", state.token)
        assertEquals("", state.username)
        assertEquals("", state.password)
        assertEquals(AuthType.BEARER, state.authType)
    }

    @Test
    fun `Basic 凭据不会串到 token 字段`() {
        val state = serverDialogInitialState(serverA, AuthCredentials.Basic("alice", "pa"))

        assertEquals("", state.token)
        assertEquals(AuthType.BASIC, state.authType)
    }

    @Test
    fun `新增服务器使用默认值且不携带任何旧凭据`() {
        val state = serverDialogInitialState(null, AuthCredentials.Basic("alice", "pa"))

        // 即使调用方还留着上一台的凭据对象，新增分支也只能给出空表单
        assertEquals("", state.name)
        assertEquals("", state.hostname)
        assertEquals("8188", state.port)
        assertEquals(AuthType.NONE, state.authType)
        assertEquals("", state.username)
        assertEquals("", state.password)
        assertEquals("", state.token)
    }

    @Test
    fun `编辑时协议前缀一并显示`() {
        val state = serverDialogInitialState(serverB, AuthCredentials.None)

        assertEquals("https://10.0.0.8", state.hostname)
        assertEquals("8189", state.port)
        assertEquals("机房", state.name)
    }

    /**
     * 源码契约：表单字段的 remember 必须带 key。
     *
     * 上面的函数级用例只能证明「初值依赖服务器/凭据」，证明不了组合层每次都用它重建；
     * 无 key 的 `remember { mutableStateOf(...) }` 才是 P3-6 的真正成因，因此这里直接钉住
     * 修复点的写法（与 architecture/ 下几个源码契约测试同一风格，纯 JVM 可跑）。
     */
    @Test
    fun `表单字段不得使用无 key 的 remember`() {
        val source = File("src/main/java/sh/hnet/comfychair/ui/components/ServerDialog.kt")
        assertTrue("找不到 ServerDialog 源码：${source.absolutePath}", source.isFile)

        val unkeyed = Regex(
            """var\s+(name|hostname|port|authType|username|password|token)\s+by\s+remember\s*\{"""
        ).findAll(source.readText()).map { it.value }.toList()

        assertTrue(
            "ServerDialog 的表单/凭据字段又用回了无 key 的 remember（切换编辑对象时会把上一台" +
                "的账号密码留在输入框里，保存时写到当前这一台）：\n" + unkeyed.joinToString("\n"),
            unkeyed.isEmpty()
        )
    }
}