# Ethornell / BGI（ethornell-rs）Android 化与内置引擎接入计划

> 状态：**实验接入已实施，部分设备验收通过，游戏兼容尚未闭环**。§0.3是当前矩阵，§12.5是实施后的完成清单；§2及文末调研记录明确保留历史时点，不能把整篇后续内容一概视为未实施原案。
> 调查日期：2026-10-15。
> TyranorNext 调研基线：`7a3f007cefe634f63c054730fd90c21ed5185352`；当前工作区已有未提交实施改动。
> ethornell-rs 调研基线：`5556c36b53c9af29a19ba507ffa431b19300f0e7`；实施基于该 revision 的 dirty 工作区，不等于该提交的原样产物。
> 本地引擎仓库：`/Users/weiss/github- engine/ethornell-rs`（目录名包含空格）。

## 阅读导航

- 决策与范围：§0–§3；验证记录与复现命令：§2。
- 引擎改造：§4；C ABI / JNI / 线程与生命周期：§5。
- GPU / 字体 / 输入 / 平台请求：§6；音频与多媒体：§7。
- 文件 / 存档 / 权限：§8；TyranorNext 精确改动清单：§9。
- 构建与许可：§10；里程碑 / 工作量：§11。
- 测试与发布完成定义：§12；风险回退 / 待决事项 / 参考：§13–§15。
- §0.3 与[测试验收记录](Ethornell测试验收记录.md)记录实施状态；文末保留原调研与独立审查记录，不将它们冒充实施验收。

## 0. 执行摘要

**已按“共用运行前端 + host-driven C ABI + Android SurfaceView宿主”实施并通过部分设备验收；当前阻塞是商业样本首标题/正文/槽位闭环，不再是运行库不可加载。** 原调研否决的无游戏API探针仍不是可交付产品，不能与当前宿主运行库混淆。

必须把以下五件事分开：

1. Rust 依赖与源码能对 Android 做类型检查。
2. Android `.so` 能完成代码生成与链接。
3. TyranorNext 能加载运行库，并通过真实接口控制它。
4. Android 设备能运行指定游戏并通过生命周期、输入、音画与存档测试。
5. 有足够游戏样本和发布合规依据，可以对外声明支持范围。

第1–3项已有实施与真实加载/控制证据；第4项仅部分fixture、合成存读档与正常退出压力通过，第5项未完成。§2保留早期探针历史，当前证据见§0.3和§12.5。不得把局部设备成功或家族识别等同于商业样本完整兼容。

### 0.1 推荐方案与首版边界

| 项目 | 建议 |
|---|---|
| 运行方式 | 内置引擎；Android Activity 在独立 `:ethornell` 进程内承载；独立进程不是安全沙箱 |
| 目标 ABI / 系统 | 首版 `arm64-v8a` / API 26+，不提高 App `minSdk`；x86_64 仅可选测试目标 |
| 原生库 | 已接入`libethornell.so`与项目构建的`libethornell_bridge.so`，最终候选仍须固定输入/hash并复验 |
| 渲染 | 保留 `wgpu 0.19` 主渲染器，增加 Android `ANativeWindow` surface；Vulkan 优先，GLES 独立验证 |
| 主循环 | Android 侧专用引擎线程串行持有运行时；Choreographer 仅提交节拍，不在 UI 线程执行资源加载/VM/GPU 等待 |
| 音频 | 优先复用 Kira → CPAL → Oboe；宿主补 `ndk-context` 初始化、音频焦点、挂起/恢复与路由变化 |
| 资源路径 | 首版要求可访问的真实本地目录；复用 App 现有授权流程，不把 `content://` 当文件路径 |
| 存档 | 必须先完成读写路径调查与样本验证；不先假定为 `<游戏根>/save`，更不能让镜像功能递归整个游戏根 |
| 游戏兼容 | 首批使用少量合法持有、桌面基线可复现的原版/汉化样本建立白名单矩阵 |
| 发布门槛 | 宿主 ABI、字体、输入、Surface 恢复、存档闭环、安全边界、许可审查均完成 |

### 0.2 优先级

- **P0 / Go-No-Go**：宿主解耦、最小 C ABI、Surface 所有权、字体与文本、VM 边界、路径/存档安全、许可证据。
- **P1 / 首版必需**：扫描到启动全链路、基础输入与返回、音频焦点、后台恢复、逐游戏兼容验证、可追溯构建与 release APK。
- **P2 / 条件增强**：完整 IME、手柄高级映射、独立存档迁移、SAF 原生 VFS、额外视频后端、超分等。
- P2 不意味着“遇到调用可以假装成功”：首批游戏若依赖某项，必须提前实现，或明确把该游戏排除在支持范围外。

### 0.3 当前验收矩阵（2026-10-16，draft）

**当前`9e2fef…`：中文标题/两句正文/正常退出已通过；真实slot数据准备/保存流程尚未闭环，无date/thumbnail/file、重启读回未成，仍禁管理。** 空slot无需确认可正常，不以此判失败；当前复核跨VM procedure双输出/共享heap，不硬写ready或猜placeholder根因。available=false、M5/发布未过。

| 范围 | 当前verified | remaining / 状态 |
|---|---|---|
| 运行时与App接入（M1/M4） | code1002集成候选已获父exact APK native静态及full JVM构建14s通过、cache reused，真实Android gate trace已取到 | 仅构建/诊断验证，不等商业修复；最终关trace并按固定hash重验，不跨候选借用结果 |
| 原创核心fixture（M2） | API36/SJIS、16KB/SJIS与GBK各 **4 pass / 1 optional skip**；RTLD_NOW/14ABI、UTF-8/thread/token、BP Closed、像素/重挂与已覆盖glyph/render fixture通过 | 不是完整字体/文本验收：真实game文本未画；API26加载/create/thread过，2项GPU为该硬件stage2 `adapter_unavailable`，不能泛化所有API26设备 |
| Surface/暂停/退出重入（M3） | 16KB像素/重挂20轮、实机正常close20会话、两环境pause3/3及dispatch3/3通过 | pause为直接JNI+Kira API，不含硬件录音/Activity onPause；dispatch只覆盖已列三例，不等完整故障/路由/小时级soak |
| 真实游戏（M2/M5） | 中文标题/两句正文/正常退出/三critical文件证据保持，真实slot输入链已验证 | **slot数据准备/保存未闭环**，无date/thumbnail/file、重启读回未成；数据生产者涉及跨VM procedure双输出/共享heap，尚待真实结果，不把堆指针当bool或硬写ready |
| 真实游戏正常退出 | 父手动Back→宿主确认→游戏自绘YES/NO→YES→App通过，非kill | 仅已观察正常退出路径；不将旧不明回App推断为native crash，不代替slot/重读或小时级生命周期 |
| 私有用户数据/存档（M3/M4） | SafeStore两环境6/6、VM三会话持久化通过；dispatch实机3/3（4.541s）、16KB3/3（12.971s）均过，protocol层两平台闭环；flag聚焦4suites/42tests零失败 | HOST_DISPATCH_PROTOCOL已true，DEVICE_SAVE_IO=false、available=false；商业slot激活/提交仍主P1，管理不开放，不再把protocol写待适配 |
| 自动测试与审查 | 父最新App567tests（0fail、1skip）、engine250（0fail）；script22/VM209、tools75及Release/pause/dispatch设备证据保持；App/native基础层主要完成 | 不累加旧计数；callee/profile与DA源码尚须freeze后统一build；商业slot、音频听感/蓝牙、小时soak、多GPU/公开许可未过，full plan不done |
| 兼容与发布（M5） | Release R8+resource shrink构建44s通过；16KB matching纯Java probe **3/3通过**（identity、RTLD_NOW/14ABI/build_info、productionJNI create/poll/destroy），精确APK库/zipalign有证据 | scope仅`release-native-load-create-only`，不含GPU/title/商业slot；真实game首轮语言ABI/API26GPU/长稳等未闭环，**source许可/公开发布独立blocked** |

版本ABI修复采用显式会话profile，保留Tayutama695 null handlers，不全局篡改table；该约束已有owner与VM只读复核确认，修复后的真实分支仍待新候选验证。

旧组合回归归属原hash，不挪用。当前code1002只读gate trace已排除guest写回与等待门，转追返回contract；诊断不改guest，不扩能力。最终disable trace重建，experimental/许可blocked不变。

保存排除细节留验收时间线；当前只保“真实slot数据准备/保存流程未闭环”。独立producer复核已纠正ready实为堆指针而非bool，未见先前猜测的调用，不预设empty-placeholder根因。owner与只读复核继续跨VM双输出/共享heap，不硬写状态。

最新父复验：protocol flag变更后full App/engine unit+strings构建24s通过、cache stored；当前Debug APK精确native静态检查、tools75与actionlint重复通过。真实游戏手动正常退出后可再次正常App启动、不再busy。仅余商业SAVE事件激活/提交仍在trace（含独立输入路径复核），无slot成功证据；不因已投入时间降低完整目标门槛。调查日期保留10-15，跨日证据以日志执行时间为准。

M0–M5原阶段门槛继续有效；下文保留标明“调查/探针/拟议”的原始设计取证，不能据此否认§0.3与§12.5已验证的实施结果。候选旧hash/字节/失败仅见[验收时间线](Ethornell测试验收记录.md)，顶层仅保当前候选。仍为draft，继续以首标题、正文与商业存读档闭环为目标。

## 1. 项目基线与证据使用方式

### 1.1 TyranorNext 的现有承载结构

项目不是单一引擎 Android 壳，而是多引擎游戏库与启动器。职责边界必须保持：

```text
app/ui：游戏库、引擎页、设置页、错误文案、用户操作
  ↓
app/core：扫描、配置解析、启动编排、存档镜像
  ↓
engine：Activity 宿主、输入层、JNI/C ABI、原生引擎库
```

- 根 `settings.gradle.kts:32–34` 仅含 `:app` 与 `:engine`。
- `engine/build.gradle:5–36`：compileSdk 36、minSdk 26、NDK `28.0.13004108`、CMake `3.22.1`，CMake ABI 限 `arm64-v8a`。
- `app/build.gradle.kts:178–190`：compileSdk 37.0、targetSdk 36、minSdk 26；不应把 engine 的 compileSdk 与 App 的 compileSdk 混写。
- `engine/src/main/cpp/CMakeLists.txt:4–20`：已有 Siglus/FVP 等 JNI shim。
- `engine/rust/Cargo.toml:9–15`、`engine/src/main/java/com/core/archive/Xp3Core.kt:9–40`：已有 Rust `cdylib` + JNI 的工具库先例，但它不是完整游戏宿主模板。
- 内置 `.so` 已按 ABI 存在于 `engine/src/main/jniLibs/arm64-v8a/`；另一部分引擎采用 nativeplugin ZIP，不应混淆两条分发链。

本文仅提出新引擎建议，不把它提前加入 `CONTEXT.md` 的“已支持 EngineType / 内置引擎”列表。真正实施并确定命名、行为后，同步更新领域术语；本轮不强建 ADR。

### 1.2 引擎分层与依赖

`ethornell-rs` 是 Ethornell / BURIKO General Interpreter（BGI）重实现，不是 Windows `.exe` 执行器，也不是 Winlator 插件。

```text
ethornell-cli
  └─ ethornell-app：bootstrap、VM native API、场景/图形/声音调度、桌面/headless 前端
      ├─ ethornell-core / archive / image / script / vm
      ├─ ethornell-render：wgpu 0.19.4 + winit 0.31.0-beta.3 + ab_glyph
      ├─ ethornell-audio：Kira 0.8.7 → CPAL 0.15.3 → Android Oboe
      ├─ na_mpeg2_decoder（video-only）
      └─ Ruffle v0.4.1：ruffle_core + ruffle_render_wgpu → wgpu 27.0.1
```

注意：**主画面 `wgpu 0.19.4` 与 Ruffle `wgpu 27.0.1` 同时出现在依赖图**，不是 Cargo 错误，也不能直接混用两者的 Device/Texture 类型。不能为了 Android 移植顺便统一大版本，造成额外回归面。

`crates/na_wmv_player` 虽为 workspace 成员并有 `cdylib/staticlib` 配置，但不应仅凭这一点就声称主运行时支持 WMV 或需要随主库打包 `libwmv_decoder.so`。实际电影调用链、构建输出与 `DT_NEEDED` 必须分别检查。

### 1.3 关键源码锚点（以下 E 路径相对 ethornell-rs）

| 编号 | 证据 | 含义 |
|---|---|---|
| E1 | `Cargo.toml:1–43`、各 crate `Cargo.toml` | edition 2024、resolver 3、workspace 与依赖；主运行时尚无 Android ABI crate |
| E2 | `crates/ethornell-app/src/lib.rs:167–255` | `run(AppConfig)` 从资源 bootstrap 后进入 headless/window；不是宿主逐帧 API |
| E3 | 同文件 `8112–8293` | `RuntimeEngine` 私有；已有单步调度基础，但需要抽取可供宿主控制的边界 |
| E4 | `crates/ethornell-app/src/runtime_frontend.rs:3–31` | FIFO 输入 → 帧推进 → 声音命令执行，可复用，不能丢失现有次序 |
| E5 | `crates/ethornell-app/src/window.rs:315–722` | 调度、窗口请求、纹理版本更新、绘制列表、文字/ruby、present 仍集中在桌面前端 |
| E6 | 同文件 `804–845` | `EventLoop::new()` + `run_app`；没有传入 AndroidApp 的入口 |
| E7 | `crates/ethornell-render/src/lib.rs:185–228` | Surface 绑定 winit Window；`Limits::default()` 与 surface capabilities 选择需要移动端核验 |
| E8 | 同文件 `996–1008,1125–1138` | 字体候选仅 macOS/Linux/Windows，没有 Android 字体路径 |
| E9 | `crates/ethornell-app/src/timing.rs:1–32` | 16ms 节奏、超过 500ms 的间隔计为暂停；不能套用别的引擎时间策略 |
| E10 | `crates/ethornell-audio/src/lib.rs:111–127,240–307` | AudioManager 初始化、整段音频解码、通道播放/暂停；无现成 Android 焦点状态机 |
| E11 | `crates/ethornell-app/src/platform.rs:71–100` | Android 会落入 `xdg-open` 分支，需要改成受控宿主请求 |
| E12 | `.github/workflows/rust.yml`、`release.yml` | 已有 CI/发布面向桌面，不能作为 Android 实机兼容证据 |

## 2. 本次验证记录与可复现命令

### 2.1 验证分级

| 项目 | 本次状态 | 能说明什么 / 不能说明什么 |
|---|---|---|
| 两个源码 checkout | 开始调查时均干净，基线见页首 | 本轮不修改引擎源码或 App 业务代码 |
| Android CLI 项目描述 | 成功识别 `:app`、`:engine`，发现已有 debug/release APK | 不是本轮重新 assemble，也不是新引擎 APK |
| 本机 Rust target | 已安装 `aarch64-linux-android` 等 | 仅工具链条件 |
| 默认 Android `cargo check` | 配好 NDK 后仍失败：缺 android-activity feature | 是默认配置缺口，不证明代码不能移植 |
| 补 `winit/android-native-activity` 后的 Android arm64 `cargo check` | **通过，26.28s；ethornell-app 报 113 条 warning** | 源码与依赖可完成该目标的检查，不代表代码生成、可加载宿主或游戏可运行 |
| Android release `.so` 代码生成/链接探针 | **成功，3m38s；但有未解析 android_main、无游戏 API** | 只证明可生成 ELF；该产物不得交付或用于估算完整引擎体积 |
| macOS 宿主单测（archive/script/vm） | **5 + 22 + 188 = 215 项通过，0 失败** | 不覆盖 app/渲染/音频/真实游戏，也不是 Android target 上运行的测试 |
| JNI 创建会话、Surface 渲染、音频、读写存档 | **未执行** | 需要新增宿主与实机/模拟器验证 |
| Android 设备上的游戏通关 / 跨版本兼容 | **未执行** | 无“全 BGI 游戏支持”结论 |

### 2.2 不修改源码的 Android 检查命令（已执行）

```bash
# 在 ethornell-rs 根执行；NDK 路径按环境指定。
export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/28.0.13004108"
export NDK_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin"
export CC="$NDK_BIN/aarch64-linux-android26-clang"
export CXX="$NDK_BIN/aarch64-linux-android26-clang++"
export AR="$NDK_BIN/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CC"

cargo check -p ethornell-app --target aarch64-linux-android \
  --locked --features winit/android-native-activity
```

环境：macOS，Rust/Cargo `1.96.0`，NDK `28.0.13004108`，API 26。该 Rust 版本是**本机验证版本，不是已确认的最低 Rust 版本**；edition 2024、resolver 3 和锁定依赖各自也有 MSRV 要求。

最早的 `oboe-sys` 错误先后为找不到 `aarch64-linux-android-clang++`、`aarch64-linux-android-ar`，设置 `CXX` 与 `AR` 后消失。不得把工具定位问题记录为音频后端不支持 Android。

这条 feature 只让现有 winit 桌面依赖能检查通过。`winit-android 0.31.0-beta.3` 源码 `event_loop.rs:138–141` 仍要求通过 `with_android_app()` 传入 `AndroidApp`；当前 `window.rs` 没有这样做，不能从 JNI 直接调用 `run()` 来绕开它。

### 2.3 release 链接探针（仅调查，不作为产品产物）

```bash
# 沿用上面的临时 NDK 环境；不编辑 Cargo.toml。
cargo rustc -p ethornell-app --target aarch64-linux-android --locked \
  --features winit/android-native-activity --lib --crate-type cdylib --release -j 4
```

实际生成 `target/aarch64-linux-android/release/libethornell_app.so`，文件大小 **1,278,704 B**，ELF64 AArch64；SHA-256：

```text
0b48dedb6ae6d7afd0f14124d55fa5a75034726568b597f3aab80769772be41d
```

`llvm-readelf` / `llvm-nm` 检查结果：

- `DT_NEEDED`：`libandroid.so`、`libdl.so`、`liblog.so`、`libc.so`；Android ident note 标记 API 26 / NDK r28。
- 导出 `ANativeActivity_onCreate` 与若干 wgpu C 函数，但**没有 `ethornell_android_*`、JNI 游戏入口或可驱动游戏的 C API**。
- 未定义符号中含 **`android_main`**；当前库没有实现该入口，不能把链接成功当作 RTLD_NOW 加载通过。
- 由于无游戏 C ABI 导出根，链接器可剔除未被根引用的运行时逻辑；**该约 1.22MiB 的大小、NEEDED 列表不能代表正式引擎**，也不能说明图形/音频只需上述系统库。
- LOAD `p_align` 都为 `0x4000`；但本次 GNU_RELRO 为 `VirtAddr=0xbd3e0, MemSiz=0x5c20`，结束地址 `0xc3000` **不是 16KB 边界**。按 §15 官方指南的检查方式，此探针尚不能标记完整 16KB 合规，正式库需复查 Rust/链接器 common-page-size 与 RELRO 布局，并在 16KB 环境验证。

结论：代码生成/ELF 链接不是首要无解障碍；**稳定导出真正引用运行时的 host-driven API、去除错误 activity glue，并通过动态加载/页大小检查才是下一道门槛**。本轮不把该探针复制到 TyranorNext，也未作 Android 设备加载。

### 2.4 宿主单测（已执行）

```bash
cargo test -p ethornell-archive -p ethornell-script -p ethornell-vm --lib --locked -j 4
```

在本机 macOS target 运行，archive 5、script 22、vm 188 项通过，无失败。它是后续解耦回归可参考的一组基线，不是整个 workspace 测试，更不是 Android 实机正确性的替代。

## 3. 路线比较与建议架构

### 3.1 路线比较

| 路线 | 优点 | 主要代价 | 建议 |
|---|---|---|---|
| winit + NativeActivity/GameActivity | 延续上游窗口循环，做独立测试 APK 相对直接 | 补 android_main/AndroidApp、触屏与生命周期；需重新对接 App 输入层/设置/Surface；不能直接套普通 Activity JNI | 保留作独立参考 harness，不作为首选产品宿主 |
| **host-driven C ABI + 普通 Activity + SurfaceView** | 对齐本项目已有 Siglus/FVP 类接入形态；宿主可控、接口稳定；不依赖 winit Android glue | 需抽取帧前端、补 Renderer surface 创建、正式生命周期和 platform services | **推荐** |
| 软件帧缓冲 → Bitmap | 可复用项目 Framebuffer 宿主概念 | 当前主渲染器是 GPU，不存在可直接复用的完整 CPU 输出后端；GPU readback→Bitmap 会增加带宽/延迟 | 不作为默认路线，离屏截图仅测试使用 |
| 外置 APK 引擎模块 | 可独立更新、分开安装与授权 | 两套生命周期与授权/存档/版本协议，仍不能免除 Rust Android 化 | 内置体积/维护成本不可接受时再考虑 |

### 3.2 建议的数据流

```text
游戏库点击
  → core/game/launch/EngineLauncher：集中 when 分派 + 生效设置解析 + 存档准备
  → engine/com.core.ethornell.EthornellActivity（拟新增，:ethornell）
      ├─ UI 线程：SurfaceHolder / 输入 / 返回 / 音频焦点 / 宿主弹窗
      ├─ 引擎线程：串行会话命令 + 节拍合并 + 生命周期确认
      └─ NativeEthornell → ethornell_bridge（dlopen + ABI 校验）
          → libethornell.so 的 host-driven API
              ├─ 共用 RuntimeEngine / 输入队列 / 音频命令
              ├─ wgpu Renderer → ANativeWindow
              └─ 受限资源根 + 已确定的用户数据路径
```

**借用既有模式，不复制既有缺陷。** 本方案优先专用线程，不能因其他引擎当前在主线程 step，就把初始化、文件 IO、静态音频解码、GPU present 同步阻塞迁入 UI 线程。`RuntimeEngine` 及渲染器始终由一个所有者线程访问；不得为跨线程移动含非 Send 成员的运行时粗暴添加 `unsafe impl Send/Sync`。

## 4. Rust 引擎改造面

### 4.1 先抽共享前端，再接 Android ABI

建议在引擎仓库实现以下边界；文件名为方案建议：

| 拟议文件/模块 | 职责 |
|---|---|
| `ethornell-app/src/host_runtime.rs` | 共用会话配置、bootstrap、输入入队、tick、退出请求、平台请求队列；从 lib.rs 私有大对象提取稳定外观，不大规模重写 native opcode |
| `ethornell-app/src/frame_presenter.rs` | 抽取 window.rs 的纹理版本、图形命令、层级、clip、blend、文字阴影/ruby 与 modeless 绘制；桌面与 Android 共用 |
| `ethornell-app/src/host_services.rs` | 文件/对话框/打开链接/时间与内存信息等宿主服务；桌面实现保留，Android 实现明确权限边界 |
| `ethornell-render` surface 模块 | 共用 GPU 初始化与命令绘制；分别提供桌面 window 与 Android raw surface 构造/重挂 |
| `crates/ethornell-android/` | 新增薄 `cdylib` crate，库名 ethornell；仅做 ABI 参数/句柄/错误封装、Android context 与宿主桥接 |

独立 crate 不能直接访问 `ethornell-app` 当前私有 `RuntimeEngine`；必须先由 app crate 公开受控的 `HostRuntime` 外观，或把 ABI 模块暂置 app 内。推荐前者，避免 Android 包装把内部 VM 结构全部 public。

### 4.2 Feature 拆分策略

拟议 feature（尚不存在）：`desktop-window`、`host-driven`、`audio-output`、`flash`、`diagnostics`。

要求：

1. 桌面默认能力保持；新 Android crate 选择 host-driven，不引入 winit/android-activity 主循环。
2. 当前 `winit::keyboard::KeyCode` 不只在 window.rs 使用，还出现在 app/lib.rs 与输入处理里。先定义共用键/descriptor 输入，再在桌面边界映射，不能仅给 window.rs 加 `cfg` 就声称依赖已剥离。
3. render crate 的 winit 依赖也要可选，共用渲染命令不可依赖具体 Window。
4. Ruffle 目前是 app 的非 optional 依赖；裁剪 Flash 需要同时 feature-gate 效果运行时及调用点，不是 Cargo 单行改动。
5. 首个完整行为基线优先保留已使用的解码/效果能力。若另做“无 Flash 精简包”衡量体积，必须分别记录能力位与不支持游戏，不能默默禁用效果。
6. 不在这一轮同时升级 wgpu/Kira/winit/Ruffle。先保持锁文件基线，减少故障归因困难。
7. 禁止用进程级 `std::env::set_var` 代替每会话配置；edition 2024 下它还有并发安全要求。配置传 `HostConfig`，桌面环境变量只在入口转换一次。

### 4.3 时间与输入调度契约

- 桌面基线约每 16ms 请求绘制，**每次主循环执行一次 native scheduler pass**；延迟帧不补跑多个虚构 VM 帧（E3/E5/E9）。
- 宿主接收 VSync 后按单调时间与约 16ms 目标节拍决定是否推进；90/120Hz 不得每个回调无条件推进。显示刷新率不等于 VM tick 频率。
- 传真实 elapsed，继续遵守 `normalize_engine_elapsed_ms()` 的 `>500ms → 0` 规则。后台恢复重置宿主时间基准，暂停时不累计游戏/音频/视频时间。
- 引擎线程忙时最多保留一个待处理节拍，合并时间戳，不堆积无限 doFrame Runnable。
- 输入先按 FIFO 消费，再推进 VM，最后执行声音/呈现；点击 down/up、键盘 press/release 不得被简单末值覆盖。
- 仅合并同一指针的连续 move；离焦/暂停/CANCEL 清理按住态，并产生必要的 release/cancel，防止快进卡住。
- 现有 VM watchdog 是指令级上限，不保证一次 native 资源调用耗时有界。增加线程阻塞诊断，后续再按证据拆 IO/解码；不能用 watchdog 冒充 ANR 防护。
- 抽取后先证明 RuntimeEngine 可在创建线程内使用，而不是“建好对象再跨线程搬运”；需要 IO worker 时只传不可变字节/解码结果及有界队列，不让资源线程回调直接修改 VM/GPU 状态。

### 4.4 VM / 脚本的确定事实与针对性修正

**32 位 guest 不要求使用 32 位 Android。** `ethornell-vm/src/lib.rs:82–99` 使用 `Value::Ptr(u32)`，`native_thread.rs:7–17,256–284` 将 CThread 的 32 位指针槽建模为审计句柄；guest 内存通过 tagged-address 与有界 Vec 读写（`lib.rs:6887–6967`），不是直接解引用宿主指针。保留 guest u32 布局与 little-endian，Android 会话 token 独立使用 u64/jlong。

但有以下**代码已可确认、不是泛泛建议**的缺口，需在 M1/M3 增加专项工作和测试（本表路径前缀均为引擎仓库 `crates/`）：

| 缺口 / 证据 | 影响与动作 |
|---|---|
| `ethornell-vm/src/async_program.rs:54–90` 子 VM 从 `Vm::new()` 创建，复制状态未包含 `graph_text_encoding` | GBK 主 VM 的子/孙线程可回落 SJIS；将会话编码传播至全部 VM，测试同一字节串的主/子/孙线程对白、姓名、ruby、bitmap |
| 同文件 `376–394` 子线程 hardcode `fail_on_stub=false, collect_diagnostics=false`；`443–455` 多种错误标为 completed | 不能用主线程 strict 开关证明全会话严格；传播测试策略、汇聚子线程停止原因，避免后台剧情线程静默消失 |
| `ethornell-vm/src/native_call.rs:21360–21379` strict 支持判断接受 Partial | “stub=0 / handler 存在”不等于实现完整；记录实际触发的 Partial、宿主拒绝和结果，建立按游戏/场景的验证 |
| `ethornell-vm/src/lib.rs:56–57,2368–2385` 每 VM 初始 16MiB、可增长至 256MiB；async_program 创建独立 VM 并复制状态 | 单 VM 上限不是进程上限；计数协程、全体 VM 内存、共享 heap、CPU/GPU 资源；做频繁建/销协程压力 |
| `ethornell-vm/src/async_program.rs:534–599` shared heap journal 追加，未见清理/压缩 | 长稳需观察 journal 条数与 bytes；证明所有观察者消费后才可压缩，不能不顾 generation 直接清空 |
| `ethornell-vm/src/lib.rs:1698–1809` memory 查询只实现 linux/macOS/Windows | Rust Android target_os 是 android 而非 linux，当前落为零；补受限宿主查询，区分未知和真实零，验证最低配置探测 |
| 同文件 `1861–1905` 手写 64-bit Unix tm/time/localtime_r FFI | 不把它误报为 ARM64 必崩，但应以目标 libc 类型替代并测本地时间/时区；ARM32 不列首版支持 |
| `ethornell-app/src/lib.rs:1593–1618` 缺脚本可能缓存空程序；`ethornell-vm/src/lib.rs:9157–9172` 某 child-thread 路径构造空程序 | 增加缺资源与空程序 fallback 可见诊断；游戏因跳过程序而“继续”不能计为兼容通过 |

这些问题同时存在于桌面基线，处理时拆成独立提交，明确哪些是“Android 移植”、哪些是“上游运行语义修复”，分别做回归。不要把所有 Partial 一次性补完列作 Android 首帧前置条件；只阻断需要它们的作品/功能声明。

## 5. C ABI、JNI 与线程契约（拟议）

### 5.1 最小 API 面

以下为设计草案，不是现有导出；正式实现以版本化 C 头文件为唯一契约：

```c
uint32_t ethornell_android_abi_version(void);
int32_t  ethornell_android_build_info(char *out, uint32_t capacity);
int32_t  ethornell_android_init_context(void *java_vm, void *global_app_context);

int32_t  ethornell_android_create(const EthornellConfigV1 *config, uint64_t *out_handle);
int32_t  ethornell_android_set_surface(uint64_t handle, void *native_window,
                                     uint32_t width_px, uint32_t height_px,
                                     uint64_t generation);
int32_t  ethornell_android_detach_surface(uint64_t handle, uint64_t generation);
int32_t  ethornell_android_step(uint64_t handle, uint64_t monotonic_time_ns);
int32_t  ethornell_android_input(uint64_t handle, const EthornellInputV1 *input);
int32_t  ethornell_android_set_paused(uint64_t handle, uint32_t reason_mask);
int32_t  ethornell_android_request_close(uint64_t handle);
int32_t  ethornell_android_poll_event(uint64_t handle, EthornellEventV1 *out_event);
int32_t  ethornell_android_complete_request(uint64_t handle, uint64_t request_id,
                                          const EthornellResponseV1 *response);
int32_t  ethornell_android_destroy(uint64_t handle);
```

`ConfigV1` 至少包含 `struct_size`、ABI version、资源根、用户数据策略/目录、图形文本编码、字体路径、后端偏好、诊断开关。字符串定义为**有界 UTF-8 bytes + length**，不跨 ABI 传 Rust String/Vec/enum 或 C++ STL。

错误至少区分：无效句柄、无效配置、ABI 不匹配、资源/脚本缺失、权限/路径拒绝、字体不可用、GPU 初始化/丢失、音频失败、VM 致命停止、未支持宿主能力、内部 panic。状态码和能力位使用固定宽度整数；`bool`/Rust 布局不跨语言默认推断。

### 5.2 所有权与失败处理

- `uint64_t` handle 是带代际的 registry token，不把 Java 可伪造的 `long` 无检查地当 Rust 指针解引用；0 为无效，销毁后旧 token 必须拒绝。
- create 不要求已有 Surface：可先初始化资源/VM，再附着呈现目标。无 Surface 或处于暂停态时不继续推进剧情。
- step、input、pause、surface、destroy 都在同一个引擎线程串行调用。UI 只投递命令与等待必要的 Surface detach 确认。
- C ABI 使用 panic 边界；构建不能改成 `panic=abort` 后仍宣称 catch_unwind 可恢复。捕获 panic 后会话进入 Failed，仅允许取诊断/清理，不继续运行受损状态；OOM/abort/segfault 不在可恢复承诺内。
- JNI 持久保存的是 JavaVM 与有效 GlobalRef，不跨线程缓存 JNIEnv，不保存 local jobject；JNI 异常必须检查。
- `init_context` 在创建音频后端之前执行；ndk-context 在库/进程内统一初始化，最后使用者销毁后才清理。Application Context 不得误持有 Activity。
- Surface 引用统一归 JNI shim 的会话记录管理：`ANativeWindow_fromSurface` 得到持有引用，runtime 仅借用，直到 detach/destroy 确认已 drop 对应 wgpu Surface 后 shim 才 release。set_surface 失败也必须对新旧引用各有确定释放路径；不要 C++ 与 Rust 两边都默认“对方负责 release”。generation 防迟到任务，handle 防销毁后调用，二者不能互相替代。
- 所有临时字符串在调用内复制；如要返回详细诊断，使用 caller-owned buffer/有界事件，不返回已失效 Rust 指针。
- JNI shim 优先 `RegisterNatives`，同步配置消费者 keep rules；若沿用项目现有命名导出，必须用 release R8 验证真实类名与签名。
- JNI String 经过 UTF-16 ↔ 标准 UTF-8 明确转换；`GetStringUTFChars` 是 Modified UTF-8，不得把它无条件当标准 UTF-8 路径，需测试 emoji/补充平面字符与非法 NUL。
- `dlopen` 用本 APK 对应的库位置，`RTLD_LOCAL`，检查所有必需符号与 ABI 版本；不接受来自游戏数据的库路径或加载 Windows 插件。

### 5.3 Surface 状态与同步

```text
New → Initializing → ReadyNoSurface → Running
                                  ↔ Suspended（后台/无 Surface/音频焦点/宿主对话框）
任一非终态 → Closing → Closed
任一执行态 → Failed → Closed
```

暂停原因用 bitmask 独立叠加，不能用一次 resume 清除所有原因。只有 Activity resumed、Surface generation 有效、无阻塞请求且音频策略允许时才能恢复。

`surfaceDestroyed` 必须遵守 Android 规则：**回调返回前，渲染线程已停止访问该 Surface**。仅 `ANativeWindow_acquire` 留住对象引用不能延长系统 Surface 可呈现的生命周期。

实施顺序：

1. UI 关闭该 generation 的节拍入口，并以高优先级投递 detach。
2. 引擎线程停止 acquire/present，丢弃旧 generation 的待处理 resize/step，释放 wgpu Surface；GPU 资源释放遵守后端生命周期。
3. 确认 detach 完成后 JNI 释放 ANativeWindow 对应引用，回调才返回。
4. 新 surfaceCreated/Changed 带新 generation；在同线程重新构造 Surface，重新检查格式/尺寸/兼容 adapter，必要时重建 GPU 并重新上传纹理。

必须专测销毁与慢帧相撞的场景。不得“等待超时后照常访问旧 Surface”；超时属于故障处置路径，应停止会话/按明确定义的进程故障策略退出，而不是继续绘制。也不得 UI 等工作线程、工作线程同步等 UI 弹窗形成互锁。

**高优先级队列不能抢占当前 native 调用**：它只能越过未执行任务，不能中断正在进行的 IO、整段解码或 GPU 等待。M2/M3 应选定有界等待与故障监督机制；未拿到 detach 确认时不能只 `Activity.finish()` 就返回，也不能假定队列内 destroy 已执行。若 native 执行不能及时停止，由**不依赖被阻塞引擎线程**的监督路径终止当前 `:ethornell` 进程（仅此进程，不误杀主进程/其他引擎）；这条异常退路不承诺存档成功。实施后用慢初始化、慢解码、慢 present 注入验证，阈值由实测确定并记录，不以任意常数掩盖持续 ANR。

会话保活仅覆盖进程仍在的 Surface 重建；**进程死亡后不恢复 Rust 内存快照**。重启从既有游戏存档恢复，不伪称 `onSaveInstanceState` 可保存整个 VM。

## 6. 渲染、文本、输入与平台服务

### 6.1 Android GPU 适配

1. 从 `Renderer::new(Arc<dyn Window>)` 拆出“由 surface + dimensions 初始化”路径，Android 使用 raw-window-handle 0.6 的 Android display/window handle；`create_surface_unsafe` 的裸窗口有效期必须覆盖整个 wgpu Surface。
2. 保留 Vulkan/GLES 可选后端，先在真实机型验证 Vulkan；GLES 不能只凭依赖图中出现 glow 就宣称可用。强制后端选项先作为测试参数，默认自动策略在样本验证后确定。
3. 获取 capabilities 时处理空 formats/present/alpha 列表，优选支持的 FIFO；不无条件索引 `[0]`。按 adapter 能力选择移动端 limits，不能 `Limits::default()` 失败后无解释崩溃。
4. 零尺寸、横竖切换、分屏、异形屏、Surface 丢失分别处理。尺寸统一为 Surface **物理像素**；游戏 viewport 按脚本逻辑尺寸 aspect-fit，勿固定所有游戏为 1280×720。
5. 黑边点击不映射到正文；输入使用同一份 viewport 计算。DPI/density 不得在 Kotlin 和 Rust 各乘一次。
6. 抽取 window.rs 中绘制逻辑时完整保留：图形 revision 更新、z 顺序特殊规则、nearest/bilinear、clip、ignore_source_alpha、blend modes、旋转/quad、文字阴影、ruby、modeless 对话框。
7. surface Lost/Outdated 可重建；Timeout 跳帧但不陷入忙循环；OutOfMemory/DeviceLost 需要停止或完整重建的明确策略及上报。
8. Ruffle offscreen 与主画面若保留两套 GPU device，记录读回/上传成本与内存峰值，不把 Texture 跨版本强转。
9. 长时间游玩量测 graph_images、graph_textures、文字缓存与音频数据持有；缓存是否有回收应以实际路径为准，禁止因存在 `remove_texture` 就推断所有场景均会释放。

### 6.2 字体与编码是启动阻塞项

当前主 Renderer 构造时必须加载 CJK 字体（E7/E8）；因此“只补 JNI + Surface”仍可能在 Android 启动失败。

拟议策略：

- 建立单一 `FontProvider`，覆盖主 Renderer、图文测量/位图文字和效果内文字；不能只修改最终屏幕字体导致布局与点击区域错位。
- 按已定义优先级尝试用户指定字体、允许的游戏字体、系统字体；必要时随引擎资源提供可分发的 CJK 兜底字体。不要假设每个 OEM 都有同一文件名。
- 当前 CPU snapshot 字体缓存为全局 `OnceLock<Option<FontArc>>`（`snapshot.rs:546–558`）；将字体选择变为明确会话输入，避免第一次加载失败/首次游戏字体永久影响后续会话。无字体画矩形只能作诊断占位，不能作为产品正常显示。
- Android `/system/fonts` 或可用系统字体查询只是候选来源；API 26 不能无保护调用更高 API 的字体接口。验证 TTC face、glyph coverage、日文/简中/繁中/拉丁扩展，不仅是文件可解析。
- 回退字体改变布局时需要留样本图进行对照。缺字不能悄悄变空白后继续声称兼容。
- 当前 `ETHORNELL_TEXT_ENCODING` 明确支持 SJIS/CP932 与 GBK/CP936，改变的是 graphics API 的文本缓冲编码，不改变资源名和 BP 系统串编码（README 与 lib.rs:8165–8173）。
- 首版设置仅公开**已验证的 SJIS / GBK**。不要复用其他引擎 SJIS/GBK/UTF-8 菜单就假设本引擎支持 UTF-8；扩展编码需追踪全部相关路径与测试。
- 路径使用 UTF-8 不等于游戏文本使用 UTF-8。汉化字体替换/字符映射属单独能力，未实现不默认启用。

### 6.3 输入与退出

- 触屏主指映射鼠标 move/down/up，支持 CANCEL 和越界 release；多指不能互相产生错误 up。
- 当前 `window.rs:233–314` 点击只匹配 `ButtonSource::Mouse`；锁定 winit Android 后端产生 `ButtonSource::Touch`，因此 NativeActivity 备选路线也必须补触摸，不能假设它会自动模拟鼠标。
- 最小可玩范围必须包括左键、右键、滚轮/回看、Enter、Escape、Space、Ctrl/方向键中的样本需求；给无物理鼠标用户可达的右键/回看入口。
- Android KeyEvent → 项目共用输入层 → 引擎 descriptor，不能把 Android keyCode 直接当 Windows VK。复用已有引擎输入抽象时扩展 backend，不让 UI import JNI 类。
- Android 返回键/手势先进入宿主统一返回策略；“游戏菜单键”与“退出游戏确认”区分。脚本退出和宿主退出复用运行时 `handle_host_window_close` 对应语义，必要时等待游戏确认，不能一律立即 kill。
- 失去焦点清理按住态；系统音量键由系统处理。
- IME 名字输入先列能力门槛：首批样本若需要，在 P1 增加 InputConnection/composition/commit/cancel 与编码长度限制；否则明确未支持并排除该场景，不能把 key text 当完整 IME。

### 6.4 宿主请求与桌面系统能力

建立请求/响应队列：Rust 提交 request_id 和类型，Android 主线程显示交互，结果回到引擎线程继续相应 VM procedure。不要工作线程同步等待 UI，再让 UI 同步等待工作线程。

**这不是上游现成能力**：`native_user.rs:1246–1301` 的确认同步返回 `Value::Int`，`native_system.rs:113–141` 的 Retry/Cancel 为调用内同步循环，VM `lib.rs:5590–5603` 也在 native 调用返回时结算输出；`CProcHostCompleted` 表示已经完成，不能直接当异步请求设施。M1 必须为相关调用设计可挂起 continuation，保存调用线程、返回槽/输出内存和请求 ID；响应到达后只结算一次，不能先回默认答案再补结果。请求挂起期间引擎线程继续处理 detach/close/cancel/response；旧会话响应与已取消请求必须拒绝。M3 再完成暂停、销毁与 UI 请求相撞的生命周期验收。

需要逐项分类：消息框/确认、文件选择、shell_open、窗口标题/尺寸/显示、时间/物理内存查询、安装/注册表元数据、进程执行/库调用。

- 必需交互（例如退出确认、bootstrap 错误、名字输入）必须实现或阻止该游戏进入支持名单。
- Android 不执行 `xdg-open`、`cmd`、PowerShell 或游戏传入的任意命令。链接采用 allowlist scheme（例如 https），经宿主确认再发安全 Intent；目录外文件不因脚本请求自动授权。
- 桌面窗口大小/全屏请求映射逻辑画布与 viewport，不让脚本任意重设 Android Activity 布局或权限。
- 不实现原 Windows DLL/Win32 二进制执行；已由 Rust 重实现的 native opcode 与“加载 DLL”是不同能力。
- 未实现能力返回明确状态并记诊断，尤其避免首批游戏依赖能力被 no-op 掩盖。

## 7. 音频、视频与效果

### 7.1 音频

保留 Kira 音量/loop/fade/panning/通道语义。Android 的 CPAL 后端确实使用 Oboe，且 CPAL/Oboe Java 查询依赖 ndk-context；必须在 AudioManager 创建前初始化有效 VM/Context。

首版补齐：

- 前台播放前请求 AudioFocus（USAGE_GAME 与实际输出属性协调）；API 35+ 需满足顶层 App/前台服务条件，本方案不新增后台播放服务。
- 焦点丢失、onPause、无 Surface、锁屏引发的挂起使用独立原因位；恢复时只恢复此前播放的通道，不把脚本主动暂停的通道唤醒。
- 不用 `stop_all()` 代替临时暂停，否则 BGM 位置/循环/淡入淡出会丢失；为 AudioSystem 增加宿主级 suspend/resume，冻结相应游戏音频时钟。
- 现有 `set_volume()` 只修改字段不能直接视为全局硬静音接口；应核对其调用链并对已播放声音验证实际效果。
- 拔耳机/蓝牙切换、48kHz/44.1kHz、输出设备失效、短促音效与长 BGM 都需验证。音频初始化失败可以支持明确“无声模式”作为降级，但应报告而非伪装成功。
- 当前 `StaticSoundData::from_cursor` 会复制并解码整个音频；在专用线程进行并量测内存。若目标游戏音轨导致峰值过大，再引入 streaming，不在首轮盲改所有声音实现。

### 7.2 视频 / Flash

将“游戏运行时实际调用支持”与“workspace 中存在解码器”分开。逐样本登记资源容器/编码、视频帧、音轨、循环/seek、播放结束与跳过事件。

- 当前电影 `graph_effect.rs:168–198` 只保留 MPEG Video 流，**没有电影音轨输出**，volume setter `322–348` 只是状态；音频另路播放的作品需要实证，不宣称已有音画同步。
- `graph_effect.rs:384–410,470–502` 的零时长流可能出现 active 不自然结束、位置夹零；首版样本若含电影，必须覆盖无 PTS/时长失败/EOF/跳过，避免永久 wait。
- BMV 在 `graph_movie.rs:102–128` 通过格式校验后仍返回 decoder failure 6，尚不能视为已解码。
- SWF 的 `graph_flash.rs:122–141` 仅配置 renderer/movie/autoplay/viewport，锁定 Ruffle 默认 NullAudio/NullNavigator/NullVideo；开 mp3/aac features 不等于声音已接出。
- `graph_flash.rs:225–243,263–308` tick 明确不实时更新 BGI bitmap，只在 capture-and-release 时回贴最终帧，**当前不是完整实时 SWF 画面后端**；不得把 Ruffle 初始化成功算效果正确。
- Ruffle 当前强依赖且有独立 GPU 图，涉及效果而不只是可随意跳过的片头；`preload(ExecutionLimit::none())` 也需要执行/内存预算。将其变 optional 必须有能力位与场景测试；精简 bring-up 可先关闭，完整基线另跑以揭示缺口。
- WMV 尚未接主运行时路由；WMV、MP4 或其他格式不能因为 TyranorNext 其他引擎带有 IJK/MediaCodec 就自动复用。那是额外 backend 与时序/纹理/音轨适配工作；若复用现有 WMV C API，还需补句柄/并发/帧指针有效期/panic/尺寸预算检查。
- 不支持的视频不能默认阻塞 VM 永远等待，也不能把强制跳过当作完整兼容。返回可诊断状态，只有确认该场景非关键且列入产品策略时才允许可见降级。

## 8. 资源访问、存档与安全边界

### 8.1 文件模型

首版采用本地真实目录以控制范围。启动前在 core 验证根目录可读、游戏 bootstrap 存在、需要的用户数据位置可写；具体 URI/path 转换遵守项目现有门面，不新增绕过授权的“万能路径解析”。

- 已授权 SAF URI 不自动等价于可 `std::fs` 访问的路径。不能转换的来源应返回类型化启动失败，并提供用户可执行的本地目录选择方案。
- 现有 `GamePathUtils.kt:14–56` 的 documentId→本地路径转换是兼容工具，不是权限证明。限定已知本地 provider、验证 canonical containment、目录/读写与授权；不能信任任意 provider 的 documentId 或 URI 自报的路径。
- SAF VFS / 按需 FD 随机访问 / 持久缓存属于后续独立子项目；不要未经用户知情复制数十 GB 游戏到私有缓存。
- 资源名大小写、日文/中文/空格/emoji、汉化补丁优先顺序和 archive 同名覆盖需要保持桌面语义。
- 引擎当前 archive 名与系统字符串还固定使用 SJIS；guest root getter 会把宿主绝对路径重新编码成 SJIS（`ethornell-vm/src/lib.rs:7727–7734,10141–10154`）。因此 JNI 正确传 UTF-8 **仍不保证**中文/emoji 游戏根的 guest 往返正确。设计 guest 可见虚拟根/路径映射，分别测试 archive entry 名、系统串和图形 GBK，不能用一个编码开关统治三者。
- 防止 `..`、绝对路径、Windows 盘符/UNC、符号链接把写入越出允许根；不能只 `join()` 后做字符串前缀检查。规范化、已有父目录 realpath 与创建/写入竞态要一起考虑。
- Android 文件 policy 必须接管**读、枚举、写、删、复制/重命名两端、bootstrap/secondary root 切换及安装器文件操作**，不只保护 write_file_bytes：现有 `user_files.rs:49–79` 接受绝对文件/archive root，`native_system.rs:92–108` 直接解析两端 rename，`lib.rs:18151–18220` 根探测会查父目录/当前目录，`18584–18602` 还有脚本驱动进程启动。只在 core 校验一次启动路径不能修复这些 native 能力面。
- resource root 的读权限和 user root 的写权限分离，secondary root 只从宿主批准根集合选择；Android 禁止桌面式自动把父目录/cwd 扩为授权范围。桌面宽路径语义如需保留，使用独立 policy 并单独回归。
- 资源加载区分只读 resource root 与允许写入的 user root；对文件数、文件大小、图像尺寸、解压结果和脚本分配设上限，checked arithmetic，避免畸形包导致 OOM/写满盘。

### 8.2 存档策略先取证再确定

不得复制其他引擎方案里“save 目录”的假设。调查 bootstrap/native user file 调用实际创建的文件、脚本参数、游戏目录与工作目录变化，并形成每个样本的访问日志。

代码已确认：公开 `run()` 将 native_root 设为资源游戏根（`lib.rs:192–205`），`user_files.rs:164–180` 允许绝对路径或 root.join，`lib.rs:18288–18318` 最终 `create_dir_all + fs::write`，**当前不是原子提交也无写根 containment**。同一 native_root 还承担 loose file/marker 查询（`user_files.rs:88–112,197–204`），不能只把它改成私有 save 目录而破坏读资源；应把 guest 路径解析、资源读取、用户数据读写明确分开。

两阶段方案：

1. **Bring-up**：临时复制合法样本，在可写目录记录桌面/Android 文件访问，确认 BGI 用户文件集合、写时机、save/load/缩略图/配置和全局进度文件；不对用户正式库尝试破坏性迁移。
2. **产品**：根据实际行为选原位用户数据或覆盖 user root，核心要求是同一配置下“引擎实际读写路径 = core 镜像/导入导出映射路径”。若路径尚不能安全枚举，暂不开放该引擎存档管理，不使用 fallback 整根镜像。

需实现并验证：

- 不把资源 `.arc`、`.exe`、脚本或诊断日志误归为存档；清空存档必须严格限制在确定的用户数据集合。
- 游戏存档格式是否兼容 PC 原版、桌面 ethornell-rs，必须用互读样本证明；不只比较文件扩展名。
- 临时文件 → flush/sync → 原子替换（同文件系统）写入；失败保留旧存档。外置存储拔出、空间不足、权限撤销要给明确错误。
- 镜像前后必须有“引擎停止写入并确认 flush”的屏障；仅 Activity onPause 或 onDestroy 事件本身不等于写完，杀进程也不能当存档成功。
- 同一游戏不能并发两个写会话；顺序退出/重进、导入/导出/删除与活跃会话互斥。
- 进程强杀/LMK 不保证收到 onDestroy，不能把所有数据落盘押在该回调上。

### 8.3 安全约束

- 新宿主 Activity 显式 `exported=false`，不开放任意第三方 path 启动入口，不新增宽泛 Provider/URI grant。
- `:ethornell` 仍是 App 同 UID 和权限，仅隔离崩溃/内存与全局状态，不阻止脚本访问 App 可访问的数据；路径边界仍须在 native 文件层实施。
- 复用现有授权策略，不因引擎要读文件而额外申请麦克风/媒体/安装包等无关权限；权限被撤销后在真实操作点处理 SecurityException/IO 失败。
- 游戏为不可信输入；受限 shell/open URL、动态库加载、归档路径、内存分配与图片解码都在审查面。
- 日志默认不记录完整用户路径、对白、存档内容；诊断导出需用户主动触发并脱敏。

## 9. TyranorNext 接入改动清单

以下名称均为**待实施时确认的建议命名**：`EngineType.ETHORNELL`、显示名 `Ethornell / BGI`、`internal.ethornell`、`EngineSettingsKind.ETHORNELL`。

### 9.1 引擎宿主与打包

| 位置 | 拟议工作 |
|---|---|
| `engine/src/main/java/com/core/ethornell/EthornellActivity.java` | SurfaceView、线程、输入、会话注册、退出与错误；沿用 engine 宿主风格，不依赖 app/ui |
| 同包 `NativeEthornell.java` | JNI 薄门面、load/ABI 错误、会话 token；不在类静态初始化失败时直接崩溃 |
| `engine/src/main/cpp/ethornell_bridge.cpp` | C ABI dlopen shim，ANativeWindow/GlobalRef 所有权，字符串编码、版本/符号校验 |
| `engine/src/main/cpp/CMakeLists.txt` | 新增 bridge target，链接 android/log/dl；不复制多份运行库 |
| `engine/src/main/jniLibs/arm64-v8a/libethornell.so` | 经复现脚本产出的正式库及 provenance，不放本轮无宿主接口的链接探针 |
| `engine/src/main/AndroidManifest.xml` / `engine/consumer-rules.pro` | 注册私有 `:ethornell` Activity、必要 configChanges 与屏幕策略；检查 merged manifest、release keep；App 现有 `com.core.**` keep 不能代替 library 消费规则 |

现有 App 的统一顶部栏/设置/弹窗规范继续适用。引擎游戏画面宿主参考现有原生宿主，不把管理页面 UI 规范擅自扩展成“所有新页面都豁免”；需要新的例外必须单独说明。

Activity 首版拟参照现有宿主使用 `singleInstance`，但**重入策略要写成契约**：onCreate/onNewIntent 用同一 payload 验证；同游戏请求只恢复既有会话，不重复 create；不同游戏在运行时明确拒绝并提示，不仅替换 Intent 后继续旧 runtime。若以后支持切换，先停止旧会话、确认写入/flush、清理登记，再建新会话。所有异步命令、UI 请求/响应同时绑定会话 token，旧响应不能作用于新游戏；Surface generation 仅标识 surface，不代表游戏会话。

### 9.2 扫描 / 启动 / 持久化

- 当前 `GameEntity.kt:28,46,72` 与 `GameRecordCodec.kt` 按 enum **name 字符串而非 ordinal** 存储。只增枚举、不加列通常不需要 Room schema 升版；发布后改枚举名需兼容映射，增加数据库字段则仍须显式 Migration。
- 扫描初步候选是根目录 `system.arc` 与 bootstrap `ipl._bp` 证据，**`.arc` 扩展名本身不够**；arc magic/内部入口识别宜在 core 门面下走小型有界 probe，不能在 Compose 页面调用 native。当前 `.bgi` 已在 System35 辅助资源扩展名中出现（`EngineScanner.kt:60–64,959–972`），不能拿它直接判断 Ethornell。
- `EngineScanner.kt:685–1035` 是**首次命中即返回**，不是所有 confidence 取最大值。新增强证据须与 UK2 泛 `.mes` 等弱规则明确排序，混合目录与父目录测试不可省。
- 扫描根本层命中后停止下钻，增量扫描还会对已知游戏目录剪枝（同文件 `179–280,554–586`）；旧误判不一定自动纠正，发布时应说明全量重扫或制定保守重分类路径。
- 先确认扫描架构允许的 IO 成本：粗筛文件名，再可取消地少量 probe，禁止扫描期全量解包/遍历读取每个资源。当前内容头读取上限 64KiB，需容忍短读；公开 `detectEngine(DocumentFile)` 在 `650–657` 未传 `headOf`，内容 probe 的 File/SAF 一致性要补齐，不扩大全局递归白名单。
- 处理 `System.arc`、缺 bootstrap、仅散装脚本、资源目录嵌套、多个候选、同目录混合引擎。首版只自动识别强证据样本；“启动文件覆盖”不能把任意 BP 当支持入口。
- **该基线没有内置 adapter 注册表**：直接扩展 `EngineLauncher.kt:778–866` 的集中 `when`，新增 `buildEthornellIntent`；沿用尾部 `867–878` 主题注入、`ResolvedEngineSettings` 与既有 LaunchResult，不借接入改造成另一套启动框架。`LaunchResult.Success` 只代表 startActivity 成功，不等于 Rust 初始化成功。
- `LaunchContract.kt` 新增唯一 launchMode 和实际支持的 payload；ROOT_URI 保留游戏身份，PATH 为真实目录。宿主界面 locale 显式传 `UI_LANGUAGE_TAG`，不要和游戏编码混为一项。
- `EnginePluginBootstrap.kt:77–102` 把新直打包引擎加入无需 nativeplugin 的分支；不误注册到外置 APK 模块表、Winlator 分流或 KiriKiri/ONS/Artemis ZIP 列表。
- 大小写/特殊路径与游戏 ID 检测失败要可诊断。上游 game ID 无法检测会回退 `Tayutama2TV`（lib.rs:129–164）；Android 不应静默把所有游戏宣称为该 ID，可显示“未确定”诊断并按实测决定是否阻止。
- 运行时 bootstrap 失败时桌面入口可能走单图片展示 fallback（E2/E5）；产品 Android 启动必须明确判失败，不能把显示一张图片计作游戏启动成功。

### 9.3 设置、展示与回归面

- 引擎页的 supported engines、GAL 分组、描述、已安装判断、内置条目和版本信息。
- 首页分类、游戏封面占位色/角标、筛选、引擎名展示；中性色与语义色收口 theme，不硬编码页面主题色。
- 引擎设置与单游戏覆盖：先只公开文本编码（SJIS/GBK）、已实现字体策略、横竖屏等真实能力；渲染后端先诊断用途，性能/超分/独立存档不先展示空开关。
- `AppSettings / EngineSettings / PerGameSettings` 按既有 Resolver 合并，不用 UI Compose 状态承载 core 全局配置。
- `values`、`values-en`、`values-ja` 三语言同步，错误在 UI 映射；core 不返回本地化文案，取消异常原样传播。
- 新页面调用 AppTopBar，设置组件/输入框/标签/跳转项使用公共组件；修改触及的既有弹窗同步无反馈按钮规范，避免借此次接入大规模改造无关页面。
- 存档管理显隐、`GameSaveManager` 定位/镜像过滤、会话冲突、进程启动时 app 初始化成本都要单测。
- 当前实际存档入口是 `GameSaveManager.kt` 的分派与过滤，不存在可直接套用的新引擎 SaveLocator 注册机制。`GameScreen.kt:2073–2083` 会默认给新的内置类型显示存档入口；若映射未完成必须显式关闭。`EngineSessionRegistry.kt:18–19` 仅已有 Tyrano/RPGMaker 常量，新增 native 会话登记与运行中存档互斥需真正补实现。

### 9.4 按当前代码的实施文件索引

以下路径均相对 TyranorNext，供实现时逐个核对；行号对应页首基线。

| 文件 | 基线锚点 / 需做事项 |
|---|---|
| `app/src/main/java/com/tyranor/next/core/engine/EngineType.kt` | `10–34`：唯一 ETHORNELL、缩写 BGI、非手动类型 |
| `app/src/main/java/com/tyranor/next/core/game/scan/EngineScanner.kt` | `650–671,685–1035`：File/SAF 内容探测、规则顺序、启动目标；不得使用 `.bgi` 单特征 |
| `app/src/main/java/com/tyranor/next/core/game/launch/EngineLauncher.kt` | `114–140,778–878,1021–1044`：supportedEngines、when、专用 builder、主题/locale/配置传递 |
| `app/src/main/java/com/tyranor/next/core/engine/plugin/EnginePluginBootstrap.kt` | `77–102`：无需插件安装分支 |
| `engine/src/main/java/com/core/engine/LaunchContract.kt` | `14–32,99–119`：新 launchMode 与 payload；不复用错语义参数 |
| `engine/src/main/java/com/core/input/InputSink.kt`、`InputRemapController.kt` | 新 native sink 和 canonical key 映射；FVP/Siglus 目前不是完整统一输入样板；逐游戏 key 用 game.uri |
| `app/src/main/java/com/tyranor/next/core/game/storage/GameEntity.kt`、`scan/GameRecordCodec.kt` | engine name round-trip 与未知值；新参数需显式分区 |
| `app/src/main/java/com/tyranor/next/core/game/storage/GameOverridePartitions.kt` | `73–86,110–174`：新参数分区白名单，不依赖默认落入 tyrano 分区 |
| `app/src/main/java/com/tyranor/next/core/settings/{EngineSettingsStore,PerGameSettingsStore,EffectiveEngineSettings,EngineSettingsResolver}.kt` | 全局/单游戏/生效配置、normalize 和可用值白名单 |
| `app/src/main/java/com/tyranor/next/core/game/save/GameSaveManager.kt` | `51–171,240–308,579–616`：路径、过滤、原子目录替换时保全资源；删除/恢复/镜像必须同源 |
| `engine/src/main/java/com/core/engine/EngineSessionRegistry.kt` | 新 host 常量、存活/陈旧登记处理；不能只登记却不消费 |
| `app/src/main/java/com/tyranor/next/ui/engine/EngineScreen.kt` | `467–511,541–579`：GAL 分组、描述、内置版本弹窗 |
| `app/src/main/java/com/tyranor/next/ui/game/GameScreen.kt` | `949–965,2047–2083`：封面色、存档显隐；目录自动 bootstrap 不应无条件开“任意启动文件” |
| `app/src/main/java/com/tyranor/next/ui/settings/{EngineSettingsMenuActivity,SettingsScreen,PerGameSettingsScreen,EngineSettingsText}.kt` | 菜单、设置卡、标题、值映射与保存链 |
| `app/src/main/java/com/tyranor/next/ui/settings/FontSettings.kt` | `27–52`：若复用 FontImport，补大小/内容解析/原子写/覆盖策略，不只校验扩展名 |
| `app/src/main/res/values{,-en,-ja}/strings.xml` 与 `engine/src/main/res/values{,-en,-ja}/strings.xml` | App 和 engine 两套宿主文本均三语言齐平；Java 字面量不在现有 Kotlin 文案检查覆盖面 |
| `engine/src/main/AndroidManifest.xml`、`engine/src/main/res/values/styles.xml`、`engine/consumer-rules.pro` | private Activity、进程/屏幕/主题、release JNI 保留 |
| `.github/workflows/android-ci.yml`、`android-beta-release.yml` | 现有 assemble/JVM 门禁补 native ABI/依赖/16KB/设备测试证据，或独立 native CI 交付报告 |

网络封面服务通常无需按引擎新增分支；首页分类目前按 enum 自动生成（`CategoryWarehouse.kt:161–193`），不重复增加第二套分类逻辑。标题回写是可选增强，必须保护用户改名且处理跨进程通知，不能把 Room 放进引擎子进程。
- 实施时同步 `CONTEXT.md`，只描述已落地行为，计划尚未支持的能力不得写入事实清单。

## 10. 构建、二进制检查与许可

### 10.1 正式构建建议

建立独立 `platform/scripts/build_android.sh`（引擎仓库，拟新增），固定 Rust toolchain、NDK、API、target、feature profile、Cargo.lock，并输出头文件/manifest/build-info/SHA-256/未 strip 符号。

```bash
# 以下是移植实施后的目标命令；crate/feature 尚未创建，当前不可直接执行。
export ANDROID_NDK_HOME="<sdk>/ndk/28.0.13004108"
export CARGO_NDK_PLATFORM=26
cargo ndk -t arm64-v8a -p 26 -o dist/android/jniLibs \
  build --release --locked -p ethornell-android --lib
```

移植期不在本 App 的每次 assemble 中网络拉取 Rust 引擎 HEAD。正式库在引擎 CI 构建，App 引用确定 revision+hash 的预编译产物；需要 source build 时提供可复现脚本，而非只交一个来历不明 `.so`。

### 10.2 二进制验收

- `llvm-readelf -h -d -Ws`：ELF64 AArch64、DT_NEEDED、未解析符号、版本、入口导出白名单；检查是否有不该存在的 `android_main`/NativeActivity glue 引用。
- 实际设备 `dlopen(..., RTLD_NOW)` + ABI self-test：构建机链接通过不保证 Android 加载成功（共享库可能允许未定义符号）。
- `llvm-readelf -n` 检查 API note/build-id，按 API 26 真实加载验证，不能只靠 NDK 编译器文件名。
- `llvm-objdump -p <lib>.so` / `llvm-readelf -lW` 检查每个 LOAD 的 16KB 对齐及 RELRO；NDK r28 默认支持不能取代 Rust+native 完整产物检查。
- 审查全部传递 native 依赖；如需要 libc++_shared，只分发一份与构建匹配且满足页大小要求的版本。不能 `pickFirst` 掩盖不同版本冲突。
- 项目当前 `useLegacyPackaging=true`：压缩/解压打包不能修复 ELF LOAD 的页对齐。APK zipalign 验证也不能替代 ELF 验证；检查最终 merged APK 而不只检查 dist 文件。
- 记录未 strip/strip 库大小、APK 下载增量、安装体积、两套 GPU 后端和 Flash 对体积影响；本轮不给未测的体积承诺。
- 不做 `--all-features` 合并互斥 Android activity 路线；分别构建 desktop、host-driven、可选 flash profile。

### 10.3 发布许可是独立阻塞项

根 `Cargo.toml` 的 `[workspace.package] license = "MPL-2.0"` 只是线索，**不是本轮已完成的许可认定**：当前各包未统一 `license.workspace = true`，`cargo metadata --no-deps` 对全部工作区包返回 license=null，仓库也未发现受 git 跟踪的根 LICENSE/COPYING/NOTICE。

还需核实：

- 上游原始来源与本地 fork revision、授权声明与 LICENSE 全文、各 crate 继承/文件级声明。
- `src/raw`、逆向参考 C、调试文件/游戏相关数据与实际 Rust 编译输入的关系；不把第三方游戏资产/反编译参考材料随 APK、源码包或公开测试夹具一起分发。
- Ruffle、解码器、字体、所有 Rust/native 依赖的许可证与 notice/SBOM；进程隔离不自动消除许可证义务。
- 应提供的修改源码、构建脚本、声明、获取方式；必要时取得作者澄清或法律审查。

本方案不沿用其他历史文档中“两个许可证兼容，附源码链接就够”的简化判断。**许可未澄清可做授权范围内的本地技术验证，不进入公开发布阶段。**

## 11. 阶段拆解、交付与停止条件

实施顺序：M0 → M1 → M2 → M3 → M4 → M5。Rust 运行时改造与 App 接入分开提交，便于 bisect；有接口依赖的任务不能无契约并行。

| 阶段 | 工作包 | 交付物 | 通过门槛 / 停止条件 |
|---|---|---|---|
| M0 可行性基线 | 固定 revision；编译/链接/符号检查；合法桌面样本；VM/文件/许可调查 | evidence、依赖树、构建日志、样本矩阵、阻塞清单 | 不把默认 check 的 feature 缺失当无解；若关键 native 能力/许可无可用解决路径则停止产品化 |
| M1 宿主解耦 | HostConfig/HostRuntime、FramePresenter、HostServices、feature 切分、headless/desktop 共用回归 | 可测试 Rust 外观与 C ABI v1、头文件、桌面前后快照/帧行为对比 | Android 不引入 winit activity glue；桌面样本行为不因抽取变更；无假成功图片 fallback |
| M2 Android 最小运行 | cdylib、JNI shim、SurfaceView+专用线程、字体、viewport、最小输入、context/声音 | 开发 harness APK、可重现 `.so`、设备日志/截图 | 至少一个样本到标题和对白；检查 JNI、字体、左/右键、GPU；未通过不开放库内“已支持” |
| M3 生命周期与数据安全 | pause reason、Surface detach/attach、音频焦点、请求/响应弹窗、文件限制、存档 flush/映射 | 生命周期压力用例、存档闭环证据、错误 DTO/诊断 | 无旧 Surface 访问/死锁/后台继续剧情；断盘/空间不足不损坏旧存档；写入越界拒绝 |
| M4 App 全链路 | 强证据扫描、EngineLauncher when/builder/contract、设置 resolver、引擎页/封面/三语言、存档管理 | 主 App debug/release APK、自动测试、功能清单 | 扫描→入库→启动→游戏保存→镜像→导入/再进均真实可用；其他引擎回归通过 |
| M5 兼容与发布 | 多游戏/多 GPU/API/16KB、长稳、能力分档、SBOM/许可、符号化与回滚 | 兼容矩阵、release checklist、产物 manifest、已知限制 | 达到声明范围；许可、16KB、release R8 任一不满足不得公开发布 |

### 11.1 工作量参考（规划估算，不是承诺）

前提：一名熟悉 Rust/wgpu/Android 的开发者，有合法可用样本与 Adreno/Mali 设备，Rust 主逻辑无需大规模逆向补齐。

- M0：约 2–4 人日。
- M1：约 5–8 人日。
- M2：约 4–7 人日。
- M3：约 6–10 人日。
- M4：约 3–6 人日。
- M5：约 5–10 人日。

合计约 **25–45 人日**，不含等待许可澄清、设备准备，也不含新游戏触发的大量 opcode/视频/IME 兼容开发。每个阶段按证据重新估算；不能把桌面能进标题当作压缩后续验收的理由。

§4.4 的子线程编码/诊断、§7.2 的电影/SWF 缺口需单独工单化：编码传播与错误汇总应随首批 GBK/协程样本前置；BMV、WMV、实时 SWF/声音只有样本需要时进入该阶段工期。不得把 25–45 人日理解为“所有上游兼容性补齐”的估算。

## 12. 测试矩阵与完成定义

### 12.1 自动化

| 层级 | 必测内容 |
|---|---|
| Rust 纯逻辑 | 资源 probe/优先级；VM 内存与有界输入；时间间隔与 120Hz 节拍选择；编码/字体选择；存档路径与原子写；请求/响应恢复 |
| Rust ABI | struct_size/version、null/非法句柄/长度/NaN、创建失败清理、双销毁/旧 generation、panic 隔离、无 Surface step |
| 原生宿主 | JNI GlobalRef/字符串编码/ANativeWindow 引用计数；线程归属；Surface 销毁确认；R8 release 加载；RTLD_NOW 全符号解析 |
| App JVM | EngineType 序列化、扫描假阳性/冲突、EngineLauncher 分派、LaunchContract/设置白名单、存档边界、错误映射 |
| 图形对照 | 桌面 vs Android 的层级/裁剪/混合/字体/ruby/转场/Flash；采用容忍 GPU/字体差异的阈值与关键区域，不能只做全图 hash |
| 压力/故障 | 畸形包/图像尺寸溢出/越界路径/解码失败、权限撤销、磁盘满、后台期间拔存储、Surface thrash、UI 弹窗互锁 |

无授权游戏数据不进入公开 CI；公开测试用自行生成的格式夹具，真实样本在本地/私有测试环境保存指纹与复现说明。

### 12.2 设备与游戏样本

- API 26 设备/可用模拟环境，另覆盖 Android 11/13 与 15+；API 35/36 的音频焦点、返回、边到边行为单列。
- 至少各一款 Adreno、Mali 真实设备；分别验证 Vulkan 与声明支持的 GLES 路线。模拟器不能代替真 GPU 结论。
- 一套真实 16KB 环境；记录 `getconf PAGE_SIZE` 为 16384。只看 APK 对齐不算已在 16KB 运行。
- 60Hz 与 90/120Hz、手机与平板/分屏、横竖切换、锁屏/切后台各至少 20 次、冷启动与连续重启至少 20 次。
- 日文 CP932 原版、GBK 汉化、不同逻辑分辨率与 bootstrap/引擎代际样本。每游戏记录 game ID/入口/编码/字体/电影/Flash/存档与故障点，不把一部游戏代表整个家族。
- 每个发布样本至少验证：标题→新游戏→对白/选择→BGM/SE/语音→回看/菜单→保存→退出→重开读取；需要时包含电影和特殊效果。长稳至少 60 分钟并记录 native/GPU 内存趋势，通关结果独立标识。

### 12.3 建议性能观察项

记录创建耗时、首帧/首段对白时间、引擎 tick p50/p95/p99、解码峰值、输入到反馈延迟、音画同步、CPU/GPU/内存与耗电。以固定设备+样本制定预算，不写未经测量的“所有游戏 60FPS / 某 MB 内存”。

至少满足：UI 线程无重资源初始化/同步长解码，无无限节拍积压；暂停无后台推进；恢复不一次性补跑数百 VM pass；重复 Surface 重建后内存不持续单调增长；退出后主 App 可正常使用。

### 12.4 项目构建与审查流程

```bash
# TyranorNext 根；实施后执行，不是本文已完成的测试声明。
./gradlew :app:testDebugUnitTest :engine:testDebugUnitTest --no-daemon
./gradlew assembleDebug --no-daemon
./gradlew :app:assembleRelease --no-daemon
```

Android CLI 安装沿用项目规范 `android --sdk=/tmp/androidsdk install ...`；本次调查机器实际 SDK 在 `$HOME/Library/Android/sdk`。实施时先用 `android info sdk` 确认配置，显式准备所需 SDK，不能让文档路径与真实执行结果混为一谈。本轮未安装或运行新增引擎 APK。

按 `AGENTS.md` 组织独立逻辑、架构、健壮性、安全与回归审核；阅读 Android CLI 可发现的权限/Intent 审查技能。文档审查也应核对“已有事实/提议/未验证”的分界，不能仅做语法校对。

### 12.5 首版完成定义

以下按可独立验收的子项拆分；`[x]`只证明该子项，不代表其所属M阶段或首版整体完成。当前§0.3与验收记录为证据索引。

**已实施并验证的子项**

- [x] host-driven ABI/JNI候选可入包；API26/API36/16KB的RTLD_NOW、14导出与基础ABI探针通过。
- [x] Release保持R8/resource shrink，16KB matching纯Java instrumentation 3/3通过：identity/load/ABI/build_info与productionJNI create/poll/destroy；scope仅release-native-load-create-only。
- [x] host-driven产物已有输入快照/来源记录与特定候选APK字节核验；dirty局部快照不是public clean，不以此宣称最终独立可复现重建完成。
- [x] API36/SJIS、16KB/SJIS与GBK原创fixture四核心方法通过，包括BP Closed、像素、Surface重挂与旧generation拒绝；optional real-game skip单列。
- [x] 已覆盖的glyph/render fixture设备子项有通过证据；只勾该子集，不勾真实游戏字体/编码/正文布局整体通过。
- [x] 实机正常close fixture两轮退出ACK、进程退出与新进程重入通过，无测试中间强杀。
- [x] 跨进程dispatch设备3/3通过（4.541s）：持锁owner metadata/进程身份核验、claim/ACK/TERMINAL/退出重入、revoke late拒绝、missing-resource terminal后新token；仅授权protocol gate，管理仍关闭。
- [x] 相同dispatch三例16KB再验3/3通过（12.971s）；两平台protocol闭环，flag聚焦4suites/42tests零失败，HOST_DISPATCH_PROTOCOL=true/DEVICE_SAVE_IO=false/available=false。
- [x] 合成框架重复压力：16KB像素/重挂/旧generation拒绝20/20轮，实机10轮双会话正常退出/重入（共20次）通过，轮间无test kill；不外推真实游戏或小时级soak。
- [x] 直接JNI暂停仪器3/3通过：fractional guest clock精确序列/CANCEL held清理、无Surface与Activity/focus原因独立、新generation暂停detach恢复RGBW；真实Kira init/pause API被调用，不外推录音或Activity onPause集成。
- [x] 扫描/显式启动/全局与单游戏设置/三语言已接入；实机识别及GBK设置保存、App/engine现有自动回归通过（skip单列）。
- [x] 私有save store/gate已有实现与自动回归，设备后端6/6通过（含恶意ZIP typed failure）；未验证native槽位时保持管理关闭，capability false。
- [x] 合成globalSDC由VM真实写入/destroy/同根重读/空根negative，16KB/SJIS与实机/GBK均通过；Java未造档，不外推商业slot。
- [x] 合法样本普通App UI新用户三语言选择简中后，正常标题/中文菜单父截图视觉PASS；新游戏街景/speaker、zoom推进与SAVE中文十槽UI可见。仅勾这些子项，不代表正文或slot保存成功。
- [x] 父实机新游戏/私有UserData写入后，system.arc、原BGI.gdb和原UserData/AmairoChocolate101.sud三critical文件与原ZIP的SHA-256一致；仅此三文件，不声称全资源树hash或商业slot闭环。
- [x] 当前候选新游戏街景/角色名/首句真实中文与点击推进后的第二句中文均父实机视觉PASS，不再是数字key；仅代表已观察两句，不外推全篇或精确DA ABI。
- [x] 真实样本手动Back→宿主确认→游戏自绘YES/NO→YES→App正常退出通过，无kill；只勾正常退出路径，不等商业slot或完整生命周期。
- [x] 静态native/来源边界/workflow验证工具、原创夹具已交付并有测试；限制与未通过项公开记录，不将短冒烟冒充完整兼容。

**首版仍未完成的门槛**

- [ ] 最终首分岔修复候选固定source inputs/hash并复验独立可复现构建、依赖、最终APK、release R8加载与回退版本。
- [ ] API26在有可用adapter硬件上的GPU验证及无adapter错误边界验收；不以当前两项失败推断整个Android26不支持；多GPU/后端仍待验证。
- [ ] 首批真实游戏完整矩阵：标题与两句正文已过，商业slot实际提交/日期缩略图/新文件/退出重开读取未闭环；当前无保存且非确认crash，选择/音画/长篇仍不统称全兼容。
- [ ] 后台暂停/音频焦点与路由、输入cancel/慢调用故障、真实游戏生命周期、小时级soak及dispatch issue-revoke完整覆盖；已过Surface20轮/正常close20会话不代勾这些范围。
- [ ] 真实游戏字体、文本布局/编码/缺字与需要的IME/电影/Flash能力验证；不支持场景明确排除，不假成功。
- [ ] native存档创建读回与App管理闭环后再决定开放；恶意ZIP typed-error及私有后端设备6/6已通过，但不等于native槽位或PC互通。
- [ ] 其他引擎设备回归、长稳/内存与性能预算、最终兼容声明和回退演练齐全。
- [ ] source许可、第三方notice/SBOM、修改源码/构建与符号交付闭环；**公开发布仍独立blocked，不因技术通过自动勾选**。

## 13. 风险与回退

| 风险 | 等级 | 应对 / 触发动作 |
|---|---|---|
| 基线游戏运行逻辑尚不完整 | 高 | 先桌面同样本基线；分开归因上游兼容问题与 Android 移植问题；不为手机移植重写全部引擎 |
| Surface 销毁/重建 UAF、死锁 | 高 | 单线程所有权+generation+同步 detach，压力与慢帧注入；失败停止会话 |
| VM 指针/句柄误当宿主地址 | 高 | 核查 guest memory 模型、范围检查；FFI 不暴露 VM 内部地址，不允许运行外部 DLL |
| 存档映射误判或目录穿越 | 高 | 首先 IO 取证，最小集合、边界检查、flush 屏障；不通过不开存档管理 |
| 字体初始化/GBK 显示/字符度量不一致 | 高 | 多路径 FontProvider、coverage 与版面测试，不只加 `/system/fonts` 一条字符串 |
| 两套 wgpu 与 Flash 内存/体积开销 | 中高 | 保留行为基线、分 profile 测量，不在移植期强行跨大版本合并 |
| 高频屏加速/后台跳时 | 中高 | 保留原生调度节奏，pause reason 与单调时间，禁止补帧 catch-up |
| 音频在后台继续或恢复丢通道 | 中高 | 宿主级 suspend/focus，不用 stop 替代 pause，路由测试 |
| 许可声明/逆向参考材料权属未澄清 | 发布阻塞 | 取得清晰授权、清理分发输入、SBOM；无结论不公开产物 |
| native 库与 App 版本不匹配 | 中 | ABI v1+build-info/hash 校验，拒绝错版；保留上一版源码与产物 |

回退时只禁用新引擎的可启动能力，不破坏已有游戏记录与用户存档；枚举值/数据格式一旦发布不能随便删除。发布回退与 App `versionCode` 规则无关：不得降低版本号实现回退，应在递增版本中关闭新能力或回用验证过的库。

当前旧 APK 读到未知 engine name 会落为 UNKNOWN，而 `EngineLauncher.kt:856–865` 的 UNKNOWN 又走 Tyrano fallback。因此“安装旧 APK”不是安全兼容回滚；若要跨版本降级必须另行处理未知引擎的拒绝启动/显示策略，不能让 BGI 数据被错误交给网页壳。

## 14. 待验证问题与下一步建议

1. **先决定合法首批样本与声明范围**：具体游戏/版本、SJIS/GBK、是否使用 Flash/电影/名字输入；缺样本无法做真实兼容计划。
2. M0 保存本轮 Android `.so` 探针的链接/符号/RELRO 报告，并用正式 ABI 导出根重做；本轮 check 通过不再作为待解决阻塞，未定义 android_main 与页布局问题有明确后续动作。
3. 首个代码任务应为 HostRuntime/FramePresenter 抽取与桌面回归，而不是先把 EngineType 加进 UI 造成“支持假象”。
4. 字体、存档路径与 Android 宿主对话框要与 bring-up 同期解决，不推迟到“能进标题之后随便补”。
5. 许可、16KB、release R8 与 API 26 的验证在最终公开发布前全部闭环。

## 15. 参考资料

- [TyranorNext 基线源码](https://github.com/Weiss-UltimateSavior/Tyranor-Next/tree/7a3f007cefe634f63c054730fd90c21ed5185352)
- [ethornell-rs 本地 fork 基线源码](https://github.com/Weiss-UltimateSavior/ethornell-rs/tree/5556c36b53c9af29a19ba507ffa431b19300f0e7)
- 仓库 `AGENTS.md`、`CONTEXT.md`；现有 FVP/Siglus 接入方案仅作历史参考，冲突时以当前代码为准。
- [Android JNI tips](https://developer.android.com/ndk/guides/jni-tips)：JavaVM/JNIEnv、GlobalRef、字符串编码、线程、native 注册与 keep。
- [SurfaceHolder.Callback](https://developer.android.com/reference/android/view/SurfaceHolder.Callback)：surfaceDestroyed 返回前停止线程访问 Surface。
- [ANativeWindow](https://developer.android.com/ndk/reference/group/a-native-window)：window 引用、尺寸与 API 版本。
- [Android 16KB page sizes](https://developer.android.com/guide/practices/page-sizes)：ELF/RELRO/打包/运行环境核验；NDK r28 默认对齐不替代实际检查。
- [Android Audio focus](https://developer.android.com/media/optimize/audio-focus)：焦点变化、USAGE_GAME、API 35+ 前台限制。
- 本机锁定依赖源码：`winit-android 0.31.0-beta.3/src/lib.rs:34–71,125–165` 与 `event_loop.rs:138–141`；`wgpu 0.19.4/src/lib.rs:530–546`（raw handle 生命周期）；`cpal 0.15.3/src/host/oboe/android_media.rs`（ndk-context）。

---

## 调查补充与独立审查记录

本节记录后续链接探针结果及独立审查反馈；不把待完成的实现任务打勾。

### A. 构建探针（已完成）

- Android `cargo check`：成功，补齐 feature，无源码变更。
- Android 强制 `cdylib --release`：成功，3m38s；游戏 API 缺失、未定义 android_main、LOAD/RELRO 结论见 §2.3。
- macOS archive/script/vm 单测：215 项通过；命令与覆盖边界见 §2.4。
- 本轮没有制作可玩 Android APK，没有实机安装/性能/游戏存档成功证据。
- 日志保存在本机临时工具目录；可长期复查的命令、基线、hash 和关键输出已经写入本文，不能依赖临时绝对日志路径作为发布证据。

### B. TyranorNext 独立接入审查（已纳入）

独立只读审查覆盖架构、持久化、扫描、启动、安全与回归；已阅读 Android CLI 可发现的 `android-permissions-security` 审查技能。主要修正：

1. 当前启动为 EngineLauncher 集中 when，不存在内置 adapter 注册链；方案已按实际文件修正。
2. EngineType 按 name 字符串持久化；纯新增值通常不需 Room 升版，改名与回退风险单列。
3. `.bgi` 已属于 System35 辅助特征；扫描首次命中及增量剪枝需要冲突/全量重扫方案。
4. 明确 EnginePluginBootstrap 无插件分支、新内置类型默认显示存档入口、native 会话登记不现成覆盖、输入层需要新 sink。
5. SAF 与真实路径不等价、普通子进程非沙箱；私有 Activity、不扩大 Provider/grant 面、Unicode JNI 路径列入验收。
6. App/engine 两套三语言和 release 消费 keep 规则均列明；当前 Java 硬编码文案存在检查盲区，须人工/额外门禁核验。

所有“已纳入”仅指**方案完善**，不意味着代码缺口已经修复。

### C. 引擎可移植性独立审查（已纳入）

独立只读审查覆盖 VM/指针、路径/存档、子线程、平台 cfg、字体、电影/SWF 与资源健壮性；关键结论：

1. ARM64 host 与 u32 guest 是已有设计，不需要以“原引擎 32 位”为理由改做 ARM32；首版仍按 arm64 推进。
2. GBK 未传播子 VM、子线程 strict/diagnostics 关闭、Partial 被计为 supported，已补入 §4.4 与分阶段回归要求。
3. native_root 混合资源与用户文件、绝对/父路径、非原子覆盖写已明确为真实风险，不只是待猜测事项。
4. CPU snapshot 字体与 GPU 字体不能分开修；统一 FontProvider 还需处理全局 OnceLock 缓存和重新配置，不能一会话首次选错后永久锁住。
5. MPEG 无电影音轨、BMV decoder failure、WMV 未路由、Ruffle Null 后端及非实时回贴已在 §7.2 分级；这些是具体作品兼容门槛，不全压成首帧前置项。
6. VM/协程/shared-heap journal、GPU texture cache 需长稳计量；单个 VM 上限不能替代进程内存预算。

两项源码审查与一项文档独立复审各自独立执行；复审不代替实现后的新一轮代码审核。

### D. 文档独立复审（已纳入）

复审确认建议架构与源码事实总体一致，并按 Android 权限/Intent 安全技能核查私有 Activity 与路径边界；补齐四处实施契约：

1. 异步宿主交互需要 VM continuation/输出一次性结算改造，不只是加队列（§6.4）。
2. 高优先级 detach 不能抢占 native 调用，需独立监督的故障终止路径，不允许 finish 后继续访问旧 Surface（§5.3）。
3. 文件 policy 覆盖读/枚举/重命名两端/根切换等全部 native 操作，不只检查启动根与写文件（§8.1）。
4. Activity 重入、同游戏恢复/异游戏拒绝及会话 token 绑定必须明确，不能只依赖 Surface generation（§9.1）。

以上仍为实施任务，未在本轮修改业务代码。文档正文区分了静态证据、实测结果、建议 API 和设备待验证项。
