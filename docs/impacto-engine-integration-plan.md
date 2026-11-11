# Impacto engine integration plan

Status: implementation and independent logic/architecture reviews are complete. Local JVM, native-boundary, Debug/Release R8 and artifact checks passed with the existing lint baseline disclosed. Real-device and game-compatibility acceptance remain open; keep the PR draft. Results: [acceptance record](impacto-acceptance.md).

## Request and baseline

Integrate the existing CommitteeOfZero/impacto runtime into Tyranor Next, rather than reimplementing the MAGES. virtual machine. Work in a new isolated branch and preserve unrelated engine work. Review this written plan before dividing implementation, then independently review and validate the integrated result before proposing an upstream pull request.

- Tyranor Next baseline: `345be7a` (`main` at checkout).
- Impacto baseline: `ba0b6e0884b9016fcd7dfd3e50024c5af1e8aef7`.
- Workspace: an isolated cloud checkout; no modifications to other projects or connected computers.
- Upstream Impacto already builds an Android ARM64 shared library and APK, but remains experimental. Integration does not establish complete game compatibility.

## Architecture findings

TYN separates UI, `app/core` orchestration, and `engine` hosts. Engine registration is distributed across classification/scanning, launch, capabilities/settings, catalog UI, and runtime packaging. Large native runtimes are extracted through `NativeRuntimePack`; startup errors use `LaunchResult` and localized UI mappings. Three language resource sets and specification/architecture documents must remain consistent.

Impacto uses an SDL3 entry point (`SDL_main`), global engine state, profile Lua, TOML user configuration, original game assets, and private writable saves/configuration. Its ordinary shutdown and error paths can terminate its process. It must not run in TYN's main process. The upstream Android directory picker and copy routines are not suitable as TYN's launch layer.

There is a concrete SDL collision: TYN's existing `org.libsdl.app` is SDL2; its separate `org.libsdl3.app` expects SDL 3.4.12. Impacto pins static SDL 3.4.10. Neither existing Java package nor existing runtime may be overwritten.

## Design

### Runtime isolation and reproducibility

Use a dedicated non-exported `ImpactoActivity` in the `:impacto` process. Reuse upstream SDL3 Java 3.4.10 under an Impacto-specific namespace and rebuild its matching native SDL with a deterministic source patch for `SDL_JAVA_PREFIX` and hardcoded JNI registration class paths. SDL 3.4.10 does not provide an Android-Java-package CMake option; a cache variable alone is not an implementation. Patch `src/core/android/SDL_android.c` and `src/hidapi/android/hid.cpp`, then verify source and ELF namespaces. Keep static SDL inside the Impacto runtime, as upstream does, so existing SDL2 and krkrsdl3 hosts remain unchanged. Preserve upstream copyright notices and include source revision, dependency versions, build recipe, patch set, SHA-256 checksums and third-party license notices. Do not silently substitute an unverified upstream APK library whose JNI namespace differs.

Package `libimpacto.so` through a dedicated runtime pack, using existing extraction and stamp validation. Ensure runtime presence before launch and recheck the current pack at Activity restoration. Do not return before the Android superclass lifecycle call; do not invoke native methods after a broken-library path. Never kill the main application process. Keep graphics claims to what the upstream runtime actually renders; TYN's custom enhancement pipeline is not automatically available.

### Profile and resource contract

Support only explicit upstream profiles at the pinned revision. Do not identify a game from a generic `.cpk`, `.mpk`, or executable extension. Use an explicit, bounded, versioned `impacto.json` launch descriptor in a prepared game directory. Filename sets are not unique (for example Darling and MO7 overlap), so they can validate a declared profile but cannot select it. The descriptor selects an allowlisted profile; it cannot inject arbitrary CLI flags, Lua, library paths, or execute scripts from arbitrary paths. Document exact directory layout and original legally obtained asset requirements. A generic MAGES archive is not proof that Impacto supports that game.

Generate `basepaths.lua`, a game-definition file and per-game user configuration in private app storage. Use robust Lua string escaping, canonical paths and per-game stable identifiers. Map original game files without copying gigabytes or modifying them. Bundle only redistributable upstream runtime resources, profiles and shaders. Inspect all profiles for hardcoded resource and save locations; patch only necessary path plumbing with an auditable upstream patch. The upstream VFS adds the profile ID beneath `RootGamedataDir`; use a generated trusted profile wrapper to remap only exact profile-root-prefixed mount paths to the selected original directory. Resolve all explicit save-path keys, including MO6TW hardcoded resource paths, into private per-game saves. Keep user saves/config separate from replaceable bundled resources and never overwrite them during app upgrades. Reject unsupported SAF-only locations with an existing typed launch failure rather than claiming direct SAF support.

### Application integration

Add `EngineType.IMPACTO` with stable serialization and additive exhaustive mappings. Add precise descriptor-based scanning to both filesystem and SAF adapters where possible; preserve existing detection precedence. Launch planning validates descriptor, profile, supported Android API/ABI, readable paths, assets and runtime, then builds a concrete launch payload. App core depends only on engine APIs; UI does not import native host packages.

Add the catalog entry, experimental compatibility wording, source/license information, necessary profile/settings access and only capabilities implemented end to end. Reuse existing orientation, theme, focus and common input integration where supported. Do not expose save management or per-game settings that do not map to real behavior. Localize user-visible messages in Chinese, English and Japanese. Keep original engines' defaults unchanged.

## Work packages after review

1. Native/runtime worker: pinned source build recipe and minimal patch set, dedicated SDL Java namespace, native artifact/provenance/license bundle, runtime resources, runtime host and its lifecycle tests.
2. App integration worker: descriptor/profile parsing, deterministic scanner integration, typed launch preparation, engine classification and capability/UI/settings plumbing, tests and localization.
3. Validation worker: workspace-local official JDK/Android toolchain, clean baseline diagnostics, focused and aggregate checks; no unrelated tracked changes.
4. Integration lead: resolve package interfaces before edits, coordinate disjoint ownership, review every diff, add final docs and acceptance evidence, then request independent logic/architecture/robustness/regression review.

Workers must coordinate overlapping files before editing. No binary or feature-complete claim is accepted without a reproducible source identity and actual validation evidence.

## Verification gates

- Descriptor/profile tests: valid fixtures, unknown profile, missing/oversized/malformed descriptor, escaping and non-ASCII paths, path traversal, raw/SAF parity, false-positive archive cases and existing engine precedence.
- Launch/resource tests: stable per-game save roots, missing/unreadable inputs, stale/missing runtime, atomic resource updates, failed copy and preservation of saves, correct CLI argument separation and isolated Activity declaration.
- Native checks: ARM64 ELF identity, expected Android API level, JNI namespace/version consistency, exported entry point and dependencies, 16 KiB load-segment alignment, checksum and licensing records.
- Repository checks: `python tools/check-hardcoded-ui-strings.py`; focused JVM tests followed by `./gradlew testDebugUnitTest --no-daemon`; `./gradlew :app:lintDebug :engine:lintDebug --no-daemon`; `./gradlew assembleDebug --no-daemon`; relevant existing resource/runtime regressions.
- Device acceptance when a suitable ARM64 device and legal sample are available: cold start, title screen/input/audio/video, pause/resume, rotation, close/relaunch, main-process survival, two games with distinct saves, upgrade restoration, missing library/resource failures. Recheck existing ONS and krkrsdl3 isolation. No proprietary sample is committed.

Report passed, failed and not-run checks separately. Compilation and synthetic fixtures are not evidence of complete game compatibility. If device/sample access is absent, document the exact unmet gate in the PR and keep it draft.

## Risks and unresolved review questions

- The private SDL strategy adds code and binary size but avoids modifying shared native dependencies.
- All shipped runtime resources need redistribution/license review, including fonts and game-adjacent assets. Static LGPL dependencies need corresponding-source/relinking compliance review; the ISC top-level license alone is insufficient.
- Upstream profile-specific save paths and resource-root behavior must be resolved before host code is finalized.
- Upstream requests a GLES 3.2 context despite advertising GLES 3.0 in its manifest. Gate the unmodified runtime on actual GLES 3.2 capability and do not raise the entire TYN application requirement.
- Upstream Android uses API 28 while TYN supports API 26. Retain TYN's minimum globally and gate Impacto specifically if a verified API26 build is not feasible.
- A real runtime build may require substantial vcpkg downloads/time. Do not register a pack with missing binary and break unrelated standard builds; either finish/provide verified artifacts or explicitly mark a development-only build path in the reviewed implementation.
- Upstream write permission is absent in the connected GitHub account. Confirm an appropriate user-owned fork and branch before publishing the authorized draft PR; never push directly to upstream or reuse an unrelated branch.

## Contracts resolved during independent review

- Descriptor v1 is UTF-8 `impacto.json`, at most 4096 bytes, with only integer `version: 1` and a case-sensitive allowlisted `profile`. Duplicate/unknown keys, trailing JSON and invalid types are rejected. Packaged profile metadata is the single source of the allowlist. The app rereads at launch.
- App-to-host extras are the existing canonical `PATH` and stable `ROOT_URI`, plus `IMPACTO_PROFILE` and `IMPACTO_LAUNCH_DIR`. The latter is a unique app-private session configuration directory. The host verifies canonical containment. A SHA-256 of the stable identity defines persistent user config/save location; stable user configuration is never rewritten during launch or resource upgrade.
- SDL loading overrides both `loadLibraries()` (absolute `System.load`) and `getMainSharedObject()`; the upstream default APK-lib lookup cannot locate a runtime pack. Private JNI/Java callback classes have explicit R8 keep rules and release-minification checks.
- Resources use a distinct versioned atomic installer, not the native-library pack's 32-entry `.so`-only contract. Validate archive entries, canonical containment, total bytes/count and manifest hashes; stage, verify, rename and stamp only after success. Preserve the previous complete version on failure. Saves and user TOML are outside all replaceable directories.
- Patch upstream Android working-directory selection to use only the validated private runtime-resource root supplied through a fixed host option. `RootInstallDir` alone does not work: native/profile code directly opens `resources/...` and upstream main otherwise changes CWD to Android `filesDir`.
- Trusted VFS remapping covers direct string mounts and table `Path` and `Order` values, preserving `Whitelist` and mount order. Never change game resources or accept arbitrary local profile Lua.
- Pin every native source dependency, including LibAtrac9 (upstream otherwise fetches moving `master`), vcpkg baseline, SDL and other FetchContent sources. Record official toolchain versions and exact artifact hashes.
- Noto CJK fonts require their actual SIL OFL copyright/license. Upstream font-atlas generation provenance is incomplete; preserve supplied upstream notices and record this limitation rather than claiming an ISC-only binary or making unsupported asset-ownership assertions. Public distribution requires resolving any actual license exclusion and LGPL corresponding-source/relink materials.
