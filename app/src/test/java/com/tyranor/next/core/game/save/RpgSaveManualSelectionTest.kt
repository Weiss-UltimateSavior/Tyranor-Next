package com.tyranor.next.core.game.save

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * 手动版本选择标记的事务语义（[RpgSaveSyncState.withManualSelection]）。
 *
 * 契约：存档目录**交换成功之前**失败，标记必须回到事务前的样子；交换成功**之后**失败，
 * pending 必须保留并失败关闭——那时标准侧可能已被覆盖，退回常规 mtime 同步正是标记要防的事。
 * 只用纯 [File] 构造状态，不引入 Android 上下文。
 */
class RpgSaveManualSelectionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun File.markers(): List<File> =
        checkNotNull(listFiles()).filter { it.name.endsWith(".manual.json") }.sortedBy { it.name }

    private fun File.markerBytes(): ByteArray = markers().single().readBytes()

    @Test
    fun failureBeforeDirectoryCommitRestoresAbsentMarkerAndRethrows() {
        val stateDir = temporaryFolder.newFolder("state_early")
        val store = RpgSaveSyncState(stateDir)
        val ioFailure = IOException("zip read failed")

        val thrown = assertThrows(IOException::class.java) {
            store.withManualSelection<Unit>("g") { _ -> throw ioFailure }
        }

        // 原样重抛：不得把 IO 失败换成别的语义，调用方要如实上报同一次失败
        assertSame(ioFailure, thrown)
        assertFalse(store.hasManualSelection("g"))
        // 回滚是删除标记，而不是留下 pending 或 false：下一次常规同步必须正常执行
        assertTrue(stateDir.markers().isEmpty())
        // 失败路径不残留私有目录里的半成品临时文件
        assertTrue(checkNotNull(stateDir.listFiles()).none { ".tmp." in it.name })

        // 取消不属于失败：必须原样向上传播，同样不得留下标记
        val cancelled = CancellationException("job cancelled")
        assertSame(cancelled, assertThrows(CancellationException::class.java) {
            store.withManualSelection<Unit>("g") { _ -> throw cancelled }
        })
        assertFalse(store.hasManualSelection("g"))
        assertTrue(stateDir.markers().isEmpty())
    }

    @Test
    fun failureBeforeDirectoryCommitRestoresPreviousMarkerEvenWhenDamaged() {
        val stateDir = temporaryFolder.newFolder("state_previous")
        val store = RpgSaveSyncState(stateDir)
        assertTrue(store.markManualSelection("g"))
        val presentBytes = stateDir.markerBytes()

        val ioFailure = IOException("staging copy failed")
        assertSame(ioFailure, assertThrows(IOException::class.java) {
            store.withManualSelection<Unit>("g") { _ -> throw ioFailure }
        })
        // 事务前的 Present 标记必须逐字节回来：用户此前选过版本不能因一次失败而丢失
        assertTrue(store.hasManualSelection("g"))
        assertArrayEquals(presentBytes, stateDir.markerBytes())

        // 损坏标记同样必须复原：不能悄悄回滚成「无标记」而放行常规同步（那正是覆盖用户选择的入口）
        stateDir.markers().single().writeText("{corrupt")
        val corruptBytes = stateDir.markerBytes()
        val blockFailure = IOException("backup restore failed")
        assertSame(blockFailure, assertThrows(IOException::class.java) {
            store.withManualSelection<Unit>("g") { _ -> throw blockFailure }
        })
        assertArrayEquals(corruptBytes, stateDir.markerBytes())
        val unreadable = assertThrows(GameSaveException::class.java) { store.hasManualSelection("g") }
        assertEquals(SaveErrorCode.SAVE_DIR_UNAVAILABLE, unreadable.code)
    }

    @Test
    fun commitCallbackMarksPresentAndSurvivesNewInstance() {
        val stateDir = temporaryFolder.newFolder("state_success")
        val store = RpgSaveSyncState(stateDir)

        val imported = store.withManualSelection("g") { onDirectoryCommitted ->
            onDirectoryCommitted()
            3
        }

        assertEquals(3, imported)
        assertTrue(store.hasManualSelection("g"))
        // 跨实例（等价于进程重启）仍是 Present：定向同步方向不能只活在内存里
        assertTrue(RpgSaveSyncState(stateDir).hasManualSelection("g"))

        // 没调用提交回调就不算交换成功：整笔事务失败关闭，并回滚到「无标记」
        val missingCommit = assertThrows(GameSaveException::class.java) {
            store.withManualSelection("h") { _ -> 1 }
        }
        assertEquals(SaveErrorCode.SAVE_DIR_UNAVAILABLE, missingCommit.code)
        assertFalse(store.hasManualSelection("h"))
        assertTrue(store.hasManualSelection("g"))
    }

    @Test
    fun failureAfterDirectoryCommitKeepsPendingAndNextTransactionRecovers() {
        val stateDir = temporaryFolder.newFolder("state_late")
        val store = RpgSaveSyncState(stateDir)
        val lateFailure = IOException("backup cleanup failed")

        val thrown = assertThrows(IOException::class.java) {
            store.withManualSelection("g") { onDirectoryCommitted ->
                onDirectoryCommitted()
                throw lateFailure
            }
        }
        assertSame(lateFailure, thrown)

        // 目录已交换：保留 pending 并失败关闭，既不能退回常规同步，也不能当作 Present 再定向
        val unreadable = assertThrows(GameSaveException::class.java) { store.hasManualSelection("g") }
        assertEquals(SaveErrorCode.SAVE_SELECTION_INCOMPLETE, unreadable.code)
        val pending = stateDir.markerBytes().toString(Charsets.UTF_8)
        assertTrue(pending, "pending" in pending)
        assertTrue(store.needsManualSelectionRecovery("g"))

        // 下一次显式选择成功后自愈，标记回到 Present
        store.withManualSelection("g") { onDirectoryCommitted -> onDirectoryCommitted() }
        assertTrue(RpgSaveSyncState(stateDir).hasManualSelection("g"))
        assertFalse(store.needsManualSelectionRecovery("g"))
    }

    @Test
    fun oversizedMarkerRollsBackToUnreadableSentinelAndCanBeRepaired() {
        val stateDir = temporaryFolder.newFolder("state_oversized")
        val store = RpgSaveSyncState(stateDir)
        assertTrue(store.markManualSelection("g"))
        val marker = stateDir.markers().single()
        marker.writeBytes(ByteArray(64 * 1024 + 1))

        val oversized = assertThrows(GameSaveException::class.java) { store.hasManualSelection("g") }
        assertEquals(SaveErrorCode.SAVE_DIR_UNAVAILABLE, oversized.code)

        val failure = IOException("directory swap failed")
        assertSame(failure, assertThrows(IOException::class.java) {
            store.withManualSelection<Unit>("g") { _ -> throw failure }
        })
        // 超大内容无法原样保留，但显式选择必须已原子替换它，并以不可读哨兵失败关闭。
        assertTrue(marker.isFile)
        assertTrue(marker.length() <= 64 * 1024)
        val closed = assertThrows(GameSaveException::class.java) { store.hasManualSelection("g") }
        assertEquals(SaveErrorCode.SAVE_DIR_UNAVAILABLE, closed.code)
        assertTrue(store.needsManualSelectionRecovery("g"))

        store.withManualSelection("g") { onDirectoryCommitted -> onDirectoryCommitted() }
        assertTrue(store.hasManualSelection("g"))
    }

    @Test
    fun emptyAndNonEmptyMarkerDirectoriesArePreservedAndFailClosed() {
        val stateDir = temporaryFolder.newFolder("state_empty_marker")
        val store = RpgSaveSyncState(stateDir)
        assertTrue(store.markManualSelection("g"))
        val marker = stateDir.markers().single()
        assertTrue(marker.delete())
        assertTrue(marker.mkdir())
        var blockCalled = false

        val unavailable = assertThrows(GameSaveException::class.java) { store.hasManualSelection("g") }
        assertEquals(marker.toPath().toAbsolutePath().normalize().toString(), unavailable.detail)
        val marked = assertThrows(GameSaveException::class.java) { store.markManualSelection("g") }
        assertEquals(SaveErrorCode.SAVE_DIR_UNAVAILABLE, marked.code)
        assertEquals(unavailable.detail, marked.detail)

        val empty = assertThrows(GameSaveException::class.java) {
            store.withManualSelection<Unit>("g") { blockCalled = true }
        }
        assertEquals(SaveErrorCode.SAVE_DIR_UNAVAILABLE, empty.code)
        assertEquals(marker.toPath().toAbsolutePath().normalize().toString(), empty.detail)
        assertFalse(blockCalled)
        assertTrue(marker.isDirectory)
        assertTrue(checkNotNull(marker.listFiles()).isEmpty())
        assertTrue(store.needsManualSelectionRecovery("g"))

        val nonEmptyDir = temporaryFolder.newFolder("state_nonempty_marker")
        val blocked = RpgSaveSyncState(nonEmptyDir)
        assertTrue(blocked.markManualSelection("h"))
        val nonEmptyMarker = nonEmptyDir.markers().single()
        assertTrue(nonEmptyMarker.delete())
        assertTrue(nonEmptyMarker.mkdir())
        val child = File(nonEmptyMarker, "keep.txt").apply { writeText("keep") }
        blockCalled = false

        val nonEmpty = assertThrows(GameSaveException::class.java) {
            blocked.withManualSelection<Unit>("h") { blockCalled = true }
        }
        assertEquals(SaveErrorCode.SAVE_DIR_UNAVAILABLE, nonEmpty.code)
        assertEquals(nonEmptyMarker.toPath().toAbsolutePath().normalize().toString(), nonEmpty.detail)
        assertFalse(blockCalled)
        assertTrue(nonEmptyMarker.isDirectory)
        assertEquals("keep", child.readText())
    }

    @Test
    fun metadataStatFailureIsUnreadableInsteadOfAbsent() {
        val blockedParent = temporaryFolder.newFile("blocked_state_parent")
        val store = RpgSaveSyncState(blockedParent)

        val failure = assertThrows(GameSaveException::class.java) { store.hasManualSelection("g") }

        assertEquals(SaveErrorCode.SAVE_DIR_UNAVAILABLE, failure.code)
        assertTrue(store.needsManualSelectionRecovery("g"))
    }

    @Test
    fun clearRaisesWhenManualMarkerCleanupCannotComplete() {
        val stateDir = temporaryFolder.newFolder("state_clear_failure")
        val store = RpgSaveSyncState(stateDir)
        assertTrue(store.markManualSelection("g"))
        val marker = stateDir.markers().single()
        assertTrue(marker.delete())
        assertTrue(marker.mkdir())
        val child = File(marker, "keep.txt").apply { writeText("keep") }

        assertFalse(store.clearManualSelection("g"))
        val deleteFailure = assertThrows(GameSaveException::class.java) { store.clear("g") }
        assertEquals(SaveErrorCode.SAVE_DIR_UNAVAILABLE, deleteFailure.code)
        assertEquals(marker.toPath().toAbsolutePath().normalize().toString(), deleteFailure.detail)
        assertTrue(marker.isDirectory)
        assertEquals("keep", child.readText())

        val manifests = temporaryFolder.newFolder("state_migration_clear")
        val legacy = RpgSaveSyncState(manifests)
        assertTrue(legacy.markManualSelection("m"))
        val legacyMarker = manifests.markers().single()
        val blockedSelections = temporaryFolder.newFile("blocked_selections_parent")
        val migrating = RpgSaveSyncState(manifests, blockedSelections)

        val migrationFailure = assertThrows(GameSaveException::class.java) { migrating.clear("m") }
        assertEquals(SaveErrorCode.SAVE_DIR_UNAVAILABLE, migrationFailure.code)
        assertTrue(legacyMarker.isFile)
        assertTrue(legacy.hasManualSelection("m"))
    }

    @Test
    fun clearDeletesCurrentEmptyDirectoryAndLegacyMarkerWithoutMigration() {
        val manifests = temporaryFolder.newFolder("state_dual_clear_manifests")
        val selections = temporaryFolder.newFolder("state_dual_clear_selections")
        val legacy = RpgSaveSyncState(manifests)
        assertTrue(legacy.markManualSelection("g"))
        val legacyMarker = manifests.markers().single()
        val currentMarker = File(selections, legacyMarker.name)
        assertTrue(currentMarker.mkdir())
        val current = RpgSaveSyncState(manifests, selections)

        current.clear("g")

        assertFalse(currentMarker.exists())
        assertFalse(legacyMarker.exists())
        assertFalse(current.hasManualSelection("g"))
    }

    @Test
    fun selectionMarkerMigratesWithoutMovingTheExistingManifest() {
        val manifests = temporaryFolder.newFolder("manifests")
        val selections = temporaryFolder.newFolder("no_backup_selections")
        val legacy = RpgSaveSyncState(manifests)
        assertTrue(legacy.markManualSelection("g"))
        val oldMarker = manifests.markers().single()
        val manifest = File(manifests, "existing.json").apply { writeText("manifest") }

        val current = RpgSaveSyncState(manifests, selections)
        assertTrue(current.hasManualSelection("g"))
        assertFalse(oldMarker.exists())
        assertEquals(1, selections.markers().size)
        assertEquals("manifest", manifest.readText())
        assertTrue(current.clearManualSelection("g"))
        assertTrue(selections.markers().isEmpty())
    }
}
