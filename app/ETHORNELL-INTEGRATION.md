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

## Host ABI: 15 exports, kind 5 and JSON dialogs

The vendored ABI header and prebuilt library are pinned to upstream `0cfff1d`
(`ethornell_android.h` SHA-256 `566f475c...`) plus one documented local compatibility
patch (`tools/ethornell/patches/0001-user-data-double-bgi-resource.patch`, SHA-256 `77b99d93...`).
The current `libethornell.so` SHA-256 is `043fef05...` (27,259,224 bytes), and
provenance records `dirty=true` with `local_patches=1`. `verify_provenance.py --local-static`
(including exact native bytes in the debug and release APKs) passes, but strict source
verification is **not** passed and cannot pass while the build is dirty
(`release_ready=false`). ABI version remains `1`; the interface is additive:

- 15 exports: the original 14 are mandatory, and `ethornell_android_request_payload` is
  resolved separately. A legacy 14-symbol library remains loadable only with kind-5 support
  disabled; release artifacts must carry all 15.
- Event kind 5 is a host-side input-dialog request. The event text is the dialog type; the
  complete JSON payload is fetched with `request_payload` on the engine owner thread. The
  two-stage read treats the positive return as the required byte count (including the
  trailing NUL), rejects embedded NUL/invalid UTF-8/oversize payloads, and treats unknown or
  stale request ids as non-fatal.
- `completeRequest(handle, id, result, text)` keeps the kinds separate: kind 2 uses empty
  text and an integer result; kind 5 uses a schema-valid JSON answer. Malformed JSON would
  poison the session, so the host builds and re-parses every accepted answer before sending
  it, and sends exactly `{"accepted": false}` for cancel.
- The engine-owned dialog UI (`EthornellHostDialog` + `EthornellDialogProtocol`) covers
  `input`, `segmented`, `select` and `date`, including field limits, numeric hints and
  zero-based date conversion. It uses its own dialog window/IME mode so the Activity's
  `adjustNothing` Surface is not resized. No App Compose component is imported.

`LaunchResult.Success` still means only that the explicit Activity Intent was dispatched; it
does not mean native initialization, dialog support or game compatibility succeeded.

Save management was enabled after device acceptance of the actual runtime user-data protocol, flush
barrier, cross-process active-session exclusion, the 64 MiB per-file boundary, and native global
user-data write/read/empty negative. `DEVICE_SAVE_IO_VERIFIED=true` and
`HOST_DISPATCH_PROTOCOL_VERIFIED=true`, so `available=true`. Scope is App-private user-root
backup/restore/delete only; the ZIP budget is 1 GiB but the native atomic-write ceiling remains
64 MiB per file. No whole-root mirror or presumed `save/` fallback is installed, resource-root
`UserData` is never imported automatically, and PC save interoperability or commercial in-game
slot compatibility is not claimed. Historically it remained unavailable until those tests passed;
that earlier state is kept in the acceptance records rather than rewritten here.

The current local patch fixes the startup/global-data **write** paths: `Sys80:32` resolved a name
that already carried the `bgi-resource\` prefix a second time, so the write targeted the read-only
resource root. Clicking the language selection additionally makes `SaveGlobalUserData` write
`bgi-resource\BGI.gdb`; the old `7ee` build wrote a bare `BGI.gdb`, so this is a `0cfff1d` behaviour
change. The patch collapses exactly one duplicated prefix in `file_policy.rs` and aliases only the
conventional `UserData` subtree plus the exact top-level `BGI.gdb` and `BGI####.cad` (four digits,
case-insensitive) to the private `bgi-user/` root; arbitrary resource names, subdirectories and
traversal remain denied. Save existence/read APIs (`Sys80:34`, `Sys80:30/31/35`, loose read) are
**not** covered by this patch or this acceptance round and remain unverified. The preceding
`541bf6` build only handled `UserData` and still failed on `bgi-resource\BGI.gdb` after the language
click (`PermissionDenied`), so it is not a final candidate.

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
races before its registry becomes visible. Private-userdata cleanup remains gated: it is skipped while
session ownership/flush guarantees are unavailable, and otherwise uses the same operation gate and
private-root delete. ETHORNELL has an explicit builder branch, never UNKNOWN's Tyrano fallback.

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

## Acceptance status (2026-11-02)

- Artifact/static: current `libethornell.so` is `043fef05...` (27,259,224 bytes), upstream
  `0cfff1d` plus one local patch (SHA-256 `77b99d93...`, patched `file_policy.rs` `be3547f7...`).
  `verify_provenance.py --local-static` and `--apk` passed for both
  debug and release APKs (exact native bytes); release `zipalign -c -P 16 -v 4` passed with
  build-tools 36.0.0 (37.0.0 was not installed). Strict source verification did not pass and
  cannot pass while provenance records `dirty=true`; `release_ready=false`.
- Build/JVM: `:app:assembleDebug` and `:app:assembleDebugAndroidTest` passed; app JVM 910 tests
  (0 failures), engine 583 tests (0 failures), `checkHardcodedUiStrings`, the tools suite (79 tests)
  and the focused `file_policy` Rust tests (14/14) passed. The JVM counts are the previous run over
  unchanged code rather than a fresh rerun.
- Device: `EthornellDeviceTest` 6/6 passed in 1.426s (15 exports/build info, UTF-8/thread/token, GPU
  pixels/detach-reattach, script-driven Closed, synthetic VM global user-data write/read/empty);
  `EthornellPauseDeviceTest` 3/3 passed (2.999s, pause/Surface only); ABI/supplementary 2/2.
- Save: `EthornellSaveDeviceTest` 9/9 passed in 2.047s after `pm clear`; its first run was 8/9 because
  a retained Amairo private-Activity task kept the intentional global save gate busy. That is a
  test-isolation/order artifact, not a product regression.
- Release R8 probe: 3/3 in SJIS and 3/3 in GBK (`NUMTEST=3`, `FAILURES=0`) against the `043f` library,
  with kind bound 1..5 and production JNI create/poll/destroy; scope is
  release-native-load-create-only (no GPU/step/save).
- Real sample (Amairo): the private Activity stayed alive for 30s and 45s including a language click;
  after the click there was no `failed_detail`. The private user root contains `BGI.gdb`
  (about 123,802 bytes). `UserData` may be empty in this fresh fixture, so this verifies the
  startup/global-data write path only, not save slots. Title, dialogue, save slots and read-back are
  **not** verified. Save existence/read APIs (`Sys80:34`, `Sys80:30/31/35`, loose read) are outside
  this patch and acceptance.
- Not verified: commercial in-game slot save/restart-read, save-slot metadata/index, PC save
  interoperability, real dialogue/input dialogs, 16KB/API26 full-game reruns, audio routing, fonts,
  movie playback and long soak. The Jewel sample still stops at `system.arc:ipl._bp` on an unknown
  opcode; the old `7ee` build failed the same way, so this is not a new regression.
