# SiglusEngine（siglus_rs）内置引擎接入方案

> 状态：**已实施（M0–M5 完成）**；实现说明与偏差见文末「实施记录」
> 关联上游：`Weiss-UltimateSavior/siglus_rs`（fork 自 `xmoezzz/siglus_rs`，MPL-2.0）
> 结论摘要：**内置 host 路线**（engine 模块新增宿主 Activity）+ **jniLibs 直打包**；
> 一期范围 = 核心可玩 + 文本输入/IME + 键盘映射 + 语言设置 + GAMENAME 标题回写（**不含封面**）。

---

## 0. 术语与命名

| 项 | 取值 |
|---|---|
| EngineType | `SIGLUS` |
| 显示名（`EngineType.displayName`） | `Siglus` |
| 引擎页分组 | GAL（`EngineTab.GAL`） |
| launchMode 取值 | `internal.siglus` |
| 宿主类 | `com.core.siglus.SiglusActivity`（进程 `:siglus`） |
| 原生库 | `libsiglus.so`（上游 cargo-ndk 产物 `libsiglus_scene_vm.so` 改名而来，20.5MB/arm64） |
| 存档目录（一期） | `<游戏根>/savedata` |
| 引擎设置分类 | 新增 `EngineSettingsKind.SIGLUS` |

---

## 1. 背景

SiglusEngine 是 Key / VisualArts（VA）系视觉小说的自研引擎，代表作含 Rewrite、Summer Pockets、Angel Beats! 1st beat、Little Busters! 等。`siglus_rs` 是该引擎的非官方 Rust 重写与多平台移植（MPL-2.0），已提供完整的 Android 实现：

- Java 侧持有 `SurfaceView` 与 `Choreographer` 帧循环，Rust 侧以 `siglus_android_*` C ABI 被逐步驱动（step/touch/resize/messagebox）；
- 与 TyranorNext 现有内置引擎（Artemis / ONS）的宿主模型同构，接入面小；
- 该仓库已由本项目作者 fork，允许按需增补 ABI 与宿主行为。

接入价值：补齐 TyranorNext 对 Siglus 系游戏的「扫描识别 → 启动 → 存档 → 设置」全链路支持。

---

## 2. 上游勘察结论（实测）

### 2.1 平台与宿主架构

| 项 | 结论 | 来源 |
|---|---|---|
| Android 支持 | 已有完整 port（Launcher + Player + JNI shim + cargo-ndk 构建脚本） | `platform/android/`、`platform/scripts/package_android_apk.sh` |
| 宿主模型 | `AppCompatActivity` + `SurfaceView` + `Choreographer`；引擎由 C ABI `step(dt_ms)` 驱动 | `SiglusGameActivity.java` |
| 后台恢复 | `surfaceDestroyed` 只停帧循环，`onDestroy` 才销毁引擎（回前台保留进度） | 同上 |
| 返回键 | 单击 → 引擎 Escape（游戏内取消），2 秒内双击 → 退出 | 同上 |
| 沉浸式 | `WindowCompat` + `systemBars` 隐藏，`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` | 同上 |
| 上游模块 minSdk | 28（本项目 26，低版本未验证，需在 README/说明中标注） | `platform/android/app/build.gradle.kts` |

### 2.2 C ABI 清单（`siglus.h` 已导出，Android 段）

```
siglus_android_init_context(JavaVM*, jobject context)          // 必须先于 create（音频后端依赖 ndk-context）
siglus_android_create(ANativeWindow*, w_px, h_px, scale, game_dir) -> handle
siglus_android_step(handle, dt_ms) -> i32                      // >0 正常退出；<0 VM 错误
siglus_android_resize / set_surface / touch(phase,x,y)
siglus_android_text_input(handle, utf8)                        // 文本提交（当前宿主未接线）
siglus_android_ime_preedit(handle, utf8|null, start, end)      // null 表示 preedit 取消
siglus_android_key_down / key_up(handle, key_code)             // key_code = Windows VK
siglus_android_set_native_messagebox_callback / submit_messagebox_result
siglus_android_destroy(handle)
siglus_game_name_from_dir(dir) / siglus_game_cover_path_from_dir / siglus_game_cover_mime_from_dir
siglus_string_free(ptr)
```

- 触摸坐标由 Rust 侧按 aspect-fit viewport 换算到逻辑坐标（宿主只传物理像素）；
- 键码语义为 **Windows 虚拟键码**（`host.rs::vm_key_from_platform_code`：0x1B/0x0D/0x20/0x08/0x09/0x10/0x11/0x12/0x25-0x28/0x30-0x39/0x41-0x5A/0x70-0x7B）；
- **无**「当前是否需要 IME / IME 光标区域」查询 ABI，也**无**「未映射按键的文本事件」入口（桌面端用 winit Ime/KeyboardInput 事件，Android 需补 ABI）。

### 2.3 实测数据

| 项 | 数值/结论 |
|---|---|
| `lib/arm64-v8a/libsiglus.so` | 20,493,704 B（已 strip，仅依赖 `liblog/libandroid/libdl/libOpenSLES/libm/libc`） |
| JNI shim | 385,896 B（自研 shim 可替代，无需打包） |
| 上游 APK（`pre-release`，`1eadb38`） | 95.8MB，双 ABI；**重复打包** `libsiglus_scene_vm.so`（与 `libsiglus.so` 完全同尺寸，可省一份） |
| 对比 | 本项目已内置 `libkrkrsdl3.so` 28.4MB → 体积可接受 |
| 桌面 cdylib 参考 | macOS `libsiglus_scene_vm.dylib` 18.7MB（与 Android 同量级） |
| 存档 | `<project_dir>/savedata`（读取兼容 `<project_dir>/save`），`config.sav`/`global.sav`/截图 png；与 PC 同格式 |
| 密钥 | 首次启动自动爆破 EXE key 并写回 `<游戏根>/key.toml`（写失败仅内存生效）；需游戏根写权限 |
| 检测特征 | `Gameexe.dat` / `Gameexe.ini`（含 EN/ZH/ZHTW/DE/ES/FR/ID 变体）+ `Scene.pck`（根或 `Data/`）；`Select.ini` 决定 append 目录 |
| 标题 | `GAMENAME`（Gameexe），兜底目录名 |
| 语言 | 环境变量 `SIGLUS_LANGUAGE`（默认 `JP`），经引擎 `GET_LANGUAGE` 系统调用返回给游戏脚本 |
| 日志开关 | `SIGLUS_LOG`（off/error/warn/info/debug/trace） |
| 视频 | `.omv`/`.mpg`/`.wmv` 纯软解（in-engine），无需外部播放器 |
| 帧循环 | Java `Choreographer`，`dt` clamp 250ms |

### 2.4 缺口清单（需处理）

| # | 缺口 | 处理方式 |
|---|---|---|
| G1 | Android 宿主未接文本输入/IME | fork 补 ABI（见 §6.1）+ engine 宿主接线（见 §5） |
| G2 | 键盘仅映射 Escape | 宿主按 §7.1 映射表转发；补「未映射键文本事件」ABI |
| G3 | 无存档目录覆盖（固定游戏根） | 一期接受；二期 fork 支持 `SIGLUS_SAVE_DIR` 实现「独立存档」 |
| G4 | 打包重复 `libsiglus_scene_vm.so` | 构建流程只取一份并改名（见 §8.1） |
| G5 | 上游 minSdk 28 | 发布说明标注「Siglus 需 Android 9+」 |
| G6 | 覆盖解析需 EXE key（`Gameexe.dat`） | 标题回写在引擎 create 成功后调用（此时 key 已就绪或已缓存） |

---

## 3. 总体设计

### 3.1 路线选择

| | 外置 APK 模块 | **内置 host（选定）** |
|---|---|---|
| 主 APK | 不变 | +20.5MB（arm64） |
| 接入量 | 注册表 + `<queries>` + 模块安装状态 + fork 加 launch 入口 | LaunchContract 宿主分支 + 扫描/存档/设置补齐 |
| 体验 | 双 APK、模块需自行授予存储权限 | 单 APK；all-files 流程、存档镜像、单游戏设置、引擎页全复用 |
| 许可 | 进程隔离最干净 | 可接受（MPL-2.0 经 §3.3 与 GPLv2 兼容；保留声明与源码链接） |
| 升级 | 模块独立发版 | 随 App 发版（二期可平移到 nativeplugin zip） |

结论：与既有内置引擎（Kirikiroid2 / ONS / Artemis）保持一致，走内置 host。

### 3.2 数据流

```
App（游戏库卡片点击）
  └─ EngineLauncher.buildIntent(SIGLUS)
       extras: PATH/GAME_PATH/PROJECT_ROOT/GAME_DIR/LAUNCH_TARGET/ROOT_URI
               LAUNCH_MODE=internal.siglus、ORIENTATION、主题色、SIGLUS_LANGUAGE
  └─ SiglusActivity（:siglus 进程）
       ├─ SurfaceView.created → System.loadLibrary("siglus_bridge")
       ├─ siglus_android_init_context → siglus_android_create(nativeWindow, w, h, density, gameDir)
       ├─ Choreographer 每帧 → siglus_android_step(dt)
       ├─ 触摸/按键/IME → siglus_android_touch / key_event / text_input
       ├─ 每帧查询 IME 区域 → 显示/隐藏输入法
       └─ crate 成功后 → siglus_game_name_from_dir → 写 prefs（标题回写）
```

### 3.3 关键决策

1. **jniLibs 直打包**：`engine/src/main/jniLibs/arm64-v8a/libsiglus.so`，随 APK 分发，CI 不需要 Rust 工具链（与 `libkrkrsdl3.so` 同模式）；二期如需免发版升级再平移 `nativeplugin` zip。
2. **shim 自研**：不复制上游 `siglus_jni.cpp`（仅 ~500 行且缺少 IME/语言/元数据入口），在 `engine/src/main/cpp/siglus_bridge.cpp` 自研（dlopen `libsiglus.so`，只依赖系统库），规避 MPL 混入 engine 源码树。
3. **一期不含封面**：不接入 `cover.png` 等；标题回写按用户要求保留。
4. **权限**：Siglus 需要读游戏资源 + 写 `savedata/`、`key.toml` → 复用 `EngineLauncher.requestAllFilesAccessIfNeeded`（MANAGE_EXTERNAL_STORAGE）。
5. **独立存档一期不做**：引擎固定写游戏根；二期经 `SIGLUS_SAVE_DIR` 环境变量支持（见 §11）。

---

## 4. App 侧改动清单

### 4.1 引擎类型与展示

| 文件 | 改动 |
|---|---|
| `core/engine/EngineType.kt` | 新增 `SIGLUS("Siglus")` |
| `core/game/launch/EngineLauncher.kt` | `supportedEngines` 加入 `SIGLUS`（引擎页展示） |
| `ui/engine/EngineScreen.kt` | `engineTabOf` → GAL；`engineDisplayName` 保持 displayName；`engineDescription` 新增分支；内置引擎默认 `installed=true` + `engine_integrated`（无需弹窗，一期不进 `builtinDialogEntries`） |
| `ui/game/GameScreen.kt` | `EngineType.coverColor()` 新增占位色；`shouldShowSaveManagement` 保持「非外置/非模拟器 → 显示」 |
| `ui/home/HomeScreen.kt` | 无需改动（用 displayName） |

### 4.2 扫描识别（`core/game/scan/EngineScanner.kt`）

在 `detectEngine` 判定链中新增特征（插在 Artemis 之后、Tyrano 之前均可；特征无冲突）：

新增收集变量（`call` 收集阶段，目录白名单 `ENGINE_SEARCH_DIRECTORIES` 内生效）：
`hasGameexeDat`、`hasGameexeIni`、`hasScenePck`（根或 `Data/`）、`hasSelectIni`、`hasG00`。

判定与打分（`launchTarget = LAUNCH_TARGET_GAME_DIR`）：

| 条件 | 分数 |
|---|---|
| `Gameexe.dat` + `Scene.pck` | 96 |
| `Gameexe.ini` + `Scene.pck` | 95 |
| `Gameexe.dat/ini`（无 `Scene.pck`） | 85 |
| `Scene.pck` + `Select.ini` + 任一 `.g00` | 80 |
| 命中 `Select.ini` | +3（加分项，不单独成立） |

文件名匹配需大小写不敏感，并覆盖本地化变体 `Gameexe{EN,ZH,ZHTW,DE,ES,FR,ID}.{dat,ini}`（与引擎 `GAMEEXE_CANDIDATES` 对齐）。

新增单测 `EngineScannerSiglusTest`（参照 `EngineScannerPspSwitchTest`）：根特征命中/`Data/Scene.pck` 命中/大小写变体/仅 Gameexe 无 Scene.pck/不应误判（RPG Maker `Game.ini`、Artemis `root.pfs`、ONS 无特征）。

### 4.3 启动链路（`core/game/launch/EngineLauncher.kt`）

- `buildIntent` 新增分支（返回 `Intent(SiglusActivity)`）：
  - `PATH`/`GAME_PATH`/`PROJECT_ROOT`/`GAME_DIR` = 游戏根真实路径（沿用 `resolveGameDirectory`）；
  - `ROOT_URI`、`LAUNCH_TARGET`、`LAUNCH_MODE = LaunchContract.LAUNCH_MODE_SIGLUS`；
  - `ORIENTATION`（沿用现有方向参数）；
  - `SIGLUS_LANGUAGE` = 生效语言（auto 时传空 → 宿主不设置 env）；
  - 主题色 extras 由统一注入逻辑处理（`buildIntent` 尾部已有）。
- 前置检查：`requestAllFilesAccessIfNeeded`（与其它内置引擎一致）；
- 启动成功后：`recordRecentGame`；另外调用标题回写导入（见 §7.4）；
- 启动失败：沿用 `LaunchResult.Failure` 类型化返回与 `LaunchErrorMessages` 映射。

### 4.4 插件校验分支

`core/engine/plugin/EnginePluginBootstrap.kt` 的 `ensureForLaunch` `when (engine)` 新增 `SIGLUS -> true`（内置，无插件依赖）；漏改会编译失败（exhaustive when）。

### 4.5 存档（`core/game/save/GameSaveManager.kt`）

`resolveSaveLocation` 新增分支：

```
EngineType.SIGLUS -> SaveLocation(File(root, "savedata"), text(R.string.save_location_engine_game_dir, game.engine.displayName), true)
```

- `listSaveFiles` / `exportToZip` / `importFromZip` 自动复用（无须改动）；
- `cleanupAppData`：SIGLUS 不产生应用私有数据 → 不新增分支（删除游戏时游戏根内 `savedata` 由现有策略决定，与 KRKR 非镜像模式一致）；
- `excludeFor` 不新增（`.sav`/`.png` 均属正常存档）。

### 4.6 引擎设置（语言）

| 文件 | 改动 |
|---|---|
| `ui/settings/EngineSettingsMenuActivity.kt` | `EngineSettingsKind` 新增 `SIGLUS(R.string.engine_settings_siglus_title, R.drawable.ic_settings_engine)` |
| `ui/settings/SettingsScreen.kt` | `EngineSettingsDetailScreen` 的 `LazyListPlaceholder` 新增 `kind == SIGLUS` 卡片 `SiglusSettingsCard`（语言下拉，复用 Ren'Py 版本下拉的排版） |
| `core/settings/EngineSettingsStore.kt` | 常量 `SIGLUS_LANGUAGE_AUTO="auto"` 与 `JP/EN/ZH/ZHTW/DE/ES/FR/ID`；`getSigluLanguage/setSigluLanguage`（键 `siglus_language`，写入 `EnginePrefs.APP_PREFS`）；白名单归一 |
| `core/settings/EngineSettingsText.kt` | `sigluLanguageOptions()`：`auto → 跟随引擎默认（JP）`、`JP/EN/ZH/ZHTW/DE/ES/FR/ID` |
| `core/settings/EngineSettingsResolver.kt` + `ResolvedEngineSettings` | 新增 `sigluLanguage` 字段（全局 + 单游戏覆盖合并，`resolveAllowed` 白名单） |
| `core/settings/EffectiveEngineSettings.kt` | 语言值白名单归一（非法值回落 `auto`） |
| `core/settings/PerGameSettingsStore.kt` | 新增 `F_SIGLUS_LANGUAGE = "siglus_language"` 与分区 |
| `ui/settings/PerGameSettingsScreen.kt` | `when (game.engine)` 新增 SIGLUS 分支：语言下拉（跟随全局/具体语言）+ 信息卡（引擎名、存档目录、返回键说明） |

语言语义：`SIGLUS_LANGUAGE` 仅决定引擎 `GET_LANGUAGE` 系统调用返回值（多语言版本游戏据此切换文本），`auto` 表示不设置（引擎默认 `JP`）。

### 4.7 文案（三语言严格一致）

三份 `strings.xml` 同步新增（键名定稿见 §附录 C）：

- `engine_desc_siglus`（引擎页描述）
- `engine_settings_siglus_title`（设置入口标题）
- `engine_settings_siglus_language_title`、`engine_settings_siglus_language_auto`、`engine_settings_siglus_language_value_jp/en/zh/zhtw/de/es/fr/id`
- 复用已有 `save_location_engine_game_dir`（带 `%1$s` 参数，传 `game.engine.displayName`），无需新增存档文案
- 其余提示（返回键双击退出等）在宿主内用三语言资源 or 固定英文（宿主属 engine 模块，若需本地化则 engine 也需 strings，参考 ONS 宿主做法）

### 4.8 其它

- `core/game/storage/EngineDetectionRepository.kt`：`when (game.engine)` 走 `else -> null`（无需检测缓存）；
- `core/game/model/ScanGame.kt` / `GameEntity.kt`：不需要新字段（语言走设置，标题走回写）；
- `README.md`：引擎支持表新增 SiglusEngine 行；致谢补 `siglus_rs`（MPL-2.0）与许可证声明；
- `CONTEXT.md`：引擎列表中补 SiglusEngine 与「Siglus 标题回写」术语。

---

## 5. engine 侧改动清单

### 5.1 JNI shim：`engine/src/main/cpp/siglus_bridge.cpp`（新增）

- `dlopen("libsiglus.so", RTLD_NOW)` + `dlsym` 取函数指针（缓存到静态结构，`std::once_flag`）；
- JNI 方法（`com.core.siglus.NativeSiglus`）：
  - `nativeInitContext(Context)` → `siglus_android_init_context`（用 `JNI_OnLoad` 保存的 JavaVM + 全局引用）；
  - `nativeSetLanguage(String|null)` → `setenv("SIGLUS_LANGUAGE", v, 1)`（**必须在 create 之前**调用；传 null 不设置）；
  - `create/setSurface/resize/step/touch/keyEvent/textInput/imePreedit/destroy`；
  - `imeArea(handle, int[4]) -> boolean`（新增 ABI，见 §6.1）；
  - `gameNameFromDir(path)`（标题回写用）；
  - `messageboxCallback` 注册（JNI 回调 → Java `SiglusMessagebox.onNativeMessagebox`）。
- 与 `artemis_loader.cpp` 相同的容错：符号缺失仅记日志，`create` 前必检核心符号；失败抛 `IllegalStateException` 交由宿主 finish。

### 5.2 CMake

`engine/src/main/cpp/CMakeLists.txt` 新增：

```cmake
add_library(siglus_bridge SHARED siglus_bridge.cpp)
target_link_libraries(siglus_bridge ${android-lib} ${log-lib} ${dl-lib})
```

### 5.3 Java 宿主：`engine/src/main/java/com/core/siglus/`

| 类 | 职责 |
|---|---|
| `NativeSiglus.java` | JNI 包装（`System.loadLibrary("siglus_bridge")`） |
| `SiglusActivity.java` | 生命周期/帧循环/Surface/输入/返回键/IME 显隐/标题回写/消息框路由（改造自上游 `SiglusGameActivity`，去 Launcher 相关） |
| `SiglusTextInputView.java` | 复用 engine 现有 IME 模式（`org.tvp.kirikiri2.KrTextInputView` + `KrInputConnection` 同构）：`onCheckIsTextEditor`/`onCreateInputConnection`，回调 `commitText→textInput`、`setComposingText→imePreedit`、`finishComposingText→imePreedit(null)`、`sendKeyEvent→keyEvent` |

宿主关键行为（与上游一致处沿用，差异处标注）：

1. `onCreate`：全屏沉浸式（`WindowCompat` + `WindowInsetsControllerCompat`）；解析 `LaunchContract` extras，缺 `PATH` → Toast + finish；挂 `SurfaceHolder.Callback`、touch、back 回调；`applyImmersive()`；`setKeepScreenOn`。
2. `surfaceCreated`：`handle != 0` 时 `setSurface` 重挂（后台恢复不重建引擎）；否则 `ensureEngine()`。
3. `ensureEngine()`：`nativeInitContext` → `nativeSetLanguage`（若 extra 非空）→ `create(window, w, h, density, path)`；失败 finish；成功注册消息框回调、登记 handle。
4. 帧循环：`Choreographer.postFrameCallback`，`dt` clamp 250ms；`step > 0` → `finish()`；`step < 0` → 停循环保留最后一帧与日志（上游语义）。
5. `onPause` 停循环（不销毁引擎），`onDestroy` `destroy(handle)`。
6. 输入：
   - 触摸直接转发（Rust 侧做坐标换算）；
   - `onKeyDown/onKeyUp` 按 §7.1 映射为 VK 码，经 `keyEvent(handle, vk, text, isRepeat)` 转发；`KEYCODE_BACK` 由 `OnBackPressedCallback` 处理（单击 Escape、2s 双击退出）；
   - IME：每帧 `imeArea(handle)` → 需要时 `SiglusTextInputView` 获取焦点并 `showSoftInput`，不需要时隐藏并 `clearFocus`；`commitText`/`setComposingText`/`finishComposingText` 转发。
7. 消息框：`AlertDialog`（engine 侧传统 View 体系，与 ONS/Artemis 宿主一致），四类 kind 与 `submitMessageboxResult` 映射沿用上游（OK/OK+CANCEL/YES+NO/YES+NO+CANCEL，**用户逻辑值** 0/1/2）。
8. 标题回写：create 成功后调用 `gameNameFromDir(path)`，与目录名不同则写 `EnginePrefs.APP_PREFS` 的 `siglus_title.<pathHash>`（`pathHash = Integer.toHexString(path.hashCode())`），并与 app 侧约定同名；
9. 日志：`SIGLUS_LOG` 可由 `Intent` extra `siglus_log` 覆盖（默认 warn），便于真机排查。

### 5.4 Manifest / 资源

- `engine/src/main/AndroidManifest.xml` 新增：

```xml
<activity
    android:name="com.core.siglus.SiglusActivity"
    android:exported="false"
    android:process=":siglus"
    android:launchMode="singleInstance"
    android:taskAffinity="com.core.Siglus"
    android:screenOrientation="sensorLandscape"
    android:windowSoftInputMode="adjustNothing"
    android:configChanges="keyboard|keyboardHidden|navigation|orientation|screenLayout|screenSize|smallestScreenSize|uiMode|density|fontScale|locale|layoutDirection" />
```

- `engine/src/main/res/values/styles.xml` 新增 `Theme.Siglus`（`Theme.AppCompat.NoActionBar` + 全屏背景黑）。

### 5.5 契约与常量

- `engine/src/main/java/com/core/engine/LaunchContract.kt`：
  - `LAUNCH_MODE_SIGLUS = "internal.siglus"`；
  - `SIGLUS_LANGUAGE = "siglus_language"`；
  - `SIGLUS_PATH_HASH = "siglus_path_hash"`；
  - （二期）`SIGLUS_SCOPED_SAVE_DIR`。
- `engine/src/main/java/com/core/engine/EnginePrefs.kt`：`KEY_SIGLUS_TITLE_PREFIX = "siglus_title."`。

### 5.6 打包

- `engine/src/main/jniLibs/arm64-v8a/libsiglus.so`（20.5MB，随 APK）；
- `packaging.jniLibs.useLegacyPackaging = true` 已开启（无需改动）；
- CI 无需 Rust 工具链（.so 入库）。

---

## 6. fork（Weiss-UltimateSavior/siglus_rs）改动

### 6.1 新增 Android C ABI（`crates/siglus_scene_vm/src/android_host.rs` + `include/siglus.h`）

| 新 ABI | 语义 | 用途 |
|---|---|---|
| `int32_t siglus_android_ime_area(void* handle, int32_t* out_xywh)` | 返回 1 时表示当前有聚焦 editbox，并回填 **surface 像素** 的 x/y/w/h（Rust 侧用 viewport + 逻辑尺寸换算，复用触控换算代码）；返回 0 表示不需要 IME | 宿主每帧调用 → `showSoftInput`/`hideSoftInput` 与光标区域 |
| `int32_t siglus_android_editbox_accepts_direct_text(void* handle)` | 对应 `ctx.editbox_accepts_direct_text()` | 物理键盘文本直通判定 |
| `void siglus_android_key_event(void* handle, int32_t key_code, const char* text_utf8, int32_t is_repeat)` | 桌面语义合并：映射键 → `on_key_down`（`is_repeat && Enter/Space/Escape` 忽略）；未映射且非 editbox → `notify_wait_key()`；若 `accepts_direct_text` 且有 text → `on_text_input` | 键盘/软键盘输入统一入口 |

`host.rs` 需暴露：`notify_wait_key()`（转发 `vm.ctx.notify_wait_key()`）、`editbox_accepts_direct_text()`、`focused_editbox_ime_area()`（已有 `ctx` 方法）、`logical_size()`（已有）。

### 6.2 语言与存档（无需一期改动 / 二期）

- `SIGLUS_LANGUAGE`：**已支持**（`runtime/globals.rs` 读 `env::var`，默认 `JP`），一期仅需宿主 `setenv`，fork 零改动；
- 二期「独立存档」：`original_save::save_dir()` 增加 `SIGLUS_SAVE_DIR` 环境变量覆盖（保存/读取同一入口，读取时保留对 `<根>/savedata`、`<根>/save` 的兼容）。

### 6.3 构建流程（`platform/scripts/package_android_apk.sh`，可选）

- 修 G4：产出后只保留 `libsiglus.so`（删除重复的 `libsiglus_scene_vm.so`），APK 体积约减半。

### 6.4 版本与署名

- 记录构建基线 commit（如 `c17065c`）到 TyranorNext 的 `README.md` 与本文档；
- MPL-2.0：保留 `LICENSE-MPL-2.0` 与文件头声明，TyranorNext 侧 README 注明「引擎运行库来自 siglus_rs（MPL-2.0），源码见 fork 仓库」。

---

## 7. 关键交互细节

### 7.1 键盘映射（Android `KeyEvent` → Windows VK）

| Android | VK | 说明 |
|---|---|---|
| `KEYCODE_ESCAPE` / `KEYCODE_BACK` | 0x1B | 取消/返回 |
| `KEYCODE_ENTER` / `KEYCODE_NUMPAD_ENTER` | 0x0D | 确认 |
| `KEYCODE_SPACE` | 0x20 | 推进 |
| `KEYCODE_DEL` | 0x08 | Backspace（编辑框退格） |
| `KEYCODE_FORWARD_DEL` | 0x2E | Delete |
| `KEYCODE_TAB` | 0x09 | |
| `KEYCODE_SHIFT_LEFT/RIGHT` | 0x10 | |
| `KEYCODE_CTRL_LEFT/RIGHT` | 0x11 | 快进 |
| `KEYCODE_ALT_LEFT/RIGHT` | 0x12 | |
| `KEYCODE_META_*` | 0x5B | |
| `KEYCODE_DPAD_LEFT/UP/RIGHT/DOWN` | 0x25/0x26/0x27/0x28 | 菜单导航 |
| `KEYCODE_A`..`KEYCODE_Z` | 0x41..0x5A | |
| `KEYCODE_0`..`KEYCODE_9` | 0x30..0x39 | |
| `KEYCODE_F1`..`F12` | 0x70..0x7B | |

未映射且 `event.unicodeChar != 0` 的按键：走 `key_event(handle, 0, text, repeat)`（触发 `notify_wait_key` 与直接文本输入）。

### 7.2 IME / 文本输入流程

```
每帧（step 之后）:
  imeArea(handle) -> true ?  显示输入法（SiglusTextInputView 请求焦点 + showSoftInput）
                    -> false ? 隐藏输入法（hideSoftInput + clearFocus）
IME 事件:
  commitText(t)          -> textInput(handle, t)          // 引擎 commit 到 editbox
  setComposingText(t,…)  -> imePreedit(handle, t, 0, len) // 组合中
  finishComposingText()  -> imePreedit(handle, null)      // 取消组合
物理键盘:
  keyEvent(handle, vk, text, repeat)
```

- 复用 engine 现有 IME 类模式（`KrTextInputView`/`KrInputConnection`），保持与 KRKR 宿主一致的实现风格；
- 光标区域（CursorAnchorInfo）：一期可省略（多数 IME 无该信息也能工作），列为可选优化。

### 7.3 语言

```
设置（全局或单游戏）→ EngineSettingsResolver.sigluLanguage
  → EngineLauncher extras(SIGLUS_LANGUAGE)
  → 宿主 nativeSetLanguage(非 auto 时 setenv)
  → 引擎 create 时 SystemRuntimeState::default() 读取 env
  → 游戏脚本 GET_LANGUAGE 得到对应值
```

### 7.4 标题回写（GAMENAME）

```
宿主 create 成功 → gameNameFromDir(path)（引擎已持有 key，解析 Gameexe 得到 GAMENAME）
  → 与目录名不同时写 tyranor_prefs: siglus_title.<pathHash>
App（启动成功后 / 游戏库刷新时）→ 若 DB 中 title == 目录名（即未手工改名）→ 更新为 GAMENAME
```

- 回写失败/无 `GAMENAME` → 保持目录名（现有行为）；
- 用户手工改名保护：仅当 title 等于扫描默认值（目录名）时才覆盖。

### 7.5 存档

- 一期：`<游戏根>/savedata`（引擎自带兼容读取 `<根>/save`）；
- 存档镜像（导出/导入 zip）与列表自动复用；导入写回 `<根>/savedata`；
- 二期（可选）：`SIGLUS_SAVE_DIR` + 单游戏「独立存档」开关（目录 `getExternalFilesDir/save/siglus/<safeSaveName>`），与 KRKR/ONS 交互语义一致。

### 7.6 权限

- 与其它原生引擎一致：启动前 `requestAllFilesAccessIfNeeded`（Android 11+ `MANAGE_EXTERNAL_STORAGE`）；
- 无权限时：Siglus 仍可读（视目录而定），但 `savedata/` 与 `key.toml` 写入失败 → 需在 `PerGameSettingsScreen` 信息卡提示「需开启所有文件访问权限以保存存档」。

### 7.7 返回键与退出

- 单击返回 → 引擎 Escape（关闭菜单/取消选择）；
- 2 秒内双击 → 退出宿主（沿用上游语义，Toast 提示）；
- `step > 0`（游戏内退出到系统）→ `finish()`。

---

## 8. 构建与集成步骤

### 8.1 构建引擎库（fork 侧，一次性/每次更新）

```bash
cd /Users/weiss/Desktop/sg/siglus_rs
cargo ndk -t arm64-v8a -o /tmp/siglus-android build --release -p siglus_scene_vm
cp /tmp/siglus-android/arm64-v8a/libsiglus_scene_vm.so \
   /Users/weiss/opencode/rma/TyranorNext/engine/src/main/jniLibs/arm64-v8a/libsiglus.so
```

（或直接使用上游 release APK 内的 `lib/arm64-v8a/libsiglus.so`，基线 `1eadb38`，本项目已实测可解包取得。）

### 8.2 集成构建与校验

```bash
./gradlew :app:assembleDebug --no-daemon
./gradlew :app:testDebugUnitTest --no-daemon
python3 tools/check-hardcoded-ui-strings.py
git diff --check
```

### 8.3 CI 影响

- 无 Rust 工具链需求（.so 入库）；APK 体积 +20.5MB（arm64），Nativeplugin 打包任务不受影响；
- fork 侧如需 CI 产出 .so，可参考上游 `release.yml` 的 Android job（本方案不强制）。

---

## 9. 测试与验收

### 9.1 单元测试

| 用例 | 内容 |
|---|---|
| `EngineScannerSiglusTest` | 特征识别与打分（含大小写、`Data/Scene.pck`、本地化变体）、无误判 |
| `EngineSettingsResolverTest`（扩展） | SIGLUS 语言：全局/单游戏覆盖、非法值回落 auto |
| `GameSaveManagerTest`（扩展） | SIGLUS → `<根>/savedata`；导入导出往返 |

### 9.2 真机验收（至少 1 款 Siglus 游戏）

1. 扫描识别为 Siglus，标题/封面正常展示；
2. 启动进入游戏：标题画面 → 触摸推进对话 → 打开菜单/存档 → 读档；
3. 保存后重进（含杀进程重进）存档仍在（`savedata/` 有新文件；首次启动生成 `key.toml`）；
4. 返回键单击 = 取消/关闭菜单，双击 = 退出；
5. 硬件键盘（或 adb `input keyevent`）空格/回车推进、Ctrl 快进；
6. 名字输入场景：软键盘弹出、输入提交、组合文本正常（§7.2）；
7. 语言切换（若游戏支持多语言）：设置 JP/EN 后 `GET_LANGUAGE` 生效；
8. 标题回写：首次启动后游戏库标题由目录名变为 GAMENAME；
9. 后台返回：切后台再回前台不重启引擎（进度保留）；
10. 存档镜像：导出 zip → 清档 → 导入 → 读档恢复。

### 9.3 回滚

- 移除 `EngineType.SIGLUS` 相关分支 + 删除 `libsiglus.so` 即可回到接入前（无数据迁移风险；标题回写仅改 title 字段，可直接在库中改回）。

---

## 10. 风险与待确认

| 风险 | 影响 | 缓解 |
|---|---|---|
| wgpu/Vulkan 兼容（部分老设备无 Vulkan） | 无法渲染 | 上游支持 GLES 后端（wgpu），真机覆盖测试；不达标则文档标注设备要求 |
| 上游 API 变动 | 重新编译 .so 即需回归 | 固定基线 commit；只依赖 `siglus_android_*` 稳定 ABI |
| 首次启动爆破 EXE key 慢 | 首启等待 | 上游行为，加载态提示（宿主 Toast/等待动画可选） |
| minSdk 26 vs 上游 28 | 老设备崩溃 | 发布说明标注 Android 9+；或在 `EngineLauncher` 做版本判断提示 |
| 游戏根只读（SD 卡/Android/data） | 存档与 key 写入失败 | 复用 all-files 流程；二期 `SIGLUS_SAVE_DIR` 独立存档 |
| MPL-2.0 合规 | 许可证冲突质疑 | 保留声明 + README 注明源码（fork）链接；shim 自研不混入 |
| 宿主 IME 与游戏内输入法重叠 | 输入异常 | 与 KRKR 宿主行为对齐；IME 仅在 `imeArea` 为真时显示 |

待确认（评审时定）：

1. 语言下拉是否包含 `ZHTW/DE/ES/FR/ID`（一期给全量，若嫌冗长可只留 `auto/JP/EN/ZH`）；
2. 标题回写是否默认开启（建议开启，且仅覆盖「未手工改名」的标题）；
3. 是否在引擎页 SIGLUS 行加信息弹窗（显示引擎来源/版本基线，默认不加，保持 `engine_integrated`）。

---

## 11. 里程碑（建议实施顺序）

| 阶段 | 内容 | 依赖 | 预估 |
|---|---|---|---|
| M0 | fork 补 ABI（§6.1）+ 重建 .so；入 `jniLibs` | 无 | 0.5 天 |
| M1 | engine：shim + 宿主 + Manifest + 契约常量 | M0 | 1 天 |
| M2 | app：EngineType/扫描/启动/存档/插件分支 + 文案 | M1 | 1 天 |
| M3 | 语言设置链路（Store/Resolver/Settings UI/单游戏覆盖） | M2 | 1 天 |
| M4 | IME/键盘接线 + 标题回写 + 真机验收 | M3 | 1 天 |
| M5 | README/CONTEXT 更新 + 回归（单测/三语言/构建） | M4 | 0.5 天 |
| 二期 | 独立存档、nativeplugin 化、封面（cover.png）、打包去重、光标锚点 | M5 | 待排 |

---

## 附录 A：ABI 对照表（一期）

| shim JNI | C ABI | 备注 |
|---|---|---|
| `nativeInitContext` | `siglus_android_init_context` | create 前必须 |
| `nativeSetLanguage` | `setenv("SIGLUS_LANGUAGE")` | 通过 libc，非引擎符号 |
| `create` | `siglus_android_create` | |
| `setSurface` | `siglus_android_set_surface` | |
| `resize` | `siglus_android_resize` | |
| `step` | `siglus_android_step` | |
| `touch` | `siglus_android_touch` | |
| `keyEvent` | `siglus_android_key_event` | 新增（fork） |
| `textInput` | `siglus_android_text_input` | |
| `imePreedit` | `siglus_android_ime_preedit` | |
| `imeArea` | `siglus_android_ime_area` | 新增（fork） |
| `acceptsDirectText` | `siglus_android_editbox_accepts_direct_text` | 新增（fork，可选内联进 keyEvent 判定） |
| `destroy` | `siglus_android_destroy` | |
| `gameNameFromDir` | `siglus_game_name_from_dir` | 标题回写 |
| `stringFree` | `siglus_string_free` | 元数据释放 |
| 消息框回调 | `siglus_android_set_native_messagebox_callback` + `submit_messagebox_result` | |

## 附录 B：检测特征对照（EngineScanner）

| 特征 | 文件名/路径 | 大小写 |
|---|---|---|
| Gameexe（主） | `Gameexe.dat`、`Gameexe.ini` | 不敏感 |
| Gameexe（本地化） | `GameexeEN/ZH/ZHTW/DE/ES/FR/ID.{dat,ini}` | 不敏感 |
| Scene | `Scene.pck`（根）、`Data/Scene.pck` | 不敏感 |
| append | `Select.ini` | 不敏感 |
| 资源提示 | 根或搜索目录内任一 `*.g00` | 不敏感 |

## 附录 C：新增文案键（三语言）

| 键 | zh | ja | en |
|---|---|---|---|
| `engine_desc_siglus` | SiglusEngine（Key/VisualArts 系） | SiglusEngine（Key/VisualArts 系） | SiglusEngine (Key / VisualArts) |
| `engine_settings_siglus_title` | Siglus | Siglus | Siglus |
| `engine_settings_siglus_language_title` | 游戏语言 | ゲーム言語 | Game language |
| `engine_settings_siglus_language_auto` | 跟随引擎默认（JP） | エンジン既定（JP） | Engine default (JP) |
| `engine_settings_siglus_language_value_jp` | 日语（JP） | 日本語（JP） | Japanese (JP) |
| `engine_settings_siglus_language_value_en` | 英语（EN） | 英語（EN） | English (EN) |
| `engine_settings_siglus_language_value_zh` | 简体中文（ZH） | 簡体字中国語（ZH） | Simplified Chinese (ZH) |
| `engine_settings_siglus_language_value_zhtw` | 繁体中文（ZHTW） | 繁体字中国語（ZHTW） | Traditional Chinese (ZHTW) |
| `engine_settings_siglus_language_value_de` | 德语（DE） | ドイツ語（DE） | German (DE) |
| `engine_settings_siglus_language_value_es` | 西班牙语（ES） | スペイン語（ES） | Spanish (ES) |
| `engine_settings_siglus_language_value_fr` | 法语（FR） | フランス語（FR） | French (FR) |
| `engine_settings_siglus_language_value_id` | 印尼语（ID） | インドネシア語（ID） | Indonesian (ID) |

> 实际文案以项目三语言校对习惯为准；键名与数量以本表为基线，新增后必须通过 `tools/check-hardcoded-ui-strings.py`。

---

## 实施记录（M0–M5）

### M0 fork 改动（`/Users/weiss/Desktop/sg/siglus_rs`，未提交）

| 文件 | 改动 |
|---|---|
| `crates/siglus_scene_vm/src/host.rs` | 新增 `SiglusHost::key_event(code, text, is_repeat)`（桌面 KeyboardInput 语义：映射键去重、未映射键 `notify_wait_key`、editbox 直通文本）、`editbox_accepts_direct_text()`、`focused_editbox_ime_area()`、`notify_wait_key()` |
| `crates/siglus_scene_vm/src/android_host.rs` | 新增导出 `siglus_android_key_event` / `siglus_android_ime_area`（复用 aspect-fit viewport 换算为 surface 像素）/ `siglus_android_editbox_accepts_direct_text` |
| `crates/siglus_scene_vm/include/siglus.h`、`platform/android/app/src/main/cpp/include/siglus.h` | 同步新 ABI 声明 |

构建与入库：

```bash
cd /Users/weiss/Desktop/sg/siglus_rs
ANDROID_NDK_HOME=$HOME/Library/Android/sdk/ndk/28.0.13004108   cargo ndk -t arm64-v8a -o /tmp/siglus-android build --release --lib -p siglus_scene_vm
cp /tmp/siglus-android/arm64-v8a/libsiglus_scene_vm.so    /Users/weiss/opencode/rma/TyranorNext/engine/src/main/jniLibs/arm64-v8a/libsiglus.so
```

实测：构建约 5 分钟；产物 20,484,936 B（与上游 20,493,704 B 同量级，仅依赖系统库）；`siglus_android_key_event/ime_area/editbox_accepts_direct_text` 已确认导出。

### M1–M3 engine/app 改动

- engine：`cpp/siglus_bridge.cpp`（自研 JNI shim：dlopen + JNI_OnLoad 缓存 JavaVM/NativeSiglus、`setenv("SIGLUS_LANGUAGE")`、消息框回调、IME/文本/元数据）、CMake 目标 `siglus_bridge`、`com/core/siglus/{NativeSiglus,SiglusActivity,SiglusTextInputView}.java`、Manifest `:siglus` 条目、`Theme.Siglus`、`LaunchContract`（`LAUNCH_MODE_SIGLUS`/`SIGLUS_LANGUAGE`/`SIGLUS_PATH_HASH`）、`EnginePrefs`（`siglus_title.`/`siglus_uri.`/`siglus_default_title.` 前缀）、engine 三语言文案（`engine_siglus_*` 3 键 ×3）。
- app：`EngineType.SIGLUS`、`EngineScanner` 特征（`GAMEEXE_DAT_RE`/`GAMEEXE_INI_RE`/`scene.pck`/`select.ini`/`.g00`；评分 96/95/85/80）+ `EngineScannerSiglusTest`（6 例）、`EngineLauncher.buildSiglusIntent`（含标题回写登记）、`EnginePluginBootstrap` 分支、`GameSaveManager` SIGLUS → `<root>/savedata`、`EngineScreen`（GAL/描述）、`GameScreen` 占位色、`SiglusTitleFeedback`（库加载导入）+ `MainLibraryViewModel` 挂载、`strings.xml ×3`（13 键）。
- 设置：`EngineSettingsStore`（`siglus_language` + 9 值白名单）、`EngineSettingsResolver`/`ResolvedEngineSettings.siglusLanguage`、`EngineSettingsText.siglusLanguageOptions(Map)`、`EngineSettingsKind.SIGLUS` + 全局卡片、`PerGameSettingsStore.F_SIGLUS_LANGUAGE` + 单游戏卡片、`GameOverridePartitions.KEY_SIGLUS_LANGUAGE`（随 tyrano 分区持久化）。

### 与方案的偏差（已实现口径）

1. 语言覆盖的分区：DB 无 SIGLUS 列，`siglus_language` 显式列入 `TYRANO_KEYS`（tyrano 分区即既有「剩余字段」分区），无 Room 迁移。
2. 标题回写协议：App 启动前登记 `siglus_uri.<hash>` 与 `siglus_default_title.<hash>`，宿主仅写 `siglus_title.<hash>`；导入仅在「库标题 == 登记目录名」时覆盖，用户改名有保护。
3. 导入时机：`MainLibraryViewModel` 初始化（库加载）消费，而非启动回调（宿主写入发生在启动之后）。
4. IME 光标锚点已用 `CursorAnchorInfo` 实现（原方案列为可选）。
5. 测试：`EngineScannerSiglusTest` 覆盖 dat/ini/本地化/Data-Scene/低置信/误判；`GameOverridePartitionsTest` 增补 SIGLUS 键断言。

### 验证结果

- `./gradlew :app:assembleDebug` 通过；APK 含 `lib/arm64-v8a/libsiglus.so`（20.5MB）与 `libsiglus_bridge.so`，合并 Manifest 含 `SiglusActivity`/`:siglus`。
- `./gradlew :app:testDebugUnitTest` 通过；`python3 tools/check-hardcoded-ui-strings.py` 与 `git diff --check` 通过。
- 真机验收（§9.2 十项）尚未执行，待安装设备后回归；Siglus 需 Android 9+（上游 minSdk 28）。
