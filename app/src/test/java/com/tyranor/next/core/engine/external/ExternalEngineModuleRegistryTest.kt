package com.tyranor.next.core.engine.external

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.settings.EngineSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalEngineModuleRegistryTest {
    @Test
    fun resolvesRenPyByEngineAndAliases() {
        assertSame(RenPyExternalEngineModule, ExternalEngineModuleRegistry.moduleForEngine(EngineType.RENPY))
        assertSame(RenPyExternalEngineModule, ExternalEngineModuleRegistry.moduleForAlias("internal.renpy"))
        assertSame(RenPyExternalEngineModule, ExternalEngineModuleRegistry.moduleForAlias("external.renpy"))
        assertSame(RenPyExternalEngineModule, ExternalEngineModuleRegistry.moduleForAlias(RenPyExternalEngineModule.packageName))
        assertTrue(ExternalEngineModuleRegistry.isExternalEngine(EngineType.RENPY))
        assertTrue(RenPyExternalEngineModule.installUrl.orEmpty().endsWith("/RenPy-Plugin-8.5.apk"))
    }

    @Test
    fun resolvesRenPyVersions() {
        assertSame(RenPyExternalEngineModule, ExternalEngineModuleRegistry.moduleForRenpyVersion(EngineSettingsStore.RENPY_85))
        assertSame(RenPy77ExternalEngineModule, ExternalEngineModuleRegistry.moduleForRenpyVersion(EngineSettingsStore.RENPY_77))
        assertNull(ExternalEngineModuleRegistry.moduleForRenpyVersion(EngineSettingsStore.RENPY_AUTO))
        assertNull(ExternalEngineModuleRegistry.moduleForRenpyVersion("unknown"))
        assertNull(ExternalEngineModuleRegistry.moduleForRenpyVersion(""))
        assertSame(RenPyExternalEngineModule, ExternalEngineModuleRegistry.moduleForRenpyVersion(" 8.5 "))
    }

    @Test
    fun resolveModulePicksVersionTargetForRenpy() {
        // 全局/生效版本决定 Ren'Py 目标模块（而非「任一版本已装」），与启动/下载一致
        assertSame(RenPy77ExternalEngineModule, ExternalEngineModuleRegistry.resolveModule(EngineType.RENPY, EngineSettingsStore.RENPY_77))
        assertSame(RenPyExternalEngineModule, ExternalEngineModuleRegistry.resolveModule(EngineType.RENPY, EngineSettingsStore.RENPY_85))
        assertSame(RenPyExternalEngineModule, ExternalEngineModuleRegistry.resolveModule(EngineType.RENPY, EngineSettingsStore.RENPY_AUTO))
        assertSame(
            RenPy77ExternalEngineModule,
            ExternalEngineModuleRegistry.resolveModule(
                EngineType.RENPY,
                EngineSettingsStore.RENPY_AUTO,
                detectedRenpyVersion = EngineSettingsStore.RENPY_77,
            ),
        )
        assertSame(
            RenPyExternalEngineModule,
            ExternalEngineModuleRegistry.resolveModule(
                EngineType.RENPY,
                EngineSettingsStore.RENPY_85,
                detectedRenpyVersion = EngineSettingsStore.RENPY_77,
            ),
        )
        assertSame(RenPyExternalEngineModule, ExternalEngineModuleRegistry.resolveModule(EngineType.RENPY, null))
        assertSame(RenPyExternalEngineModule, ExternalEngineModuleRegistry.resolveModule(EngineType.RENPY, "unknown"))
        // 其余引擎返回其唯一模块，忽略版本
        assertSame(RpgMakerExternalEngineModule, ExternalEngineModuleRegistry.resolveModule(EngineType.RPGMAKER, EngineSettingsStore.RENPY_77))
        assertNull(ExternalEngineModuleRegistry.resolveModule(EngineType.KIRIKIRI))
    }

    @Test
    fun renpyVersionModuleProtocols() {
        assertTrue(RenPy77ExternalEngineModule.installUrl.orEmpty().endsWith("/RenPy-Plugin-7.7.1.apk"))
        assertEquals("cyou.joiplay.runtime.renpy.run", RenPy77ExternalEngineModule.action)
        assertEquals("cyou.joiplay.runtime.renpy.v7d7d1", RenPy77ExternalEngineModule.packageName)
    }

    @Test
    fun runtimeVersionsRequireGameDirectory() {
        assertTrue(RenPyExternalEngineModule.requiresGameDirectoryPath)
        assertTrue(RenPy77ExternalEngineModule.requiresGameDirectoryPath)
        // 版本选择链路：每游戏版本 → 对应模块 → 是否依赖目录解析
        assertSame(RenPy77ExternalEngineModule, ExternalEngineModuleRegistry.moduleForRenpyVersion(EngineSettingsStore.RENPY_77))
        assertTrue(ExternalEngineModuleRegistry.moduleForRenpyVersion(EngineSettingsStore.RENPY_77)!!.requiresGameDirectoryPath)
    }

    @Test
    fun resolvesRpgMakerByEngineAndAliases() {
        assertSame(RpgMakerExternalEngineModule, ExternalEngineModuleRegistry.moduleForEngine(EngineType.RPGMAKER))
        assertSame(RpgMakerExternalEngineModule, ExternalEngineModuleRegistry.moduleForAlias("internal.rpgmaker"))
        assertSame(RpgMakerExternalEngineModule, ExternalEngineModuleRegistry.moduleForAlias("internal.rpgmxp"))
        assertSame(RpgMakerExternalEngineModule, ExternalEngineModuleRegistry.moduleForAlias("internal.rpgmvx"))
        assertSame(RpgMakerExternalEngineModule, ExternalEngineModuleRegistry.moduleForAlias("internal.rpgmvxace"))
        assertSame(RpgMakerExternalEngineModule, ExternalEngineModuleRegistry.moduleForAlias("internal.mkxp-z"))
        assertSame(RpgMakerExternalEngineModule, ExternalEngineModuleRegistry.moduleForAlias(RpgMakerExternalEngineModule.packageName))
        assertTrue(ExternalEngineModuleRegistry.isExternalEngine(EngineType.RPGMAKER))
        assertTrue(RpgMakerExternalEngineModule.installUrl.orEmpty().endsWith("/RPGM-Plugin.apk"))
    }
}
