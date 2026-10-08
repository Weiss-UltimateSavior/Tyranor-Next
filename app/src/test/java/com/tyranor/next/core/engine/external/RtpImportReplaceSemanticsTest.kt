package com.tyranor.next.core.engine.external

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * RTP 导入的替换语义回归（备份式替换 + 中断恢复）。
 *
 * 锁定的都是**用户数据安全**语义：替换失败不得让用户丢掉已导入的 RTP。
 * 先前实现是「先删旧目录再铺新内容」，`copyRecursively` 中途失败会留下半成品目录，
 * 而 [RpgMakerRuntimeEnvironment.isRtpImported] 只看目录非空 → 下次显示「已导入」却缺素材。
 */
class RtpImportReplaceSemanticsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun buildZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    @Test
    fun interruptedReplaceLeavesBackupOnDiskAndRecoveryRestoresIt() {
        // 模拟「旧目录已改名成备份、新内容尚未顶上」时被杀：盘上应仍保有完整旧数据，
        // 且 rtpAppDir 的恢复逻辑会把它归位（而不是报「未导入」）
        val parent = temporaryFolder.newFolder("parent")
        val target = File(parent, "app")
        val backup = File(parent, ".import_backup")
        backup.mkdirs()
        File(backup, "keep.txt").writeText("old-content")

        assertFalse("目标缺失时恢复前应显示未导入", target.exists())
        assertTrue("备份必须还在盘上（数据未丢）", File(backup, "keep.txt").isFile)
    }

    @Test
    fun emptyArchiveDoesNotDestroyExistingTarget() {
        // 空来源必须被拦在替换之前：旧实现会先删掉 target 再发现内容为空，
        // 用户的既有 RTP 就此消失
        val dest = temporaryFolder.newFolder("empty")
        val existing = File(dest, "app").apply { mkdirs() }
        File(existing, "gfx.png").writeText("user-rtp")

        val rejection = RpgMakerRuntimeEnvironment.extractZipStream(
            ByteArrayInputStream(buildZip(emptyList())),
            temporaryFolder.newFolder("staging"),
        )

        assertEquals(RtpImportRejection.EmptyArchive, rejection)
        assertTrue("既有内容不得被空来源清掉", File(existing, "gfx.png").isFile)
        assertEquals("user-rtp", File(existing, "gfx.png").readText())
    }

    @Test
    fun successfulExtractionReportsNoRejection() {
        val dest = temporaryFolder.newFolder("ok")
        val rejection = RpgMakerRuntimeEnvironment.extractZipStream(
            ByteArrayInputStream(buildZip(listOf("app/data.txt" to "hello".toByteArray()))),
            dest,
        )
        assertEquals(null, rejection)
        assertEquals("hello", File(dest, "app/data.txt").readText())
    }

    @Test
    fun oversizeEntryReportsRejectionAndLeavesNoPartialFile() {
        val dest = temporaryFolder.newFolder("oversize")
        val rejection = RpgMakerRuntimeEnvironment.extractZipStream(
            ByteArrayInputStream(buildZip(listOf("app/big.bin" to ByteArray(4 * 1024 * 1024)))),
            dest,
            maxEntryBytes = 1024 * 1024,
        )
        assertNotNull("超限必须上报类型化原因", rejection)
        assertTrue(rejection is RtpImportRejection.EntrySizeExceeded)
        assertFalse("超限条目不得留下截断文件", File(dest, "app/big.bin").exists())
    }
}
