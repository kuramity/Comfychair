package sh.hnet.comfychair.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * P1-22 的失败分支测试：相册写入**不许假成功**。
 *
 * 修复前的四步全是 `?.let`，任何一步返回 null 都被静默跳过，最后照样弹「已保存」。
 * 这里用进程内假句柄覆盖四条路径：
 * 1. insert 返回 null → false，且没有任何行需要回滚；
 * 2. 打开输出流失败 → false，且 pending 行被删除（相册不留 0 字节残条）；
 * 3. 写入抛异常 → false，且 pending 行被删除；
 * 4. 发布（IS_PENDING=0）失败 → false，且 pending 行被删除；
 * 5. 全流程成功 → true，且发布只做一次。
 */
class PhotoSavePolicyTest {

    /** 假句柄：真机上对应 MediaStore 返回的 Uri，测试里只要可比较即可 */
    private data class FakeHandle(val id: Int)

    private class Recorder {
        var discarded = 0
        var published = 0
        var inserted = 0
    }

    @Test
    fun `insert 返回 null 时返回 false 且无可回滚行`() {
        val rec = Recorder()
        val result = PhotoSavePolicy.run<FakeHandle>(
            insert = { rec.inserted++; null },
            openStream = { ByteArrayOutputStream() },
            write = { it.write(byteArrayOf(1)) },
            publish = { rec.published++ },
            discard = { rec.discarded++ }
        )

        assertFalse("insert 失败必须报失败", result)
        assertEquals(0, rec.discarded)
        assertEquals(0, rec.published)
    }

    @Test
    fun `打开输出流失败时回滚 pending 行`() {
        val rec = Recorder()
        val result = PhotoSavePolicy.run(
            insert = { rec.inserted++; FakeHandle(1) },
            openStream = { null },
            write = { error("不应被调用") },
            publish = { rec.published++ },
            discard = { rec.discarded++ }
        )

        assertFalse("拿不到输出流不能算保存成功", result)
        assertEquals("必须删除 pending 行，否则相册里会留下 0 字节残条", 1, rec.discarded)
        assertEquals(0, rec.published)
    }

    @Test
    fun `写入抛异常时回滚 pending 行`() {
        val rec = Recorder()
        val result = PhotoSavePolicy.run(
            insert = { rec.inserted++; FakeHandle(2) },
            openStream = { ByteArrayOutputStream() },
            write = { throw IOException("磁盘已满") },
            publish = { rec.published++ },
            discard = { rec.discarded++ }
        )

        assertFalse("写入失败必须报失败", result)
        assertEquals(1, rec.discarded)
        assertEquals(0, rec.published)
    }

    @Test
    fun `发布失败时回滚 pending 行而不是留一条隐形条目`() {
        val rec = Recorder()
        val result = PhotoSavePolicy.run(
            insert = { rec.inserted++; FakeHandle(3) },
            openStream = { ByteArrayOutputStream() },
            write = { it.write(byteArrayOf(1, 2, 3)) },
            publish = { throw IllegalStateException("update 影响 0 行") },
            discard = { rec.discarded++ }
        )

        assertFalse(result)
        assertEquals("发布失败也必须回滚：否则条目永远 pending、相册里看不见也删不掉", 1, rec.discarded)
    }

    @Test
    fun `全流程成功返回 true 且内容完整写入`() {
        val rec = Recorder()
        val sink = ByteArrayOutputStream()
        val result = PhotoSavePolicy.run(
            insert = { rec.inserted++; FakeHandle(4) },
            openStream = { sink },
            write = { it.write(byteArrayOf(7, 8, 9)) },
            publish = { rec.published++ },
            discard = { rec.discarded++ }
        )

        assertTrue(result)
        assertEquals(1, rec.published)
        assertEquals(0, rec.discarded)
        assertEquals(listOf<Byte>(7, 8, 9), sink.toByteArray().toList())
    }

    @Test
    fun `输出流在写入后被关闭`() {
        var closed = false
        val stream = object : OutputStream() {
            override fun write(b: Int) { /* 丢弃 */ }
            override fun close() { closed = true }
        }

        PhotoSavePolicy.run(
            insert = { FakeHandle(5) },
            openStream = { stream },
            write = { it.write(1) },
            publish = { },
            discard = { }
        )

        assertTrue("use{} 必须关闭流，否则 MediaStore 侧可能拿不到完整数据", closed)
    }

    @Test
    fun `流打开后写入异常也要关闭流`() {
        var closed = false
        val stream = object : OutputStream() {
            override fun write(b: Int) { throw IOException("写入失败") }
            override fun close() { closed = true }
        }
        var discarded = 0

        val result = PhotoSavePolicy.run(
            insert = { FakeHandle(6) },
            openStream = { stream },
            write = { it.write(1) },
            publish = { },
            discard = { discarded++ }
        )

        assertFalse(result)
        assertTrue("异常路径也要关闭流，否则文件描述符泄漏", closed)
        assertEquals(1, discarded)
    }
}