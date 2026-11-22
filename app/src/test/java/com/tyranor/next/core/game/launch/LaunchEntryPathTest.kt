package com.tyranor.next.core.game.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 文件型外置入口（SWF）的目录决策：tree / document / file / 外部 provider 分支。
 * 纯函数直接覆盖，无需 Android Context。
 */
class LaunchEntryPathTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun rejectsRelativePath() {
        assertNull(EngineLauncher.resolveLaunchEntryPath("content://unknown", "games/game.swf", "game.swf"))
    }

    @Test
    fun pureTreeUriMapsToDirectoryBeforePermissionCheck() {
        val folder = temporaryFolder.newFolder("Tree")
        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ATree"

        assertEquals(
            folder.absolutePath,
            EngineLauncher.resolveLaunchEntryPath(treeUri, folder.absolutePath, "story.swf"),
        )
    }

    @Test
    fun externalStorageDocumentFileMapsToParent() {
        val folder = temporaryFolder.newFolder("Document")
        val swf = folder.resolve("game.swf").apply { writeText("swf") }
        val uri = "content://com.android.externalstorage.documents/tree/primary%3ADocument/document/primary%3ADocument%2Fgame.swf"

        assertEquals(
            folder.absolutePath,
            EngineLauncher.resolveLaunchEntryPath(uri, swf.absolutePath, "game.swf"),
        )
    }

    @Test
    fun externalStorageDocumentDirectoryMapsToItself() {
        val folder = temporaryFolder.newFolder("Wrapper")
        folder.resolve("story.swf").writeText("swf")
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AWrapper/document/primary%3AWrapper"

        assertEquals(
            folder.absolutePath,
            EngineLauncher.resolveLaunchEntryPath(uri, folder.absolutePath, "story.swf"),
        )
    }

    @Test
    fun downloadsDocumentFileMapsToParent() {
        val folder = temporaryFolder.newFolder("Downloads")
        val swf = folder.resolve("downloaded.swf").apply { writeText("swf") }
        val uri = "content://com.android.providers.downloads.documents/tree/msd%3A12/document/msf%3A42"

        assertEquals(
            folder.absolutePath,
            EngineLauncher.resolveLaunchEntryPath(uri, swf.absolutePath, "downloaded.swf"),
        )
    }

    @Test
    fun fileUriAndAbsolutePathEntriesWork() {
        val folder = temporaryFolder.newFolder("File Entry")
        val swf = folder.resolve("game.swf").apply { writeText("swf") }

        assertEquals(
            folder.absolutePath,
            EngineLauncher.resolveLaunchEntryPath(swf.toURI().toString(), swf.absolutePath, "game.swf"),
        )
        assertEquals(
            folder.absolutePath,
            EngineLauncher.resolveLaunchEntryPath(swf.absolutePath, swf.absolutePath, "game.swf"),
        )
    }

    @Test
    fun keepsParentFallbackBeforeStoragePermissionIsGranted() {
        val missing = File(temporaryFolder.root, "No Permission/game.swf")

        // 缺少「所有文件访问」时 File.isFile 为 false：仍按 .swf 入口取父目录，让权限门禁先运行
        assertEquals(
            missing.parentFile!!.absolutePath,
            EngineLauncher.resolveLaunchEntryPath(missing.absolutePath, missing.absolutePath, "game.swf"),
        )
    }
}
