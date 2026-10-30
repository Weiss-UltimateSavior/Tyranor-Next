package com.tyranor.next.core.game.save.sync

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import com.core.engine.LaunchContract
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.save.GameSaveManager
import com.tyranor.next.core.game.storage.GameLibraryFacade
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** App-side lifecycle adapter. Engine runtimes and their native save writers remain unchanged. */
object SaveSyncRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val monitors = ConcurrentHashMap.newKeySet<String>()
    private val nativeLaunchGraces = NativeLaunchGraceRegistry(MAX_LAUNCH_GRACE_ENTRIES)
    private val externalLaunchOwner = UUID.randomUUID().toString()
    private val externalDispatchSequence = java.util.concurrent.atomic.AtomicLong()
    private val externalLaunchLock = Any()
    private val externalLaunches = HashMap<String, ExternalSaveLaunch>()
    private val secondaryActivityProcessNamesLock = Any()
    @Volatile private var cachedSecondaryActivityProcessNames: Set<String>? = null
    @Volatile private var leftUiSinceLastForeground = false

    internal data class LaunchTicket(
        val token: String,
        val previous: JSONObject?,
        val generation: Long,
        val tracksSession: Boolean = true,
    )
    private data class CachedBaseline(val contentHash: String, val metadataHash: String)
    private data class SaveMetadataObservation(val root: File, val files: List<File>, val metadataHash: String)
    private data class SaveTreeSnapshot(val contentHash: String?, val metadataHash: String)
    private sealed interface FlushDecision {
        data object Busy : FlushDecision
        data object StateChanged : FlushDecision
        data object SessionReplaced : FlushDecision
        data object Unchanged : FlushDecision
        data class Changed(val snapshot: SaveTreeSnapshot) : FlushDecision
    }

    fun install(application: Application) {
        val process = processName(application)
        if (process == application.packageName || !process.startsWith(application.packageName + ":")) return
        val hostActivities = HostActivityUriRegistry<Activity>()
        val hostWrite = Mutex()
        val idleSince = java.util.concurrent.atomic.AtomicLong(0L)
        val closingUris = ConcurrentHashMap.newKeySet<String>()
        val reconciliationRunning = java.util.concurrent.atomic.AtomicBoolean(false)
        val runtime = runtimeStore(application)
        val key = SaveContentHash.text(process)
        fun nativeOwnerPending(): Boolean = process == application.packageName + ":siglus" &&
            com.core.siglus.SiglusEngineAdmission.hasActiveOwner()
        fun registerActivity(activity: Activity) {
            val uri = activity.intent
                ?.getStringExtra(LaunchContract.ROOT_URI)
                ?.takeIf { it.isNotBlank() }
            hostActivities.register(activity, uri)
        }
        suspend fun recordNow(): Boolean =
            hostWrite.withLock {
                try {
                    val snapshot = hostActivities.snapshot()
                    val pending = nativeOwnerPending()
                    val uris = if (pending) (snapshot.uris + closingUris).distinct() else snapshot.uris
                    runtime.save(key, JSONObject()
                        .put("pid", Process.myPid())
                        .put("activities", snapshot.activities)
                        .put("idleSince", idleSince.get())
                        .put("nativeOwnerPending", pending)
                        .put("uris", JSONArray(uris)))
                    if (!pending) closingUris.clear()
                    true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // An unreadable/missing record is treated as busy by the reader.
                    false
                }
            }
        fun record() { scope.launch { recordNow() } }
        fun reconcileOwner() {
            if (!reconciliationRunning.compareAndSet(false, true)) return
            scope.launch {
                try {
                    while (true) {
                        while (nativeOwnerPending()) delay(200)
                        if (hostActivities.activityCount() == 0) idleSince.set(SystemClock.elapsedRealtime())
                        if (!recordNow()) {
                            delay(1_000)
                            continue
                        }
                        if (nativeOwnerPending()) continue
                        if (hostActivities.activityCount() == 0) {
                            application.sendBroadcast(Intent(application, SaveSyncExitReceiver::class.java))
                        }
                        break
                    }
                } finally {
                    reconciliationRunning.set(false)
                }
                if (nativeOwnerPending() || (hostActivities.activityCount() == 0 && idleSince.get() == 0L)) {
                    reconcileOwner()
                }
            }
        }
        record()
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                idleSince.set(0L)
                registerActivity(activity)
                record()
            }
            override fun onActivityDestroyed(activity: Activity) {
                if (nativeOwnerPending()) closingUris += hostActivities.snapshot().uris
                hostActivities.unregister(activity)
                if (hostActivities.activityCount() == 0 && !activity.isChangingConfigurations) {
                    // Always reconcile, even if the owner releases between the lifecycle
                    // callback and this coroutine. One final record precedes the exit signal.
                    idleSince.set(0L)
                    record()
                    reconcileOwner()
                } else {
                    record()
                    if (nativeOwnerPending()) reconcileOwner()
                }
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = record()
            override fun onActivityPaused(activity: Activity) = record()
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        })
    }

    /** Called under the same active-save mutex as launch and explicit version selection. */
    internal suspend fun prepareLaunch(context: Context, game: ScanGame,
        expectedActiveKey: String = activeKey(context, game)): LaunchTicket? =
        SaveSyncLocks.session(SaveContentHash.text(game.uri)) { prepareLaunchLocked(context, game, expectedActiveKey) }

    private suspend fun prepareLaunchLocked(context: Context, game: ScanGame, expectedActiveKey: String): LaunchTicket? {
        try {
            val enabledGeneration = WebDavSettingsStore.enabledGeneration(context) ?: return null
            requireSameSaveDirectory(expectedActiveKey, activeKey(context, game))
            if (!GameSaveManager(context).resolveSaveLocation(game).available) return null
            if (isGameRunning(context, game)) return null
            val config = WebDavSettingsStore.load(context)
            val token = UUID.randomUUID().toString()
            if (!config.active || !config.mode.uploads) {
                // Manual/pull-only launches, including YURIS, still need the handoff hint.
                return WebDavSettingsStore.whileEnabled(context, enabledGeneration) {
                    if (game.engine != EngineType.YURIS) markNativeLaunchGrace(game.uri, enabledGeneration, token)
                    LaunchTicket(token, null, enabledGeneration, tracksSession = false)
                }
            }
            val store = sessions(context)
            val key = SaveContentHash.text(game.uri)
            val previous = store.load(key).takeIf { it.has("token") && saveSessionGenerationMatches(it, enabledGeneration) }
            val directoryKey = expectedActiveKey
            directoryActivations(context).requireAvailable(directoryKey)
            // Keep the old task intact rather than relabelling directory A's baseline as B's.
            previous?.let { requireSessionDirectory(it, directoryKey) }
            val inherited = inheritedSessionBaseline(previous)
            val previousBaseline = inherited.orEmpty()
            val inheritBaseline = inherited != null
            var baselineMetadata = previous?.optString("baselineMetadata").orEmpty()
            val cached = if (!inheritBaseline || baselineMetadata.isBlank()) {
                loadCachedBaseline(baselines(context), key, game.uri, directoryKey)
            } else null
            val baseline = if (inheritBaseline) {
                previousBaseline
            } else {
                val manager = GameSaveManager(context)
                val snapshot = resolveBaselineSnapshot(manager, game, cached, directoryKey)
                if (cached == null ||
                    cached.contentHash != snapshot.contentHash ||
                    cached.metadataHash != snapshot.metadataHash) {
                    persistBaseline(context, game, snapshot, directoryKey)
                }
                baselineMetadata = snapshot.metadataHash
                snapshot.contentHash.orEmpty()
            }
            if (baselineMetadata.isBlank() && previousBaseline.isNotBlank()) {
                baselineMetadata = cached?.takeIf { it.contentHash == previousBaseline }?.metadataHash.orEmpty()
            }
            requireSameSaveDirectory(directoryKey, activeKey(context, game))
            return WebDavSettingsStore.whileEnabled(context, enabledGeneration) {
                store.save(key, JSONObject().put("uri", game.uri).put("baseline", baseline)
                    .put("activeKey", directoryKey)
                    .put("enabledGeneration", enabledGeneration)
                    .put("baselineMetadata", baselineMetadata)
                    .put("token", token).put("startedAt", System.currentTimeMillis()).put("ownerPid", Process.myPid()))
                // External YURIS uses its persisted returned-state path instead of a host handoff.
                if (game.engine != EngineType.YURIS) markNativeLaunchGrace(game.uri, enabledGeneration, token)
                LaunchTicket(token, previous, enabledGeneration)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            recordReport(SaveSyncReport(failures = listOf(SaveSyncFailure(game.title, SaveSyncService.errorCode(failure)))))
            return null
        }
    }

    internal suspend fun finishLaunch(context: Context, game: ScanGame, ticket: LaunchTicket?, success: Boolean) =
        withContext(NonCancellable) {
            SaveSyncLocks.session(SaveContentHash.text(game.uri)) { finishLaunchLocked(context, game, ticket, success) }
        }

    private fun finishLaunchLocked(context: Context, game: ScanGame, ticket: LaunchTicket?, success: Boolean) {
        if (ticket == null) return
        if (!success) clearNativeLaunchGrace(game.uri, ticket)
        if (!ticket.tracksSession) return
        try {
            val store = sessions(context)
            val key = SaveContentHash.text(game.uri)
            if (!success) {
                if (store.load(key).optString("token") == ticket.token) {
                    if (ticket.previous == null) store.remove(key) else store.save(key, ticket.previous)
                }
                return
            }
            if (!WebDavSettingsStore.isEnabled(context)) {
                if (store.load(key).optString("token") == ticket.token) store.remove(key)
                return
            }
            monitor(context.applicationContext, game)
            // Persist a fallback even if the launcher is reclaimed while a native game is open.
            SaveSyncWorker.enqueue(context, pull = false, delayed = true)
        } catch (failure: Exception) {
            // Cloud bookkeeping must never turn a successful engine launch into an app error.
            recordReport(SaveSyncReport(failures = listOf(SaveSyncFailure(game.title, SaveSyncService.errorCode(failure)))))
        }
    }

    /**
     * An explicit restore is one pending change even if the next game session writes no bytes.
     * Returns false only when session bookkeeping or work scheduling throws.
     */
    internal suspend fun recordRestoredVersion(
        context: Context,
        game: ScanGame,
        hash: String,
        directoryKey: String,
    ): Boolean {
        try {
            requireSameSaveDirectory(directoryKey, activeKey(context, game))
            // The directory was explicitly replaced. An alias's old gameplay baseline must
            // not reinterpret this selection as progress to publish under another identity.
            retireDirectorySessions(context, directoryKey)
            val key = SaveContentHash.text(game.uri)
            val scheduled = SaveSyncLocks.session(key) {
                // Recheck after waiting; a disabled sweep may have already completed.
                val enabledGeneration = WebDavSettingsStore.enabledGeneration(context) ?: return@session false
                val config = WebDavSettingsStore.load(context)
                if (!config.active || !config.mode.uploads) return@session false
                requireSameSaveDirectory(directoryKey, activeKey(context, game))
                WebDavSettingsStore.whileEnabled(context, enabledGeneration) {
                    sessions(context).save(key, JSONObject()
                        .put("uri", game.uri).put("baseline", hash).put("token", UUID.randomUUID().toString())
                        .put("activeKey", directoryKey)
                        .put("enabledGeneration", enabledGeneration)
                        .put("startedAt", 0L).put("returned", true))
                    true
                } ?: false
            }
            if (scheduled) SaveSyncWorker.enqueue(context, pull = false)
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // The restored archive and promotion checkpoint remain available for the next retry.
            recordReport(SaveSyncReport(failures = listOf(SaveSyncFailure(game.title, SaveSyncService.errorCode(failure)))))
            return false
        }
    }

    /** Caller holds the directory lock; persisted ownership survives missing SAF/library aliases. */
    internal suspend fun retireDirectorySessions(context: Context, directoryKey: String) {
        clearDirectorySessionBaselines(directoryKey, sessions(context), baselines(context))
    }

    internal fun onFeatureDisabled(context: Context) {
        val store = sessions(context)
        // Snapshot before returning to the settings caller; off -> on cannot retarget this sweep.
        val observed = WebDavSettingsStore.disabledSessions(context, store)
        try {
            SaveSyncWorker.cancel(context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { /* Workers also check the persisted switch before any work. */ }
        scope.launch {
            try {
                discardDisabledSaveSessions(store, observed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { /* Entries remain inert while the master switch is off. */ }
        }
    }

    fun onForeground(context: Context) {
        if (processName(context) != context.packageName) return
        val priorBackground = leftUiSinceLastForeground
        leftUiSinceLastForeground = false
        val externalReturnSequence = externalDispatchSequence.get()
        val app = context.applicationContext
        scope.launch {
            try {
                synchronized(externalLaunchLock) {
                    if (priorBackground) externalLaunches.entries.removeAll { (_, entry) ->
                        entry.dispatchSequence in 1..externalReturnSequence
                    }
                }
                updateExternalLaunch(null) {
                    val liveTokens = synchronized(externalLaunchLock) { externalLaunches.keys.toSet() }
                    externalLaunchStore(app).foreground(
                        externalReturnSequence,
                        returned = priorBackground,
                        liveTokens = liveTokens,
                    )
                }
                if (!WebDavSettingsStore.isEnabled(app)) {
                    onFeatureDisabled(app)
                    return@launch
                }
                val store = sessions(app)
                val config = WebDavSettingsStore.load(app)
                if (!config.active || (!config.mode.pulls && !config.mode.uploads)) {
                    for (key in store.keys()) discardPending(app, store, key, store.load(key))
                    return@launch
                }
                val games = GameLibraryFacade.loadGames(app).associateBy { it.uri }
                for (key in store.keys()) {
                    val entry = store.load(key)
                    if (!entry.has("token") || !saveSessionGenerationMatches(entry, WebDavSettingsStore.sessionGeneration(app))) continue
                    val game = games[entry.optString("uri")]
                    val binding = entry.optString("activeKey")
                    if (game != null && binding.isNotBlank() && binding == activeKey(app, game)) {
                        markSaveSessionForeground(store, key, entry, binding,
                            game.engine, priorBackground, Process.myPid())
                    }
                    game?.let { monitor(app, it) }
                }
                if (config.active && (config.mode.pulls || config.mode.uploads)) {
                    SaveSyncWorker.enqueue(app, pull = config.mode.pulls)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                recordReport(SaveSyncReport(failures = listOf(SaveSyncFailure("", SaveSyncService.errorCode(failure)))))
            }
        }
    }

    fun onBackground(context: Context) {
        if (processName(context) != context.packageName) return
        leftUiSinceLastForeground = true
        if (!WebDavSettingsStore.isEnabled(context)) return
        scope.launch {
            try {
                val store = sessions(context)
                for (key in store.keys()) {
                    val entry = store.load(key)
                    val binding = entry.optString("activeKey")
                    if (entry.has("token") && saveSessionGenerationMatches(entry, WebDavSettingsStore.sessionGeneration(context)) && binding.isNotBlank() &&
                        binding == activeKey(context, entry.optString("uri"))) {
                        markSaveSessionBackground(store, key, entry, binding)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { /* Pending entries remain for a later opening. */ }
        }
    }

    private fun monitor(context: Context, game: ScanGame) {
        if (!monitors.add(game.uri)) return
        scope.launch {
            try {
                val store = sessions(context)
                val key = SaveContentHash.text(game.uri)
                while (store.load(key).has("token")) {
                    delay(1500)
                    if (!WebDavSettingsStore.isEnabled(context)) return@launch
                    if (!saveSessionGenerationMatches(store.load(key), WebDavSettingsStore.sessionGeneration(context))) return@launch
                    if (!isGameRunning(context, game)) {
                        SaveSyncWorker.enqueue(context, pull = false)
                        return@launch
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // WorkManager and the next foreground event retain the persisted fallback.
            } finally {
                monitors.remove(game.uri)
            }
        }
    }

    internal suspend fun flushPending(context: Context, excludedUris: Set<String> = emptySet()): SaveSyncReport {
        if (!WebDavSettingsStore.isEnabled(context)) return SaveSyncReport()
        val config = WebDavSettingsStore.load(context)
        val store = sessions(context)
        val games = GameLibraryFacade.loadGames(context).associateBy { it.uri }
        var report = SaveSyncReport()
        for (key in store.keys()) {
            var token = ""
            var game: ScanGame? = null
            try {
                val entry = store.load(key)
                if (!entry.has("token")) continue
                token = entry.getString("token")
                game = games[entry.optString("uri")]
                if (game == null || !config.active || !config.mode.uploads ||
                    !saveSessionGenerationMatches(entry, WebDavSettingsStore.sessionGeneration(context))) {
                    discardPending(context, store, key, entry, gameMissing = game == null)
                    continue
                }
                if (game.uri in excludedUris) continue
                val target = game
                val baseline = entry.optString("baseline")
                val manager = GameSaveManager(context)
                val directoryKey = entry.optString("activeKey").ifBlank { activeKey(context, target) }
                val decision = withCurrentSaveSession(store, key, entry, directoryKey,
                    resolveActiveKey = { activeKey(context, target) }, guardMutation = true) {
                    if (!saveSessionGenerationMatches(it, WebDavSettingsStore.sessionGeneration(context))) return@withCurrentSaveSession FlushDecision.StateChanged
                    directoryActivations(context).requireAvailable(directoryKey)
                    if (isGameRunning(context, target)) {
                        FlushDecision.Busy
                    } else {
                        val current = captureContentSnapshot(manager, target, directoryKey)
                        val changed = SaveSyncService(context).hasPendingPromotion(target, config) ||
                            (current.contentHash != null && current.contentHash != baseline)
                        if (changed) {
                            FlushDecision.Changed(current)
                        } else if (store.load(key).optString("token") != token) {
                            // A newer launch owns the exit upload now; stay silent instead of
                            // reporting a state change the user cannot act on.
                            FlushDecision.SessionReplaced
                        } else {
                            persistBaseline(context, target, current, directoryKey)
                            store.remove(key)
                            FlushDecision.Unchanged
                        }
                    }
                } ?: if (store.load(key).optString("token") != token) {
                    FlushDecision.SessionReplaced
                } else {
                    // The binding/directory changed but the session was not replaced; report it.
                    FlushDecision.StateChanged
                }
                when (decision) {
                    FlushDecision.Busy -> report += SaveSyncReport(busy = 1)
                    FlushDecision.SessionReplaced -> Unit
                    FlushDecision.StateChanged -> report += SaveSyncReport(failures = listOf(
                        SaveSyncFailure(target.title, SaveSyncError.LOCAL_STATE_CHANGED, target.uri)))
                    FlushDecision.Unchanged -> Unit
                    is FlushDecision.Changed -> {
                        val result = SaveSyncService(context).syncGameIfCurrent(target, SaveSyncDirection.UPLOAD,
                            expectedActiveKey = directoryKey, expectedContentHash = decision.snapshot.contentHash) {
                            val current = store.load(key)
                            if (current.optString("token") != token ||
                                !saveSessionGenerationMatches(current, WebDavSettingsStore.sessionGeneration(context))) false else {
                                requireSessionDirectory(current, activeKey(context, target))
                                directoryActivations(context).requireAvailable(directoryKey)
                                true
                            }
                        }
                        report += result
                        if (result.failures.isEmpty() && result.busy == 0 && result.cleanupPending == 0) {
                            var gameBecameBusy = false
                            var sessionReplaced = false
                            val committed = withCurrentSaveSession(store, key, entry, directoryKey,
                                resolveActiveKey = { activeKey(context, target) }, guardMutation = true) {
                                if (isGameRunning(context, target)) {
                                    gameBecameBusy = true
                                    false
                                } else if (store.load(key).optString("token") != token) {
                                    sessionReplaced = true
                                    false
                                } else {
                                    val final = captureContentSnapshot(manager, target, directoryKey)
                                    if (final.contentHash == decision.snapshot.contentHash) {
                                        persistBaseline(context, target, final, directoryKey)
                                        store.remove(key)
                                        true
                                    } else {
                                        false
                                    }
                                }
                            } ?: run {
                                sessionReplaced = store.load(key).optString("token") != token
                                false
                            }
                            if (!committed) {
                                if (sessionReplaced) Unit
                                else if (gameBecameBusy) report += SaveSyncReport(busy = 1)
                                else report += SaveSyncReport(
                                    failures = listOf(SaveSyncFailure(target.title, SaveSyncError.LOCAL_STATE_CHANGED, target.uri)))
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report += SaveSyncReport(failures = listOf(SaveSyncFailure(game?.title.orEmpty(), SaveSyncService.errorCode(failure), game?.uri.orEmpty())))
            }
        }
        return report
    }

    private suspend fun discardPending(
        context: Context,
        store: SaveSyncStateStore,
        key: String,
        entry: JSONObject,
        gameMissing: Boolean = false,
    ) = SaveSyncLocks.active(entry.optString("activeKey").ifBlank { activeKey(context, entry.optString("uri")) }) {
        SaveSyncLocks.session(key) {
            if (store.load(key).optString("token") == entry.optString("token")) {
                val current = WebDavSettingsStore.load(context)
                // A concurrent mode change and launch may already have replaced this ticket.
                if (gameMissing || !current.active || !current.mode.uploads ||
                    !saveSessionGenerationMatches(store.load(key), WebDavSettingsStore.sessionGeneration(context))) store.remove(key)
            }
        }
    }

    internal fun recordReport(report: SaveSyncReport) {
        SaveSyncFeedback.publish(report)
    }

    internal fun hasPending(context: Context): Boolean {
        if (!WebDavSettingsStore.isEnabled(context)) return false
        val store = sessions(context)
        val generation = WebDavSettingsStore.sessionGeneration(context)
        return store.keys().any { key -> store.load(key).let { it.has("token") && saveSessionGenerationMatches(it, generation) } }
    }

    internal fun isGameRunning(context: Context, game: ScanGame): Boolean {
        val manager = GameSaveManager(context)
        val library = GameLibraryFacade.loadGames(context).let { games ->
            if (games.any { it.uri == game.uri }) games else games + game
        }
        val related = relatedSaveUris(game, library) { manager.resolveSaveLocation(it).directory }
        val directoryKey = saveDirectoryIdentity(game) { manager.resolveSaveLocation(it).directory }
        val externalBusy = synchronized(externalLaunchLock) {
            externalLaunches.values.any {
                it.uri in related || it.activeKey.isNullOrBlank() || it.activeKey == directoryKey
            }
        }
        if (externalBusy) return true
        try {
            if (externalLaunchStore(context).hasActive(related, directoryKey)) return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Unreadable external-owner evidence cannot establish that Winlator has exited.
            return true
        }
        val external = library.filter { it.uri in related && it.engine == EngineType.YURIS }.mapTo(HashSet()) { it.uri }
        val generation = WebDavSettingsStore.sessionGeneration(context)
        val store = sessions(context)
        val entries = generationFilteredSessionEvidence(related, generation) { uri ->
            store.load(SaveContentHash.text(uri))
        }
        // External Winlator cannot expose a host-process registration callback.
        if (entries.any { (uri, entry) -> uri in external && entry.has("token") && !entry.optBoolean("returned") }) return true
        val nativeEntries = entries.filterKeys { it !in external }.values.filter { it.has("token") }
        val inGrace = related.any { launchGraceActive(it, generation) } || nativeEntries.any { entry -> entry.has("token") &&
            System.currentTimeMillis() - entry.optLong("startedAt") in 0..START_GRACE_MS }
        val declaredHosts = secondaryActivityProcessNames(context)
        val probe = object : SaveHostProbe {
            override fun visibleHosts(): List<SaveHostProcess>? {
                val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
                val processes = try { manager.runningAppProcesses } catch (_: Exception) { null }
                if (processes.isNullOrEmpty()) return null
                return processes.filter { process ->
                    process.uid == Process.myUid() && (process.processName.isNullOrBlank() ||
                        if (declaredHosts == null) process.processName.startsWith("${context.packageName}:")
                        else process.processName in declaredHosts)
                }.map { SaveHostProcess(it.pid, it.processName.orEmpty()) }
            }
            override fun records(): Map<String, JSONObject> {
                val store = runtimeStore(context)
                val known = declaredHosts?.map(SaveContentHash::text)?.toSet()
                return loadSaveHostRecords(store, known)
            }
            override fun identity(pid: Int): SaveHostIdentity {
                return try {
                    SaveHostIdentity.Live(readProcessName(File("/proc/$pid/cmdline")))
                } catch (_: java.io.FileNotFoundException) {
                    try {
                        android.system.Os.kill(pid, 0)
                        SaveHostIdentity.Unknown
                    } catch (failure: android.system.ErrnoException) {
                        if (failure.errno == android.system.OsConstants.ESRCH) SaveHostIdentity.Dead else SaveHostIdentity.Unknown
                    } catch (_: Exception) { SaveHostIdentity.Unknown }
                } catch (_: Exception) { SaveHostIdentity.Unknown }
            }
        }
        // An explicit restore creates bookkeeping without launching a host. Missing host evidence
        // must still block real launches, but cannot turn that restore into a permanent busy loop.
        val needsHostEvidence = nativeEntries.any { entry -> sessionRequiresHostEvidence(entry.has("token"),
            entry.optLong("startedAt", -1L), entry.optBoolean("returned")) }
        return hasRunningSaveHost(probe, game.uri, needsHostEvidence, inGrace, SystemClock.elapsedRealtime(), related)
    }

    internal fun launchAdmission(context: Context, game: ScanGame): SaveSyncLaunchAdmission {
        if (!supportsManagedSave(game.engine)) return SaveSyncLaunchAdmission.Unmanaged
        return optionalSaveSyncLaunchAdmission({ WebDavSettingsStore.isEnabled(context) }) {
            GameSaveManager(context).resolveSaveLocation(game).directory?.canonicalPath?.let { SAVE_DIRECTORY_KEY_PREFIX + it }
        }
    }

    /**
     * Start hint for every managed launch. Always returns a token, even when optional persistence
     * fails; the caller must keep the in-memory protection and only clear it on a failed launch.
     */
    internal fun prepareOptionalLaunchGrace(context: Context, game: ScanGame, directoryKey: String? = null): String {
        val token = UUID.randomUUID().toString()
        if (game.engine == EngineType.YURIS) {
            synchronized(externalLaunchLock) {
                externalLaunches[token] = ExternalSaveLaunch(game.uri, token, directoryKey, externalLaunchOwner)
            }
            try {
                updateExternalLaunch(game) { externalLaunchStore(context).prepare(game.uri, token, directoryKey) }
            } catch (cancelled: CancellationException) {
                synchronized(externalLaunchLock) { externalLaunches.remove(token) }
                try { externalLaunchStore(context).failed(game.uri, token) }
                catch (cleanup: Exception) { if (cleanup !== cancelled) cancelled.addSuppressed(cleanup) }
                throw cancelled
            }
            return token
        }
        val generation = try {
            WebDavSettingsStore.sessionGeneration(context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A failed optional prefs read cannot remove protection from an actual start.
            Long.MAX_VALUE
        }
        markNativeLaunchGrace(game.uri, generation, token)
        return token
    }

    internal fun optionalLaunchDispatched(context: Context, game: ScanGame, token: String?, directoryKey: String?) {
        if (token == null) return
        if (game.engine == EngineType.YURIS) {
            val sequence = externalDispatchSequence.incrementAndGet()
            synchronized(externalLaunchLock) {
                externalLaunches[token] = ExternalSaveLaunch(game.uri, token, directoryKey, externalLaunchOwner, sequence)
            }
            val persisted = try {
                externalLaunchStore(context).dispatched(game.uri, token, directoryKey, sequence)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                recordReport(SaveSyncReport(failures = listOf(
                    SaveSyncFailure(game.title, SaveSyncService.errorCode(failure), game.uri))))
                false
            }
            if (!persisted) {
                // The in-memory entry keeps the launch protected. Remove the sequence-zero marker
                // so a later foreground cannot interpret it as a permanently running host.
                try { externalLaunchStore(context).failed(game.uri, token) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Foreground retires a stale sequence-zero marker by token. */ }
            }
        } else {
            val generation = try {
                WebDavSettingsStore.sessionGeneration(context)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Long.MAX_VALUE }
            markNativeLaunchGrace(game.uri, generation, token)
        }
    }

    internal fun finishOptionalLaunchGrace(context: Context, game: ScanGame, token: String?, success: Boolean) {
        if (success || token == null) return
        nativeLaunchGraces.clear(game.uri, token)
        if (game.engine == EngineType.YURIS) {
            synchronized(externalLaunchLock) { externalLaunches.remove(token) }
            updateExternalLaunch(game) { externalLaunchStore(context).failed(game.uri, token) }
        }
    }

    /** Game deletion must be able to release its own external launch evidence. */
    internal fun clearExternalLaunchEvidence(context: Context, game: ScanGame) {
        synchronized(externalLaunchLock) {
            externalLaunches.entries.removeAll { it.value.uri == game.uri }
        }
        updateExternalLaunch(game) { externalLaunchStore(context).remove(game.uri) }
    }

    private fun updateExternalLaunch(game: ScanGame?, action: () -> Unit) {
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            recordReport(SaveSyncReport(failures = listOf(
                SaveSyncFailure(game?.title.orEmpty(), SaveSyncService.errorCode(failure), game?.uri.orEmpty()))))
        }
    }

    private fun externalLaunchStore(context: Context) =
        ExternalSaveLaunchStore(context.noBackupFilesDir, externalLaunchOwner)

    internal fun activeKey(context: Context, game: ScanGame): String = saveDirectoryIdentity(game) {
        GameSaveManager(context).resolveSaveLocation(it).directory
    }

    private fun activeKey(context: Context, uri: String): String =
        GameLibraryFacade.loadGames(context).firstOrNull { it.uri == uri }?.let { activeKey(context, it) } ?: uri

    private fun secondaryActivityProcessNames(context: Context): Set<String>? {
        cachedSecondaryActivityProcessNames?.let { return it }
        return synchronized(secondaryActivityProcessNamesLock) {
            cachedSecondaryActivityProcessNames?.let { return@synchronized it }
            val appContext = context.applicationContext
            val packageInfo = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appContext.packageManager.getPackageInfo(
                        appContext.packageName,
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    appContext.packageManager.getPackageInfo(appContext.packageName, PackageManager.GET_ACTIVITIES)
                }
            } catch (_: Exception) {
                return@synchronized null
            }
            val activities = packageInfo.activities ?: return@synchronized null
            activities.map { normalizeActivityProcessName(appContext.packageName, it.processName) }
                .filter { it.startsWith("${appContext.packageName}:") }
                .toSet()
                .also { cachedSecondaryActivityProcessNames = it }
        }
    }

    private fun markNativeLaunchGrace(uri: String, generation: Long, token: String) {
        nativeLaunchGraces.mark(uri, generation, token, SystemClock.elapsedRealtime(), START_GRACE_MS)
    }

    private fun launchGraceActive(uri: String, generation: Long): Boolean =
        nativeLaunchGraces.active(uri, generation, SystemClock.elapsedRealtime())

    private fun clearNativeLaunchGrace(uri: String, ticket: LaunchTicket) {
        nativeLaunchGraces.clear(uri, ticket.token, ticket.generation)
    }

    private fun loadCachedBaseline(store: SaveSyncStateStore, key: String, uri: String, activeKey: String): CachedBaseline? {
        val state = store.load(key)
        if (state.length() == 0) return null
        val contentHash = state.opt("contentHash") as? String ?: return null
        val metadataHash = state.opt("metadataHash") as? String ?: return null
        if (state.opt("uri") != uri || state.optString("activeKey") != activeKey ||
            !HASH.matches(contentHash) ||
            !HASH.matches(metadataHash)) {
            return null
        }
        return CachedBaseline(contentHash, metadataHash)
    }

    private suspend fun resolveBaselineSnapshot(
        manager: GameSaveManager,
        game: ScanGame,
        cached: CachedBaseline?,
        activeKey: String,
    ): SaveTreeSnapshot {
        val observed = observeSaveMetadata(manager, game, activeKey)
        if (cached?.metadataHash == observed.metadataHash) {
            return SaveTreeSnapshot(cached.contentHash, observed.metadataHash)
        }
        return hashStableContent(manager, game, observed, activeKey)
    }

    private suspend fun captureContentSnapshot(
        manager: GameSaveManager,
        game: ScanGame,
        activeKey: String,
    ): SaveTreeSnapshot = hashStableContent(manager, game, observeSaveMetadata(manager, game, activeKey), activeKey)

    private suspend fun observeSaveMetadata(
        manager: GameSaveManager,
        game: ScanGame,
        activeKey: String,
    ): SaveMetadataObservation {
        val location = manager.resolveSaveLocation(game)
        if (!location.available || location.directory == null) {
            throw SaveSyncException(SaveSyncError.SAVE_UNAVAILABLE)
        }
        val root = manager.syncSaveDirectory(game)
        requireSameSaveDirectory(activeKey, saveDirectoryIdentity(game) { root })
        val files = manager.listSaveFiles(game, strict = true, expectedDirectory = root)
        val job = currentCoroutineContext()
        val metadataHash = SaveSyncMetadataFingerprint.fingerprint(root, files) { job.ensureActive() }
        return SaveMetadataObservation(root, files, metadataHash)
    }

    private suspend fun hashStableContent(
        manager: GameSaveManager,
        game: ScanGame,
        observed: SaveMetadataObservation,
        activeKey: String,
    ): SaveTreeSnapshot {
        val job = currentCoroutineContext()
        val contentHash = SaveContentHash.files(observed.root, observed.files) { job.ensureActive() }
        val afterFiles = manager.listSaveFiles(game, strict = true, expectedDirectory = observed.root)
        val afterMetadata = SaveSyncMetadataFingerprint.fingerprint(observed.root, afterFiles) {
            job.ensureActive()
        }
        if (afterMetadata != observed.metadataHash) {
            // A cache entry is only authoritative for one stable tree state.
            throw SaveSyncException(SaveSyncError.LOCAL_IO)
        }
        requireSameSaveDirectory(activeKey, saveDirectoryIdentity(game) {
            manager.resolveSaveLocation(it).directory
        })
        return SaveTreeSnapshot(contentHash, observed.metadataHash)
    }

    private fun persistBaseline(context: Context, game: ScanGame, snapshot: SaveTreeSnapshot, directoryKey: String) {
        requireSameSaveDirectory(directoryKey, activeKey(context, game))
        val store = baselines(context)
        val key = SaveContentHash.text(game.uri)
        val contentHash = snapshot.contentHash
        if (contentHash.isNullOrBlank()) {
            store.remove(key)
            return
        }
        if (!HASH.matches(contentHash) || !HASH.matches(snapshot.metadataHash)) {
            throw SaveSyncException(SaveSyncError.LOCAL_IO)
        }
        store.save(key, JSONObject()
            .put("version", 1)
            .put("uri", game.uri)
            .put("activeKey", directoryKey)
            .put("contentHash", contentHash)
            .put("metadataHash", snapshot.metadataHash))
    }

    private fun sessions(context: Context) = SaveSyncStateStore(File(context.noBackupFilesDir, "save_sync/sessions"), recoverCorrupt = true)
    private fun directoryActivations(context: Context) =
        SaveDirectoryActivationStore(File(context.noBackupFilesDir, "save_sync"))
    private fun baselines(context: Context) =
        SaveSyncStateStore(File(context.noBackupFilesDir, "save_sync/baselines"), recoverCorrupt = true)
    private fun runtimeStore(context: Context) = SaveSyncStateStore(File(context.noBackupFilesDir, "save_sync/hosts"))
    private fun processName(context: Context): String = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else {
        try {
            readProcessName(File("/proc/self/cmdline"))
        } catch (_: Exception) {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            manager?.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName.orEmpty()
        }
    }

    private fun readProcessName(file: File): String = file.inputStream().buffered().use { input ->
        val bytes = java.io.ByteArrayOutputStream()
        repeat(4096) {
            val byte = input.read()
            if (byte == 0 && bytes.size() > 0) return@use bytes.toString("UTF-8")
            if (byte <= 0) throw java.io.IOException("Incomplete process identity")
            bytes.write(byte)
        }
        throw java.io.IOException("Oversized process identity")
    }
    private val HASH = Regex("[a-f0-9]{64}")
    private const val START_GRACE_MS = 15_000L
    private const val MAX_LAUNCH_GRACE_ENTRIES = 32
}

internal data class HostActivityUriSnapshot(val activities: Int, val uris: List<String>)

/** Empty is a real pre-save baseline; null means there is no pending session to inherit. */
internal fun inheritedSessionBaseline(previous: JSONObject?): String? =
    previous?.takeIf { it.has("token") && it.has("baseline") }?.getString("baseline")

internal suspend fun clearDirectorySessionBaselines(
    directoryKey: String,
    sessions: SaveSyncStateStore,
    baselines: SaveSyncStateStore,
) {
    for (key in (sessions.keys() + baselines.keys()).distinct()) {
        SaveSyncLocks.session(key) {
            if (sessions.load(key).optString("activeKey") == directoryKey) sessions.remove(key)
            if (baselines.load(key).optString("activeKey") == directoryKey) baselines.remove(key)
        }
    }
}

internal fun sessionRequiresHostEvidence(hasToken: Boolean, startedAt: Long, returned: Boolean): Boolean =
    hasToken && !(startedAt == 0L && returned)

/** Tracks one URI per Activity instance so a destroyed screen cannot leave a stale host reference. */
internal class HostActivityUriRegistry<T : Any> {
    private val lock = Any()
    private val activityUris = HashMap<T, String?>()
    private val uriReferences = HashMap<String, Int>()

    fun register(activity: T, uri: String?) {
        synchronized(lock) {
            activityUris.put(activity, uri)?.let(::releaseLocked)
            if (uri != null) uriReferences[uri] = (uriReferences[uri] ?: 0) + 1
        }
    }

    fun unregister(activity: T) {
        synchronized(lock) {
            activityUris.remove(activity)?.let(::releaseLocked)
        }
    }

    fun snapshot(): HostActivityUriSnapshot = synchronized(lock) {
        HostActivityUriSnapshot(activityUris.size,
            if (activityUris.values.any { it == null }) emptyList() else uriReferences.keys.sorted())
    }

    fun activityCount(): Int = synchronized(lock) { activityUris.size }

    private fun releaseLocked(uri: String) {
        val remaining = (uriReferences[uri] ?: 1) - 1
        if (remaining <= 0) uriReferences.remove(uri) else uriReferences[uri] = remaining
    }
}

internal enum class HostRecordRelation { MATCHING, UNRELATED, UNKNOWN }

internal fun classifyHostRecord(
    livePid: Int,
    recordedPid: Int,
    recordedActivities: Int,
    recordedUris: List<String>?,
    gameUri: String,
    idleForMillis: Long = 2_000L,
): HostRecordRelation {
    if (livePid <= 0 || recordedPid != livePid || recordedActivities < 0 || gameUri.isBlank()) {
        return HostRecordRelation.UNKNOWN
    }
    if (recordedActivities == 0) return if (idleForMillis >= 1_500L) HostRecordRelation.UNRELATED else HostRecordRelation.UNKNOWN
    if (recordedUris.isNullOrEmpty()) return HostRecordRelation.UNKNOWN
    return if (gameUri in recordedUris) HostRecordRelation.MATCHING else HostRecordRelation.UNRELATED
}

internal fun shouldMarkExternalYurisReturned(
    engine: EngineType,
    priorBackground: Boolean,
    leftApp: Boolean,
    recordedOwnerPid: Int,
    currentOwnerPid: Int,
): Boolean = engine == EngineType.YURIS &&
    (priorBackground || leftApp || recordedOwnerPid != currentOwnerPid)

private fun normalizeActivityProcessName(packageName: String, declared: String): String = when {
    declared.startsWith(":") -> packageName + declared
    '.' !in declared -> "$packageName.$declared"
    else -> declared
}
