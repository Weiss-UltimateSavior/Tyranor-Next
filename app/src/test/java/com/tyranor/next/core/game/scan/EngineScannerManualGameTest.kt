package com.tyranor.next.core.game.scan

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重扫合并：手动添加的 PC 游戏不参与扫描，重扫时必须保留；同 uri 被扫描命中则以扫描结果为准。
 */
class EngineScannerManualGameTest {

    private fun game(uri: String, engine: EngineType, title: String = uri) = ScanGame(
        title = title,
        uri = uri,
        engine = engine,
        launchTarget = "DIR",
    )

    @Test
    fun keepsManualPcGamesMissingFromScan() {
        val manual = game("content://tree/pc1", EngineType.PC)
        val scanned = listOf(game("content://tree/krkr", EngineType.KIRIKIRI))

        val merged = EngineScanner.mergeScannedWithManual(listOf(manual, scanned[0].copy(engine = EngineType.KIRIKIRI)), scanned)

        assertTrue(merged.contains(manual))
        assertEquals(2, merged.size)
    }

    @Test
    fun scannedEntryWinsWhenSameUri() {
        val manual = game("content://tree/pc1", EngineType.PC, title = "旧标题")
        val scannedSameUri = game("content://tree/pc1", EngineType.KIRIKIRI, title = "扫描标题")

        val merged = EngineScanner.mergeScannedWithManual(listOf(manual), listOf(scannedSameUri))

        assertEquals(1, merged.size)
        assertEquals("扫描标题", merged.first().title)
    }

    @Test
    fun nonPcManualEntriesFollowScanResult() {
        // 非 PC 的历史条目（例如从扫描根移除后的残留）不在保护范围，按原语义由扫描结果重建
        val stale = game("content://tree/stale", EngineType.ONS)

        val merged = EngineScanner.mergeScannedWithManual(listOf(stale), emptyList())

        assertTrue(merged.isEmpty())
    }
}
