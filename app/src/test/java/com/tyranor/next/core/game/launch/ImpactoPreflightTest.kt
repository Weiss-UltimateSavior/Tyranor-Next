package com.tyranor.next.core.game.launch

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.save.GameSaveManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ImpactoPreflightTest {
    @Test fun gatesOnlyThePinnedRuntimeRequirements() {
        assertNull(ImpactoPreflight.check(28, listOf("arm64-v8a"), 0x30002))
        assertNull(ImpactoPreflight.check(36, listOf("arm64-v8a", "armeabi-v7a"), 0x40000))
        assertEquals(ImpactoPrepareError.ANDROID_VERSION, ImpactoPreflight.check(27, listOf("arm64-v8a"), 0x30002))
        assertEquals(ImpactoPrepareError.ABI, ImpactoPreflight.check(28, listOf("x86_64"), 0x30002))
        listOf(null, 0, -1, 0x30000, 0x30001).forEach {
            assertEquals(ImpactoPrepareError.GLES, ImpactoPreflight.check(28, listOf("arm64-v8a"), it))
        }
    }

    @Test fun unsupportedSaveManagementIsNotAdvertised() {
        assertFalse(GameSaveManager.supportsFileSaves(EngineType.IMPACTO))
    }
}
