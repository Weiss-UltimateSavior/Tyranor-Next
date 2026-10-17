package com.tyranor.next.core.game.save

/** Release admission, not a user preference. Enable only after device save-IO fixture acceptance. */
object EthornellSaveCapability {
    const val DEVICE_SAVE_IO_VERIFIED = false
    // Independently accepted by the parent's real-device dispatch boot-race tests.
    // This does not admit save management without device/game save-IO acceptance.
    internal const val HOST_DISPATCH_PROTOCOL_VERIFIED = true
    val available: Boolean get() = DEVICE_SAVE_IO_VERIFIED && HOST_DISPATCH_PROTOCOL_VERIFIED
}
