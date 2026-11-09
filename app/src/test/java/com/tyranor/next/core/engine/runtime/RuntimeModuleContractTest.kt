package com.tyranor.next.core.engine.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeModuleContractTest {
    @Test
    fun runtimeModuleV1RemainsCompatibleWithInstalledApks() {
        assertEquals("com.tyranor.runtime.action.LAUNCH", RuntimeModuleContract.ACTION_LAUNCH)
        assertEquals("com.tyranor.runtime.extra.FOLDER_PATH", RuntimeModuleContract.EXTRA_FOLDER_PATH)
        assertEquals("com.tyranor.runtime.extra.GAME_TITLE", RuntimeModuleContract.EXTRA_GAME_TITLE)
        assertEquals("com.tyranor.runtime.extra.PROTOCOL_VERSION", RuntimeModuleContract.EXTRA_PROTOCOL_VERSION)
        assertEquals(1, RuntimeModuleContract.PROTOCOL_VERSION)
    }
}
