# SoftPAL（sena-rs）引擎接入方案

> 状态：**方案，未实施**（v2，2026-11-05，已按 4 路独立核对修正）。
> 被接入方：`/Volumes/HUAWEI-M2LITE/app/sena-rs`（SoftPAL 引擎的 Rust 重实现，目标游戏 Koikake，MPL-2.0）。文中「上游」均指该仓库；TyranorNext 引用给相对路径。

## 1. 结论先行

1. **宿主形态 = JNI + RGBA 帧泵 + 单线程驱动**。上游 Android 路径没有 GPU 直连：`sena_android_create` 丢弃 `ANativeWindow*` 与 `native_scale_factor`，`set_surface` 退化为 `resize`，每帧由 Rust 在 CPU 上光栅化成 RGBA8888 交宿主显示（`crates/pal-vm/src/ffi.rs:667-736`）。`android_main` 是空桩，**不能用 NativeActivity**（`ffi.rs:591-608`）。
2. **新增引擎族 `SOFTPAL`，进程 `:sena`，普通 `AppCompatActivity` + 自建 `SenaFrameView`**。不复用 `FramebufferView`：它只有 aspect-fit + 固定最近邻，没有 integer/bilinear 参数（`engine/src/main/java/com/core/games/FramebufferView.java:46-90`），改造会波及 RealLive/AVG32/UK2 三个宿主。
3. **`libsena.so` 走 `NativeRuntimePack` 新分片 `sena`**。M0 试构建（arm64 release + strip）：20.20 MB，xz 后 **6.09 MiB**。当前 APK 89.80 MiB，加入后约 **95.9 MiB**（见 §8 待决）。
4. **存档只做「游戏目录内」**：上游固定写 `<gameRoot>/save/`（`save/saveNNN.dat`、`continue.dat`、`save/sena_rs/*`），无 save-root 参数（`app.rs:23-51`、`runtime.rs:16371-16393`）。`SaveLocation(File(root,"save"))` + `supportsFileSaves`，**不接管独立存档**。
5. **消息框只有 kind 0，且是 pending 状态机**：`step` 在等待确认时返回 0 并挂起，确认后**下一次** `step` 才返回 2；`submit` 只接受 value=0，无超时/取消 API（`ffi.rs:110-121`、`:444-500`）。宿主必须用 engine 侧 `AlertDialog.Builder`（engine 模块不得依赖 app 的 Compose `AppAlertDialog`）实现 Pending→Confirmed→Terminal。

## 2. 上游事实（供实现对照）

| 维度 | 事实 | 证据（上游） |
| --- | --- | --- |
| 产物 | cargo-ndk 产 `libpal_vm.so`，脚本复制为 `libsena.so`；参考 JNI 壳 `libsena_jni.so` | `platform/scripts/package_android_apk.sh:78-91` |
| ABI | 13 个 `sena_android_*`（create/step/resize/set_surface/touch/destroy/frame_{rgba,width,height,generation}/init_context/messagebox callback/submit） | Android 头 `platform/android/app/src/main/cpp/include/sena.h:71-99`（common 版为 `platform/common/sena.h:114-142`） |
| 线程 | 句柄以裸 `*mut` 无锁传递，推断除 `submit_messagebox_result`（跨线程邮箱）外全部应在同一线程调用；回调在 `step` 线程同步触发 | `ffi.rs:34-36`、`:1072`；`reverse/softpal-runtime-audit.md:179` |
| 帧 | RGBA8888、紧密无 stride、自上而下、alpha=255；**首帧后**尺寸才变为逻辑分辨率（`SYSTEM.INI` 或 1280×720），首帧前是 `window_size_from_config` 给的窗口尺寸；`generation` 每次成功 step 递增，指针仅在下一次 step 前有效 | `ffi.rs:405-417`、`:472-493`、`scene.rs:294-312` |
| step | 0=继续（错误帧首调也返回 0 并挂起等待确认），1=正常结束，2=错误（确认后下一次调用返回 2 并保持终止） | `ffi.rs:123-147`、`:444-450`、`:499-500` |
| 音频 | 默认 `device-audio`（CPAL→AAudio），宿主只需 `init_context` 注册 `ndk_context`；**无 pause/静音 ABI** | `ffi.rs:634-658` |
| 输入 | 触摸单指（0=down/1=move/2=up/3=cancel，只发左键）；`sena_host_key`（winit 字符串键名）已在 Android 头声明（`:35`），`sena_host_mouse_button` 仅在 common 头、需自声明/`dlsym`；两者均导出；无滚轮、无文本输入 | Android 头 `sena.h:35`、`common/sena.h:46`；`ffi.rs:513-551`、`:1104-1118` |
| 存档 | `<root>/save/`（原生 + portable），无重定向；游戏根必须可写 | `save_format.rs:113-123`、`runtime.rs:16371-16393` |
| 识别资产 | `data/archive.dat`（首选）或根 `archive.dat`；`data.pac`/`csv.pac` 内可含 `Script.src` 等；精确 profile 用 `PAL.dll`/`koikake.exe` 的 size+SHA-256，且 `PAL.dll` 另参与 dispatch 导出探测 | `resource.rs:89-99,171-188`、`native_profile.rs:26-44,140-155`、`assets.rs:45-49` |
| 编码 | NLS：`sjis`(默认)/`gbk`/`utf8`；语言：`jp`(默认)/`cn`/`tc`；Koikake 原版 SJIS+JP | `nls.rs:8-61`、`config.rs:255-267` |
| 许可 | MPL-2.0；分发 `.so` 必须提供 Source Code Form 获取方式 | `LICENSE-MPL-2.0:170-195` |
| 体积 | M0 实测：strip 后 20.20 MB、xz 6.09 MiB；内嵌字体 8.99 MB；默认特性含 wgpu/winit/softbuffer | `font.rs:10`、`pal-vm/Cargo.toml:39-44`、§7 |

## 3. 接入设计

### 3.1 引擎身份与扫描

- `EngineType.SOFTPAL("SoftPAL", "SPAL")`，插在 `UNKNOWN` 之前（`core/engine/EngineType.kt:10,37`）；`abbr` 唯一性测试无需改。
- `supportedEngines` 追加（`core/game/launch/EngineLauncher.kt:128-158`，引擎页 `ui/engine/EngineScreen.kt:113` 是唯一来源）。
- `launchMode = "internal.softpal"`；本期不做子版本。
- `EngineScanner` 的现有模型是**单条 if 链 + 首次命中即返回固定 confidence**（不是强/中/弱评分；`EngineScanner.kt:1503` 兜底 UNKNOWN），且 `rel`/文件名已 `lowercase()` 比较、会递归进 `ENGINE_SEARCH_DIRECTORIES` 白名单子目录（`:1116-1137`、`:1508-1521`）。因此判据写成**固定 confidence 的 if 分支**并指定插入点：
  - `data/archive.dat` 存在 → `SOFTPAL`，confidence 96（首选，上游首选路径）；
  - 根 `archive.dat` 或 `Script.src` → 92；
  - `data.pac`/`csv.pac` + (`PAL.dll` 或 `koikake.exe`) → 90（**不解析 PAC 内部**；`ARCHIVE.DAT` 在 `data.pac` 内这一事实只作为组合判据，不做归档读取）；
  - `File.dat`+`Text.dat`+`Mem.dat`+`Point.dat` 中 ≥3 → 86；
  - `SYSTEM.INI` 单独不足以判定：现有 `system.ini + first.iet` 已判 Artemis（`:1336-1349`），SoftPAL 分支必须放在 Artemis 之后且要求 `system.ini` 内容含 `SoftPAL`/`PAL` 标记（M2 再定解析强度）。
- `launchTarget = DIR`（`EngineScanner.kt:1487`，同型分支 `:1244`）。
- **M1 前必须先用真实 Koikake 目录验证判据**；扫描测试补 `data/`、根、`data.pac` 三种夹具。

### 3.2 进程与宿主

Manifest（放在 `engine/src/main/AndroidManifest.xml`，字段对齐 FVP `:150-160`）：

```xml
<activity android:name="com.core.sena.SenaActivity"
    android:exported="false" android:process=":sena"
    android:launchMode="singleInstance"
    android:taskAffinity="com.core.Softpal"
    android:screenOrientation="sensorLandscape"
    android:theme="@style/Theme.Softpal"
    android:windowSoftInputMode="adjustNothing"
    android:configChanges="keyboard|keyboardHidden|navigation|orientation|layoutDirection|screenLayout|screenSize|smallestScreenSize|uiMode|density|fontScale|locale"/>
```

宿主 `com/core/sena/SenaActivity.java`：

- `onCreate`：解析 `PATH` → `NativeSena.initAndroidContext` → `create(null, w, h, 1.0, root, nls, lang)`；失败 Toast + `finish()`。
- 呈现：新增 `SenaFrameView`（照 `FramebufferView` 写，但显式提供 `fit | integer` 与 `bilinear | nearest` 两个参数），`Bitmap.Config.ARGB_8888` + `copyPixelsFromBuffer`；**首帧前不画**（`generation==0`），首帧后才用 `frame_width/height` 建 Bitmap 与 letterbox。
- 循环：`Choreographer` → `step(dt)`；`dt` 夹 0–250 ms，默认按 60 fps 节流（累积 dt），避免 120 Hz 双倍 CPU。
- 消息框状态机：
  - 回调内复制 title/message（指针只在下一次 step 前有效），按 `request_id` 去重；
  - 用 engine 侧 `AlertDialog.Builder`（`setCancelable(false)`）展示；Activity 不可用/正在 `onStop` 时自动 `submit(id, 0)`，避免永久挂起；
  - 确认后 `submit(id, 0)` → 继续 `step` → 收到 2 → 关循环并 `finish()`；
  - `onDestroy` 顺序：停 Choreographer → 注销回调（`set_native_messagebox_callback(null)`）→ `destroy(handle)`。
- 触摸：用 `SenaFrameView` 的 `toFrame()` 反变换；点在 letterbox 外不转发。只有单指左键；右键/键盘仅在 `dlsym` 到可选符号时启用。
- 生命周期：`onPause` 停泵、`releaseAll`；`onResume` 续跑（Surface 无关）。**后台音频无法暂停**：一期接受，`onStop` 给 30 s 宽限后 `destroy + finish`（依赖游戏自身存档），并在 UI 提示；长期需上游 suspend/mute。

### 3.3 JNI 桥（自研）

- `engine/src/main/cpp/sena_bridge.cpp`，模板取 `rfvp_bridge.cpp:127`：绝对路径 `dlopen` + `std::call_once` 符号解析；必需 create/step/resize/touch/destroy/frame_*；可选 `sena_host_key`/`sena_host_mouse_button`/messagebox，缺失只降级。
- `NativeSena.java`：`nativeSetEngineLibraryPath(String)`、`initAndroidContext(Context)`、`create(...)`、`step/resize/touch/destroy`、`frameGeneration/Width/Height`、`copyFrame(long, ByteBuffer)`（direct buffer + `memcpy`）、`setMessageboxCallback`；`libraryPath(context, PACK_SENA, "libsena.so")` 取路径。
- 不复用上游 `libsena_jni.so`：其符号与 `JNI_OnLoad` 硬编码 `io.github.xmoezzz.sena` 包名与 `SenaGameActivity.onNativeMessagebox`（`sena_jni.cpp:243-262`）。
- CMake 必须三行齐备（只写 `add_library` 会链接失败）：

```cmake
add_library(sena_bridge SHARED sena_bridge.cpp)
target_link_libraries(sena_bridge ${log-lib} ${dl-lib} ${android-lib})
target_compile_features(sena_bridge PRIVATE cxx_std_17)
```

- `engine/consumer-rules.pro` 追加 `-keep class com.core.sena.** { *; }`（app 侧 `-keep class com.core.**` 已兜底，仍建议补）。
- 可观测性：宿主统一 tag `SenaHost`，记录 create 结果、step 返回码、messagebox id、暂停/销毁、首帧超时与 generation 停滞；`SENA_DEBUG` 打开逐帧耗时与状态打印；不依赖上游 `--trace-*`（那是桌面 CLI 参数，FFI 不可用）。

### 3.4 运行库分发

| 改动 | 内容 |
| --- | --- |
| `engine/.../nativeplugin/NativeRuntimePack.kt` | 新增 `PACK_SENA = "sena"`（`libraryPath` 已有 stamp/只读校验，无需改结构） |
| `app/build.gradle.kts` | `bundledEngineRuntimePacks += "sena" to listOf("libsena.so")`（source/container 校验、压缩、`jniLibs.excludes` 全部自动推导） |
| `EngineLauncher.engineRuntimePacksFor` | `EngineType.SOFTPAL -> add(NativeRuntimePack.PACK_SENA)`（漏加会被 `else -> Unit` 静默跳过） |
| 源头 | `engine/src/main/jniLibs/arm64-v8a/libsena.so`，**只提交 strip 后的 `libsena.so`**；`libpal_vm.so`/`libwmv_decoder.so` 是中间产物，不得入库（不在 excludes 清单，会被当 `lib/` 打包） |

- M0 实测增量 6.09 MiB；APK 89.80 → **约 95.9 MiB**（§8）。
- 既有 `VerifyEngineRuntimePack` 只校验文件存在与 zip 非空，不做哈希/ELF 检查；哈希、ABI、16KB 与符号必须由新增的 `tools/sena/verify_provenance.py` 承担并在 CI 强制。
- 备份已整目录排除 `engine_runtime/`（`res/xml/backup_rules.xml:22`、`data_extraction_rules.xml:19`），无需改。

### 3.5 启动契约

`engine/src/main/java/com/core/engine/LaunchContract.kt`（**engine 模块**）新增：

| extra | 语义 |
| --- | --- |
| `SENA_NLS` | `sjis`(默认) / `gbk` / `utf8` |
| `SENA_LANG` | `jp`(默认) / `cn` / `tc`（不要沿用上游 Android 的 `CN` 默认值） |
| `SENA_INTEGER_SCALE` | bool，仅宿主呈现 |
| `SENA_DEBUG` | bool，日志/诊断 |
| `LAUNCH_MODE_SOFTPAL` | `internal.softpal` |

- `EngineLauncher.buildIntent`（`:1016-1109`）新增 `EngineType.SOFTPAL -> buildSenaIntent(...)`；`buildSenaIntent` 与 `buildFvpIntent` 同构，复用运行库 ensure 与 `needsAllFilesAccess`。
- 新增类型化失败 `LaunchResult.Failure.SenaGameRootUnwritable`（游戏根不可写）与 `SenaInitFailed(detail)`；同步 `ui/common/LaunchErrorMessages.kt` 与三语言 `strings.xml`（`LaunchResult.kt` 是穷尽 sealed，必须一并改）。

### 3.6 设置

- prefs 键（小写，与 FVP/XSYS 对齐）：`sena_nls` / `sena_lang` / `sena_integer_scale` / `sena_debug`；默认 `sjis` / `jp` / false / false。
- `EngineSettingsStore` + `EngineSettingsResolver.ResolvedEngineSettings`（`:318-397`）+ `EngineSettingsStore.resolve` 四处同步。
- 单游戏覆盖：`PerGameSettingsStore` 字段 + `GameOverridePartitions.TYRANO_KEYS`（`:165-197`）注册 `sena_*` 键（否则不识别），并加锚定测试。
- UI：`EngineSettingsMenuActivity.EngineSettingsKind` 加 `SOFTPAL`（`:111-127`，菜单自动出现），详情分块加在 `SettingsScreen.kt:702+`；单游戏在 `PerGameSettingsScreen.kt` 加同名三态项。
- 不做：音量/分辨率（无 ABI）。

### 3.7 存档

- `GameSaveManager.resolveSaveLocation` 增加 `EngineType.SOFTPAL -> SaveLocation(File(root,"save"), …, available = root.canWrite())`（照 FVP `:182-187`；`SaveLocation` 只有 directory/description/available/mirrorForced 四个字段，**没有 `inGameDirectory`**）。
- `supportsFileSaves`（`:777-781`）加入 `SOFTPAL`，从而获得导出/导入能力；**不新增独立存档开关**，在引擎设置与单游戏设置页写一句「不接管独立存档：存档由游戏目录 `save/` 承担」。
- 启动前置：游戏根可写探测，失败返回 `SenaGameRootUnwritable`。
- 存档同步：`supportsFileSaves` 会使其进入 WebDAV 同步候选（`SaveDirectoryIdentity` 依赖它）。M2 必须确认 `:sena` 被「宿主忙碌」探测覆盖（按进程后缀或 `EngineSessionRegistry`）；未覆盖前**不要开启该引擎的自动同步**，只在设置页允许手动导出/导入。
- 旧档兼容：上游读取还会看 `root/data/`、`存档/`、`补丁/存档/`、`patch/save/`（`runtime.rs:16342-16358`），portable 快照文件是 `*.sav`、不全是 `.dat`；只读不迁移，写入仍落 `save/`。M2 的导出/导入范围勿只按 `.dat` 过滤。

### 3.8 输入

- 装配点是 `InputRemapController.install(activity, container, gameId, theme, sink, engineDefaultsEnabled)`（`InputRemapController.kt:338-353`），Router 由其内部创建；非 Web 的现成先例是 **Ethornell**（`EthornellActivity.java:174-176`），**FVP 未接 InputSink**。
- `SenaInputSink implements InputSink`（`send/releaseAll/performAction`）：canonical 键 → 上游键名；`sena_host_key` 在 Android 头已声明，`sena_host_mouse_button` 需自声明 `dlsym`（两者均导出）。任一缺失时只启用触摸并记录降级。
- 虚拟按键/手柄复用 `VirtualPadView` 与默认方案；`releaseAll` 在 `onPause`/`onDestroy`/弹窗时调用。
- 兼容边界：无文本输入 ABI、无滚轮、无双指手势（触摸只发左键）。

### 3.9 图形

- 一期只做 `fit | integer` × `nearest | bilinear`，由 `SenaFrameView` 承担。
- 性能：1280×720×4≈3.7 MB/帧；按 60 fps 节流，复用 Bitmap 与 direct buffer；1080p 逻辑分辨率需另测内存峰值。
- 不接 Anime4K/VN-SR/CAS：现有滤镜链挂在 Artemis/KR 的 SurfaceTexture 路径，无法直接复用到 RGBA 帧泵；后续可评估 GLES 上传 + `GraphicsFilterPipeline`。

## 4. 里程碑与验收

| 阶段 | 内容 | 验收 | 预估 |
| --- | --- | --- | --- |
| M0 产物与门禁 | `cargo ndk -P 26 --locked` 全新建、strip；记录 commit/sha256/体积；`llvm-readelf -n` 确认 `note=26`、`-l` align=0x4000、`-d` NEEDED、`nm -D` 13 符号；写 `libsena.provenance.json` + `tools/sena/verify_provenance.py` | 二进制来源可核对；M1 可 dlopen | 1 d |
| M1 最小可跑 | 身份/扫描/分片/桥/宿主/契约/文案；真实 Koikake 目录先验扫描 | 标题→ADV；触摸点 START；≥30 fps；二次返回退出；无 ANR | 3–4 d |
| M2 设置/存档 | 4 项设置全局+单游戏；`GameOverridePartitions`；存档位置/可写预检；同步忙碌探测结论 | 切 NLS/LANG 正确；导出→导入→继续；同步开关行为符合结论 | 2 d |
| M3 打磨/门禁 | InputSink/虚拟按键；三语言；SPEC/持久化清单/CONTEXT/CHANGELOG；JVM 回归 + lint + 真机矩阵 | 3 机型冷启/后台/存档往返；`testDebugUnitTest` + `:app:lintDebug` + `check-hardcoded-ui-strings.py` 通过；既有引擎无回归 | 2 d |

JVM 回归（新增）：`EngineScannerSoftpalTest`（data/ 根/data.pac + UNKNOWN 边界）、`EngineSettingsResolver` 三级合并、`GameOverridePartitions` 键锚定。

## 5. 风险

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| MPL-2.0 分发 | 合规 | 固定 commit + `libsena.provenance.json`；`engine/src/main/assets/sena/LICENSE-MPL-2.0.txt` + `THIRD_PARTY_NOTICES`（kira MIT/Apache）；README 引擎条目；release notes 写源码获取方式 |
| API level | API 26 设备 | M0 已发现试构建是 API 28（`.note.android.ident=0x1c`）；必须 `-P 26 --locked` 重编并复验 |
| 16KB | Android 15+ | 试构建 align=0x4000；但 `useLegacyPackaging=true` 时 `zipalign -P 16` 只报压缩条目，不能证明；以 `readelf -l` 为准 |
| 扫描误判/漏判 | 入库失败 | 判据按真实 Koikake 目录验证；`SYSTEM.INI` 冲突用 Artemis 先行 + SoftPAL 标记护栏 |
| 帧泵性能 | 掉帧/发热 | 60 fps 节流、Bitmap 复用、direct buffer；1080p 另测 |
| 后台音频 | 退出后仍出声 | 一期 `onStop` 30 s 宽限后 destroy；长期要上游 ABI |
| 游戏根不可写 | 存档失败 | `SenaGameRootUnwritable` 类型化失败；只支持可写目录 |
| 体积 | 95.9 MiB | 见 §8 |
| 上游 ABI 漂移 | 重编回归 | 固定 commit、符号缺失即失败、verifier 门禁 |

## 6. 文件清单

**engine（新增）**
- `engine/src/main/cpp/sena_bridge.cpp`
- `engine/src/main/java/com/core/sena/{SenaActivity.java, SenaFrameView.java, NativeSena.java, SenaInputSink.java, SenaMessageBox.java}`
- `engine/src/main/jniLibs/arm64-v8a/libsena.so`（M0 产物）+ `libsena.provenance.json`
- `engine/src/main/assets/sena/{LICENSE-MPL-2.0.txt, THIRD_PARTY_NOTICES.txt}`

**engine（修改）**
- `engine/src/main/cpp/CMakeLists.txt`（add_library + link + cxx_std_17）
- `engine/src/main/java/com/core/engine/LaunchContract.kt`（extras + launchMode）
- `engine/src/main/java/com/core/nativeplugin/NativeRuntimePack.kt`（`PACK_SENA`）
- `engine/src/main/AndroidManifest.xml`、`res/values/styles.xml`（`Theme.Softpal`）
- `engine/src/main/res/values{,-ja,-en}/strings.xml`
- `engine/consumer-rules.pro`

**app（修改）**
- `core/engine/EngineType.kt`
- `core/game/scan/EngineScanner.kt` + `app/src/test/.../scan/EngineScannerSoftpalTest.kt`
- `core/game/launch/{EngineLauncher.kt, LaunchResult.kt}`
- `core/engine/plugin/EnginePluginBootstrap.kt`（**穷尽 when，必须加 `SOFTPAL -> return null`**）
- `core/settings/{EngineSettingsStore.kt, EngineSettingsResolver.kt, PerGameSettingsStore.kt}`
- `core/game/storage/GameOverridePartitions.kt`（`sena_*` 键）
- `core/game/save/GameSaveManager.kt`
- `ui/settings/{EngineSettingsMenuActivity.kt, SettingsScreen.kt, PerGameSettingsScreen.kt}`
- `ui/engine/EngineScreen.kt`（`engineIconRes` / `engineTabOf` / `supportedEngines` 消费点）+ `app/src/main/res/drawable-nodpi/ic_engine_card_softpal.png`
- `ui/game/GameScreen.kt`（`coverColor()` 穷尽 when）
- `ui/common/LaunchErrorMessages.kt` + `app/src/main/res/values{,-ja,-en}/strings.xml`
- `app/build.gradle.kts`（`bundledEngineRuntimePacks`）

**docs / CI**
- 本文、`SPEC.md`（引擎表、存档边界）、`docs/应用持久化数据清单.md`（运行库分片、`<游戏根>/save`）、`CONTEXT.md`（内置引擎表）、`CHANGELOG.md`、`README.md`（引擎表 + 许可）
- `tools/sena/verify_provenance.py`、`.github/workflows/android-beta-release.yml`（sena 产物校验步骤）

## 7. M0 试构建记录（2026-11-05）

**当前只是试构建，不作为最终产物**：

```bash
cd /Volumes/HUAWEI-M2LITE/app/sena-rs
ANDROID_NDK_HOME=$HOME/Library/Android/sdk/ndk/28.0.13004108 CARGO_NDK_PLATFORM=28 \
  cargo ndk -t arm64-v8a -o /tmp/sena-jni build -p pal-vm --lib --release
# libpal_vm.so → 复制为 libsena.so → llvm-strip --strip-unneeded
```

| 项 | 实测 |
| --- | --- |
| 上游 HEAD | `7f633dbb89408297827d6c1857eb8e97dc363dff`（clean） |
| 未 strip / strip 后 | 24.65 MB / **20.20 MB** |
| xz -9 / -9e | 6.08 MiB / **6.09 MiB** |
| `libsena.so` sha256 | `f7250680dcb0d850fdea78f4242e6c32a638c795075be7aeda3d77499c58feb5` |
| 16KB | 4 个 LOAD align = `0x4000` |
| NEEDED | `liblog/libdl/libandroid/libaaudio/libm/libc`（纯系统库） |
| 符号 | 13 个 `sena_android_*` 全导出；`sena_host_key`/`sena_host_mouse_button`/`sena_free_c_string` 亦可选 |
| **待修** | `.note.android.ident = 0x1c`（API 28，项目 minSdk 26）；构建缺 `--locked` 且复用了旧 target |

**M0 收尾要求**：用 `cargo ndk -P 26 --locked` 全新 target 重建，复验 `note=26`、符号、NEEDED、align 与哈希，写入 `libsena.provenance.json` 后再进入 M1。

## 8. 待决：90 MiB 预算

`89.80 + 6.09 ≈ 95.9 MiB`，与既有「release < 90 MiB」目标冲突。已确认接受该增量，但需在 M1 前明确 90 是否仍是硬门禁：

1. **接受约 96 MiB**（SoftPAL 随包，离线可用）；
2. **仅 SoftPAL 首用下载**（基座维持 89.8 MiB，需新增下载/校验/托管）；
3. **另减约 6 MiB 其它运行时**（牺牲既有兼容，不推荐）。
