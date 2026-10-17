package com.tyranor.next.core.game.launch

/** Consumes the registry's UID/process-generation-validated snapshot; stale files are not sessions. */
internal object EthornellLaunchPolicy {
    enum class Action { START_NEW, RESTORE_EXISTING, BUSY }

    fun decide(requestedIdentity: String, liveIdentity: String?, hostAlive: Boolean): Action = when {
        liveIdentity != null -> if (liveIdentity == requestedIdentity) Action.RESTORE_EXISTING else Action.BUSY
        hostAlive -> Action.BUSY // Initializing/closing: no trustworthy identity yet, never create another writer.
        else -> Action.START_NEW
    }
}
