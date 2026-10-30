package com.tyranor.next.core.game.save.sync

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.save.EthornellSaveCapability
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises the real runtime entry point rather than just supportsManagedSave or a fake resolver. */
@RunWith(AndroidJUnit4::class)
class SaveLaunchAdmissionRuntimeTest {
    @Test
    fun ethornellWithoutVerifiedSaveIoBypassesRuntimeBookkeepingAndSaveOperationFence() = runBlocking {
        assertFalse(EthornellSaveCapability.available)
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                throw AssertionError("ETHORNELL admission must not read WebDAV settings")
            override fun getFilesDir(): File =
                throw AssertionError("ETHORNELL admission must not resolve managed save files")
            override fun getNoBackupFilesDir(): File =
                throw AssertionError("ETHORNELL admission must not create save-sync state")
            override fun getExternalFilesDir(type: String?): File? =
                throw AssertionError("ETHORNELL admission must not resolve external save files")
        }
        val game = ScanGame("ETHORNELL admission", "file:///unused-ethornell-admission", EngineType.ETHORNELL, "")
        val exclusive = launch(start = CoroutineStart.UNDISPATCHED) {
            SaveSyncLocks.mutation { awaitCancellation() }
        }
        try {
            assertEquals(SaveSyncLaunchAdmission.Unmanaged, SaveSyncRuntime.launchAdmission(context, game))
            val result = withTimeout(5_000) {
                withSaveSyncLaunchAdmission(
                    resolve = { SaveSyncRuntime.launchAdmission(context, game) },
                    managed = { throw AssertionError("ETHORNELL was admitted to managed save bookkeeping") },
                    unmanaged = { "engine-start-path" },
                )
            }
            assertEquals("engine-start-path", result)
        } finally {
            exclusive.cancelAndJoin()
        }
    }
}
