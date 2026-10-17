package com.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [InputConfigStore.readTextCapped] 的大小上限回归。
 *
 * 超大/恶意文件不得在进 [InputJsonLimits] 之前一次性读进内存（OOM）：
 * 超限按「损坏文件」处理，调用方回退出厂默认/跳过——与解析失败同一路径。
 */
class InputConfigStoreReadCapTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun fill(file: File, bytes: Int) {
        file.outputStream().use { output ->
            val chunk = ByteArray(8192)
            var remaining = bytes
            while (remaining > 0) {
                val take = minOf(remaining, chunk.size)
                output.write(chunk, 0, take)
                remaining -= take
            }
        }
    }

    @Test
    fun smallFileReadsBack() {
        val file = temporaryFolder.newFile("small.json")
        file.writeText("""{"id":"x"}""")
        assertEquals("""{"id":"x"}""", InputConfigStore.readTextCapped(file))
    }

    @Test
    fun missingFileReturnsNull() {
        val file = File(temporaryFolder.root, "absent.json")
        assertNull(InputConfigStore.readTextCapped(file))
    }

    @Test
    fun oversizeFileReturnsNullWithoutReading() {
        val file = temporaryFolder.newFile("big.json")
        // 刚好超过上限 1 字节：必须被拦下（不抛 OOM、不读内容）
        fill(file, (InputConfigStore.MAX_CONFIG_FILE_BYTES + 1).toInt())
        assertNull(InputConfigStore.readTextCapped(file))
    }

    @Test
    fun exactlyAtLimitIsAllowed() {
        val file = temporaryFolder.newFile("edge.json")
        fill(file, InputConfigStore.MAX_CONFIG_FILE_BYTES.toInt())
        assertEquals(
            InputConfigStore.MAX_CONFIG_FILE_BYTES.toInt(),
            InputConfigStore.readTextCapped(file)?.length,
        )
    }
}
