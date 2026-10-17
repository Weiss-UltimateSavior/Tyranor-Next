# App → Ethornell host contract (integration handoff)

The app currently emits an explicit Intent to `com.core.ethornell.EthornellActivity`.
`LaunchResult.Success` means Activity dispatch only, not successful native initialization.

Payload required by engine host (all keys use shared LaunchContract constants):

| Constant | Exact value | Meaning |
|---|---|---|
| LAUNCH_MODE_ETHORNELL | `internal.ethornell` | LAUNCH_MODE value |
| ETHORNELL_NLS | `ETHORNELL_NLS` | `sjis` or `gbk` only |
| ETHORNELL_FONT_PATH | `ETHORNELL_FONT_PATH` | Empty or validated app-private imported font |
| ETHORNELL_USER_DATA_DIR | `ETHORNELL_USER_DATA_DIR` | Absolute private writable root |
| PATH / GAME_PATH | existing constants | Canonical real readable resource root |
| ROOT_URI | existing constant | Stable library game identity |
| UI_LANGUAGE_TAG | existing constant, `uiLanguageTag` | Explicit application locale |

User root is `filesDir/ethornell/userdata/<SHA-256(ROOT_URI UTF-8)>`, full lowercase digest.
The app `EthornellPaths` facade delegates `userDataDir` / `validateUserDataDir` to
`com.core.ethornell.EthornellPaths`, the engine-owned single derivation/validation implementation.
No basename or 32-bit hash.
Host must validate the supplied directory and isolate all user operations from resource reads.
Resource root is read-only to the runtime policy. Never reinterpret `content://` as a file path.

Save management intentionally remains unavailable in UI and GameSaveManager until the actual runtime
user-data protocol, flush barrier, and cross-process active-session exclusion are tested. No whole-root
mirror or presumed `save/` fallback is installed. Do not advertise PC save interoperability yet.

Scanner automatic acceptance requires root `system.arc`, supported complete index within 64 KiB,
and a nonempty exact `ipl._bp` entry. Large indices and loose-bootstrap-only layouts are deliberately
not auto-accepted. Full library rescan is needed to revisit already misclassified/pruned directories.

Host is responsible for runtime failure UI, ABI/capability checks, same-game reentry vs different-game
rejection, game session registration and flush confirmation. App-only preflight is not a native sandbox.

App launch now consumes `currentLiveGame(HOST_ETHORNELL)` and `isHostAlive(HOST_ETHORNELL)` before
directory/settings/preparation. A matching live ROOT_URI restores the existing identity-matched AppTask
with `moveToFront()` (no new payload or resource/user-root preparation). A different live identity or a
host that is alive without a valid identity returns typed `EthornellPrepareError.SESSION_BUSY`.
An in-process launch mutex serializes the dispatch window; the native host still must reject reentry
races before its registry becomes visible. No private-userdata cleanup is attempted while session
ownership/flush guarantees are unverified. ETHORNELL has an explicit builder branch, never UNKNOWN's
Tyrano fallback.

Font import is now IO-dispatched and coroutine-cancellable, with a 64 MiB actual-copy budget,
signature/platform parser validation, and exclusively owned unique staging files. Paths are only
published after validation; failed/cancelled imports delete only their own file, never overwrite a
font referenced by another game. Both Ethornell picker flows guard reentry while importing. Existing
KR/FVP callers use the same suspending importer, and RPGM's external font importer shares the bounded
immutable copier. Permission preparation now validates known provider/lexical path before the all-files
grant gate and validates canonical containment/readability afterwards. Unknown-size SAF streams that
reach EOF below the read budget use that physical length to reject truncated archive payloads.

## Best practices and security alignment update: private explicit launch

- **Priority:** High (untrusted game resource and write-root separation).
- **Scope:** `EngineLauncher`, `EthornellPaths`, `FontSettings`, `EngineScanner`; no new app permissions,
  exported components, nested Intents, URI grant flags, or dependencies.
- **Implementation:** Explicit component with a fixed class name; only the known local external-storage
  documents provider may map to a native path, canonical containment is checked, other providers return
  typed `GameDirUnresolved`. Font paths must be imported into the private `fonts` directory. User data
  always goes to its own private identity-scoped root. Native host must revalidate on both cold and warm
  entry; single-instance reentry is not permission evidence.
- **Diff excerpt:**

```diff
+ if (uri.scheme == "content" && uri.authority != "com.android.externalstorage.documents") return null
+ if (!file.canonicalFile.toPath().startsWith(allowed.canonicalFile.toPath())) return null
+ Intent().setClassName(context, "com.core.ethornell.EthornellActivity")
+ putExtra(LaunchContract.ROOT_URI, game.uri)
+ putExtra(LaunchContract.ETHORNELL_USER_DATA_DIR, EthornellPaths.userDataDir(context.filesDir, game.uri).absolutePath)
```

- **Verification:** JVM tests cover both archive formats, bounded and short reads, adapter parity,
  exact bootstrap names, ambiguity, nested directories, weak MES priority, System35, enum-name persistence,
  nullable overrides, private font containment and stable SHA-256 user roots. Device/real-game/native
  initialization and lifecycle validation is separate and is not claimed by these app tests.

Local verification on 2026-10-15: `./gradlew :app:testDebugUnitTest :app:checkHardcodedUiStrings --no-daemon`
passed (80 suites, 534 tests, zero failures/errors after app-review fixes). Full APK assemble,
device installation, and native runtime validation remain with the integration owner. Nested reviewer
delegation was attempted but blocked by the harness subagent-depth limit; parent-session independent
reviews are still required.
