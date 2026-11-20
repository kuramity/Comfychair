package sh.hnet.comfychair.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 凭据落盘策略：加密存储不可用时绝不能把密码写进明文 prefs。
 */
class CredentialPersistencePolicyTest {

    @Test
    fun `加密不可用时不落盘`() {
        assertFalse(
            "加密存储不可用时必须放弃落盘（避免明文密码）",
            CredentialPersistencePolicy.shouldPersist(false)
        )
    }

    @Test
    fun `加密可用时正常落盘`() {
        assertTrue(
            "加密存储可用时应正常保存凭据",
            CredentialPersistencePolicy.shouldPersist(true)
        )
    }
}