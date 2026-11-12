package com.tyranor.next.core.engine.runtime

import com.core.engine.LaunchContract
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

    @Test
    fun engineShellAgreesWithTheExternalLaunchProtocol() {
        assertEquals(RuntimeModuleContract.ACTION_LAUNCH, LaunchContract.RUNTIME_MODULE_ACTION)
        assertEquals(RuntimeModuleContract.EXTRA_FOLDER_PATH, LaunchContract.RUNTIME_MODULE_FOLDER_PATH)
        assertEquals(RuntimeModuleContract.EXTRA_GAME_TITLE, LaunchContract.RUNTIME_MODULE_GAME_TITLE)
        assertEquals(RuntimeModuleContract.EXTRA_PROTOCOL_VERSION, LaunchContract.RUNTIME_MODULE_PROTOCOL_VERSION)
        assertEquals(RuntimeModuleContract.PROTOCOL_VERSION, LaunchContract.RUNTIME_MODULE_VERSION)
    }
}
