package com.tyranor.next.core.game.scan

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.engine.runtime.RuntimeModuleDescriptor
import com.tyranor.next.core.engine.runtime.RuntimeModuleScanSpec
import com.tyranor.next.core.engine.runtime.RuntimeModuleSnapshot
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.ui.main.mergeChangedGameFields
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 远程游玩状态绑定字段在序列化、扫描合并与并发持久化中的保留回归。
 */
class RemoteBindingPreservationTest {

    @Test
    fun nextmoeIdWithSeparatorsOrNewlinesIsSanitizedOnSerializeAndRoundTripped() {
        val game = ScanGame(
            title = "NextMoe Game",
            uri = "content://games/nm",
            engine = EngineType.KIRIKIRI,
            launchTarget = "data.xp3",
            vndbId = "v17",
            bangumiId = "4154",
            hikarinagiId = "hkn-1",
            nextmoeId = "207\u0001379\nend",
        )
        val encoded = GameRecordCodec.serializeGame(game)
        val decoded = GameRecordCodec.parseGame(encoded)
        assertEquals("207 379 end", decoded?.nextmoeId)
        assertEquals("v17", decoded?.vndbId)
        assertEquals("4154", decoded?.bangumiId)
        assertEquals("hkn-1", decoded?.hikarinagiId)
    }

    @Test
    fun engineScannerMergePreservesAllRemoteBindingIdsAndMetadata() {
        val saved = ScanGame(
            title = "Saved Title",
            uri = "content://games/rescan",
            engine = EngineType.KIRIKIRI,
            launchTarget = "data.xp3",
            coverUri = "file:///covers/rescan.jpg",
            coverSource = AppSettingsStore.COVER_SOURCE_NEXTMOE,
            vndbId = "v562",
            bangumiId = "4154",
            hikarinagiId = "gal-9",
            nextmoeId = "207379",
            metadataTitle = "Sakura no Uta",
            openTime = 12345L,
        )
        val rescanned = ScanGame(
            title = "Rescanned Title",
            uri = "content://games/rescan",
            engine = EngineType.KIRIKIRI,
            launchTarget = "data.xp3",
        )

        val merged = EngineScanner.mergeScannedGame(
            current = rescanned,
            saved = saved,
            allowedModules = emptySet(),
            discoverySucceeded = true,
        )

        assertEquals("v562", merged.vndbId)
        assertEquals("4154", merged.bangumiId)
        assertEquals("gal-9", merged.hikarinagiId)
        assertEquals("207379", merged.nextmoeId)
        assertEquals("Sakura no Uta", merged.metadataTitle)
        assertEquals("file:///covers/rescan.jpg", merged.coverUri)
        assertEquals(AppSettingsStore.COVER_SOURCE_NEXTMOE, merged.coverSource)
        assertEquals(12345L, merged.openTime)
    }

    @Test
    fun mergeChangedGameFieldsPreservesConcurrentMultiSourceBindingUpdates() {
        val initial = ScanGame(
            title = "Sakura",
            uri = "content://games/concurrent",
            engine = EngineType.KIRIKIRI,
            launchTarget = "data.xp3",
            vndbId = "v1",
        )
        // 先行写已将 nextmoeId 写入存储（作为 base）
        val persistedBase = initial.copy(nextmoeId = "207379")
        // 并发后发写只把 bangumiId 和 hikarinagiId 从 initial 更新
        val concurrentUpdated = initial.copy(bangumiId = "4154", hikarinagiId = "gal-2")

        val merged = mergeChangedGameFields(
            base = persistedBase,
            before = initial,
            updated = concurrentUpdated,
        )

        assertEquals("v1", merged.vndbId)
        assertEquals("207379", merged.nextmoeId)
        assertEquals("4154", merged.bangumiId)
        assertEquals("gal-2", merged.hikarinagiId)
    }

    @Test
    fun mergeRescanResultsPreservesRemoteBindingsAcrossRuntimeModuleAndSystemNnnFallback() {
        val pkg = "com.tyranor.runtime.emberfall"
        val saved = ScanGame(
            title = "Saved Runtime Game",
            uri = "content://games/runtime_sysnnn",
            engine = EngineType.RUNTIME_MODULE,
            launchTarget = "",
            coverUri = "file:///covers/rt.jpg",
            coverSource = AppSettingsStore.COVER_SOURCE_VNDB,
            vndbId = "v100",
            bangumiId = "200",
            hikarinagiId = "300",
            nextmoeId = "400",
            metadataTitle = "Runtime Meta",
            runtimeModulePackage = pkg,
            openTime = 555L,
        )
        val rescannedAsBuiltIn = ScanGame(
            title = "Rescanned SystemNNN",
            uri = saved.uri,
            engine = EngineType.SYSTEMNNN,
            launchTarget = "spt/sys.spt",
        )
        val enabledDescriptor = RuntimeModuleDescriptor(
            packageName = pkg,
            label = "Emberfall",
            versionName = "1.0",
            versionCode = 1L,
            protocol = 1,
            activityName = "$pkg.MainActivity",
            scan = RuntimeModuleScanSpec(
                match = RuntimeModuleScanSpec.Match.ANY,
                extensions = listOf(".spt"),
                markers = emptyList(),
            ),
            enabled = true,
        )
        val snapshot = RuntimeModuleSnapshot(modules = listOf(enabledDescriptor), lastError = null)

        // 模块仍启用：保留 RUNTIME_MODULE 分支与全部四源绑定
        val mergedEnabled = EngineScanner.mergeRescanResults(
            savedGames = listOf(saved),
            scannedGames = listOf(rescannedAsBuiltIn),
            runtimeSnapshot = snapshot,
            enabledPackages = setOf(pkg),
        ).single()
        assertEquals(EngineType.RUNTIME_MODULE, mergedEnabled.engine)
        assertEquals("v100", mergedEnabled.vndbId)
        assertEquals("200", mergedEnabled.bangumiId)
        assertEquals("300", mergedEnabled.hikarinagiId)
        assertEquals("400", mergedEnabled.nextmoeId)
        assertEquals("Runtime Meta", mergedEnabled.metadataTitle)

        // 模块停用：降级到内置 SYSTEMNNN，但仍保留 runtimeModulePackage 与全部四源绑定
        val mergedDisabled = EngineScanner.mergeRescanResults(
            savedGames = listOf(saved),
            scannedGames = listOf(rescannedAsBuiltIn),
            runtimeSnapshot = snapshot,
            enabledPackages = emptySet(),
        ).single()
        assertEquals(EngineType.SYSTEMNNN, mergedDisabled.engine)
        assertEquals(pkg, mergedDisabled.runtimeModulePackage)
        assertEquals("v100", mergedDisabled.vndbId)
        assertEquals("200", mergedDisabled.bangumiId)
        assertEquals("300", mergedDisabled.hikarinagiId)
        assertEquals("400", mergedDisabled.nextmoeId)
        assertEquals("Runtime Meta", mergedDisabled.metadataTitle)

        // 手工/未重扫的 RUNTIME_MODULE 在模块停用时降级为 UNKNOWN 并保留绑定字段
        val manualDisabled = EngineScanner.mergeRescanResults(
            savedGames = listOf(saved.copy(uri = "content://games/manual_rt")),
            scannedGames = emptyList(),
            runtimeSnapshot = snapshot,
            enabledPackages = emptySet(),
        ).single()
        assertEquals(EngineType.UNKNOWN, manualDisabled.engine)
        assertEquals(pkg, manualDisabled.runtimeModulePackage)
        assertEquals("v100", manualDisabled.vndbId)
        assertEquals("200", manualDisabled.bangumiId)
        assertEquals("300", manualDisabled.hikarinagiId)
        assertEquals("400", manualDisabled.nextmoeId)
        assertEquals("file:///covers/rt.jpg", manualDisabled.coverUri)
    }
}
