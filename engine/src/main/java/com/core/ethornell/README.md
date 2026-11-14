# Ethornell Android host

`EthornellActivity` is a fullscreen native game host, not an app management page.
It consumes `LaunchContract.LAUNCH_MODE_ETHORNELL`, `ROOT_URI` (identity), `PATH`
(absolute readable resource directory), `ETHORNELL_USER_DATA_DIR` (fixed game-local
writable directory), `ETHORNELL_NLS` (`sjis`/`gbk`), optional `ETHORNELL_FONT_PATH`
(imported private `files/fonts` file), and `UI_LANGUAGE_TAG` (host UI only).
Same payload re-entry resumes the current session; changed payloads are rejected.
`EthornellPaths.gameUserDataDir(resourceRoot)` defines the active user root:
`<canonical game directory>/savedata`. `validateGameUserDataDir` requires exact
absolute spelling and canonical destination, rejecting symlinks and aliases.
The host validates the root on entry and again before native creation. The native
policy permits only this fixed child as a nested user root; it excludes that tree
from ordinary resource reads. `userDataDir(filesDir, ROOT_URI)` remains the legacy
migration source and identity hashing contract, not the active write destination.
App save operations migrate legacy data without replacing existing target files;
removing a library entry retains game-local saves.

The local runtime patch `0004-virtual-user-file-reads.patch` recognizes only
exact `bgi-resource/` and `bgi-user/` virtual roots followed by a path separator
at the VM read/existence gate. In restricted `RuntimeTraceApi`, `read_disk_file`
and `disk_file_exists` use `user_data_alias` only when the mapped path begins
with `bgi-user/`; other paths keep their original meaning. Restricted
`load_file_bytes` applies the scoped user alias only to authorized non-named
archive calls. A missing mapped save does not fall back to the raw resource path;
other `read_runtime_bytes` calls and ordinary named-archive loads retain their
original path. The raw `FilePolicy` read helper is unchanged. The device fixture
exercises Sys81:32/35 generic Header loading as well as the Sys80 loose-file API.
Commercial-game slot save/readback and format compatibility remain experimental
and require separate acceptance; this is a runtime patch, not a host `openat`
shim.

`LaunchContract.ETHORNELL_DISPATCH_TOKEN` is a required one-shot random launch token.
`EthornellDispatchRegistry` persists ISSUED/CLAIMED/TERMINAL/REVOKED under the same JVM
and OS metadata lock with nofollow opens, forced file contents, atomic rename and
directory sync. Host claim happens before user-data/native initialization; cleanup
ACK writes TERMINAL before process exit. Missing/revoked/terminal token replays are
rejected, including Recents reconstruction after the original process ended.
The app can discover issuer-death leftovers through bounded `records(filesDir)`;
`revokeUnclaimed` atomically competes with claim, never timeout-releasing a claimed
session. `isOwnerAlive` uses exact /proc PID/starttime/UID/process-name evidence,
not ActivityManager list absence. `retireDeadClaim` transitions to TERMINAL under
the same lock only on confirmed missing PID or changed starttime; unknown evidence
throws IOException. `issue` reclaims only terminal/revoked records. Metadata scan
is capped at 4096 entries; corrupt/oversized/unconfirmed records fail closed and
are not silently deleted. This metadata lock is not the native writer flock.

All native calls and library loading run on `Ethornell-engine`. Choreographer submits
one replaceable monotonic timestamp. The VM gate is 16 ms, with no catch-up passes.
Input edges are FIFO; only adjacent moves coalesce. Android/canonical keycodes are
explicitly mapped to Windows descriptors. Back opens a host menu with reachable
right-click, backlog, and a separate exit confirmation; exit then requests the
runtime's game-close semantics rather than killing it.
Only once Closed and native destroy ACK have completed (or initialization failed
and cleanup completed without a runtime), the host clears its registry, finishes
the task and terminates its pre-verified own process. This prevents an Android-cached
empty `:ethornell` process from leaving the launcher's conservative guard permanently BUSY.
This terminal policy is never triggered directly by request_close/WM_CLOSE.

Activity, focus, audio and dialog suspension are independent mask bits. Runtime
Surface/request suspension remains independent. Audio focus loss (including duck)
really suspends; unplug/noisy suspends until focus is requested on foreground resume.
There is no background audio service, IME implementation, file picker or shell intent.

`surfaceDestroyed` closes its generation and blocks until native detach/destroy ACK.
A daemon supervisor independent of the worker terminates **only this pre-verified
`:ethornell` process** if ACK does not arrive. The provisional bring-up fault budgets
are 1.5 s for detach and 15 s for other native commands. They are not measured
performance guarantees: slow-init/present injection tests and real-device measurements
must tune/validate them before release. Fault termination does not promise saved progress.

JNI uses RegisterNatives, bounded standard UTF-8 conversion, shim registry handles,
RTLD_NOW/LOCAL fixed SONAME loading, exact v1 structures, and explicit borrowed window
ownership. `engine/src/main/cpp/ethornell_android.h` is copied byte-for-byte from
`ethornell-rs/platform/android/include/ethornell_android.h`; compare hashes whenever
updating the runtime. ABI v1 retains ndk-context for the isolated process lifetime;
the shim retains one Application GlobalRef across session destroy/create failures,
and releases it only on safe library unload or VM/process teardown.

This implementation and JVM/native builds do **not** establish game compatibility,
16KB-device compliance, API26 runtime loading, release R8 loading, or licensing.
Game-local save import/export is enabled through the fixed `<game>/savedata` user-root
capability; this management scope does not establish commercial-game slot save/readback
or PC-format compatibility. Those runtime behaviors require separate acceptance.

## Opt-in private Debug input-route diagnostics

Native Debug builds alone may enable ABI flags bit1 using an owner-created empty
`files/ethornell_debug/.ethornell-input-trace` file (metadata directory 0700, file
0600; no symlinks). It is checked only at session creation; remove it and start a
new session to disable. Release builds ignore it. No Intent or exported entry
enables this option. The matching Rust host uses bit1 only for targeted input
routing, not full VM/debug_graph traces. `EthornellInputTrace` logs only negotiated
kind1/codes1001,1002/request0 `input_route` and `input_gate` fixed numeric fields,
at most 384 bytes and a shared 64-event session budget; other positive diagnostics
are not printed. Code1002 reads profile-approved guest state after the real 90BC
write without altering guest data.
