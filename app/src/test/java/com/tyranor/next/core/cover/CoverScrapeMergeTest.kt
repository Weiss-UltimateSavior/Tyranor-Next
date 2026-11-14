package com.tyranor.next.core.cover

import android.content.Context
import android.content.ContextWrapper
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.storage.GameLibraryFacade
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.core.status.PlayStatusService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class CoverScrapeMergeTest {
    private val original = ScanGame(
        title = "原始标题",
        uri = "content://games/first",
        engine = EngineType.KIRIKIRI,
        launchTarget = "data.xp3",
    )

    @Test
    fun acceptsScrapedCoverAndPreservesConcurrentNonCoverFields() {
        val current = original.copy(title = "用户重命名")
        val scraped = original.copy(coverUri = "file:///new.jpg", coverSource = "vndb")

        val merged = mergeScrapedCover(current, original, scraped)

        assertEquals("用户重命名", merged.title)
        assertEquals("file:///new.jpg", merged.coverUri)
        assertEquals("vndb", merged.coverSource)
    }

    @Test
    fun rejectsScrapedCoverWhenUserChangedCoverMeanwhile() {
        val current = original.copy(coverUri = "file:///custom.png", coverSource = "custom")
        val scraped = original.copy(coverUri = "file:///remote.jpg", coverSource = "vndb")

        val merged = mergeScrapedCover(current, original, scraped)

        assertEquals("file:///custom.png", merged.coverUri)
        assertEquals("custom", merged.coverSource)
    }

    @Test
    fun mergeKeepsExistingBindingsOverScrapedHits() {
        // 游玩状态绑定不写封面列、必过封面一致性护栏：标题首条候选不得静默改写手工绑定，
        // 否则后续状态写入会打到错误的远端条目
        val current = original.copy(vndbId = "v17", bangumiId = "4154", hikarinagiId = "hkn1", nextmoeId = "207379", metadataTitle = "手工绑定标题")
        val scraped = original.copy(
            coverUri = "file:///new.jpg",
            coverSource = "vndb",
            vndbId = "v99",
            bangumiId = "9999",
            hikarinagiId = "hkn99",
            nextmoeId = "999999",
            metadataTitle = "首条候选标题",
        )

        val merged = mergeScrapedCover(current, original, scraped)

        assertEquals("file:///new.jpg", merged.coverUri)
        assertEquals("v17", merged.vndbId)
        assertEquals("4154", merged.bangumiId)
        assertEquals("hkn1", merged.hikarinagiId)
        assertEquals("207379", merged.nextmoeId)
        assertEquals("手工绑定标题", merged.metadataTitle)
    }

    @Test
    fun mergeFillsMissingBindingsFromScrapedHit() {
        val scraped = original.copy(
            coverUri = "file:///new.jpg",
            coverSource = "vndb",
            vndbId = "v17",
            bangumiId = "4154",
            hikarinagiId = "hkn1",
            metadataTitle = "候选标题",
        )

        val merged = mergeScrapedCover(original, original, scraped)

        assertEquals("v17", merged.vndbId)
        assertEquals("4154", merged.bangumiId)
        assertEquals("hkn1", merged.hikarinagiId)
        assertEquals("候选标题", merged.metadataTitle)
    }

    @Test
    fun unbindWindowDoesNotRefillClearedBindings() {
        // BK-40：解绑进行中（current 已清而任务快照未清）不回填——否则批量刮削会把用户
        // 刚解绑的绑定 id 静默接回去；封面列护栏不受影响（封面更新仍生效）
        val scraped = original.copy(
            coverUri = "file:///new.jpg",
            coverSource = "vndb",
            vndbId = "v17",
            bangumiId = "4154",
            hikarinagiId = "hkn1",
            metadataTitle = "候选标题",
        )
        // 任务快照携带三个绑定，任务运行期间用户逐一解绑（current 已清、快照未清：
        // null != "v17" 等三处比对不命中 → 不回填；封面列保持原值、仍过封面护栏）
        val snapshot = original.copy(vndbId = "v17", bangumiId = "4154", hikarinagiId = "hkn1")
        val unboundAll = snapshot.copy(vndbId = null, bangumiId = null, hikarinagiId = null)

        val merged = mergeScrapedCover(unboundAll, snapshot, scraped)

        assertEquals("file:///new.jpg", merged.coverUri)
        assertEquals("vndb", merged.coverSource)
        assertNull(merged.vndbId)
        assertNull(merged.bangumiId)
        assertNull(merged.hikarinagiId)
        // metadataTitle 不在解绑清档面（BK-42B 保留），快照为空时刮削命中仍可填充
        assertEquals("候选标题", merged.metadataTitle)
    }

    @Test
    fun consistentEmptyOrMatchingBindingWindowStillRefills() {
        // 正常绑定窗口：current 与任务快照一致（同为空 / 同值）时回填行为不变
        val scraped = original.copy(
            coverUri = "file:///new.jpg",
            coverSource = "vndb",
            vndbId = "v17",
            bangumiId = "4154",
            hikarinagiId = "hkn1",
            metadataTitle = "候选标题",
        )
        // 同为空：正常回填（既有行为）
        val mergedEmpty = mergeScrapedCover(original, original, scraped)
        assertEquals("v17", mergedEmpty.vndbId)
        assertEquals("4154", mergedEmpty.bangumiId)
        assertEquals("hkn1", mergedEmpty.hikarinagiId)

        // 同值（快照与 current 均已绑定同一条目）：已有绑定优先，不被刮削值改写
        val bound = original.copy(vndbId = "v17", bangumiId = "4154", hikarinagiId = "hkn1")
        val mergedSame = mergeScrapedCover(bound, bound, scraped)
        assertEquals("v17", mergedSame.vndbId)
        assertEquals("4154", mergedSame.bangumiId)
        assertEquals("hkn1", mergedSame.hikarinagiId)
    }

    @Test
    fun rebindDuringScrapeKeepsNewUserBinding() {
        // 任务运行期间用户重绑为另一条目（current 非空但与快照不同）：已有绑定优先，
        // 不被快照/刮削值覆盖
        val scraped = original.copy(coverUri = "file:///new.jpg", coverSource = "vndb", vndbId = "v17")
        val rebound = original.copy(vndbId = "v99")

        val merged = mergeScrapedCover(rebound, original, scraped)

        assertEquals("v99", merged.vndbId)
        assertEquals("file:///new.jpg", merged.coverUri)
    }

    @Test
    fun bindThenUnbindDuringScrapeDoesNotRefillClearedBinding() {
        // 初始未绑定 -> 任务在途期间用户手动绑定后又解绑（current 回到 null，与 original 同为空）->
        // 凭逐源绑定修订号变化判定不得回填；未改动过的其它来源仍可正常填充
        val scraped = original.copy(
            coverUri = "file:///new.jpg",
            coverSource = "vndb",
            vndbId = "v17",
            bangumiId = "4154",
        )

        val merged = mergeScrapedCover(
            current = original,
            original = original,
            scraped = scraped,
            vndbRevisionUnchanged = false,
            bangumiRevisionUnchanged = true,
            hikarinagiRevisionUnchanged = true,
        )

        assertNull(merged.vndbId)
        assertEquals("4154", merged.bangumiId)
        assertEquals("file:///new.jpg", merged.coverUri)
    }

    private val context: Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    @Before
    fun setUpFacade() {
        GameLibraryFacade.skipPersistForTest = true
        GameLibraryFacade.seedGamesForTest(null)
    }

    @After
    fun tearDownFacade() {
        GameLibraryFacade.seedGamesForTest(null)
        GameLibraryFacade.skipPersistForTest = false
    }

    @Test
    fun coverOnlyUpdateKeepsBaseBindingsOverScrapedHits() {
        val base = original.copy(vndbId = "v17", hikarinagiId = "hkn1", metadataTitle = "已有标题")
        val scraped = base.copy(coverUri = "file:///new.jpg", coverSource = "vndb", vndbId = "v99", hikarinagiId = "hkn99", metadataTitle = "候选标题")

        val updated = scraped.asCoverOnlyUpdateFrom(base)

        assertEquals("file:///new.jpg", updated.coverUri)
        assertEquals("v17", updated.vndbId)
        assertEquals("hkn1", updated.hikarinagiId)
        assertEquals("已有标题", updated.metadataTitle)
    }

    @Test
    fun facadeBindUnbindRoundTripPreventsStaleTaskSnapshotFromRefillingVndb() {
        // 真实联动测试：任务启动时在 cacheLock 下原子读取快照与修订号；刮削期间经 PlayStatusService
        // 完成 unbound -> bind -> unbind 后，persistScrapedCover 因 vndb 修订号已变化不回填 vndbId，
        // 仅写入新封面与修订号未变的 bangumiId。
        GameLibraryFacade.seedGamesForTest(listOf(original))
        val (snapshot, revisions) = GameLibraryFacade.loadGamesWithBindingRevisions(context)
        val startGame = snapshot.single()
        val expectedRev = revisions.getValue(startGame.uri)

        val bound = PlayStatusService.bindSubject(
            context,
            startGame,
            AppSettingsStore.COVER_SOURCE_VNDB,
            "v17",
            "手工标题",
        )
        assertNotNull(bound)
        val unbound = PlayStatusService.unbindSubject(
            context,
            bound!!,
            AppSettingsStore.COVER_SOURCE_VNDB,
        )
        assertNotNull(unbound)

        val scraped = startGame.copy(
            coverUri = "file:///new.jpg",
            coverSource = "vndb",
            vndbId = "v99",
            bangumiId = "4154",
            metadataTitle = "候选标题",
        )
        val persisted = CoverScrapeTaskManager.persistScrapedCover(
            context,
            startGame,
            scraped,
            expectedRevision = expectedRev,
        )

        assertNotNull(persisted)
        assertNull(persisted!!.vndbId)
        assertEquals("4154", persisted.bangumiId)
        assertEquals("file:///new.jpg", persisted.coverUri)
    }

    @Test
    fun overrideGamesBindUnbindInterleavingRefreshesSnapshotUnderLock() {
        // UI 传来的 overrideGames 是调用前快照；若启动进锁前先发生了 bind+unbind（或仅 bind），
        // loadGamesWithBindingRevisions 仍在同一 cacheLock 下按 URI 读取权威游戏实体与修订号：
        // 1. 仅 bind 发生在锁前：快照立即看见已绑定的 "v17"，不会与旧 null 配对导致覆写；
        // 2. 进锁后再发生 bind+unbind：修订号递增拦截刮削回填；
        // 3. 启动前已被删除的 URI 不再混入快照。
        val deletedGame = original.copy(
            uri = "content://games/deleted",
            title = "Deleted",
        )
        GameLibraryFacade.seedGamesForTest(listOf(original, deletedGame))
        val staleUiGames = listOf(original, deletedGame)

        // 先删除一个条目、再对保留条目完成一次 bind
        GameLibraryFacade.updateGames(context) { list -> list.filterNot { it.uri == deletedGame.uri } }
        val boundBeforeLock = PlayStatusService.bindSubject(
            context,
            original,
            AppSettingsStore.COVER_SOURCE_VNDB,
            "v17",
            "手工标题",
        )
        assertNotNull(boundBeforeLock)

        val (snapshot, revisions) = GameLibraryFacade.loadGamesWithBindingRevisions(context, staleUiGames)
        val snapGame = snapshot.single()
        assertEquals("v17", snapGame.vndbId)
        val expectedRev = revisions.getValue(snapGame.uri)

        // 解绑发生在此之后：修订号变化同样阻断刮削回填 vndbId
        val unboundAfterSnapshot = PlayStatusService.unbindSubject(
            context,
            boundBeforeLock!!,
            AppSettingsStore.COVER_SOURCE_VNDB,
        )
        assertNotNull(unboundAfterSnapshot)

        val scraped = snapGame.copy(
            coverUri = "file:///new.jpg",
            coverSource = "vndb",
            vndbId = "v99",
            bangumiId = "4154",
            metadataTitle = "候选标题",
        )
        val persisted = CoverScrapeTaskManager.persistScrapedCover(
            context,
            snapGame,
            scraped,
            expectedRevision = expectedRev,
        )

        assertNotNull(persisted)
        assertNull(persisted!!.vndbId)
        assertEquals("4154", persisted.bangumiId)
        assertEquals("file:///new.jpg", persisted.coverUri)
    }

    @Test
    fun deleteAndReaddSameUriRejectsAllStaleScrapedBindings() {
        // 捕获快照（null 封面 + null 绑定）→ 删除原游戏 → 同 URI 重建同名游戏（同样 null 封面 + null 绑定）→
        // 旧刮削任务携带四来源候选 ID 回写时，修订号已在删增过程中全量递增，旧绑定全部被拒绝。
        GameLibraryFacade.seedGamesForTest(listOf(original))
        val (snapshot, revisions) = GameLibraryFacade.loadGamesWithBindingRevisions(context)
        val startGame = snapshot.single()
        val expectedRev = revisions.getValue(startGame.uri)

        GameLibraryFacade.updateGames(context) { list -> list.filterNot { it.uri == startGame.uri } }
        val replacement = startGame.copy(title = "Replacement Game")
        GameLibraryFacade.updateGames(context) { list -> list + replacement }

        val staleScraped = startGame.copy(
            coverUri = "file:///stale.jpg",
            coverSource = "bangumi",
            vndbId = "v99",
            bangumiId = "4154",
            hikarinagiId = "hkn99",
            nextmoeId = "nm99",
            metadataTitle = "旧任务候选标题",
        )
        val persisted = CoverScrapeTaskManager.persistScrapedCover(
            context,
            startGame,
            staleScraped,
            expectedRevision = expectedRev,
        )

        assertNotNull(persisted)
        assertNull(persisted!!.vndbId)
        assertNull(persisted.bangumiId)
        assertNull(persisted.hikarinagiId)
        assertNull(persisted.nextmoeId)
    }
}
