package com.tyranor.next.core.game.save

/**
 * Admission for App-side Ethornell backup/restore/delete, not a user preference.
 * The active user root is the game's savedata directory. Legacy private data is
 * a one-time migration source, never the fallback write destination. Operations
 * retain host-dispatch admission, ZIP limits and reserved inode protection.
 * This does not imply PC save conversion or commercial-game slot compatibility.
 */
object EthornellSaveCapability {
    const val DEVICE_SAVE_IO_VERIFIED = true
    internal const val HOST_DISPATCH_PROTOCOL_VERIFIED = true
    val available: Boolean get() = DEVICE_SAVE_IO_VERIFIED && HOST_DISPATCH_PROTOCOL_VERIFIED
}
