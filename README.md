<p align="center">
  <img src="screenshots/index1.png" alt="Tyranor Next" width="900" />
</p>

基于 **Tyranor 模拟器逆向重写**的多引擎视觉小说（Galgame）模拟器器，面向 Android 平台。具体功能与兼容边界见 [SPEC.md](SPEC.md)。

mac原生版本如下：

- [Tyranor Next for macOS](https://github.com/Weiss-UltimateSavior/Tyranor-Mac)

iOS版本计划中...
鸿蒙设备可使用卓易通安装

主打轻便、简单、快捷，不引入其他冗余功能的简约设计思路

企鹅群：364439133

感谢 [@安音](https://www.pixiv.net/users/8182248) 为本项目绘画的logo娘！

### 默认主题界面（浅色）
<p align="center">
  <img src="screenshots/index2.png" alt="Tyranor Next" width="900" />
</p>

### 三种不同风格主题（不包含深浅色模式对比）
<p align="center">
  <img src="screenshots/index3.png" alt="Tyranor Next" width="900" />
</p>

## 感谢各位贡献者们！一起成为光吧！

<a href="https://github.com/Weiss-UltimateSavior/Tyranor-Next/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=Weiss-UltimateSavior/Tyranor-Next" />
</a>

## 支持范围

### 引擎与游戏类型

| 游戏类型                 | 典型识别特征                                                              | 运行环境                         |
| -------------------- | ------------------------------------------------------------------- | ---------------------------- |
| Kirikiri / Kirikiri2 | `.xp3`、`startup.tjs`                                                | Kirikiroid2 / krkrsdl3 原生运行时 |
| ONScripter           | `nscript.dat`、`.nsa`                                                | ONScripter 原生运行时             |
| Artemis              | `system.ini`、`.pfs`                                                 | Artemis 原生运行时                |
| SiglusEngine         | `Gameexe.dat`/`Gameexe.ini`、`Scene.pck`（根或 `Data/`）                | Siglus 原生运行时（siglus_rs）      |
| RealLive             | `Gameexe.ini` + `SEEN.TXT`（10000 项 TOC）或 `SEEN####.TXT`             | 内置视觉小说运行时（game_launcher）  |
| AVG32                | `Gameexe.ini` + `SEEN.TXT`（`PACL`）或 `SEEN###.TXT`（`TPC32`）          | 内置视觉小说运行时（game_launcher）  |
| UK2（PC-98）          | `UK2.CFG`、`*.MES`（`<< UK2 TEXT Ver1.00 >>` 头）                       | 内置视觉小说运行时（game_launcher）  |
| FVP                  | `*.hcb`/`*.bch` 脚本、`graph.bin`/`voice.bin` 等资源包                   | 内置 FVP 原生运行时（rfvp）          |
| System35             | `*.ALD`（`SA.ALD` 等；根目录或 `GAMEDATA/`）、`System39.ain`/`System39.ini`/`*.wai`/`*.bgi`/`*.alk` | 内置 System3.x 运行时（xsystem35） |
| System4              | `System40.ini` 或 `AliceStart.ini`（根目录；`*.ain` 为辅助特征）                    | 内置 System4 运行时（xsystem4）      |
| YU-RIS               | `yscfg.dat`、`pac/*.ypf`、`YS*.DLL`、`.ymv`                          | 外置 Winlator（winlator-cn）        |
| CatSystem2           | `config/startup.xml`、`*.int`、`*.cst`、`*.hg3`、`*.kcs` 等组合         | 外置 Winlator（winlator-cn）        |
| PC（手动添加）        | 游戏页顶栏「添加 PC 游戏」选择目录并指定启动 exe                          | 外置 Winlator（winlator-cn）        |
| TyranoBuilder        | `index.html`、`tyrano/`                                              | 内置 Tyrano Web 运行环境           |
| RPG Maker XP         | `.rgssad`、`Game.ini` + `Data/*.rxdata`                              | 外置 RPGM APK 模块               |
| RPG Maker VX         | `.rgss2a`、`Game.ini` + `Data/*.rvdata`                              | 外置 RPGM APK 模块               |
| RPG Maker VX Ace     | `.rgss3a`、`Game.ini` + `Data/*.rvdata2`                             | 外置 RPGM APK 模块               |
| mkxp-z               | 用户选择/内部别名 `internal.mkxp-z`                                         | 外置 RPGM APK 模块               |
| RPG Maker MV         | `www/`、`js/rpg_core.js`                                             | 内置 Web 运行环境                  |
| RPG Maker MZ         | `www/`、`js/rmmz_core.js`                                            | 内置 Web 运行环境                  |
| VN                   | `globalData.vndata`                                                 | 内置 Web 运行环境                  |
| WebOther             | 通用 `index.html` 网页游戏                                                | 内置 Web 运行环境                  |
| Ren'Py               | `.rpa`、`game/script.rpy`、`game/options.rpy`、`renpy/` + `.rpy/.rpyc` | 外置 RenPy APK 模块              |
| Ruffle / Flash       | 顶层 `.swf`；根 `index.html/index.htm` + 唯一 SWF 的叶子目录                   | 外置 Ruffle APK 模块              |
| PSP                  | `.pbp`、`.cso`、`.iso`、`.chd`                                         | 外置 PPSSPP 模拟器                 |
| Nintendo Switch      | `.nsp`、`.xci`、`.nca`、`.nro`                                         | 外置 Eden 模拟器                   |


### 平台与文件要求

- Android 8.0（API 26）及以上。
- 当前原生引擎库仅提供 `arm64-v8a`，设备需为 64 位 ARM 架构。
- 游戏目录需位于 Android 可访问的本地存储，并通过系统文件选择器（SAF）授权；启动时目录必须能够映射为真实文件路径，外置存储上的部分引擎可能需要“所有文件访问”权限。
- 实际兼容性取决于游戏使用的引擎版本、封包/加密方式和脚本特性；特殊修改版可能需要调整引擎设置或补丁。


## 参与贡献

欢迎参与项目开发与维护！

在提交 Pull Request 前，请先阅读务必阅读遵守 [贡献指南](./CONTRIBUTING.md)。
如果不符合贡献指南的要求，可能会被拒绝合并。
代码务必符合项目整体风格，避免引入新的问题。
提交前请务必使用项目内AGENTS.md文件内的审核方式进行提交前审核。
如果代码一眼丁真，一定拒绝合并。

## 技术架构

开发与维护文档：[仓库规范](AGENTS.md) · [架构说明](ARCHITECTURE.md) · [功能规格与验收清单](SPEC.md) · [领域术语](CONTEXT.md) · [变更记录](CHANGELOG.md)。专题方案与 `.archify/` 图表保留其生成时的上下文；当前实现以源码及上述架构/规格说明为准。


### 引擎集成设计

- 引擎原生插件（`kirikiroid2` / `ons` / `artemis` 的 `.so`）以 assets 形式随 APK 打包（`nativeplugins/`），首次启动由 `NativePluginManager` 自动解压安装到应用私有目录
- 共享 Native 插件 `.so` 的源头位于 `engine/src/main/nativeplugins`；app 侧仅维护插件 `manifest.json` 和 app-only 插件。构建时由 Gradle 合并打包成 `app/build/generated/assets/nativeplugins/*.zip.xz`（未压缩 zip 容器 + LZMA2，同一引擎家族的多个近重复 `.so` 共享字典）
- 大体积引擎运行库（`libsiglus.so`、`librfvp.so`、`libethornell.so`、`libkrkrsdl3.so`、IJK 三件套、System35/4 与 `libSDL2x.so`）不进 APK 的 `lib/`：构建期打成 `assets/engine-runtime/<pack>.zip.xz`，首次启动对应引擎时由 `NativeRuntimePack` 解压到应用私有目录，JNI 桥按绝对路径加载
- 共享 RPG Maker 注入脚本源头位于 `engine/src/main/assets`；app 侧只保留应用专属注入脚本，构建时由 Gradle 同步生成到 app assets，避免两边手工维护重复文件
- `app` 模块通过 `core/game/launch/EngineLauncher` 将扫描结果映射到对应引擎 Activity 启动（SAF URI → 真实路径转换）
- Ren'Py、RPG Maker RGSS、Ruffle 等外置 APK 引擎模块由 `core/engine/external` 统一注册、检测安装状态并按 intent 协议拉起；主 App 不维护手动启用开关，模块安装即视为可用。Ruffle 使用 `launchEntryIsFile` 的文件型入口，启动前把 SWF 文件记录解析为父目录再下发
- 第三方 APK 运行时模块由 `core/engine/runtime` 按 `com.tyranor.runtime.` 前缀与 `LAUNCH` action 动态发现，在侧边栏「妙妙工具 → 引擎模块管理」默认关闭、按模块启用；扫描仅使用模块 `res/raw/tyranor_runtime.json` 声明的特征，启动经显式组件只传入游戏文件夹路径，模块自管运行、设置与存档
- PSP / Nintendo Switch 由 `core/engine/external` 的 `ExternalEmulatorRegistry` / `ExternalEmulatorLauncher` 跳转外置 PPSSPP / Eden：扫描识别 ROM 后逐条入库（一 ROM 一条），运行时在目录解析前分流、按显式组件与读取授权启动；引擎页「外置跳转支持」项展示安装状态并可跳下载页，Manifest `<queries>` 已声明对应包名
- Tyrano 运行环境内置本地 HTTP 服务器、Asar 归档解析与 JS 钩子脚本（`__tyrano__.js` 等），无需外部依赖即可运行网页式脚本游戏
- FVP（rfvp）宿主为 Java `SurfaceView` + `Choreographer` 主循环驱动 Rust 引擎（`librfvp.so` + 自研桥接库），随 APK 打包；支持 `*.hcb` 原版与 `*.bch` 汉化脚本，文本编码（Shift-JIS/GBK/UTF-8）、系统字体回退与文本 HiDPI 可在引擎设置中按全局/单游戏调整
- RealLive / AVG32 / UK2 由 `game_launcher` 运行库承载（与 Siglus 同一 `libsiglus.so`，由 `libgames_bridge.so` dlopen），宿主 `FramebufferGameActivity`（`:fbgames` 进程）每帧驱动 `game_fb_*` 帧缓冲：单指=左键、双指点按=右键、双指上下滑=滚轮、Back=右键，文本编码（自动/Shift-JIS/GBK/Big5/UTF-8/韩语）可按全局/单游戏调整
- 原生库仅提供 `arm64-v8a` 架构

## 可视化文档

`.archify/` 下随仓库提供一组基于当前代码生成、经自动化校验（schema 校验 + 真实浏览器渲染检查）的交互式图表：均为自包含 HTML，浏览器直接打开即可缩放、检索/聚焦节点、切换深浅主题并导出图片；每个目录内同时保留可编辑的 `candidate.json` 源数据与校验回执，可配合 archify skill 重新渲染。

| 图表 | 类型 | 覆盖内容 |
| --- | --- | --- |
| [系统总览](.archify/架构图-系统总览-20261006-022735/architecture-overview.html) | 架构图 | 三层架构、主进程 / 引擎进程族边界、外置模块与模拟器、在线服务集成 |
| [引擎运行时与原生插件体系](.archify/架构图-引擎运行时-20261006-022735/architecture-engine-runtime.html) | 架构图 | 插件供给链、宿主与 JNI 桥接、预编译运行库、Rust XP3 与 Web 壳 |
| [应用功能地图](.archify/功能图-应用功能地图-20261006-022735/architecture-feature-map.html) | 功能图 | 六大功能域 × 18 项能力及对应实现组件 |
| [游戏扫描与入库流程](.archify/流程图-游戏扫描入库-20261006-022735/workflow-scan-import.html) | 流程图 | SAF 授权、目录遍历、引擎识别优先链、差量合并与 Room 落库 |
| [游戏启动编排流程](.archify/流程图-游戏启动编排-20261006-022735/workflow-launch-orchestration.html) | 流程图 | 四级分流（安卓 / 模拟器 / 外置模块 / 内置引擎）与启动前确认 |
| [KiriKiri 启动请求时序](.archify/时序图-KiriKiri启动-20261006-022735/sequence-kirikiri-launch.html) | 时序图 | 插件保障 → SAF 镜像 → Intent → JNI 桥接 → TVP 就绪全链路 |
| [游戏库数据与封面管线](.archify/数据流图-游戏库与封面-20261006-022735/dataflow-library-covers.html) | 数据流图 | 采集、识别、Room 权威存储、prefs 镜像、封面缓存与消费方 |
| [RPG Maker MV/MZ 存档生命周期](.archify/生命周期图-存档互通-20261006-022735/lifecycle-save-interop.html) | 生命周期图 | 互通同步、格式转化、延迟回前台与中止等状态 |

## 构建

```bash
# 编译 Debug APK
./gradlew assembleDebug --no-daemon
```

产物位于 `app/build/outputs/apk/debug/`。需要 Android SDK（本地平台 `android-37.0`）环境。

CI 使用 JDK 17；还需 engine 的 Android 36 平台、Build Tools 37.0.0、NDK 28.0.13004108 与 CMake 3.22.1。Windows 使用 `./gradlew.bat`。JVM 回归运行 `./gradlew testDebugUnitTest --no-daemon`，完整检查命令与设备验收边界见 [AGENTS.md](AGENTS.md#构建)。普通 Gradle 构建使用已提交的运行库，不自动重编所有原生引擎。

## 目录结构

```
app/
├── src/main/java/com/tyranor/next/
│   ├── core/      功能抽象层：扫描、启动、引擎模块、封面、存档、补丁、拆封包、配置、授权、更新
│   ├── ui/        界面 UI 交互层：主界面、游戏页、设置页、弹窗、公共组件
│   └── theme/     Compose / Miuix 主题、色调、深浅色适配
├── src/main/assets/rtp/    App 自有 RTP 资源
├── src/main/nativeplugins/ 插件 manifest 与 app-only 插件源头
├── build/generated/assets/ 构建生成的 engine/ 共享脚本与 nativeplugins/ 插件资产（.zip.xz）
├── schemas/                Room schema（随代码提交）
└── src/{test,androidTest,releaseProbe}/ JVM、设备与 release probe 测试

engine/
├── src/main/java/          底层引擎宿主、桥接代码与运行时入口
├── src/main/cpp/           本地编译的 Native/JNI 与图形桥
├── src/main/assets/        底层引擎资源与共享引擎脚本源头
├── src/main/nativeplugins/ 共享 Native 插件 so 源头
├── src/main/jniLibs/       预编译原生运行库（arm64-v8a）
├── src/test/              JVM 回归与 Node Web 桥 harness
└── rust/                   XP3 解封包 Rust 源码（xp3 解析与 KSD mode-2 隐性解码；许可证见 LICENSE.usefulunpack）

buildSrc/、tools/、.github/workflows/  构建校验、工具与 CI
docs/、.archify/                     专题方案、审查记录与可视化
```

## 许可证

本项目基于 **GNU General Public License v2.0** 发布，详见 [LICENSE](LICENSE)（GPL-2.0-only）。

- `engine/` 引擎运行时基于 Tyranor 模拟器逆向重写，上游涉及 Kirikiroid2 / ONScripter 等 GPL-2.0 项目，因此整个项目以 GPL-2.0 授权分发
- 基于本项目发布的衍生作品须遵循 GPL-2.0 条款，并随发行物提供完整源码
- Miuix 等第三方依赖按各自许可证引入
- Siglus / RealLive / AVG32 / UK2 引擎运行库来自 [siglus_rs](https://github.com/xmoezzz/siglus_rs)（MPL-2.0，上述引擎的非官方 Rust 重写/移植），以其自身许可证引入；相应源码可于上游或其 fork 仓库获取
- FVP 引擎运行库来自 [rfvp](https://github.com/xmoezzz/rfvp)（MPL-2.0，FVP 引擎的非官方 Rust 重写/移植），以其自身许可证引入；相应源码可于上游或其 fork 仓库获取
- System35 引擎运行库来自 [xsystem35-sdl2](https://github.com/kichikuou/xsystem35-sdl2)（GPL-2.0-or-later，AliceSoft System 3.x 开源重实现的 SDL2 移植），引擎源码与修改声明见 fork [xsystem35-sdl2](https://github.com/Weiss-UltimateSavior/xsystem35-sdl2)；本仓库仅保留预构建运行库、构建剧本与补丁记录（见 `engine/xsystem35/BUILD.md`）；其依赖 SDL2 / SDL2_mixer（zlib）、freetype（FTL/GPLv2）、libwebp（BSD-3-Clause）按各自许可证引入，许可证文本随 `engine/xsystem35/licenses/` 保留
- System4 引擎运行库来自 [xsystem4](https://github.com/nunuhara/xsystem4)（GPL-2.0-or-later，AliceSoft System 4 引擎的开源重实现），引擎源码与修改声明见 fork [xsystem4](https://github.com/Weiss-UltimateSavior/xsystem4)；其依赖 libsys4（GPL-2.0-or-later，上游 [libsys4](https://github.com/nunuhara/libsys4)）同样取自 fork [libsys4](https://github.com/Weiss-UltimateSavior/libsys4)。Android 构建仅参考 `kichikuou/xsystem4-android` 的事实信息，未拷贝其任何文件。静态链入的依赖包括 libsndfile fork [libsndfile](https://github.com/Weiss-UltimateSavior/libsndfile)（`xsystem4` 分支，LGPL-2.1-or-later）、libogg / libvorbis / libwebp（BSD-3-Clause）、libjpeg-turbo（BSD-3-Clause / IJG）、libpng、libffi / libdeflate / cglm（MIT）及 freetype（同 System35）；SDL2（zlib）与 System35 共用 `libSDL2x.so`；xsystem4 与 libsys4 内的第三方例外（如 cJSON、pl_mpeg（MIT）、queue.h、base64.c（BSD）、kvec.h（MIT；`vector.h` 与之同声明）、sts_mixer（Unlicense，公有领域）、mt19937int（libsys4，LGPL-2.0-or-later 声明））按各自许可证引入。预构建运行库、构建剧本与补丁记录见 `engine/xsystem4/BUILD.md`，许可证文本随 `engine/xsystem4/licenses/` 保留
- Anime4K GLSL 着色器（`engine/src/main/assets/anime4k/`）来自 [bloc97/Anime4K](https://github.com/bloc97/Anime4K)，按其 MIT 许可证引入（版权声明保留于各着色器文件头部）
- VN-SR C08 / C12 / C16 原生2X滤镜来自 [AAAA1111-Lab/VN-SR](https://github.com/AAAA1111-Lab/VN-SR)，按 MIT 许可证引入；资源与许可说明见[VN-SR滤镜集](docs/vnsr-filters.md)。

## 致谢
- [Kirikiroid2](https://github.com/zeas2/Kirikiroid2): Kirikiroid2引擎
- **Tyranor 模拟器**：本项目引擎运行时与核心架构的逆向重写基础
- [Artemis-Compat](https://github.com/Weiss-UltimateSavior/artemis-compat)：自研兼容内核
- [siglus_rs](https://github.com/xmoezzz/siglus_rs)（MPL-2.0）：SiglusEngine / RealLive / AVG32 / UK2 的 Rust 重写与多平台移植，四个引擎的运行时
- [rfvp](https://github.com/xmoezzz/rfvp)（MPL-2.0）：FVP 引擎的 Rust 重写与多平台移植，FVP 运行时
- [xsystem35-sdl2](https://github.com/kichikuou/xsystem35-sdl2)（GPL-2.0-or-later）：AliceSoft System 3.x 引擎的开源重实现（SDL2 移植），System35 运行时；依赖 [SDL2](https://github.com/libsdl-org/SDL) / [SDL2_mixer](https://github.com/libsdl-org/SDL_mixer)（zlib）、[freetype](https://freetype.org/)（FTL/GPLv2）、[libwebp](https://chromium.googlesource.com/webm/libwebp)（BSD-3-Clause）
- [xsystem4](https://github.com/nunuhara/xsystem4) 与 [libsys4](https://github.com/nunuhara/libsys4)（GPL-2.0-or-later）：AliceSoft System 4 引擎的开源重实现及其底层库，System4 运行时；Android 构建参考 [xsystem4-android](https://github.com/kichikuou/xsystem4-android)（kichikuou），仅作事实参考。依赖 [libsndfile](https://github.com/kichikuou/libsndfile)（LGPL-2.1-or-later，`xsystem4` 分支）等，见「许可证」
- **RinneMobile**：游戏扫描识别/SAF路径映射逻辑/独立存档映射/krkrsdl3 等多个功能的参考与实现
- [Miuix](https://github.com/compose-miuix-ui/miuix)：设置界面组件库
- [Anime4K](https://github.com/bloc97/Anime4K):（bloc97，MIT）：KRKR 游戏画面实时超分（线条重建 CNN 着色器）
- [OnscripterYuri](https://github.com/YuriSizuku/OnscripterYuri): ONS引擎
- [UsefulUnpack](https://github.com/znso4pa/usefulunpack)（znso4pa，MIT）：XP3 / KSD 解封包 Rust 实现（`engine/rust` 的 `lib.rs` / `common.rs` / `ksd.rs` 来源，许可证见 `engine/rust/LICENSE.usefulunpack`）
- 各引擎运行时均基于其开源许可引入
