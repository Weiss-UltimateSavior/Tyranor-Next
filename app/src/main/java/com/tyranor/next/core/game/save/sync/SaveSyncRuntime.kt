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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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
    private val nativeLaunchGraceLock = Any()
    private val nativeLaunchGraceUntil = HashMap<String, Long>()
    private val secondaryActivityProcessNamesLock = Any()
    @Volatile private var cachedSecondaryActivityProcessNames: Set<String>? = null
    @Volatile private var leftUiSinceLastForeground = false

    internal data class LaunchTicket(val token: String, val previous: JSONObject?)
    private data class CachedBaseline(val contentHash: String, val metadataHash: String)
    private data class SaveMetadataObservation(val root: File, val files: List<File>, val metadataHash: String)
    private data class SaveTreeSnapshot(val contentHash: String?, val metadataHash: String)
    private sealed interface FlushDecision {
        data object Busy : FlushDecision
        data object Unchanged : FlushDecision
        data class Changed(val snapshot: SaveTreeSnapshot) : FlushDecision
    }

    fun install(application: Application) {
        val process = processName(application)
        if (process == application.packageName || !process.startsWith(application.packageName + ":")) return
        val hostActivities = HostActivityUriRegistry<Activity>()
        val hostWrite = Mutex()
        val runtime = runtimeStore(application)
        val key = SaveContentHash.text(process)
        fun registerActivity(activity: Activity) {
            val uri = activity.intent
                ?.getStringExtra(LaunchContract.ROOT_URI)
                ?.takeIf { it.isNotBlank() }
            hostActivities.register(activity, uri)
        }
        fun record() {
            scope.launch {
                hostWrite.withLock {
                    try {
                        val snapshot = hostActivities.snapshot()
                        runtime.save(key, JSONObject()
                            .put("pid", Process.myPid())
                            .put("activities", snapshot.activities)
                            .put("uris", JSONArray(snapshot.uris)))
                    } catch (_: Exception) {
                        // An unreadable/missing record is treated as busy by the reader.
                    }
                }
            }
        }
        record()
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                registerActivity(activity)
                record()
            }
            override fun onActivityDestroyed(activity: Activity) {
                hostActivities.unregister(activity)
                record()
                if (hostActivities.activityCount() == 0 && !activity.isChangingConfigurations) {
                    application.sendBroadcast(Intent(application, SaveSyncExitReceiver::class.java))
                }
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        })
    }

    /** Called under the same active-save mutex as launch and explicit version selection. */
    internal suspend fun prepareLaunch(context: Context, game: ScanGame): LaunchTicket? {
        try {
            if (!WebDavSettingsStore.isEnabled(context)) return null
            if (!GameSaveManager(context).resolveSaveLocation(game).available) return null
            if (isGameRunning(context, game)) return null
            // External YURIS has no in-package host handoff; its persisted returned-state path remains authoritative.
            if (game.engine != EngineType.YURIS) markNativeLaunchGrace(game.uri)
            val config = WebDavSettingsStore.load(context)
            if (!config.active || !config.mode.uploads) return null
            val store = sessions(context)
            val key = SaveContentHash.text(game.uri)
            val previous = store.load(key).takeIf { it.has("token") }
            val previousBaseline = previous?.optString("baseline").orEmpty()
            var baselineMetadata = previous?.optString("baselineMetadata").orEmpty()
            val cached = if (previousBaseline.isBlank() || baselineMetadata.isBlank()) {
                loadCachedBaseline(baselines(context), key, game.uri)
            } else null
            val baseline = if (previousBaseline.isNotBlank()) {
                previousBaseline
            } else {
                val manager = GameSaveManager(context)
                val snapshot = resolveBaselineSnapshot(manager, game, cached)
                if (cached == null ||
                    cached.contentHash != snapshot.contentHash ||
                    cached.metadataHash != snapshot.metadataHash) {
                    persistBaseline(context, game, snapshot)
                }
                baselineMetadata = snapshot.metadataHash
                snapshot.contentHash.orEmpty()
            }
            if (baselineMetadata.isBlank() && previousBaseline.isNotBlank()) {
                baselineMetadata = cached?.takeIf { it.contentHash == previousBaseline }?.metadataHash.orEmpty()
            }
            val token = UUID.randomUUID().toString()
            store.save(key, JSONObject().put("uri", game.uri).put("baseline", baseline)
                .put("baselineMetadata", baselineMetadata)
                .put("token", token).put("startedAt", System.currentTimeMillis()).put("ownerPid", Process.myPid()))
            return LaunchTicket(token, previous)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            recordReport(SaveSyncReport(failures = listOf(SaveSyncFailure(game.title, SaveSyncService.errorCode(failure)))))
            return null
        }
    }

    internal fun finishLaunch(context: Context, game: ScanGame, ticket: LaunchTicket?, success: Boolean) {
        if (!success) clearNativeLaunchGrace(game.uri)
        if (ticket == null) return
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
    internal fun recordRestoredVersion(context: Context, game: ScanGame, hash: String): Boolean {
        try {
            if (!WebDavSettingsStore.isEnabled(context)) return true
            val config = WebDavSettingsStore.load(context)
            if (!config.active || !config.mode.uploads) return true
            sessions(context).save(SaveContentHash.text(game.uri), JSONObject()
                .put("uri", game.uri).put("baseline", hash).put("token", UUID.randomUUID().toString())
                .put("startedAt", 0L).put("returned", true))
            SaveSyncWorker.enqueue(context, pull = false)
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // The restored archive and promotion checkpoint remain available for the next retry.
            recordReport(SaveSyncReport(failures = listOf(SaveSyncFailure(game.title, SaveSyncService.errorCode(failure)))))
            return false
        }
    }

    internal fun onFeatureDisabled(context: Context) {
        try {
            SaveSyncWorker.cancel(context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { /* Workers also check the persisted switch before any work. */ }
        scope.launch {
            try {
                val store = sessions(context)
                for (key in store.keys()) {
                    val uri = store.load(key).optString("uri")
                    SaveSyncLocks.active(uri) {
                        if (!WebDavSettingsStore.isEnabled(context)) store.remove(key)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { /* Entries remain inert while the master switch is off. */ }
        }
    }

    fun onForeground(context: Context) {
        if (processName(context) != context.packageName) return
        val priorBackground = leftUiSinceLastForeground
        leftUiSinceLastForeground = false
        val app = context.applicationContext
        scope.launch {
            try {
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
                    if (!entry.has("token")) continue
                    val game = games[entry.optString("uri")]
                    if (game != null && shouldMarkExternalYurisReturned(
                            engine = game.engine,
                            priorBackground = priorBackground,
                            leftApp = entry.optBoolean("leftApp"),
                            recordedOwnerPid = entry.optInt("ownerPid", -1),
                            currentOwnerPid = Process.myPid(),
                        )) {
                        SaveSyncLocks.active(entry.optString("uri")) {
                            val current = store.load(key)
                            if (current.has("token")) store.save(key, current.put("returned", true))
                        }
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
                    if (entry.has("token")) SaveSyncLocks.active(entry.optString("uri")) {
                        val current = store.load(key)
                        if (current.has("token")) store.save(key, current.put("leftApp", true))
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

    internal suspend fun flushPending(context: Context): SaveSyncReport {
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
                if (game == null || !config.active || !config.mode.uploads) {
                    discardPending(context, store, key, entry, gameMissing = game == null)
                    continue
                }
                val target = game
                val baseline = entry.optString("baseline")
                val manager = GameSaveManager(context)
                val decision = SaveSyncLocks.active(target.uri) {
                    if (isGameRunning(context, target)) {
                        FlushDecision.Busy
                    } else {
                        val current = captureContentSnapshot(manager, target)
                        val changed = current.contentHash != null &&
                            (current.contentHash != baseline ||
                                SaveSyncService(context).hasPendingPromotion(target, config))
                        if (changed) {
                            FlushDecision.Changed(current)
                        } else if (store.load(key).optString("token") != token) {
                            FlushDecision.Busy
                        } else {
                            persistBaseline(context, target, current)
                            store.remove(key)
                            FlushDecision.Unchanged
                        }
                    }
                }
                when (decision) {
                    FlushDecision.Busy -> report += SaveSyncReport(busy = 1)
                    FlushDecision.Unchanged -> Unit
                    is FlushDecision.Changed -> {
                        val result = SaveSyncService(context).syncGame(target, SaveSyncDirection.UPLOAD)
                        report += result
                        if (result.failures.isEmpty() && result.busy == 0) {
                            val committed = SaveSyncLocks.active(target.uri) {
                                if (isGameRunning(context, target) ||
                                    store.load(key).optString("token") != token) {
                                    false
                                } else {
                                    val final = captureContentSnapshot(manager, target)
                                    if (final.contentHash == decision.snapshot.contentHash) {
                                        persistBaseline(context, target, final)
                                        store.remove(key)
                                        true
                                    } else {
                                        false
                                    }
                                }
                            }
                            if (!committed) report += SaveSyncReport(busy = 1)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report += SaveSyncReport(failures = listOf(SaveSyncFailure(game?.title.orEmpty(), SaveSyncService.errorCode(failure))))
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
    ) = SaveSyncLocks.active(entry.optString("uri")) {
        if (store.load(key).optString("token") == entry.optString("token")) {
            val current = WebDavSettingsStore.load(context)
            // A concurrent mode change and launch may already have replaced this ticket.
            if (gameMissing || !current.active || !current.mode.uploads) store.remove(key)
        }
    }

    internal fun recordReport(report: SaveSyncReport) {
        SaveSyncFeedback.publish(report)
    }

    internal fun hasPending(context: Context): Boolean {
        if (!WebDavSettingsStore.isEnabled(context)) return false
        val store = sessions(context)
        return store.keys().any { store.load(it).has("token") }
    }

    internal fun isGameRunning(context: Context, game: ScanGame): Boolean {
        val entry = sessions(context).load(SaveContentHash.text(game.uri))
        // External Winlator cannot expose a host-process registration callback.
        if (game.engine == EngineType.YURIS) return entry.has("token") && !entry.optBoolean("returned")
        if (launchGraceActive(game.uri)) return true
        val started = entry.optLong("startedAt")
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return true
        val processes = try {
            manager.runningAppProcesses
        } catch (_: Exception) {
            null
        } ?: return true

        val declaredHosts = secondaryActivityProcessNames(context)
        val liveHosts = processes.filter { process ->
            if (process.uid != Process.myUid()) return@filter false
            val name = process.processName.orEmpty()
            name.isBlank() || if (declaredHosts == null) {
                name.startsWith("${context.packageName}:")
            } else {
                name in declaredHosts
            }
        }
        if (liveHosts.any { process -> hostRecordRelation(context, process, game.uri) != HostRecordRelation.UNRELATED }) {
            return true
        }
        // Cover startActivity -> host registration without ever touching a running save directory.
        return entry.has("token") && System.currentTimeMillis() - started in 0..START_GRACE_MS
    }

    private fun hostRecordRelation(
        context: Context,
        process: ActivityManager.RunningAppProcessInfo,
        gameUri: String,
    ): HostRecordRelation {
        val processName = process.processName.orEmpty()
        if (processName.isBlank()) return HostRecordRelation.UNKNOWN
        val state = try {
            runtimeStore(context).load(SaveContentHash.text(processName))
        } catch (_: Exception) {
            return HostRecordRelation.UNKNOWN
        }
        val array = state.optJSONArray("uris") ?: return HostRecordRelation.UNKNOWN
        val uris = ArrayList<String>(array.length())
        for (index in 0 until array.length()) {
            val uri = array.opt(index) as? String ?: return HostRecordRelation.UNKNOWN
            if (uri.isBlank()) return HostRecordRelation.UNKNOWN
            uris += uri
        }
        return classifyHostRecord(
            livePid = process.pid,
            recordedPid = state.optInt("pid", -1),
            recordedActivities = state.optInt("activities", -1),
            recordedUris = uris,
            gameUri = gameUri,
        )
    }

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

    private fun markNativeLaunchGrace(uri: String) = synchronized(nativeLaunchGraceLock) {
        val now = SystemClock.elapsedRealtime()
        val iterator = nativeLaunchGraceUntil.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value <= now) iterator.remove()
        }
        nativeLaunchGraceUntil[uri] = now + START_GRACE_MS
        while (nativeLaunchGraceUntil.size > MAX_LAUNCH_GRACE_ENTRIES) {
            val oldest = nativeLaunchGraceUntil.minByOrNull { it.value } ?: break
            nativeLaunchGraceUntil.remove(oldest.key)
        }
    }

    private fun launchGraceActive(uri: String): Boolean {
        return synchronized(nativeLaunchGraceLock) {
            val until = nativeLaunchGraceUntil[uri]
            when {
                until == null -> false
                until > SystemClock.elapsedRealtime() -> true
                else -> {
                    nativeLaunchGraceUntil.remove(uri)
                    false
                }
            }
        }
    }

    private fun clearNativeLaunchGrace(uri: String) {
        synchronized(nativeLaunchGraceLock) {
            nativeLaunchGraceUntil.remove(uri)
        }
    }

    private fun loadCachedBaseline(store: SaveSyncStateStore, key: String, uri: String): CachedBaseline? {
        val state = store.load(key)
        if (state.length() == 0) return null
        val contentHash = state.opt("contentHash") as? String ?: return null
        val metadataHash = state.opt("metadataHash") as? String ?: return null
        if (state.opt("uri") != uri ||
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
    ): SaveTreeSnapshot {
        val observed = observeSaveMetadata(manager, game)
        if (cached?.metadataHash == observed.metadataHash) {
            return SaveTreeSnapshot(cached.contentHash, observed.metadataHash)
        }
        return hashStableContent(manager, game, observed)
    }

    private suspend fun captureContentSnapshot(
        manager: GameSaveManager,
        game: ScanGame,
    ): SaveTreeSnapshot = hashStableContent(manager, game, observeSaveMetadata(manager, game))

    private suspend fun observeSaveMetadata(
        manager: GameSaveManager,
        game: ScanGame,
    ): SaveMetadataObservation {
        val location = manager.resolveSaveLocation(game)
        if (!location.available || location.directory == null) {
            throw SaveSyncException(SaveSyncError.SAVE_UNAVAILABLE)
        }
        val root = manager.syncSaveDirectory(game)
        val files = manager.listSaveFiles(game, strict = true)
        val job = currentCoroutineContext()
        val metadataHash = SaveSyncMetadataFingerprint.fingerprint(root, files) { job.ensureActive() }
        return SaveMetadataObservation(root, files, metadataHash)
    }

    private suspend fun hashStableContent(
        manager: GameSaveManager,
        game: ScanGame,
        observed: SaveMetadataObservation,
    ): SaveTreeSnapshot {
        val job = currentCoroutineContext()
        val contentHash = SaveContentHash.files(observed.root, observed.files) { job.ensureActive() }
        val afterFiles = manager.listSaveFiles(game, strict = true)
        val afterMetadata = SaveSyncMetadataFingerprint.fingerprint(observed.root, afterFiles) {
            job.ensureActive()
        }
        if (afterMetadata != observed.metadataHash) {
            // A cache entry is only authoritative for one stable tree state.
            throw SaveSyncException(SaveSyncError.LOCAL_IO)
        }
        return SaveTreeSnapshot(contentHash, observed.metadataHash)
    }

    private fun persistBaseline(context: Context, game: ScanGame, snapshot: SaveTreeSnapshot) {
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
            .put("contentHash", contentHash)
            .put("metadataHash", snapshot.metadataHash))
    }

    private fun sessions(context: Context) = SaveSyncStateStore(File(context.noBackupFilesDir, "save_sync/sessions"))
    private fun baselines(context: Context) =
        SaveSyncStateStore(File(context.noBackupFilesDir, "save_sync/baselines"))
    private fun runtimeStore(context: Context) = SaveSyncStateStore(File(context.noBackupFilesDir, "save_sync/hosts"))
    private fun processName(context: Context): String = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        manager?.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName.orEmpty()
    }
    private val HASH = Regex("[a-f0-9]{64}")
    private const val START_GRACE_MS = 15_000L
    private const val MAX_LAUNCH_GRACE_ENTRIES = 32
}

internal data class HostActivityUriSnapshot(val activities: Int, val uris: List<String>)

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
): HostRecordRelation {
    if (livePid <= 0 || recordedPid != livePid || recordedActivities <= 0 ||
        recordedUris.isNullOrEmpty() || gameUri.isBlank()) {
        return HostRecordRelation.UNKNOWN
    }
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
