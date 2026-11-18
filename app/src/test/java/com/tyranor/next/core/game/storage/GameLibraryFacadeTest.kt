package com.tyranor.next.core.game.storage

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.testing.InMemoryPreferencesContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

/**
 * GameLibraryFacade 逐源绑定修订号生命周期回归（ROB-4）：游戏行移除后其修订号条目同步
 * 清理，不再按「进程存活期内出现过的不重复 URI」单调累积；重加同一 URI 的新行从零代次
 * 开始，与「先绑后解」刮削护栏不冲突（曾绑定条目的快照修订号 ≥1 必不匹配）。
 * 注：`removeRootAndGames` 走同一 `forgetBindingRevisions` 助手，但其根读取经 Room
 * （`loadRootsLocked`），JVM 测试面不可达——清理语义由单删用例覆盖。
 */
class GameLibraryFacadeTest {

    private lateinit var context: InMemoryPreferencesContext

    @Before
    fun setUp() {
        context = InMemoryPreferencesContext()
        GameLibraryFacade.resetCachesForTest()
        GameLibraryFacade.skipPersistForTest = true
    }

    @After
    fun tearDown() {
        GameLibraryFacade.resetCachesForTest()
        GameLibraryFacade.skipPersistForTest = false
    }

    private fun game(uri: String) = ScanGame(
        title = "ROB4",
        uri = uri,
        engine = EngineType.KIRIKIRI,
        launchTarget = "data.xp3",
    )

    private fun bumpVndbBinding(uri: String) {
        GameLibraryFacade.updateGameCoverMetadata(context, uri) { it.copy(vndbId = "v17") }
    }

    @Test
    fun removeGameClearsBindingRevisionEntry() {
        val target = game("file:///games/rob4_target")
        val kept = game("file:///games/rob4_keep")
        GameLibraryFacade.seedGamesForTest(listOf(target, kept))
        bumpVndbBinding(target.uri)
        bumpVndbBinding(kept.uri)
        assertNotEquals(
            GameLibraryFacade.BindingRevisionSnapshot(),
            GameLibraryFacade.bindingRevision(target.uri),
        )

        GameLibraryFacade.removeGame(context, target.uri)

        // 条目已移除：修订号回到零代次快照（清空条目），未触碰其他游戏的修订号
        assertEquals(GameLibraryFacade.BindingRevisionSnapshot(), GameLibraryFacade.bindingRevision(target.uri))
        assertNotEquals(GameLibraryFacade.BindingRevisionSnapshot(), GameLibraryFacade.bindingRevision(kept.uri))
    }

    @Test
    fun reAddedGameStartsFromZeroGenerationAndStaysGuardedAgainstStaleSnapshots() {
        // 重加同一 URI：新行修订号从零开始；曾绑定条目的旧快照修订号 ≥1 必不匹配，
        // 刮削写回仍被修订号护栏拦截（先绑后解不复活）
        val target = game("file:///games/rob4_readd")
        GameLibraryFacade.seedGamesForTest(listOf(target))
        bumpVndbBinding(target.uri)
        val staleSnapshot = GameLibraryFacade.bindingRevision(target.uri)
        assertNotEquals(0L, staleSnapshot.vndb)

        GameLibraryFacade.removeGame(context, target.uri)
        assertEquals(GameLibraryFacade.BindingRevisionSnapshot(), GameLibraryFacade.bindingRevision(target.uri))

        GameLibraryFacade.seedGamesForTest(listOf(target))
        assertEquals(GameLibraryFacade.BindingRevisionSnapshot(), GameLibraryFacade.bindingRevision(target.uri))
    }
}
