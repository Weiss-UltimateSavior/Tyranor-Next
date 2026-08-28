package com.tyranor.next.core.engine.external

import android.content.Intent
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
        assertSame(RenPy80ExternalEngineModule, ExternalEngineModuleRegistry.moduleForRenpyVersion(EngineSettingsStore.RENPY_803))
        assertSame(RenPy77ExternalEngineModule, ExternalEngineModuleRegistry.moduleForRenpyVersion(EngineSettingsStore.RENPY_77))
        assertNull(ExternalEngineModuleRegistry.moduleForRenpyVersion(EngineSettingsStore.RENPY_AUTO))
        assertNull(ExternalEngineModuleRegistry.moduleForRenpyVersion("unknown"))
        assertNull(ExternalEngineModuleRegistry.moduleForRenpyVersion(""))
        assertSame(RenPyExternalEngineModule, ExternalEngineModuleRegistry.moduleForRenpyVersion(" 8.5 "))
    }

    @Test
    fun modulesForEngineAggregatesVersionModules() {
        val renpy = ExternalEngineModuleRegistry.modulesForEngine(EngineType.RENPY)
        assertEquals(3, renpy.size)
        assertEquals(setOf("renpy85", "renpy80", "renpy77"), renpy.map { it.id }.toSet())
        assertEquals(1, ExternalEngineModuleRegistry.modulesForEngine(EngineType.RPGMAKER).size)
        assertEquals(0, ExternalEngineModuleRegistry.modulesForEngine(EngineType.KIRIKIRI).size)
    }

    @Test
    fun renpyVersionModuleProtocols() {
        assertTrue(RenPy80ExternalEngineModule.installUrl.orEmpty().endsWith("/RenPy-Plugin-8.0.3.apk"))
        assertTrue(RenPy77ExternalEngineModule.installUrl.orEmpty().endsWith("/RenPy-Plugin-7.7.1.apk"))
        assertEquals("cyou.joiplay.runtime.renpy.run", RenPy77ExternalEngineModule.action)
        assertEquals("cyou.joiplay.runtime.renpy.v7d7d1", RenPy77ExternalEngineModule.packageName)
        assertEquals("cyou.joiplay.renpy", RenPy80ExternalEngineModule.packageName)
        assertEquals(Intent.ACTION_MAIN, RenPy80ExternalEngineModule.action)
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
