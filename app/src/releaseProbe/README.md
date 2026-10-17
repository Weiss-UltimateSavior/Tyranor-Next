# Pure Java release-native instrumentation

Explicit opt-in only: `-PethornellReleaseProbe=true`. Default remains the existing debug AndroidX/JUnit/Compose harness. Opt-in selects `testBuildType=release`, replaces androidTest Java sources using the Variant Sources API, chooses the framework-only Java Instrumentation subclass, and excludes all explicitly declared AndroidX/JUnit/Compose test dependencies. Production release R8/resource shrinking and production keep rules are unchanged. Test-only keep is limited to this runner's literal JNI export.

The runner references only Android framework and Java APIs. It loads the real target `com.core.ethornell.NativeEthornell` through the target classloader/reflection; it contains **no copied/shadow production JNI classes**. The same test-only C++ library/header as the debug probe supplies RTLD_NOW, 14 ABI symbols, build_info buffer and ABI-layout checks. Its extra JNI entry only exists in the test APK. No Kotlin/AndroidX/JUnit initialization is needed to run these checks.

## Build (parent acceptance session)

Source isolation clears **both** `java` and AGP built-in `kotlin` directories on all `androidTest*` source sets, including base and release overlays, only when the explicit release-probe property is true. AGP otherwise independently discovers `.kt` files under `src/androidTest/java`; clearing Java alone is insufficient. The release runner's one pure-Java directory is then added with Variant `sources.java.addStaticSourceDirectory`. Default debug Kotlin tests are untouched; no test dependencies or production keep rules are added to compensate for leaked sources.

```bash
TYRANOR_APPLICATION_ID=com.tyranor.next.ethornelltest ./gradlew \
  -PethornellReleaseProbe=true :app:assembleRelease :app:assembleReleaseAndroidTest --no-daemon
```

Do not mix a debug test APK with this release target. Verify target release `mapping.txt`/R8 task and shrink task outputs as artifact evidence; the runtime `debuggable=false` assertion alone does not prove shrinking occurred. Verify actual application IDs and signing certificates: release target and instrumentation must have compatible signing (local no-CI-keystore builds use existing debug signing for release; do not override production signing for this test). No `--no-configuration-cache`, no disabling R8, no broad Kotlin keep rules.

Install **separately** (two different packages, not splits; artifact names can depend on signing):

```bash
android --sdk=/tmp/androidsdk install --device='<serial>' --apks=app/build/outputs/apk/release/app-release.apk
android --sdk=/tmp/androidsdk install --device='<serial>' --apks=app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
```

Use only isolated `com.tyranor.next.ethornelltest`, never overwrite the user's formal `com.tyranor.next`. Run serially, no Android CLI layout/UIAutomation connection is used by this runner:

```bash
/tmp/androidsdk/platform-tools/adb -s '<serial>' shell am instrument -w -r \
  -e expected_package com.tyranor.next.ethornelltest -e encoding sjis \
  com.tyranor.next.ethornelltest.test/com.tyranor.next.releaseprobe.EthornellReleaseInstrumentation
```

Repeat with `encoding gbk`. NUMTEST=3, per-check START/PASS/FAIL, numtests/current/stack status fields and a final `passed`, `failures`, `scope`, `stream` result bundle are emitted. Final instrumentation result is -1 only when all checks pass, otherwise 0; still inspect `FAILURES` and process-crash output rather than treating adb's exit code as success. 60s independent daemon watchdog logs FAIL and kills only the instrumented PID on stuck JNI; process death is failure, not PASS.

1. Target identity: nondebuggable target, exact expected isolated package, target UID, actual device page size.
2. Actual RTLD_NOW/load/14 exports/ABI/build_info via the installed target's native library path.
3. Production JNI load/create with original ret PackFile fixture in canonical, separate non-BMP private resource/user roots; poll genuine bootstrap diagnostics; owner-thread pause/destroy; stale token rejection; resource unchanged.

Scope is **partial release-native evidence**. No Surface or step occurs: create/poll does NOT prove VM ret execution, GPU/pixels, audio, text/font coverage, saves, full R8 private-Activity behavior or game compatibility. Debug GPU/Closed tests and release ordinary-App UI launch/manual logs remain separate gates. The production host must be launched through the normal release game library for manual acceptance; framework instrumentation finish normally force-stops its target package, so this runner does not claim post-run host survival.

Switch back by omitting the property (or `-PethornellReleaseProbe=false`) and build the ordinary `assembleDebugAndroidTest` again. Install the matching target/test pair each time, because test application ID is intentionally the same across modes.

## Implementation evidence

Pending parent Gradle packaging and device execution. Plain javac against only android.jar validates the framework-only source dependency boundary; NDK syntax checking validates shared probe/header static assertions. Neither substitutes for actual release-device execution. No production code/keep/native changes are part of this runner.
