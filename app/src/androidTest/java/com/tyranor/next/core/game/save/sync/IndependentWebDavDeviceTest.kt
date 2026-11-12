package com.tyranor.next.core.game.save.sync

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.ZoneOffset
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two independently installed clients rendezvous on an independently hosted DAV server.
 * The driver owns a fresh server root, adb reverse, concurrent runs, and server cleanup.
 */
@RunWith(AndroidJUnit4::class)
class IndependentWebDavDeviceTest {
    @Test(timeout = 180_000)
    fun concurrentClientsPublishAndDownloadTheSameHistory() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(context.packageName.startsWith("com.tyranor.next.webdavacceptance") &&
            args.getString("webdav_device_acceptance") == "true" &&
            args.getString("webdav_independent_server") == "true")
        val run = requireNotNull(args.getString("webdav_run"))
        require(run.matches(Regex("[a-f0-9]{32}")))
        val role = requireNotNull(args.getString("webdav_role"))
        require(role == "phone" || role == "emulator")
        val epoch = requireNotNull(args.getString("webdav_epoch")).toLong()
        require(epoch in (System.currentTimeMillis() - 3_600_000)..System.currentTimeMillis())
        // This endpoint is a driver-created adb reverse, never a user's configured cloud.
        val config = WebDavConfig("http://127.0.0.1:18763/", "acceptance", "synthetic-only")
        val game = RemoteSaveGame("ONS", "independent-server-$run")
        val root = File(context.cacheDir, "independent_dav_${UUID.randomUUID()}")
        check(root.mkdirs())
        try {
            WebDavClient(config, root).use { client ->
                client.testConnection()
                val sharedArchive = archive(root, "shared.zip", "shared-$run")
                val sharedHash = SaveContentHash.archive(sharedArchive)
                val shared = SaveVersion(SaveVersionNames.createRemote(epoch, sharedHash, ZoneOffset.UTC),
                    sharedHash, epoch, sharedArchive.length())
                client.upload(game, shared, sharedArchive)

                val ownArchive = archive(root, "$role.zip", "$role-$run")
                val ownHash = SaveContentHash.archive(ownArchive)
                val ownTime = epoch + if (role == "phone") 1 else 2
                val own = SaveVersion(SaveVersionNames.createRemote(ownTime, ownHash, ZoneOffset.UTC),
                    ownHash, ownTime, ownArchive.length())
                assertTrue(client.upload(game, own, ownArchive).uploaded)

                val all = withTimeout(90_000) {
                    var listed = client.list(game)
                    while (listed.size < 3) {
                        delay(250)
                        listed = client.list(game)
                    }
                    listed
                }
                assertEquals(3, all.size)
                assertEquals(1, all.count { it.contentHash == sharedHash })
                assertTrue(all.any { it.contentHash == ownHash })
                all.forEach { remote ->
                    assertNotNull("Independent server must supply an ETag", remote.etag)
                    val target = File(root, "download-${remote.contentHash}.zip")
                    client.download(game, remote, target)
                    assertEquals(remote.contentHash, SaveContentHash.archive(target))
                }
                // A repeat upload must verify the immutable collision instead of replacing it.
                assertTrue(!client.upload(game, shared, sharedArchive).uploaded)
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("webdav_independent", "$role: three verified archives; duplicate preserved")
                })
            }
        } finally {
            check(root.deleteRecursively())
        }
    }

    private fun archive(root: File, name: String, value: String): File =
        File(root, name).apply {
            ZipOutputStream(outputStream()).use { zip ->
                // Identical ZIP metadata makes the shared object identical across devices.
                zip.putNextEntry(ZipEntry("slot.dat").apply { time = 0 })
                zip.write(value.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
}
