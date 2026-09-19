# FVPEngine（rfvp）内置引擎接入方案

> 状态：**已实施（M0–M3 完成，真机冒烟通过）**；完整真机验收（§9.2 全量）待游戏数据落机后执行，见文末「实施记录」。
> 关联上游：`xmoezzz/rfvp`（MPL-2.0，FVP 引擎的 Rust 跨平台重写）；本地勘察基线 `304e773`（"version bump"）
> 结论摘要：**内置 host 路线**（engine 模块新增 `com.core.fvp` 宿主）+ **jniLibs 直打包 `librfvp.so`**；
> 上游已提供完整 Android host-driven C ABI（`rfvp_android_*`，SurfaceView + Choreographer 驱动），
> 「运行时」接入面与 Siglus（siglus_rs）同构；「引擎库」`librfvp.so` 实测 arm64 约 38.7 MiB（strip 后）。
> 一期范围 = 扫描识别 + 启动 + 存档镜像 + 编码/字体设置 + 真机验收；**不含** IME、UIF `replace_chars` 映射、独立存档。

---

## 0. 术语与命名

| 项 | 取值 |
|---|---|
| EngineType | `FVP` |
| 显示名（`EngineType.displayName`） | `FVP` |
| 引擎页分组 | GAL（`EngineTab.GAL`） |
| launchMode 取值 | `internal.fvp` |
| 宿主类 | `com.core.fvp.FvpActivity`（进程 `:fvp`） |
| 运行库（引擎库） | `librfvp.so`（上游 cargo-ndk 产物，arm64 实测 45,826,680 B，strip 后 40,527,784 B） |
| JNI 桥（运行时） | `librfvp_bridge.so`（engine 模块自研 shim，dlopen 上述运行库） |
| 存档目录（一期） | `<游戏根>/save` |
| 引擎设置分类 | 新增 `EngineSettingsKind.FVP` |
| 游戏脚本 | `*.hcb`（原版）/ `*.bch`（汉化补丁，同容器格式） |

---

## 1. 背景

FVP 是 FAVORITE 系视觉小说使用的自研引擎（代表作《はっぴぃ☆マーガレット！》《WhiteEternity》等）。
`rfvp` 是该引擎的非官方 Rust 重写（MPL-2.0），已完成：

- 完整的虚拟机/脚本解析（`.hcb`）、资源封包（`.bin`）读取、winit + wgpu 桌面运行；
- **Android host-driven 运行时**：Java 侧 `SurfaceView` + `Choreographer` 帧循环，Rust 侧以 `rfvp_android_*` C ABI 逐步驱动（与 Siglus 上游模型一致）；
- 本机已实测：以桌面模式可正常跑通《HappyMarguerite》（日文原版与 CHS 汉化 `Marguerite.bch`）——扫描、对白、BGM/SE、存档目录均正常。

接入价值：补齐 TyranorNext 对 FVP 系游戏的「扫描识别 → 启动 → 存档 → 设置」全链路支持；
汉化版（`*.bch` + GBK/SJIS 文本 + 自定义字体目录）与中文文本显示可在 Android 上复用系统 CJK 字体回退。

---

## 2. 上游勘察结论（实测）

### 2.1 平台与宿主架构

| 项 | 结论 | 来源 |
|---|---|---|
| Android 支持 | 有完整 port：Launcher APK + Player Activity + JNI shim + `cargo ndk` 打包脚本 | `platform/android/`、`platform/scripts/package_android_apk.sh` |
| 宿主模型 | Java 持有 `SurfaceView` + `Choreographer` 主循环；`App::host_step(dt_ms)` 由 C ABI 逐帧驱动 | `platform/android/.../RfvpGameActivity.java`、`crates/rfvp/src/app.rs`（`host_step`） |
| 纹理/渲染 | wgpu 0.19 直接绑定 `ANativeWindow*`（Vulkan/GLES 双后端，构建图含 `glow`） | `App::build_android`、`init_render_android` |
| 音频 | kira + cpal（Android 走 AAudio/OpenSL），需先初始化 `ndk-context` | `rfvp_android_init_context` 注释、平台脚本 |
| 输入 | 单指触摸 `rfvp_android_touch(phase,x,y)`；iOS 另有 `host_key_ios`，**Android 无按键 ABI** | `android_host.rs`、`app.rs::host_key_ios` |
| Surface 生命周期 | 提供 `set_surface`（重建 ANativeWindow）与 `resize`；上游 Activity 在 `surfaceDestroyed` 时销毁引擎并 `finish` | `rfvp_jni.cpp`、`RfvpGameActivity.java` |
| 打包 | `cargo ndk -t arm64-v8a -o jniLibs build --release -p rfvp --lib`；默认 features 全量（含 gpu-render/audio/native-video/mp4） | `package_android_apk.sh:75-78` |
| 构建面 API | 上游脚本默认 `CARGO_NDK_PLATFORM=28`；实测 API 26 亦构建成功（`.note.android.ident = 26`） | 本次实测（NDK 28.0.13004108 / cargo-ndk 4.1.2） |

### 2.2 C ABI 清单（`android_host.rs` 已导出，实测 `llvm-nm -D` 可用）

```
rfvp_android_init_context(JavaVM*, GlobalRef<Context>)      // 必须先于 create（音频后端依赖 ndk-context）
rfvp_android_create(ANativeWindow*, w_px, h_px, scale, game_dir_utf8, nls_utf8) -> handle
rfvp_android_step(handle, dt_ms) -> i32                     // 1 = 请求退出
rfvp_android_resize(handle, w_px, h_px)
rfvp_android_set_surface(handle, ANativeWindow*, w_px, h_px)
rfvp_android_touch(handle, phase, x_px, y_px)               // phase 0/1/2/3
rfvp_android_set_text_hidpi(handle, enabled)
rfvp_android_destroy(handle)
android_main(...)                                           // 空 stub，仅为链接期占位
```

- 触摸坐标与 Surface 尺寸均为**物理像素**，Rust 侧做 aspect-fit viewport 换算（含内建退出确认/旧式存读档 UI 命中）；
- `game_dir_utf8` 即游戏根，`nls_utf8` 为 `sjis|gbk|utf8`（缺省 sjis）；
- 无按键/IME/标题元数据 ABI（见 §2.4 缺口）。

### 2.3 引擎库实测数据

| 项 | 数值/结论 |
|---|---|
| `librfvp.so`（release, 未 strip） | 45,826,680 B（≈43.7 MiB） |
| `librfvp.so`（`llvm-strip --strip-unneeded`） | 40,527,784 B（≈38.7 MiB） |
| cargo-ndk 附带产物 | `libwmv_decoder.so` 599,624 B、`libmediacodec-4c4a3ce3b3fea0cb.so` 446,216 B（依赖 crate 的 cdylib 副产物；`librfvp.so` 的 `NEEDED` 与字符串均未引用） |
| `NEEDED` 依赖 | `libmediandk`、`liblog`、`libOpenSLES`、`libandroid`、`libdl`、`libm`、`libc`（**无 libc++_shared**） |
| 构建命令 | 见 §8.1；API 26/28 均可，与项目 `minSdk 26` 对齐无阻碍 |
| 存档 | `<游戏根>/save/rfvp_s###.bin`（读取兼容 `s###.bin`）；与桌面/PC 同格式 |
| 字体 | 自定义字体目录 `<游戏根>/font|Font|FONT/*.ttf|otf|ttc`（大小写兼容）；系统回退扫 `/system/fonts`（需运行时开关，Android 默认关） |
| 编码 | `sjis`（默认）/`gbk`/`utf8`，创建时固定，无运行时切换 ABI（改设置需重启宿主） |
| 视频 | `.mpg`（in-engine 软解）、`.wmv`（纯软解）；`.mp4` 走 MediaCodec 依赖链 |
| 退出 | 脚本内退出 → `step` 返回 1；返回键语义需宿主补（见 §6.1） |
| 后台 | 上游 Activity 在 `surfaceDestroyed` 销毁引擎并 finish（保守语义）；`set_surface` 预留保活能力 |

### 2.4 缺口清单（需 fork 处理）

| # | 缺口 | 处理方式 |
|---|---|---|
| G1 | Android 无按键 ABI，返回键无法映射 Escape | fork 新增 `rfvp_android_key(handle, vk, phase)`（一期仅 ESC，二期可扩全量 VK） |
| G2 | 无运行时「系统字体回退」开关，中文汉化可能缺字 | fork 新增 `rfvp_android_set_system_font(handle, enabled)`；Android 宿主默认开 |
| G3 | `App::find_hcb` 只 glob `*.hcb`，汉化补丁 `*.bch` 无法识别 | fork 改为 `*.bch` 优先、`*.hcb` 兜底（同容器，桌面已实测可解析） |
| G4 | 无游戏标题元数据读取（对比 Siglus 的 `GAMENAME` 回写） | 可选：宿主侧移植上游 `HcbTitleReader` 逻辑 + `fvp_title.<hash>` 回写（P2） |
| G5 | 无 IME/文本输入 ABI | 一期不做（FVP 系游戏基本无名字输入）；需要时参考 Siglus 方案补 ABI |
| G6 | 汉化补丁的 UIF `replace_chars` 映射未实现（部分占位繁体字不转简体） | 列为 P2：fork 增加可选字符映射（读取 `uif_config.json` 的 replace 对） |
| G7 | 无存档目录覆盖（独立存档） | 一期接受 `<游戏根>/save`；P2 以环境变量/ABI 覆盖 |
| G8 | 引擎库体积 38.7 MiB | 已 strip；P2 可裁剪 `mp4` 等 features（需回归视频兼容），一期接受 |

---

## 3. 总体设计

### 3.1 路线选择

| | 外置 APK 模块 | **内置 host（选定）** |
|---|---|---|
| 主 APK | 不变 | +38.7 MiB（arm64，strip 后） |
| 接入量 | 模块工程 + 注册表 + 安装协议 + fork launch 入口 | LaunchContract 宿主分支 + 扫描/存档/设置补齐 |
| 体验 | 双 APK、模块需自行授权 | 单 APK；all-files 流程、存档镜像、单游戏设置、引擎页全复用 |
| 许可 | 进程隔离最干净 | MPL-2.0 与 GPL-2.0 兼容，保留声明与源码链接即可 |
| 升级 | 模块独立发版 | 随 App 发版（二期可平移到 nativeplugin zip） |

结论：与 Siglus/ONS/Artemis 保持一致，走内置 host。

### 3.2 数据流

```
App（游戏库卡片点击）
  └─ EngineLauncher.buildFvpIntent(FVP)
       extras: PATH/GAME_PATH/PROJECT_ROOT/GAME_DIR、LAUNCH_TARGET、ROOT_URI
               LAUNCH_MODE=internal.fvp、FVP_NLS、FVP_SYSTEM_FONT、FVP_TEXT_HIDPI
               ORIENTATION、主题色
  └─ FvpActivity（:fvp 进程）
       ├─ SurfaceView.created → System.loadLibrary("rfvp_bridge")
       ├─ rfvp_android_init_context(JavaVM, AppContext)   // 先于 create
       ├─ rfvp_android_create(nativeWindow, w, h, density, gameDir, nls)
       ├─ rfvp_android_set_text_hidpi / set_system_font    // 生效设置
       ├─ Choreographer 每帧 → rfvp_android_step(dt)       // dt clamp 250ms；1=退出
       ├─ 触摸 → rfvp_android_touch；返回键 → rfvp_android_key(ESC)
       └─ surfaceDestroyed → 停循环并销毁引擎（一期沿用上游语义）
```

### 3.3 关键决策

1. **jniLibs 直打包**：`engine/src/main/jniLibs/arm64-v8a/librfvp.so`，随 APK 分发，CI 不需要 Rust 工具链（与 `libkrkrsdl3.so` / `libsiglus.so` 同模式）。
2. **桥接自研**：不复制上游 `rfvp_jni.cpp`（MPL），在 `engine/src/main/cpp/rfvp_bridge.cpp` 自研 dlopen shim（仅依赖系统库），与 `siglus_bridge.cpp` 同模板。
3. **宿主模型复用上游 host-driven 语义**：Java `SurfaceView` + `Choreographer` + `rfvp_android_*`，不启用 winit/`android-activity` 路径。
4. **返回键一期只映射 Escape**：对齐上游 iOS 宿主；全量键盘映射列为二期。
5. **系统字体回退 Android 默认开启**：Android 必有 Noto CJK；对汉化补丁的缺字兜底价值高（与桌面默认不同，作为宿主默认值并在设置中可关）。
6. **`*.bch` 优先于 `*.hcb`**：与汉化补丁魔改 exe 的行为一致，接入后自动匹配汉化脚本。
7. **一期不含封面**：与其他内置引擎一致；标题回写列为可选增强。

---

## 4. App 侧改动清单

### 4.1 引擎类型与展示

| 文件 | 改动 |
|---|---|
| `core/engine/EngineType.kt` | 新增 `FVP("FVP")`（置于 `SIGLUS` 之后） |
| `core/game/launch/EngineLauncher.kt` | `supportedEngines` 加入 `FVP`（引擎页展示） |
| `ui/engine/EngineScreen.kt` | `engineTabOf` → GAL；`engineDescription` 新增分支；`builtinDialogEntries` 可加 `fvp-rfvp` 条目（可选）；内置引擎默认 `installed=true` |
| `ui/game/GameScreen.kt` | `EngineType.coverColor()` 新增占位色；`shouldShowSaveManagement` 走「非外置/非模拟器 → 显示」 |
| `ui/home/HomeScreen.kt` | 无需改动（使用 displayName） |

### 4.2 扫描识别（`core/game/scan/EngineScanner.kt`）

在 `detectEngine` 判定链中新增特征（建议置于 SIGLUS 之后；两者特征无冲突）：

新增收集变量（根目录级）：

- `hasFvpScript`：根目录存在 `*.hcb` 或 `*.bch`（**大小写不敏感**，两者同容器格式）；
- `hasFvpGraphPack`：`graph.bin` / `graph_vis.bin`；
- `hasFvpAudioPack`：`bgm.bin` / `se.bin` / `se_env.bin` / `se_sys.bin` / `voice.bin` / `voice2.bin` / `etc.bin`。

判定与打分（`launchTarget = LAUNCH_TARGET_GAME_DIR`）：

| 条件 | 分数 |
|---|---|
| `hasFvpScript` + 任一图形/音频包 | 96 |
| `hasFvpScript`（无包） | 88 |
| 仅包命中（无脚本） | 不成立（防误判） |

新增单测 `EngineScannerFvpTest`（参照 `EngineScannerSiglusTest`）：hcb+包命中 / bch+包命中 / 大小写变体 / 仅脚本命中 / 仅包不误判 / 不误伤 Siglus/YURIS/RPGM/ONS 样例。

### 4.3 启动链路（`core/game/launch/EngineLauncher.kt`）

- `supportedEngines` 增加 `EngineType.FVP`；
- `buildIntent` 新增分支：`EngineType.FVP -> buildFvpIntent(...)`，新函数放在 `buildSiglusIntent` 附近：

```
PATH / GAME_PATH / PROJECT_ROOT / GAME_DIR = 游戏根真实路径（沿用 resolveGameDirectory）
ROOT_URI、LAUNCH_TARGET、LAUNCH_MODE = LaunchContract.LAUNCH_MODE_FVP
FVP_NLS        = 生效编码（sjis/gbk/utf8）
FVP_SYSTEM_FONT、FVP_TEXT_HIDPI = 生效布尔设置
ORIENTATION    = 沿用现有方向参数
主题色 extras 由 buildIntent 尾部统一注入
```

- 前置：`requestAllFilesAccessIfNeeded`（FVP 需写 `<游戏根>/save`，非镜像模式与 KRKR/ONS 一致）；
- 成功后：`recordRecentGame`；失败：沿用 `LaunchResult.Failure` / `LaunchErrorMessages`。

### 4.4 插件校验分支

`core/engine/plugin/EnginePluginBootstrap.kt` 的 `ensureForLaunch` `when (engine)` 新增 `FVP -> null`（内置，无原生插件依赖；漏改会编译失败）。

### 4.5 存档（`core/game/save/GameSaveManager.kt`）

`resolveSaveLocation` 新增分支：

```
EngineType.FVP -> SaveLocation(File(root, "save"), text(R.string.save_location_engine_game_dir, game.engine.displayName), true)
```

- `listSaveFiles` / `exportToZip` / `importFromZip` 自动复用；
- `cleanupAppData` 不新增分支（不产生应用私有数据，与 SIGLUS 同口径）。

### 4.6 引擎设置

| 设置项 | 键 | 取值 | 默认 |
|---|---|---|---|
| 文本编码（NLS） | `fvp_nls` | `sjis` / `gbk` / `utf8` | `sjis` |
| 系统字体回退 | `fvp_system_font` | bool | `true`（Android 默认开，见 §3.3-5） |
| 文本 HiDPI | `fvp_text_hidpi` | bool | `true`（与上游默认一致） |

改动点（以 SIGLUS 为模板）：

| 文件 | 改动 |
|---|---|
| `core/settings/EngineSettingsStore.kt` | 键常量、NLS 白名单、get/set/normalize |
| `core/settings/EngineSettingsResolver.kt` + `ResolvedEngineSettings` | 新增 `fvpNls` / `fvpSystemFont` / `fvpTextHidpi` 字段与合并 |
| `core/settings/EffectiveEngineSettings.kt` | NLS 用 `resolveAllowed`；布尔用 `resolveBool` |
| `core/settings/PerGameSettingsStore.kt` | `F_FVP_NLS` / `F_FVP_SYSTEM_FONT` / `F_FVP_TEXT_HIDPI` |
| `core/game/storage/GameOverridePartitions.kt` | 键常量并加入 `TYRANO_KEYS`（沿用 SIGLUS 先例，避免 Room 迁移） |
| `ui/settings/EngineSettingsText.kt` | `fvpNlsOptions()` / map |
| `ui/settings/EngineSettingsMenuActivity.kt` | `EngineSettingsKind.FVP` |
| `ui/settings/SettingsScreen.kt` | `FvpSettingsCard`（编码下拉 + 两个开关） |
| `ui/settings/PerGameSettingsScreen.kt` | FVP 分支：三项覆盖 + 信息卡（存档目录、编码说明） |

编码语义：仅创建引擎时生效，改动后需重启本局（宿主 Toast 提示可选）。

### 4.7 文案（三语言严格一致）

三份 `strings.xml` 同步新增：`engine_desc_fvp`、`engine_settings_fvp_title`、`engine_settings_fvp_nls_title`、`engine_settings_fvp_nls_sjis/gbk/utf8`、`engine_settings_fvp_system_font_title`、`engine_settings_fvp_text_hidpi_title`、`engine_settings_fvp_note` 等（键名与数量见附录 C）。

### 4.8 其它

- `core/game/storage/EngineDetectionRepository.kt`：FVP 走 `else -> null`（无版本检测缓存）；
- `ScanGame` / `GameEntity`：无需新字段；
- `README.md`：引擎支持表新增 FVP 行；致谢补 `rfvp`（MPL-2.0）；
- `CONTEXT.md`：引擎列表补 FVPEngine 术语。

---

## 5. engine 侧改动清单

### 5.1 JNI 桥：`engine/src/main/cpp/rfvp_bridge.cpp`（新增）

- `dlopen("librfvp.so", RTLD_NOW)` + `dlsym` 取函数指针（`std::call_once` 缓存）；
- JNI 方法（`com.core.fvp.NativeRfvp`）：
  - `nativeInitAndroidContext(Context)`：`JNI_OnLoad` 缓存 JavaVM，`NewGlobalRef` 后调 `rfvp_android_init_context`；
  - `create(Surface, w, h, scale, gameDir, nls)`：`ANativeWindow_fromSurface` + 持有窗口引用（handle 为 key 的 `unordered_map`，与上游 shim 同模式）；
  - `step/resize/setSurface/touch/setTextHidpi/setSystemFont/keyEvent/destroy`；
- 容错：符号缺失仅记日志；`create` 前必检核心符号；失败返回 0 交宿主 finish（对齐 `siglus_bridge.cpp`）。

### 5.2 CMake

`engine/src/main/cpp/CMakeLists.txt` 新增：

```cmake
add_library(rfvp_bridge SHARED rfvp_bridge.cpp)
target_link_libraries(rfvp_bridge ${android-lib} ${log-lib} ${dl-lib})
```

### 5.3 Java 宿主：`engine/src/main/java/com/core/fvp/`

| 类 | 职责 |
|---|---|
| `NativeRfvp.java` | JNI 包装（`System.loadLibrary("rfvp_bridge")`） |
| `FvpActivity.java` | 生命周期/帧循环/Surface/触摸/返回键/沉浸式/加载遮罩（参考上游 `RfvpGameActivity` 行为，代码自研） |
| `FvpTitleReader.java`（P2） | 读取 HCB 头标题（`sys_desc_offset` → title），用于标题回写 |

宿主关键行为：

1. `onCreate`：全屏沉浸式；解析 `LaunchContract` extras（`PATH → GAME_PATH → PROJECT_ROOT → GAME_DIR` 取第一个非空，缺失 Toast + finish）；`SurfaceHolder.Callback` + `OnTouchListener`；`setKeepScreenOn`；显示加载遮罩（`engine_starting_game`）。
2. `surfaceCreated`：`handle == 0` → `ensureEngine()`（init context → create → 应用 hidpi/system font 设置）；否则 `setSurface` 重挂。首帧成功后移除加载遮罩。
3. 帧循环：`Choreographer`，`dt` clamp `[0,250]ms`；`step > 0` → `finish()`。
4. `onPause` 停循环；`onDestroy` 停循环 + `destroy(handle)`；`surfaceDestroyed` 一期沿用上游：停循环 + 销毁 + finish（`setSurface` 保活列为 P2）。
5. 输入：触摸 phase 直接转发；`KEYCODE_BACK` 单击 → `keyEvent(ESC down/up)`，2 秒内双击 → 退出（Toast `engine_fvp_back_again_to_exit`）。
6. 多实例保护：`singleInstance` + `onNewIntent` Toast `engine_another_game_running`。

### 5.4 Manifest / 资源

```xml
<!-- FVPEngine（rfvp）宿主：Java SurfaceView + Choreographer 主循环驱动 Rust 引擎 -->
<activity
    android:name="com.core.fvp.FvpActivity"
    android:exported="false"
    android:process=":fvp"
    android:launchMode="singleInstance"
    android:taskAffinity="com.core.Fvp"
    android:screenOrientation="sensorLandscape"
    android:theme="@style/Theme.Fvp"
    android:windowSoftInputMode="adjustNothing"
    android:configChanges="keyboard|keyboardHidden|navigation|orientation|screenLayout|screenSize|smallestScreenSize|uiMode|density|fontScale|locale|layoutDirection" />
```

- `engine/src/main/res/values/styles.xml` 新增 `Theme.Fvp`（`Theme.AppCompat.NoActionBar` + 黑底）；
- `engine/consumer-rules.pro` 新增 `-keep class com.core.fvp.** { *; }`（JNI 名字查找安全）。

### 5.5 契约与常量

`engine/src/main/java/com/core/engine/LaunchContract.kt`：

```kotlin
// ---------- FVP ----------
const val FVP_NLS = "fvp_nls"                     // sjis|gbk|utf8
const val FVP_SYSTEM_FONT = "fvp_system_font"     // bool
const val FVP_TEXT_HIDPI = "fvp_text_hidpi"       // bool
// （P2）const val FVP_PATH_HASH = "fvp_path_hash"

const val LAUNCH_MODE_FVP = "internal.fvp"
```

`EnginePrefs.kt`（P2）：`KEY_FVP_TITLE_PREFIX = "fvp_title."`。

### 5.6 打包

- `engine/src/main/jniLibs/arm64-v8a/librfvp.so`（strip 后 38.7 MiB，随 APK）；
- 建议同时携带 cargo-ndk 产物 `libwmv_decoder.so` / `libmediacodec-<hash>.so`（+0.7 MiB，视频解码兜底；确认无引用后可裁剪）；
- `packaging.jniLibs.useLegacyPackaging = true` 已开启（无需改动）；
- CI 无需 Rust 工具链（.so 入库）。

---

## 6. fork（rfvp）改动

基线：`/Users/weiss/github- engine/rfvp` @ `304e773`（建议 fork 至 `Weiss-UltimateSavior/rfvp`）。

### 6.1 Android 按键 ABI（G1）

`crates/rfvp/src/android_host.rs` 新增：

```c
void rfvp_android_key(void* handle, int32_t vk_code, int32_t phase); // phase 0=down, 1=up
```

- 语义采用 Windows VK（对齐 Siglus）：一期至少 `0x1B ESC`；二期可扩 `0x0D/0x20/0x11/0x25-0x28` 等；
- `App::host_key_android(vk, phase)`：映射为 winit `Key` 后走 `inputs_manager.notify_keydown/up`（参考 `host_key_ios`）；
- 内建退出确认/旧式存读档 UI 打开时按键只在 UI 内消费。

### 6.2 系统字体回退 ABI（G2）

```c
void rfvp_android_set_system_font(void* handle, int32_t enabled);
```

- `App` 暴露 `set_system_font_fallback_enabled`（`text_manager` 已有同名方法）；
- 宿主在 create 后、首帧前调用；
- Android 无内置「系统字体」概念，扫 `/system/fonts` 的 CJK 字体（已实现）。

### 6.3 `*.bch` 支持（G3）

`crates/rfvp/src/app.rs::find_hcb`：

- 优先匹配根目录 `*.bch`（汉化补丁），无则 `*.hcb`；
- `.bch` 与 `.hcb` 同为 HCB 容器（首字段 `sys_desc_offset`，桌面已实测解析与游玩通过）；
- 该改动只影响脚本选择，不改解析器。

### 6.4 可选增强（P2）

| 项 | 方案 |
|---|---|
| 标题回写（G4） | 新增 `rfvp_android_game_title_from_dir` + `rfvp_android_string_free`；宿主写 `fvp_title.<hash>`，App 侧条件导入（对齐 Siglus 协议） |
| UIF 映射（G6） | `App` 增加可选字符映射表（读取游戏根 `uif_config.json` 的 `text_processor.rules[replace_chars]`），在文本解码后应用；仅对声明支持的汉化补丁生效 |
| 独立存档（G7） | `rfvp_android_set_save_dir` 或环境变量覆盖 `save_manager` 根路径 |
| Surface 保活 | 宿主 `surfaceDestroyed` 只停循环；回前台 `set_surface` 重建 wgpu Surface（上游 ABI 已具备） |

### 6.5 构建与署名

- 记录构建基线 commit 到 TyranorNext `README.md` 与本文档；
- MPL-2.0：保留上游 `LICENSE` 与文件头；TyranorNext README 注明「引擎运行库来自 rfvp（MPL-2.0），源码见上游/fork 仓库」；
- 桥接 shim 与宿主代码自研，不直接复制上游文件，规避许可混入争议。

---

## 7. 关键交互细节

### 7.1 触摸与坐标

- 宿主只传物理像素（`MotionEvent.getX/getY`、`SurfaceHolder.getSurfaceFrame()` 宽高）；
- Rust 侧按 aspect-fit viewport 将触摸换算到逻辑坐标，并处理内建 UI（退出确认、旧式存读档）命中优先级；
- `native_scale_factor` 传 `DisplayMetrics.density`，仅影响文本 HiDPI 栅格化。

### 7.2 返回键与退出

- 单击返回 → 引擎 Escape（关闭菜单/取消）；
- 2 秒内双击 → 退出宿主（Toast 提示）；
- `step` 返回 1（脚本内退出到系统，如标题 Exit）→ `finish()`。

### 7.3 文本编码（NLS）

```
设置（全局或单游戏）→ EngineSettingsResolver.fvpNls
  → EngineLauncher extras(FVP_NLS)
  → 宿主 create(gameDir, nls)
  → 脚本/存档字符串按编码解码
```

- `sjis`：原版日文 / 基于 SJIS 的汉化补丁占位文本；
- `gbk`：以 GBK 重编码的汉化补丁；
- `utf8`：UTF-8 汉化补丁；
- 运行时不可切换，改动需重启本局（宿主可按需 Toast）。

### 7.4 字体

- 自定义字体：游戏根 `font|Font|FONT` 目录（TTF/OTF/TTC）自动加载——汉化补丁常用方式；
- 系统回退：Android 宿主默认开启，扫 `/system/fonts`（Noto Sans CJK 等）补缺字；
- 引擎内置 MS-PGothic/MS-PMincho 位图/矢量字体兜底（无需 `default.ttf`）。

### 7.5 存档

- 一期：`<游戏根>/save/rfvp_s###.bin`（读取兼容 `s###.bin`）；
- 存档镜像（导出/导入 zip）与列表自动复用；
- 需游戏根写权限 → 复用 `requestAllFilesAccessIfNeeded`（Android 11+ 需 `MANAGE_EXTERNAL_STORAGE`）。

### 7.6 权限

- 与 Siglus 一致：启动前请求所有文件访问权限；
- 无权限时读游戏可运行（视目录），但存档写入失败 → 单游戏设置信息卡提示。

### 7.7 后台与恢复

- 一期沿用上游保守语义：`surfaceDestroyed` → 停循环 + 销毁 + finish；
- 二期（P2）：保活 + `set_surface` 重建（Siglus 已实现该语义，可对齐）。

---

## 8. 构建与集成步骤

### 8.1 构建引擎库（一次性/每次更新）

```bash
cd "/Users/weiss/github- engine/rfvp"
export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/28.0.13004108"
CARGO_NDK_PLATFORM=26 cargo ndk -t arm64-v8a -o /tmp/rfvp-android build --release -p rfvp --lib
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip" \
  --strip-unneeded /tmp/rfvp-android/arm64-v8a/librfvp.so
cp /tmp/rfvp-android/arm64-v8a/librfvp.so \
   /Users/weiss/opencode/rma/TyranorNext/engine/src/main/jniLibs/arm64-v8a/
```

实测：构建约 1.5–3 分钟；API 26/28 均可（与 `minSdk 26` 对齐）。

### 8.2 集成构建与校验

```bash
./gradlew :app:assembleDebug --no-daemon
./gradlew :app:testDebugUnitTest --no-daemon
python3 tools/check-hardcoded-ui-strings.py
git diff --check
```

### 8.3 CI 影响

- 无 Rust 工具链需求（.so 入库）；
- APK 体积 +38.7 MiB（arm64），`jniLibs` 总量由约 68.8 MiB → 约 107.5 MiB；
- nativeplugin 打包任务不受影响。

---

## 9. 测试与验收

### 9.1 单元测试

| 用例 | 内容 |
|---|---|
| `EngineScannerFvpTest` | hcb/bch ± 资源包特征、大小写、仅包不成立、与 Siglus/YURIS/RPGM/ONS 样例不互斥 |
| `EngineSettingsResolverTest`（扩展） | FVP 编码白名单回落、系统字体/HiDPI 覆盖合并 |
| `GameOverridePartitionsTest`（扩展） | FVP 键在 tyrano 分区往返不丢 |
| `GameSaveManagerTest`（扩展） | FVP → `<根>/save`；导入导出往返 |

### 9.2 真机验收（至少 1 款 FVP 游戏；建议《HappyMarguerite》日文 + CHS）

1. 扫描识别为 FVP（日文版 `*.hcb`、汉化版 `*.bch` 均可）；
2. 启动进入游戏：标题画面 → 触摸推进对话 → 打开菜单/存档 → 读档；
3. 保存后杀进程重进存档仍在（`<根>/save` 有新文件）；
4. 编码切换：日文 `sjis`、汉化 `gbk`/`sjis`（视补丁）下文本正确，改设置后重启生效；
5. 中文字体：无 `font/` 目录时系统回退正常显示简中；放置自定义 TTF 后优先使用；
6. 返回键单击 = 取消，双击 = 退出；游戏内 Exit = 正常退出；
7. `text_hidpi` 开关：高分屏文字清晰度差异符合预期；
8. 后台/回前台、旋转（sensorLandscape 内）、多任务切换无崩溃；
9. 大体积游戏（graph.bin >1GB）加载与播放流畅，内存与首帧耗时记录；
10. 存档镜像：导出 zip → 清档 → 导入 → 读档恢复。

### 9.3 回滚

- 移除 `EngineType.FVP` 相关分支 + 删除 `librfvp.so`/桥接 so 即回到接入前；
- 无数据迁移风险（存档在游戏根，设置键滞留无害）。

---

## 10. 风险与待确认

| 风险 | 影响 | 缓解 |
|---|---|---|
| `librfvp.so` 38.7 MiB，APK 体积明显增大 | 安装包 ~110 MiB | 已 strip；P2 裁剪 `mp4`/`image-formats` 等 features；或二期平移 nativeplugin 按需下载 |
| wgpu Vulkan/GLES 兼容（老设备） | 无法渲染 | wgpu 0.19 已含 GLES 后端；真机覆盖测试；不达标则在 README 标注设备要求 |
| 上游 API 变动 | 重编译需回归 | 只依赖 `rfvp_android_*` 稳定 ABI；固定基线 commit |
| 汉化补丁 UIF 占位字未映射 | 个别繁体占位字残留 | P2 实现字符映射（§6.4）；一期在设置信息卡说明 |
| 返回键/无 IME | 少量输入场景受限 | 一期仅 ESC 映射；IME 列为 P2（FVP 系基本无输入） |
| `<游戏根>` 只读（SD 卡/Android/data） | 存档写入失败 | 复用 all-files 流程；P2 独立存档目录 |
| MPL-2.0 合规 | 许可质疑 | 保留声明 + README 注明源码链接；shim/宿主自研 |
| `surfaceDestroyed` 销毁引擎 | 切后台丢进度 | 一期接受；P2 用 `set_surface` 保活 |

待确认（评审时定）：

1. 系统字体回退默认值（建议 `true`，Android 缺字场景收益明显）；
2. 是否一期就做标题回写（建议 P2，与 Siglus 对齐体验后再补）；
3. `builtinDialogEntries` 是否加 `fvp-rfvp` 版本条目（建议加，展示引擎来源基线）；
4. 编码选项是否提供 `auto`（一期建议不做，显式三选一更可控）。

---

## 11. 里程碑（建议实施顺序）

| 阶段 | 内容 | 依赖 | 预估 |
|---|---|---|---|
| M0 | fork 补 ABI（§6.1/6.2/6.3）+ 重建并 strip `.so`；入 `jniLibs` | 无 | 0.5 天 |
| M1 | engine：shim + 宿主 + Manifest/资源 + 契约常量 | M0 | 1 天 |
| M2 | app：EngineType/扫描/启动/存档/插件分支 + 文案 | M1 | 1 天 |
| M3 | 设置链路（编码/系统字体/HiDPI：Store/Resolver/UI/单游戏覆盖） | M2 | 1 天 |
| M4 | 真机验收（§9.2）+ 返回键/字体/编码回归 | M3 | 1 天 |
| M5 | README/CONTEXT 更新 + 单测/三语言/构建回归 | M4 | 0.5 天 |
| 二期 | IME、UIF 映射、标题回写、独立存档、Surface 保活、体积裁剪 | M5 | 待排 |

---

## 附录 A：ABI 对照表

| shim JNI（`com.core.fvp.NativeRfvp`） | C ABI | 备注 |
|---|---|---|
| `nativeInitAndroidContext(Context)` | `rfvp_android_init_context` | create 前必须（音频/ndk-context） |
| `create(Surface,w,h,scale,gameDir,nls)` | `rfvp_android_create` | 返回 handle，0=失败 |
| `step(handle,dtMs)` | `rfvp_android_step` | 1=请求退出 |
| `resize` | `rfvp_android_resize` | |
| `setSurface` | `rfvp_android_set_surface` | 一期未使用（P2 保活） |
| `touch` | `rfvp_android_touch` | phase 0/1/2/3 |
| `setTextHidpi` | `rfvp_android_set_text_hidpi` | |
| `setSystemFont`（新增） | `rfvp_android_set_system_font` | fork §6.2 |
| `keyEvent`（新增） | `rfvp_android_key` | fork §6.1 |
| `destroy` | `rfvp_android_destroy` | |

## 附录 B：检测特征对照（EngineScanner）

| 特征 | 文件名/路径 | 大小写 |
|---|---|---|
| 脚本（原版） | 根目录 `*.hcb` | 不敏感 |
| 脚本（汉化） | 根目录 `*.bch` | 不敏感 |
| 图形包 | `graph.bin`、`graph_vis.bin` | 不敏感 |
| 音频/其它包 | `bgm.bin`、`se.bin`、`se_env.bin`、`se_sys.bin`、`voice.bin`、`voice2.bin`、`etc.bin` | 不敏感 |
| 辅助（不计分） | `movie/*.mpg`、`patchdata/` | — |

## 附录 C：新增文案键（三语言）

| 键 | zh | ja | en |
|---|---|---|---|
| `engine_desc_fvp` | FVPEngine（FAVORITE 系） | FVPEngine（FAVORITE 系） | FVPEngine (FAVORITE) |
| `engine_settings_fvp_title` | FVP | FVP | FVP |
| `engine_settings_fvp_nls_title` | 文本编码 | テキスト文字コード | Text encoding |
| `engine_settings_fvp_nls_sjis` | Shift-JIS（日文原版） | Shift-JIS（日本語版） | Shift-JIS (Japanese) |
| `engine_settings_fvp_nls_gbk` | GBK（简体汉化） | GBK（簡体字中国語） | GBK (Simplified Chinese) |
| `engine_settings_fvp_nls_utf8` | UTF-8 | UTF-8 | UTF-8 |
| `engine_settings_fvp_system_font_title` | 系统字体回退 | システムフォント補完 | System font fallback |
| `engine_settings_fvp_text_hidpi_title` | 文本高分辨率渲染 | テキスト高解像度描画 | HiDPI text rendering |
| `engine_settings_fvp_note` | 修改编码后需重启游戏 | 文字コード変更後はゲームを再起動 | Restart the game to apply encoding changes |

> engine 模块宿主的 Toast 文案（`engine_fvp_init_failed`、`engine_fvp_back_again_to_exit` 等）需在 `engine/src/main/res/values{,-en,-ja}/strings.xml` 三份同步；实际文案以项目三语言校对习惯为准，新增后必须通过 `python3 tools/check-hardcoded-ui-strings.py`。

---

## 实施记录（M0–M5）

### M0 fork 改动（`/Users/weiss/github- engine/rfvp`，未提交）

| 文件 | 改动 |
|---|---|
| `crates/rfvp/src/android_host.rs` | 新增导出 `rfvp_android_key(handle, vk, phase)`、`rfvp_android_set_system_font(handle, enabled)` |
| `crates/rfvp/src/app.rs` | 新增 `App::host_key_android`（VK → winit Key：ESC/Enter/Space/方向键/Control，phase 0/1）、`App::set_system_font_fallback_enabled`（开启即触发一次性系统字体扫描）；`find_hcb` 改为 `*.bch` 优先、`*.hcb` 兜底 |
| `crates/rfvp/src/subsystem/resources/text_manager.rs` | `FontEnumerator::load_system_fallback_fonts`（std 与 no_std 两个实现，no_std 为空实现） |

构建与入库（API 26）：

```bash
cd "/Users/weiss/github- engine/rfvp"
export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/28.0.13004108"
CARGO_NDK_PLATFORM=26 cargo ndk -t arm64-v8a -o /tmp/rfvp-android-fvp build --release -p rfvp --lib
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip" --strip-unneeded /tmp/rfvp-android-fvp/arm64-v8a/*.so
cp /tmp/rfvp-android-fvp/arm64-v8a/*.so \
   engine/src/main/jniLibs/arm64-v8a/
```

实测产物：`librfvp.so` 45,831,904 B → strip 后 40,529,800 B；`libwmv_decoder.so` 435,832 B；`libmediacodec-4c4a3ce3b3fea0cb.so` 305,344 B；`.note.android.ident` API level = 26；`rfvp_android_*` 全量符号（含新增两个）已确认导出。

### M1 engine 侧改动

- `cpp/rfvp_bridge.cpp`（自研 shim：dlopen `librfvp.so` + dlsym 函数表 + JNI_OnLoad 缓存 JavaVM + ANativeWindow 引用管理 + 符号缺失容错）；
- `cpp/CMakeLists.txt` 新增 `rfvp_bridge` 目标；
- `com/core/fvp/NativeRfvp.java`（JNI 包装）与 `com/core/fvp/FvpActivity.java`（SurfaceView + Choreographer 主循环、沉浸式、加载遮罩、返回键单击 ESC / 2 秒双击退出、singleInstance 多开提示、`surfaceDestroyed` 停循环并销毁引擎）；
- Manifest `FvpActivity`（`:fvp`、sensorLandscape、Theme.Fvp）、`styles.xml` Theme.Fvp、`consumer-rules.pro` keep `com.core.fvp.**`；
- `LaunchContract`：`FVP_NLS` / `FVP_SYSTEM_FONT` / `FVP_TEXT_HIDPI` / `LAUNCH_MODE_FVP`；
- engine 三语言文案 ×3（`engine_fvp_missing_game_dir` / `engine_fvp_init_failed` / `engine_fvp_back_again_to_exit`）。

### M2/M3 app 侧改动

- `EngineType.FVP`、`EngineScanner` 特征（根目录 `*.hcb`/`*.bch` + `FVP_PACK_NAMES`；脚本+资源包 96 / 仅脚本 88，嵌套目录脚本不成立）+ `EngineScannerFvpTest`（7 例）；
- `EngineLauncher`：`supportedEngines`、`buildIntent` FVP 分支、`buildFvpIntent`（PATH/GAME_PATH/PROJECT_ROOT/GAME_DIR + 三项设置 + launchMode）；`EnginePluginBootstrap` 返回 null；`GameSaveManager` → `<游戏根>/save`；
- UI：`EngineScreen`（GAL 分组、描述文案、`fvp-rfvp` 版本条目）、`GameScreen.coverColor`（`0xFFB05A2A`）；
- 设置：`EngineSettingsStore`（键/白名单/默认 sjis/系统字体与 HiDPI 默认开）、`EngineSettingsResolver`/`ResolvedEngineSettings`、`PerGameSettingsStore`（`F_FVP_*`）、`GameOverridePartitions`（随 tyrano 分区，避免 Room 迁移）、`EngineSettingsText`、`EngineSettingsKind.FVP`、`SettingsScreen` FVP 卡片、`PerGameSettingsScreen` FVP 分支；
- 三语言文案 ×3：`engine_desc_fvp` + `engine_settings_fvp_*`（共 10 键）。

### 与方案的偏差

1. 仓库中不存在 `EngineSettingsResolverTest` / `GameSaveManagerTest`：以 `EffectiveEngineSettingsTest` 增补 FVP 白名单/布尔覆盖用例、`GameOverridePartitionsTest` 增补 FVP 键往返与契约断言替代；存档目录分支由编译期穷举保证。
2. Android 键 ABI 从「一期仅 ESC」扩为 ESC + Enter/Space/方向键/Control（宿主一期仍只发送 ESC），避免二期再改 `.so`。
3. `builtinDialogEntries` 按建议新增 `fvp-rfvp` 条目（`rfvp-xmoezzz`）。
4. 系统字体回退默认开启（按建议）；编码未提供 auto（三选一显式）。

### 验证结果

- `./gradlew :app:assembleDebug --no-daemon`：通过；APK 含 `lib/arm64-v8a/librfvp.so`（40.5 MB）、`librfvp_bridge.so`、`libwmv_decoder.so`、`libmediacodec-*.so`。
- `./gradlew :app:testDebugUnitTest --no-daemon`：通过（含新增 FVP 扫描 7 例与设置/分区回归）。
- `python3 tools/check-hardcoded-ui-strings.py`、`git diff --check`：通过。
- **真机冒烟（Huawei MAA-AN10 / arm64）**：
  1. Debug APK 覆盖安装成功、应用启动正常、原游戏库数据保留；
  2. 引擎页出现 FVP 条目（描述/「已集成」）并可进入 FVP 引擎设置页（编码下拉 + 两个开关 + 说明文案渲染正常）；
  3. 使用仅含 `Marguerite.bch`（汉化脚本，3.2 MB）的合成目录完成扫描识别（`FVP`、占位色、入库），并成功从游戏卡片启动 `FvpActivity`；
  4. `:fvp` 进程存活、无 FATAL，`/proc/<pid>/maps` 确认已加载 `librfvp.so` 与 `librfvp_bridge.so`（dlopen + 符号解析 + create 成功；缺少资源包时显示空画面属预期）；
  5. 测试文件与测试条目已清理。
- 待执行：§9.2 的完整游玩验收（对白/存档/读档/编码切换/自定义字体/返回键双击退出等）需将完整游戏数据落机并通过 SAF 授权后回归。

---

## 参考

- 上游：`xmoezzz/rfvp`（MPL-2.0）：`setsumei/HOW-TO-BUILD.android-apk.md`、`platform/android/`、`crates/rfvp/src/android_host.rs`、`crates/rfvp/src/app.rs`、`crates/rfvp/src/subsystem/resources/text_manager.rs`
- 本项目先例：`docs/Siglus引擎接入方案.md`（内置 host + jniLibs 路线模板）、`docs/外置APK引擎模块接入方案.md`
- 规范：根 `AGENT.md`（三层架构、提交前审核）、`CONTEXT.md`（术语）
