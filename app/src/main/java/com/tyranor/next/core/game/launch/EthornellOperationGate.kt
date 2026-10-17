package com.tyranor.next.core.game.launch

import android.content.Context
import com.core.engine.EngineSessionRegistry
import com.core.ethornell.EthornellDispatchRegistry
import com.tyranor.next.core.game.save.GameSaveException
import com.tyranor.next.core.game.save.SaveErrorCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Main-process coordination only, NOT a replacement for the native writer flock.
 * The lock spans preflight -> startActivity, and each complete save operation.
 * Durable dispatch state is owned by the engine registry, not an App polling
 * lease. Unclaimed starts are revoked atomically against host claim; late hosts
 * reject revoked tokens before touching userdata. Claimed owners require either
 * destroy ACK or registry-verified process death. No timeout proves quiescence.
 */
internal object EthornellOperationGate {
    private val mutex = Mutex()

    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }

    /** Legacy synchronous cleanup must never block the UI or bypass the same Mutex. */
    fun cleanupIfIdle(context: Context, block: () -> Unit) {
        tryExclusive {
            if (!isSaveBusy(context)) block()
        }
    }

    internal fun tryExclusive(block: () -> Unit): Boolean {
        if (!mutex.tryLock()) return false // Retain data rather than race an in-flight launch/save.
        try {
            block()
            return true
        } finally {
            mutex.unlock()
        }
    }

    fun requireIdle(context: Context) {
        if (isSaveBusy(context)) throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
    }

    fun isBusy(context: Context): Boolean {
        if (EthornellDispatchRecovery(context.filesDir) {
                EthornellDispatchRegistry.retireDeadClaim(context, it.token)
            }.busy()) return true
        // Always check the WHOLE host, even after a terminal ACK: native destroy
        // completed, but the process may not yet have exited.
        return snapshot(context).busy
    }

    /** Under [exclusive], immediately before dispatch. Registry is the sole token source. */
    fun issue(context: Context, identity: String): String =
        EthornellDispatchRegistry.issue(context.filesDir, identity)

    /** A dead process with a retained task can be recreated directly from Recents,
     * bypassing the launcher's Mutex. Keep management closed until that task is
     * dismissed as well; never kill/remove it as a side effect of a save action. */
    fun isSaveBusy(context: Context): Boolean {
        if (isBusy(context)) return true
        return try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                ?: return true
            manager.appTasks.any { task ->
                val component = task.taskInfo?.baseIntent?.component ?: return@any false
                component.packageName == context.packageName &&
                    component.className == "com.core.ethornell.EthornellActivity"
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            true
        }
    }

    private fun snapshot(context: Context): EthornellHostSnapshot {
        return try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val processes = manager?.runningAppProcesses
                ?: return EthornellHostSnapshot(alive = false, acknowledged = false, known = false)
            // Both queries matter: a live but not-yet-registered host is initializing,
            // and a registry read can win over a later racing process-list read.
            val live = EngineSessionRegistry.currentLiveGame(context, EngineSessionRegistry.HOST_ETHORNELL)
            val alive = EngineSessionRegistry.isHostAlive(context, EngineSessionRegistry.HOST_ETHORNELL) ||
                processes.any { it.uid == android.os.Process.myUid() &&
                    it.processName == "${context.packageName}:ethornell" }
            EthornellHostSnapshot(alive, live != null)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            EthornellHostSnapshot(alive = false, acknowledged = false, known = false)
        }
    }
}

internal data class EthornellHostSnapshot(val alive: Boolean, val acknowledged: Boolean, val known: Boolean = true) {
    val busy: Boolean get() = !known || alive || acknowledged
}

/** Recovery survives main-process death without a second pending file or polling.
 * Caller still checks host liveness after this metadata-only recovery succeeds. */
internal class EthornellDispatchRecovery(
    private val filesDir: File,
    private val retireDeadClaim: (EthornellDispatchRegistry.Record) -> Boolean,
) {
    fun busy(): Boolean {
        for (record in EthornellDispatchRegistry.records(filesDir)) {
            when (record.stage) {
                EthornellDispatchRegistry.Stage.ISSUED -> {
                    // claim and revoke share the engine's OS metadata lock.
                    // If claim won, re-read and require exact-owner recovery.
                    if (!EthornellDispatchRegistry.revokeUnclaimed(filesDir, record.token, record.identity)) {
                        val current = EthornellDispatchRegistry.status(filesDir, record.token) ?: continue
                        if (current.stage == EthornellDispatchRegistry.Stage.ISSUED ||
                            (current.stage == EthornellDispatchRegistry.Stage.CLAIMED && !retireDeadClaim(current))) return true
                    }
                }
                EthornellDispatchRegistry.Stage.CLAIMED -> if (!retireDeadClaim(record)) return true
                EthornellDispatchRegistry.Stage.TERMINAL,
                EthornellDispatchRegistry.Stage.REVOKED -> Unit
                else -> throw GameSaveException(SaveErrorCode.SAVE_DIR_UNAVAILABLE)
            }
        }
        return false
    }
}
