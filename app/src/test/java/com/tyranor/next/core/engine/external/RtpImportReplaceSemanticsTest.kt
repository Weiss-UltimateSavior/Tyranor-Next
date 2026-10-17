package com.tyranor.next.core.engine.external

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        // 且恢复逻辑会把它归位（而不是报「未导入」）。
        // 注意：此前该测试只断言备份文件还在、从未执行恢复路径；这里真正调用
        // recoverPendingImport 验证归位（依赖 File，可在 JVM 上跑）。
        val parent = temporaryFolder.newFolder("parent")
        val target = File(parent, "app")
        val backup = File(parent, ".import_backup")
        backup.mkdirs()
        File(backup, "keep.txt").writeText("old-content")

        assertFalse("目标缺失时恢复前应显示未导入", target.exists())
        assertTrue("备份必须还在盘上（数据未丢）", File(backup, "keep.txt").isFile)

        assertTrue("恢复必须成功", RpgMakerRuntimeEnvironment.recoverPendingImport(target))
        assertTrue("旧数据必须归位到 app 目录", File(target, "keep.txt").isFile)
        assertEquals("old-content", File(target, "keep.txt").readText())
        assertFalse("归位后备份目录应被消费", backup.exists())
    }

    @Test
    fun recoveryRestoresEmptyTargetWithBackup() {
        // 目标为「空目录」+ 备份完整：空目标只可能是上次替换在 copyRecursively 刚建目录
        // 就被杀留下的半成品，备份才是完整旧数据（isRtpImported 只看目录非空，不归位会误报）
        val parent = temporaryFolder.newFolder("empty-target")
        val target = File(parent, "app").apply { mkdirs() }
        val backup = File(parent, ".import_backup").apply { mkdirs() }
        File(backup, "gfx.png").writeText("old-rtp")

        assertTrue(RpgMakerRuntimeEnvironment.recoverPendingImport(target))
        assertEquals("old-rtp", File(target, "gfx.png").readText())
        assertFalse(backup.exists())
    }

    @Test
    fun recoveryRestoresBackupWhenMarkerAndPartialTargetPresent() {
        // 提交标记 + 备份 + 半成品 target（copyRecursively 只拷了一半就被杀）：
        // 必须删掉半成品、放回备份、清掉标记——否则 isRtpImported 会报「已导入」却缺素材
        val parent = temporaryFolder.newFolder("marker-partial")
        val target = File(parent, "app").apply { mkdirs() }
        File(target, "half.bin").writeText("partial-copy")
        val backup = File(parent, ".import_backup").apply { mkdirs() }
        File(backup, "full.bin").writeText("complete-old")
        File(parent, ".importing").writeText("")

        assertTrue(RpgMakerRuntimeEnvironment.recoverPendingImport(target))
        assertFalse("半成品必须清除", File(target, "half.bin").exists())
        assertEquals("complete-old", File(target, "full.bin").readText())
        assertFalse("备份已消费", backup.exists())
        assertFalse("标记已清除", File(parent, ".importing").exists())
    }

    @Test
    fun recoveryClearsFirstImportPartialWhenNoBackup() {
        // 首次导入中断：标记 + 半成品 + 无备份 → 半成品无旧数据可保护，整体清除（连同标记）
        val parent = temporaryFolder.newFolder("marker-first")
        val target = File(parent, "app").apply { mkdirs() }
        File(target, "half.bin").writeText("partial-copy")
        File(parent, ".importing").writeText("")

        assertTrue(RpgMakerRuntimeEnvironment.recoverPendingImport(target))
        assertFalse("首次导入的半成品应被清除", target.exists())
        assertFalse("标记应被清除", File(parent, ".importing").exists())
    }

    @Test
    fun commitImportTempSuccessInstallsAndCleansMarkers() {
        // 提交段成功：新内容顶上、提交标记与备份都清掉（盘上不留「待恢复」现场）
        val parent = temporaryFolder.newFolder("commit")
        val temp = File(parent, ".import_tmp").apply { mkdirs() }
        File(temp, "gfx.png").writeText("new-rtp")
        val target = File(parent, "app").apply { mkdirs() }
        File(target, "old.bin").writeText("old-rtp")

        val rejection = RpgMakerRuntimeEnvironment.commitImportTemp(temp, target)
        assertEquals("成功不应返回拒绝原因", null, rejection)
        assertEquals("new-rtp", File(target, "gfx.png").readText())
        assertFalse("旧数据应被新内容替换", File(target, "old.bin").exists())
        assertFalse("备份应清掉", File(parent, ".import_backup").exists())
        assertFalse("提交标记应清掉", File(parent, ".importing").exists())
    }

    @Test
    fun emptyArchiveDoesNotDestroyExistingTarget() {
        // 空来源必须被拦在替换之前：旧实现会先删掉 target 再发现内容为空，
        // 用户的既有 RTP 就此消失。
        // 此前该测试把「既有 RTP」放在一个目录、却对另一个 staging 目录解压，
        // existing 完全不在调用路径上、断言恒真；这里直接走提交段验证。
        val parent = temporaryFolder.newFolder("empty")
        val temp = File(parent, ".import_tmp").apply { mkdirs() }
        val existing = File(parent, "app").apply { mkdirs() }
        File(existing, "gfx.png").writeText("user-rtp")

        val rejection = RpgMakerRuntimeEnvironment.commitImportTemp(temp, existing)

        assertEquals(RtpImportRejection.EmptyArchive, rejection)
        assertTrue("既有内容不得被空来源清掉", File(existing, "gfx.png").isFile)
        assertEquals("user-rtp", File(existing, "gfx.png").readText())
        assertFalse("空来源不得留下备份/标记现场", File(parent, ".import_backup").exists())
        assertFalse(File(parent, ".importing").exists())
    }

    @Test
    fun recoveryKeepsStateWhenPartialCannotBeDeleted() {
        // 注入删除失败：半成品 target 删不掉时，恢复必须**中止**（返回 false），
        // 保留「标记 + 备份」现场待重试——若继续删标记声称成功，下次恢复会把
        // 「非空 target + 备份」判成「已成功替换的残留」而删掉唯一旧数据。
        val parent = temporaryFolder.newFolder("delete-fail")
        val target = File(parent, "app").apply { mkdirs() }
        File(target, "half.bin").writeText("partial-copy")
        val backup = File(parent, ".import_backup").apply { mkdirs() }
        File(backup, "full.bin").writeText("complete-old")
        File(parent, ".importing").writeText("")

        val recovered = RpgMakerRuntimeEnvironment.recoverPendingImport(target) { false }

        assertFalse("删除失败时恢复必须中止", recovered)
        assertTrue("标记必须保留（下次恢复重试的依据）", File(parent, ".importing").exists())
        assertTrue("备份必须保留", File(backup, "full.bin").isFile)
        assertEquals("complete-old", File(backup, "full.bin").readText())
        assertTrue("半成品 target 不得被声称清除", File(target, "half.bin").isFile)
    }

    @Test
    fun recoveryKeepsStateWhenStaleBackupCannotBeDeleted() {
        // 目标有效 + 陈旧备份删不掉（成功路径清理失败留下的孤儿占住 .import_backup）：
        // 恢复必须中止而非继续——继续会让后续 target.renameTo(backup) 失败，被迫走
        // 「删旧数据而不备份」的危险分支
        val parent = temporaryFolder.newFolder("stale-delete-fail")
        val target = File(parent, "app").apply { mkdirs() }
        File(target, "current.bin").writeText("current")
        val backup = File(parent, ".import_backup").apply { mkdirs() }
        File(backup, "old.bin").writeText("old")

        val recovered = RpgMakerRuntimeEnvironment.recoverPendingImport(target) { false }

        assertFalse("陈旧备份删不掉时恢复必须中止", recovered)
        assertEquals("current", File(target, "current.bin").readText())
        assertTrue("备份仍保留", File(backup, "old.bin").isFile)
    }

    @Test
    fun commitAbortsWithoutDataLossWhenStaleBackupCannotBeDeleted() {
        // 与上一条同场景但走提交段入口：陈旧备份删不掉 → 本次导入必须放弃（IoFailed），
        // 旧数据（target）原样保留
        val parent = temporaryFolder.newFolder("commit-stale-fail")
        val temp = File(parent, ".import_tmp").apply { mkdirs() }
        File(temp, "new.bin").writeText("new-rtp")
        val target = File(parent, "app").apply { mkdirs() }
        File(target, "current.bin").writeText("current")
        val backup = File(parent, ".import_backup").apply { mkdirs() }
        File(backup, "old.bin").writeText("old")

        val rejection = RpgMakerRuntimeEnvironment.commitImportTemp(temp, target) { false }

        assertTrue("陈旧备份占位时必须放弃导入", rejection is RtpImportRejection.IoFailed)
        assertEquals("current", File(target, "current.bin").readText())
        assertTrue("旧数据仍在", File(backup, "old.bin").isFile)
        assertFalse("导入未完成，不得留下提交标记", File(parent, ".importing").exists())
    }

    @Test
    fun readyAppDirIsNullWhenRecoveryFailsWithoutLosingOldData() {
        // 启动路径（ensureRtpEnvironment）用的取目录接口：归位失败时必须返回 null，
        // 让调用方**不要** mkdirs / 写 sf.sf2——否则 target 变非空，下次恢复会把备份
        // 当「成功替换的陈旧孤儿」删掉，旧 RTP 永久丢失
        val root = temporaryFolder.newFolder("ready-fail")
        val target = File(root, "app").apply { mkdirs() }
        File(target, "half.bin").writeText("partial-copy")
        val backup = File(root, ".import_backup").apply { mkdirs() }
        File(backup, "full.bin").writeText("complete-old")
        File(root, ".importing").writeText("")

        val ready = RpgMakerRuntimeEnvironment.rtpAppDirIfReady(root) { false }

        assertNull("归位失败时不得交出可写目录", ready)
        assertTrue("备份（唯一旧数据）必须保留", File(backup, "full.bin").isFile)
        assertEquals("complete-old", File(backup, "full.bin").readText())
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

    // ---------- 并发导入：临时目录必须逐次唯一 ----------

    @Test
    fun clearRtpIsRefusedWhileAnImportIsInFlight() {
        // 「清除」与「导入」的互斥不能只靠 UI 按钮置灰：退出/重建设置页会丢掉那个标记，
        // 而导入在 IO 线程继续跑（阻塞文件 IO 不响应协程取消）。此时若清除把在途临时目录
        // 删掉，导入会拿残缺内容继续提交（半成品装成 app 并删掉备份）——必须由 core 拒绝。
        val parent = temporaryFolder.newFolder("RPGVX")
        val inFlightTemp = RpgMakerRuntimeEnvironment.newImportTempDir(parent).apply { mkdirs() }
        val otherParent = temporaryFolder.newFolder("RPGXP")
        val otherTemp = RpgMakerRuntimeEnvironment.newImportTempDir(otherParent).apply { mkdirs() }
        RpgMakerRuntimeEnvironment.registerInFlightImport("rpgmvx", inFlightTemp)
        RpgMakerRuntimeEnvironment.registerInFlightImport("rpgmxp", otherTemp)
        try {
            assertTrue(RpgMakerRuntimeEnvironment.isRtpImportInFlight("rpgmvx"))
            assertEquals(
                "在途导入期间清除必须被拒绝",
                RpgMakerRuntimeEnvironment.RtpClearResult.IMPORT_IN_FLIGHT,
                RpgMakerRuntimeEnvironment.clearRtp("rpgmvx"),
            )
            // 清除不得吞掉在途登记（它正是靠这条登记才拒绝的）。别写「不得删掉在途临时目录」：
            // clearRtp 删的是真实外部存储的 `<子类型>` 目录，与本测试的 TemporaryFolder 无关，
            // 那种断言在任何路径下都成立（第八轮复审）
            assertTrue("被拒绝的清除不得吞掉在途登记", RpgMakerRuntimeEnvironment.isRtpImportInFlight("rpgmvx"))
            // 另一子类型的登记必须仍然在列（非恒真断言：这条登记确实写过）
            assertTrue("被拒绝的清除不得动别的子类型登记", RpgMakerRuntimeEnvironment.isRtpImportInFlight("rpgmxp"))
        } finally {
            RpgMakerRuntimeEnvironment.unregisterInFlightImport("rpgmvx", inFlightTemp)
            RpgMakerRuntimeEnvironment.unregisterInFlightImport("rpgmxp", otherTemp)
        }
        assertFalse("导入结束后登记必须注销", RpgMakerRuntimeEnvironment.isRtpImportInFlight("rpgmvx"))
        assertFalse("注销后键集合为空必须摘除", RpgMakerRuntimeEnvironment.inFlightImports.containsKey("RPGXP"))
    }

    @Test
    fun sameSubtypeConcurrentImportsKeepIndependentRegistrations() {
        // 同子类型两次导入并发（UI 置灰可被页面重建绕过）：登记必须是集合。旧实现（键上只留最后
        // 一次登记 + 条件注销）正是在**后登记者先结束**时摘掉整条键，让先登记者（仍在途）失去
        // 登记，清除便能删掉它正在写的目录——因此注销顺序必须是「先注销后登记的那个」；反过来
        // （先注销先登记的那个）条件注销与当前值不匹配、是空操作，缺陷不显现，断言会变成恒真。
        val parent = temporaryFolder.newFolder("concurrent")
        val first = RpgMakerRuntimeEnvironment.newImportTempDir(parent).apply { mkdirs() }
        val second = RpgMakerRuntimeEnvironment.newImportTempDir(parent).apply { mkdirs() }
        RpgMakerRuntimeEnvironment.registerInFlightImport("mkxp-z", first)
        RpgMakerRuntimeEnvironment.registerInFlightImport("mkxp-z", second)
        try {
            RpgMakerRuntimeEnvironment.unregisterInFlightImport("mkxp-z", second)
            assertTrue(
                "后登记者注销后，先登记者必须仍在途（否则清除会删掉它的临时目录）",
                RpgMakerRuntimeEnvironment.isRtpImportInFlight("mkxp-z"),
            )
            assertEquals(
                RpgMakerRuntimeEnvironment.RtpClearResult.IMPORT_IN_FLIGHT,
                RpgMakerRuntimeEnvironment.clearRtp("mkxp-z"),
            )
        } finally {
            RpgMakerRuntimeEnvironment.unregisterInFlightImport("mkxp-z", first)
        }
        assertFalse(RpgMakerRuntimeEnvironment.isRtpImportInFlight("mkxp-z"))
    }

    @Test
    fun inFlightRegistrationIsKeyedByNormalizedDirectoryName() {
        // 登记键必须与目录名同源（子类型拼写的大小写/前后空白归一）：否则 UI 用 " RPGMVX "、
        // core 其它入口用 "rpgmvx" 时各登记一份，clearRtp 只认其中一种 —— 等于没有互斥
        // （清除会删掉另一次导入的临时目录）。
        // 注意拼写变体指**子类型**本身（rpgmvx / RPGMVX / " rpgmvx "），不是目录名（RPGVX）——
        // rtpDirName 只认子类型名，目录名再传进来会落到兜底分支。
        val parent = temporaryFolder.newFolder("normalized")
        val temp = RpgMakerRuntimeEnvironment.newImportTempDir(parent).apply { mkdirs() }
        RpgMakerRuntimeEnvironment.registerInFlightImport(" RPGMVX ", temp)
        try {
            assertTrue("大小写/空白变体必须命中同一条登记", RpgMakerRuntimeEnvironment.isRtpImportInFlight("rpgmvx"))
            assertEquals(
                "同一子类型的另一种拼写也必须被拒绝清除",
                RpgMakerRuntimeEnvironment.RtpClearResult.IMPORT_IN_FLIGHT,
                RpgMakerRuntimeEnvironment.clearRtp("RpgMvx"),
            )
        } finally {
            RpgMakerRuntimeEnvironment.unregisterInFlightImport("rpgmvx", temp)
        }
        assertFalse(RpgMakerRuntimeEnvironment.isRtpImportInFlight("rpgmvx"))
    }

    @Test
    fun importTempDirsAreUniquePerCall() {
        // 导入耗时可到分钟级，UI 未加在途守卫时两次导入会同时进行：
        // 固定名 .import_tmp 会让第二次 deleteRecursively() 删掉第一次正在写的目录，
        // 先提交者把两次内容混合 rename 成 app，后提交者以「读写失败：.import_tmp」失败
        val parent = temporaryFolder.newFolder("temp-unique")

        val first = RpgMakerRuntimeEnvironment.newImportTempDir(parent)
        val second = RpgMakerRuntimeEnvironment.newImportTempDir(parent)

        assertTrue("临时目录必须与固定名区分", first.name.startsWith(RpgMakerRuntimeEnvironment.IMPORT_TEMP_DIR))
        assertTrue("两次导入不得共用同一临时目录：$first / $second", first != second)
        assertEquals("临时目录必须落在同一父目录（同分区才能 rename）", parent, first.parentFile)
        assertEquals(parent, second.parentFile)
    }

    @Test
    fun staleImportTempCleanupKeepsActiveTempAndImportSiteState() {
        // 清陈旧临时目录时不得碰到 .importing / .import_backup / app：
        // 那是导入状态机与「唯一旧数据」的落点
        val parent = temporaryFolder.newFolder("stale-clean")
        val active = RpgMakerRuntimeEnvironment.newImportTempDir(parent).apply { mkdirs() }
        val stale = RpgMakerRuntimeEnvironment.newImportTempDir(parent).apply { mkdirs() }
        File(stale, "half.bin").writeText("half")
        val marker = File(parent, ".importing").apply { writeText("") }
        val backup = File(parent, ".import_backup").apply { mkdirs() }
        File(backup, "old.bin").writeText("old")
        val app = File(parent, "app").apply { mkdirs() }
        File(app, "data.bin").writeText("data")

        // 全部视为陈旧（清除路径即如此调用）
        RpgMakerRuntimeEnvironment.deleteStaleImportTemps(
            parent,
            keep = active,
            staleBefore = Long.MAX_VALUE,
        )

        assertTrue("在途临时目录必须保留", active.isDirectory)
        assertFalse("陈旧临时目录必须清理", stale.exists())
        assertTrue("提交标记不得被清理", marker.isFile)
        assertTrue("备份（唯一旧数据）不得被清理", File(backup, "old.bin").isFile)
        assertTrue("已导入内容不得被清理", File(app, "data.bin").isFile)
    }

    @Test
    fun freshImportTempSurvivesCleanupOfAnotherImport() {
        // 默认老化阈值下，新鲜临时目录可能是**并发的另一次导入**正在写的目录：
        // 删掉它等于让那次导入失败（清理只针对崩溃残留）
        val parent = temporaryFolder.newFolder("fresh-clean")
        val inFlight = RpgMakerRuntimeEnvironment.newImportTempDir(parent).apply { mkdirs() }
        File(inFlight, "half.bin").writeText("half")

        RpgMakerRuntimeEnvironment.deleteStaleImportTemps(parent, keep = null)

        assertTrue("新鲜（可能是在途）临时目录不得被清理", inFlight.isDirectory)
        assertTrue("其内容也不得被动", File(inFlight, "half.bin").isFile)
    }
}
