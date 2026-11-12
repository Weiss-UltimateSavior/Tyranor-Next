package com.tyranor.next.ui.main

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 用户改动合并：重命名/换封面不丢运行时模块绑定，显式改包名时采用 updated。 */
class MainLibraryViewModelRuntimeModuleTest {

    private fun game(packageName: String?) = ScanGame(
        title = "Runtime Game",
        uri = "content://games/runtime",
        engine = EngineType.RUNTIME_MODULE,
        launchTarget = "DIR",
        runtimeModulePackage = packageName,
    )

    @Test
    fun mergeAdoptsUpdatedRuntimeModulePackage() {
        val base = game("com.tyranor.runtime.alpha")
        val before = base
        val updated = base.copy(runtimeModulePackage = "com.tyranor.runtime.beta")

        val merged = mergeChangedGameFields(base, before, updated)

        assertEquals("com.tyranor.runtime.beta", merged.runtimeModulePackage)
    }

    @Test
    fun mergeKeepsBaseRuntimeModulePackageWhenUpdateDidNotChangeIt() {
        val before = game("com.tyranor.runtime.alpha")
        // 并发刮削先写了封面；本地快照只改了标题，未触碰包名。
        val base = before.copy(coverUri = "file:///new.jpg")
        val updated = before.copy(title = "Renamed")

        val merged = mergeChangedGameFields(base, before, updated)

        assertEquals("com.tyranor.runtime.alpha", merged.runtimeModulePackage)
        assertEquals("Renamed", merged.title)
        assertEquals("file:///new.jpg", merged.coverUri)
    }

    @Test
    fun mergeAdoptsClearedRuntimeModulePackage() {
        val base = game("com.tyranor.runtime.alpha")
        val before = base
        val updated = base.copy(runtimeModulePackage = null)

        assertNull(mergeChangedGameFields(base, before, updated).runtimeModulePackage)
    }
}
