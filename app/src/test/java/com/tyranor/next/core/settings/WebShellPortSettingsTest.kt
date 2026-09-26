package com.tyranor.next.core.settings

import com.core.engine.LaunchContract
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Web 壳端口归一：1..65535 合法；空/0/越界/非数字回退默认固定端口（23333）。
 */
class WebShellPortSettingsTest {

    @Test
    fun invalidValuesFallBackToDefault() {
        assertEquals(LaunchContract.WEB_SHELL_PORT_DEFAULT, EngineSettingsStore.normalizeWebShellPort(null))
        assertEquals(LaunchContract.WEB_SHELL_PORT_DEFAULT, EngineSettingsStore.normalizeWebShellPort(""))
        assertEquals(LaunchContract.WEB_SHELL_PORT_DEFAULT, EngineSettingsStore.normalizeWebShellPort("abc"))
        assertEquals(LaunchContract.WEB_SHELL_PORT_DEFAULT, EngineSettingsStore.normalizeWebShellPort("0"))
        assertEquals(LaunchContract.WEB_SHELL_PORT_DEFAULT, EngineSettingsStore.normalizeWebShellPort("-1"))
        assertEquals(LaunchContract.WEB_SHELL_PORT_DEFAULT, EngineSettingsStore.normalizeWebShellPort("65536"))
    }

    @Test
    fun validPortsAreKept() {
        assertEquals(1, EngineSettingsStore.normalizeWebShellPort("1"))
        assertEquals(23333, EngineSettingsStore.normalizeWebShellPort("23333"))
        assertEquals(65535, EngineSettingsStore.normalizeWebShellPort(" 65535 "))
    }

    @Test
    fun perGameOverrideWinsOnlyWhenValid() {
        assertEquals(25555, EffectiveEngineSettings.resolveWebShellPort("25555", 23333))
        // 非法/空覆盖回退全局而不是默认值
        assertEquals(23333, EffectiveEngineSettings.resolveWebShellPort("0", 23333))
        assertEquals(23333, EffectiveEngineSettings.resolveWebShellPort("", 23333))
        assertEquals(23333, EffectiveEngineSettings.resolveWebShellPort("abc", 23333))
        assertEquals(23333, EffectiveEngineSettings.resolveWebShellPort(null, 23333))
        assertEquals(65111, EffectiveEngineSettings.resolveWebShellPort("65111", 23333))
    }

    @Test
    fun defaultMatchesTyranorOriginalPort() {
        assertEquals(23333, LaunchContract.WEB_SHELL_PORT_DEFAULT)
    }
}
