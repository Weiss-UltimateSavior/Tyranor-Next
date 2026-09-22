package com.tyranor.next.core.engine.external

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.settings.EngineSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PPSSPP 版本设置：标准版 / 黄金版归一，以及跳转目标（包名）解析。
 */
class PpssppVersionSettingsTest {

    @Test
    fun versionNormalizationDefaultsToStandard() {
        assertEquals(EngineSettingsStore.PPSSPP_VERSION_STANDARD, EngineSettingsStore.normalizePpssppVersion(null))
        assertEquals(EngineSettingsStore.PPSSPP_VERSION_STANDARD, EngineSettingsStore.normalizePpssppVersion(""))
        assertEquals(EngineSettingsStore.PPSSPP_VERSION_STANDARD, EngineSettingsStore.normalizePpssppVersion("unknown"))
        assertEquals(EngineSettingsStore.PPSSPP_VERSION_GOLD, EngineSettingsStore.normalizePpssppVersion("GOLD"))
        assertEquals(EngineSettingsStore.PPSSPP_VERSION_GOLD, EngineSettingsStore.normalizePpssppVersion(" gold "))
    }

    @Test
    fun ppssppTargetMapsVersionToPackage() {
        assertEquals(
            ExternalEmulatorRegistry.PPSSPP_PACKAGE_GOLD,
            ExternalEmulatorRegistry.ppssppTarget(EngineSettingsStore.PPSSPP_VERSION_GOLD).packageName,
        )
        assertEquals(
            ExternalEmulatorRegistry.PPSSPP_PACKAGE_STANDARD,
            ExternalEmulatorRegistry.ppssppTarget(EngineSettingsStore.PPSSPP_VERSION_STANDARD).packageName,
        )
        // 未知值回退标准版
        assertEquals(
            ExternalEmulatorRegistry.PPSSPP_PACKAGE_STANDARD,
            ExternalEmulatorRegistry.ppssppTarget(null).packageName,
        )
    }

    @Test
    fun bothPpssppVariantsShareActivityAndStayRegisteredForPsp() {
        assertEquals(ExternalEmulatorRegistry.ppssppStandard.activityName, ExternalEmulatorRegistry.ppssppGold.activityName)
        assertTrue(ExternalEmulatorRegistry.ppssppStandard.supports(EngineType.PSP))
        assertTrue(ExternalEmulatorRegistry.ppssppGold.supports(EngineType.PSP))
        // 安装探测覆盖两个包名（引擎页/跳转弹窗按版本取用）
        val packages = ExternalEmulatorRegistry.targets.map { it.packageName }
        assertTrue(ExternalEmulatorRegistry.PPSSPP_PACKAGE_STANDARD in packages)
        assertTrue(ExternalEmulatorRegistry.PPSSPP_PACKAGE_GOLD in packages)
    }
}
