# System3.x（xsystem35-sdl2）内置引擎接入方案

> 状态：**已实施（一期，2026-10-13）**
> 上游：`kichikuou/xsystem35-sdl2`（GPL-2.0-or-later）；本地构建来源为 fork [Weiss-UltimateSavior/xsystem35-sdl2](https://github.com/Weiss-UltimateSavior/xsystem35-sdl2)（固定 commit `18af9be`，本地勘察仓库 `/Volumes/HUAWEI-M2LITE/app/xsystem35-sdl2`）；**引擎与依赖源码不 vendored 进主仓**（构建时由 `engine/xsystem35/scripts/build-xsys35.sh` 准备到仓库外目录，见 `engine/xsystem35/BUILD.md`）
> 结论摘要：**内置 host 路线**（SDLActivity 主循环宿主）＋ **xsystem35 及其依赖源码不 vendored 进主仓（构建时由脚本准备到仓库外）、CMake 预构建入库**（jniLibs 直打包）＋ **SDL2 2.32.10 Java 层生成到 `org.libsdl.app32` 新包**（现有 `org.libsdl.app` 为 ONS 专用的 2.26.3，其 onCreate 版本硬校验与 2.32.10 native 必然冲突，不可复用，见 §3.4）。
> 一期范围 = 扫描识别 + 启动 + 存档镜像 + 基础显示/音频设置 + 真机验收；不含 CD 镜像安装（用户按 xsystem35 ZIP 目录格式放置游戏即可）。

---

## 0. 术语与命名

| 项 | 取值 |
|---|---|
| EngineType | `XSYS35`（新增） |
| 显示名（`EngineType.displayName`） | `System35` |
| 角标（`abbr`） | `S3X` |
| 引擎页分组 | GAL（`EngineTab.GAL`） |
| launchMode 取值 | `internal.xsys35` |
| 宿主类 | `com.core.xsys35.Xs35Activity`（进程 `:xsys35`） |
| 运行库（引擎库） | `libxsystem35.so`（上游 C 源码构建） |
| SDL2 运行库（重命名） | `libSDL2x.so`（避免与 APK 内 ONS 插件同名冲突，见 §3.4） |
| SDL2 Java 层 | vendored SDL2 **2.32.10** Java 层整包改名 `org.libsdl.app32`（现有 `org.libsdl.app` 为 ONS 专用的 2.26.3，其 `onCreate` 版本硬校验与 2.32.10 native 必然冲突，不可复用，见 §3.4） |
| 附库 | `libSDL2_mixer_xs35.so`（若以共享库产出）；freetype / libwebp 为**硬依赖**（Android 分支强制 `HAVE_WEBP=1`）但默认静态链入 `libxsystem35.so`——独立 .so 是否产出以构建产物实测为准（§3.3 / §5.4） |
| 内置字体 | `MTLc3m.ttf`（ゴシック）、`mincho.ttf`（明朝）→ engine assets |
| 存档目录（一期） | 独立存档开关（默认**开**）：`getExternalFilesDir(null)/save/<safeSaveName>`；关闭 = `<游戏根>/save` |
| 存档文件名 | `<场景基名>s[a-z].asd` + `Volume.sav` + `*.asd.` 备份（镜像按目录整体处理，无需白名单） |
| 引擎设置分类 | 新增 `EngineSettingsKind.XSYS35` |
| 识别特征 | 根目录 `*.ALD`（`SA.ALD` 等）+ 可选 `System39.ain`/`System39.ini`/`*.wai`/`*.bgi`/`*.alk` |

---

## 1. 背景

xsystem35 是 AliceSoft **System 3.x** 游戏引擎的开源重实现（GPL-2.0-or-later），`xsystem35-sdl2` 是它的 SDL2 跨平台移植。Android APK 上游已稳定供档（v2.20.0，minSdk 21，targetSdk 34），支持：

- 从游戏目录直接运行（`GAMEDATA` 布局：`*.ALD` 场景/图形/音频档）；
- CD 音频虚拟化（`playlist.txt` 列 mp3/ogg 曲目，无需光驱）；
- MIDI 经 Android `MediaPlayer` 播放（`src/midi.android.c`）；
- 存档导入/导出（`save/*.asd` zip 往返）。

接入价值：补齐 TyranorNext 对 System 3.x 系（兰斯系列、闘神都市、DiaboLiQuE 等大量 AliceSoft 经典）的「扫描识别 → 启动 → 存档」全链路支持。

---

## 2. 上游勘察结论（源码级）

### 2.1 Android 宿主与运行时构建（核心）

| 项 | 结论 | 来源 |
|---|---|---|
| 构建系统 | 独立 Gradle 工程 `android/`；`app/` 同时承载 xsystem35 Kotlin 代码与 SDL2 的 Android Java 层（`org/libsdl/app/*.java`，完整项目副本） | `android/app/build.gradle`、`android/app/src/main/java/` |
| 原生构建 | `externalNativeBuild.cmake` → `app/jni/CMakeLists.txt`：**FetchContent** 拉取 SDL2 2.32.10 / freetype 2.14.3 / SDL2_mixer 2.8.0 源码并作为子工程构建；**libwebp 1.5.0 由根 `CMakeLists.txt` 的 `fetch_webp()` 拉取**（Android 分支强制调用并 `set(HAVE_WEBP 1)`，非可选依赖）；再 `add_subdirectory(../../../)` 构建 xsystem35 本体 | `android/app/jni/CMakeLists.txt`、根 `CMakeLists.txt:32-51,73-82` |
| 构建参数 | `-DANDROID_APP_PLATFORM=android-19 -DANDROID_STL=c++_static`，abiFilters `armeabi-v7a,arm64-v8a` | `android/app/build.gradle` |
| 引擎库产物 | `libxsystem35.so`（`add_library(xsystem35 SHARED)`，Android 分支；其余平台为可执行文件）；`main()` 由 SDL2 的 `nativeRunMain` 调用 | `src/CMakeLists.txt:29-36`、`src/xsystem35.c:458` |
| 宿主基类 | `GameActivity : SDLActivity`（`org.libsdl.app`），声明 `:game` 独立进程、`alwaysRetainTaskState` | `GameActivity.kt`、`AndroidManifest.xml` |
| 启动参数 | `getLibraries() = ["SDL2","xsystem35"]`（顺序即加载顺序，主库取末项）；`getArguments() = ["-gamedir", <游戏根>, "-savedir", <存档目录>]` | `GameActivity.kt:74-83` |
| -gamedir 语义 | `main()` 内 `strcmp(argv[1],"-gamedir")==0` 才 `chdir(argv[2])`（**无 argc 校验、argv[1] 无条件解引用**：完全无参 → 段错误；`argv[1]` 非 `-gamedir` → 跳过 chdir 继续运行），再加载 profile（`.xsys35rc`）→ **`-gamedir` 必须成对置于首位、游戏路径成为进程 CWD** | `src/xsystem35.c:458,463-467` |
| Java ← native 回调 | 经 `SDL_AndroidGetActivity()` 反射调用：`inputString(msg,old,max)`、`inputNumber(msg,min,max,init)`（阻塞式弹窗）、`midiStart(path,loop)`、`midiStop()`、`midiCurrentPosition()`；MIDI 临时文件写 `SDL_AndroidGetInternalStoragePath()/tmp.mid` | `GameActivity.kt:136-167,170-238`、`src/input_modal.c`、`src/midi.android.c` |
| 标题 | native 经 `setTitle` 回传；宿主把标题写入游戏目录 `title.txt`（上游自有库管理用） | `GameActivity.kt:84-93` |
| SDL2 补丁 | 仅一处：`SDLActivity.setWindowStyle(true)`（绕 SDL#8995），无其它 fork | `GameActivity.kt:48-49` |
| 退出 | `onDestroy` → `Process.killProcess(myPid())`（与 TyranorNext 各宿主同策略，注意先 `midi.release()`） | `GameActivity.kt:62-72` |
| 字体 | 默认字体 `MTLc3m.ttf`/`mincho.ttf` 由构建期拷到 APK assets；引擎在 `open_face` 中优先 `FT_New_Face`，失败后走 `SDL_RWFromFile`（可读 assets）→ **必须把两份字体放到 APK assets 根**；Android 下打不开会 `SYSERROR` 终结 | `android/app/jni/CMakeLists.txt:45-57`、`src/font.c:108-144,153-157`、`config.h.in` |
| 存档 | 扫描目录后按场景档基名生成 `<savedir>/<basename>s[a-z].asd`（Android/Emscripten 强制小写）；`Volume.sav` 由 `save_path` 派生 | `src/gameresource.c:38-156`（`:124-138`）、`src/volume.c:116` |
| 引擎剖面 | `.xsys35rc`（**查找顺序 `$HOME/.xsys35rc` → 相对路径 CWD**（`src/profile.c:134-147`）；`-gamedir` 经 `chdir` 成为 CWD 被第二次查找命中，Android 进程通常无 `HOME`）：`savedir`/`ttfont_*`/`antialias`/`audio_buffer_size`/`render_driver`/`playlist`/`integerscale`/`mute_on_unfocus` 等 | `src/profile.c:134-147`、`src/xsystem35.c:299-406`（check_profile）、`xsys35rc.sample` |
| 本地化 | 上游 po/ja 等；Android 构建未启用 libintl（`HAVE_LIBINTL` 不定义），走内置 NLS catalog（`nls.c`） | `CMakeLists.txt:160-169`、`src/CMakeLists.txt:145-147` |
| 体积 | 源码 `src/` 1.5 MB + `modules/` 0.7 MB；预构建产物预计：`libxsystem35.so` ~2–3 MB（freetype/libwebp 静态链入并入估算）+ SDL2 ~1.4 MB + SDL2_mixer ~0.5 MB（strip 后，arm64） | 本次勘察估算 |

### 2.2 依赖清单（Android 分支）

```
xsystem35 ──┬── SDL2（2.32.10, zlib）        必须；宿主/输入/音频/窗口/GL
            ├── SDL2_mixer（2.8.0, zlib）    必须；CD 音频（mp3/ogg 经内置解码）
            ├── freetype（2.14.3, FTL/GPLv2）必须；字体光栅化（仅自带光栅器）
            ├── libwebp（1.5.0, BSD-3）      必须；HAVE_WEBP=1，CG 支持 webp
            └── zlib / log                    NDK 系统库
```

- Android 分支不使用 PortMidi/cJSON（调试器关闭；MIDI 走 Java MediaPlayer）。
- `ENABLE_DEBUGGER` 默认 ON 但仅非 Android 分支编译（`src/CMakeLists.txt:134-137` 只在非 Android 时含 debugger 源）——Android 构建可忽略。
- **依赖构建形态（决定 §3.3 打包清单）**：上游 Android 构建未设 `BUILD_SHARED_LIBS`；freetype/libwebp 默认**静态链入** `libxsystem35.so`（`src/CMakeLists.txt:131` `target_link_libraries(xsystem35 PRIVATE WebP)`、`:149` `src_lib PRIVATE sdl2 freetype2 zlib`），SDL2_mixer 是否产出独立 .so 以构建产物实测为准——jniLibs 清单以 §5.4 readelf 实测回填。

### 2.3 缺口 / 需自研补的部分

| # | 缺口 | 处理 |
|---|---|---|
| G1 | SDL2 运行库与既有 ONS 插件**同名冲突**（`libSDL2.so`） | 重命名新库为 `libSDL2x.so`（§3.4） |
| G2 | xsystem35 无「启动器」概念（上游自带游戏列表/安装器） | TyranorNext 侧只复用 `GameActivity` 形态：`-gamedir/-savedir` 直启，不搬 Launcher/GameStore/GameInstaller |
| G3 | 无引擎设置持久化（只有 `.xsys35rc`） | **不注入 rc**：profile 查找顺序为 `$HOME/.xsys35rc` → 相对路径 CWD（chdir 后即 gamedir，`src/profile.c:134-147`）；可表达的设置一律走命令行参数（`-savedir`/`-renderer` 等，§6.6），不写用户游戏目录 |
| G4 | 无 IME 文本框（输入弹窗是原生 `EditText`） | 一期不做（System3.x 输入极少） |
| G5 | 无独立存档目录开关 | **引擎侧无需改动**：`-savedir` 是自由路径，App 侧按 ONS 先例一期即提供「独立存档」开关（§4.6、§6.5） |
| G6 | 无返回键→Escape 映射 | SDL2 原生 `back` 行为：`SDL_ANDROID_TRAP_BACK_BUTTON=1`（引擎已设）+ 宿主「双击返回退出」策略（对齐其它宿主） |
| G7 | 无标题回写协议 | 宿主覆盖 `Activity.setTitle()` 捕获 native 标题，写 prefs（`xsys35_title.<pathHash>`）→ 复用 Siglus 回写导入协议（§6.7） |
| G8 | 共享 `org.libsdl.app`（2.26.3）Java 层在 `onCreate` 对 `nativeGetVersion()` **硬校验**（≠"2.26.3" 即弹「SDL C/Java version mismatch」退出，子类不可绕过），与 2.32.10 native 混用必然失败 | 不复用该 Java 层：vendored SDL2 2.32.10 Java 层整包改名为 `org.libsdl.app32`（§3.4）；现有 `org.libsdl.app` 保留给 ONS，不动 |

---

## 3. 总体设计

### 3.1 路线选择

| | 外置 APK 模块（External Engine Module） | **内置 host（选定）** |
|---|---|---|
| 主 APK 体积 | 不变 | +4–6 MB（arm64，strip 后，含依赖库） |
| 接入量 | 模块工程 + 注册表 + 安装协议 | 扫描/启动/宿主/设置补齐 |
| 体验 | 双 APK、模块需自行授权 | 单 APK；all-files 流程/存档镜像/单游戏设置/引擎页全复用 |
| 许可 | 进程隔离最干净 | GPL-2.0-or-later：随 App 分发或下载分发都需提供源码；见 §9 许可 |
| 兼容性风险 | 与主 App 的 SDL2 隔离最彻底 | 需处理 `libSDL2.so` 同名冲突（§3.4），已给出方案 |

结论：与 KIRIKIROIID/ONS/Siglus/FVP 的内置引擎先例一致，走内置 host；SDL2 同名冲突与 Java 层版本硬校验用「运行库重命名 + vendored 2.32.10 Java 层新包 `org.libsdl.app32`」一并解决（§3.4），不 fork 上游引擎本体。

### 3.2 数据流

```
App（游戏库卡片点击）
  └─ EngineLauncher.buildXs35Intent(XSYS35)
       extras: PATH/GAME_PATH/PROJECT_ROOT/GAME_DIR、LAUNCH_TARGET、ROOT_URI
               LAUNCH_MODE=internal.xsys35
               XS35_SAVE_DIR（绝对路径）、XS35_SCOPED_SAVE_DIR、XS35_RENDERER、
               XS35_INTEGER_SCALE、XS35_MUTE_UNFOCUS、XS35_ORIGINAL_ROOT、
               ORIENTATION、主题色 extras
  └─ Xs35Activity（:xsys35 进程，extends org.libsdl.app32.SDLActivity ← vendored 2.32.10）
       ├─ loadLibraries()：System.loadLibrary("SDL2x") → "xsystem35"（对齐上游：只显式加载 SDL2 与主库；SDL2_mixer/freetype/libwebp 经 DT_NEEDED 自动解析，独立 .so 形态以构建产物为准）
       ├─ getArguments()：["-gamedir", <游戏根>, "-savedir", <存档目录>, 可选 "-renderer" 等]
       ├─ SDL 原生主线程：nativeRunMain("libxsystem35.so", "SDL_main", args)
       │    └─ xsystem35 main()：chdir(gamedir) → 加载 profile → initGameResource → SDL_Init → 游戏循环
       ├─ Java 回调：inputString/inputNumber（原生弹窗）、midiStart/midiStop/midiCurrentPosition（MediaPlayer）
       ├─ setTitle 回调：宿主解析标题 → 写 xsys35_title.<pathHash>（库加载时导入，§6.7）
       └─ 返回键：单击 = SDL back（引擎语义）、2 秒内双击 = 退出确认（宿主）
```

### 3.3 引擎库打包形态（选定）

```
engine/
  xsystem35/                    ← vendored 上游源码（src/ modules/ po/ fonts/ licenses/ CMakeLists.txt）
    third_party/                ← 依赖源码（SDL2 2.32.10 / freetype 2.14.3 / SDL2_mixer 2.8.0 / libwebp 1.5.0）
    android-host/               ← 最小构建剧本（等价上游 android/app/jni/CMakeLists.txt，见 §5.4）
    BUILD.md                    ← 复现构建说明（NDK 版本、命令、产物路径、patch 说明、产物 SHA256）
    patches/                    ← 对依赖的最小 patch（SDL2 SONAME 重命名 / SDL_android.c app32 前缀等）
engine/src/main/java/org/libsdl/app32/
  SDLActivity.java 等 10 文件    ← vendored SDL2 2.32.10 Java 层（package org.libsdl.app → org.libsdl.app32）
engine/src/main/jniLibs/arm64-v8a/
  libxsystem35.so               ← 预构建入库（与 librfvp.so / libsiglus.so 同模式）
  libSDL2x.so                   ← 预构建入库（SONAME=libSDL2x.so，避免与 ONS 插件冲突）
  libSDL2_mixer_xs35.so*        ← *仅当以共享库产出时入库；freetype / libwebp 为硬依赖但默认静态链入
                                   libxsystem35.so（无独立 .so，§2.2）——以 §5.4 readelf 实测为准回填本清单
engine/src/main/assets/
  MTLc3m.ttf  mincho.ttf        ← 内置字体（xsystem35 默认字体，缺失会 SYSERROR）
```

- **构建期**：一次性用 NDK（`ANDROID_STL=c++_static`、`ANDROID_APP_PLATFORM=android-26`）在本地/CI 外构建；Gradle 不新增原生构建（engine 模块 CMake 只保留桥/工具库）。
- **预构建入库**与 `librfvp.so`/`libsiglus.so` 先例一致；**不需要** `FetchContent`（依赖源码 vendored 后再构建，避免网络依赖）。
- 体积：预计 APK 增加 4–6 MB（arm64）；可接受。

### 3.4 SDL2 冲突分析（关键决策）

现状事实：

- APK `jniLibs` 已有 `libSDL3.so`（krkrsdl3），**没有** `libSDL2.so`。
- ONS 插件（nativeplugin zip）自带 `libSDL2.so`（2.26.3，SONAME `libSDL2.so`），运行时经 `NativeLibraryLoader` **绝对路径** `System.load` 从插件目录加载；`libonsyuri.so` 的 `DT_NEEDED` 含 `libSDL2.so`，由动态链接器在 app 库目录/已加载表中解析。
- **若把 xsystem35 的 `libSDL2.so`（2.32.10）直接放进 APK**：SONAME 与 ONS 插件库相同即产生解析歧义 → ONS 崩溃风险。**不可接受。**
- **现有 `org.libsdl.app`（2.26.3）Java 层在 `onCreate` 里对 native 版本做硬校验**：`loadLibraries()` 之后调 `nativeGetVersion()`，与 `"2.26.3"` 不等即置 `mBrokenLibraries` → 弹「SDL Error」对话框 → finish（`SDLActivity.java:943-964`，onCreate 内部流程，子类无法绕过）。`nativeGetVersion()` 来自 `libSDL2x.so`（2.32.10），必然返回 `"2.32.10"` ≠ `"2.26.3"`——**「复用 2.26.3 Java 层 + 2.32.10 native」不是风险项，是 100% 确定的启动失败**。

选定方案：**重命名冲突库 + vendored 2.32.10 Java 层新包 `org.libsdl.app32`**：

| 库 | APK 内文件名 | SONAME | 加载方式 |
|---|---|---|---|
| xsystem35 的 SDL2 | `libSDL2x.so` | `libSDL2x.so` | `System.loadLibrary("SDL2x")` |
| ONS 的 SDL2 | 只存在于插件目录 | `libSDL2.so` | 插件绝对路径加载（不变） |
| SDL2_mixer | `libSDL2_mixer_xs35.so`（若以共享库产出） | 同名重命名 | DT_NEEDED 自动解析（上游只显式加载 SDL2 与主库） |
| freetype / libwebp | 无独立 .so（默认静态链入 `libxsystem35.so`；以构建产物实测为准，§2.2） | — | — |
| xsystem35 本体 | `libxsystem35.so` | `libxsystem35.so` | `System.loadLibrary` |

- 运行库重命名通过 vendored SDL2 的最小 patch（`target_properties OUTPUT_NAME/SONAME = SDL2x`）实现，不改渲染/输入等源码逻辑。
- **Java 层不复用现有 2.26.3 类，改用 vendored SDL2 2.32.10 Java 层整体改包为 `org.libsdl.app32`**（与 `org.libsdl.app` 并存、互不影响——ONS 宿主继续用 2.26.3 类，其版本校验天然通过）：
  - Java 侧：2.32.10 `android-project` Java 源（SDLActivity/SDL/SDLSurface/SDLAudioManager/SDLControllerManager/SDLInputConnection/HIDDeviceManager 等）落 `engine/src/main/java/org/libsdl/app32/`，`package` 声明与内部互引同步 `org.libsdl.app` → `org.libsdl.app32`；
  - native 侧：patch SDL2 `src/core/android/SDL_android.c`——JNI 符号由 `#define SDL_JAVA_PREFIX org_libsdl_app` 宏统一派生（改一处宏），`JNI_OnLoad` 中另有 4 处硬编码 `"org/libsdl/app/…"` register_methods 字符串需同步改 `org/libsdl/app32/…`；patch 面以 `grep -rn 'org/libsdl/app' src/` 复核穷尽（2.32.10 实测：宏 + JNI_OnLoad 4 处）；
  - app32 Java 层与 native 同版 2.32.10：`nativeGetVersion()` 返回 `"2.32.10"`，onCreate 版本硬校验通过；
  - 原「一期不改 Java 层（复用 2.26.3）」方案**作废**：版本硬校验一票否决（见现状事实）；即使尝试 patch 掉共享类的校验，2.28–2.32 的 native 还可能经 `SDL_AndroidGetActivity` 反射调用 2.26.3 Java 层不存在的方法，双重不可靠，不采用。

### 3.5 其余关键决策

1. **宿主继承 vendored `org.libsdl.app32.SDLActivity`（2.32.10）**（保持上游主循环模型，Java 层与 native 同版、onCreate 版本硬校验天然通过，§3.4），不做 Step-ABI 改造；退出沿用 `killProcess` 策略（与 Artemis/KRKR 一致）。
2. **扫描识别在 EngineScanner 内新增评分**（§4.2），不与既有 RealLive/AVG32/UK2/Siglus 判定冲突。
3. **存档按目录整体镜像**（导出/导入 zip），不引入文件白名单（`.asd` + `Volume.sav` + `*.asd.` 备份均可整体往返）。
4. **一期即提供「独立存档」开关（默认开）**：`-savedir` 为自由路径，默认指向应用外部存档目录（对齐 ONS `scopedSaveDir=true` 先例），避免 `<游戏根>` 位于 SD 卡/`Android/data` 时的写入失败与卸载丢档；关闭时回落 `<游戏根>/save`。
5. **不移植上游 Launcher/GameStore/GameInstaller/CD 镜像安装**：游戏目录由 TyranorNext 扫描授权目录得到，直接 `-gamedir` 启动。
6. **不做 `.xsys35rc` 注入**：可表达设置一律走命令行参数（`-savedir`/`-renderer`/`-integerscale`/`-mute_on_unfocus`/可选 `-ttfont_gothic`），不写用户游戏目录（§4.6、§6.6）。
7. **标题回写一期做**：复用 Siglus 协议（登记 uri/默认标题 → 宿主写 `xsys35_title.<pathHash>` → 库加载时条件导入），成本低且与既有先例逐行同构。
8. **不启用 libintl**：保持上游 Android 构建行为（内置 NLS），引擎自绘弹窗文案由 engine 侧字符串资源与本项目三语言规范覆盖。
9. **字体随包分发**（`MTLc3m.ttf`/`mincho.ttf` 进 assets）；自定义字体项列为 P2。

---

## 4. App 侧改动清单

### 4.1 引擎类型与展示

| 文件 | 改动 |
|---|---|
| `core/engine/EngineType.kt` | 新增 `XSYS35("System35", "S3X")`（置于 `AVG32` 附近） |
| `core/game/launch/EngineLauncher.kt` | `supportedEngines` 加入 `XSYS35`（引擎页展示） |
| `ui/engine/EngineScreen.kt` | `engineTabOf` → GAL；`engineDescription` 新增分支；`builtinDialogEntries` 加 `xsys35-2.20.0` 条目（命名见 §9 评审点 2） |
| `ui/game/GameScreen.kt` | `EngineType.coverColor()` 新增占位色（建议深红/紫系） |
| `ui/home/HomeScreen.kt` | 无需改动（displayName 自动展示） |

### 4.2 扫描识别（`core/game/scan/EngineScanner.kt`）

在 `detectEngine` 判定链新增特征（建议置于 `FVP` 之后、framebuffer 组之前；两类特征无冲突）：

新增收集变量（仅根目录级，大小写不敏感；**同时容忍 `<根>/GAMEDATA/` 子目录形态**——CD 镜像解出的游戏惯用布局，见 §11.4-C1）：

- `hasAldScript`：根目录或 `GAMEDATA/` 存在 `*.ALD` 且长度 ≥ 6（`SA.ALD`、`GA.ALD`…，System3.x 场景/图形档）；
- `hasXs35Support`：根目录或 `GAMEDATA/` 存在 `System39.ain` / `System39.ini` / `*.wai` / `*.bgi` / `*.alk` 之一；
- 命中 `GAMEDATA/` 形态时 `launchTarget` 取**实际含 `*.ALD` 的目录**（`<根>` 或 `<根>/GAMEDATA`）：
  - 上游把「含 `*s?ald` 的目录」直接作为游戏目录（`GameInstaller` 写 `GAMEDATA` 标记、`-gamedir` 指向它，见 §11.2），引擎在 `chdir` 后从 CWD 扫描 ALD；
  - 因此启动时 `-gamedir` 必须传该目录——App 侧可在扫描期记录，或启动期用既有「启动文件/入口目录」形态兜底（与 KRKR `pickKrActivateEntry` 同思路）；
  - `GAMEDATA` 目录内的 ALD 与资源即引擎完整工作目录；根目录的 `playlist.txt` 例外（BGM 播放列表按 CWD 相对路径解析，`GAMEDATA/` 形态下应把 `playlist.txt` 放在**与 ALD 同一目录**（即 `-gamedir` 实际指向的目录），见 §6.3）。

判定与打分（`launchTarget = LAUNCH_TARGET_GAME_DIR`）：

| 条件 | 分数 |
|---|---|
| `hasAldScript` + `hasXs35Support` | 96 |
| `hasAldScript` 单独（≥2 个 ALD） | 90 |
| `hasAldScript` 单独（1 个） | 80 |

防误判：`*.ALD` 与既有引擎特征无重叠（Siglus 用 Gameexe/Scene.pck、RealLive/AVG32 用 SEEN.TXT、UK2 用 .MES）；单测需覆盖「不误伤既有样例」与「目录仅含非 S 前缀 ALD（如只有 `GA.ALD`）」样例——**引擎硬性要求存在场景档 `S*.ALD`**（`gameresource.c` 只认 'S' 前缀，无场景档 → `sys35_usage(true)` 退出，`xsystem35.c:473-480`），此类目录会被扫描特征误检为低分 XSYS35、但引擎拒绝运行，由宿主启动预检（§6.1）兜底拦下。

新增单测 `EngineScannerXs35Test`（参照 `EngineScannerSiglusTest`）：`SA.ALD`+`System39.ini` 高分 / 多 ALD 中分 / 单 ALD 低分 / 大小写变体 / 不误伤 Siglus/RealLive/AVG32/UK2/FVP 样例。

### 4.3 启动链路（`core/game/launch/EngineLauncher.kt`）

- `supportedEngines` 增加 `EngineType.XSYS35`；
- `buildIntent` 新增分支：`EngineType.XSYS35 -> buildXs35Intent(context, path, game, settings)`；
- 新函数（放在 `buildFvpIntent` 附近）：

```
PATH / GAME_PATH / PROJECT_ROOT / GAME_DIR = 游戏根真实路径（沿用 resolveGameDirectory；若根目录无 *.ALD 而
                 GAMEDATA/ 子目录有，则用「含 ALD 的实际目录」作为 -gamedir，见 §4.2）
ROOT_URI、LAUNCH_TARGET、LAUNCH_MODE = LaunchContract.LAUNCH_MODE_XSYS35
XS35_SCOPED_SAVE_DIR = 生效「独立存档」布尔（默认 true）
XS35_SAVE_DIR   = 生效存档目录**绝对路径**（独立 = getExternalFilesDir/save/<safeSaveName>；关闭 = <游戏根>/save；
                  与 GameSaveManager.resolveSaveLocation 同源，禁止两侧各自推导；宿主据此传 -savedir）
XS35_RENDERER   = 生效渲染后端（auto/opengl/software → 仅非 auto 时传 -renderer）
XS35_INTEGER_SCALE = 生效布尔 → -integerscale
XS35_MUTE_UNFOCUS  = 生效布尔 → -mute_on_unfocus
ORIENTATION     = 在 buildXs35Intent 内显式注入（沿用 krkrsdl3 的默认 6；注意：buildIntent 尾部统一注入的
                  只有主题色 extras，现有 Siglus/FVP/Framebuffer 分支并不注入 ORIENTATION，本引擎自行注入）
主题色 extras 由 buildIntent 尾部统一注入
```

- 启动前 `ensureXs35SaveDir`（对齐 `EngineLauncher.ensureKrSaveDir`）：`mkdirs` + 存在但非目录的显式失败（独立存档外部存储不可用 → `LaunchResult.Failure`，不回退游戏根）；
- 前置：`requestAllFilesAccessIfNeeded`（关闭独立存档时 `-savedir <游戏根>/save` 需写权限；开启时仅读游戏目录仍走该分支，与 ONS 同口径）；
- 成功后：`recordRecentGame`；失败：沿用 `LaunchResult.Failure` / `LaunchErrorMessages`。

### 4.4 插件校验分支

`core/engine/plugin/EnginePluginBootstrap.kt` 的 `ensureForLaunch` `when (engine)` 新增 `XSYS35 -> null`（内置，无原生插件依赖；漏改会编译失败）。

### 4.5 存档（`core/game/save/GameSaveManager.kt`）

`resolveSaveLocation` 新增分支（与启动侧同源：同一 `settings.xs35ScopedSaveDir` 与同一目录函数）：

```
EngineType.XSYS35 -> 独立存档开（默认）：SaveLocation(File(File(getExternalFilesDir(null), "save"), GamePathUtils.safeSaveName(root)), text(R.string.save_location_engine_scoped, game.engine.displayName), true)
                     独立存档关：        SaveLocation(File(root, "save"), text(R.string.save_location_engine_game_dir, game.engine.displayName), true)
```

- 与 ONS 分支同构（ONS：`getExternalFilesDir/save/<root.name>`，见 `GameSaveManager.resolveSaveLocation`），目录名统一用 `GamePathUtils.safeSaveName(root)`；
  - 已知既有不一致（不改，避免回归）：ONS 引擎侧 `resolveScopedSaveDirectory` 用 `guessName`（目录名），App 侧 `GameSaveManager` / `EngineLauncher.buildIntent` 用 `File(root).name` / `safeSaveName` 两套推导并存；XSYS35 新增代码只允许一份实现（App 侧单一函数，宿主只消费 `XS35_SAVE_DIR`），不得复刻该分裂；
- 外部存储不可用（`getExternalFilesDir` 为 null）→ `SaveLocation(null, text(R.string.save_error_…), false)`，与 ONS 口径一致；
- 目录函数建议与宿主侧共用一个纯函数（放 `core/game/save` 或 `GamePathUtils`），保证「启动写入」与「镜像读取」永远同一路径；
- `listSaveFiles` / `exportToZip` / `importFromZip` 自动复用；
- `cleanupAppData` 不新增分支（无应用私有数据）；
- `excludeFor` / `restoresExcludedFromBackup` 无需白名单（存档目录内全部内容均为存档产物的口径，与 FVP 一致）。

### 4.6 引擎设置

| 设置项 | 键 | 取值 | 默认 |
|---|---|---|---|
| 独立存档 | `xsys35_scoped_save_dir` | bool | **`true`**（对齐 ONS `scopedSaveDir=true`；目录 = `getExternalFilesDir/save/<safeSaveName>`） |
| 渲染后端 | `xsys35_renderer` | `auto` / `opengl` / `software` | `auto`（不传 `-renderer`，SDL 自动探测；opengl = Android 上的 GLES2） |
| 整数倍缩放 | `xsys35_integer_scale` | bool | `false`（上游默认） |
| 失焦静音 | `xsys35_mute_unfocus` | bool | `false` |
| （P2）自定义字体 | `xsys35_font` | 私有字体绝对路径 | 空（内置 `MTLc3m`/`mincho`） |
| （占位）文本编码说明 | — | 一期固定 Shift-JIS（System3.x 原生），无设置项，仅在信息卡说明 | — |

- 音频缓冲（上游 profile `audio_buffer_size`）**不暴露**：需要写 `.xsys35rc` 才能生效，与「不注入 rc」决策冲突（§3.5-6），列为 P2；
- 渲染后端真机异常时用户可用 `software` 兜底（SDL 软件渲染器，兼容性最稳）。

改动点（以 FVP 为模板）：

| 文件 | 改动 |
|---|---|
| `core/settings/EngineSettingsStore.kt` | 键常量、白名单（renderer）、get/set/normalize |
| `core/settings/EngineSettingsResolver.kt` + `ResolvedEngineSettings` | 新增 `xs35*` 字段与合并 |
| `core/settings/EffectiveEngineSettings.kt` | 白名单 `resolveAllowed` / 布尔 `resolveBool` |
| `core/settings/PerGameSettingsStore.kt` | `F_XSYS35_*` |
| `core/game/storage/GameOverridePartitions.kt` | 键常量并加入 `TYRANO_KEYS`（沿用先例，避免 Room 迁移） |
| `ui/settings/EngineSettingsText.kt` | `xsys35RendererOptions()` 等 |
| `ui/settings/EngineSettingsMenuActivity.kt` | `EngineSettingsKind.XSYS35` |
| `ui/settings/SettingsScreen.kt` | `Xs35SettingsCard`（独立存档开关 + 渲染下拉 + 整数缩放/失焦静音开关 + 信息卡） |
| `ui/settings/PerGameSettingsScreen.kt` | XSYS35 分支：四项覆盖（独立存档/渲染/整数缩放/失焦静音）+ 信息卡（存档目录按开关显示实际路径、Shift-JIS 说明、卸载清除提示） |

### 4.7 文案（三语言严格一致）

- App 侧 `app/src/main/res/values{,-en,-ja}/strings.xml`：`engine_desc_xsys35`、`engine_settings_xsys35_title`、`engine_settings_xsys35_renderer_title` / `_auto` / `_opengl` / `_software`、`engine_settings_xsys35_integer_scale_title`、`engine_settings_xsys35_mute_unfocus_title`、`engine_settings_xsys35_note`（编码/存档说明；独立存档复用 `engine_settings_scoped_save_dir`）等；
- engine 侧 `engine/src/main/res/values{,-en,-ja}/strings.xml`：`engine_xsys35_missing_game_dir`、`engine_xsys35_init_failed`、`engine_xsys35_back_again_to_exit`、`engine_xsys35_exit_game` 等；
- 完成后必须通过 `python3 tools/check-hardcoded-ui-strings.py`。

### 4.8 其它

- `core/game/storage/EngineDetectionRepository.kt`：XSYS35 走 `else -> null`（无版本检测缓存）；
- `ScanGame` / `GameEntity`：无需新字段；
- `README.md`：引擎支持表新增 System35 行；致谢补 `xsystem35-sdl2`（GPL-2.0-or-later）与依赖许可；
- `CONTEXT.md`：引擎与运行时节补 `XSYS35` 与「内置引擎」清单。

---

## 5. engine 侧改动清单

### 5.1 Java 宿主：`engine/src/main/java/com/core/xsys35/`

| 类 | 职责 |
|---|---|
| `Xs35Activity.java/kotlin` | `extends org.libsdl.app32.SDLActivity`（vendored 2.32.10 Java 层，§3.4）；覆盖 `getLibraries()` / `getArguments()`；实现 native 回调方法；返回键/沉浸式/加载遮罩/多开提示 |
| `Xs35Prefs.java/kotlin`（可选） | 读取 Intent extras 生成 `-savedir`/`-renderer` 等参数（也可内联在 Activity） |

关键行为（对齐上游 `GameActivity` 并接入本项目约定）：

1. `getLibraries()` 返回 `["SDL2x", "xsystem35"]`（对齐上游 `GameActivity` 只显式加载 SDL2 与主库；SDL2_mixer / freetype / libwebp 经 DT_NEEDED 自动解析——是否产出独立 .so 以构建产物为准，若 mixer 为独立库则由 §5.4 patch 保证重命名。末项为主库，`nativeRunMain` 会自动拼 `libxsystem35.so` + `SDL_main`）。

    > 说明：`SDLActivity.getMainSharedObject()` 取 `getLibraries()` 末项拼 `lib<name>.so`，`getMainFunction()` 默认 `"SDL_main"`；xsystem35 的 `main()` 由 SDL2 的 `SDL_main` 宏适配，默认值即可。

2. `getArguments()`：`["-gamedir", gameRoot, "-savedir", saveDir]` + 可选 `-renderer <name>`；`-gamedir <目录>` 必须成对置于首位（上游无 argc 校验、`argv[1]` 无条件解引用，非 `-gamedir` 首参会在错误 CWD 运行，见 §5.3）。
3. `onCreate`：解析 `LaunchContract`（`PATH → GAME_PATH → PROJECT_ROOT → GAME_DIR` 取首个非空；缺失 Toast + finish）；`SDLActivity.setWindowStyle(true)`（上游 SDL#8995 workaround）。
4. Java 回调（native 反射调用，方法名/签名必须精确）：
   - `public String inputString(String msg, String oldVal, int maxLen)`：UI 线程 `AlertDialog + EditText`，阻塞等待结果（上游实现语义，保持）；
   - `public int inputNumber(String msg, int min, int max, int initial)`：`AlertDialog + NumberPicker`；
   - `public void midiStart(String path, boolean loop)` / `public void midiStop()` / `public int midiCurrentPosition()`：内部 `MediaPlayer`（onStop 暂停、onResume 恢复、onDestroy release + killProcess）；
   - 文案复用 engine 侧公共字符串（`engine_ok` / `engine_cancel` / `engine_prompt` 等），不新增硬编码文案。
5. 生命周期：`onPause/onResume` 转发 MIDI 状态；`onDestroy` 顺序执行 `midi.release()` → `killProcess`（与上游一致）。
6. 返回键：在 `Xs35Activity` 覆写 `onBackPressed()`——`SDL_ANDROID_TRAP_BACK_BUTTON` hint 命中时直接返回（交回 SDL back 引擎语义，引擎已在 `gfx_video.c:109-112` 设 1），未命中走 `DoubleBackExit.handleBack` 双击退出（与现有 2.26.3 SDLActivity 的本地接入同款策略，`SDLActivity.java:895-901`）。vendored app32 层保持上游原样，双击退出逻辑只落在宿主子类，避免二次改动 vendored 源（若实测 2.32.10 已改用 OnBackPressedDispatcher，则以 callback 注册实现同一语义）。
7. `singleInstance` + `onNewIntent` Toast `engine_another_game_running`（对齐其它宿主）。

### 5.2 Manifest / 资源

```xml
<!-- System3.x（xsystem35）宿主：SDL2 主循环 -->
<activity
    android:name="com.core.xsys35.Xs35Activity"
    android:exported="false"
    android:process=":xsys35"
    android:launchMode="singleInstance"
    android:taskAffinity="com.core.Xsys35"
    android:screenOrientation="sensorLandscape"
    android:theme="@style/Theme.Xsys35"
    android:windowSoftInputMode="adjustPan"
    android:configChanges="keyboard|keyboardHidden|navigation|orientation|screenLayout|screenSize|smallestScreenSize|uiMode|density|fontScale|locale|layoutDirection" />
```

- `engine/src/main/res/values/styles.xml` 新增 `Theme.Xsys35`（无 ActionBar + 黑底，参考 `Theme.FramebufferGames`/`Theme.Siglus`）；
- `engine/consumer-rules.pro` 新增 `-keep class com.core.xsys35.** { *; }` 与 `-keep class org.libsdl.app32.** { *; }`（JNI 注册/反射需要，与现有 `org.libsdl.app.**` 条目并列）；
- 字体 assets：`engine/src/main/assets/MTLc3m.ttf`、`mincho.ttf`（从 vendored `fonts/` 拷贝；或构建期由 engine 模块 Gradle 任务从 `engine/xsystem35/fonts/` 同步，避免双份维护——**推荐后者**，与「禁止手工维护同一份二进制」条款一致）。

### 5.3 契约与常量（`engine/src/main/java/com/core/engine/LaunchContract.kt`）

```kotlin
// ---------- System35 (xsystem35) ----------
const val XS35_SAVE_DIR = "xs35_save_dir"            // 生效存档目录绝对路径（由 App 按「独立存档开关」解析，禁止宿主自行推导）
const val XS35_SCOPED_SAVE_DIR = "xs35_scoped_save_dir"  // 独立存档布尔（供宿主日志/提示；路径以 XS35_SAVE_DIR 为准）
const val XS35_RENDERER = "xs35_renderer"            // auto|opengl|software（auto 不传 -renderer）
const val XS35_INTEGER_SCALE = "xs35_integer_scale"  // bool → -integerscale
const val XS35_MUTE_UNFOCUS = "xs35_mute_unfocus"    // bool → -mute_on_unfocus
const val XS35_ORIGINAL_ROOT = "xs35_original_root"  // 原始游戏根（标题回写登记用；镜像/还原场景下与 PATH 可能不同）

const val LAUNCH_MODE_XSYS35 = "internal.xsys35"
```

`EnginePrefs.kt`（标题回写协议，与 Siglus 同构）：

```kotlin
const val KEY_XSYS35_TITLE_PREFIX = "xsys35_title."          // 宿主写：解析后的游戏标题
const val KEY_XSYS35_URI_PREFIX = "xsys35_uri."              // App 启动前登记：game.uri
const val KEY_XSYS35_DEFAULT_TITLE_PREFIX = "xsys35_default_title."  // App 启动前登记：目录名
```

> 上游对 `argv` 的实际约束（`xsystem35.c:463-467` 无 argc 校验、`argv[1]` **无条件解引用**）：`argv[1] == "-gamedir"` 时才 `chdir(argv[2])`——完全无参会直接段错误、`argv[1]` 非 `-gamedir` 则跳过 chdir 在错误 CWD 运行；`-savedir` 走 `savedir = argv[++i]` 原样使用（无 realpath，见 §6.5；缺值才打印 usage 退出），其余参数（`-renderer`/`-integerscale`/`-mute_on_unfocus`）均无顺序约束。宿主拼参必须保证 `-gamedir <目录>` 成对置于首位。

### 5.4 引擎库预构建（一次性 / 每次更新上游）

```bash
# 0) 准备：vendored 源码（engine/xsystem35/ + third_party/）
# 1) 配置（NDK r28，可对齐项目 ndkVersion 28.0.13004108）
cmake -B /tmp/xsys35-build -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release \
  -S engine/xsystem35/android-host   # 或 vendored 的独立构建剧本
# 2) 构建 + strip
cmake --build /tmp/xsys35-build -j
$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/*/bin/llvm-strip --strip-unneeded \
  /tmp/xsys35-build/**/libxsystem35.so /tmp/xsys35-build/**/libSDL2x.so ...
# 3) 入库
cp ... engine/src/main/jniLibs/arm64-v8a/
```

- 上面「android-host」指 vendored 时新增一个最小构建剧本（等价上游 `android/app/jni/CMakeLists.txt`，改为 `SOURCE_DIR` 指向 vendored 目录、跳过 FetchContent、输出重命名）；`BUILD.md` 记录完整命令与产物 SHA256。
- **patch 清单**（`engine/xsystem35/patches/`）：
  1. SDL2 native：`OUTPUT_NAME/SONAME = SDL2x`（防止与 ONS 插件 `libSDL2.so` 冲突）；
  2. SDL2 native：JNI 类名前缀 app32 化——`src/core/android/SDL_android.c` 的 `#define SDL_JAVA_PREFIX org_libsdl_app` 改为 `org_libsdl_app32`，以及 `JNI_OnLoad` 中 4 处硬编码 `"org/libsdl/app/…"` register_methods 字符串同步改 `org/libsdl/app32/…`；patch 面以 `grep -rn 'org/libsdl/app' src/` 复核穷尽（2.32.10 实测：宏 + JNI_OnLoad 4 处）；
  3. SDL2 Java 层：2.32.10 `android-project` Java 源整包落 `engine/src/main/java/org/libsdl/app32/`（package 声明与内部互引 `org.libsdl.app` → `org.libsdl.app32`）；
  4. SDL2_mixer：若以共享库产出，输出名加 `_xs35` 后缀；freetype / libwebp 默认静态链入 `libxsystem35.so`（无独立产物、无需 patch），若实测产出独立 .so 则同样加后缀入库。
- 产物核对：`llvm-readelf -d` 确认 SONAME/NEEDED 链（`libxsystem35.so → libSDL2x.so`（+ `libSDL2_mixer_xs35.so`、freetype/libwebp 视形态）），**并以实测产物回填 §3.3 jniLibs 清单**（freetype/libwebp 预计无独立 .so）；同时确认 app32 Java 层与 native `nativeGetVersion()` 同为 2.32.10（onCreate 版本硬校验通过，§3.4）。

### 5.5 许可与署名

- vendored 目录保留上游 `COPYING`（GPL-2.0）与各依赖 LICENSE（zlib/FTL/BSD-3）；
- README 增加「内置引擎许可」小节：xsystem35-sdl2（GPL-2.0-or-later，源码链接），SDL2/SDL2_mixer（zlib）、freetype（FTL/GPLv2）、libwebp（BSD-3）；
- 若未来要求「主仓不承载 GPL 源码」，可平移到 nativeplugin zip 按需下载（见 §3.1 备选），本方案不启用。

---

## 6. 关键交互细节（待真机验证项标记 ⚠）

### 6.1 启动与退出

- `argv[1]` 必须成对为 `-gamedir <目录>`（上游无 argc 校验、`argv[1]` 无条件解引用：完全无参 → 段错误；`argv[1]` 非 `-gamedir` → 跳过 chdir 在错误 CWD 运行）；`chdir` 后目录无场景档 `S*.ALD` → `sys35_usage(true)` 打印 usage 并退出（`xsystem35.c:473-480`）→ 表现为 SDL 线程退出、Activity 白屏。**宿主需兜底**：启动前检查目标目录**场景档 `S*.ALD`**（非任意 `*.ALD`，对齐引擎硬性要求，§4.2）存在，不存在 Toast `engine_xsys35_init_failed` 并 finish。
- 退出：`main()` 正常返回 → `nativeRunMain` 返回 → SDLActivity 默认行为是 finish（需在宿主 `onDestroy` 兜底 `killProcess`，与上游一致）。Java 层与 native 同版 2.32.10（§3.4），原「验证 2.26 Java 层与 2.32 native 主线程行为差异」的风险项随 app32 方案消除。

### 6.2 输入

- 触摸/鼠标：SDL 原生处理（真机上是触摸→鼠标映射，右键映射在 SDL 内建，需实测 ⚠）；
- 硬件键盘/手柄：SDL 原生 keymap（System3.x 使用方向键/Enter/Esc/鼠标），宿主不拦截（返回键除外）；
- 原生输入弹窗：`EditText/NumberPicker` 走 androidx 兼容，注意与 `Theme.Xsys35`（NoActionBar）兼容 ⚠。

### 6.3 音频

- BGM（CD 虚拟）：`playlist.txt` 按引擎 CWD（= `-gamedir` 目录）解析——普通形态即游戏根、`GAMEDATA/` 形态位于 `GAMEDATA/` 内（与 §4.2 一致）；存档 `save/*.asd` 独立；
- MIDI：Java MediaPlayer 经 `midi.android.c`；`SDL_AndroidGetInternalStoragePath()/tmp.mid` 需可写（应用私有目录，天然可写）；
- 切后台：上游 `MediaPlayer` 在 `onStop` 暂停、`onResume` 恢复；SDL 音频线程行为沿用 SDL 默认（`SDL_ANDROID_BLOCK_ON_PAUSE` 语义）⚠。

### 6.4 字体

- 默认字体必须存在于 assets（否则 `SYSERROR("Cannot open font …")` 终结）；
- 自定义字体：`.xsys35rc` 的 `ttfont_gothic`/`ttfont_mincho` 相对 CWD（= 游戏目录）解析；一期不提供宿主级字体设置（P2 可接 `FontSettings` 先例）；
- 汉化版兼容：System3.x 文本为 Shift-JIS，汉化多采用补丁式字体/文本替换，一期仅保证默认字体渲染 ⚠。

### 6.5 存档（独立存档一期即做）

- 路径（绝对路径，宿主不落相对路径——上游 `savedir = argv[++i]` 原样使用、`fc_init` 相对名先 `mkdir_p` 再拼接，相对值在 `chdir` 之后落点不可控）：
  - 独立存档开（默认）：`getExternalFilesDir(null)/save/<safeSaveName>`；
  - 关闭：`<游戏根>/save`（`GAMEDATA/` 形态下 = `-gamedir` 实际指向目录内的 `save/`，与 §4.2 launchTarget 口径一致）；
- 文件形态：`<场景基名>s[a-z].asd`（如 `SAsa.asd`）、`Volume.sav`、`*.asd.` 备份，镜像按目录整体导出/导入；
- 与 PC 版 xsystem35 存档互通（同格式，默认 `system39` saveformat）——用户可手动互拷；
- 目录由启动侧 `ensureXs35SaveDir` 预创建（`fc_init` 有 `mkdir_p` 兜底，但预创建可在权限问题上提前给出类型化失败）；镜像读取侧走同一目录函数（§4.5）。

### 6.6 引擎设置注入（命令行参数，不写用户目录）

上游 profile 查找顺序（`src/profile.c:134-147`）：`$HOME/.xsys35rc` → 相对路径 `.xsys35rc`（即 CWD；`-gamedir` 经 `chdir` 成为 CWD 后被第二次查找命中。Android 应用进程通常无 `HOME`，即便被设置也需该路径恰有 rc 才生效，风险极低）。**不注入 `.xsys35rc`**（避免写入/还原用户游戏目录）。一期映射：

| 设置 | 落地方式 | 上游依据 |
|---|---|---|
| 独立存档 | `-savedir <绝对路径>` | `savedir = argv[++i]`（原样使用） |
| 渲染后端 | `-renderer <name>`（非 auto 时） | `SET sys35_ParseOption` → `SDL_SetHint(SDL_HINT_RENDER_DRIVER)` |
| 整数倍缩放 | `-integerscale` | `integer_scaling` → `gfx_setIntegerScaling(true)` |
| 失焦静音 | `-mute_on_unfocus` | `volume_set_mute_on_unfocus(true)` |
| CD 播放列表 | 不传（引擎按 CWD 解析 `playlist.txt`：普通形态 = 游戏根；GAMEDATA/ 形态 = GAMEDATA/ 内，见 §6.3） | `DEFAULT_PLAYLIST_PATH` / `muscd_set_playlist` |
| 自定义字体（P2） | `-ttfont_gothic <绝对路径>`（复用 FVP `FontImport`：复制进 `filesDir/fonts`） | `fontname_tt[FONT_GOTHIC] = argv[i+1]` |
| 音频缓冲（P2） | 不暴露（仅 profile 可配） | `audio_buffer_size` 只有 `get_profile` 路径 |

> 真机需验证 ⚠：`-renderer opengl|software` 在 SDL2 Android 的取值语义（opengl = GLES2；software 恒可用）。

### 6.7 标题回写（复用 Siglus 协议）

- 上游标题链：`gfx_setWindowTitle` → `SDL_SetWindowTitle` → SDL2 Java 层 `setActivityTitle(String)` → `sendCommand(COMMAND_CHANGE_TITLE)` → `((Activity) context).setTitle(...)`（现有 2.26.3 层 `SDLActivity.java:119` 同链路，vendored app32 层为同版机制，宿主覆盖 `Activity.setTitle` 均可捕获）；
- 宿主在 `Xs35Activity` 覆盖 `Activity.setTitle(CharSequence)`：取冒号后段（上游格式 `"XSystem35 Version 2.20.0:标题"`，见上游 `GameActivity.setTitle` 同款解析），首次拿到非空标题即写 `xsys35_title.<pathHash>`（与 siglus 的 pathHash 算法一致），不重复写；
- App 启动前登记 `xsys35_uri.<hash>` / `xsys35_default_title.<hash>`（复用 `buildSiglusIntent` 的登记写法）；
- 导入：新增 `Xs35TitleFeedback`，在 `MainLibraryViewModel` 已有 `SiglusTitleFeedback.import(appContext)` 处并列调用；导入规则完全一致（仅标题仍等于登记目录名时覆盖）。

---

## 7. 测试与验收

### 7.1 单元测试

| 用例 | 内容 |
|---|---|
| `EngineScannerXs35Test` | ALD ± 辅助特征、数量分级、大小写、与既有引擎样例不互斥 |
| `EffectiveEngineSettingsTest`（扩展） | XSYS35 渲染后端白名单回落、布尔覆盖合并 |
| `GameOverridePartitionsTest`（扩展） | XSYS35 键在 tyrano 分区往返不丢 |
| `LaunchContractXs35Test`（可选） | 新常量快照 |

### 7.2 真机验收（建议《Rance IV》或《闘神都市》日文原版）

1. 扫描识别为 System35（目录含 `SA.ALD` 等）；
2. 启动进入标题 → 触摸推进 → 存档（engine 内菜单）→ 读档；
3. 独立存档开启：`getExternalFilesDir/save/<name>/SAsa.asd` 落盘；关闭：`<游戏根>/save/` 落盘；两条路径分别杀进程重进可读；
4. 两种存档位置分别导出 zip → 清档 → 导入 → 读档一致；
5. CD 音频：放 `playlist.txt` + mp3 → BGM 播放；无 playlist 时不崩溃；
6. 渲染后端切换（auto/opengl/software）与整数缩放生效；
7. 标题回写：首次启动后库内标题从目录名变为游戏标题；用户改名后不被覆盖；
8. 返回键单击/双击行为、后台/回前台音频恢复；
9. 与 ONS 插件共存回归：**先启动 ONS 游戏 → 退出 → 启动 System35 游戏**，两者均正常（SDL2 冲突回归重点）；反向顺序同理；
10. 版本门禁（app32 回归）：启动任何 System35 游戏不得出现「SDL C/Java version mismatch」弹窗（app32 Java 层与 native 同版 + `SDL_android.c` 前缀 patch 生效性回归）；
11. 权限：未授权 all-files 时给出提示而非崩溃（关闭独立存档场景重点回归）。

### 7.3 回滚

- 移除 `EngineType.XSYS35` 相关分支 + 删除 jniLibs/assets 产物即回到接入前；
- 无数据迁移风险（存档在游戏根，设置键滞留无害）。

---

## 8. 风险与待确认

| 风险 | 影响 | 缓解 |
|---|---|---|
| ~~SDL2 Java 层与 native 版本错配~~（已由设计消除：现有 `org.libsdl.app`（2.26.3）onCreate 版本硬校验与 2.32.10 native 必然冲突，§3.4 现状事实） | 启动即「SDL C/Java version mismatch」弹窗退出 | 已消除：vendored 2.32.10 Java 层新包 `org.libsdl.app32` 与 native 同版；patch 面已实测收敛（SDL_JAVA_PREFIX 宏 + JNI_OnLoad 4 处字符串）；现有 `org.libsdl.app` 仅 ONS 使用、不受影响 |
| `getMainSharedObject`/`SDL_main` 约定不匹配 | 无法进入 native main | 宿主覆盖 `getMainSharedObject()` 显式返回 `libxsystem35.so` 绝对路径（最小兜底）；实现时先验证默认约定 |
| `chdir` + assets 字体相对路径语义 | 字体找不到导致 SYSERROR | 字体放 assets 根；真机验证（§6.4）；必要时宿主启动前把字体拷贝到游戏目录（不推荐） |
| profile（.xsys35rc）不注入 | 依赖 profile 的设置项（音频缓冲）无法落地 | 一期仅命令行子集（§6.6）；P2 再评估 |
| GPL-2.0-or-later 源码合规 | 分发要求 | 保留许可、README 声明源码链接；如未来收紧则平移到模块化下载（§3.1） |
| APK 体积 +4–6 MB | 安装包增大 | strip；确认仅 arm64；freetype/libwebp 默认已静态链入主库（§2.2），必要时把 SDL2_mixer 也静态并入以减文件数（体积不降） |
| 上游 API 演进 | 重编译回归 | 固定 vendored 基线 commit，记录于 `BUILD.md`；跟随上游时重跑 §7 验收 |
| ALD 特征误判（其它引擎目录含 `*.ALD`） | 错误识别 | 分级阈值 + 单测不误伤回归 |
| 独立存档与 PC 版存档位置不同 | 用户找不到旧档 | 信息卡显示实际生效目录；关闭开关即回游戏根 `save/`；P2 提供迁移动作 |

待确认（评审时定）：

1. ~~独立存档是否一期做~~ → **已定：一期做，默认开**（§3.5-4、§4.5、§6.5）；
2. ~~版本条目命名~~ → **已定：`xsys35-2.20.0`**（§9 评审点 2）；
3. ~~渲染后端默认值~~ → **已定：`auto`**（§9 评审点 3）；
4. ~~标题回写是否 P2~~ → **已定：一期做**（§3.5-7、§6.7），宿主写 prefs + 复用 Siglus 导入协议。

## 9. 评审点调研结论（2026-10-12）

### 9.1 独立存档：一期做，默认开

| 证据 | 位置 |
|---|---|
| ONS 先例：`scopedSaveDir` 默认 **true**，目录 = `getExternalFilesDir/save/<名称>`（引擎侧 `OnsSettings.resolveScopedSaveDirectory` / App 侧 `GameSaveManager.resolveSaveLocation`） | `OnsSettings.kt:16,53-57`、`GameSaveManager.kt:83-91` |
| 老引擎（Siglus/RealLive/AVG32/UK2/FVP）一期均为游戏根固定目录——是历史包袱，不是推荐模板 | `GameSaveManager.kt:118-149` |
| `-savedir` 是自由路径（`savedir = argv[++i]`，无 realpath/无校验）→ 引擎侧零改动即可支持任意目录 | 上游 `xsystem35.c:209-215` |
| 上游 `fc_init` 对 savedir 有 `mkdir_p` 兜底，但宿主预创建可在权限问题给出类型化失败 | 上游 `filecheck.c:110-113` |

结论：默认独立（`getExternalFilesDir/save/<GamePathUtils.safeSaveName(root)>`：目录基址形态对齐 ONS 先例，目录名推导对齐 KRKR 的 `safeSaveName`——ONS App 侧现用 `File(root).name`，XSYS35 有意选用更稳的 `safeSaveName` 单一实现，不复刻 ONS 的两套推导分裂，见 §4.5；与 KRKR SDL3 同基目录），关闭回游戏根。好处：SD 卡/`Android/data` 只读场景不丢存档、卸载重装存档可迁移（用户在设置信息卡有提示）；风险：存档位置与 PC 版不互通（有开关与说明），卸载会清空（信息卡提示）。

### 9.2 版本条目：`xsys35-2.20.0`

现有条目风格：`siglus-rs` / `game-launcher` / `rfvp`（上游代号）、`tyranor-builtin` / `Kirikiroid2-1.3.9` / `tyranornext-01-02`（版本号）。xsystem35 有清晰的上游发版号（勘察基线 `XSYSTEM35_VERSION="2.20.0"`，`CHANGELOG.md` 维护；上游 `CMakeLists.txt` 单一来源），无代号，故用「引擎短名-版本号」——与 ONS 的 `ONScripter-0.7.7` 同型；每次更新 vendored 基线时同步更新该条目与 `BUILD.md`。

### 9.3 渲染后端：默认 `auto`，三档下拉

上游只有 `SDL_SetHint(SDL_HINT_RENDER_DRIVER, name)` 一个入口（`gfx_video.c:90-92`），SDL 自动探测（Android 上 opengl = GLES2，software = SDL 软件渲染器）。`software` 是真实可用的兜底（SDL 渲染器本就是软件实现，Android 上常用于 GLES 兼容问题），因此暴露三档：自动 / OpenGL / 软件渲染；默认自动、不下发参数。

### 9.4 标题回写：一期做，复用 Siglus 协议

- native → Activity 标题链路在 SDL2 Java 层（现有 2.26.3 与 vendored app32 2.32.10）均存在（`SDLActivity.java:119`），宿主覆盖 `Activity.setTitle` 即可捕获；
- 上游标题格式为 `"XSystem35 Version <ver>:<游戏标题>"`（冒号分隔；上游 `GameActivity` 用同款解析写 `title.txt`），解析取冒号后段；
- 复用 Siglus 三键协议与导入语义（登记 uri/默认标题 → 宿主写标题 → 库加载条件导入），新增 `Xs35TitleFeedback` 挂在 `MainLibraryViewModel` 现有导入调用点并列执行；
- 兼容性：不写用户游戏的 `title.txt`（上游自有库管理文件，TyranorNext 无用途）。

---

## 10. 里程碑（建议实施顺序）

| 阶段 | 内容 | 依赖 | 预估 |
|---|---|---|---|
| M0 | vendored 源码（SDL2 2.32.10 含 android-project Java 层）+ 依赖 patch（SDL2x 重命名 + `SDL_android.c` app32 前缀）+ app32 Java 层落 `engine/src/main/java/org/libsdl/app32/` + 预构建 `.so` 入库（清单以 readelf 实测回填）+ BUILD.md | 无 | 1.5–2 天 |
| M1 | engine：宿主 `Xs35Activity`（extends app32 SDLActivity）+ Manifest/Theme/consumer-rules（含 `org.libsdl.app32.**` keep）+ 字体 assets 同步 + 契约常量 | M0 | 1 天 |
| M2 | app：EngineType/扫描/启动/存档/插件分支 + 文案 | M1 | 1 天 |
| M3 | 设置链路（独立存档/渲染后端/整数缩放/失焦静音：Store/Resolver/UI/单游戏覆盖 + 标题回写导入） | M2 | 0.5–1 天 |
| M4 | 真机验收（§7.2，含 ONS 共存回归、双存档路径、标题回写） | M3 | 1 天 |
| M5 | README/CONTEXT/许可更新 + 单测/三语言/构建回归 | M4 | 0.5 天 |
| 二期 | 自定义字体（复用 FVP FontImport + `-ttfont_gothic`）、音频缓冲（rc）、CD 镜像解包（§11 的 C2 方案）、存档位置迁移提示向导 | M5 | 待排 |

---

## 11. 附录：CD 镜像安装（功能说明与取舍）

> 本节回答「CD 镜像安装是什么、上游怎么做、TyranorNext 要不要做」；结论见 §11.4。

### 11.1 是什么

AliceSoft System 3.x 时代的游戏按 **CD-ROM 发行**，光盘内容分两部分：

| 部分 | 形态 | 说明 |
|---|---|---|
| 数据轨 | ISO 9660 文件系统，游戏数据在 `GAMEDATA/` 目录（`SA.ALD` 等） | 光驱里可像文件一样读 |
| 音频轨 | **CD-DA**（红皮书音频，不是光盘上的文件） | 就是 BGM，物理上无法用文件管理器复制，只能整盘读取或转轨 |

因此今天玩家手里的资源是**光盘镜像**（.iso / .bin+.cue / .img+.cue / .ccd / .mdf+.mds），而不是现成的游戏文件夹。上游功能就是把镜像**在手机上直接变成可玩的游戏目录**，一步到位、不需要 PC：

1. 解析镜像里的 ISO 9660 文件系统，提取 `GAMEDATA/` 全部文件；
2. 识别其中的场景档（`*s?ald` 正则，如 `SAsa.ald`）；
3. 读取镜像的**音频轨**，逐轨导出为 `cdda/trackNN.wav`（44100Hz/16bit/立体声，补 WAV 头）；
4. 生成 `playlist.txt`（每行一条 `cdda/trackNN.wav`），xsystem35 启动时按序当 BGM 播放（虚拟光驱）。

### 11.2 上游实现（约 740 行 Kotlin，独立于引擎 C 代码）

| 组件 | 文件 | 职责 |
|---|---|---|
| 镜像解析 | `CdImageReader.kt`（566 行） | 支持 `.iso` 单档；`.bin`/`.img` + `.cue`/`.ccd`；`.mdf` + `.mds`（同基名配对，长按多选）。CUE 支持 `MODE1/2048`、`MODE1/2352`、`AUDIO`；CCD/MDS 支持 2352 扇区与 track 偏移；**≥4GB 的 MDS 不支持**（32 位偏移）；随机访问 = `openFileDescriptor` + `FileChannel` 定位读；SAF 返回管道时整盘拷贝到缓存临时文件（用完即删） |
| ISO 9660 | `Iso9660FileSystem.kt`（175 行） | 主/`Joliet` 卷描述符（Joliet → UTF-16 名，否则 Shift-JIS）；按目录记录递归遍历；定位根下 `GAMEDATA`（大小写不敏感） |
| 安装编排 | `GameInstaller.kt`（282 行） | `extractCdImage`：进度回调逐文件；未找到 `GAMEDATA` 或未找到 ALD → 失败且**删除半成品目录**；写出 `GAMEDATA` 标记 + 音频轨 + `playlist.txt` |

### 11.3 TyranorNext 的适配问题

| 点 | 上游 | TyranorNext |
|---|---|---|
| 「安装」概念 | 有（自有游戏库，安装到私有目录） | **没有**：扫描授权目录 → 库条目 → 启动；无安装/复制游戏内容的先例 |
| 输出落点 | 应用私有目录 | 需用户 SAF 目录（与「解封包」工具同模式）可用 SAF 写入；`GAMEDATA` 标记对 TyranorNext 无意义（它由启动参数指出游戏目录） |
| 引擎要求 | 吃 `GAMEDATA/` 目录 | **要求真实路径**（`chdir` + ALD 走 POSIX 文件访问，`safar://` content URI 不可用）→ 输出必须是真目录 |
| 临时文件 | `getExternalCacheDir` 中转 | 同（`openFileDescriptor` 失败时整盘拷贝，`cacheDir` 空间需镜像大小） |
| 容量 | 镜像与输出各一份（零拷贝读原镜像，输出 ≈ 镜像） | 同；需在 UI 提示空间 |

### 11.4 取舍

| 方案 | 内容 | 成本 |
|---|---|---|
| C1（一期） | **不做镜像解包**；仅在扫描侧容忍 `GAMEDATA` 子目录（`<根>/GAMEDATA/SA.ALD` 也识别为 System35），用户自行在 PC/其它工具解出后入库 | 扫描分支约 10 行 + 单测 |
| C2（预排二期） | 独立工具（与「解封包」页同模式）：选镜像 + 元数据文件 + 输出目录 → 解包进所选目录（SAF 写入），含进度/取消/半成品清理 | 移植/改写约 1500–2500 行 + 单测；真机验收（镜像选购、4GB 限制、管道 URI 回退） |

**结论**：一期走 C1（不引入「安装」概念、不阻塞 M0–M5），C2 作为独立工具在二期立项；若用户群体以「手上只有光盘镜像」为主，可提前 C2。

---

## 附录 A：上游关键文件索引（勘察用）

| 主题 | 文件 |
|---|---|
| Android 构建 | `android/app/build.gradle`、`android/app/jni/CMakeLists.txt`、`android/app/jni/Application.mk` |
| 宿主 | `android/app/src/main/java/io/github/kichikuou/xsystem35/GameActivity.kt`、`LauncherActivity.kt`、`GameStore.kt`、`GameInstaller.kt` |
| SDL2 Java 层 | `android/app/src/main/java/org/libsdl/app/*.java`（2.32.10） |
| SDL2 JNI 桥（SDL_JAVA_PREFIX 宏 / JNI_OnLoad 注册） | SDL2 2.32.10 `src/core/android/SDL_android.c`（vendored `third_party/SDL2` 内） |
| 入口/参数 | `src/xsystem35.c`（main、ParseOption、check_profile） |
| 引擎资源识别 | `src/gameresource.c`（ALD 扫描、存档名生成） |
| 字体 | `src/font.c`（open_face Android assets 分支）、`fonts/`、`config.h.in` |
| MIDI/输入桥 | `src/midi.android.c`、`src/input_modal.c` |
| 音频 | `src/music_cdrom.c`、`src/cdrom.mp3.c`、`src/pcm.sdlmixer.c`、`src/bgm.sdlmixer.c` |
| 构建选项 | 根 `CMakeLists.txt`（ANDROID 分支）、`xsys35rc.sample` |

## 附录 B：本轮勘察发现的既有工程事实（改动依据）

| 事实 | 位置 |
|---|---|
| APK `jniLibs` 已有 `libSDL3.so`，无 `libSDL2.so` | `engine/src/main/jniLibs/arm64-v8a/` |
| ONS 插件自带 `libSDL2.so`（SONAME `libSDL2.so`），`libonsyuri.so` 的 DT_NEEDED 含它 | `engine/src/main/nativeplugins/ons/arm64-v8a/`（readelf 实测） |
| 既有 SDL2 Java 层为 `org.libsdl.app`（2.26.3 基线，含 `loadLibraries()`/`getMainSharedObject()`/`setWindowStyle`） | `engine/src/main/java/org/libsdl/app/SDLActivity.java` |
| ONS 宿主继承同一 `SDLActivity`，库经插件绝对路径加载 | `engine/src/main/java/com/yuri/onscripter/ONScripter.java` |
| 预构建 `.so` 入库先例 | `librfvp.so`、`libsiglus.so`、`libarchive_xp3_core.so` |
| 字体 asset 分发先例（ONS `DroidSansFallback.ttf` 复制到私有目录） | `engine/src/main/java/com/core/ons/OnsLibLoader.kt` |
| 引擎页分组/版本弹窗/设置卡片的接入点 | `ui/engine/EngineScreen.kt`、`ui/settings/SettingsScreen.kt`、`EngineSettingsMenuActivity.kt` |
| 现有 `org.libsdl.app`（2.26.3）Java 层在 `onCreate` 对 `nativeGetVersion()` **硬校验**（≠"2.26.3" 即弹「SDL C/Java version mismatch」退出，onCreate 内部流程、子类不可绕过）——与 2.32.10 native 混用必然失败 | `engine/src/main/java/org/libsdl/app/SDLActivity.java:943-964` |
| 现有 SDLActivity 已本地接入 `DoubleBackExit.handleBack`（trap-back hint 命中交 SDL，未命中双击退出）——app32 层的双击退出在宿主子类实现同款策略 | `engine/src/main/java/org/libsdl/app/SDLActivity.java:895-901` |

## 附录 C：新增文案键（三语言，示例）

| 键 | zh | ja | en |
|---|---|---|---|
| `engine_desc_xsys35` | System3.x（xsystem35） | System3.x（xsystem35） | System3.x (xsystem35) |
| `engine_settings_xsys35_title` | System35 | System35 | System35 |
| `engine_settings_xsys35_renderer_title` | 渲染后端 | レンダラー | Renderer |
| `engine_settings_xsys35_renderer_auto` | 自动 | 自動 | Auto |
| `engine_settings_xsys35_renderer_opengl` | OpenGL | OpenGL | OpenGL |
| `engine_settings_xsys35_renderer_software` | 软件渲染 | ソフトウェア | Software |
| `engine_settings_xsys35_integer_scale_title` | 整数倍缩放 | 整数倍スケーリング | Integer scaling |
| `engine_settings_xsys35_mute_unfocus_title` | 切后台静音 | 非フォーカス時にミュート | Mute when unfocused |
| `engine_settings_xsys35_note` | 文本为 Shift-JIS；独立存档开启时存档位于应用存档目录，关闭时位于游戏目录 save/ | テキストは Shift-JIS。独立セーブ有効時はアプリのセーブフォルダ、無効時はゲームフォルダの save/ | Text is Shift-JIS; saves live in the app save dir when scoped saves are on, otherwise in the game's save/ |

> engine 侧宿主 Toast 文案（`engine_xsys35_missing_game_dir` 等）在 `engine/src/main/res/values{,-en,-ja}/strings.xml` 三份同步；实际文案以项目三语言校对习惯为准，新增后必须通过 `python3 tools/check-hardcoded-ui-strings.py`。

---

## 12. 实施记录（2026-10-13）

一期实施与方案的差异、审核修正如下（其余按方案落地）：

1. **patch 面比 §3.4 多一个文件**：`third_party/SDL2/src/hidapi/android/hid.cpp` 的 `SDL_JAVA_PREFIX` 也必须 app32 化（否则 `HIDDeviceManager` 的 JNI 符号仍是 `Java_org_libsdl_app_*`，app32 Java 层启动即 `UnsatisfiedLinkError`）；完整改名面为 **2 个宏 + 4 处 JNI_OnLoad 字符串**，patch 面复核命令更新为对全 `src/` 的 `grep -rn 'org_libsdl_app\|org/libsdl/app' third_party/SDL2/src/`。
2. **libwebp 构建前以目录作用域 `BUILD_SHARED_LIBS OFF` 抑制其产出独立 .so**：SDL2_mixer 的 `option(BUILD_SHARED_LIBS … ON)` 会把该 cache 变量置 ON，而 libwebp 的库 target 跟随它；不处理会多出 `libwebp.so` / `libsharpyuv.so`（`WEBP_LINK_STATIC` 只影响工具链接）。产物保持 3 个 .so，处理位置见 `engine/xsystem35/android-host/CMakeLists.txt`。
3. **扫描判定块位置由「FVP 之后、framebuffer 组之前」调整为「CS2 之后、ARTEMIS 之前」**：原位置对「既有引擎特征 + 杂散 `*.ALD`」目录会以 XSYS35 低分档抢判（回归审核 M1），宿主预检拒绝后表现为原游戏无法启动；后移后消除抢判，XSYS35 真实目录不含既有引擎特征，召回不受影响（配套补「既有引擎 + ALD」回归用例）。
4. **标题回写 hash 改为显式 extra `XS35_PATH_HASH`**（替换原 `XS35_ORIGINAL_ROOT` 自行推导），对齐 Siglus 先例；导入侧以 `MODE_MULTI_PROCESS` 读取 + 前台恢复重跑导入，缓解跨进程 prefs 缓存限制（跨进程整条写回覆盖风险仍属已知限制）。
5. **`cleanupAppData` 补 XSYS35 分支**（原 §4.5「无应用私有数据」判断有误——默认独立存档即在应用外部存储）；默认目录名统一用 `Xs35Paths.scopedSaveDir`。
6. **宿主返回键行为修正**：上游 SDL 2.32.10 在 `dispatchKeyEvent` 转发并消费返回键，游戏内实际行为是引擎自绘退出菜单（AC_BACK）；宿主 `onBackPressed` 覆写仅覆盖系统直接调用的兜底路径（启动早期等），「双击退出」在该引擎不作为主要退出手段。
7. **vendored 裁剪与体积登记**：删除非 Android 平台/测试目录（清单、引用检查与裁剪后从零重建验证见 `engine/xsystem35/BUILD.md` §2.1）；APK 增量实测约 +8.1MB（3 个 .so 3.37MB + 两份字体未压缩 6.7MB），且 `noCompress ttf/otf` 使既有 `DroidSansFallback.ttf` 也变为 Stored（release 预计 +13–14MB，超出 §3.3 的 4–6MB 预估）；属 xsystem35 字体读取性能的硬需求取舍。
8. **宿主横屏强制**：xsystem35 的 resizable 窗口 + 未设 `SDL_HINT_ORIENTATIONS` 使上游 SDL 请求 FULL_USER（受系统旋转锁影响，锁竖屏时游戏竖屏信箱化）；`Xs35Activity` 覆盖 `setOrientationBis` 始终请求 sensorLandscape（真机复验：锁定竖屏与自动旋转两种状态下均横屏）。
9. **运行期系统弹窗统一到引擎层 `KrDialogStyle`**（KRKR/Artemis 共用）：覆盖 app32 `messageboxCreateAndShow`（引擎错误/提示消息框）并将 `inputString`/`inputNumber` 改用统一样式；`KrDialogStyle` 新增 `showNumberBox` 数值输入入口（加法）。
10. **引擎与依赖源码不 vendored 进主仓**：来源为 fork `Weiss-UltimateSavior/xsystem35-sdl2`（固定 commit `18af9be`），依赖由 `scripts/build-xsys35.sh` 按 SHA1 下载到仓库外构建目录；主仓保留预构建 .so、`android-host` 构建剧本、patches、app32 生成脚本与 BUILD.md；内置字体（MTLc3m/mincho）改为直接提交 `engine/src/main/assets/`；原 vendored 目录（约 65MB）已删除。
