package com.core.input

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [InputConfigStore.replaceViaBackup] 的数据安全回归。
 *
 * 核心不变量：**替换失败时旧配置必须还在**。先前实现是「删掉目标再 rename」，只要第二次
 * rename 仍失败，新旧配置会一起消失——这是用户方案的丢失路径，必须由测试锁住。
 */
class InputConfigStoreBackupTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun files(name: String): Triple<File, File, File> {
        val dir = temporaryFolder.newFolder(name)
        return Triple(File(dir, "tmp"), File(dir, "config.json"), File(dir, "config.json.bak"))
    }

    @Test
    fun successReplacesTargetAndRemovesBackup() {
        val (tmp, target, backup) = files("ok")
        tmp.writeText("new")
        target.writeText("old")

        assertTrue(InputConfigStore.replaceViaBackup(tmp, target, backup))

        assertEquals("new", target.readText())
        assertFalse("成功后备份必须清掉", backup.exists())
        assertFalse("成功后临时文件已改名，不应存在", tmp.exists())
    }

    @Test
    fun failedInstallRestoresOriginalContent() {
        // 注入失败：tmp 不存在 → 第二次 rename 必然失败
        val (tmp, target, backup) = files("fail")
        target.writeText("user-layout")

        assertFalse("替换失败必须返回 false", InputConfigStore.replaceViaBackup(tmp, target, backup))

        assertTrue("旧配置必须仍在", target.exists())
        assertEquals("user-layout", target.readText())
    }

    @Test
    fun staleBackupFromInterruptedRunIsRestoredFirst() {
        // 上一次替换被杀：备份在、目标缺失。替换前必须先把备份归位，
        // 否则 backup.delete() 会删掉盘上唯一的数据
        val (tmp, target, backup) = files("stale")
        backup.writeText("precious")
        tmp.writeText("new")

        assertTrue(InputConfigStore.replaceViaBackup(tmp, target, backup))

        assertEquals("new", target.readText())
        assertFalse(backup.exists())
    }

    @Test
    fun staleBackupSurvivesWhenInstallFails() {
        // 同上但本次替换失败：旧内容（来自备份）必须被恢复，而不是随备份一起丢失
        val (tmp, target, backup) = files("stale-fail")
        backup.writeText("precious")

        assertFalse(InputConfigStore.replaceViaBackup(tmp, target, backup))

        assertTrue("中断运行的旧数据必须回到原位", target.exists())
        assertEquals("precious", target.readText())
    }

    @Test
    fun staleBackupAlongsideValidTargetIsSuperseded() {
        // 目标有效、备份是上一次替换成功后被杀留下的孤儿：替换成功时旧目标内容
        // 经备份中转后整体清掉，不得留下孤儿文件（否则下次替换把它当残留删除，
        // 等于是静默丢弃一份「看起来最新」的数据）
        val (tmp, target, backup) = files("stale-orphan")
        tmp.writeText("new")
        target.writeText("current")
        backup.writeText("superseded")

        assertTrue(InputConfigStore.replaceViaBackup(tmp, target, backup))

        assertEquals("new", target.readText())
        assertFalse("孤儿备份必须清掉", backup.exists())
    }
}
