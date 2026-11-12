# CMVS（cmvs-rs）引擎接入方案

> 状态：**已实施**（2026-11-08；Release 92.57 MiB，MAA-AN10/API 36 实机验收；见文末「实施与验收记录」）。
> 被接入方：`/Volumes/HUAWEI-M2LITE/app/cmvs-rs`（`cmvs-rs` 的本地 fork 同步副本，仅同步上游、不提交修改；Purple SOFTWARE CMVS 引擎的 Rust 重实现，MIT OR Apache-2.0，基线 `c073414b`）。文中「上游」指该 `cmvs-rs` 仓库；TyranorNext 引用给相对路径。
>
> 已确认决策：**采用方案 A（NativeActivity + loader）**；**接受仅支持 19 部登记作品**；**字体由用户自选**；**90 MiB 不作硬门禁**；**fork 仅同步上游、不提交修改，patch 只在构建时应用**；**许可只注明来自 fork 仓库**。

## 1. 结论先行

1. **方案 A：`CmvsActivity extends NativeActivity` + `libcmvs_loader.so`**。APK `lib/` 只放 loader（约 20 KB），真实 `libcmvs_android.so`（strip 8.87 MiB / xz 2.50 MiB）走 `NativeRuntimePack` 分片；loader 的 `ANativeActivity_onCreate` 用固定库名回调 Java 拿解压路径，`dlopen` 后转发（模板 `engine/src/main/cpp/artemis_loader.cpp:223-271`）。
2. **必须给上游源码打一个构建时 patch（不修改 fork 仓库）**：`app.rs:323-350` 只接受 `internalDataPath` 下 ASCII 相对路径（`platform.rs:34-64`），且 user 目录写死 `files/users/<game>`。patch 让 `launch.cfg` 支持 `game=`/`user=` 绝对路径并替换该写死推导；否则只能把整部游戏复制进私有目录。fork 仅同步上游，patch 只作为 TyranorNext 构建输入。
3. **19 部登记作品是硬边界**（`crates/cmvs-core/profiles/games.txt` 19 条 game / 18 个 profile；未命中 `UnknownGame/UnknownBinary/MissingFile/AmbiguousStartScript`）。扫描只表示「CMVS 家族」，能否运行由启动预检/上游识别决定；未登记由运行期 `code=unregistered` 明示（不是启动前 `LaunchResult`）。
4. **存档天然独立**：user 目录由 App 指定为 `files/cmvs/users/<hash>`（`CmvsPaths` 单一事实源），上游要求它在游戏根之外；`GameSaveManager` 按 ETHORNELL 的「when 之前」写法接入。
5. **字体必须用户提供**：launch.cfg 的 `font` 是相对 `filesDir` 的字体文件，第三行是**集合索引**（默认 0）；没有内置默认，缺失即 `CmvsFontUnavailable`。
6. **渲染 wgpu + 硬编码 Vulkan**（`app.rs:73-110`，workspace 无 `gles`）：无 Vulkan 设备直接失败；启动前 `FEATURE_VULKAN_HARDWARE_LEVEL` 预检，M3 评估 gles patch。
7. **输入只有触摸**：Android 后端只处理 MotionEvent，`KeyEvent` 不处理（`app.rs:272-314`）。M1 不做虚拟按键/手柄；M3 再评估覆盖层或输入桥。
8. **失败回传要自己接**：上游只写 `filesDir/launch-error.txt` 后返回，无 Java 回调；宿主需「App 预检 + Activity 启动后 watchdog」两段式，否则未登记/Vulkan/音频失败都是黑屏。
9. **体积与许可**：90 MiB 已确认不作为硬门禁；CMVS 约 92.3 MiB（+2.50 MiB），叠加 SoftPAL 约 98.4 MiB。许可不做额外论证：README/许可证仅注明运行库来自 fork 仓库（上游 `cmvs-rs`，MIT OR Apache-2.0，固定 commit + 构建时 patch）。

## 2. 上游事实（实现对照）

| 维度 | 事实 | 证据（上游） |
| --- | --- | --- |
| 入口 | `android_main` → `app::run`；`android-activity 0.6.1` 的 `native-activity`；失败写 `filesDir/launch-error.txt` | `platforms/android/native/src/lib.rs:11-31` |
| 配置 | `filesDir/launch.cfg` 三行：游戏相对路径、字体相对路径、**集合索引**；路径 ASCII 相对且 canonicalize 后仍在 filesDir | `platforms/android/native/src/app.rs:323-350`、`crates/cmvs-core/src/platform.rs:34-64` |
| 宿主要求 | 游戏根含 `key.cfg`（`read_to_string`，UTF-8；Shift-JIS 会失败）；`users/<game>` 可写；`NativeAudio::new` 失败即终止 | `app.rs:350-376` |
| 渲染 | wgpu 30，`Backends::VULKAN`；无 GLES/CPU 回退；无 Vulkan 失败在 `request_adapter` | `app.rs:73-110`、`Cargo.toml:20` |
| 事件 | Resume/Pause/Stop/Focus/Resize/InputAvailable/RedrawNeeded/Destroy；Pause 与 Destroy `persist()`，正常 Quit 也 `persist()` | `app.rs:230-271,433` |
| 输入 | 仅 MotionEvent（单主触点）；KeyEvent 不处理；无文本输入（0x213c 默认错误） | `app.rs:272-314`、`platform.rs:185-190` |
| 字体 | 启动需字体文件 + 索引；游戏内 0x213d 走 JNI `chooseFont()V`，结果写 `filesDir/font-result.cfg` | `app.rs:334-339`、`crates/cmvs-native/src/android/mod.rs:43-70` |
| 存档 | user 目录内 `saveNNN.dat`、`system.dat/.bak`、`cmvs.cfg(.bak)`、`.cmvs-system.lock`；游戏根只读 | `crates/cmvs-core/src/save_menu.rs:99`、`system_save/profile.rs:24-57` |
| 识别 | 必需 `data/pack/script.cpz` + `start.ps3|ps2`；`cmvs.cfg` 可选 | `crates/cmvs-core/src/profile/select.rs:13-14,58-75` |
| 登记 | `crates/cmvs-core/profiles/games.txt` 19 条 game / 18 profile | 同上 |
| 许可 | MIT OR Apache-2.0；来源为 fork 仓库（仅同步上游，不提交修改）；构建时应用绝对路径 patch；README 注明来源。**注意：上游 `THIRD_PARTY_NOTICES.txt` 未列 Android 侧 `jni`/`ndk`/`android-activity`/`ndk-context`/`android_logger` 等依赖**，发布前需补全（MIT/Apache 自身的署名义务，与 GPL 兼容性论证无关） | `Cargo.toml:7`、`THIRD_PARTY_NOTICES.txt` |

## 3. 接入设计

### 3.1 引擎身份与扫描

- `EngineType.CMVS("CMVS", "CMVS")`（`EngineType.kt` UNKNOWN 前）；`supportedEngines` 追加（`EngineLauncher.kt:128-156`）。
- `launchMode = "internal.cmvs"`；不做子版本。
- 扫描器是**单条 if 链 + 固定 confidence、首命中返回**、`lowercase()`、只递归白名单（`EngineScanner.kt:1092,1135,1508-1521`）。
  **坑：`data` 在白名单但 `pack` 不在**；需在 `rel.isEmpty() && lower=="data"` 分支里额外下钻 `data/pack` 的直接文件（照 `pac`/`gamedata` 的写法，勿改全局白名单、勿在分支里提前 return）。
- 判据（XSYS4 之后、UNKNOWN 之前；`launchTarget = DIR`）：
  - 强：`data/pack/script.cpz` + 唯一 `start.ps3|ps2`（头 `PS2A`）→ 96；
  - 中：同上但未读头 → 92；
  - 弱：根 `cmvs.exe|cmvs32.exe|cmvs64.exe` → 82。
- `key.cfg` 不作识别条件；缺失/双 start 由预检给类型化失败。

### 3.2 进程与宿主

```xml
<activity android:name="com.core.cmvs.CmvsActivity" android:exported="false"
    android:process=":cmvs" android:launchMode="singleInstance"
    android:taskAffinity="com.core.Cmvs" android:screenOrientation="sensorLandscape"
    android:theme="@style/Theme.Cmvs" android:windowSoftInputMode="adjustNothing"
    android:configChanges="keyboard|keyboardHidden|navigation|orientation|layoutDirection|screenLayout|screenSize|smallestScreenSize|uiMode|density|fontScale|locale">
    <meta-data android:name="android.app.lib_name" android:value="cmvs_loader"/>
</activity>
```

- `Theme.Cmvs`：用 framework `@android:style/Theme.Material.NoActionBar`（NativeActivity 不需要 AppCompat delegate），补 `windowBackground=black`、`windowNoTitle`；不要依赖 `AppCompatActivity` 的 delegate。
- **launch.cfg 固定 `filesDir/launch.cfg`（顶层）**：上游只读这里；只由 App 进程在 `startActivity` 前用 `launch.tmp` + rename 原子写一份，Activity/loader 只校验。写前先查 `:cmvs` 是否存活：存活则返回 `CmvsAlreadyRunning`，不覆盖配置；切换游戏必须由用户先正常退出，**禁止冷杀旧进程**（会丢未落盘状态）。
- `onCreate`：读取并校验 `launch.cfg` 与库路径（`NativeRuntimePack.libraryPath(context, PACK_CMVS, "libcmvs_android.so")`），通过 `CmvsActivity.shouldStartCmvsNative()` 暴露给 loader，**不在此 finish** → `super.onCreate`（native 在 super 内加载）→ loader 依标志决定 `dlopen` 或 `ANativeActivity_finish` → 沉浸式全屏。任何情况下都先调用 `super`（super 前 `finish()` 会抛 `SuperNotCalledException`）；沉浸式全屏在 `onResume`/`onWindowFocusChanged` 重复应用，避免系统栏回弹。
- **返回键**：实机验证发现 NativeActivity 的 native input queue 会先消费硬件按键，Java `dispatchKeyEvent`/`onBackPressed` 收不到；已在构建期 patch 的 `input_event` 中把 `Keycode::Back` 映射为「persist + exit」（单次返回即退出）。`onDestroy` 仍保留杀进程兜底。
- **退出**：返回键由构建期 patch 触发 `persist` 后退出；`onDestroy` 中先 `super.onDestroy()`，再在 `finally` 里 `Process.killProcess(Process.myPid())`（照 Artemis `:120-135`）——wgpu/音频/`android_main` 是进程级状态，不杀进程二次启动会黑屏。
- `onNewIntent`：不同游戏只提示/拒绝，不热切换、不覆盖 `launch.cfg`；Activity 无法把结果回传 App，`CmvsAlreadyRunning` 由 App 启动前进程检查产生，竞态下只保证不切换。
- **启动失败监测**：启动前删除旧 `launch-error.txt`；`onCreate` 开头启动后台 watchdog（先于 `super`，以便捕获 loader 失败），持续轮询 30 s（慢设备/延迟失败），读取 `code=`；命中则回主线程 Toast + `finish()` + 杀进程。无文件且超时只记录诊断，不把慢启动误判为失败；不要用 3 s 单次检查，延迟失败会漏。

### 3.3 loader 与 JNI

- `engine/src/main/cpp/cmvs_loader.cpp`（模板 `artemis_loader.cpp:223-271`）：
  - 固定库名 `libcmvs_android.so`（不信任 Intent 任意值），`CallObjectMethod` 调 `CmvsActivity.resolveCmvsEngineLibraryPath()` 拿路径并校验落在 `files/engine_runtime/cmvs/`；
  - `dlopen(RTLD_NOW|RTLD_GLOBAL)` → `dlsym("ANativeActivity_onCreate")` → 在 Java 主线程**同步**转发；
  - 自己**不**安装 `activity->callbacks`、不 `dlclose`、不链接第二份 `android-activity`（真实库会覆盖 onResume/onPause/onDestroy/onNativeWindowCreated/onInputQueueCreated 等）；
  - `dlopen` 前先调用 `CmvsActivity.shouldStartCmvsNative()`；false 时写 `code=preflight` 并 `ANativeActivity_finish`，不加载引擎；
  - 失败写 `filesDir/launch-error.txt`（含 `code=library|preflight`）并 `ANativeActivity_finish`。
- `CmvsActivity`（NativeActivity 子类）：
  - `@Keep public synchronized String resolveCmvsEngineLibraryPath()`：缓存结果，`libraryPath` 取不到返回 null；
  - `@Keep public boolean shouldStartCmvsNative()`：本次 `onCreate` 预检/配置校验是否通过；loader 在任何 `dlopen` 前读取；
  - `@Keep public void chooseFont()`：`runOnUiThread`，`choosingFont`/`isFinishing`/`isDestroyed` 守卫；`startActivityForResult` 选字体 → 复制 `files/fonts/`（后台线程，取消/失败回写 `cancel`）→ 用 `KrDialogStyle` 数字框输入**集合索引**（0..1024）→ 原子写 `files/font-result.cfg`（两行：相对字体路径 + 索引；取消写 `cancel`）。
- `engine/consumer-rules.pro` 加 `-keep class com.core.cmvs.** { *; }`。

### 3.4 上游源码 patch（M0，构建时应用）

`launch.cfg` 增加 `key=value` 形式（兼容原三行）：

```
game=/storage/emulated/0/Games/xxx
user=/data/user/0/<pkg>/files/cmvs/users/<hash>
font=fonts/cmvs-<hash>.ttf
face=0
```

- `game`/`user` 为绝对路径时 `canonicalize` 后直接使用（校验存在、目录、user 不在 game 内）；`font` 仍 `resolve_under(directory, ...)`。
- **必须同时替换 `app.rs:350` 的 `directory.join("users").join(record.game)`**，否则 `user=` 无效；`key.cfg` 仍从 `game` 根读取。
- 解析器用「全键存在才走新格式」或版本头区分，避免把旧三行误判；绝对路径允许非 ASCII（与旧相对路径的 ASCII 限制分开）。
- 错误回传：`launch-error.txt` 增加 `code=` 行（如 `unregistered`/`layout`/`audio`/`vulkan`），宿主 watchdog 按 `code` 映射提示。
- 返回键：`input_event` 中把 `KeyAction::Down + Keycode::Back` 映射为「persist + exit」，规避 NativeActivity native input queue 不把按键交给 Java 的问题。
- 保持旧三行可解析（桌面/上游壳不受影响）；fork 仓库仅同步上游、**不提交任何修改**；绝对路径 patch 作为 TyranorNext 构建输入放 `tools/cmvs/patches/0001-absolute-launch-paths.patch`，provenance 记 `dirty=true` + patch sha256。若完全禁止构建时 patch，则绝对路径不可得，只剩复制游戏到私有目录（已否决）或方案 B，需要重新决策。
- 可选（M3）：wgpu 加 `gles` feature + `Backends::VULKAN | GL`。

### 3.5 失败模型

- **App 预检（启动前，类型化）**：游戏根可解析、`data/pack/script.cpz` 与唯一 start、`key.cfg` 可读（UTF-8）、字体文件存在、user 目录可建、`FEATURE_VULKAN_HARDWARE_LEVEL`。
- **宿主 watchdog（onCreate 开头启动，覆盖 super 内 loader 失败）**：启动前删除旧文件；30 s 内轮询 `launch-error.txt`，出现即读取 `code=` → Toast（engine 字符串）→ `finish()` + 杀进程；无文件超时只记诊断，不误判慢启动。
- **启动前 `LaunchResult`（App 预检）**：`CmvsGameLayoutInvalid`、`CmvsUserDataDirUnavailable`、`CmvsFontUnavailable`、`CmvsVulkanUnavailable`、`CmvsAlreadyRunning`；`EngineLibraryPackFailed` 复用。
- **运行期提示（不是 `LaunchResult`）**：`unregistered`/`library`/`preflight`/`audio`/`vulkan`/`unknown` 等 `code=` 由 `CmvsActivity` 映射 engine 字符串并 Toast。`CmvsUnregisteredGame`/`CmvsInitFailed` 不放 `LaunchResult`，除非 App 侧另建 19 部注册表预检；当前方案明确不做。

### 3.6 运行库分发

| 改动 | 内容 |
| --- | --- |
| `NativeRuntimePack.kt` | `PACK_CMVS = "cmvs"` |
| `app/build.gradle.kts` | `"cmvs" to listOf("libcmvs_android.so")`（源校验/压缩/excludes 自动） |
| `EngineLauncher.engineRuntimePacksFor` | `EngineType.CMVS -> add(PACK_CMVS)` |
| `lib/` | `libcmvs_loader.so`（CMake，`lib_name` 指向它；16KB 对齐用 `readelf -l` 断言） |
| 源头 | `engine/src/main/jniLibs/arm64-v8a/libcmvs_android.so`（strip 后 + provenance） |

- 实测 strip 8.87 MiB、xz 2.50 MiB；APK 89.80 → **≈92.3 MiB**。
- 既有 `VerifyEngineRuntimePack` 只查存在/非空；哈希、API、16KB、符号由 `tools/cmvs/verify_provenance.py` 负责并进 CI。

### 3.7 启动契约

| extra | 语义 |
| --- | --- |
| `CMVS_USER_DATA_DIR` | `CmvsPaths.userDataDir(filesDir, game.uri)`（App 写入 launch.cfg） |
| `CMVS_FONT_PATH` | App 侧绝对路径；写 cfg 前转相对 `filesDir` 的 `fonts/…`；缺失 `CmvsFontUnavailable` |
| `CMVS_FONT_FACE` | 集合索引，默认 0 |
| `CMVS_DEBUG` | 可选诊断 |
| `LAUNCH_MODE_CMVS` | `internal.cmvs` |

- `buildIntent` 加 `EngineType.CMVS -> buildCmvsIntent(...)`（`EngineLauncher.kt:1006-1124`）；App 在 `startActivity` 前写 `filesDir/launch.cfg`（四行）。
- 复用 `PATH/GAME_PATH/PROJECT_ROOT/GAME_DIR/ROOT_URI/LAUNCH_TARGET` 与运行库 ensure。

### 3.8 设置

- 键：`cmvs_font`（默认 `""`）、`cmvs_font_face`（字体集合索引，默认 0，普通 TTF 为 0）；`EngineSettingsStore`/`EngineSettingsResolver`/`PerGameSettingsStore`/`GameOverridePartitions.TYRANO_KEYS`。
- UI：`EngineSettingsKind` 加 `CMVS`，并在 `SettingsScreen.kt:719-725` 加**独立屏分支**（否则设置页空白）；字体选择照 `EthornellSettingsScreen.kt:52-63` 的 `GetContent` + `FontImport.importToPrivate`；单游戏在 `PerGameSettingsScreen` 穷尽 when 加分支。
- 不做：编码（固定 Shift-JIS）、分辨率/全屏（游戏内 `cmvs.cfg`）、改键（游戏根 `key.cfg`）。

### 3.9 存档

- 新增 `CmvsPaths.userDataDir(filesDir, game.uri)` 作为唯一事实源（同 `EthornellPaths`），`EngineLauncher`/`GameSaveManager`/删除清理共用。
- `GameSaveManager.resolveSaveLocation` 在 `resolveGameDirectory`/`when` **之前**提前返回 CMVS（照 ETHORNELL `:44-86`），游戏目录不可解析时仍可用私有存档；`available = dir.isDirectory || dir.mkdirs()`，路径经 `CmvsPaths`/`SavePathSafety` 校验。
- `supportsFileSaves` 加 `CMVS`；`excludeFor` **显式**排除 `.cmvs-system.lock`、`.cmvs-system-*.tmp`（现有 transient 规则只认 sync_tmp/fmt_tmp）。
- 删除游戏时只清理该游戏的 `CmvsPaths.userDataDir(...)`（`GameSaveManager.kt:525-591` 的 else 分支当前直接返回）；不得清 `files/cmvs/users/*` 通配目录。
- **运行中禁止导入/整目录覆盖**：手动存档管理与 WebDAV 都必须检查 `:cmvs`（manifest 声明后 `SaveSyncRuntime` 自动覆盖宿主探测；手动路径还要加门禁，不能只靠自动同步）。
- 游戏根只读无影响；旧 PC 存档同格式可 ZIP 导入，槽位元数据 P2 再验。

### 3.10 输入

- Android 后端只有 MotionEvent（单主触点，letterbox 外忽略）；无游戏内物理键/手柄/虚拟按键；`InputRemapController` 不装配。
- M1 只做原生触摸 + 返回键（`dispatchKeyEvent` + `DoubleBackExit`）；M3 二选一：Java `addContentView` 覆盖层验证 + Rust 输入注入，或转方案 B。
- 无文本输入 ABI：0x213c 默认报错并终止整局，M1 需在样本上确认不触发。

### 3.11 图形

- wgpu Vulkan；Surface 来自 `AndroidApp.native_window()`，不经 Java/GLES。
- 不接现有 GLES 滤镜链（`buildIntent` 自动 `putDisabledGraphicsRequest()`）。
- 无 Vulkan → `CmvsVulkanUnavailable`；M3 评估 gles patch。

### 3.12 生命周期

- Pause：静音 + `persist()`；Stop：静音不落盘；Resume：只切 `resumed/focused`，**VM 常驻、不重载**；只有进程被杀后重启才重跑 `start.ps3`；正常 Quit 也 `persist()`。
- `persist()` 失败会结束游戏（`app.rs:241-268`）；音频设备打开失败即整局失败。
- 退出后杀进程；无 SaveState。

## 4. 里程碑与验收

| 阶段 | 内容 | 验收 | 预估 |
| --- | --- | --- | --- |
| M0 | 上游源码构建时 patch（路径 + 错误码）；固定 commit 重建/strip；loader 编译；provenance + notices | patch 后 `.so` 记录 sha256/符号/16KB/API note=26（API 下限 M1 前另拍板）；loader 编译通过，转发 smoke 归 M1；patch 可 rebase | 1 d |
| M1 | 身份/扫描/分片/loader/宿主/launch.cfg/预检/watchdog；登记样本到标题 | 触摸进标题、≥30 fps、三键/手势返回、无 ANR；杀进程后二次启动、私有目录 dlopen、无 Vulkan 负例、未登记 `code=unregistered` | 3–4 d |
| M2 | 字体/索引设置；存档位置/导出导入/删除清理/运行中门禁；失败三语言 | 导→入→继续；换字体生效；运行中导入被拒；字体取消/超时；未登记提示 | 2 d |
| M3 | 输入覆盖层/手柄（或转 B）、gles 评估、机型矩阵、docs（ARCHITECTURE/持久化清单/ADR 0002）、JVM + `:app:lintDebug` + `:engine:lintDebug` | 3 机型冷启/后台/存档；16KB/API 下限/进程重建/Vulkan 缺失矩阵；回归通过；既有引擎无回归 | 2–3 d |

JVM 回归：`EngineScannerCmvsTest`、launch.cfg 解析/`CmvsPaths` 哈希、`CmvsPreflight` 布局判定、设置三级合并、`GameOverridePartitions` 键锚定、watchdog `code=`→文案映射。

验收口径：M1 帧率用 `dumpsys gfxinfo`/SurfaceFlinger 采样 60 s；ANR 以 logcat/系统 ANR 记录为准；M0 的 API note/16KB 是静态 ELF 检查，不等于运行验收。

## 5. 风险

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 上游 Android 未真机验证（`docs/status.md:23`） | 高 | M0 静态 ELF 检查；M1 起真机 dlopen/全流程 |
| 仅 19 部登记作品 | 高 | 已接受；运行期 `code=unregistered` + UI 明示 |
| 构建时 patch 维护 | 中 | 单文件最小 patch + `dirty=true` provenance；fork 不提交修改，每次同步重放 |
| Vulkan-only | 中高 | 启动预检；M3 gles patch |
| NativeActivity + 私有目录 dlopen | 中 | Artemis 先例；三机型 smoke；API 26/29 下限待拍板 |
| 返回键/进程级状态 | 中 | `dispatchKeyEvent` + 杀进程；API36 手势验证 |
| launch.cfg 与 `singleInstance` 复用 | 中 | App 写前查 `:cmvs`；拒绝返回 `CmvsAlreadyRunning`；不冷杀旧进程 |
| 失败黑屏（无 Java 回调） | 中 | App 预检 + watchdog + patch 错误码 |
| 无虚拟按键/手柄 | 中 | M1 触摸；M3 覆盖层或转 B |
| 字体必须用户提供 | 中 | 复用 Ethornell 选择器；缺失类型化 |
| `key.cfg` UTF-8 读取（Shift-JIS 会失败） | 中 | M1 样本验证；不支持则启动预检类型化失败并记录，不静默黑屏 |
| 体积：单引擎 92.3；叠加 SoftPAL 98.4 | 低 | 90 MiB 已确认不作硬门禁，记录累计体积即可 |
| notices 不完整（Android 依赖缺失） | 低 | 发布前补全 `THIRD_PARTY_NOTICES` + MIT/Apache 全文；与许可立场无关，是 Apache/MIT 的署名义务 |

## 6. 文件清单

**engine（新增）**
- `engine/src/main/cpp/cmvs_loader.cpp`
- `engine/src/main/java/com/core/cmvs/CmvsActivity.kt`（launch.cfg 校验/token/字体选择）
- `engine/src/main/jniLibs/arm64-v8a/libcmvs_android.so` + `libcmvs_android.provenance.json`
- `engine/src/main/assets/cmvs/{LICENSE-MIT, LICENSE-APACHE, THIRD_PARTY_NOTICES.txt}`（原样保留上游许可文件；另补一份覆盖 Android 依赖的 notices 增量）

**engine（修改）**
- `engine/src/main/cpp/CMakeLists.txt`
- `engine/src/main/java/com/core/engine/LaunchContract.kt`
- `engine/src/main/java/com/core/nativeplugin/NativeRuntimePack.kt`
- `engine/src/main/AndroidManifest.xml`、`res/values/styles.xml`（`Theme.Cmvs`）
- `engine/src/main/res/values{,-ja,-en}/strings.xml`、`engine/consumer-rules.pro`

**app（修改）**
- `core/engine/EngineType.kt`
- `core/game/scan/EngineScanner.kt` + `app/src/test/.../scan/EngineScannerCmvsTest.kt`
- `core/game/launch/{EngineLauncher.kt, LaunchResult.kt}`
- `core/engine/plugin/EnginePluginBootstrap.kt`（`CMVS -> return null`）
- `core/settings/{EngineSettingsStore.kt, EngineSettingsResolver.kt, PerGameSettingsStore.kt}`
- `core/game/storage/GameOverridePartitions.kt`
- `core/game/launch/CmvsLaunchFiles.kt`（新：launch.cfg 原子写/严格预检/token）
- `core/game/launch/LaunchFailureBus.kt`（新：宿主失败跨页面回传）
- `core/game/model/CmvsPaths.kt`（新，`userDataDir` 单一事实源）


- `core/game/save/GameSaveManager.kt`（位置 + 删除清理 + 运行中门禁）
- `ui/settings/{EngineSettingsMenuActivity.kt, SettingsScreen.kt（kind→独立屏）, PerGameSettingsScreen.kt}`
- `ui/engine/EngineScreen.kt`（`engineIconRes`/`engineTabOf`/`builtinDialogEntries`/`dialogOnlyEngines`）+ `res/drawable-nodpi/ic_engine_card_cmvs.png`
- `ui/game/GameScreen.kt`（`coverColor`）
- `ui/common/LaunchErrorMessages.kt`、`app/src/main/res/values{,-ja,-en}/strings.xml`
- `app/build.gradle.kts`

**docs/CI**
- 本文、`SPEC.md`、`CONTEXT.md`、`ARCHITECTURE.md`、`docs/应用持久化数据清单.md`、`CHANGELOG.md`、`README.md`
- `README.md` 许可/致谢仅注明：CMVS 运行库来自 fork 仓库（上游 `cmvs-rs`，MIT OR Apache-2.0；仅同步上游，构建时 patch）
- 持久化清单补：`files/launch.cfg`、`files/font-result.cfg`、`files/fonts/`、`files/cmvs/users/`、`files/engine_runtime/cmvs/`、`files/launch-error.txt`
- `docs/adr/0002-prebuilt-engine-libs-source-in-fork.md`（provenance/源码位置按该 ADR 记录）
- `tools/cmvs/{build_android.sh, verify_provenance.py, patches/0001-absolute-launch-paths.patch}` + release workflow 校验

## 7. M0 试构建记录（2026-11-08，pre-patch）

```bash
cd /Volumes/HUAWEI-M2LITE/app/cmvs-rs
cargo ndk -t arm64-v8a --platform 26 -o /tmp/cmvs-jni build --locked --release -p cmvs-android
llvm-strip --strip-unneeded /tmp/cmvs-jni/arm64-v8a/libcmvs_android.so
```

| 项 | 实测 |
| --- | --- |
| 未 strip / strip 后 | 13,009,768 B / **9,305,896 B（8.87 MiB）** |
| xz -9e | **2,618,792 B（2.50 MiB）** |
| sha256（strip 副本） | `187a6dc0…`（`/tmp/libcmvs_android.so`；未 strip 在 `/tmp/cmvs-jni/arm64-v8a/`） |
| `.note.android.ident` | 26（`0x1a`） |
| LOAD align | `0x4000` ×4（16 KB） |
| 符号 | `ANativeActivity_onCreate`、`android_main` |
| NEEDED | liblog/libdl/libandroid/libaaudio/libm/libc |

**注意**：patch 前产物；M0 必须在 patch 后重建并重记 sha256。上表 `xz -9e` 是本地测量，APK 实际用 `CompressXzArchive preset=9`；M0 以构建产物重新记录大小、preset 与命令行。`--platform 26` 只改 ELF note，不等于 API 26 运行验证（上游壳 `minSdk=29`）。

## 8. 已确认决策与待决

已确认（2026-11-08）：方案 A；19 部边界可接受；字体用户自选；**90 MiB 不作硬门禁**（CMVS ≈92.3 MiB，叠加 SoftPAL ≈98.4 MiB）；**fork 仓库仅同步上游、不提交修改**，绝对路径 patch 仅作为 TyranorNext 构建输入；许可不另做论证，只在 README/许可证注明来自 fork 仓库。

**M1 准入**：先完成返回键（`dispatchKeyEvent`）、`onDestroy` 杀进程、失败回传/watchdog 三项，并固定 launch.cfg 唯一写入方与顶层路径；API 下限在 M1 前拍板，预算与许可已确认。

待决：

1. **API 下限**：链接产物是 API 26，但上游 Android 壳 `minSdk=29`、无 API 26 真机证据；M0/M1 补 API 26 smoke 维持 26，或在 M1 前把 CMVS 下限提到 29 并加类型化门禁。

## 9. 实施与验收记录（2026-11-08）

- 产物：`engine/src/main/jniLibs/arm64-v8a/libcmvs_android.so`（strip 后 9,318,864 B；xz 2,620,532 B），provenance 含 commit `c073414b`、patch sha256、API 26、16KB、符号清单。
- 构建：上游默认特性，patch 仅构建期应用（绝对路径 + 错误码分类 + 返回键），引擎仓库保持 clean；`tools/cmvs/build_android.sh` 与 `verify_provenance.py` 已接入 `android-beta-release.yml`。
- Release：`assembleRelease` 通过，APK 92.57 MiB；debug/release 均在 MAA-AN10（API 36、arm64、Adreno 740）安装运行。
- 实机：扫描 `galgame/REALIVE` 识别 CMVS；标题画面渲染；系统设置/鉴赏/读档/退出确认可交互；返回键退出并写 `system.dat`/`cmvs.cfg` 到 `files/cmvs/users/<hash>/`；R8 release 同样可启动。
- 失败反馈：未登记作品时 `launch-error.txt` 写入 `code=unregistered`，App 侧 `LaunchFailureBus` 在游戏页弹出「CMVS 仅支持 19 部登记作品」类型化对话框。
- 存档门禁：`GameSaveManager` 手动导入/导出/删除在 `:cmvs` 存活时抛 `SAVE_DIR_IN_USE`；删除游戏清理 `files/cmvs/users/<hash>`。
- 测试样本说明：本地 REALIVE 为 CN 1.03，`start.ps3`/`cmvs32.exe` 不在登记表；实测时把 `cmvs32.exe`/`cmvs64.exe` 移至 `/sdcard/realive_exe_backup/` 走 data-only 识别。未移动时 App 正确提示「未登记」。
- 未验证：游戏内 `chooseFont`（0x213d）真机流程；`.ttc` 面索引（已按死配置清理，face 固定 0）；存档导出/导入 UI 点验；升级覆盖安装路径；虚拟按键/手柄（Android 后端仅触摸）。
