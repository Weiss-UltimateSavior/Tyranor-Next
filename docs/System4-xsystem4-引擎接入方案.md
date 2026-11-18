# System4（xsystem4）内置引擎接入方案

> 状态：**已实施（代码、构建产物、测试与文档均已落地，未提交；独立审核已完成两轮并修复，真机验收未做）**；**一次性交付，不分期**。文中带「实施期修正」「实测」字样的段落以实际实现为准。
> 上游：`nunuhara/xsystem4`（GPL-2.0-or-later，AliceSoft System 4 引擎开源重实现）＋ 子项目 `nunuhara/libsys4`（GPL-2.0-or-later）；Android 构建参考 `kichikuou/xsystem4-android`（**仓库无 LICENSE，仅作事实参考，不拷贝其任何文件**，§5.5）。
> 固定基线：xsystem4 `6a94a0c5637ce640e787b505ffb2bd21e881f970`（master，2026-10-16）＋ libsys4 `e2bf438d0156a2ca70fc6018fddf7c870794a2ca`。**不能用壳仓库的 `04333ad`**：该 commit 不含 `--encoding`/GBK 支持（`--encoding` 由 `71dbfda`（2026-10-03）引入，并把 libsys4 升到 `e2bf438`）；两基线间 `CMakeLists.txt` 无差异，Android 构建剧本不受影响（核验实测 `git diff` 为空）。
> 后续更新（2026-11-14）：运行库重编至 xsystem4 `46f7c253d63d7b513516f42af027419314cbf047`（`6a94a0c` 之后 26 个上游提交；libsys4 / libsndfile 固定 commit 不变），产物哈希与实测见 `engine/xsystem4/BUILD.md` §8 / §10。
> 本地勘察：`/Volumes/HUAWEI-M2LITE/app/xsystem4-android` = 本项目账号下的 fork **`Weiss-UltimateSavior/xsystem4-android`**（公开，fork 自 `kichikuou/xsystem4-android`；`master` 与上游**逐字相同**，HEAD `1c8f691`，v1.0.7，无本地修改）；其 `xsystem4` 子模块仍指向 `nunuhara/xsystem4` 且未检出，引擎源码结论来自 GitHub 同仓库源码。fork 不改变壳代码的版权状态（§5.5）。
> 结论摘要：**内置 host 路线**，新增独立 `EngineType.XSYS4`；与已落地的 System35 **共用 `libSDL2x.so`（SDL2 2.32.10）与 `org.libsdl.app32`**，预期只新增 `libxsystem4.so`；字体**默认复用 App 现有字体**（APK 增量 0）并支持用户导入自定义字体。
> 交付范围（一次做完）：扫描识别（含旧库误判纠正）＋ 启动 ＋ 存档镜像 ＋ 设置（独立存档 / 文本编码 / 自定义字体 / 手柄 / 跳字延迟）＋ 手册页 ＋ exe 图标封面兜底 ＋ 文档 / ADR / 测试 ＋ 真机验收。

---

## 0. 术语与命名

| 项 | 取值 |
|---|---|
| EngineType | `XSYS4`（新增，置于 `XSYS35` 之后） |
| 显示名 / 角标 | `System4` / `S4X`（与现有 abbr 不冲突，已核对） |
| 引擎页分组 | GAL（`EngineTab.GAL`） |
| launchMode | `internal.xsys4` |
| 宿主类 | `com.core.xsys4.Xs4Activity`（进程 `:xsys4`，`taskAffinity=com.core.Xsys4`，主题 `Theme.Xsys4`）；手册页 `Xs4ManualActivity`（同进程） |
| 引擎库 | `libxsystem4.so`（`SDL_main` 入口） |
| SDL2 运行库 / Java 层 | **复用** `libSDL2x.so`（SONAME `libSDL2x.so`）与 `org.libsdl.app32`（System35 已入库），不重复构建 |
| 静态链入依赖 | freetype / libwebp / libpng / libjpeg-turbo / libffi / libsndfile(+ogg+vorbis) / libdeflate / cglm / libsys4（均不产出独立 `.so`，以 §5.4 实测为准） |
| 默认字体 | 日文版 gothic=`MTLc3m.ttf`、mincho=`mincho.ttf`；GBK 版二者=`DroidSansFallback.ttf`（现有 assets，§3.5）；用户可导入自定义字体覆盖 |
| 存档目录 | 独立存档开关（默认**开**）：`getExternalFilesDir(null)/save/<safeSaveName>`；关闭 = `<游戏根>/<ini SaveFolder，缺省 SaveData>`（经校验） |
| 引擎设置分类 | 新增 `EngineSettingsKind.XSYS4` |
| 识别特征 | 根目录 `System40.ini` 或 `AliceStart.ini`（硬特征）＋ 根目录 `*.ain`（辅助）；且无 `system39.ini/.ain` |
| 版本条目 | id `xsys4-6a94a0c`，标题 `xsystem4-6a94a0c`（xsystem4 无发行号，用固定 commit 短 hash） |
| 源码托管 | fork `Weiss-UltimateSavior/xsystem4`、`Weiss-UltimateSavior/libsys4`、`Weiss-UltimateSavior/libsndfile`（均**待建**，§5.4 步骤 0） |

---

## 1. 背景

xsystem4 是 AliceSoft **System 4**（约 2000–2012 年作品）引擎的开源重实现，GLES3 渲染。`kichikuou/xsystem4-android` 是其 Android 壳（v1.0.7，APK 27.3 MB，三 ABI）。

上游兼容表（master）标 **Supported**：Rance VI、戦国ランス（含 MangaGamer / Steam 中文版）、Rance 01 / 02 / IX、ランス・クエスト（含マグナム）、大番長、DALK外伝、パステルチャイム3、闘神都市III、はるうられ、エドランゼ 等；**Unsupported**：Evenicle、Rance 03、Rance X 及其后所有作品、ZERO、どらぺこ！ 等。

接入价值：与已落地的 System35 合起来覆盖 AliceSoft 自 System 3.x 到 System 4 的主要经典作品；Steam 中文版（GBK）可直接玩，对本项目用户群价值高。

> System 3.x 与 System 4 是**两个不同的引擎**：目录特征（`System39.*` vs `System40.ini`/`AliceStart.ini`）、启动参数、设置项、存档格式均不同，故不并入 `XSYS35`（§9-D1）。

---

## 2. 上游勘察结论

### 2.1 Android 构建与运行时

| 项 | 结论 | 来源 |
|---|---|---|
| 引擎 Android 构建 | 根 `CMakeLists.txt` 自带 Android 分支（首行注释「CMakeLists.txt for Android build」）：`add_library(xsystem4 SHARED)`、`USE_GLES`、链接 `GLESv3`；`cglm` 经 FetchContent（v0.9.2）；`execute_process(git rev-parse --short HEAD)` 生成 `version.h` | 上游 `CMakeLists.txt` |
| 壳仓库产物 | `libxsystem4.so` + `libSDL2.so` + `libcglm.so`（共享），其余静态；`shaders/`、`fonts/` 装入 assets | 壳 `CMakeLists.txt:145-153` |
| SDL 版本 | 壳用 **2.30.9** + 自带 `org.libsdl.app`（常量 2.30.9） | 壳 `cmake/FindSDL2.cmake`、`SDLActivity.java:62-64` |
| 构建参数 | `ANDROID_PLATFORM=21`、三 ABI、Release、全依赖 LTO；需 flex / bison / CMake ≥3.21 | 壳 `build-shared-libs.sh`、README |
| 宿主 | `XSystem4Activity : SDLActivity`，`:game` 进程；`getLibraries()=["SDL2","xsystem4"]`；`getArguments()=["--save-folder",<存档>,"--save-format=rsm",<游戏根>]`；`onDestroy` → `killProcess` | 壳 `XSystem4Activity.kt:23-40` |
| 外部可启动性 | `LauncherActivity exported=true`、`XSystem4Activity exported=false` 且需 extras → **无法作为外置 APK 模块按路径启动**，只能内置 | 壳 `AndroidManifest.xml:71-102` |
| 游戏识别 | `config_init_with_dir`：根目录 `System40.ini`，否则 `AliceStart.ini`（`file_exists`）；ini 提供 `GameName` / `SaveFolder` / ain 名 | 上游 `system4.c` |
| 命令行选项（基线 `6a94a0c`） | `--encoding`(仅 `sjis`/`gbk`)、`--font-mincho`、`--font-gothic`、`--font-fnl`、`--font-x-scale`、`--joypad[=on/off]`、`--msgskip-delay`、`--save-folder`、`--save-format`(`json`/`rsm`)；游戏目录为位置参数 | 上游 `system4.c`（master） |
| 配置文件 | `<home>/.xsys4rc` 与 `<游戏目录>/.xsys4rc`；`home` 取 `$XSYSTEM4_HOME`→`$XDG_DATA_HOME/xsystem4`→`$HOME/.xsystem4`→CWD；命令行覆盖 rc | 上游 `system4.c` |
| 存档默认位置 | 未传 `--save-folder` 时 = `<home>/<GameName>/<SaveFolder>`；传了直接用并 `mkdir_p` | 上游 `system4.c` |
| 失败行为 | `usage_error`（`_Noreturn`）打印 usage 后退出 → SDL 线程结束，Activity 黑/白屏 | 上游 `system4.c` |
| 触控 | 引擎内实现：轻点=左键、点黑边=右键、长按 1s=Ctrl、双指上下滑=滚轮；宿主零代码 | 上游 `input.c` |
| 着色器 | 相对路径 `shaders/*.glsl`（53 个，约 79 KB），经 SDL RWops 读 assets | 上游 `video.c`/`draw.c`/`3d/renderer.c` |
| 字体 | Android 下 `SDL_LoadFile` 整文件读入内存；缺省 `fonts/VL-Gothic-Regular.ttf` / `fonts/HanaMinA.ttf`，`--font-*` 优先，失败才回落缺省；`y_offset=-round(size*0.15)` 等度量按缺省字体调校 | 上游 `font_freetype.c` |
| 视频 | Android 只编 `movie_plmpeg.c`（MPEG-1） | 上游 `CMakeLists.txt` |
| 手册 | 自定义消息 `0x8000`（`SystemService.c`）→ 壳 `ManualActivity` 用 WebView 开 `Manual/index.html` | 壳 `XSystem4Activity.kt:14,42-58` |
| 大小写 | libsys4 `file.c:379` `path_get_icase`（目录枚举 `strcasecmp`） | libsys4 |
| 体积 | 上游 APK 27.3 MB（三 ABI，含 26.8 MB 字体）；`libxsystem4.so` arm64 **实测 5 671 944 B**（strip 后，gzip -9 约 2.26 MB；最初估算 2.5–3.5 MB 偏低） | release v1.0.7；`engine/xsystem4/BUILD.md` §8 |
| 许可 | xsystem4 / libsys4：GPL-2.0-or-later；HanaMinA = Hanazono Font License / SIL OFL | 源文件头、`COPYING` |

### 2.2 依赖清单与校验（核验已逐项下载比对，与壳仓库一致）

| 依赖 | 版本 / 来源 | 校验 | 许可 | 形态 |
|---|---|---|---|---|
| SDL2 | 2.32.10（**仅头文件**；与 System35 同 tar 包） | SHA1 `ce98fa93e31836a751feca374ab28a0770b63c16` | zlib | 链接已入库 `libSDL2x.so` |
| freetype | 2.14.3（复用 System35 缓存；壳用 2.13.3） | SHA1 `a911f65c6355ddcf7aaba46e250dbc00f67e1678` | FTL / GPLv2 | 静态 |
| libogg 1.3.5（`.tar.xz`） | xiph | SHA1 `5a368421a636f7faa4c2f662857cb507dffd7c99` | BSD-3 | 静态 |
| libvorbis 1.3.7（`.tar.xz`） | xiph | SHA1 `0a2dd71a999656b8091506839e8007a61a8fda1f` | BSD-3 | 静态 |
| libjpeg-turbo 3.1.0 | — | SHA1 `ba3c0152f14a504018de19c9f62250d8f3351525` | BSD-3 / IJG | 静态 |
| libwebp 1.3.2 | — | SHA1 `f941661a869df7347d7ca9208acb4e6d41f614de` | BSD-3 | 静态 |
| libpng 1.6.47（`.tar.xz`） | sourceforge | **SHA256** `b213cb381fbb1175327bd708a77aab708a05adde7b471bc267bd15ac99893631` | libpng | 静态 |
| libffi 3.4.6 | autotools release 包 | SHA1 `19251dfee520dff42acefe36bfe76d7168071e01` | MIT | 静态 |
| libdeflate 1.25 | — | SHA1 `7e70557d094b54c3ee16f6c012976dc8d685b22a` | MIT | 静态 |
| cglm 0.9.2 | — | SHA1 `cb8472aa8c2ab67b66378dbaf10c2c7368d4e4c3` | MIT | 静态（`CGLM_SHARED=OFF CGLM_STATIC=ON`，核验确认 v0.9.2 该组合产 STATIC `cglm` 目标） |
| libsndfile | `kichikuou/libsndfile` @ `be82f4be73c77f1e759bda9dfe1625204441465a`（分支 `xsystem4`，去 FLAC/Opus） | commit | LGPL-2.1-or-later | 静态 |
| libsys4 | `nunuhara/libsys4` @ `e2bf438d0156a2ca70fc6018fddf7c870794a2ca` | commit | GPL-2.0-or-later | 静态 |

### 2.3 缺口与处理

| # | 缺口 | 处理 |
|---|---|---|
| G1 | 壳 SDL 2.30.9 + `org.libsdl.app` 会与 ONS 的 `libSDL2.so` 同名冲突，且与 app32 版本硬校验不兼容 | 改链已入库 `libSDL2x.so`，宿主继承 `org.libsdl.app32.SDLActivity`（§3.4） |
| G2 | **现有扫描会把 System 4 目录判成 XSYS35**：根目录 `*.ald`（如 `Rance6BA.ald`）满足 `hasAldScript && aldCount>=2` → 90（`EngineScanner.kt:1005-1007`，核验推演确认） | XSYS4 规则前置于 XSYS35（§4.2）＋ 回归测试 ＋ 旧库一次性纠正（§4.2） |
| G3 | 上游字体 26.8 MB；本项目 `ttf/otf` 设 `noCompress`（`app/build.gradle.kts:249`）会原样存储 | 默认复用现有字体 + 自定义导入（§3.5） |
| G4 | 壳仓库 CMake 依赖网络且无许可证；上游 `CMakeLists` 用 FetchContent 拉 cglm | 自写构建剧本与脚本，cglm 指本地；**不得用 wrapper**（VCS_TAG 坑，§5.4） |
| G5 | 存档默认写 `<home>/…`，Android 无 `HOME` 落到 CWD（`/`） | 启动**必传绝对路径 `--save-folder`** |
| G6 | 标题：ini 里就有 `GameName` | 扫描时解析，不做回写协议（§4.2） |
| G7 | 中文 GBK 版需 `--encoding gbk` + 含全 GBK 字形的字体 | 编码设置 + 字体按编码映射（§3.5、§4.5） |
| G8 | 未支持作品（Rance X 之后、Evenicle）也含 `AliceStart.ini` | 信息卡写明兼容边界；不做作品黑名单 |
| G9 | 视频仅 MPEG-1 | 已知限制，信息卡说明，真机确认影响面 |
| G10 | 系统 bison 2.3 无法解析 libsys4 的 `ini_parser.y`（需 ≥3.0，核验实测），且 libsys4 CMake 对 `find_package(BISON)` 不带 REQUIRED、会静默选中旧版 | 脚本自动选 brew bison 并显式传 `-DBISON_EXECUTABLE`（§5.4） |
| G11 | `Weiss-UltimateSavior` 下已有 `xsystem4-android`（壳）、`xsystem35-sdl2`、**`xsystem4`**（2026-10-17 建好，本地 `/Volumes/HUAWEI-M2LITE/app/xsystem4`，`master` HEAD = `6a94a0c`，含 `--encoding` 的 `71dbfda`，`subprojects/libsys4` gitlink = `e2bf438…`，均与本方案固定基线一致）的 fork；`libsys4`、`libsndfile` 的 fork 也已建好并核对固定 commit | 四个 fork 齐备（含 GPL / LGPL 源码长期可得性）；壳 fork 保持原样，仅作参考，不作为构建输入 |
| G12 | 现有库里「已被误判为 XSYS35 的 System 4 游戏」增量扫描不会改判（`known` 剪枝） | 升级后一次性定点重检测 XSYS35 条目（§4.2） |

| G13 | 引擎遇错（`ERROR`/`usage_error`）走 `sys_error_handler`（`SDL_ShowSimpleMessageBox`）→ logcat → `sys_exit` = **`exit()` 整个 `:xsys4` 进程**，Activity 不走 `onDestroy`；游戏正常退出同样直接 `exit()` | 宿主不依赖 `onDestroy` 收尾；消息框覆写（`messageboxCreateAndShow`）因此更重要；预检必须把可预见错误拦在进入 SDL 之前（§5.1） |
| G14 | `System40.ini` / `AliceStart.ini` / ain / `Data/…` / `.xsys4rc` 只认**精确大小写**（`file_exists`=`access()`）；仅资源包枚举（`asset_manager.c`）与视频（`path_get_icase`）大小写不敏感 | App 扫描与宿主预检大小写不敏感，宿主解析出**实际 ini 绝对路径**作为位置参数传入（引擎支持 `.ini` 路径参数，取其目录为游戏目录）；ain 大小写不一致属 §7.2 验收项 |
| G15 | 字体加载失败是**致命**错误：`--font-*` 失败 → `XSYS4_DATA_DIR/fonts` → 相对 `fonts/…` → `ERROR("Failed to load … font")` 退出（`text.c:50-72`） | 宿主在传参前校验字体（自定义路径存在且可读，否则回落内置映射；内置 asset 名存在性由构建期门禁保证） |

---

## 3. 总体设计

### 3.1 路线选择

| | 外置 APK 模块 | **内置 host（选定）** |
|---|---|---|
| 可行性 | **不可行**：上游 `XSystem4Activity` 为 `exported=false` 且依赖 extras，无法按路径外部启动（§2.1），现有外置模块协议（`core/engine/external`）无法套用 | 与 KRKR / ONS / Siglus / FVP / System35 先例一致 |
| 主 APK 体积 | 不变 | **实测**：`libxsystem4.so` strip 后 5 671 944 B（5.41 MiB），gzip -9 约 2.26 MB，`useLegacyPackaging=true` 下 APK 增量≈2.3 MB；SDL2 与字体零增量（壳 APK 内压缩 2.71 MB 含符号表，口径一致） |
| 体验 | 双 APK、游戏需放进外置 APK 私有目录 | 单 APK；all-files、存档镜像、单游戏设置、引擎页全复用 |
| 许可 | 进程隔离 | GPL-2.0-or-later 随 App 分发，本项目已是 GPL-2.0（与 System35 同口径，§5.5） |

### 3.2 数据流

```
App（游戏库卡片点击）
  └─ EngineLauncher.buildXs4Intent(XSYS4)
       extras: PATH/GAME_PATH/PROJECT_ROOT/GAME_DIR（= 游戏根，无 GAMEDATA 概念）、LAUNCH_TARGET、ROOT_URI
               LAUNCH_MODE=internal.xsys4
               XS4_SAVE_DIR（绝对路径）、XS4_SCOPED_SAVE_DIR、XS4_ENCODING(sjis|gbk)、
               XS4_FONT_GOTHIC_PATH / XS4_FONT_MINCHO_PATH（仅用户导入的自定义字体绝对路径，缺省不下发）、
               XS4_JOYPAD(auto|on|off)、XS4_MSGSKIP_DELAY(ms，0=引擎默认)、ORIENTATION
  └─ Xs4Activity（:xsys4 进程，extends org.libsdl.app32.SDLActivity）
       ├─ onCreate 预检：根目录大小写不敏感解析出实际 ini（System40.ini → AliceStart.ini）；GLES ≥ 3.0；
       │                 自定义字体可读性；内置字体映射（Xs4Fonts，按编码）
       ├─ getLibraries()：["SDL2x", "xsystem4"]（其余依赖静态，无需显式加载）
       ├─ getArguments()：["--save-folder", <存档>, "--save-format=rsm", "--encoding", <sjis|gbk>,
       │                    "--font-gothic", <..>, "--font-mincho", <..>, ["--joypad=on|off"], ["--msgskip-delay", <ms>],
       │                    <实际 ini 绝对路径>]            ← 位置参数放最后
       ├─ SDL 原生主线程：nativeRunMain("libxsystem4.so", "SDL_main", args)
       │    └─ xsystem4 main()：getopt_long → config_init_with_ini → 读 .xsys4rc（被命令行覆盖）→ VM 执行 ain
       ├─ 触控/手柄：引擎内处理（宿主不转发）
       ├─ 自定义消息 0x8000：onUnhandledMessage → 启动 Xs4ManualActivity（WebView，只读游戏 Manual/）
       └─ 返回键：与 Xs35Activity 同款（trap-back 交 SDL；兜底双击退出）
```

### 3.3 引擎库打包形态（选定）

```
engine/
  xsystem4/
    android-host/cmake/             ← 自研 FindSDL2 / FindFreetype / FindSndFile 三个 find 模块（不拷贝壳仓库文件）
    scripts/build-xsys4.sh          ← 一键可复现构建（依赖下载 + fork 源码 + 构建 + 入库 + 门禁）
    licenses/                       ← 随二进制分发的依赖与第三方许可文本（§5.5）
    BUILD.md
engine/src/main/jniLibs/arm64-v8a/
  libxsystem4.so                    ← 新增，预构建入库（SONAME=libxsystem4.so）
  libSDL2x.so                       ← 既有（System35 入库，两引擎共用）
engine/src/main/assets/
  shaders/…                         ← 53 个文件 ≈79 KB，**保留子目录层级**（含 shaders/effects/*.glsl）
```

- 不再设独立 `android-host/CMakeLists.txt` wrapper：xsystem4 fork 作为**顶层 `-S`**，用 `-DCMAKE_MODULE_PATH` 提供自研 find 模块（理由见 §5.4：wrapper 会让 `git rev-parse` 取到主仓提交号，破坏可复现）。
- 目录名 `shaders/` 与现有 assets 无冲突（现有 `anime4k/`、`rpgmaker/`、`ui/`、`img/`…；`app/build.gradle.kts:243` 的 `shaders=false` 只是 AGP 开关，与 assets 无关）。
- 不构建 armeabi-v7a / x86_64；`jniLibs` 与 `useLegacyPackaging=true` 现有配置无需 Gradle 改动，assets 随 engine 库自动并入。

### 3.4 SDL 运行时共享策略（关键决策）

System35 已把 SDL2 2.32.10 构建为 `libSDL2x.so` 并配套 `org.libsdl.app32`（见 `docs/System35-xsystem35-引擎接入方案.md` §3.4）。System4 **直接共用**：

| 方案 | 结果 | 结论 |
|---|---|---|
| A. 沿用壳的 SDL 2.30.9 + `org.libsdl.app` | `libSDL2.so` 与 ONS 同名冲突；Java 层 2.30.9 又需第三套包名 | ✗ |
| B. 另建一份重命名 SDL（如 `libSDL2x4.so` + `app30`） | APK 两份 SDL2（+≈1.5 MB）、两套 Java 层 | ✗ |
| **C. 共用 `libSDL2x.so` + `app32`（选定）** | APK 只一份；进程隔离（`:xsys35`/`:xsys4`）互不影响 | ✓ |

已核验的事实：
- `libSDL2x.so`：SONAME `libSDL2x.so`、版本串 `SDL-release-2.32.10`、导出 `Java_org_libsdl_app32_*` 52 个、无旧前缀、`LOAD p_align=0x4000`。
- 壳 `libxsystem4.so` 引用的 53 个 `SDL_*` 符号全部存在于本仓 `libSDL2x.so`；xsystem4 + libsys4 源码中所有 `SDL_xxx(` 调用名与导出表比对，缺失的仅 `SDL_PointInRect/PointInFRect/RectEmpty/VERSION_ATLEAST/arraysize`（头文件内联或宏，非链接问题）。
- 链接实验（NDK clang + `-flto -fvisibility=hidden` 链 `libSDL2x.so`）：产物 `NEEDED=libSDL2x.so`，`SDL_main` 仍导出为 `T`（`SDL_main.h` 在 Android 下 `#define main SDL_main`，声明默认可见）。ELF 的 NEEDED 取自被链接库自身 SONAME，故自研 `FindSDL2.cmake` 里的 `IMPORTED_SONAME` 是多余但无害的防御。

实现要点：自研 `FindSDL2.cmake` 提供 `SDL2::SDL2`：头文件取 System35 构建同款 SDL2 2.32.10 源（仅头文件），库指向 `engine/src/main/jniLibs/arm64-v8a/libSDL2x.so`（IMPORTED SHARED）。SDL 2.30.9→2.32.10 的 xsystem4 行为差异作为 §7.2 门禁。

### 3.5 字体策略

实测（fontTools 读 cmap；cp932 的 1880 个「缺失」全是 `0xF040–0xF9FC` 造字区，所有字体一致，实际 JIS X 0208 + NEC/IBM 扩展均已覆盖）：

| 字体 | 来源 | 体积 | JIS X 0213 | GBK |
|---|---|---|---|---|
| `MTLc3m.ttf` | App 现有（xsystem35 gothic） | 1.92 MB | 64.7% | 33.2% |
| `mincho.ttf` | App 现有（xsystem35 mincho） | 4.78 MB | 64.6% | 33.2% |
| `DroidSansFallback.ttf` | App 现有（ONS） | 7.29 MB | 95.3% | 100% |
| `VL-Gothic-Regular.ttf` | xsystem4 上游 gothic | 4.09 MB | 92.5% | 60.0% |
| `HanaMinA.ttf` | xsystem4 上游 mincho | 22.76 MB | 99.7% | 100% |

| 方案 | APK 增量 | 说明 |
|---|---|---|
| A. 上游原样（VL-Gothic + HanaMinA） | **+26.8 MB**（`noCompress` 原样存储；改非 `.ttf` 扩展名约 +15 MB） | 字形最全；`SDL_LoadFile` 整文件入内存，运行时多占约 27 MB |
| **B. 默认复用现有字体 + 自定义导入（选定）** | **0** | 日文 CP932 全覆盖；GBK 用 Droid；想要 HanaMinA 等的用户自行导入 |
| C. 补 VL-Gothic | +4.1 MB | **验收后备**：§7.2-5 目检 B 不可接受时，在同一次交付内补入并改默认映射 |

规则（字体名与映射集中在 engine 侧 `com.core.xsys4.Xs4Fonts`，App 只下发编码与自定义路径，避免 App 写死 System35 的 asset 名）：
- 默认映射：`sjis` → gothic=`MTLc3m.ttf`、mincho=`mincho.ttf`；`gbk` → 二者=`DroidSansFallback.ttf`（asset 名；`SDL_RWFromFile` 相对路径先试内部存储再回落 assets，已核对 SDL 2.32.10 源码）。
- 自定义：设置页导入（复用 `FontImport.importToPrivate` → `filesDir/fonts/`），设置键保存路径；启动时 App 下发绝对路径，宿主校验（绝对、存在、可读、扩展名白名单）后传 `--font-gothic/--font-mincho`，任何一项失败回落默认映射并记日志。
- 跨引擎耦合声明：`MTLc3m`/`mincho`/`DroidSansFallback` 现在被 System35、ONS、System4 共用，`engine/xsystem35/BUILD.md` 与 `app/build.gradle.kts:246` 注释需点明（防日后改字体漏掉 System4）。

⚠ 度量风险：`y_offset=-round(size*0.15)`、半角判定、`GLYPH_BORDER_SIZE` 按 VL-Gothic / HanaMinA 调校，换字体可能基线偏移；字体加载失败为致命错误（G15）。→ §7.2 必检。

### 3.6 其余关键决策

1. **独立 `EngineType.XSYS4`**（§9-D1）；宿主继承 `org.libsdl.app32.SDLActivity`。
2. **存档必传绝对路径 `--save-folder`**：独立存档开（默认）= `getExternalFilesDir/save/<safeSaveName(游戏根)>`；关闭 = `<游戏根>/<SaveFolder>`（ini 值**必须校验**：非绝对、不含 `..`、`\`→`/`，否则回落 `SaveData`）。`--save-format=rsm` 显式传（引擎默认已是 rsm，usage 文案过期；与壳一致）。同一目录若被改判引擎，沿用同一 `save/<name>`；因 xsystem35 预检会拦下 System 4 目录，实际不会产生 xsystem35 存档。
3. **不注入 `.xsys4rc`**：设置走命令行（命令行覆盖 rc）；用户自放的游戏目录 rc 仍被引擎读取，不干预。`home_dir` 在 Android 为 `/`，全局 rc 路径 `//.xsys4rc` 读不到，无害。
4. **标题由 App 扫描时解析 ini `GameName`**；用户改名的保留沿用**现有行为**：`rescanLibrary` 的 `copy(...)` 不保留 `title`，全量重扫会把标题重置为扫描结果（所有引擎同，现为目录名，XSYS4 为 `GameName`），本方案不改此语义。
5. **手册页**：收到 `0x8000` → `Xs4ManualActivity`（自研 WebView，§5.1）。
6. **exe 图标封面兜底**：本项目已有「本地封面」链路（`EngineScanner.applyLocalCover`、`CoverScraperService.kt:62`），XSYS4 在其后追加「游戏目录 `.ico` / exe 内置图标 → App 缓存 PNG」兜底（§4.8）；**不写入游戏目录**（壳会写 `.xsystem4.ico`）。
7. **设备门槛**：GLES 3.0。**不声明** `uses-feature glEsVersion`（该属性对 `glEsVersion` 不支持 `required=false`，库清单合并后会被商店过滤整机型，与运行时预检设计矛盾），仅宿主预检 `reqGlEsVersion` 并 Toast。engine 清单已有 `VIBRATE`（壳唯一权限），无需新增权限。

---

## 4. App 侧改动清单

System35 落地涉及 app 21 个源文件 + engine 10 个 + 测试 6 个 + 文档若干，下表逐项镜像（行号为现状，已核对）。`when(engine)` 多处是穷尽无 else，漏改即编译失败：`EngineLauncher.buildIntent`、`GameSaveManager.resolveSaveLocation`、`EnginePluginBootstrap`、`EngineScreen`（三处）、`GameScreen.coverColor`。

### 4.1 引擎类型与展示

| 文件 | 改动 |
|---|---|
| `core/engine/EngineType.kt` | 新增 `XSYS4("System4", "S4X")`（紧随 `XSYS35`，`:23`）；`EngineTypeAbbrTest` 补 `S4X` |
| `core/game/launch/EngineLauncher.kt` | `supportedEngines`（`:129`）加入；`buildIntent` 分支（`:875`）；`:390` 同款 `ensureXs4SaveDir`；新增 `import com.core.xsys4.Xs4Activity`（core 依赖 engine，规则允许，同 `:38`） |
| `ui/engine/EngineScreen.kt` | GAL 分组（`:470`）；`engineDescription`（`:500`）；`builtinDialogEntries`（map 在 `:544`，XSYS35 条目在 `:567`）加 `builtinEntry("xsys4-6a94a0c", "xsystem4-6a94a0c")` |
| `ui/game/GameScreen.kt` | `coverColor()`（`:2208`）新增 `XSYS4 -> Color(0xFF00A176)`（青绿；与全部现有色 Lab ΔE≥24、白字对比度 3.3，已核验） |
| `core/engine/plugin/EnginePluginBootstrap.kt` | 内置引擎 `return null` 多标签分支（`:90`）加入 `XSYS4` |
| `core/game/storage/EngineDetectionRepository.kt` | 无需改动（`else -> null`） |

### 4.2 扫描识别（`core/game/scan/EngineScanner.kt`）

**新增收集变量**（仅根目录级、大小写不敏感，写在 `rel.isEmpty()` 分支旁；`when` 为首匹配互斥，顺序见下）：
- `hasSystem4Ini`：`system40.ini` 或 `alicestart.ini`（并记录该节点 `system4IniNode`，用于标题）；
- `hasSystem39Marker`：**精确**名 `system39.ini` / `system39.ain`（不用 `hasXs35Support`——它含 `.alk`，而 System 4 同样用 ALK 资源包，libsys4 有 `alk.c`，会让真实 System 4 目录被护栏误杀）；
- `hasAin`：根目录 `*.ain` 且**不是** `system39.ain`。**`hasAin` 只能作为 `hasSystem4Ini` 的附属条件，不得单独成为判据**（System 3.9 目录天然有 `system39.ain`）。

> `when` 顺序约束：新 `.ain` 分支必须排在 `:913`（`isXs35SupportName`）**之后**，或显式排除 `system39.ain`，否则 `system39.ain` 被吞，XSYS35 辅助特征失效，`EngineScannerXs35Test.detectsXs35CaseInsensitive`（96）会退化。

**判定与打分**（`launchTarget = LAUNCH_TARGET_GAME_DIR`）。**实施期修正（独立审核发现原位置会抢判十个既有引擎）**：定义 `isSystem4 = hasSystem4Ini && !hasSystem39Marker`；①XSYS35 的三条规则（96 / 90 / 80）各加 `!isSystem4` 护栏，使其不再吞 System 4 目录；②XSYS4 两条规则放在**所有既有引擎规则（含 ONS）之后**、最终 UNKNOWN 返回之前。这样 ARTEMIS / TYRANO / RPG_MV / RPG_MZ / VN / RPGMAKER / RENPY / WEB_OTHER / KIRIKIRI / ONS 即使目录里混入杂散 `System40.ini` 也仍判原引擎；XSYS35 仅在目录有 `System40.ini` 时行为才变，其余不动。

| 条件 | 分数 |
|---|---|
| `isSystem4 && hasAin` | 97 |
| `isSystem4` | 90 |

理由：System 4 根目录有 `*.ald`，若不给 XSYS35 加护栏就被 `hasAldScript && aldCount>=2`（90）吞掉（G2，已按最小目录与 Rance6 风格目录推演确认）；`!hasSystem39Marker` 防止「System 3.x 目录混入 `AliceStart.ini`」被抢判（`AliceStart.ini` 是否出现于 3.x 发行目录无法证实，保守处理；`GAMEDATA/` 子目录内的精确名 `system39.ini/.ain` 同样置标记）。嵌套目录（`data/System40.ini`）不构成特征。残余风险：3.x 目录有 `AliceStart.ini` 而无任何 `system39.*` 标记时会判为 XSYS4，待真实 3.x 样本验证。

**标题**：`Detection` 增加 `titleHint: String? = null`（已有同类默认字段先例），在 `detectEngine` 中对 `system4IniNode` 读 head（≤64 KB，SAF 走 `SafScanSession`，File 走 `FileScanSession.readHead`）并经 `Xs4Ini` 解析；`ScanGame.title` 的 **4 个构造点**（SAF 增量 `:198`、SAF 全量 `:259`、File 增量 `:583`、File 全量 `:617`）统一改为 `detection.titleHint ?: dir.name`。`Xs4Ini`（纯函数，**放 `core/game/model/Xs4Ini.kt`**，供扫描、启动、存档三处共用，避免 model 反向依赖 scan）：正则 `^\s*(\w+)\s*=\s*"(.*)"`（行首锚定，注释行 `;GameName=…` 不匹配，键名大小写不敏感）；提取 `GameName`、`SaveFolder`。**编码判定（实施期修正，原「含 `U+FFFD` 才回退 GBK」不充分）**：GB2312 常用汉字首字节多落在 `0xB0–0xDF`，在 Shift-JIS 中是合法的单字节半角片假名，如「我的游戏」按 SJIS 解码为 `ﾎﾒｵﾄﾓﾎﾏｷ` 且不含 `U+FFFD`。新规则（`Xs4Ini.pickDecoded`）：①SJIS 结果含 `U+FFFD` 且 GBK 严格解码合法 → GBK；②否则若 SJIS 结果的非 ASCII 字符中半角片假名占比 ≥ 50%，且 GBK 合法并含 CJK 统一表意文字 → GBK；③否则 SJIS。取舍：纯半角片假名的日文标题碰巧是合法 GBK 时会误判，罕见，接受。`SaveFolder` 同规则，合法性仍由 `sanitizeSaveFolder` 把关。`GameRecordCodec` 已持久化 title，无新字段。

**旧库纠正（G12）**：增量扫描对已入库目录剪枝，不会改判已被存为 XSYS35 的 System 4 游戏。**实施期修正（原「整库全量重扫」会重置所有用户改名的标题、手改的 engine，且扫描根不可读时可能删除整根游戏）**：改为**定点重检测**——`AppSettingsStore` 记 `migration_xsys4_rescan_done`；`MainLibraryViewModel` init 的 IO 协程中，若标志未置位则调用 `EngineScanner.redetectXs35Games`：只取库中 `engine == XSYS35` 的条目，重跑 `detectEngine`（File / SAF 各一条路径）；**仅当结果为 XSYS4** 才更新该条目的 engine、launchTarget，以及「标题仍等于目录名」时的 title（取 `titleHint`），其余字段与其它条目一律不动；目录不存在（`Missing`）或根不可读（`Unreadable`）的条目原样保留、不删除。协程包 try/catch（取消重抛）；仅当所有目标都得到确定结果（`Done`）或无 XSYS35 条目时才用同步 `commit()` 置位；存在 `Missing` / `Unreadable` 则不置位、下次启动仅重试仍为 XSYS35 的条目（存储暂不可用与目录真被删除无法区分，故两者都保守地计入待重试，重试成本低）。判定与应用抽成纯函数（`Xs4RescanMigration.targetsOf/applyDetection/canMarkDone`）单测。

### 4.3 启动链路（`EngineLauncher.kt`）

`buildXs4Intent(context, path, game, settings)`（放 `buildXs35Intent` 之后）：

```
PATH / GAME_PATH / PROJECT_ROOT / GAME_DIR = 游戏根（resolveGameDirectory）
ROOT_URI、LAUNCH_TARGET、LAUNCH_MODE = LAUNCH_MODE_XSYS4
XS4_SAVE_DIR        = Xs4Paths 解析的存档目录绝对路径（与 GameSaveManager 同源）
XS4_SCOPED_SAVE_DIR = 生效「独立存档」布尔（默认 true）
XS4_ENCODING        = 生效编码 sjis|gbk
XS4_FONT_GOTHIC_PATH / XS4_FONT_MINCHO_PATH = 仅当设置了自定义字体且文件存在时下发
XS4_JOYPAD          = auto|on|off（auto 不下发）
XS4_MSGSKIP_DELAY   = ms（0 不下发）
ORIENTATION         = 6（契约保留；宿主朝向由 manifest 固定，不消费；本引擎自行注入，buildIntent 尾部只统一注入主题色）
```

- `resolveXs4SaveDir` / `ensureXs4SaveDir`（仿 `:1212` / `:1225`）：`mkdirs`；存在但非目录显式失败；独立存档外部存储不可用 → 类型化失败，**不回退游戏根**；`buildXs4Intent` 内不可达分支同样 `throw IllegalStateException`（由外层收口为 `StartFailed`，不得抛带文案异常）。
- `requestAllFilesAccessIfNeeded` 前置沿用；成功后 `recordRecentGame`。
- **App 侧启动前预检（实施期新增）**：`Xs4Paths.resolveIniFile(游戏根)==null` → `Xs4IniMissing`；`reqGlEsVersion` 明确低于 3.0 → `Xs4Gles3Required`（`null` 或 ≤0 表示系统未报告版本，放行；判定在纯函数 `Xs4Preflight.hasGles3`，与 `Xs4Activity.supportsGles3` 同口径）。原因：宿主在 `super.onCreate` 之前 `finish()` 会触发 `SuperNotCalledException`（`Xs35Activity` 同款存量缺陷，本次不改），可预见的错误必须在拉起宿主之前以类型化失败拦下；宿主内预检仅作防御。文案键 `launch_error_xs4_ini_missing` / `launch_error_xs4_gles3_required`。
- 失败类型：`LaunchResult.Failure.Xs4SaveDirUnavailable(path: String?)` / `Xs4SavePathNotDirectory(path: String)`（`path==null` 表示外部存储不可用，同 `:65-68`）。「逐引擎一对」是第三次复制（Krkr、Xs35、Xs4），这是**有意取舍**：本次不泛化，避免顺带改动既有引擎；留作后续重构项登记于 `docs/应用层代码审查问题清单.md`。映射在 `ui/common/LaunchErrorMessages.kt`（仿 `:75-83`）。

### 4.4 存档（`core/game/save/GameSaveManager.kt`）

新增 `core/game/model/Xs4Paths.kt`（镜像 `Xs35Paths`，纯 `java.io.File`）：

```kotlin
object Xs4Paths {
    fun scopedSaveDir(scopedRoot: File, rootPath: String): File   // <ext>/save/<safeSaveName>
    fun gameDirSaveDir(root: File): File                          // <root>/<sanitizeSaveFolder(Xs4Ini 读到的 SaveFolder)>，内部读 ini，单一实现
    fun sanitizeSaveFolder(raw: String?): String                  // 非绝对、无 ".."、'\'→'/'，否则 "SaveData"
    fun resolveIniFile(root: File): File?                         // 大小写不敏感找 System40.ini → AliceStart.ini，返回实际文件（宿主位置参数用）
}
```

- `resolveSaveLocation`（`:73`，穷尽 when）新增 `XSYS4` 分支（仿 `:171-187`）：同一 `settings.xs4ScopedSaveDir` 与 `Xs4Paths`；外部存储不可用 → `SaveLocation(null, text(R.string.save_error_xsys4_external_unavailable), false)`。`SaveLocation.description` 返回本地化文案是**存量违例**（`GameSaveManager.kt:33-37,174`，与「core 不得返回本地化文案」冲突）：本次沿用该模式、不扩大范围，并在审查问题清单登记；更优做法（给 `SaveLocation` 加错误码）会触及所有引擎分支，不混入本次。
- `cleanupAppData`（`:379`，分支仿 `:429-434`）清理独立存档目录；`listSaveFiles` / `exportToZip` / `importFromZip` 自动复用；无白名单。
- `SaveFolder` 来自**不可信 ini**，必须经 `sanitizeSaveFolder`；`gameDirSaveDir` 在 `scoped=false` 时才读盘，调用方须在 IO 线程（`PerGameSettingsScreen` 已用 `LaunchedEffect` 异步解析，XSYS4 沿用，不得在 composition 内同步读盘）。

### 4.5 引擎设置

| 设置项 | 键（三处字面量锚定） | 取值 | 默认 |
|---|---|---|---|
| 独立存档 | `xsys4_scoped_save_dir` | bool | **true** |
| 文本编码 | `xsys4_encoding` | `sjis` / `gbk` | `sjis` |
| 自定义 gothic 字体 | `xsys4_font_gothic_path` | `filesDir/fonts/…` 绝对路径 | 空（用内置映射） |
| 自定义 mincho 字体 | `xsys4_font_mincho_path` | 同上 | 空 |
| 手柄 | `xsys4_joypad` | `auto` / `on` / `off` | `auto`（不传） |
| 跳字延迟 | `xsys4_msgskip_delay` | 预设 0（引擎默认）/ 100 / 200 / 400 ms | 0 |

渲染后端 / 整数缩放 / 失焦静音**不提供**（xsystem4 无对应选项）。

**改动点（逐项，来自核验的完整触点）**：
- `EngineSettingsStore`：键常量、`XSYS4_ENCODING_VALUES`（仿 `:303-309`）、get/set/normalize；
- `PerGameSettingsStore`：`F_XSYS4_*`；`GameOverridePartitions`：键常量并加入 `TYRANO_KEYS`（仿 `:84-88,127-128`，避免 Room 迁移）；**三处字面量必须一致**，补锚定测试（仿 `GameOverridePartitionsTest.kt:218-244`）；
- `EngineSettingsResolver`：`resolve()` 构造（`:204-221`）与 `ResolvedEngineSettings` 字段（`:299-306`，仅一处构造点 `:28`）；**`EffectiveEngineSettings` 无需改动**（`resolveAllowed` / `resolveBool` 为通用函数），仅扩展 `EffectiveEngineSettingsTest`；
- `ui/settings/EngineSettingsText.kt`：`xsys4EncodingOptions()` **和 `xsys4EncodingOptionsMap()`**（单游戏页用）、手柄 / 跳字预设选项及其 Map；
- `EngineSettingsMenuActivity`：枚举新增 `XSYS4(titleRes, iconRes)`（菜单 `entries.forEach` 自动生成）；
- `SettingsScreen`：状态 `:760-763`、`saveAll` `:848-851`、调用处回调 `:932-935`、函数签名 `:1018-1021`、卡片 `:1301` **内联** `if (kind == XSYS4) item { EngineCard }`（现有各引擎均内联，不另起 `Xs4SettingsCard`）；
- `PerGameSettingsScreen`：状态 `:118-121`、全局值 `:323-326`、Map `:360`、`save()` `:430-438`、`LaunchedEffect` 路径解析 `:223-228`、保存后刷新 `:530-536`、`when` 分支 `:838-874`；建议把 `xs35SaveLocation` 泛化为通用 `saveLocation` 以免复制；
- 自定义字体导入入口复用 `FontImport.importToPrivate`（`ui/settings/FontSettings.kt`，UI 层；core 只存路径字符串，不 import UI）。

**UI 规范（AGENTS.md）**：控件用 Miuix `OverlayDropdownPreference` / `SwitchPreference`（单游戏页 `OverrideSwitch` / `OverrideChoice`）；所有说明文字 `bodyMedium`，不得用 `bodySmall`；无弹窗。内容划分（以实现为准）：全局卡 = 独立存档开关 + 编码下拉 + 自定义字体两项 + `font_hint` + 手柄 + 跳字 + `note` + `compat_hint`（兼容边界单列，`note` 本身不含）；单游戏卡 = 对应 `Override*` + `note` + `uninstall_hint` + `compat_hint` + 生效存档路径。

### 4.6 文案（三语言严格一致；不可用 `.java` 逃检）

`tools/check-hardcoded-ui-strings.py` 只校验 app / engine 各自三语言键集合齐平，且**只扫 `.kt`、不扫 `.java`**；因此 `Xs4Activity.java` / `Xs4ManualActivity.java` 的所有文案必须走 `uiString(R.string.*)`（`EngineUiText.get`），靠审核保证；`Xs4Ini.kt` 不得含 CJK 字面量。完整键表见附录 C。复用已有键：`engine_settings_scoped_save_dir`、`save_location_engine_scoped`、`save_location_engine_game_dir`；engine 侧 `engine_another_game_running`、`engine_ok`、`engine_cancel`。

### 4.7 引擎页 / 其它接线

- `MainLibraryViewModel`：仅新增 §4.2 的一次性重扫；**不**接标题回写（`Xs35TitleFeedback` 调用点不动）；
- `EnginePrefs` 不加键；`EngineSettingsMenuActivity.engineSettingsKindTitle` 无需改（`titleRes` 驱动）。

### 4.8 exe 图标封面兜底（`core/cover/`）

- 新增 `core/cover/Xs4IconCover.kt`（**自行重写**，不拷贝壳的 `PEResourceExtractor`）：游戏根下优先取 `*.ico`；否则遍历 `*.exe`（排除 `OpenSaveFolder.exe`、`ResetConfig.exe`、`Uninstaller.exe`）解析 PE `.rsrc`（RT_GROUP_ICON / RT_ICON）重建 ICO，选最接近 256px 的图像，经 `BitmapFactory` 解码后**压成 PNG 写入持久封面目录**（`filesDir/covers_remote/xs4_<游戏 key>_<指纹>.png`，与在线封面共用，替换/删除游戏时由 `CoverImageCache.deleteCachedCover` 回收；不放 `cacheDir`，系统清缓存后 URI 会悬空），返回 file URI。限制：exe 读入大小上限（如 64 MB）、资源偏移越界检查、异常全部吞掉返回 null。
- 接入点：`CoverScraperService.scrapeLibraryCovers`，**在全部在线来源失败之后**才对 `engine==XSYS4 && coverUri 为空` 兜底（图标不抢占在线刮削）；`coverSource=COVER_SOURCE_LOCAL`；**不写游戏目录**。图标产物被识别为「非封面」（`Xs4IconCover.isIconCoverUri`），只补缺失时仍会先尝试在线来源并可被替换。
- 测试：PE 解析用合成最小 PE 样本（单元测试），含越界 / 无 `.rsrc` / 非 PE 负例。

### 4.9 文档与规范性产出

- `README.md`：引擎支持表新增 System4 行；许可证 / 致谢补 xsystem4、libsys4、libsndfile fork 及第三方例外（§5.5）；
- `CONTEXT.md`（遵守「只收项目特有术语、定义一至两句」）：
  - L8 类型列表 `…AVG32、XSYS35、XSYS4、UK2、FVP…`；L12「内置引擎」清单补 `System4（xsystem4，与 System35 共用 libSDL2x）`；L108 `EngineSettingsKind` 列表补 `XSYS4`；
  - L60 标题回写条追加「System4 不使用该协议，标题在扫描期解析 ini 的 GameName」；
  - 新增条目：「**System3.x / System4（XSYS35 / XSYS4）**：AliceSoft 两代引擎，各为独立 EngineType；扫描时 System4（根目录 `System40.ini` 或 `AliceStart.ini`）规则先于 System3.x，因两者根目录都可含 `*.ald`。_Avoid_：System3/4 合并类型」；
  - 新增条目：「**SDL2 共享运行库（libSDL2x / app32）**：System35 与 System4 共用的 SDL2 2.32.10 原生库与重命名 Java 层 `org.libsdl.app32`，与 ONS 的 `libSDL2.so` / `org.libsdl.app`（2.26.3）并存互不冲突。_Avoid_：SDL 补丁版、第二套 SDL」。
- **ADR**（三门槛核验：难逆转 / 脱离上下文费解 / 真实取舍），`docs/adr/` 目录按需新建：
  - `0001-sdl2-shared-libsdl2x-app32.md`：System35 / System4 共用重命名的 SDL2 2.32.10（libSDL2x + org.libsdl.app32），与 ONS 的 2.26.3 并存；
  - `0002-prebuilt-engine-libs-source-in-fork.md`：内置原生引擎库以预构建 `.so` 入库，源码与修改声明放 fork，依赖放仓库外构建（覆盖 FVP / System35 / System4 的合规与可复现策略）。
  - 不建 ADR：独立 EngineType、字体复用、不注入 rc、扫描优先级（均易逆转或显而易见，代码注释 + 单测已覆盖）。

---

## 5. engine 侧改动清单

### 5.1 Java 宿主

**`com.core.xsys4.Xs4Activity`**（对照 `Xs35Activity` 527 行：保留骨架，去掉 MIDI、输入弹窗、标题回写；双向互指注释照搬，见下「预检口径」）：

| 行为 | 做法 |
|---|---|
| 基类 | `extends org.libsdl.app32.SDLActivity` |
| `onCreate` 预检 | ① 游戏根取 `PATH → GAME_PATH → PROJECT_ROOT → GAME_DIR` 首个非空且**必须绝对路径**（否则 Toast `engine_xsys4_missing_game_dir` + finish）；② 解析**实际 ini 文件**（大小写不敏感：`System40.ini` → `AliceStart.ini`），缺失 Toast `engine_xsys4_init_failed` + finish；③ `ActivityManager.deviceConfigurationInfo.reqGlEsVersion < 0x30000` → Toast `engine_xsys4_gles3_required` + finish；④ 解析字体（见下）；之后 `super.onCreate` → `sdlReady=true` → `SDLActivity.setWindowStyle(true)`（SDL#8995 workaround） |
| `getLibraries()` | `["SDL2x", "xsystem4"]` |
| `getArguments()` | `--save-folder <XS4_SAVE_DIR>`（须绝对路径，否则忽略并记日志；**不传将退回 `home` 推导，App 必须下发**）、`--save-format=rsm`、`--encoding <sjis|gbk>`（白名单）、`--font-gothic/--font-mincho`、`--joypad=<on|off>`（非 auto）、`--msgskip-delay <ms>`（>0）；**最后一个位置参数 = 实际 ini 绝对路径**（引擎对 `.ini` 位置参数走 `config_init_with_ini` 并取其目录为游戏目录，解决 ini 名大小写不一致） |
| 字体解析 | `Xs4Fonts.resolve(encoding, customGothic, customMincho)`：自定义路径通过「绝对 + 存在 + 可读 + 扩展名白名单（ttf/ttc/otf/otc）」才采用，否则回落默认映射（§3.5）；任何回落记日志。`--font-*` 加载失败是引擎致命错误（G15），故宿主必须保证传下去的路径可用 |
| 返回键 | 同 `Xs35Activity.onBackPressed`：`sdlReady` 守卫；trap-back hint 命中交 SDL；否则 `DoubleBackExit` |
| 朝向 | 覆写 `setOrientationBis` 恒请求 `sensorLandscape` |
| 退出 | 引擎正常退出与致命错误都是 `exit()` 整进程（G13），宿主不依赖 `onDestroy`；`onDestroy` 仍保留 `try { super.onDestroy() } finally { killProcess }` 作兜底 |
| 多开 | `singleInstance` + `onNewIntent` Toast `engine_another_game_running` |
| 消息框 | 覆写 `messageboxCreateAndShow` 走 `KrDialogStyle`（同 `Xs35Activity:236`）；引擎致命错误对话框由此呈现，是唯一可见的错误出口 |
| 自定义消息 | 覆写 `onUnhandledMessage(int command, Object param)`：`command==0x8000` → 启动 `Xs4ManualActivity`（仅当 `<游戏根>/Manual/index.html` 存在，大小写不敏感解析），返回 true；其余交 `super` |
| 文案 | 全部 `uiString(R.string.*)`（`EngineUiText.get`）；`.java` 不被检查脚本扫描，靠审核保证 |

预检口径与 App 侧（`Xs4Paths.resolveIniFile`）**双向互指**（同 `Xs35Activity.containsScenarioScript` / `Xs35Paths.hasScenarioAld`），修改须同步。

**`com.core.xsys4.Xs4ManualActivity`**（自研；壳的 `ManualActivity` 无许可证不拷贝）：WebView 展示游戏 `Manual/`。安全约束：非导出；`settings.javaScriptEnabled=false`；`allowFileAccess=true` 但 `allowFileAccessFromFileURLs=false`、`allowUniversalAccessFromFileURLs=false`、`allowContentAccess=false`；`shouldOverrideUrlLoading` 与 `shouldInterceptRequest` 只放行规范化后位于 `Manual/` 目录内的 `file://`，其余（含 http/https、其它 file）一律拦截；入参 URL 由宿主构造，不接受外部 Intent 传入任意 URL。返回键 = finish。

### 5.2 Manifest / 资源

```xml
<!-- System4（xsystem4）宿主：SDL2 主循环（共用 org.libsdl.app32 2.32.10） -->
<activity
    android:name="com.core.xsys4.Xs4Activity"
    android:exported="false"
    android:process=":xsys4"
    android:launchMode="singleInstance"
    android:taskAffinity="com.core.Xsys4"
    android:screenOrientation="sensorLandscape"
    android:theme="@style/Theme.Xsys4"
    android:windowSoftInputMode="adjustPan"
    android:configChanges="keyboard|keyboardHidden|navigation|orientation|screenLayout|screenSize|smallestScreenSize|uiMode|density|fontScale|locale|layoutDirection" />
<activity
    android:name="com.core.xsys4.Xs4ManualActivity"
    android:exported="false"
    android:process=":xsys4"
    android:taskAffinity="com.core.Xsys4"
    android:theme="@style/Theme.Xsys4" />
```

> 手册页的 `taskAffinity` 与宿主一致，且 `Xs4Activity` 启动它时加 `FLAG_ACTIVITY_NEW_TASK`（实施期修正）：宿主是 `singleInstance`，由它启动的 Activity 必落入新任务，不设 affinity 时手册会并入主 App 任务，返回键回到游戏库而非游戏。返回行为仍需真机验证（§7.2-9）。

- `styles.xml` 新增 `Theme.Xsys4`（同 `Theme.Xsys35`）；`consumer-rules.pro` 新增 `-keep class com.core.xsys4.** { *; }`（`org.libsdl.app32.**` 已由 System35 保留）；
- **不声明** `uses-feature glEsVersion`（§3.6-7）；`VIBRATE` 已有；
- `shaders/` 53 个文件进 `engine/src/main/assets/shaders/`（保留层级；`BUILD.md` 记 SHA-256 清单；构建门禁校验文件数）。

### 5.3 契约与常量（`engine/.../LaunchContract.kt`）

```kotlin
// ---------- System4 (xsystem4) ----------
const val XS4_SAVE_DIR = "xs4_save_dir"                  // 生效存档目录绝对路径（App 解析，宿主不推导）
const val XS4_SCOPED_SAVE_DIR = "xs4_scoped_save_dir"    // 独立存档布尔（诊断用）
const val XS4_ENCODING = "xs4_encoding"                  // sjis | gbk
const val XS4_FONT_GOTHIC_PATH = "xs4_font_gothic_path"  // 仅用户自定义字体绝对路径
const val XS4_FONT_MINCHO_PATH = "xs4_font_mincho_path"
const val XS4_JOYPAD = "xs4_joypad"                      // on | off（auto 不下发）
const val XS4_MSGSKIP_DELAY = "xs4_msgskip_delay"        // int ms（0 不下发）

const val LAUNCH_MODE_XSYS4 = "internal.xsys4"
```

无 `EnginePrefs` 标题回写键。设置键（`xsys4_font_*_path`）与 extras（`xs4_font_*_path`）语义一致：均为自定义字体路径；内置映射只在 engine 侧 `Xs4Fonts`。

### 5.4 引擎库预构建（一次性 / 每次更新上游）

仿 `engine/xsystem35/scripts/build-xsys35.sh`，**依赖与源码不入主仓**，放仓库外构建目录（默认 `${TMPDIR:-/tmp}/xsys4-build`）。

0. **前置：fork**（G11）：
   - ✅ 已完成：`Weiss-UltimateSavior/xsystem4`（`master` = `6a94a0c5637ce640e787b505ffb2bd21e881f970`，已核对）；壳 fork `xsystem4-android` 无需改动；
   - ✅ 已完成并核对：`Weiss-UltimateSavior/libsys4`（`e2bf438d0156a2ca70fc6018fddf7c870794a2ca` "Add GBK character encoding support" 在 `master`）、`Weiss-UltimateSavior/libsndfile`（`xsystem4` 分支已带入，tip = `be82f4be73c77f1e759bda9dfe1625204441465a` "cmake: Allow building with Ogg/Vorbis only"；该 commit **不在默认分支** `master`，clone 时必须取 `xsystem4` 分支）。
   - fork 不承载修改，保证 GPL / LGPL 源码长期可得（第三方删库不影响合规）。xsystem4 fork 的 `.gitmodules` 仍指向 `nunuhara/libsys4`，脚本**直接 clone 我们自己的 libsys4 fork 到 `subprojects/libsys4` 并检出固定 commit**，不依赖 `git submodule update`，也不改 `.gitmodules`。
1. **前置检查**：NDK `28.0.13004108`、SDK 自带 CMake 3.31.1 + Ninja（不用 homebrew cmake 4.x）、`flex`、**bison ≥ 3**（macOS 自带 2.3 解析不了 `ini_parser.y` 的 `%define api.prefix`——已实测；脚本自动定位 `brew --prefix bison`（本机 `/opt/homebrew/opt/bison/bin/bison` 3.8.2）并**显式传 `-DBISON_EXECUTABLE` / `-DFLEX_EXECUTABLE`**，因 libsys4 对 `find_package(BISON)` 不带 REQUIRED、会静默选中旧版）、`git`；`libSDL2x.so` 已入库且 SHA-256 为 `engine/xsystem35/BUILD.md:251` 记录的入库产物值 `cee53fbc5fd41a895658ce98401cd6aa033ad6039b426d4401f9ac1b3caba0c1`（`:315` 是新流程复现值，不是入库值）。
2. **依赖下载**：§2.2 全表（SHA1 / SHA256 / commit 校验，失败即退出；`.tar.xz` 用 `tar -xf`）；libpng 的 sourceforge 源建议加备用镜像。
3. **staging 构建**（全部装入同一前缀并设 `CMAKE_STAGING_PREFIX` / `CMAKE_FIND_ROOT_PATH`，libsys4 靠各依赖的 CMake config 包定位：libjpeg-turbo、WebP、PNG、libdeflate 必须用 CMake 构建，且保留 `libjpeg-turbo::turbojpeg-static` 目标）；公共参数 `-DCMAKE_BUILD_TYPE=Release -DCMAKE_INTERPROCEDURAL_OPTIMIZATION=ON -DCMAKE_POLICY_VERSION_MINIMUM=3.5 -DCMAKE_POSITION_INDEPENDENT_CODE=ON`；
   - **libffi**（核验已实测在本机交叉编译通过）：`CC="<ndk>/bin/clang --target=aarch64-linux-android26" AS=$CC AR=llvm-ar RANLIB=llvm-ranlib STRIP=llvm-strip LD=ld.lld ./configure --host=aarch64-linux-android26 --prefix=<staging> --disable-shared --disable-dependency-tracking --disable-builddir --disable-multi-os-directory --disable-structs --disable-raw-api --disable-docs`（漏 `--disable-multi-os-directory` 会让 `libffi.a` 装到 CMake 找不到的目录）；release 包自带 `configure`，无需 autoreconf；
   - libsndfile fork：`-DBUILD_SHARED_LIBS=OFF -DBUILD_PROGRAMS=OFF -DBUILD_EXAMPLES=OFF -DBUILD_TESTING=OFF -DENABLE_CPACK=OFF`；libpng：`-DPNG_SHARED=OFF -DPNG_ARM_NEON=on`；libwebp：关闭全部工具；libdeflate：`-DLIBDEFLATE_BUILD_SHARED_LIB=OFF -DLIBDEFLATE_GZIP_SUPPORT=OFF -DLIBDEFLATE_BUILD_GZIP=OFF`；构建后确认 libsys4 的 `HAVE_LIBDEFLATE` 命中（未找到会静默回落 ZLIB）。
4. **xsystem4 本体**：fork 检出目录作为**顶层 `-S`**（**不使用 wrapper**：上游 `execute_process(git rev-parse --short HEAD WORKING_DIRECTORY ${CMAKE_SOURCE_DIR})` 在 wrapper 下会取到 TyranorNext 提交号写进 `version.h`，每次主仓提交都改变二进制，破坏可复现）；参数 `-DCMAKE_MODULE_PATH=<engine/xsystem4/android-host/cmake>`（自研 `FindSDL2` / `FindFreetype`）、`-DANDROID_PLATFORM=android-26`、`-DFETCHCONTENT_SOURCE_DIR_CGLM=<本地 cglm>`、`-DCGLM_SHARED=OFF -DCGLM_STATIC=ON`（两项都传）、`CMAKE_STAGING_PREFIX`（上游 `:16-19` 硬编码 `${CMAKE_STAGING_PREFIX}/lib/libffi.a`）。
5. **strip + 门禁**：`llvm-strip --strip-unneeded`；构建树出现除 `libxsystem4.so` 外的 `.so`（如 `libcglm.so`）即报错。
6. **产物核对（回填 BUILD.md）**：
   - `llvm-readelf -d`：`NEEDED` 应为 **{`libm`, `libz`, `libSDL2x`, `libGLESv3`, `libdl`, `liblog`, `libc`}**（壳产物同形，`libz` 为系统 zlib），**不含** `libSDL2.so` / `libcglm.so`；
   - `llvm-nm -D | grep -w SDL_main` → `T SDL_main`；
   - `llvm-readelf -l`：`LOAD p_align=0x4000`（仓库**没有**通用逐 `.so` 的 16 KB 检查，仅 APK 级 `zipalign -c -P 16`（`android-beta-release.yml:192`）与 Ethornell 专用 `tools/ethornell/verify_native.py`，故此项为本方案自带的显式命令；打包后另跑 `zipalign -c -P 16 -v 4 <apk>`）；
   - 记录体积与 SHA-256；两次 `--clean` 构建比对可复现。**实测结论（已落地）**：产物 SHA-256 `56a4063eedc9cbd3f72d1bc06e937e0239ecdb44fb61bc68348ee791cca7c6eb`（2026-10-18 重建），两次不同构建目录逐字节一致（libjpeg-turbo 的 `BUILD` 已固定，消除隔日日期漂移）；达成可复现需加 `-Wl,--build-id=none`（lld 对未 strip 输出做哈希，`STT_FILE` 含绝对路径；代价：产物无 build-id，Android 运行不依赖）。详见 `engine/xsystem4/BUILD.md`。
7. **反向声明**：在 `engine/xsystem35/BUILD.md`、`build-xsys35.sh` 头部注明「`libSDL2x.so` 同时被 System4 依赖，升级 SDL2 / 改动导出符号必须回归 System4」。

### 5.5 许可与署名

| 组件 | 许可 | 备注 |
|---|---|---|
| xsystem4 / libsys4 | **GPL-2.0-or-later**（绝大多数源文件头已核对） | 本项目 GPL-2.0-only，兼容；预构建 `.so` 为其衍生分发，完整源码见 fork |
| xsystem4 内第三方例外 | `cJSON.[ch]`（MIT）、`pl_mpeg.h`（MIT）、`queue.h`（BSD）、`sts_mixer.h`、`base64.c`（BSD）、`dungeon/mtrand43.c`、`debug-ffi.c`（自动生成） | 许可文本须随 `engine/xsystem4/licenses/` 分发（`pl_mpeg` 为静链代码，MIT 声明必须随附） |
| libsys4 内第三方例外 | `kvec.h` / `vector.h`（MIT）、`mt19937int.c`、`s2utbl.h`、`ini_parser.y`（无头） | 同上 |
| libsndfile（kichikuou fork） | LGPL-2.1-or-later | 静态链入 GPL 作品，按 GPL 分发；fork 公开可得 |
| SDL2 / freetype | zlib / FTL-or-GPLv2 | 同 System35 |
| libogg / libvorbis / libjpeg-turbo / libwebp / libpng / libffi / libdeflate / cglm | BSD-3 / IJG / libpng / MIT | 随 `licenses/` 保留 |
| 字体 | 无新增 | 沿用现有三款字体既有许可记录 |

- **壳仓库（`kichikuou/xsystem4-android` 及本账号 fork）无 LICENSE**：仓库里没有任何 LICENSE / COPYING 文件，GitHub 许可探测返回 `NOASSERTION` 只是误把 `collect-licenses.sh` 当成了许可文件（已用 `gh api .../license` 与目录树核对）。**fork 不赋予任何授权**，默认保留全部权利。因此其 Kotlin 宿主、CMake 超级构建、`cmake/Find*.cmake`、`PEResourceExtractor`、`ManualActivity`、`GameList` 等**一律不拷贝**，全部自行重写；仅把依赖版本与参数当事实参考。评审时核对未引入其文件。
- 可选动作（不阻塞交付）：向 `kichikuou` 提 issue 请求为壳仓库补 LICENSE（作者同时维护已按 GPL-2.0-or-later 授权的 `xsystem35-sdl2`，补授权的可能性高）；若获得授权，日后才可复用其代码。
- README「许可证」「致谢」补 xsystem4 / libsys4 / libsndfile fork 条目（仿 System35 条目）。

---

## 6. 关键交互细节（待真机验证项标记 ⚠）

### 6.1 启动与退出

- 预检在 `onCreate`（§5.1）：路径非绝对、ini 缺失、GLES < 3.0 都在进入 SDL 前失败。
- 引擎致命错误（`ERROR` / `usage_error` / 字体加载失败 / **损坏的 ini**——`ini_lexer.l` 对未闭合字符串直接 `ERROR`）：先 `SDL_ShowSimpleMessageBox`（经 `messageboxCreateAndShow` 覆写呈现），再 `exit()` 整进程；`usage()` 的 `puts` 在 Android 不可见。⚠ 验证：错误对话框可见、进程干净退出、不留僵尸任务。
- 游戏正常退出同样 `exit()`，宿主靠 singleInstance 任务随进程结束收尾。

### 6.2 输入

- 触屏手势、手柄由引擎内处理；宿主不拦截（返回键除外）。「点黑边 = 右键」依赖信箱化留边与 SDL 触摸转鼠标事件，宽高比接近屏幕时可能无黑边 ⚠ 真机确认。
- 上游 `input.c:723-733` 的 `case SDL_MULTIGESTURE:` 末尾缺 `break`，会落入 `SDL_CONTROLLERDEVICEADDED` 分支（上游已有 bug，固定 commit 与 master 一致，低风险）⚠ 验收双指滚轮时留意。
- 软键盘：引擎对 Android 的 `SDL_TEXTINPUT` / 按键做延迟投递（`DEFERRED_KEY_DELAY`），宿主不另处理。

### 6.3 音频与视频

- 音频：libsndfile（ogg / vorbis / wav）→ SDL 音频；⚠ 无爆音、后台 / 回前台恢复。
- 视频：Android 只编 `movie_plmpeg.c`（MPEG-1）；打不开时 `movie_load` 返回 NULL 并 WARNING，**非致命**。⚠ 真机确认 OP/ED 是否播放及不能播时的表现。

### 6.4 字体

按 §3.5。⚠ 必检：基线偏移、半角宽度、描边；自定义字体导入后启动正常、失败回落生效。

### 6.5 存档

- 独立存档开：`<ext>/save/<safeSaveName>`；关闭：`<游戏根>/<SaveFolder|SaveData>`；均绝对路径下发，引擎 `mkdir_p`，App 侧 `ensureXs4SaveDir` 预创建以提前类型化失败。
- `--save-format=rsm`：⚠ 与 Windows 原版 `SaveData/*.asd` 互通性未验证，**不承诺**；文案不用「互通」字样（`CONTEXT.md` 里「存档互通」是 RPG Maker 专有术语）。
- 镜像导出 / 导入按目录整体处理。

### 6.6 设置注入

| 设置 | 落地方式 |
|---|---|
| 独立存档 | `--save-folder <绝对路径>` |
| 文本编码 | `--encoding sjis|gbk`（**需 `6a94a0c` 基线**） |
| 字体 | `--font-gothic` / `--font-mincho` |
| 手柄 | `--joypad=on|off` |
| 跳字延迟 | `--msgskip-delay <ms>` |

### 6.7 窗口与朝向

横屏固定（manifest + `setOrientationBis`）；窗口、GLES3 上下文、缩放由引擎与 SDL 处理。

---

## 7. 测试与验收

### 7.1 单元测试

| 用例 | 内容 |
|---|---|
| `EngineScannerXs4Test`（新增，参照 `EngineScannerXs35Test`） | `System40.ini`+`*.ain` → XSYS4(97)；`AliceStart.ini`+`*.ain` → 97；仅 ini → 90；大小写变体；嵌套 `data/System40.ini` 不构成特征；**含根目录 `*.ald`/`*.alk` 的 System 4 目录不被 XSYS35 抢判（G2 核心回归）**；**`System39.ini + AliceStart.ini + SA.ALD` 仍判 XSYS35**（护栏）；`system39.ain` 不被计作 `hasAin`；Siglus / RealLive / AVG32 / UK2 / FVP / YURIS / CS2 + 杂散 `System40.ini` 不被抢判；`titleHint` 取 `GameName`（Shift-JIS、GBK 回退、缺失回退目录名） |
| `EngineScannerXs35Test` / `EngineScannerEthornellTest`（回归） | 既有 XSYS35 样例判定不变（尤其 `detectsXs35CaseInsensitive` 的 96） |
| `Xs4IniTest` | `GameName` / `SaveFolder`；Shift-JIS；GBK 回退；BOM；引号缺失；缺字段 |
| `Xs4PathsTest` | `scopedSaveDir`；`gameDirSaveDir`；`sanitizeSaveFolder`（绝对、`../x`、`a\b`、空串、纯空白）；`resolveIniFile` 大小写 |
| `Xs4FontsTest`（engine 侧纯函数，JVM 单测） | `sjis` / `gbk` 默认映射；自定义路径不存在 / 非绝对 / 扩展名非法 → 回落默认 |
| `Xs4IconCoverTest` | 合成最小 PE（含 `.rsrc` 图标）→ 可解码；无 `.rsrc` / 非 PE / 偏移越界 → null；`.ico` 直读 |
| `EffectiveEngineSettingsTest`（扩展） | 编码白名单回落、独立存档布尔覆盖合并、手柄 / 跳字预设回落 |
| `GameOverridePartitionsTest`（扩展） | XSYS4 键在 tyrano 分区往返不丢 + 字面量锚定（仿 `:218-244`） |
| 一次性定点迁移测试 | 被误存为 XSYS35 的 System 4 条目改判为 XSYS4；标题仅在等于目录名时更新、用户改名保留；真正 XSYS35 条目不变；根不可读 / 目录缺失条目原样保留；非 XSYS35 条目不动；其它字段不变 |
| 杂散 `System40.ini` 回归（十引擎） | ARTEMIS / TYRANO / RPG_MV / RPG_MZ / VN / RPGMAKER / RENPY / WEB_OTHER / KIRIKIRI / ONS 各一例，仍判原引擎；Rance6 风格目录判 XSYS4(97)；`GAMEDATA/System39.ini + 根 AliceStart.ini` 判 XSYS35 |
| `Xs4PreflightTest` | ini 缺失 / GLES<3.0 → 类型化失败；`reqGlEsVersion` 为 null 或 ≤0 放行 |
| `EngineTypeAbbrTest`（扩展） | `S4X` |

**门禁清单（每次提交前）**：`./gradlew assembleDebug --no-daemon`、`./gradlew testDebugUnitTest --no-daemon`、`python3 tools/check-hardcoded-ui-strings.py`、`bash engine/xsystem35/scripts/sync-app32-java.sh --check`、APK 内字体仍为 Stored、`zipalign -c -P 16 -v 4 <apk>`、`llvm-readelf -d/-l` 核对 `libxsystem4.so`（§5.4-6）。设备安装用 Android CLI（`--sdk=/tmp/androidsdk`）。**发版**：只改 `app/build.gradle.kts` 的 `appVersionName`，`versionCode` 由 `versionCodeOf()` 推导，禁手写。

**审核（AGENTS.md「测试与审核」）**：必须让 Agent 先阅读 Android CLI 的审查技能；由多个相互独立的 Agent 从逻辑 / 架构 / 健壮性 / 回归风险 / 代码规范五个角度重新检查。**审核在真机验收之前完成一轮**（发现问题后无需重跑整套真机验收），真机验收后再做终审；回归角度必须覆盖「ONS / System35 / System4 共享 SDL」与「扫描优先级对既有引擎样例的影响」。

### 7.2 真机验收（建议 Rance VI、戦国ランス；GBK 中文版 ≥1 款；一款 System35 与一款 ONS 游戏做共存）

1. 扫描识别为 System4（含根目录 `*.ald` / `*.alk` 的目录不再被识别为 System35）；标题显示为 ini `GameName`；
2. **升级迁移**：用旧版本库（System 4 游戏被存为 XSYS35）升级，首次启动自动重扫后条目变为 XSYS4，且仅触发一次；
3. 启动进入标题 → 触摸推进（轻点、点黑边右键、长按 Ctrl、双指滚轮；留意 `SDL_MULTIGESTURE` 缺 `break` 的副作用）→ 存档 → 读档；
4. 独立存档开 / 关两条路径分别落盘、杀进程重进可读；两种位置导出 zip → 清档 → 导入 → 读档一致；
5. **字体必检**：日文版无缺字；基线 / 半角 / 描边与上游字体截图对照可接受——**不可接受则在本次交付内切方案 C（补 VL-Gothic 并改默认映射）**；
6. 自定义字体：导入 HanaMinA 等 → 生效；删除文件或路径失效 → 回落默认并启动成功；
7. GBK 版：编码切 `gbk` 后中文显示正确；日文版切错编码的表现可接受；
8. 手柄 / 跳字延迟设置生效；
9. 手册页：有 `Manual/index.html` 的游戏，游戏内触发手册 → 打开；非 `Manual/` 内链接与外链被拦截；无 `Manual/` 时无反应不崩溃；
10. exe 图标封面：仅含 exe（无 cover 图）的游戏出现图标封面；用户已有封面不被覆盖；游戏目录内未新增任何文件；
11. 音频正常、后台 / 回前台恢复；视频：确认 OP/ED 是否播放及不能播时的表现；
12. 返回键单击 / 双击行为；引擎致命错误（构造损坏 ini、移除字体）时对话框可见、进程干净退出；
13. **SDL 共享回归**：先 System35 → 退出 → System4；先 ONS → 退出 → System4；反向同理；`unzip -l` 确认 APK 内只有一份 `libSDL2x.so`；
14. **版本门禁**：不得出现「SDL C/Java version mismatch」；SDL 2.30.9→2.32.10 下 xsystem4 输入 / 音频 / GL 无回归；
15. 大小写：Windows 原样拷贝（文件名大小写与 ini 记载不一致，含 ain）仍能启动；宿主预检传 ini 实际路径生效；
16. 权限：未授权 all-files 时给出提示而非崩溃；
17. 不支持作品（如 Rance X）：失败表现可接受，信息卡兼容提示可见；
18. 16 KB：`libxsystem4.so` `p_align=0x4000`，整包 `zipalign -P 16` 通过。

### 7.3 回滚

- 移除 `EngineType.XSYS4` 相关分支 + 删除 `libxsystem4.so` / `assets/shaders/` 即回到接入前；`libSDL2x.so`、`org.libsdl.app32` 是 System35 资产，回滚不动。
- **降级兼容**：`GameEntity.kt:46`、`GameRecordCodec.kt:41` 对未知引擎名 `getOrDefault(UNKNOWN)`——降级到不含 XSYS4 的旧版后，已入库的 XSYS4 条目会读成 `UNKNOWN`（重新写库即丢失类型），并非「回到接入前」。
- 设置键滞留无害；独立存档目录留在用户外部存储。

---

## 8. 风险与待确认

| 风险 | 影响 | 缓解 |
|---|---|---|
| 扫描误判（G2） | System 4 被识别为 System35 | XSYS4 规则前置 + 护栏 + 回归测试；一次性重扫纠正旧库 |
| 固定 commit 选错 | `04333ad` 无 `--encoding`，传参会 `Too many arguments` 退出 | 已改为 `6a94a0c`/`e2bf438`；新基线比壳 v1.0.7 新，兼容性需 §7.2 回归 |
| 复用字体（方案 B）观感 / 度量不佳 | 基线偏移、风格与原版差异 | 目检；不可接受则本次交付内切方案 C；自定义字体导入兜底 |
| 字体加载失败 = 致命（G15） | 进程退出 | 宿主传参前校验 + 回落默认；构建门禁确认内置 asset 存在 |
| SDL 2.30.9→2.32.10 | xsystem4 新 SDL 下行为差异 | 同大版本小步升级；符号覆盖已核验；§7.2-13/14 门禁 |
| 构建链比 System35 重 | 工期与可复现性 | 全部校验 + 仓库外构建 + 产物门禁；bison/flex/VCS_TAG 坑已前置处理 |
| 依赖 fork 的可得性（G11） | 第三方删库会让 GPL / LGPL 源码不可得 | 四个 fork 已齐备（xsystem4 / libsys4 / libsndfile / 壳）；脚本只从我们自己的 fork 取源码 |
| 视频仅 MPEG-1 | 部分影像不播 | 已知限制；信息卡说明；真机确认 |
| `rsm` 存档与 Windows 原版不互通 | 用户预期 | 文案不承诺；位置在信息卡显示 |
| 引擎遇错 `exit()` 整进程（G13） | 无 `onDestroy` | 预检前置 + 消息框覆写 + 兜底 killProcess |
| 手册 WebView | 本地文件访问面 | JS 关闭、文件访问收紧、URL 白名单（§5.1） |
| 图标解析（不可信 exe） | 越界 / 超大文件 | 大小上限、偏移检查、异常吞掉、单测负例 |
| 未支持作品被识别（G8） | 用户误以为全系可玩 | 信息卡兼容边界；`CONTEXT.md`「类型已接入不等于兼容性已验收」 |
| 上游无发行号、快速演进 | 重编译回归 | 固定 commit 记于 `BUILD.md`；跟随上游时重跑 §7 |
| 壳仓库（含本账号 fork）无 LICENSE | 误拷贝版权风险；fork 不赋予授权 | §5.5 明确不拷贝、自行重写；评审核对；可选向作者请求补 LICENSE |
| 嵌套 / 外层包装目录 | 用户选了上一层目录识别失败 | 与现有引擎一致，不放宽 |

---

## 9. 评审点与决策建议

| # | 议题 | 建议 | 备选 |
|---|---|---|---|
| D1 | 独立类型 vs 并入 `XSYS35` | **独立 `XSYS4`** | 并入并二次分流（扫描与设置复杂，不推荐） |
| D2 | SDL 运行时 | **共用 `libSDL2x` + `app32`**（ADR 0001） | 另建一份（体积与维护翻倍） |
| D3 | 字体 | **B 复用现有 + 自定义导入**；验收不可接受则同交付内切 C | A 上游原样（+26.8 MB） |
| D4 | 源码托管 | fork 三个仓库固定 commit（ADR 0002） | 直接引用上游（失去修改声明落点与长期可得性） |
| D5 | 版本条目 | id `xsys4-6a94a0c`，标题 `xsystem4-6a94a0c` | 带「Android 1.0.7」（错误：壳版本不对应本基线） |
| D6 | 默认存档位置 | **独立存档开** | 默认关（外部存储写入风险） |
| D7 | 设置范围 | 独立存档 + 编码 + 自定义字体 + 手柄 + 跳字（一次做完） | 删减（不再分期，不推荐） |
| D8 | libwebp / freetype 版本 | libwebp 沿用壳已验证的 1.3.2；freetype 复用 System35 的 2.14.3（⚠ 回归） | freetype 回退壳的 2.13.3（多一份下载） |
| D9 | 视频限制 | 记为已知限制，视验收升级 | 评估移植 FFmpeg 路径（另立项） |
| D10 | 封面占位色 | `0xFF00A176`（ΔE≥24、白字对比度 3.3，已核验） | 琥珀 `0xFFC08A1E`（色相更远，但白字对比度未核） |
| D11 | 旧库纠正 | **一次性自动定点重检测**（只处理 XSYS35 条目，不动其它字段） | 整库全量重扫（会重置标题，已否决）；仅发布说明提示用户手动刷新 |

---

## 10. 实施顺序（单次交付，无分期）

按依赖排成一条线，无循环依赖（设置层先于启动 / 存档；扫描与回归测试先于启动链路合入）：

| 步 | 内容 | 预估 |
|---|---|---|
| 1 | 前置：fork 已全部完成（✅）；仅剩工具链检查（bison / flex 定位） | 0.2 天 |
| 2 | 构建：依赖下载脚本 + staging + 自研 find 模块 + `libxsystem4.so` 入库 + `shaders/` 入 assets + `BUILD.md` / 许可文本 + System35 反向声明 | 2–2.5 天 |
| 3 | engine：`Xs4Activity` + `Xs4ManualActivity` + `Xs4Fonts` + Manifest / Theme / consumer-rules / 契约常量 / engine 三语言文案 | 1 天 |
| 4 | app 核心：`EngineType` + `Xs4Ini` + `Xs4Paths` + **扫描（XSYS4 前置）+ 全部扫描测试** + 设置层（Store / Resolver / PerGame / 分区 / 锚定测试）+ 一次性重扫迁移 | 2 天 |
| 5 | app 启动与存档：`buildXs4Intent` / 失败类型 / `GameSaveManager` / 插件分支 / 引擎页与封面色 | 1 天 |
| 6 | app UI：设置卡片（全局 + 单游戏）、自定义字体导入、信息卡、app 三语言文案 | 1 天 |
| 7 | 图标封面兜底 `Xs4IconCover` + 单测 | 0.7 天 |
| 8 | 文档：README / CONTEXT / ADR 0001–0002 / 审查问题清单登记 | 0.5 天 |
| 9 | 门禁 + 多 Agent 独立审核（含 Android CLI 审查技能）一轮 | 0.5 天 |
| 10 | 真机验收（§7.2）+ 修复 + 终审 | 1.5 天 |

合计约 **10–11 天**（System35 约 5–6 天；多出的是依赖数量、flex / bison / libffi 工具链，以及原二期功能一并交付）。

---

## 附录 A：上游关键文件索引（勘察用）

| 主题 | 文件 |
|---|---|
| Android 构建 | `nunuhara/xsystem4` 根 `CMakeLists.txt`；壳 `CMakeLists.txt`、`build-shared-libs.sh`、`cmake/Find*.cmake`、`collect-licenses.sh`（仅参考） |
| 入口 / 参数 / 配置 | `src/system4.c`（`get_xsystem4_home`、`config_init_with_dir` / `_ini`、`set_encoding`、`main`；行号以 `6a94a0c` 为准） |
| 输入 / 触控 | `src/input.c`（`__ANDROID__` 延迟按键、`SDL_FINGER*`、`SDL_MULTIGESTURE`） |
| 字体 | `src/text.c:40-72`（缺省宏与回落、失败 `ERROR`）、`src/font_freetype.c`（`ft_font_load`、`y_offset`） |
| 渲染 | `src/video.c`、`src/draw.c`、`src/3d/renderer.c`（`shaders/**/*.glsl`） |
| 错误 / 退出 | libsys4 `src/system.c`（`sys_verror` / `sys_exit` → `exit()`） |
| 文件 / 大小写 | libsys4 `src/file.c`（`path_get_icase`）、`asset_manager.c` |
| 视频 | `src/movie_plmpeg.c`（Android）、`src/meson.build`（FFmpeg 选择逻辑） |
| 壳宿主（仅参考） | `XSystem4Activity.kt`、`GameList.kt`、`PEResourceExtractor.kt`、`ManualActivity.kt`、`AndroidManifest.xml` |
| 兼容性 | `game_compatibility.md`（master） |

## 附录 B：本仓既有工程事实（改动依据）

| 事实 | 位置 |
|---|---|
| System35 已落地：`libSDL2x.so` 1 476 240 B（SHA-256 `cee53fbc…`，`BUILD.md:251`）、`libSDL2_mixer_xs35.so`、`libxsystem35.so` | `engine/src/main/jniLibs/arm64-v8a/` |
| `org.libsdl.app32` 9 个 Java 文件由脚本生成 | `engine/src/main/java/org/libsdl/app32/`、`engine/xsystem35/scripts/sync-app32-java.sh` |
| `Xs35Activity`（527 行）：预检 / 返回键 / 朝向 / 消息框 / killProcess | `engine/src/main/java/com/core/xsys35/Xs35Activity.java` |
| XSYS35 规则 `hasAldScript && aldCount>=2` → 90 位于 `:1005-1007`，在 CS2 之后 | `EngineScanner.kt:997-1010` |
| `ScanGame` 构造点 4 处（title 均为 `dir.name`）；`Detection` 现无 title | `EngineScanner.kt:198,259,583,617,655-660` |
| `rescanLibrary` 的 `copy(...)` 不保留 title；增量扫描对已入库目录剪枝 | `EngineScanner.kt:96-150` |
| 本地封面链路 | `EngineScanner.applyLocalCover`（`:389`）、`CoverScraperService.kt:62` |
| 引擎名解析 `getOrDefault(UNKNOWN)` | `GameEntity.kt:46`、`GameRecordCodec.kt:41` |
| 设置接线触点（SettingsScreen 4 处、PerGameSettingsScreen 6 处等） | 见 §4.5 |
| 现有字体 `MTLc3m` 1.92 MB / `mincho` 4.78 MB / `DroidSansFallback` 7.29 MB；`FontImport` 在 UI 层 | `engine/src/main/assets/`、`ui/settings/FontSettings.kt` |
| `noCompress += ttf, otf` | `app/build.gradle.kts:249` |
| engine 清单已有 `VIBRATE`；无 `uses-feature`；`:xsys4`、`com.core.Xsys4` 无冲突 | `engine/src/main/AndroidManifest.xml` |
| 无 `shaders/`、`fonts/` 目录 | `engine/src/main/assets/`、`app/src/main/assets/` |
| 全仓无 System4 相关既有代码 | grep 无命中 |
| 本项目 GPL-2.0-only | `LICENSE`、`README.md` |
| 检查脚本只扫 `.kt`，不扫 `.java` | `tools/check-hardcoded-ui-strings.py` |

## 附录 C：新增文案键（三语言）

**app `values{,-en,-ja}/strings.xml`**

| 键 | zh | ja | en |
|---|---|---|---|
| `engine_desc_xsys4` | System4（xsystem4） | System4（xsystem4） | System4 (xsystem4) |
| `engine_settings_xsys4_title` | System4引擎设置 | System4 エンジン設定 | System4 engine settings |
| `engine_settings_xsys4_encoding_title` | 文本编码 | 文字コード | Text encoding |
| `engine_settings_xsys4_encoding_sjis` | Shift-JIS（日文版） | Shift-JIS（日本語版） | Shift-JIS (Japanese) |
| `engine_settings_xsys4_encoding_gbk` | GBK（中文版） | GBK（中国語版） | GBK (Chinese) |
| `engine_settings_xsys4_font_gothic_title` | 自定义黑体字体 | カスタムゴシックフォント | Custom gothic font |
| `engine_settings_xsys4_font_mincho_title` | 自定义明朝字体 | カスタム明朝フォント | Custom mincho font |
| `engine_settings_xsys4_font_hint` | 仅对 System4 生效；导入失效时回落内置字体；修改后需重新进入游戏。 | System4 のみ有効。読み込めない場合は内蔵フォントに戻ります。変更後はゲームの再起動が必要です。 | System4 only; falls back to the built-in font if the file is unusable; re-enter the game after changes. |
| `engine_settings_xsys4_joypad_title` | 手柄 | ゲームパッド | Gamepad |
| `engine_settings_xsys4_joypad_auto` / `_on` / `_off` | 跟随引擎默认 / 开 / 关 | エンジン既定 / オン / オフ | Engine default / On / Off |
| `engine_settings_xsys4_msgskip_title` | 跳字延迟 | メッセージスキップ遅延 | Message skip delay |
| `engine_settings_xsys4_msgskip_default` | 引擎默认 | エンジン既定 | Engine default |
| `engine_settings_xsys4_note` | 文本编码须与游戏版本一致；独立存档开启时存档位于应用存档目录，关闭时位于游戏目录 | 文字コードはゲーム版に合わせてください。独立セーブ有効時はアプリのセーブフォルダ、無効時はゲームフォルダ | Match the text encoding to the game version; saves live in the app save dir when scoped saves are on, otherwise in the game folder |
| `engine_settings_xsys4_uninstall_hint` | 卸载或删除游戏会清除应用独立存档目录 | アンインストールまたはゲーム削除時にアプリ専用セーブフォルダが消去されます | Uninstalling or removing the game clears its scoped save directory |
| `engine_settings_xsys4_compat_hint` | Rance X 及之后作品、Evenicle 等不受支持；影像仅支持 MPEG-1 | Rance X 以降の作品・Evenicle などは非対応。動画は MPEG-1 のみ | Rance X and later titles and Evenicle are unsupported; video is MPEG-1 only |
| `save_error_xsys4_external_unavailable` | 外部存储不可用，无法使用独立存档目录 | 外部ストレージが利用できないため、専用セーブフォルダを使用できません | External storage unavailable; cannot use the scoped save directory |
| `launch_error_xs4_save_path_not_directory` | 存档路径存在但不是目录：%1$s | セーブパスがフォルダではありません：%1$s | Save path exists but is not a directory: %1$s |
| `launch_error_xs4_save_dir_unavailable` | 无法创建存档目录：%1$s | セーブフォルダを作成できません：%1$s | Cannot create save directory: %1$s |
| `launch_error_xs4_external_unavailable` | 外部存储不可用，无法使用独立存档目录 | 外部ストレージが利用できないため、専用セーブフォルダを使用できません | External storage unavailable; cannot use the scoped save directory |
| `launch_error_xs4_ini_missing` | 未找到 System40.ini / AliceStart.ini，无法启动 System4 游戏 | System40.ini / AliceStart.ini が見つからないため System4 ゲームを起動できません | System40.ini / AliceStart.ini not found; cannot start this System4 game |
| `launch_error_xs4_gles3_required` | 需要支持 OpenGL ES 3.0 的设备 | OpenGL ES 3.0 対応デバイスが必要です | A device with OpenGL ES 3.0 support is required |

> 自定义字体的「导入 / 清除 / 跟随默认 / 导入失败」文案优先复用 FVP 已有键（`engine_settings_fvp_font_*`，实现时 grep 确认可复用，不足再新增 `engine_settings_xsys4_font_*`）。

**engine `values{,-en,-ja}/strings.xml`**

| 键 | zh | ja | en |
|---|---|---|---|
| `engine_xsys4_missing_game_dir` | System4 启动失败：缺少游戏目录 | System4 の起動に失敗しました：ゲームディレクトリがありません | System4 failed to start: the game directory is missing |
| `engine_xsys4_init_failed` | System4 引擎初始化失败：未找到 System40.ini / AliceStart.ini | System4 エンジンの初期化に失敗しました：System40.ini / AliceStart.ini が見つかりません | Failed to initialize System4: System40.ini / AliceStart.ini not found |
| `engine_xsys4_gles3_required` | 需要支持 OpenGL ES 3.0 的设备 | OpenGL ES 3.0 対応デバイスが必要です | A device with OpenGL ES 3.0 support is required |
| `engine_xsys4_back_again_to_exit` | 再按一次返回键退出游戏 | もう一度戻るキーを押すとゲームを終了します | Press back again to exit the game |

> 复用：`engine_another_game_running`、`engine_ok`、`engine_cancel`。新增后必须通过 `python3 tools/check-hardcoded-ui-strings.py`（三语言键集合齐平；`.java` 里的文案不被扫描，靠审核保证走 `uiString`）。

