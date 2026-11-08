package com.tyranor.next.core.game.storage

import android.app.OnboardingActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.RootsTestUri
import android.os.Process
import android.util.Log
import androidx.room.InvalidationTracker
import com.tyranor.next.core.settings.AppSettingsStore
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GameLibraryFacadeRootsTest {
    private var processFixture: AutoCloseable? = null
    private var loggingFixture: AutoCloseable? = null
    private var repositoryFixtureStarted = false
    private lateinit var blockedFiles: File
    private lateinit var database: File
    private lateinit var preferences: MemoryPreferences
    private lateinit var context: Context

    @Before
    fun prepareUnavailableFreshInstallation() {
        processFixture = Process.fixtureScope(37_001)
        loggingFixture = Log.fixtureScope()
        check(repositoryWriter().children.none()) { "Root queue fixture requires an idle repository" }
        repositoryFixtureStarted = true
        AppSettingsStore.resetOnboardingForTests()
        setCachedRoots(null)
        setPendingWrites(0)
        blockedFiles = Files.createTempFile("roots-cache-blocked-files", ".tmp").toFile()
        database = Files.createTempFile("roots-cache-uncreated-library", ".db").toFile().apply { delete() }
        preferences = MemoryPreferences()
        context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getPackageName(): String = "com.tyranor.next.motionv2"
            override fun getSystemService(name: String): Any? =
                if (name == Context.ACTIVITY_SERVICE) OnboardingActivityManager("com.tyranor.next.motionv2") else null
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "app_settings") preferences.value else EMPTY_PREFERENCES
            override fun getFilesDir(): File = blockedFiles
            override fun getDatabasePath(name: String): File = database
        }
    }

    @After
    fun clearFixtureState() {
        try {
            // Owned writes normally drain inside withPausedRootWrite; failed assertions also clean up
            // before the platform scopes close and other test classes resume their normal mock behavior.
            if (repositoryFixtureStarted) {
                runBlocking {
                    withTimeout(10_000) {
                        repositoryWriter().children.toList().forEach { it.cancelAndJoin() }
                    }
                }
                setCachedRoots(null)
                setPendingWrites(0)
                AppSettingsStore.resetOnboardingForTests()
                if (::blockedFiles.isInitialized) blockedFiles.delete()
                if (::database.isInitialized) database.delete()
            }
        } finally {
            loggingFixture?.close()
            processFixture?.close()
        }
    }

    @Test
    fun unavailableInstallationDoesNotCacheAnUnsavedRootAndTheNextReadRetries() {
        val revision = GameLibraryFacade.rootsRevision.value
        assertTrue(GameLibraryFacade.saveRoot(context, " /not-saved/ ").isEmpty())
        val attempts = preferences.commits
        assertTrue(GameLibraryFacade.loadRoots(context).isEmpty())
        assertTrue("Unavailable roots must not become a cached empty view", preferences.commits > attempts)
        assertEquals("A rejected root write must not advertise an optimistic change", revision, GameLibraryFacade.rootsRevision.value)
        assertFalse(database.exists())
    }

    @Test
    fun persistenceFailureInvalidatesRootSnapshotAndNotifiesTheExistingViewToReload() {
        // Install the production persistence-failure listener through an actual uncached read.
        assertTrue(GameLibraryFacade.loadRoots(context).isEmpty())
        setCachedRoots(listOf("/previous-root"))
        assertEquals(listOf("/previous-root"), GameLibraryFacade.loadRoots(context))
        val revision = GameLibraryFacade.rootsRevision.value
        val attempts = preferences.commits

        checkNotNull(GameLibraryRepository.onPersistFailure).invoke()

        assertEquals(revision + 1, GameLibraryFacade.rootsRevision.value)
        assertTrue(GameLibraryFacade.loadRoots(context).isEmpty())
        assertTrue("An invalidated root snapshot must allow a fresh persistence attempt", preferences.commits > attempts)
        val afterReload = GameLibraryFacade.rootsRevision.value
        checkNotNull(GameLibraryRepository.onPersistFailure).invoke()
        assertEquals("Repeated failures without a root snapshot need no extra invalidation", afterReload, GameLibraryFacade.rootsRevision.value)
    }

    @Test
    fun aSuccessfullyLoadedEmptyRootSnapshotIsDistinctFromUnavailableStorage() {
        setCachedRoots(emptyList())
        val attempts = preferences.commits
        assertTrue(GameLibraryFacade.loadRoots(context).isEmpty())
        assertTrue(GameLibraryFacade.loadRoots(context).isEmpty())
        assertEquals("A real empty snapshot remains a normal cache hit", attempts, preferences.commits)
    }

    @Test
    fun aFailedEarlierWriteCannotCacheThePendingProjectionAheadOfTheLaterSuccessfulWrite() {
        withPausedRootWrite(expected = listOf("/saved-b"), verifyColdReads = true) {
            // Read only: no third root command may replace the failure-invalidated snapshot.
            assertEquals(listOf("/saved-b"), GameLibraryFacade.loadRoots(context))
        }
    }

    @Test
    fun normallySuccessfulQueuedWritesKeepTheLaterOptimisticRootSnapshot() {
        setCachedRoots(listOf("/saved-a", "/saved-b"))
        setPendingWrites(2)
        val revision = GameLibraryFacade.rootsRevision.value

        finishQueuedWrite()
        assertEquals(listOf("/saved-a", "/saved-b"), GameLibraryFacade.loadRoots(context))
        finishQueuedWrite()
        assertEquals(listOf("/saved-a", "/saved-b"), GameLibraryFacade.loadRoots(context))
        assertEquals("Normal completion must not invalidate a newer optimistic snapshot", revision, GameLibraryFacade.rootsRevision.value)
    }

    @Test
    fun addingAnotherRootAfterFailureImmediatelyIncludesTheStillPendingRoot() {
        withPausedRootWrite(expected = listOf("/saved-b", "/saved-c")) {
            assertEquals(listOf("/saved-b", "/saved-c"), GameLibraryFacade.saveRoot(context, "/saved-c"))
        }
    }

    @Test
    fun removingAStillPendingInsertionAfterFailurePreservesTheRequestedOrder() {
        withPausedRootWrite(expected = emptyList()) {
            GameLibraryFacade.removeRoot(context, RootsTestUri("/saved-b"))
        }
    }

    @Test
    fun reAddingAStillPendingRemovalAfterFailurePreservesTheRequestedOrder() {
        withPausedRootWrite(removingB = true, expected = listOf("/saved-b")) {
            GameLibraryFacade.saveRoot(context, "/saved-b")
        }
    }

    @Test
    fun successfulQueuedWritesKeepTheirImmediateAndPersistedResults() {
        withPausedRootWrite(failA = false, expected = listOf("/failed-a", "/saved-b", "/saved-c")) {
            GameLibraryFacade.saveRoot(context, "/saved-c")
        }
    }

    /** Real facade/repository FIFO, with only the Room transaction boundary and DAO replaced. */
    private fun withPausedRootWrite(
        removingB: Boolean = false,
        failA: Boolean = true,
        expected: List<String>,
        verifyColdReads: Boolean = false,
        action: () -> Unit,
    ) {
        val enteredA = CountDownLatch(1)
        val enteredB = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val releaseB = CountDownLatch(1)
        val rootReads = AtomicInteger()
        val rows = linkedMapOf<String, ScanRootEntity>()
        if (removingB) rows["/saved-b"] = ScanRootEntity("/saved-b", addedAt = 0)
        fun pause(entered: CountDownLatch, release: CountDownLatch) {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "Timed out releasing a test root write" }
        }
        val dao: GameLibraryDao = proxy { method, args ->
            when (method) {
                "getRoots" -> synchronized(rows) {
                    rootReads.incrementAndGet()
                    rows.values.toList()
                }
                "insertRootsIfAbsent" -> {
                    @Suppress("UNCHECKED_CAST")
                    for (row in args[0] as List<ScanRootEntity>) {
                        if (row.uri == "/failed-a") {
                            pause(enteredA, releaseA)
                            if (failA) throw IOException("Synthetic root write failure")
                        }
                        if (row.uri == "/saved-b" && !removingB) pause(enteredB, releaseB)
                        synchronized(rows) { rows.putIfAbsent(row.uri, row) }
                    }
                    Unit
                }
                "deleteRoot" -> {
                    if (args[0] == "/saved-b" && removingB) pause(enteredB, releaseB)
                    synchronized(rows) { if (rows.remove(args[0]) == null) 0 else 1 }
                }
                else -> error("Unexpected root DAO method: $method")
            }
        }
        val replacement = object : GameLibraryDatabase() {
            override fun gameLibraryDao(): GameLibraryDao = dao
            override fun createInvalidationTracker(): InvalidationTracker = error("Not used by root writes")
            override fun clearAllTables() = error("Not used by root writes")
            override val transactionExecutor: Executor = Executor { it.run() }
            @Suppress("DEPRECATION")
            override fun beginTransaction() = Unit
            @Suppress("DEPRECATION")
            override fun setTransactionSuccessful() = Unit
            @Suppress("DEPRECATION")
            override fun endTransaction() = Unit
        }
        val databaseField = GameLibraryDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val migrationField = GameLibraryRepository::class.java.getDeclaredField("migrationDone").apply { isAccessible = true }
        val mirrorField = GameLibraryRepository::class.java.getDeclaredField("mirrorJob").apply { isAccessible = true }
        val writer = repositoryWriter()
        check(writer.children.none()) { "Root queue fixture requires an idle repository" }
        val previousDatabase = databaseField.get(null)
        val previousMigration = migrationField.getBoolean(GameLibraryRepository)
        val previousMirror = mirrorField.get(GameLibraryRepository)
        fun drainOwnWrites() = runBlocking {
            withTimeout(10_000) {
                // Each completed write can replace mirrorJob after this loop observes it. Keep
                // cancelling the latest mirror instead of accidentally joining a new delayed one.
                while (writer.children.any()) {
                    (mirrorField.get(GameLibraryRepository) as Job?)?.cancelAndJoin()
                    delay(1)
                }
            }
        }
        databaseField.set(null, replacement)
        migrationField.setBoolean(GameLibraryRepository, true)
        preferences = MemoryPreferences(mapOf(AppSettingsStore.KEY_ONBOARDING_COMPLETED to false))
        try {
            GameLibraryFacade.saveRoot(context, "/failed-a")
            assertTrue("First write never reached the DAO", enteredA.await(10, TimeUnit.SECONDS))
            if (removingB) GameLibraryFacade.removeRoot(context, RootsTestUri("/saved-b"))
            else GameLibraryFacade.saveRoot(context, "/saved-b")
            releaseA.countDown()
            assertTrue("Pending write never reached the DAO", enteredB.await(10, TimeUnit.SECONDS))

            action()
            val readsBeforePendingLoad = rootReads.get()
            assertEquals("Accepted commands must be visible to the immediate scan", expected, GameLibraryFacade.loadRoots(context))
            if (verifyColdReads) {
                assertEquals("A pending projection must remain uncached", readsBeforePendingLoad + 1, rootReads.get())
            }
            releaseB.countDown()
            drainOwnWrites()
            val readsBeforeCompletedLoad = rootReads.get()
            assertEquals("Completed queue must reload the authoritative roots", expected, GameLibraryFacade.loadRoots(context))
            if (verifyColdReads) {
                assertEquals("Completion must read the DAO again instead of reusing the pending projection", readsBeforeCompletedLoad + 1, rootReads.get())
            }
            assertEquals("DAO must receive the same commands in FIFO order", expected, synchronized(rows) { rows.keys.toList() })
        } finally {
            releaseA.countDown()
            releaseB.countDown()
            try {
                drainOwnWrites()
                val pending = GameLibraryFacade::class.java.getDeclaredField("pendingRootChanges").apply { isAccessible = true }
                assertTrue("All owned root changes must finish before restoring the database", (pending.get(GameLibraryFacade) as Collection<*>).isEmpty())
            } finally {
                databaseField.set(null, previousDatabase)
                migrationField.setBoolean(GameLibraryRepository, previousMigration)
                mirrorField.set(GameLibraryRepository, previousMirror)
            }
        }
    }

    private fun repositoryWriter(): Job {
        val scope = GameLibraryRepository::class.java.getDeclaredField("writeScope").apply { isAccessible = true }
        return (scope.get(GameLibraryRepository) as CoroutineScope).coroutineContext[Job]!!
    }

    /** Seeds a previously loaded snapshot without opening Android SQLite in a host JVM. */
    private fun setCachedRoots(roots: List<String>?) {
        GameLibraryFacade::class.java.getDeclaredField("rootsCache").apply {
            isAccessible = true
            set(GameLibraryFacade, roots)
        }
    }

    private fun setPendingWrites(count: Int) {
        GameLibraryFacade::class.java.getDeclaredField("pendingRootWrites").apply {
            isAccessible = true
            setInt(GameLibraryFacade, count)
        }
    }

    private fun finishQueuedWrite() {
        GameLibraryFacade::class.java.getDeclaredMethod("finishRootWrite").apply {
            isAccessible = true
            invoke(GameLibraryFacade)
        }
    }

    private class MemoryPreferences(initial: Map<String, Any> = emptyMap()) {
        private val memory = HashMap(initial)
        var commits = 0
        val value: SharedPreferences = proxy { method, args ->
            when (method) {
                "contains" -> memory.containsKey(args[0])
                "getBoolean" -> memory[args[0]] ?: args[1]
                "getAll" -> memory.toMap()
                "edit" -> editor()
                else -> error("Unexpected preferences method: $method")
            }
        }

        private fun editor(): SharedPreferences.Editor {
            val changes = HashMap<String, Any?>()
            lateinit var editor: SharedPreferences.Editor
            editor = proxy { method, args ->
                when (method) {
                    "putBoolean" -> { changes[args[0] as String] = args[1]; editor }
                    "remove" -> { changes[args[0] as String] = null; editor }
                    "commit", "apply" -> {
                        for ((key, changed) in changes) {
                            if (changed == null) memory.remove(key) else memory[key] = changed
                        }
                        if (method == "commit") { commits++; false } else null
                    }
                    else -> error("Unexpected editor method: $method")
                }
            }
            return editor
        }
    }

    companion object {
        private val EMPTY_PREFERENCES = MemoryPreferences().value

        private inline fun <reified T> proxy(crossinline invoke: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                invoke(method.name, args ?: emptyArray())
            } as T
    }
}
