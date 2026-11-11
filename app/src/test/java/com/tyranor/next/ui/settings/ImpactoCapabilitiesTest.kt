package com.tyranor.next.ui.settings

import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertNull
import org.junit.Test

class ImpactoCapabilitiesTest {
    @Test fun noUnimplementedSettingsOrEnhancementPipelineIsAdvertised() {
        assertNull(engineSettingsKindOf(EngineType.IMPACTO))
        assertNull(EngineType.IMPACTO.graphicsFamily())
    }
}
