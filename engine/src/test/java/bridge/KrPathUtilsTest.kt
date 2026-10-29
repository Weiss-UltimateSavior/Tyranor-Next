package bridge

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KrPathUtilsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun sourceSaveDirectoryMapsToADifferentIndependentDestination() {
        val source = temporary.newFolder("game")
        val target = temporary.newFolder("private-saves")
        val request = File(source, "savedata/Slot.KSD")
        assertEquals(File(target, "Slot.KSD").canonicalFile, resolve(request, source, target))
        assertEquals(target.canonicalFile, resolve(File(source, "savedata"), source, target))
    }

    @Test
    fun alreadyRedirectedDestinationIsIdempotentAndKeepsSaveFilenameCase() {
        val source = temporary.newFolder("game")
        val target = temporary.newFolder("private-saves")
        val request = File(target, "Nested/Slot.KSD")
        assertEquals(request.canonicalFile, resolve(request, source, target))
        assertEquals(target.canonicalFile, resolve(target, source, target))
    }

    @Test
    fun sdMirrorSourceAndDestinationCanShareTheSameSaveRoot() {
        val source = temporary.newFolder("mirror-game")
        val target = File(source, "savedata")
        val request = File(target, "slot.ksd")
        assertEquals(request.canonicalFile, resolve(request, source, target))
    }

    @Test
    fun nestedSavedataResourcesAndSimilarSiblingNamesAreNotRedirected() {
        val source = temporary.newFolder("game")
        val target = temporary.newFolder("private-saves")
        assertNull(resolve(File(source, "data/savedata/resource.bin"), source, target))
        assertNull(resolve(File(source, "savedata-other/slot.ksd"), source, target))
        assertNull(resolve(File(target.parentFile, target.name + "-other/slot.ksd"), source, target))
    }

    @Test
    fun sourceAndDestinationTraversalCannotEscapeTheConfiguredSaveRoot() {
        val source = temporary.newFolder("game")
        val target = temporary.newFolder("private-saves")
        assertNull(resolve(File(source, "savedata/../../outside.ksd"), source, target))
        assertNull(resolve(File(target, "../outside.ksd"), source, target))
    }

    @Test
    fun missingDestinationUsesOnlyTheTopLevelProjectSavedata() {
        val source = temporary.newFolder("game")
        val request = File(source, "savedata/slot.ksd")
        assertEquals(request.canonicalFile,
            KrPathUtils.resolveScopedSavePath(request.path, source.path, null, null))
        assertNull(KrPathUtils.resolveScopedSavePath(File(source, "data/savedata/asset").path, source.path, null, null))
    }

    @Test
    fun gameDirectoryRemainsTheFallbackSourceAnchor() {
        val source = temporary.newFolder("game")
        val target = temporary.newFolder("private-saves")
        assertEquals(File(target, "slot.ksd").canonicalFile,
            KrPathUtils.resolveScopedSavePath(File(source, "savedata/slot.ksd").path, null, source.path, target.path))
    }

    @Test
    fun missingSourceStillAllowsIdempotentTargetButRejectsUnrelatedPaths() {
        val target = temporary.newFolder("private-saves")
        val request = File(target, "slot.ksd")
        assertEquals(request.canonicalFile,
            KrPathUtils.resolveScopedSavePath(request.path, null, null, target.path))
        assertNull(KrPathUtils.resolveScopedSavePath(temporary.newFile("unrelated").path, null, null, target.path))
        assertNull(KrPathUtils.resolveScopedSavePath("savedata/slot.ksd", null, null, target.path))
    }

    @Test
    fun storageRootAliasesExpandForEngineMountSpellings() {
        // R9-#14: the engine may spell the primary volume as /sdcard or
        // /storage/self/primary while the launch intent reports /storage/emulated/0;
        // anchor matching must cover all three spellings.
        assertEquals(
            setOf(
                "/storage/emulated/0/game/savedata",
                "/sdcard/game/savedata",
                "/storage/self/primary/game/savedata",
            ),
            KrPathUtils.expandStorageRootAliases("/storage/emulated/0/game/savedata").toSet(),
        )
        assertEquals(
            setOf("/sdcard/game/savedata", "/storage/emulated/0/game/savedata", "/storage/self/primary/game/savedata"),
            KrPathUtils.expandStorageRootAliases("/sdcard/game/savedata/").toSet(),
        )
        assertEquals(listOf("/data/local/game"), KrPathUtils.expandStorageRootAliases("/data/local/game"))
    }

    private fun resolve(request: File, source: File, target: File): File? =
        KrPathUtils.resolveScopedSavePath(request.path, source.path, null, target.path)
}
