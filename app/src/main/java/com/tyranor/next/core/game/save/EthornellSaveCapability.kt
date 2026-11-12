package com.tyranor.next.core.game.save

/**
 * Release admission for App-side Ethornell save management, not a user preference.
 *
 * Enabled after the parent's real-device acceptance: `EthornellSaveDeviceTest`
 * passed 9/9 (private user-root ZIP/list/delete/rollback/atomic import, 64 MiB
 * per-file boundary, malicious ZIP, and operation-gate refusal; the public
 * GameSaveManager case now runs with the gate enabled), and native global
 * user-data write/read plus the empty negative case passed.
 *
 * Scope is only backup/restore/delete of the App-private user root
 * (`files/ethornell/userdata/<sha256>`). It is not PC save-format conversion,
 * never reads or rewrites `UserData` in the game resource root, does not import
 * those files automatically, and does not provide in-game slot editing or change
 * the engine's save/load protocol. It does not claim commercial-game in-game
 * slot save/read or PC save-format interoperability.
 */
object EthornellSaveCapability {
    const val DEVICE_SAVE_IO_VERIFIED = true
    // Independently accepted by the parent's real-device dispatch boot-race tests.
    // Both flags are required for App save management.
    internal const val HOST_DISPATCH_PROTOCOL_VERIFIED = true
    val available: Boolean get() = DEVICE_SAVE_IO_VERIFIED && HOST_DISPATCH_PROTOCOL_VERIFIED
}
