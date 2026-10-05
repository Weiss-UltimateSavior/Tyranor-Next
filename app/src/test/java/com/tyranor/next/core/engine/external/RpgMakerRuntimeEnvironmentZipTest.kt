package com.tyranor.next.core.engine.external

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * RTP 导入的 ZIP 解压上限回归。
 *
 * 重点覆盖「被过滤条目」这条绕过通道：`__MACOSX/` 与 `.DS_Store` 条目会被跳过，
 * 而 ZipInputStream 是顺序流——跳过必须读掉该条目的全部数据，`closeEntry()` 会把
 * DEFLATED 条目**完整解压**（实测 255KB 的 zip、条目解压后 256MB，光 closeEntry()
 * 就耗时 586ms）。因此跳过分支若不做有界读取，就能绕过单条目与总量上限。
 * 又因为流式 zip 的 `entry.size` 恒为 -1，声明大小检查在此类 zip 上完全失效，
 * 必须靠读取时的计数兜底——这也是本测试注入小上限即可复现的原因。
 */
class RpgMakerRuntimeEnvironmentZipTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** 构造 zip：`entries` 为 名字 → 字节数（用于制造高压缩比的重复数据）。 */
    private fun buildZip(entries: List<Triple<String, Int, ByteArray?>>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            entries.forEach { (name, size, literal) ->
                zip.putNextEntry(ZipEntry(name))
                if (literal != null) {
                    zip.write(literal)
                } else {
                    val chunk = ByteArray(64 * 1024)
                    var remaining = size
                    while (remaining > 0) {
                        val n = minOf(chunk.size, remaining)
                        zip.write(chunk, 0, n)
                        remaining -= n
                    }
                }
                zip.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun run(zipBytes: ByteArray, dest: File, maxEntries: Int = 20_000,
                    maxEntry: Long = 256L * 1024 * 1024, maxTotal: Long = 1024L * 1024 * 1024): Boolean =
        RpgMakerRuntimeEnvironment.extractZipStream(
            ByteArrayInputStream(zipBytes), dest, maxEntries, maxEntry, maxTotal,
        )

    @Test
    fun filteredMacosxEntryIsBoundedByPerEntryLimit() {
        val dest = temporaryFolder.newFolder("rtp1")
        // 被过滤的条目超过单条目上限：必须中止，而不能靠 closeEntry() 无界排空
        val zip = buildZip(
            listOf(
                Triple("__MACOSX/big.bin", 4 * 1024 * 1024, null),
                Triple("ok.txt", 0, "ok".toByteArray()),
            ),
        )
        assertFalse("被过滤的超大条目必须中止导入", run(zip, dest, maxEntry = 1024 * 1024))
    }

    @Test
    fun filteredDsStoreEntryIsBoundedByTotalLimit() {
        val dest = temporaryFolder.newFolder("rtp2")
        // 多个被过滤条目累计超总量：必须中止
        val zip = buildZip(
            listOf(
                Triple("a/.DS_Store", 1024 * 1024, null),
                Triple("b/.DS_Store", 1024 * 1024, null),
                Triple("c/.DS_Store", 1024 * 1024, null),
            ),
        )
        assertFalse(
            "被过滤条目累计超总量必须中止",
            run(zip, dest, maxEntry = 10 * 1024 * 1024, maxTotal = 2 * 1024 * 1024),
        )
    }

    @Test
    fun outOfRootEntryIsAlsoBounded() {
        val dest = temporaryFolder.newFolder("rtp3")
        // 越界条目走另一个跳过分支，同样必须有界
        val zip = buildZip(
            listOf(
                Triple("../escape/evil.bin", 4 * 1024 * 1024, null),
                Triple("ok.txt", 0, "ok".toByteArray()),
            ),
        )
        assertFalse("越界条目也必须受限", run(zip, dest, maxEntry = 1024 * 1024))
        assertFalse(
            "越界条目不得写出到 dest 之外",
            File(dest.parentFile, "escape/evil.bin").exists(),
        )
    }

    @Test
    fun normalZipWithFilteredMetadataStillSucceeds() {
        val dest = temporaryFolder.newFolder("rtp4")
        // 真实场景：macOS 打出的包带 __MACOSX 与 .DS_Store，内容很小，应正常导入
        val zip = buildZip(
            listOf(
                Triple("__MACOSX/._file", 0, ByteArray(128)),
                Triple(".DS_Store", 0, ByteArray(64)),
                Triple("app/data.txt", 0, "hello".toByteArray()),
                Triple("app/sub/nested.txt", 0, "nested".toByteArray()),
            ),
        )
        assertTrue("正常包应导入成功", run(zip, dest))
        assertEquals("hello", File(dest, "app/data.txt").readText())
        assertEquals("nested", File(dest, "app/sub/nested.txt").readText())
        assertFalse("过滤条目不应落盘", File(dest, ".DS_Store").exists())
        assertFalse("__MACOSX 不应落盘", File(dest, "__MACOSX").exists())
    }

    @Test
    fun entryCountLimitCoversFilteredEntries() {
        val dest = temporaryFolder.newFolder("rtp5")
        // 大量被过滤条目：条目数上限必须在跳过**之前**生效（上一轮已修，这里回归锁定）
        val zip = buildZip(List(20) { Triple("__MACOSX/f$it", 0, ByteArray(8)) })
        assertFalse("被过滤条目也要计入条目数上限", run(zip, dest, maxEntries = 10))
    }

    @Test
    fun normalEntryOverLimitIsRejected() {
        val dest = temporaryFolder.newFolder("rtp6")
        val zip = buildZip(listOf(Triple("app/big.bin", 4 * 1024 * 1024, null)))
        assertFalse(run(zip, dest, maxEntry = 1024 * 1024))
        assertFalse("超限条目不应留下文件", File(dest, "app/big.bin").exists())
    }
}
