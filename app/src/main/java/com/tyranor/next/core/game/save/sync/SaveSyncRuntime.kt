package com.tyranor.next.core.game.save.sync

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.save.GameSaveManager
import com.tyranor.next.core.game.storage.GameLibraryFacade
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** App-side lifecycle adapter. Engine runtimes and their native save writers remain unchanged. */
object SaveSyncRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val monitors = ConcurrentHashMap.newKeySet<String>()
    private val status = MutableStateFlow<SaveSyncReport?>(null)
    val lastAutomaticReport = status.asStateFlow()

    internal data class LaunchTicket(val token: String, val previous: JSONObject?)

    fun install(application: Application) {
        val process = processName(application)
        if (process == application.packageName || !process.startsWith(application.packageName + ":")) return
        val activities = AtomicInteger(0)
        val hostWrite = Mutex()
        val runtime = runtimeStore(application)
        val key = SaveContentHash.text(process)
        fun record() {
            scope.launch {
                hostWrite.withLock {
                    try {
                        runtime.save(key, JSONObject().put("pid", Process.myPid()).put("activities", activities.get()))
                    } catch (_: Exception) {
                        // An unreadable/missing record is treated as busy by the reader.
                    }
                }
            }
        }
        record()
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) { activities.incrementAndGet(); record() }
            override fun onActivityDestroyed(activity: Activity) {
                activities.updateAndGet { (it - 1).coerceAtLeast(0) }
                record()
                if (activities.get() == 0 && !activity.isChangingConfigurations) {
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
            val config = WebDavSettingsStore.load(context)
            if (!config.active || !config.mode.uploads || !GameSaveManager(context).resolveSaveLocation(game).available) return null
            if (isGameRunning(context, game)) return null
            val store = sessions(context)
            val key = SaveContentHash.text(game.uri)
            val previous = store.load(key).takeIf { it.has("token") }
            val baseline = previous?.optString("baseline") ?: SaveSyncService(context).fingerprint(game).orEmpty()
            val token = UUID.randomUUID().toString()
            store.save(key, JSONObject().put("uri", game.uri).put("baseline", baseline)
                .put("token", token).put("startedAt", System.currentTimeMillis()))
            return LaunchTicket(token, previous)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            recordReport(SaveSyncReport(failures = listOf(SaveSyncFailure(game.title, SaveSyncService.errorCode(failure)))))
            return null
        }
    }

    internal fun finishLaunch(context: Context, game: ScanGame, ticket: LaunchTicket?, success: Boolean) {
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

    /** An explicit restore is one pending change even if the next game session writes no bytes. */
    internal fun recordRestoredVersion(context: Context, game: ScanGame, hash: String) {
        try {
            if (!WebDavSettingsStore.isEnabled(context)) return
            val config = WebDavSettingsStore.load(context)
            if (!config.active || !config.mode.uploads) return
            sessions(context).save(SaveContentHash.text(game.uri), JSONObject()
                .put("uri", game.uri).put("baseline", hash).put("token", UUID.randomUUID().toString())
                .put("startedAt", 0L).put("returned", true))
            SaveSyncWorker.enqueue(context, pull = false)
        } catch (failure: Exception) {
            // The restored archive and promotion checkpoint remain available for the next retry.
            recordReport(SaveSyncReport(failures = listOf(SaveSyncFailure(game.title, SaveSyncService.errorCode(failure)))))
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
                    // External Winlator has no host callback; returning to TYN is its fallback.
                    if (entry.optBoolean("leftApp")) {
                        SaveSyncLocks.active(entry.optString("uri")) {
                            val current = store.load(key)
                            if (current.has("token")) store.save(key, current.put("returned", true))
                        }
                    }
                    games[entry.optString("uri")]?.let { monitor(app, it) }
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
                val changed = SaveSyncLocks.active(target.uri) {
                    if (isGameRunning(context, target)) null
                    else {
                        val service = SaveSyncService(context)
                        val current = service.fingerprint(target).orEmpty()
                        current.isNotBlank() && (current != entry.optString("baseline") || service.hasPendingPromotion(target, config))
                    }
                }
                if (changed == null) {
                    report += SaveSyncReport(busy = 1)
                    continue
                }
                val result = if (changed) SaveSyncService(context).syncGame(target, SaveSyncDirection.UPLOAD)
                    else SaveSyncReport()
                report += result
                if (result.failures.isEmpty() && result.busy == 0) {
                    SaveSyncLocks.active(target.uri) {
                        if (store.load(key).optString("token") == token) store.remove(key)
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
        status.value = report
        SaveSyncFeedback.publish(report)
    }

    internal fun hasPending(context: Context): Boolean {
        if (!WebDavSettingsStore.isEnabled(context)) return false
        val store = sessions(context)
        return store.keys().any { store.load(it).has("token") }
    }

    internal fun isGameRunning(context: Context, game: ScanGame): Boolean {
        val entry = sessions(context).load(SaveContentHash.text(game.uri))
        val started = entry.optLong("startedAt")
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return true
        val processes = manager.runningAppProcesses ?: return true
        val suffixes = when (game.engine) {
            EngineType.KIRIKIRI -> setOf("kirikiri2", "krkrsdl3")
            EngineType.ONS -> setOf("ons")
            EngineType.ARTEMIS -> setOf("artemis", "artemis.compat", "artemis.compat.v2", "artemis.v4", "artemis.v5", "artemis.v6", "artemis.clean")
            EngineType.SIGLUS -> setOf("siglus")
            EngineType.REALLIVE, EngineType.AVG32, EngineType.UK2 -> setOf("fbgames")
            EngineType.FVP -> setOf("fvp")
            EngineType.XSYS35 -> setOf("xsys35")
            EngineType.XSYS4 -> setOf("xsys4")
            EngineType.TYRANO -> setOf("tyrano")
            EngineType.RPG_MV, EngineType.RPG_MZ -> setOf("tyrano", "rpgmaker")
            EngineType.ETHORNELL -> setOf("ethornell")
            EngineType.YURIS -> return entry.has("token") && !entry.optBoolean("returned")
            else -> emptySet()
        }
        val live = processes.filter { it.uid == Process.myUid() && suffixes.any { suffix -> it.processName == "${context.packageName}:$suffix" } }
        if (live.any { process ->
                val state = runtimeStore(context).load(SaveContentHash.text(process.processName))
                state.optInt("pid", -1) != process.pid || state.optInt("activities", 1) > 0
            }) return true
        // Cover startActivity -> host registration without ever touching a running save directory.
        return live.isEmpty() && entry.has("token") && System.currentTimeMillis() - started in 0..START_GRACE_MS
    }

    private fun sessions(context: Context) = SaveSyncStateStore(File(context.noBackupFilesDir, "save_sync/sessions"))
    private fun runtimeStore(context: Context) = SaveSyncStateStore(File(context.noBackupFilesDir, "save_sync/hosts"))
    private fun processName(context: Context): String = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        manager?.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName.orEmpty()
    }
    private const val START_GRACE_MS = 15_000L
}
