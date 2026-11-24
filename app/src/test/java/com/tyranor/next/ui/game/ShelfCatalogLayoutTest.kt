package com.tyranor.next.ui.game

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 书架布局「藏书阁」全景模式几何与群组编排单测：
 * 书脊/封面双形态响应式列数分档、立式书脊层与封面搁板切分、引擎章节分组、锚点定位、
 * 末本自然斜倚、径向波纹进场曲线以及「书脊」「封面」全员入柜/出柜（含左侧邻书经 90° 正对换边）三维姿态插值。
 */
class ShelfCatalogLayoutTest {

    private fun fakeGame(index: Int, engine: EngineType = EngineType.KIRIKIRI): ScanGame = ScanGame(
        title = "Game $index",
        uri = "content://games/$index",
        engine = engine,
        launchTarget = "data.xp3",
    )

    @Test
    fun verticalColumnCountAdaptsAcrossViewportWidths() {
        assertEquals(6, shelfCatalogVerticalColumns(320.dp))
        assertEquals(8, shelfCatalogVerticalColumns(390.dp))
        assertEquals(10, shelfCatalogVerticalColumns(480.dp))
        assertEquals(12, shelfCatalogVerticalColumns(620.dp))
        assertEquals(15, shelfCatalogVerticalColumns(840.dp))
        assertEquals(18, shelfCatalogVerticalColumns(1080.dp))
        assertEquals(21, shelfCatalogVerticalColumns(1280.dp))
    }

    @Test
    fun coverColumnCountAdaptsAcrossViewportWidths() {
        assertEquals(3, shelfCatalogCoverColumns(360.dp))
        assertEquals(3, shelfCatalogCoverColumns(470.dp))
        assertEquals(4, shelfCatalogCoverColumns(520.dp))
        assertEquals(5, shelfCatalogCoverColumns(620.dp))
        assertEquals(6, shelfCatalogCoverColumns(840.dp))
        assertEquals(7, shelfCatalogCoverColumns(1080.dp))
        assertEquals(8, shelfCatalogCoverColumns(1280.dp))
    }

    @Test
    fun buildVerticalRowsChunksEvenlyAndInsertsEngineHeadersWhenAppropriate() {
        val games = (0 until 10).map { fakeGame(it, if (it % 2 == 0) EngineType.KIRIKIRI else EngineType.RENPY) }
        val flatRows = buildShelfCatalogVerticalRows(games, columns = 4, groupByEngine = false)
        assertEquals(3, flatRows.size)
        val tier0 = flatRows[0] as ShelfCatalogRowItem.VerticalTier
        val tier1 = flatRows[1] as ShelfCatalogRowItem.VerticalTier
        val tier2 = flatRows[2] as ShelfCatalogRowItem.VerticalTier
        assertEquals(4, tier0.games.size)
        assertEquals(4, tier1.games.size)
        assertEquals(2, tier2.games.size)
        assertEquals(0, tier0.startIndex)
        assertEquals(4, tier1.startIndex)
        assertEquals(8, tier2.startIndex)

        val singleEngine = (0 until 5).map { fakeGame(it, EngineType.KIRIKIRI) }
        val singleRows = buildShelfCatalogVerticalRows(singleEngine, columns = 4, groupByEngine = true)
        assertTrue(singleRows.none { it is ShelfCatalogRowItem.SectionHeader })
        assertEquals(2, singleRows.size)

        val multiEngine = listOf(
            fakeGame(0, EngineType.KIRIKIRI),
            fakeGame(1, EngineType.KIRIKIRI),
            fakeGame(2, EngineType.KIRIKIRI),
            fakeGame(3, EngineType.KIRIKIRI),
            fakeGame(4, EngineType.KIRIKIRI),
            fakeGame(5, EngineType.RENPY),
            fakeGame(6, EngineType.RENPY),
        )
        val multiRows = buildShelfCatalogVerticalRows(multiEngine, columns = 4, groupByEngine = true)
        assertEquals(5, multiRows.size)
        val header0 = multiRows[0] as ShelfCatalogRowItem.SectionHeader
        assertEquals(EngineType.KIRIKIRI, header0.engine)
        assertEquals(5, header0.count)
        assertEquals(4, (multiRows[1] as ShelfCatalogRowItem.VerticalTier).games.size)
        assertEquals(1, (multiRows[2] as ShelfCatalogRowItem.VerticalTier).games.size)
        val header1 = multiRows[3] as ShelfCatalogRowItem.SectionHeader
        assertEquals(EngineType.RENPY, header1.engine)
        assertEquals(2, header1.count)
        assertEquals(2, (multiRows[4] as ShelfCatalogRowItem.VerticalTier).games.size)
    }

    @Test
    fun buildCoverRowsChunksPerTierAndGroupsByEngine() {
        val games = (0 until 13).map { fakeGame(it, EngineType.KIRIKIRI) }
        val rows = buildShelfCatalogCoverRows(games, columns = 5, groupByEngine = false)
        assertEquals(3, rows.size)
        val r0 = rows[0] as ShelfCatalogRowItem.CoverTier
        val r1 = rows[1] as ShelfCatalogRowItem.CoverTier
        val r2 = rows[2] as ShelfCatalogRowItem.CoverTier
        assertEquals(5, r0.games.size)
        assertEquals(5, r1.games.size)
        assertEquals(3, r2.games.size)
        assertEquals(0, r0.startIndex)
        assertEquals(5, r1.startIndex)
        assertEquals(10, r2.startIndex)

        val multi = listOf(
            fakeGame(0, EngineType.KIRIKIRI),
            fakeGame(1, EngineType.KIRIKIRI),
            fakeGame(2, EngineType.ONS),
            fakeGame(3, EngineType.ONS),
        )
        val grouped = buildShelfCatalogCoverRows(multi, columns = 3, groupByEngine = true)
        assertEquals(4, grouped.size)
        assertTrue(grouped[0] is ShelfCatalogRowItem.SectionHeader)
        assertTrue(grouped[2] is ShelfCatalogRowItem.SectionHeader)
        assertEquals(1, findShelfCatalogAnchorRow(grouped, "content://games/0"))
        assertEquals(1, findShelfCatalogAnchorRow(grouped, "content://games/1"))
        assertEquals(3, findShelfCatalogAnchorRow(grouped, "content://games/3"))
        assertEquals(1 to 1, findShelfCatalogGridCoord(grouped, "content://games/3"))
    }

    @Test
    fun anchorRowAndGridCoordLocateTargetAccurately() {
        val games = listOf(
            fakeGame(0, EngineType.KIRIKIRI),
            fakeGame(1, EngineType.KIRIKIRI),
            fakeGame(2, EngineType.ONS),
            fakeGame(3, EngineType.ONS),
            fakeGame(4, EngineType.ONS),
            fakeGame(5, EngineType.ONS),
        )
        val rows = buildShelfCatalogVerticalRows(games, columns = 3, groupByEngine = true)
        assertEquals(3, findShelfCatalogAnchorRow(rows, "content://games/4"))
        assertEquals(4, findShelfCatalogAnchorRow(rows, "content://games/5"))
        assertEquals(-1, findShelfCatalogAnchorRow(rows, "content://games/missing"))

        assertEquals(1 to 2, findShelfCatalogGridCoord(rows, "content://games/4"))
        assertEquals(2 to 0, findShelfCatalogGridCoord(rows, "content://games/5"))
        assertNull(findShelfCatalogGridCoord(rows, "content://games/missing"))
    }

    @Test
    fun alignedScrollDistinguishesMissingAnchorFromFirstRow() {
        val rows = buildShelfCatalogCoverRows((0 until 6).map { fakeGame(it) }, columns = 3, groupByEngine = false)
        assertEquals(0 to 40, shelfCatalogAlignedScroll(rows, "content://games/1", 20f, 100f, 60f))
        assertNull(shelfCatalogAlignedScroll(rows, "content://games/missing", 20f, 100f, 60f))
        assertEquals(0, shelfCatalogInitialRow(rows, "content://games/missing", visibleTiersHint = 2))
    }

    @Test
    fun reflowFindsOldCenterEvenWhenDifferentColumnsProduceTheSameRowCount() {
        val games = (0 until 85).map { fakeGame(it) }
        val before = buildShelfCatalogVerticalRows(games, columns = 21, groupByEngine = false)
        val after = buildShelfCatalogVerticalRows(games, columns = 18, groupByEngine = false)
        val oldLayout = object : LazyListLayoutInfo {
            override val totalItemsCount = before.size + 1
            override val viewportStartOffset = 0
            override val viewportEndOffset = 100
            override val visibleItemsInfo = listOf(object : LazyListItemInfo {
                override val index = 3
                override val key = before[index].key
                override val offset = 0
                override val size = 100
            })
        }
        assertEquals(before.size, after.size)
        val anchor = resolveCatalogCenterUri(oldLayout, before)
        assertEquals(games[73].uri, anchor)
        assertNull(resolveCatalogCenterUri(oldLayout, after))
        assertEquals(4, shelfCatalogInitialRow(after, anchor, visibleTiersHint = 2))
    }

    @Test
    fun focusAcknowledgementRejectsMissingWrongAndSupersededRequests() {
        val stage = ShelfCatalogStage()
        val first = stage.requestFocus("first")
        stage.clearPendingFocus(first)
        assertFalse(stage.markFocusAligned(first, "first"))
        assertEquals(0, stage.settledFocusToken)

        val second = stage.requestFocus("second")
        stage.clearPendingFocus(first)
        assertFalse(stage.markFocusAligned(first, "second"))
        assertFalse(stage.markFocusAligned(second, "first"))
        assertEquals("second", stage.pendingFocusUri)
        assertTrue(stage.markFocusAligned(second, "second"))
        assertEquals(second, stage.settledFocusToken)
        assertNull(stage.pendingFocusUri)
    }

    @Test
    fun initialRowPreservesEngineHeaderAndKeepsAnchorVisibleOnShortViewports() {
        val grouped = buildShelfCatalogVerticalRows(
            listOf(
                fakeGame(0, EngineType.KIRIKIRI),
                fakeGame(1, EngineType.KIRIKIRI),
                fakeGame(2, EngineType.ONS),
                fakeGame(3, EngineType.ONS),
                fakeGame(4, EngineType.ONS),
                fakeGame(5, EngineType.ONS),
            ),
            columns = 3,
            groupByEngine = true,
        )
        // [Header KIRIKIRI (0), Tier (1), Header ONS (2), Tier ONS-0 (3), Tier ONS-1 (4)]
        assertEquals(0, shelfCatalogInitialRow(grouped, "content://games/0", visibleTiersHint = 2))
        assertEquals(2, shelfCatalogInitialRow(grouped, "content://games/3", visibleTiersHint = 2))
        assertEquals(2, shelfCatalogInitialRow(grouped, "content://games/3", visibleTiersHint = 4))
        assertEquals(4, shelfCatalogInitialRow(grouped, "content://games/5", visibleTiersHint = 2))
        assertEquals(3, shelfCatalogInitialRow(grouped, "content://games/5", visibleTiersHint = 3))

        val flat = buildShelfCatalogCoverRows((0 until 12).map { fakeGame(it) }, columns = 3, groupByEngine = false)
        assertEquals(0, shelfCatalogInitialRow(flat, "content://games/1", visibleTiersHint = 2))
        assertEquals(2, shelfCatalogInitialRow(flat, "content://games/7", visibleTiersHint = 2))
        assertEquals(1, shelfCatalogInitialRow(flat, "content://games/7", visibleTiersHint = 3))
    }

    @Test
    fun endBookLeanAppliesOnlyToTrailingBookInUnfilledNonCurrentTier() {
        assertEquals(0f, shelfCatalogEndBookLeanDegrees(colIndex = 3, tierCount = 4, maxColumns = 4, isCurrent = false), 0f)
        assertEquals(0f, shelfCatalogEndBookLeanDegrees(colIndex = 1, tierCount = 3, maxColumns = 6, isCurrent = false), 0f)
        assertEquals(-6.5f, shelfCatalogEndBookLeanDegrees(colIndex = 2, tierCount = 3, maxColumns = 6, isCurrent = false), 1e-4f)
        assertEquals(0f, shelfCatalogEndBookLeanDegrees(colIndex = 2, tierCount = 3, maxColumns = 6, isCurrent = true), 0f)
        assertEquals(0f, shelfCatalogEndBookLeanDegrees(colIndex = 0, tierCount = 1, maxColumns = 6, isCurrent = false), 0f)
    }

    @Test
    fun cascadeProgressIsBoundedMonotonicAndRadiallyStaggered() {
        assertEquals(0f, shelfCatalogCascadeProgress(0f, 3f), 1e-4f)
        assertEquals(1f, shelfCatalogCascadeProgress(1f, 0f), 1e-4f)
        assertEquals(1f, shelfCatalogCascadeProgress(1f, 8f), 1e-4f)

        var prev = 0f
        var p = 0.05f
        while (p <= 1f) {
            val v = shelfCatalogCascadeProgress(p, 2.5f)
            assertTrue("cascade must be monotonic at $p", v >= prev)
            assertTrue("cascade must stay within [0, 1]", v in 0f..1f)
            prev = v
            p += 0.05f
        }
        val near = shelfCatalogCascadeProgress(0.35f, 0.5f)
        val far = shelfCatalogCascadeProgress(0.35f, 4.0f)
        assertTrue("near tile ($near) should lead far tile ($far)", near > far)
    }

    @Test
    fun troupeFlightPoseSupportsSpinesAndCoversRoundTripsIncludingLeftNeighbors() {
        val heroOrigin = ShelfCatalogBookOrigin(
            uri = "content://games/0",
            pageIndex = 0,
            frontRect = Rect(200f, 300f, 400f, 566f),
            spineRect = Rect(188f, 300f, 200f, 566f),
            spineThicknessPx = 36f,
            boxHeightPx = 266f,
            coverWidthPx = 200f,
            yawDegrees = 14f,
            tiltDegrees = 5f,
            tint = Color.Red,
            isHero = true,
        )
        val vSlot = Rect(60f, 420f, 90f, 558f)
        val enterSpines = ShelfCatalogBookFlight(
            game = fakeGame(0),
            spineIndex = 0,
            shelfOrigin = heroOrigin,
            catalogRect = vSlot,
            catalogMode = ShelfCatalogLayoutMode.SPINES,
            delayFraction = 0f,
            arcLiftPx = 20f,
            returningToShelf = false,
            isCurrentInCatalog = true,
            zPriority = 150f,
        )
        val sStart = shelfCatalogBookPose(0f, enterSpines)
        assertEquals(heroOrigin.spineRect.center.x, sStart.spineCenterX, 1e-3f)
        assertEquals(heroOrigin.spineRect.center.y, sStart.spineCenterY, 1e-3f)
        assertEquals(heroOrigin.spineThicknessPx, sStart.spineWidth, 1e-3f)
        assertEquals(heroOrigin.boxHeightPx, sStart.spineHeight, 1e-3f)
        assertEquals(14f, sStart.absYawDeg, 1e-3f)
        assertEquals(5f, sStart.tiltDegrees, 1e-3f)
        assertEquals(1f, sStart.frontAlpha, 1e-3f)
        assertEquals(0f, sStart.catalogMix, 1e-3f)

        val sEnd = shelfCatalogBookPose(1f, enterSpines)
        assertEquals(vSlot.center.x, sEnd.spineCenterX, 1e-3f)
        assertEquals(vSlot.center.y, sEnd.spineCenterY, 1e-3f)
        assertEquals(vSlot.width, sEnd.spineWidth, 1e-3f)
        assertEquals(vSlot.height, sEnd.spineHeight, 1e-3f)
        assertEquals(90f, sEnd.absYawDeg, 1e-3f)
        assertEquals(0f, sEnd.tiltDegrees, 1e-3f)
        assertEquals(0f, sEnd.frontAlpha, 1e-3f)
        assertEquals(1f, sEnd.catalogMix, 1e-3f)

        // COVERS 进/退场（含左侧 -86° 邻书经 90° 正对无跳变切换至左装订右倒角）
        val cSlot = Rect(120f, 480f, 240f, 642f) // cover 120x162
        val leftOrigin = ShelfCatalogBookOrigin(
            uri = "content://games/1",
            pageIndex = 1,
            frontRect = Rect(40f, 300f, 220f, 566f),
            spineRect = Rect(100f, 300f, 134f, 566f),
            spineThicknessPx = 36f,
            boxHeightPx = 266f,
            coverWidthPx = 200f,
            yawDegrees = -86f,
            tiltDegrees = 0f,
            tint = Color.Blue,
            isHero = false,
        )
        val enterCoverLeft = ShelfCatalogBookFlight(
            game = fakeGame(1),
            spineIndex = 1,
            shelfOrigin = leftOrigin,
            catalogRect = cSlot,
            catalogMode = ShelfCatalogLayoutMode.COVERS,
            delayFraction = 0f,
            arcLiftPx = 15f,
            returningToShelf = false,
            isCurrentInCatalog = false,
            zPriority = 90f,
        )
        val cStart = shelfCatalogBookPose(0f, enterCoverLeft)
        assertEquals(leftOrigin.spineRect.center.x, cStart.spineCenterX, 1e-3f)
        assertEquals(86f, cStart.absYawDeg, 1e-3f)
        assertEquals(-1f, cStart.facingSign, 1e-3f)
        assertEquals(0f, cStart.catalogMix, 1e-3f)

        val cEnd = shelfCatalogBookPose(1f, enterCoverLeft)
        // facingSign=+1 时封面几何中心 = spineCenterX + 0.5 * coverWidth，必须精准重合 cSlot.center.x
        assertEquals(cSlot.center.x, cEnd.spineCenterX + cEnd.facingSign * 0.5f * cEnd.coverWidth, 1e-3f)
        assertEquals(cSlot.center.y, cEnd.spineCenterY, 1e-3f)
        assertEquals(cSlot.width, cEnd.coverWidth, 1e-3f)
        assertEquals(cSlot.height, cEnd.spineHeight, 1e-3f)
        assertEquals(0f, cEnd.absYawDeg, 1e-3f)
        assertEquals(1f, cEnd.facingSign, 1e-3f)
        assertEquals(1f, cEnd.frontAlpha, 1e-3f)
        assertEquals(1f, cEnd.catalogMix, 1e-3f)

        val exitCoverHero = ShelfCatalogBookFlight(
            game = fakeGame(0),
            spineIndex = 0,
            shelfOrigin = heroOrigin,
            catalogRect = cSlot,
            catalogMode = ShelfCatalogLayoutMode.COVERS,
            delayFraction = 0f,
            arcLiftPx = 20f,
            returningToShelf = true,
            isCurrentInCatalog = true,
            zPriority = 200f,
        )
        val eStart = shelfCatalogBookPose(0f, exitCoverHero)
        assertEquals(cSlot.center.x, eStart.spineCenterX + eStart.facingSign * 0.5f * eStart.coverWidth, 1e-3f)
        assertEquals(cSlot.center.y, eStart.spineCenterY, 1e-3f)
        assertEquals(0f, eStart.absYawDeg, 1e-3f)
        assertEquals(1f, eStart.catalogMix, 1e-3f)

        val eEnd = shelfCatalogBookPose(1f, exitCoverHero)
        assertEquals(heroOrigin.spineRect.center.x, eEnd.spineCenterX, 1e-3f)
        assertEquals(heroOrigin.spineRect.center.y, eEnd.spineCenterY, 1e-3f)
        assertEquals(heroOrigin.coverWidthPx, eEnd.coverWidth, 1e-3f)
        assertEquals(14f, eEnd.absYawDeg, 1e-3f)
        assertEquals(5f, eEnd.tiltDegrees, 1e-3f)
        assertEquals(0f, eEnd.catalogMix, 1e-3f)

        val exitCoverLeft = ShelfCatalogBookFlight(
            game = fakeGame(1),
            spineIndex = 1,
            shelfOrigin = leftOrigin,
            catalogRect = cSlot,
            catalogMode = ShelfCatalogLayoutMode.COVERS,
            delayFraction = 0f,
            arcLiftPx = 10f,
            returningToShelf = true,
            isCurrentInCatalog = false,
            zPriority = 90f,
        )
        val elEnd = shelfCatalogBookPose(1f, exitCoverLeft)
        assertEquals(leftOrigin.spineRect.center.x, elEnd.spineCenterX, 1e-3f)
        assertEquals(86f, elEnd.absYawDeg, 1e-3f)
        assertEquals(-1f, elEnd.facingSign, 1e-3f)
    }

    @Test
    fun spineHeightIsStableAndBounded() {
        for (i in 0 until 100) {
            val uri = "content://games/catalog/$i"
            val factor = shelfCatalogSpineHeightFactor(uri)
            assertEquals(factor, shelfCatalogSpineHeightFactor(uri), 0f)
            assertTrue("factor $factor out of range", factor in 0.88f..1f)
        }
    }

    @Test
    fun interruptedPickContinuesFullPoseThroughStaggerAndTurnsTowardNewHeroLanding() {
        val game = fakeGame(1)
        val original = ShelfCatalogBookOrigin(
            uri = game.uri,
            pageIndex = 1,
            frontRect = Rect(40f, 300f, 220f, 566f),
            spineRect = Rect(100f, 300f, 134f, 566f),
            spineThicknessPx = 36f,
            boxHeightPx = 266f,
            coverWidthPx = 200f,
            yawDegrees = -86f,
            tiltDegrees = 3f,
            tint = Color.Blue,
            isHero = false,
            leanDegrees = -1.3f,
            frontShade = 0.42f,
            spineShade = 0.1f,
        )
        val hero = ShelfCatalogBookOrigin(
            uri = original.uri,
            pageIndex = 3,
            frontRect = original.frontRect,
            spineRect = Rect(280f, 240f, 322f, 540f),
            spineThicknessPx = 42f,
            boxHeightPx = 300f,
            coverWidthPx = 225f,
            yawDegrees = 14f,
            tiltDegrees = 5f,
            tint = original.tint,
            isHero = true,
            leanDegrees = 0f,
            frontShade = 0f,
            spineShade = 0f,
            heroFactor = 1f,
        )
        for (mode in ShelfCatalogLayoutMode.entries) {
            val slot = if (mode == ShelfCatalogLayoutMode.SPINES) {
                Rect(60f, 420f, 90f, 558f)
            } else {
                Rect(60f, 420f, 180f, 582f)
            }
            val entering = ShelfCatalogBookFlight(
                game, 1, original, slot, mode, 0.18f, 15f, false, false, 90f, catalogLeanDegrees = -6.5f,
            )
            val takeoff = shelfCatalogBookPose(0.20f, entering)
            assertTrue(takeoff.facingSign < 0f)
            val returning = ShelfCatalogBookFlight(
                game, 1, hero, slot, mode, 0.18f, 20f, true, false, 200f,
                catalogLeanDegrees = -6.5f,
                takeoffPose = takeoff,
            )
            assertEquals(takeoff, shelfCatalogBookPose(0f, returning))
            assertEquals(takeoff, shelfCatalogBookPose(0.18f, returning))
            assertEquals(takeoff, shelfCatalogBookPose(0.8f, entering.fadeFrom(0.20f)))
            val poses = (0..1000).map { shelfCatalogBookPose(it / 1000f, returning) }
            val facingChange = poses.zipWithNext().first { (before, after) -> before.facingSign != after.facingSign }
            assertTrue(facingChange.first.absYawDeg > 89f && facingChange.second.absYawDeg > 89f)
            val landing = poses.last()
            assertEquals(hero.spineRect.center.x, landing.spineCenterX, 1e-3f)
            assertEquals(hero.spineRect.center.y, landing.spineCenterY, 1e-3f)
            assertEquals(hero.yawDegrees, landing.absYawDeg, 1e-3f)
            assertEquals(1f, landing.playBadgeAlpha, 1e-3f)
        }
    }

    @Test
    fun troupePosePreservesShadesLeanBadgeAndContinuousFrontVisibility() {
        val sideOrigin = ShelfCatalogBookOrigin(
            uri = "content://games/side",
            pageIndex = 2,
            frontRect = Rect(420f, 300f, 620f, 566f),
            spineRect = Rect(400f, 300f, 434f, 566f),
            spineThicknessPx = 34f,
            boxHeightPx = 266f,
            coverWidthPx = 200f,
            yawDegrees = 86f,
            tiltDegrees = 0f,
            tint = Color.Blue,
            isHero = false,
            leanDegrees = -1.3f,
            frontShade = 0.42f,
            spineShade = 0.02f,
            heroFactor = 0f,
        )
        val slot = Rect(120f, 420f, 152f, 560f)
        val exitSpinesSide = ShelfCatalogBookFlight(
            game = fakeGame(2),
            spineIndex = 2,
            shelfOrigin = sideOrigin,
            catalogRect = slot,
            catalogMode = ShelfCatalogLayoutMode.SPINES,
            delayFraction = 0f,
            arcLiftPx = 12f,
            returningToShelf = true,
            isCurrentInCatalog = false,
            zPriority = 90f,
            catalogLeanDegrees = 8.5f,
        )
        val atStart = shelfCatalogBookPose(0f, exitSpinesSide)
        assertEquals(8.5f, atStart.leanDegrees, 1e-3f)
        assertEquals(0f, atStart.frontShade, 1e-3f)
        assertEquals(0f, atStart.spineShade, 1e-3f)
        assertEquals(0f, atStart.frontAlpha, 1e-3f)

        val atEnd = shelfCatalogBookPose(1f, exitSpinesSide)
        assertEquals(-1.3f, atEnd.leanDegrees, 1e-3f)
        assertEquals(0.42f, atEnd.frontShade, 1e-3f)
        assertEquals(0.02f, atEnd.spineShade, 1e-3f)
        assertEquals(1f, atEnd.frontAlpha, 1e-3f)
    }

    @Test
    fun geometryOriginMatchesShelfBoxEdgesForHeroRightAndLeftBooks() {
        val coverW = 300f
        val coverH = 400f
        val spineD = 45f
        val viewport = 1100f
        val geometry = ShelfGeometry(
            coverWidth = coverW,
            coverHeight = coverH,
            spineDepth = spineD,
            gap = 9f,
            restGap = 36f,
        )
        val rail = ShelfRail(viewport, coverH * RAIL_EYE)
        val pagerZeroCenterX = 710f
        val floorY = 920f
        val liftPx = 54f
        val position = 3f
        val count = 12
        val rowShiftPx = geometry.rowShift(position, count, viewport, endPad = 60f)

        val heroTrait = ShelfBookTrait(sizeScale = 0.96f, restLeanDegrees = 1.3f)
        val heroOrigin = shelfCatalogBookOriginFromGeometry(
            uri = "content://games/3",
            pageIndex = 3,
            offset = 0f,
            geometry = geometry,
            rowShiftPx = rowShiftPx,
            rail = rail,
            pagerZeroCenterX = pagerZeroCenterX,
            floorYScreen = floorY,
            liftPx = liftPx,
            trait = heroTrait,
            tint = Color.Red,
        )
        assertTrue(heroOrigin.isHero)
        assertEquals(HERO_TILT_DEGREES, heroOrigin.tiltDegrees, 1e-3f)
        assertEquals(0f, heroOrigin.leanDegrees, 1e-3f)
        assertEquals(coverH * shelfPoseScale(0f), heroOrigin.boxHeightPx, 1e-2f)
        assertEquals(floorY - liftPx, heroOrigin.spineRect.bottom, 1e-2f)
        assertEquals(1f, heroOrigin.heroFactor, 1e-3f)

        val sideTrait = ShelfBookTrait(sizeScale = 0.97f, restLeanDegrees = -1.3f)
        val rightOrigin = shelfCatalogBookOriginFromGeometry(
            uri = "content://games/5",
            pageIndex = 5,
            offset = 2f,
            geometry = geometry,
            rowShiftPx = rowShiftPx,
            rail = rail,
            pagerZeroCenterX = pagerZeroCenterX,
            floorYScreen = floorY,
            liftPx = liftPx,
            trait = sideTrait,
            tint = Color.Green,
        )
        val leftOrigin = shelfCatalogBookOriginFromGeometry(
            uri = "content://games/1",
            pageIndex = 1,
            offset = -2f,
            geometry = geometry,
            rowShiftPx = rowShiftPx,
            rail = rail,
            pagerZeroCenterX = pagerZeroCenterX,
            floorYScreen = floorY,
            liftPx = liftPx,
            trait = sideTrait,
            tint = Color.Blue,
        )
        val sideScale = shelfPoseScale(2f) * sideTrait.sizeScale
        val rightBoxCenter = pagerZeroCenterX + rail.screenX(geometry.centerX(2f) + rowShiftPx)
        val leftBoxCenter = pagerZeroCenterX + rail.screenX(geometry.centerX(-2f) + rowShiftPx)
        val expectedHalfVis = 0.5f * geometry.visibleWidth(Math.toRadians(86.0).toFloat()) * sideScale

        // 右侧书书脊在左（left = boxCenter - halfVis）、左侧书书脊在右（right = boxCenter + halfVis）
        assertEquals(rightBoxCenter - expectedHalfVis, rightOrigin.spineRect.left, 0.5f)
        assertEquals(leftBoxCenter + expectedHalfVis, leftOrigin.spineRect.right, 0.5f)
        assertTrue(rightOrigin.yawDegrees > 80f)
        assertTrue(leftOrigin.yawDegrees < -80f)
        assertEquals(floorY, rightOrigin.spineRect.bottom, 1e-2f)
        assertEquals(-1.3f, rightOrigin.leanDegrees, 1e-3f)
        assertEquals(geometry.faceShade(2f, spine = false), rightOrigin.frontShade, 1e-3f)
        assertEquals(geometry.faceShade(2f, spine = true), rightOrigin.spineShade, 1e-3f)
        assertEquals(0f, rightOrigin.heroFactor, 1e-3f)
    }

    @Test
    fun coversExitStartsFoldingImmediatelyWithoutEarlyBloat() {
        val origin = ShelfCatalogBookOrigin(
            uri = "content://games/1",
            pageIndex = 1,
            frontRect = Rect(420f, 300f, 700f, 700f),
            spineRect = Rect(400f, 300f, 442f, 700f),
            spineThicknessPx = 42f,
            boxHeightPx = 400f,
            coverWidthPx = 280f,
            yawDegrees = 86f,
            tiltDegrees = 0f,
            tint = Color.Blue,
            isHero = false,
        )
        val slot = Rect(120f, 420f, 310f, 676f) // 190x256
        val flight = ShelfCatalogBookFlight(
            game = fakeGame(1),
            spineIndex = 1,
            shelfOrigin = origin,
            catalogRect = slot,
            catalogMode = ShelfCatalogLayoutMode.COVERS,
            delayFraction = 0f,
            arcLiftPx = 12f,
            returningToShelf = true,
            isCurrentInCatalog = false,
            zPriority = 90f,
        )
        val early = shelfCatalogBookPose(0.06f, flight)
        // 起飞首帧（t=0.06）立刻开始侧转折叠，但尺寸尚未膨胀放大，避免相邻封面互相遮挡撞叠
        assertTrue("expected immediate fold at t=0.06, yaw=${early.absYawDeg}", early.absYawDeg > 0.5f)
        assertEquals(slot.width, early.coverWidth, 1e-2f)
        assertEquals(slot.height, early.spineHeight, 1e-2f)
    }

    @Test
    fun viewportFilterExcludesClippedSlotsAboveHeaderAndBelowBottom() {
        val viewport = Rect(0f, 520f, 1080f, 2280f)
        val fullyVisible = Rect(60f, 600f, 320f, 940f)
        val whollyAbove = Rect(60f, 120f, 320f, 460f)
        val clippedAtTopHeader = Rect(60f, 400f, 320f, 720f) // ~62.5% 可见（顶部超出 120px = 37.5%）
        val halfBlockedAtTop = Rect(60f, 340f, 320f, 660f) // 顶部超出 56.25%（大于 45% 上限，不得压住检索台）
        val barelyPeekingAtTop = Rect(60f, 200f, 320f, 560f) // 仅露 40px (< 35%)
        val clippedAtBottom = Rect(60f, 2000f, 320f, 2340f)

        assertTrue(isShelfCatalogSlotInViewport(fullyVisible, viewport, allowPartial = false))
        assertTrue(!isShelfCatalogSlotInViewport(whollyAbove, viewport, allowPartial = false))
        assertTrue(!isShelfCatalogSlotInViewport(clippedAtTopHeader, viewport, allowPartial = false))
        assertTrue(!isShelfCatalogSlotInViewport(clippedAtBottom, viewport, allowPartial = false))

        // 被用户点选的目标书若至少 55% 落在可视区内且未越出顶部上限，可稳妥抽出起飞
        assertTrue(isShelfCatalogSlotInViewport(clippedAtTopHeader, viewport, allowPartial = true))
        assertTrue(!isShelfCatalogSlotInViewport(halfBlockedAtTop, viewport, allowPartial = true))
        assertTrue(!isShelfCatalogSlotInViewport(whollyAbove, viewport, allowPartial = true))
        assertTrue(!isShelfCatalogSlotInViewport(barelyPeekingAtTop, viewport, allowPartial = true))
    }

    @Test
    fun geometryOriginAccountsForStageOverflowWhenClamped() {
        val geometry = ShelfGeometry(
            coverWidth = 280f,
            coverHeight = 373f,
            spineDepth = 42f,
            gap = 9f,
            restGap = 36f,
        )
        val rail = ShelfRail(1000f, 373f * RAIL_EYE)
        val trait = ShelfBookTrait(sizeScale = 0.96f, restLeanDegrees = 0f)
        val overflowPx = 36f

        val normalHero = shelfCatalogBookOriginFromGeometry(
            uri = "content://games/0",
            pageIndex = 0,
            offset = 0f,
            geometry = geometry,
            rowShiftPx = 0f,
            rail = rail,
            pagerZeroCenterX = 500f,
            floorYScreen = 1500f,
            liftPx = 54f,
            trait = trait,
            tint = Color.Red,
            stageOverflowPx = 0f,
        )
        val clampedHero = shelfCatalogBookOriginFromGeometry(
            uri = "content://games/0",
            pageIndex = 0,
            offset = 0f,
            geometry = geometry,
            rowShiftPx = 0f,
            rail = rail,
            pagerZeroCenterX = 500f,
            floorYScreen = 1500f,
            liftPx = 54f,
            trait = trait,
            tint = Color.Red,
            stageOverflowPx = overflowPx,
        )
        val expectedHeroShift = shelfPoseScale(0f) * (overflowPx * 0.5f)
        assertEquals(expectedHeroShift, clampedHero.spineRect.bottom - normalHero.spineRect.bottom, 1e-2f)

        val normalSide = shelfCatalogBookOriginFromGeometry(
            uri = "content://games/2",
            pageIndex = 2,
            offset = 2f,
            geometry = geometry,
            rowShiftPx = 0f,
            rail = rail,
            pagerZeroCenterX = 500f,
            floorYScreen = 1500f,
            liftPx = 54f,
            trait = trait,
            tint = Color.Blue,
            stageOverflowPx = 0f,
        )
        val clampedSide = shelfCatalogBookOriginFromGeometry(
            uri = "content://games/2",
            pageIndex = 2,
            offset = 2f,
            geometry = geometry,
            rowShiftPx = 0f,
            rail = rail,
            pagerZeroCenterX = 500f,
            floorYScreen = 1500f,
            liftPx = 54f,
            trait = trait,
            tint = Color.Blue,
            stageOverflowPx = overflowPx,
        )
        val expectedSideShift = shelfPoseScale(2f) * trait.sizeScale * (overflowPx * 0.5f)
        assertEquals(expectedSideShift, clampedSide.spineRect.bottom - normalSide.spineRect.bottom, 1e-2f)
    }
}
