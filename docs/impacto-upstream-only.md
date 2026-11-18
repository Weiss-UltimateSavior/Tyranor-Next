# MAGES. 外置引擎（impacto 项目）接入记录

> **命名**：引擎名是 **MAGES.**（界面与文档中一律这样写）；**impacto** 是重新实现该引擎的开源项目，也是宿主 APK 的名字。代码里的 `IMPACTO` / `Impacto*` 标识沿用项目名。

> **宿主 APK 已迁出（2026-11-15）**：宿主 APK 的源码、固定来源 `source-lock.json`、构建脚本、发布工作流与许可说明现在在独立公开仓库 [yuukana1/Tyranor-Next-MAGES](https://github.com/yuukana1/Tyranor-Next-MAGES)，由它构建、签名并发布 APK。本仓库只保留主 App 一侧的接入（引擎类型、扫描、外置模块、文案）和 `tools/impacto/` 里与游戏数据有关的两个只读工具。下文中带日期的构建、宿主与真机记录是迁出前的历史记录，其中提到的 `engine/impacto/`、`tools/impacto/build-upstream.sh` 等路径已不在本仓库，请到上述仓库查看。

> **补丁系列（2026-11-18，取代下文「不应用 TYN 引擎补丁」）**：宿主不再是 upstream-only。为支持 Windows／Steam 版《秋之回忆6》，宿主仓库的 [PR #1](https://github.com/yuukana1/Tyranor-Next-MAGES/pull/1) 起，构建输入是**固定的上游提交加一套补丁系列**（`engine/impacto/patches/`；`source-lock.json` 记录每个补丁的 SHA-256，构建与发布工作流都要求检出恰好等于「固定提交 + 系列」）。从宿主 0.2.0 起发布的 APK 都带这套补丁。下文「约束与当前行为」据此改写；带日期的历史记录（构建、真机、样本核对）保持写下时的样子，不回改。

本记录面向 `Weiss-UltimateSavior/Tyranor-Next` 的 `main`，从当前上游基线
`e04f9a15` 建立。它取代已关闭的 [PR #161](https://github.com/Weiss-UltimateSavior/Tyranor-Next/pull/161)
的引擎补丁路线。本 PR 把 Impacto 作为外置 APK 引擎接入主 App；宿主在独立仓库构建发布。

当前唯一开发入口：[上游 PR #165](https://github.com/Weiss-UltimateSavior/Tyranor-Next/pull/165)，
分支 `yuukana1:feat/impacto-upstream-only`。原独立私有仓库已弃用，不再承担源码、产物或后续交接依赖。

## 约束与当前行为

- 直接引用 [CommitteeOfZero/impacto](https://github.com/CommitteeOfZero/impacto)，用完整 commit SHA 固定版本。
- 对上游源码、profile 与着色器的修改只存在于宿主仓库的**补丁系列**里：按序应用、逐个记录 SHA-256，只放 TYN 兼容性所需的最小改动，能上游化的写成可提交上游的形态；构建与发布校验检出恰好等于「固定提交 + 系列」。不以生成修改副本或符号替换绕过补丁系列。（原约束「不修改 Impacto 源码、profile、着色器，不应用 TYN 引擎补丁」在 2026-11-18 被取代，见顶部说明；补丁中有通用修复，也有只服务 `mo6tw-pc*` 的新增，对其他游戏没有设备回归。）
- Android 生命周期、权限、目录、启动参数和存档隔离由 TYN 宿主适配；宿主不能解决的上游问题明确记录，不维护引擎分叉。
- 不增加 TYN 的 SDL 或其它第三方源码补丁，不碰 SDL3 的 Java 层与 JNI 命名空间。上游自身依赖构建配方保持原样，并在来源记录中说明。
- （PR #165 当时的状态）原版构建产物先留在独立候选目录。当前 PR 没有把旧补丁库、旧源码归档、私有 SDL 类或商业游戏数据带入应用，也尚未新增可启动的 Impacto 引擎入口。

## 测试样本

本次选定 **Memories Off 6 ~T-Wave~（秋之回忆6）**，对应上游 `mo6tw` profile。
[上游支持列表](https://github.com/CommitteeOfZero/impacto/issues/1) 明确列出 **PS3 版**；PS Vita 仅注明可能可用且未测试。
用户已有的实际样本是 **Windows／Steam 版**（见下节），不是上游列出的 PS3 版；不能仅凭游戏标题判断兼容。

上游 profile 要求数据目录内有 `BG.CPK`、`BGM.CPK`、`CHARA.CPK`、`MASK.CPK`、
`MOVIE.CPK`、`SCRIPT.cls`、`SCRIPT/`、`SE.CPK`、`SYSTEM.CPK` 和 `VOICE.CPK`。
不改写用户的商业游戏数据。上游 README 认为该游戏可推进全部结局，同时说明没有游戏完全实现；这不等于 TYN Android 端已验收。

## Windows／Steam 样本核对（只读，2026-11-13）

样本目录含 `game.exe`、`Launcher.exe`、`steam_api.dll`、`steam_settings/` 和 `data/*.cpk`
（`bg`、`bgm`、`chara`、`manual`、`mask`、`mes00`～`mes02`、`MgsShader`、`movie`、`script`、`se`、`shader`、`sysse`、`system`、`voice`）。
没有 `SCRIPT.cls`、`SCRIPT/` 或 `PS3_GAME/PARAM.SFO`。随附说明称 `boot.bat`／`boot_game.bat` 为日文原版，`bootHT.bat` 为繁体中文，`bootKT.bat` 为简体中文；未运行任何程序，语言以说明文字为据，未经内容核实。
只读检查用 [`tools/impacto/inspect-cpk.py`](../tools/impacto/inspect-cpk.py)：仅读取结构和成员前四字节，不输出内容，不在样本旁写文件；回归测试只用合成小档案。该工具核对的是上游 `CpkArchive` 的假设（含 ITOC 才重排偏移、按 `@UTF` 魔数回退解掩码、`Files`／ID 一致），不解码任何成员。

| 项目 | 样本观察 | 上游 `mo6tw` 的假设 | 状态 |
| --- | --- | --- | --- |
| 容器 | 16 个包均为 CRI CPK（Version 7.14）。除两个着色器包外的 14 个包，TOC 标记与全量魔数扫描均无压缩成员，Align 2048，带 TOC 与 ITOC，ID 从 0 连续；`shader`／`MgsShader` 的表被掩码（上游按 `@UTF` 魔数回退解开，同一算法）且成员为 CRILAYLA 压缩的 `.cg` | `CpkArchive` 按 `std::get` 读固定列类型，并在 ITOC 末尾按 ID 顺序从 ContentOffset 重排偏移 | 16 个包的 `Files` 与 TOC 行数一致、ID 唯一，列类型与 ITOC 重排偏移同真实 TOC 全部一致（Python 静态复现，**未运行上游代码**）。着色器包的压缩成员不能被上游 `Open` 流式读取，但它们不在上游挂载点内 |
| 脚本 | `script.cpk` 为 196 个 SC3 `.scx`（ID 0～2 为 `_startup`、`_system`、`_tips`），头部偏移 +4 为字符串表、+12 起为标签表 | PS3 为 `SCRIPT.cls`；VM 用 MO6TW 指令集 | 头部布局与上游访问方式一致，起始字节流外形也像 MO6TW 指令（`00:29`、`00:12`、`00:25` 等）。**指令集是否等同未验证** |
| 文本 | SC3 内字符串表很小（如 `sa01a` 约 2 KB），另有 `mes00`／`mes01`／`mes02` 各 196 个 `.msb`（`MES\0`，如 `sa01a_00.msb` 约 19 KB） | 无 `UseMsbStrings`，文本在 SC3 内 | 正文推测在 MSB，需 `UseMsbStrings`／`UseSeparateMsbArchive` 类设置；MSB 内容未解码确认 |
| 界面贴图 | `system.cpk` 为 37 个 PNG 加 `wavtable.dat`，含 `_ht`／`_kt` 语言变体，标题背景为 1920×1080 | 按**数字 ID** 引用图集，设计尺寸 2048×1024 或 1280×720（如 Font=Id 12 2048×1184，Title=Id 33，MainBg=Id 29） | 不匹配：样本 ID 8 为 `encyc_pic.png`（4096×1170），ID 12 为 `font_kt.png`（3072×6000），ID 33 为 `title_bg4.png`（1920×1080）。上游 `sprites.lua`、字体网格和 HUD 坐标均针对 PS3 图集，**不能直接用于该样本** |
| 立绘与背景 | `bg.cpk` 812 个 PNG；`chara.cpk` 为 384 对 PNG＋`.mvl` | `CharaIsMvl=false` | 需自有 profile 选择；MVL 路径只在上游 `cclcc` 等 profile 使用过，对本样本未验证 |
| 音频 | `bgm`／`se`／`sysse`／`voice` 均为 HCA，抽查文件头 `ciph` 为 0（未加密） | `hcaaudiostream`（clHCA） | 外形一致，**未实际解码** |
| 影片 | `movie.cpk` 为 6 个 CRI USM（`@SFV`／`@SFA`，内含 `.264` 流名） | 经 FFmpeg 播放 | USM 不是常规封装，FFmpeg 是否能打开未验证，预计影响片头片尾，不影响标题 |

据此分类（尚无运行证据）：

- **可由路径映射解决**：包名大小写（上游写 `BG.CPK`，样本为小写，Android 区分大小写）和 `script.cpk` 取代 `SCRIPT.cls`。上游支持 `-bp`／`-gc` 指定基础路径与游戏定义，宿主可指向 TYN 自有的 Lua 配置目录，不必改上游文件（读源码所得，未实测）。
- **需 TYN 自有数据适配**：全部图集 ID、尺寸与坐标，字体网格与字符映射，设计分辨率，MSB 与语言选择（`mes00`～`mes02`），立绘模式。这相当于为 PC 版重写一套实验性 profile，必须标为实验性 PC 配置，不能冒充上游 PS3 配置。
- **可能需要引擎改动，证据尚不足**：USM 播放；PC 与 PS3 之间指令、系统变量的差异。需要先编出原版库并在宿主中运行，才能确认，不能靠静态推断。

结论：直接套用上游 `mo6tw` 配置不会得到可用界面；能否经自有 profile 进入标题，目前没有证据，也尚未在任何设备上运行。

## 构建和更新

已迁到 [yuukana1/Tyranor-Next-MAGES](https://github.com/yuukana1/Tyranor-Next-MAGES)：该仓库的 `engine/impacto/source-lock.json` 固定引擎、vcpkg 与工具链版本（NDK `27.3.13750724`，上游官方 CI 默认使用的版本），`tools/impacto/` 里的脚本负责校验上游 checkout、从固定提交编译库、暂存与校验候选库，发布工作流打包签名宿主 APK。构建与升级流程见该仓库的 README 与 `engine/impacto/host-apk/README.md`。

这是固定 revision 和 Git 洁净性检查，不是游戏兼容性测试。

## 宿主迁移待办

- [x] 从 TYN 上游共同祖先建立独立分支，不合入私有实验仓库的独立根历史。
- [x] 固定来源，增加原版 checkout 校验和真实 Git 仓库回归。
- [x] 增加独立原版构建入口、宿主侧依赖锁定与候选库校验；尚未完成全量构建。
- [x] 完成原版 ARM64 全量编译，记录工具链、源码与产物身份（见下节）。仍未做：该库在任何设备上运行、对任何游戏数据的验证、配套源码打包与发布。
- [ ] 适配原版 `-g`、`-bp`、`-gc`、`-uc` 参数：宿主 APK 已重写 `getArguments()` 传这些参数（代码已写，未运行、未验证），不发送自定义 `--tyranor-resource-root`。
- [ ] 宿主提供 `getChosenDirectory`、`chooseDirectory`、`resetChosenDir`：宿主 APK 已重写，数据根指向按游戏隔离的目录（代码已写，未运行、未验证）。CWD、配置回退及 ImGui 相对写入位置仍由上游默认行为决定。
- [ ] 原版 SDL3 `org.libsdl.app` 与 TYN 内置 SDL2 的同名类冲突：设计上改为独立宿主 APK 承载，主 APK 不含 SDL3 Java 层；宿主尚未构建成 APK，冲突是否消除未经验证。
- [ ] 完成资源验证和持久 TOML 管理：存档与 `userconfig.toml` 已按游戏隔离（`games/<gameKey>/`），但在宿主私有目录内，主 App 无法管理；未验证。
- [ ] 接入扫描、外置模块启动、三语言文案和引擎图标：代码已写，主 App 编译与新增单测通过；Impacto 以外置 APK 承载，不走运行库分片。对已有引擎未见行为改动，但未逐一验证；完整 JVM 回归在本机有 15 个与本改动无关、干净 HEAD 上同样出现的 Windows 环境失败。
- [x] 只读核对现有 Windows／Steam 样本的容器、脚本头和资源格式（见上节）；结论是它与上游 PS3 配置不一致，不能直接使用。
- [ ] 为该样本建立 TYN 自有、标明实验性的 PC profile（图集、字体、MSB、语言、立绘），再验证原版引擎能否读资源并解析脚本。
- [ ] 完成游戏数据的标题、正文、声音、输入、存读档、前后台及退出重启验收（上游支持的是 PS3 数据；PC 样本是否可行尚无证据）。
- [ ] 完成 API 28、16 KiB 页设备与 minified Release 检查。

引擎已作为外置模块接入，下载走宿主仓库最新发布；但真实游戏测试没有完成：没有受支持的游戏数据进到画面，渲染、声音、输入、存读档均未验证。

## 原版 ARM64 全量构建（2026-11-14，WSL Ubuntu 24.04）

用 `tools/impacto/build-upstream.sh` 从固定提交 `ba0b6e08…`（tree `a2a0b799…`，洁净）全量编译，vcpkg 固定在 `fa9a5b33…`，依赖含 SDL3 3.4.10、FFmpeg 等，退出码 0。

| 项目 | 结果 |
| --- | --- |
| 工具链 | NDK **27.3.13750724**（r27d）、CMake 3.31.1，均按官方清单的哈希校验；见下文关于 NDK 的说明 |
| 产物 | `libimpacto.so`，SHA-256 `732c9b86ff558622c39bb0825b20f0efb7ac8f986b4eb97d67887004699cd15e`，GNU build-id `8cc95bb0…`，未剥离 337 MiB（`.debug_*` 约 310 MiB），剥离后约 34 MiB |
| ELF | `LOAD` 对齐 16 KiB；导出 `JNI_OnLoad`、`SDL_main` 与 67 个 `Java_org_libsdl_app_*`（SDL3 已静态编入）；`NEEDED` 均为系统库（`libGLESv3`、`libEGL`、`libandroid`、`libmediandk`、`libvulkan`、`libOpenSLES`、`liblog`、`libc`、`libm`、`libdl`） |
| 补丁 | 无 TYN 引擎补丁，无依赖补丁；上游依赖配方保持原样 |
| 候选状态 | `candidate-not-integrated`；`hostIntegrated`/`gameplayTested`/`correspondingSourcesPackaged`/`releaseReady` 均为 false |

**NDK 说明**：来源锁原固定为 NDK 28.0.13004108，用它做全量构建时 vcpkg 依赖都编好了，但上游源码在 `fsfolderarchive.h`（前向声明的 `TextMetaEntry` 放在 `std::vector` 里）报 `incomplete type`。上游官方 CI 在 `ubuntu-24.04` 上用 runner 默认的 NDK 27.3.13750724（runner 镜像 20261111.370.1 的说明可查），改用它后编译通过，因此来源锁改为该版本。这是宿主侧工具链版本的选择，没有改动上游源码；它支持“r28 的 libc++ 不接受该处写法”这一判断，但我没有单独在 r28 上做最小复现。

**重试须知**：构建过程中 vcpkg 曾因代理 `SSL connect error` 下载失败（网络问题，非编译问题）。脚本要求重试换全新构建根，本次重试只带入 `downloads/`（vcpkg 按哈希校验的源码包），未复用任何构建或二进制缓存。

**打包**：宿主构建在 `stageImpactoLibrary` 里用该 NDK 的 `llvm-strip --strip-unneeded` 显式剥离，并确认 GNU build-id 与候选库一致、无 `.debug_*` 残留。不能依赖 AGP 自带的剥离：它找不到可用 NDK 时只给出 `Unable to strip` 警告，实测会把 337 MiB 的未剥离库原样打进 423 MB 的 APK；显式剥离后 APK 为 120 MB。工作流对最终 APK 用 build-id 校验“确为该候选库”，并拒绝残留调试段、体积异常或 debug 签名。

## 主 App 与宿主 APK 接入

主 App 把 MAGES. 引擎（经 impacto 项目的宿主 APK）当作外置 APK 引擎（与 Ren'Py、Ruffle 同一机制）：

- `EngineType.IMPACTO` 属 GAL 分类，卡片图标为 `ic_engine_card_impacto`，封面色 `#B3123A`。
- 扫描：`ImpactoGameCatalog` 按**精确大小写**的根目录条目名识别上游 profile（`chlcc`、`cclcc`、`cclcc-switch`、`mo6tw`、`dash`、`rne`），恰好一个 profile 全部命中才识别；`darling` 的要求集合与全小写的 mo6tw 完全相同，无法区分，所以这种布局一律不识别，不猜；`launchTarget` 保存 profile id（即 `-g` 参数）。影片档不作为特征。该表由 `tools/impacto/check-game-catalog.py` 对照固定提交（见宿主仓库的 `source-lock.json`）的 `gamedefinitions.lua` 和各 `vfs.lua` 校验。
- 现有 Windows／Steam 样本（小写 `.cpk`、`script.cpk`、无 `SCRIPT.cls`）**不会**被识别为 Impacto，也不会被误判成大写布局的 `chlcc`。
- 启动：`ImpactoExternalEngineModule` 发 `com.tyranor.next.impacto.action.RUN`，携带游戏目录、profile、`gameKey` 与标题；游戏目录需要主 App 已取得的路径与「所有文件访问」。宿主未安装或版本低于 `ImpactoHostContract.VERSION_CODE` 时，提示并引导下载宿主仓库最新发布的 APK（`releases/latest/download/Tyranor-Next-MAGES.apk`，资产名固定，新版本发布后自动指向）。
- 存档：存在于宿主 APK 的内部私有目录（`files/games/<gameKey>/saves`），主 App 不提供文件存档管理；设置见 SPEC：全局与单游戏覆盖，启动时经 `SETTINGS` extra 下发给宿主（0.1.2 起）。
- 宿主 APK 见 [yuukana1/Tyranor-Next-MAGES](https://github.com/yuukana1/Tyranor-Next-MAGES)：宿主的 Java 层只用上游公开钩子，直接引用已校验的上游 Java 源码（SDL3 的 `org.libsdl.app`），不复制、不修改；引擎与 profile 的改动只存在于补丁系列里；需要候选库 `libimpacto.so` 才能打包，只能在 Linux（或 WSL）构建。**不内置进 Tyranor Next**：宿主 APK 约 90 MB（其中 CJK 字体与各游戏字体贴图占大头，均已是压缩格式），内置会让所有用户多下载约 100 MB；且上游自带的 SDL3 `org.libsdl.app` 与 TYN 内置的 SDL2 同名，代码无法合并进主 APK。获取方式与其他外置 APK 模块相同：该仓库的 CI 构建、发布到 GitHub Release（已发布，下载链接始终指向最新版（0.1.1 起宿主应用名为“MAGES.（impacto）”）），引擎页点击跳转下载最新版 APK。

（写于 2026-11-14）这是接入代码，**不是兼容性验收**：带真实引擎库的宿主 APK 已能在电脑上打包，但没有装到任何设备上，引擎没有运行过，也没有对任何游戏数据验证过。之后的真机记录见下文各节。

### 真机核对（2026-11-14，一台设备，引擎库缺席）

设备 PPG-AN00，Android 17（SDK 37），arm64-v8a。主 App 用隔离包名 `com.tyranor.next.impactotest` 安装；宿主用 `-PimpactoHostOnly=true` 打出的 `0.1.0-hostonly` 诊断包（不含 `libimpacto.so`，无法启动引擎）。数据是自建的零字节占位文件，不含任何游戏内容。

| 项目 | 结果 |
| --- | --- |
| 主 App 启动、引擎页 | 正常；多出一张 Impacto 卡片，用指定的齿轮图，红叉表示宿主未安装；点开显示“模块未安装” |
| 缺“所有文件访问”时发起请求 | 宿主提示并跳转系统授权页 |
| 合法请求 | 入口 Activity 校验通过，建出内部数据根，`gamedata/mo6tw` 链接可读到外部目录的 9 个条目；游戏 Activity 在独立 `:game` 进程里启动上游 `SDLActivity`，仅因诊断包没有库而报 `libimpacto.so not found` |
| 4 种恶意请求（他人私有目录、错误 key、未知 profile、系统路径） | 全部被拒，游戏 Activity 未启动 |
| 点 Exit | `:game` 进程结束，入口进程和主 App 无崩溃 |

真机发现并已修复的问题：**外部存储（含应用自己的 `Android/data`）是 FUSE，拒绝创建符号链接**（`createSymbolicLink` 抛异常，shell 的 `ln -s` 也被拒），原设计把数据根放在外部目录会失败，现改为放在宿主内部存储。此外，一个遗留在 `build/` 里的假库曾被诊断包误打进 APK，现在诊断包不再引用该目录。

**未覆盖**：引擎启动、任何游戏数据、画面/声音/输入、存读档、从主 App 的游戏库点击启动的完整链路（用 `am start` 模拟了同样的 Intent，识别扫描用单测而非真机覆盖）、其他 Android 版本和机型。

### 真机运行（2026-11-15，带引擎库的发布版宿主 + Windows／Steam 样本）

宿主为 GitHub 发布的 `impacto-host-0.1.0`（签名 APK，含 `libimpacto.so`），设备同上（PPG-AN00，Android 17）。样本原件未改动；手机上只放了三个改成大写名的拷贝（`SYSTEM.CPK`、`MASK.CPK`、`SE.CPK`，约 94 MB），用 `am start` 发与主 App 相同的 Intent，profile 为 `mo6tw`。

| 项目 | 结果 |
| --- | --- |
| 引擎库加载、`SDL_main`、窗口与音频 | 成功；两个进程都保持运行 |
| 读到游戏数据 | 经 `gamedata/mo6tw` 链接找到已拷的三个档案；未拷的 `BG`/`BGM`/`CHARA`/`MOVIE`/`VOICE` 与 `SCRIPT.cls` 报缺失 |
| 脚本 | `[VM] Could not read script file for 0`，引擎不再前进，画面全黑 |
| 其他 | `resources/mo6tw/icondata/*.png` 不存在（上游自身没有该目录，仅警告）；日志里有一条 `Could not initialize OpenGL / GLES library`，随后引擎继续运行，但因黑屏无法确认 GL 是否真正初始化成功 |

结论：这是引擎库第一次在设备上运行，到上游启动流程的脚本读取为止；**没有任何游戏进到画面**。上游 `mo6tw` 配置要 PS3 版 `SCRIPT.cls`，该样本是 PC 版（`script.cpk`、SC3 `.scx` 加 `.msb`、PNG 图集），需要 TYN 自有、标明实验性的 PC profile 才可能继续，目前没有证据表明可行。渲染、声音、输入、存读档均未验证。

### 真机 App 层核对（2026-11-15，合并上游 main 后的主 App 测试包 + 已发布宿主）

主 App 用隔离包名 `com.tyranor.next.impactotest` 的 debug 包；宿主为发布的 `impacto-host-0.1.0`。游戏目录是自建的占位目录（`mo6tw` 布局，三个真实小档案的改名拷贝 + 零字节占位），从 App 的「输入路径」添加并扫描。

| 项目 | 结果 |
| --- | --- |
| 引擎页显示 | Impacto 卡片带指定图标；宿主未安装为红 ✕、已安装为蓝 ✓；弹窗条目“模块未安装或需要更新” |
| 引擎页入口 | 点弹窗条目跳转到宿主仓库最新发布的 APK 直链，浏览器开始下载 |
| 游戏库 | 扫描识别为 “Memories Off 6 ~T-Wave~”，深红 Impacto 占位封面；点卡片出操作面板（搜索封面 / 引擎设置 / 启动游戏）；“引擎设置”为说明页 |
| 启动 | “启动游戏”拉起宿主，`TyranorImpactoActivity` 到前台，引擎读取目录；对零字节占位文件报 `Invalid argument`（预期） |
| 桌面入口 | 宿主在手机上没有任何启动器入口，仅有 `ImpactoLaunchActivity` 响应 `RUN` |

**发现并修复的问题**：主 App 清单 `<queries>` 缺宿主包名 `com.tyranor.next.impacto`，Android 11+ 下 App 看不到宿主，装了也显示“未安装”（此前真机核对用 `am start` 绕过了 App，没有发现）。已补声明，并新增 `ExternalEngineManifestQueriesTest`：要求注册表里每个外置模块的包名都在 `<queries>` 中（撤掉修复时它会失败）。

**仍未覆盖**：引擎渲染、声音、输入、存读档；没有受支持的游戏数据进到画面。

## 本轮检查

- 20 项真实 Git 仓库回归通过，包含“父仓库冒充源码 checkout”和 Git replace refs 替换锁定提交的拒绝测试。
- 3 项 Git 环境隔离回归通过，真实临时调用方仓库的工作文件、配置、索引、引用及对象均保持不变。
- 7 项构建前置检查回归通过，覆盖缺失工具、错误 NDK、依赖缓存污染和重复构建；不会下载或编译依赖。
- 增加 Linux 专用父进程锁继承 CLI 集成检查；Windows 本地跳过，由 Linux CI 运行。
- 17 项候选检查和发布回归通过，覆盖 ELF 边界、源库并发替换、无效快照、构建/发布共用互斥、父进程锁继承及失败释放；不是完整运行库验收。
- CMake/Ninja 定位的 8 项回归、Bash 语法检查及 CMake 离线依赖声明覆盖检查通过。
- CPK 检查回归：`test-inspect-cpk.py` 的 14 项合成档案用例通过，覆盖干净布局、偏移错位、列类型不符、按魔数而非标志位解掩码、无 ITOC／ITOC 损坏、`Files` 不符与重复 ID、`@UTF` 版本字段、未标记的 LAYLA 成员、恶意表头不崩溃不挂死、截断、非 CPK 和“不改动样本”；它们只验证检查工具，不涉及游戏兼容性。
- 官方固定 checkout 的 tree 为 `a2a0b7996a46c5d067efbeb8747cef103a69b157`，Git 洁净检查通过。
- **NDK 28.0.13004108 编不过原版**：2026-11-14 在 WSL（Ubuntu 24.04）里用该 NDK 做真实全量构建，vcpkg 依赖（含 SDL3、FFmpeg）全部编好，但原版 `src/io/fsfolderarchive.h` 的 `std::vector<TextMetaEntry>`（前向声明的不完整类型）在 r28 的 libc++ 下报 `incomplete type`，`log.cpp`、`main.cpp` 等编译失败。上游官方 CI 用的是 runner 默认的 NDK 27.3.13750724，因此来源锁改为该版本；这是改宿主侧工具链版本，没有改动上游源码。下一条的"诊断性编译通过"是用 NDK 28 做的单文件探针，不能说明全量构建可行。
- 用 NDK 28 对原版 `src/io/vfs.cpp`、`src/io/fsfolderarchive.cpp` 执行语法与 ARM64 对象编译均通过。为隔离诊断，外部探针配置禁用了 OpenGL/ImGui 并补齐正常 PCH 标准头；不是完整 CMake 构建。外部直接析构 `FSFolderArchive` 的合成探针会触发不完整类型错误，但不能据此认定当前上游构建必然失败，不能作为恢复引擎补丁的理由。
- 带引擎库的宿主 APK 尚未装到任何设备；引擎库未在设备上加载，真实游戏未验收。宿主 Java 层与主 App 入口已在一台真机上核对（见上节）。
