package com.tyranor.next.ui.settings

import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ImpactoSettingsKindTest {
    @Test
    fun magesEngineHasItsOwnGlobalSettingsPage() {
        // 引擎页弹窗的「全局引擎设置」按钮由该映射驱动：MAGES. 不能再落到“没有设置入口”。
        assertEquals(EngineSettingsKind.IMPACTO, engineSettingsKindOf(EngineType.IMPACTO))
    }

    @Test
    fun enginesWithoutSettingsStillHaveNoEntry() {
        assertNull(engineSettingsKindOf(EngineType.NINTENDO_SWITCH))
        assertNull(engineSettingsKindOf(EngineType.UNKNOWN))
        assertNotNull(engineSettingsKindOf(EngineType.RUFFLE))
    }
}
