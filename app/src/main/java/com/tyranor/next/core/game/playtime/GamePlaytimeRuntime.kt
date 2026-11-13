package com.tyranor.next.core.game.playtime

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.KeyguardManager
import android.os.Bundle
import android.os.Build
import android.os.Process
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.core.engine.LaunchContract
import com.tyranor.next.core.engine.EngineType
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Records built-in engine Activity foreground time in per-process AtomicFile checkpoints. */
object GamePlaytimeRuntime {
    private const val TAG = "GamePlaytimeRuntime"
    private const val CHECKPOINT_INTERVAL_MILLIS = 15_000L
    private val lock = Any()
    private var installed = false
    private lateinit var checkpointStore: PlaytimeCheckpointStore
    private val processInstanceId = UUID.randomUUID().toString()
    private val sessions = mutableMapOf<String, SessionState>()
    private val activitySessions = IdentityHashMap<Activity, SessionState>()
    private val screenPausedActivities = IdentityHashMap<Activity, String>()
    private val checkpointExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "game-playtime-checkpoint").apply { isDaemon = true }
    }
    private var checkpointTask: ScheduledFuture<*>? = null

    /** Safe in every process: child processes install lifecycle hooks but never open Room. */
    fun install(application: Application) {
        synchronized(lock) {
            if (installed) return
            installed = true
            checkpointStore = PlaytimeCheckpointStore(application.noBackupFilesDir)
            application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) = onResumed(activity)
                override fun onActivityPaused(activity: Activity) = onPaused(activity)
                override fun onActivityDestroyed(activity: Activity) = onDestroyed(activity)
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            })
            val screenReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        Intent.ACTION_SCREEN_OFF -> pauseForScreenOff()
                        Intent.ACTION_SCREEN_ON,
                        Intent.ACTION_USER_PRESENT -> resumeAfterScreenOn(application)
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                application.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                application.registerReceiver(screenReceiver, filter)
            }
        }
    }

    private fun onResumed(activity: Activity) {
        val identity = hostIdentity(activity) ?: run {
            synchronized(lock) { detach(activity) }
            return
        }
        val sessionId = activity.intent?.getStringExtra(LaunchContract.PLAYTIME_SESSION_ID)
            ?.takeIf(String::isNotBlank) ?: run {
                synchronized(lock) { detach(activity) }
                return
            }
        val gameUri = activity.intent?.getStringExtra(LaunchContract.ROOT_URI)
            ?.takeIf(String::isNotBlank) ?: run {
                synchronized(lock) { detach(activity) }
                return
            }
        val sessionKey = "$sessionId\u0000$gameUri"
        val sessionStartedAtMillis = activity.intent?.getLongExtra(
            LaunchContract.PLAYTIME_SESSION_STARTED_AT,
            0L,
        )?.takeIf { it > 0L } ?: System.currentTimeMillis()
        if (!isScreenUsable(activity)) {
            synchronized(lock) {
                detach(activity)
                screenPausedActivities[activity] = sessionKey
            }
            return
        }
        if (!GamePlaytimeRepository.supports(identity)) {
            synchronized(lock) { detach(activity) }
            return
        }

        synchronized(lock) {
            val key = sessionKey
            screenPausedActivities.remove(activity)
            if (activitySessions[activity]?.let { it.key == key } == true) return
            detach(activity)
            val state = sessions.getOrPut(key) {
                SessionState(
                    key = key,
                    sessionId = sessionId,
                    gameUri = gameUri,
                    sessionStartedAtMillis = sessionStartedAtMillis,
                )
            }
            state.activeActivities.add(activity)
            activitySessions[activity] = state
            val segment = state.segment ?: newSegment(state).also { state.segment = it }
            segment.clock.resume(activity, SystemClock.elapsedRealtime())
            ensureCheckpointing()
        }
    }

    private fun onPaused(activity: Activity) {
        synchronized(lock) {
            screenPausedActivities.remove(activity)
            detach(activity)
        }
    }

    private fun onDestroyed(activity: Activity) {
        synchronized(lock) {
            screenPausedActivities.remove(activity)
            detach(activity)
        }
    }

    /** Pause of the last resumed host closes its immutable segment and checkpoints synchronously. */
    private fun detach(activity: Activity) {
        val state = activitySessions.remove(activity) ?: return
        if (!state.activeActivities.remove(activity)) return
        val segment = state.segment ?: return
        val now = SystemClock.elapsedRealtime()
        segment.clock.pause(activity, now)
        if (state.activeActivities.isEmpty()) {
            writeCheckpoint(state, segment, closed = true, nowElapsedMillis = now)
            state.segment = null
            sessions.remove(state.key)
            stopCheckpointingIfIdle()
        }
    }

    private fun newSegment(state: SessionState) = Segment(
        recordId = "$processInstanceId-${UUID.randomUUID()}",
        startedAtMillis = state.sessionStartedAtMillis,
        clock = PlaytimeSessionClock(),
    )

    private fun ensureCheckpointing() {
        if (checkpointTask?.isDone == false) return
        checkpointTask = checkpointExecutor.scheduleAtFixedRate(
            { synchronized(lock) { checkpointAll(closed = false) } },
            CHECKPOINT_INTERVAL_MILLIS,
            CHECKPOINT_INTERVAL_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun stopCheckpointingIfIdle() {
        if (sessions.values.none { it.segment != null }) {
            checkpointTask?.cancel(false)
            checkpointTask = null
        }
    }

    private fun pauseForScreenOff() {
        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            val bindings = activitySessions.entries.map { it.key to it.value }
            bindings.forEach { (activity, state) ->
                screenPausedActivities[activity] = state.key
                activitySessions.remove(activity)
                state.activeActivities.remove(activity)
                state.segment?.clock?.pause(activity, now)
                if (state.activeActivities.isEmpty()) closeSegment(state, now)
            }
            stopCheckpointingIfIdle()
        }
    }

    private fun resumeAfterScreenOn(application: Application) {
        val power = application.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguard = application.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (power?.isInteractive != true || keyguard?.isKeyguardLocked == true) return
        val resumed = synchronized(lock) {
            screenPausedActivities.keys.toList().also { screenPausedActivities.clear() }
        }
        resumed.forEach(::onResumed)
    }

    private fun isScreenUsable(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return power?.isInteractive == true && keyguard?.isKeyguardLocked != true
    }

    private fun closeSegment(state: SessionState, nowElapsedMillis: Long) {
        val segment = state.segment ?: return
        writeCheckpoint(state, segment, closed = true, nowElapsedMillis = nowElapsedMillis)
        state.segment = null
        sessions.remove(state.key)
    }

    private fun checkpointAll(closed: Boolean) {
        val now = SystemClock.elapsedRealtime()
        sessions.values.forEach { state ->
            state.segment?.let { writeCheckpoint(state, it, closed, now) }
        }
    }

    private fun writeCheckpoint(
        state: SessionState,
        segment: Segment,
        closed: Boolean,
        nowElapsedMillis: Long,
    ) {
        runCatching {
            checkpointStore.write(
                PlaytimeRecordSnapshot(
                    recordId = segment.recordId,
                    sessionId = state.sessionId,
                    gameUri = state.gameUri,
                    durationMillis = segment.clock.snapshot(nowElapsedMillis),
                    startedAtMillis = segment.startedAtMillis,
                    closed = closed,
                    processId = Process.myPid(),
                ),
            )
        }.onFailure { Log.w(TAG, "Could not write playtime checkpoint", it) }
    }

    private fun hostIdentity(activity: Activity): EngineType? {
        val className = activity.javaClass.name
        val intent = activity.intent ?: return null
        return when (className) {
            "com.akira.tyranoemu.remote.Kirikiroid139",
            "com.akira.tyranoemu.remote.Kirikiroid134",
            "com.core.krkrsdl3.Krkrsdl3Activity" -> EngineType.KIRIKIRI

            "com.yuri.onscripter.ONScripter" -> EngineType.ONS
            "com.core.tyrano.TyranoActivity",
            "com.core.rpgmaker.RpgMakerActivity" -> when (intent.getStringExtra(LaunchContract.TYPE)) {
                "RPG" -> EngineType.RPG_MV
                "RMMZ" -> EngineType.RPG_MZ
                "VN" -> EngineType.VN
                "WebOther" -> EngineType.WEB_OTHER
                "Tyrano" -> EngineType.TYRANO
                else -> null
            }
            "com.akira.tyranoemu.remote.ArtemisActivityV1",
            "com.akira.tyranoemu.remote.ArtemisActivityV2",
            "com.akira.tyranoemu.remote.ArtemisActivityV6",
            "com.akira.tyranoemu.remote.ArtemisActivityClean",
            "com.ies_net.artemis.VideoViewActivity" -> EngineType.ARTEMIS

            "com.core.siglus.SiglusActivity" -> EngineType.SIGLUS
            "com.core.games.FramebufferGameActivity" -> when (intent.getStringExtra(LaunchContract.GAMES_ENGINE)) {
                "reallive" -> EngineType.REALLIVE
                "avg32" -> EngineType.AVG32
                "uk2" -> EngineType.UK2
                else -> null
            }
            "com.core.fvp.FvpActivity" -> EngineType.FVP
            "com.core.ethornell.EthornellActivity" -> EngineType.ETHORNELL
            "com.core.xsys35.Xs35Activity" -> EngineType.XSYS35
            "com.core.xsys4.Xs4Activity" -> EngineType.XSYS4
            "com.core.cmvs.CmvsActivity" -> EngineType.CMVS
            "com.tyranor.runtime.systemnnn.PlayerActivity" -> EngineType.SYSTEMNNN
            // Xs4ManualActivity, validator services, external modules, and all non-host Activities are excluded.
            else -> null
        }
    }

    private data class SessionState(
        val key: String,
        val sessionId: String,
        val gameUri: String,
        val sessionStartedAtMillis: Long,
        val activeActivities: MutableSet<Activity> = Collections.newSetFromMap(IdentityHashMap<Activity, Boolean>()),
        var segment: Segment? = null,
    )

    private data class Segment(
        val recordId: String,
        val startedAtMillis: Long,
        val clock: PlaytimeSessionClock,
    )
}
