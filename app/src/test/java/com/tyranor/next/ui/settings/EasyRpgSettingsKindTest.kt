package com.tyranor.next.ui.settings

import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertEquals
import org.junit.Test

class EasyRpgSettingsKindTest {
    @Test
    fun easyRpgEngineHasItsOwnGlobalSettingsPage() {
        assertEquals(EngineSettingsKind.EASYRPG, engineSettingsKindOf(EngineType.EASYRPG))
    }
}
