package com.tyranor.next.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PrefsRenameMigration 的合并语义回归：
 * 「目标已有任意键即整体跳过」会让升级场景下旧 prefs 的设置整批丢失（引擎子进程
 * 抢先写入版本号即可触发），必须保持「按键合并、目标已有键不覆盖」。
 */
class PrefsRenameMigrationMergeTest {

    @Test
    fun `legacy keys missing in target are copied`() {
        val legacy = mapOf("kr_renderer" to "opengl", "kr_fps_limit" to "30", "flag" to true)
        val merged = PrefsRenameMigration.computeMerge(legacy, targetExistingKeys = emptySet())
        assertEquals(legacy, merged)
    }

    @Test
    fun `keys already present in target are not overwritten`() {
        val legacy = mapOf("kr_renderer" to "software", "kr_fps_limit" to "30")
        val merged = PrefsRenameMigration.computeMerge(legacy, targetExistingKeys = setOf("kr_renderer"))
        // 目标已有的键被跳过（保留 target 现值，不进入拷贝集），缺失的键照常拷入
        assertEquals(null, merged["kr_renderer"])
        assertEquals("30", merged["kr_fps_limit"])
        assertEquals(1, merged.size)
    }

    @Test
    fun `merge into non-empty target still copies missing keys`() {
        // 升级场景核心回归：目标文件非空（已被组件写入部分键）时，其余旧设置必须继续迁移
        val legacy = mapOf("ons_encoding" to "sjis", "kr_engine_kernel" to "krkrsdl3")
        val targetExisting = setOf("app_version", "tyrano_external_network")
        val merged = PrefsRenameMigration.computeMerge(legacy, targetExisting)
        assertEquals(legacy, merged)
    }
}
