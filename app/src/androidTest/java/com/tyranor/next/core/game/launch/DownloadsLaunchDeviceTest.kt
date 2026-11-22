package com.tyranor.next.core.game.launch

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.provider.BaseColumns
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import bridge.PrimaryStorageRoot
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.GamePathUtils
import com.tyranor.next.core.game.model.ScanGame
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@SdkSuppress(maxSdkVersion = 29)
class DownloadsLaunchDeviceTest {
    @Test
    fun downloadsGameUsesLegacyStoragePermission() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(app.packageName == "com.tyranor.next.downloadstest")
        val granted = InstrumentationRegistry.getArguments().getString("downloads_permission_granted")
            ?.toBooleanStrictOrNull()
        assumeTrue("Set downloads_permission_granted to match the device permission state", granted != null)
        for (permission in listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            assertEquals(granted, app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
        }

        val directory = File(PrimaryStorageRoot.get(), "Download/tyranor-test-${UUID.randomUUID()}")
        val game = downloadsGame("raw:${directory.absolutePath}")
        if (granted == false) {
            assertEquals(LaunchResult.Failure.StorageAccessRequested, EngineLauncher.launch(app, game))
        } else {
            try {
                check(directory.mkdirs())
                directory.resolve("index.html").writeText("<!doctype html><title>Downloads launch fixture</title>")
                assertEquals(LaunchResult.Success, EngineLauncher.launch(app, game))
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29, maxSdkVersion = 29)
    fun indexedDownloadsDocumentsResolveOnAndroid10() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(app.packageName == "com.tyranor.next.downloadstest")
        assumeTrue(InstrumentationRegistry.getArguments().getString("downloads_permission_granted") == "true")

        val directory = File(PrimaryStorageRoot.get(), "Download/tyranor-test-${UUID.randomUUID()}")
        try {
            check(directory.mkdirs())
            val entry = directory.resolve("index.html").apply {
                writeText("<!doctype html><title>Indexed Downloads launch fixture</title>")
            }
            val scanned = CountDownLatch(1)
            MediaScannerConnection.scanFile(app, arrayOf(entry.path), null) { _, _ -> scanned.countDown() }
            check(scanned.await(15, TimeUnit.SECONDS)) { "MediaStore did not finish indexing the fixture" }

            fun downloadId(file: File): Long = checkNotNull(app.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(BaseColumns._ID),
                "${MediaStore.MediaColumns.DATA}=?",
                arrayOf(file.path),
                null,
            )?.use { if (it.moveToFirst()) it.getLong(0) else null }) { "No Downloads row for $file" }

            val dirId = "msd:${downloadId(directory)}"
            val fileId = "msf:${downloadId(entry)}"
            val tree = DocumentsContract.buildTreeDocumentUri(DOWNLOADS_AUTHORITY, dirId)
            val fileUri = DocumentsContract.buildDocumentUriUsingTree(tree, fileId)
            assertEquals(entry.path, GamePathUtils.resolveLocalPath(app, fileUri.toString()))
            assertEquals(LaunchResult.Success, EngineLauncher.launch(app, downloadsGame(dirId)))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun downloadsGame(id: String): ScanGame {
        val tree = DocumentsContract.buildTreeDocumentUri(DOWNLOADS_AUTHORITY, id)
        return ScanGame(
            title = "Downloads launch fixture",
            uri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(),
            engine = EngineType.WEB_OTHER,
            launchTarget = "index.html",
        )
    }

    private companion object {
        const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"
    }
}
