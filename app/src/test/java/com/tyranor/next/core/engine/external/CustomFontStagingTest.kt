package com.tyranor.next.core.engine.external

import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 自定义字体导入的落盘前置条件（用户数据安全）。
 *
 * 导入走「同目录 staging → rename 覆盖」：rename 是原子的，但只要 staging 被建出来，
 * 一次**空/截断**的源就会用残缺文件替换掉用户已导入的可用字体。因此写入必须校验实际
 * 字节数，而不是只看 `copyTo` 有没有返回（它返回 Long，`!= null` 恒真）。
 */
class CustomFontStagingTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun zeroByteSourceIsRejectedAndLeavesNoStagingFile() {
        val staging = File(temporaryFolder.newFolder("zero"), "font.ttf")

        val copied = RpgMakerRuntimeEnvironment.copyFontToStaging(
            ByteArrayInputStream(ByteArray(0)),
            staging,
        )

        assertFalse("零字节源必须判失败（否则 rename 会用空文件覆盖同名旧字体）", copied)
        assertFalse("失败的 staging 不得留在盘上", staging.exists())
    }

    @Test
    fun nonEmptySourceIsWrittenToStaging() {
        val staging = File(temporaryFolder.newFolder("ok"), "font.ttf")

        val copied = RpgMakerRuntimeEnvironment.copyFontToStaging(
            ByteArrayInputStream("font-bytes".toByteArray()),
            staging,
        )

        assertTrue("正常字体必须写入 staging", copied)
        assertEquals("font-bytes", staging.readText())
    }
}
