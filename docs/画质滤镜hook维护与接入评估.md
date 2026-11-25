# 画质滤镜 Hook 维护指南与引擎接入评估

本文面向维护者，整合共用画质滤镜链在各引擎宿主下的接入与维护技术资料，分为两大主体部分：
- **第一部分：未接入引擎路线评估**（方案 B/C 优先原则、第一梯队详析、第二梯队 Hook 备选与实施顺序）。
- **第二部分：已接入原生引擎 Hook 维护指南**（Kirikiroid2、官方 Artemis 的锁定面清单、重钉核验流程、常量速查与非回退安全断裂防范；krkrsdl3 软件渲染 hook 已归档）。

---

## 第一部分：画质滤镜链未接入引擎接入评估（方案 B/C 优先）

评估基线：分支 `exp/fbgames-graphics-filter`（含 RealLive / AVG32 / UK2 的 CPU 帧缓冲模式、ONScripter 宿主与多轮审计修复；System35 滤镜接入已摘除）；上游源码快照 `reference/krkrsdl3-upstream/src`（@ `e5cabc20`，即打包库对应版本）。

### 1. 口径与硬约束

接入手段沿用三类模板：

- **A 纹理借用/hook**：对预编译 `.so` 做 PLT/vtable 级 hook（KR2 / krkrsdl3-GL / 官方 Artemis 先例），需 GNU Build ID 锁版，上游更新需重新核对。
- **B 代理 Surface（producer-consumer）**：把 App 提供的 ANativeWindow（SurfaceTexture 背书）交给引擎渲染，App 侧 OES 消费 → 共用滤镜链 → `TextureView` 呈现（Siglus / 官方 Artemis 先例）。不动任何 `.so`。
- **C CPU 帧上传**：引擎输出本就是 CPU RGBA 帧（Framebuffer 宿主先例：`game_fb_*` 帧直传 GLES）。

硬约束：**不重编任何引擎运行库**（`libonsyuri.so`、`librfvp.so`、`libxsystem35.so`、`libxsystem4.so`、`libsiglus.so`、`libgame*.so`、`libSDL2x.so`、`libSDL3.so` 均维持现有打包文件与校验清单）。用户优先级：**先做 B/C 直接可行的引擎；次选 `.so` 不常更新（上游或本项目）从而 hook 锁版维护成本低（A 类）的引擎**。

既有结论不变的部分：Kirikiroid2 / krkrsdl3-OpenGL / Siglus / 官方 Artemis 已接入；RealLive / AVG32 / UK2（CPU 帧缓冲模式）已在实验分支落地；Web 系、外置模块、Winlator 与外置模拟器维持原判（见 §5）。

### 2. 结论摘要

| 引擎 | 梯队 | 方案 | 难度 | 一句话依据 |
| --- | --- | --- | --- | --- |
| krkrsdl3 软件渲染 | — | **放弃维护**（A 实测不可行已剥离；B 待重新评估） | — | 上游活跃迭代 + hook 不可靠；B 代理路径待后续按需评估 |
| FVP（rfvp） | 一 | **B** 直接 | ★★★ | Surface 交接单点在 Activity 手里（`FvpActivity:229`/`:281`） |
| ONScripter | 一 | **B** 经 vendored SDL2 胶水 | ★★★ | `org.libsdl/app` 唯一收口点 `getNativeSurface()`（`SDLActivity.java:322`，return 在 `:327`） |
| System35 | 一 | **B** 经 `app32` 胶水 | ★★☆ | 与 ONS 同构单点（`org.libsdl/app32/SDLSurface.java:79`）；另有项目自有 fork 的源码级备选 |
| System4 | 一 | **B** 经 `app32` 胶水 | ★★☆ | 同上；引擎自有 GLES 管线对代理窗口同样适用 |
| Ethornell/BGI | 一 | **B** 直接 | ★★★ | `session.attach(holder.getSurface(),…)`（`EthornellActivity:340`）；受引擎实验期约束 |
| ONScripter（B 失败备选） | 二 | A：hook libonsyuri 的 `SDL_RenderPresent` 等 SDL 导入 | ★★★☆ | krkrsdl3-SW 的 hook 设计同款；插件 zip 每次发版重钉，A 需随钉重核 |
| System4（B 失败备选） | 二 | A：hook libxsystem4 的 EGL 导入 | ★★★☆ | 项目自有 fork 几乎不更新，锁版成本最低 |
| Web（Tyrano/RPGM MV·MZ/VN/WebOther） | 维持原判 | 页内 JS 后端（另起炉灶） | 很高 | WebView 无纹理控制点，共用 GLES 链够不着 |
| Ren'Py / RPGM RGSS / Ruffle | 维持原判 | — | 超出本仓库 | 第三方 APK，主 App 只有 intent 协议 |
| YU-RIS / CatSystem2 / PC（Winlator）、PSP / Switch | 维持原判 | — | 超出本仓库 | 容器/模拟器内部，主 App 无帧控制点 |

相对首轮评估的两处**降级修正**：ONScripter 由 ★★★★（hook libonsyuri）降至 ★★★——vendored SDL2 Java 层提供零 hook 的 B 路径；System35/System4 的"fork 源码级 ABI"备选被 B 路径取代（彻底消除重编诉求）。

### 3. 第一梯队详析

#### 3.1 krkrsdl3 软件渲染

上游事实（`environ/sdl3/sdl3_app.cpp:177-189` 注释"SDL模拟软渲染器，虽然可能选择GPU"）：SW 模式 = `SWRenderBackend` 纯 CPU 合成（`std::vector<uint8_t>`）+ `SDL_CreateRenderer(window, NULL)` 自动选驱动上屏——Android 上实际为 GLES 后端。每帧流程（`core/render/TVPCompositor.cpp:127-156`）：`UpdateWindowTexture`（`SDL_UpdateTexture`，ABGR8888 自顶向下）→ 主帧/overlay 逐一 letterbox `SDL_RenderTexture` → `EndFrame` → `SDL_RenderPresent`。全部符号是 libkrkrsdl3 对 libSDL3 的跨库导入，可 PLT hook。

**方案落地状态与上游候选判定**（2026-11-02 更新：方案 A 经实机验证不可行，实现已完整剥离，B 为唯一路线）：

- **方案 A（hook）已放弃并剥离**：hook 8 个 SDL 导入 + ES3 属性注入 + 主绘制位替换的实现已在实验分支完成并通过两轮静态审核，但实机验证确认不可行——SDL3 渲染命令队列化（`RenderClear/RenderTexture` 延迟到 `SDL_RenderPresent` 才冲刷）与 GLES2 renderer 的窗口标志重写机制双重对抗使得 hook 无法可靠产出正确画面。全部相关代码已从分支剥离，完整实现可从 git 历史考古（`7f96e5c3` 至 `264c7c20` 区间）。
- **方案 B 为唯一可行路线**：vendored `org/libsdl3/app/SDLSurface.getNativeSurface()`（L94-95）是 native 取 Surface 的唯一收口，条件返回代理 ST Surface 即可让 SDL renderer（无论 GLES/software 驱动）画进代理窗口；App 侧 OES → 滤镜 → TextureView。**零锁版**——库更新不影响代理；overlay 由 SDL 合成进代理后整体被消费，无顺序契约；GL 模式适配器按 renderer 互斥分流。代价：+1 帧延迟与一次 OES 归一 pass（A/B 性能等效结论仍成立，见 §6）、Java 胶水改动需随 SDL3 上游 glue 更新对照、需验证 ST 背书 ANativeWindow × SDL GLES2 renderer 的设备兼容性。
- 帧尺寸 = 窗口贴图分辨率（逻辑 vs 物理待运行期确认）；物理 1080p+ 时 2X CNN 触发既有 256MiB 预算 tile/bypass，实际收益以 Lanczos/CAS 类为主，预期要如实管理。

#### 3.2 FVP（rfvp）

`FvpActivity` 在 `surfaceCreated/Changed` 把 `holder.getSurface()` 交给引擎（`:229` `NativeRfvp.setSurface`、`:281` `NativeRfvp.create`）——**交接单点完全在 App 手里**。方案 B：改传 ST 代理 Surface，wgpu（Vulkan/GLES 双后端）对 ST 背书 ANativeWindow 均可呈现（录屏/推流同款路径）；App 侧 OES 消费 → 滤镜 → TextureView。零 hook、零重编、零 Java glue 改动（Activity 本来就是自建 SurfaceView）。风险：wgpu 0.19 swapchain × ST 的设备兼容性需实机验证（GLES 后端兜底）；纹理翻转与像素格式由现有方向接口适配。显示/租约/生命周期照抄 Siglus 代理模板。

#### 3.3 ONScripter

- **状态：方案 B 已在实验分支落地**（经 vendored SDL2 胶水代理接入共用画质滤镜链）。
`ONScripter extends org.libsdl.app.SDLActivity`（类声明 `ONScripter.java:67`）；SDL2 胶水在仓库内，通过重写 `createSDLSurface` 返回 `OnsSurface`，在滤镜激活时将 `SDLActivity.getNativeSurface()` 重定向到 `OnsGraphicsProxy` 的 SurfaceTexture 代理 Surface。SDL2 接收由脚本尺寸（`nativeGetWidth`/`nativeGetHeight`）决定的 1:1 无黑边缓冲；App 侧在独立 GLES 线程（`OnsGraphicsProxy`）执行 OES 归一化与 `GraphicsFilterPipeline`，通过 `TextureView` 呈现，并经由 `OnsViewport` 实现物理触点精确归一化映射。滤镜与引擎内置 `--sharpness` 互斥，终端故障或驱动异常回退至直出路径（先摘除 `TextureView` 归还租约，再恢复 SDL 物理分辨率与归一化分母）。ONS 游戏原生分辨率低（≤800×600），2X CNN 预算宽裕，**超分收益为全引擎最大**。零 hook、零身份门禁、零 native 库重编。备选方案 A 归档。

#### 3.4 System35

`Xs35Activity extends org.libsdl.app32.SDLActivity`，胶水收口点与 ONS 同构。本分支**曾实现并已摘除**滤镜接入（代理 Surface + 视图尺寸被动观察 hook）：该引擎的视图尺寸在 Android 上跳过窗口内容尺寸调整，需要额外 hook 才能观测，改动面超过本期范围，故连同 `xsys35_gfx_hook.cpp`、`Xs35Surface`、`Xs35GraphicsProxy`、`Xs35Viewport` 一并回退；System35 引擎本身照常启动，无滤镜入口。重做时的路线仍为方案 B。

#### 3.5 System4

`Xs4Activity extends org.libsdl.app32.SDLActivity`（与 System35 共用胶水——按引擎/进程条件分流注入）。引擎为自有 GLES 3.0 管线（上游 `video.c/draw.c`），EGL 上下文建立在窗口 ANativeWindow 上——ST 背书窗口同样成立。GLES3 门禁宿主已有预检（`Xs4Activity`）。风险：引擎预检与滤镜 GLES3 门禁同源，低配设备一并回退经典路径。

#### 3.6 Ethornell/BGI

`session.attach(holder.getSurface(), width, height, generation)`（`EthornellActivity.java:340`）——与 FVP 同构的单点交接，方案 B 直接适用（wgpu）。约束：引擎本体仍处实验接入期（存档 ABI、IME、BMV/WMV 未定），滤镜叠加应在引擎稳定后进行，避免双实验叠加互相污染验收。文档已否决 CPU 回读路线，B 不回读、不受影响。

### 4. 第二梯队：稳定 `.so` 的 hook 路线（B 失败时的 fallback）

| 目标 | hook 面 | 锁版成本 | 备注 |
| --- | --- | --- | --- |
| ONS libonsyuri | `SDL_RenderPresent`/`SDL_UpdateTexture` 等 SDL 导入（krkrsdl3-SW 方案 A 同款） | 中：插件 zip 每次发版重钉，需随钉重核符号 | 收益同 3.3 |
| System4 libxsystem4 | 自有 GLES 管线的 `eglSwapBuffers/eglMakeCurrent` 导入 | 低：项目自有 fork，几乎不更新 | krkrsdl3-GL 的 EGL 代际管理可复用 |
| System35 libxsystem35 | 原评估：`SDL_UpdateWindowSurface`（software 驱动时） | 低：同上 | 本期不接入；如需重做，被动观察 hook 形态已在本分支历史中验证过可行性 |
| krkrsdl3-SW libkrkrsdl3 | §3.1 方案 A（见第二部分 §3） | 低：双库 Build ID 已锁 | B 成立时不需要 |

`.so` 更新频率盘点：libonsyuri（上游 OnscripterYuri 定期更新，App 每次重钉）→ hook 维护成本中等；libxsystem35/libxsystem4/libsys4（项目自有 fork，几乎不动）→ hook 成本最低；libsiglus（固定 TYN 提交）、libSDL2x/libSDL3（vendored 稳定）；librfvp/libethornell（xmoezzz 上游 + 实验期，B 已覆盖）。

### 5. 维持原判

- **Web 宿主（Tyrano/RPGM MV·MZ/VN/WebOther/NW.js）**：WebView 内部合成，App 无纹理控制点；本地 HTTP 改写 + JS 钩子设施只能承载"页内 JS/WebGL 第二后端"（素材级预超分或页内 Anime4K），属独立课题而非"接入共用 GLES 链"。
- **外置 APK 模块（Ren'Py / RPGM RGSS / Ruffle）**：画面在第三方 APK 进程，主 App 只能协议下发；需改 JoiPlay 系 runtime 才有意义。
- **Winlator（YU-RIS/CatSystem2/PC）与 PPSSPP/Eden**：容器/模拟器内部；Winlator 侧 DXVK/Lossless 类方案、模拟器自带缩放，主 App 无帧控制点。

### 6. 建议实施顺序（按「A/B 无明显性能差异」准则修订）

准则：A/B 均可行且性能等效的引擎优先做（实现选型取工程上更稳者）；A 有明确性能代价或仅单方案可行的引擎排后。

| 序 | 引擎 | 实现选型 | A/B 等效性 |
| --- | --- | --- | --- |
| 1 | ONScripter | **B**（vendored SDL2 胶水代理，**已落地**） | 等效：800×600 量级下 pass/延迟差异毫秒级不可感知；B 零身份门禁、零 hook 维护 |
| ~~2~~ | krkrsdl3-SW | **放弃维护**——A 实测不可行已剥离（SDL 命令队列化 + GLES2 窗口标志重写双重对抗）；B 待后续按需评估 | — |
| 2 | System35 | 本期不接入（实现已摘除，见 §3.4） | — |
| 3 | System4 | **B**（`app32` 胶水代理） | 等效：差异 ≈ 1 次 blit |
| 4 | FVP | B（唯一现实路径） | **不等效**：A 需强制 GLES 后端放弃 Vulkan，引擎级损失大于滤镜侧节省 → 排后 |
| 5 | Ethornell | B | 同上，且受引擎实验期约束 → 排后 |

选型理由：ONS/System4/krkrsdl3-SW 取 B——胶水层单点收口无身份门禁与 hook 维护，且 SDL 系引擎共享同一套代理/租约设施，边际成本递减。krkrsdl3-SW 的 A（hook SDL 导入 + ES3 属性提升）经实验证明不可行：SDL3 渲染命令队列化导致 hook 内立即执行的绘制被后续冲刷的 Clear 抹掉，且 GLES2 renderer 的窗口标志重写机制使得 ES3 属性注入无法可靠生效。FVP/Ethornell 的 A 需强制 wgpu 走 GLES 后端（放弃 Vulkan 渲染），引擎级性能代价明确，不满足等效准则，维持纯 B 并排在后。

每个引擎按既有流程走：另开分支 → 实现 → 五类独立审核 → 实机验收（含渲染驱动核验、帧尺寸语义、surface 重建周期、触点映射一致性）。

### 7. 验证边界

本文为接入路线评估，基于仓库代码与上游源码（krkrsdl3 @ e5cabc20 快照）静态分析，**不含任何实现与验收**；难度与收益预期不构成性能承诺。方案 B 的共同前提——引擎把输出完整画进代理窗口、SDL/wgpu 对 ST 背书窗口的驱动兼容性、surface 重建周期的代理生命周期管理——均需在各自实现分支中按上述验证点实机确认。`.so` 更新频率依据仓库当前校验清单与 fork 归属（README 许可证节）判断，上游政策变化不影响已接入引擎，但会改变第二梯队的锁版成本。


---

## 第二部分：原生引擎 Hook 维护指南

### 1. Kirikiroid2 滤镜 Hook 维护指南（1.3.9 / 1.3.4 GL；1.2.6 已移除）

本文面向维护者：当 `libgame.so`（1.3.9）/ `libgame134.so`（1.3.4）外置插件更新后，如何判定 KR2 滤镜 hook 是否仍然成立、如何重新核验与改钉（历史 `libgame126.so` 1.2.6 路径已从仓库移除，下文仅保留当前 HEAD 存续的 1.3.x 适配）。实现原理与边界见 [接入与审查报告](krkr-local-raw-filters.md) 与 [共用画质滤镜链](graphics-filters.md) 的 Kirikiroid2 小节。

#### 1. 适配器拓扑与锁定面

两个 native 桥编译进同一个 `libkrkr_bridge_v2.so`（`CMakeLists.txt`，链接 log/dl/GLES3）：

| 桥 | 职责 | 锁定面 |
| --- | --- | --- |
| `krkr_bridge.cpp` | I/O 重定向 + 启动信号 + 语言旁路 | 符号存在性、vtable 私有偏移、机器码旁路偏移（**无 Build ID 核验**） |
| `krkr_raw_frame_bridge.cpp` + `kirikiroid_library.h` | 滤镜适配器（全部滤镜锁定面，分别 377 / 128 行） | 1.3.9/1.3.4 双库 Build ID（`kirikiroid_library.h` `identify()`）、16 个 `resolve()` 符号（含 `Sprite` vtable）、vtable 补丁（含 `_ZTV14TVPWindowLayer`）、PLT 补丁、Itanium ABI 假设 |

运行库来源：`app/src/main/nativeplugins/kirikiroid2/arm64-v8a/`（4 个 so：`libSDL2.so`、`libffmpeg.so`、`libgame.so`、`libgame134.so`）与 `app/src/main/nativeplugins/kirikiroid2/manifest.json`（当前 `pluginVersion: 2`）→ zip.xz 随 APK → 首启解压到 `filesDir/engine_plugins/kirikiroid2/current/`。**设备上实际使用的 so 由 manifest `pluginVersion` 决定**——只换 zip 不递增 pluginVersion，设备会继续用旧解压目录的旧库（静默过期）。

##### 锁定面清单（更新库 = 逐项核验）

| # | 依赖 | 核验位置 | 断裂后果 |
| --- | --- | --- | --- |
| 1 | 双库 GNU Build ID（见 §3 表） | `kirikiroid_library.h:35-85` `identify()`（`krkr_raw_frame_bridge.cpp:254` 调用） | install 失败 → 引擎直出（安全） |
| 2 | 16 个 `resolve()` 符号（`TrianglesCommand::init` + `UpdateDrawBuffer` 与 thunk、`Sprite` vtable/析构组、`Texture2D` 与 `GL` 缓存同步；vtable 阶段另解析 `_ZTV14TVPWindowLayer`） | `krkr_raw_frame_bridge.cpp:244-273, :277-278` | 缺失 → `NATIVE_SYMBOLS` / `NATIVE_VTABLE` → 直出（安全） |
| 3 | Itanium ABI 假设：`Vertex=24/Triangles=24/Blend=8`（`static_assert`）、`Blend` 按值 x3、`completeObject = vptr[-2]` | `krkr_raw_frame_bridge.cpp:29-32, :81-84` | 断言编译期失败（安全）；运行期错位 = 花屏（见 §4） |
| 4 | vtable 扫描窗：`TVPWindowLayer` 2360 字节、`Sprite` 1632 字节，**按值匹配**非固定槽号 | `krkr_raw_frame_bridge.cpp:209-216, :276-286` | 槽位移出窗口 → `NATIVE_VTABLE` → 直出（安全） |
| 5 | thunk 偏移：`Sprite` 720、`TVPWindowLayer` 1040 | dlsym 符号清单内嵌于 mangled 名（`:256, :258, :268-269`） | **符号存在但偏移漂移 → 崩溃**（非安全） |
| 6 | `TrianglesCommand::init` 的 PLT 重定位存在 | `krkr_raw_frame_bridge.cpp:287-310` | 缺失 → `NATIVE_RELOCATION` → 直出（安全） |
| 7 | 每帧绘制契约：4 顶点/6 索引、四角 UV 对齐、整数 texel、blend ONE/ZERO、opacity 255、行序可判定 | `krkr_raw_frame_bridge.cpp:154-199` `initHook` | 不满足 → 单帧直出（安全） |
| 8 | JNI 回调签名：`process (IIIFFFFIIZZ)I`（末尾两布尔依次为 `topLeft` 与 `frameDirty`） | `krkr_raw_frame_bridge.cpp:311` ↔ `KirikiroidRawFrameBridge.java` | 不匹配 → `NATIVE_CALLBACK` → 直出（安全）；**改签名必须两侧同步** |
| 9 | 滤镜开启时 Cocos 上下文为 ES3（`Cocos2dxGLSurfaceView.setEGLContextClientVersion`） | `Anime4kRuntime.useGles3Context` | ES2 上下文 → GLES3 调用失败 |

另有 `krkr_bridge.cpp` 自身的锁定面（**与滤镜无关但同库更新会破坏**）：

| 项 | 值 | 断裂后果 |
| --- | --- | --- |
| `startupFrom` 参数 = GNU COW 老式 `std::string`（3 字头手工构造） | `krkr_bridge.cpp:51-76` `LegacyCowString` | 传 NDK `std::string` → 引擎损坏 |
| vtable 私有偏移：`scene+0x348`（容器）、vtable `0x238/8`（`formCount`） | `krkr_bridge.cpp:32-33` | ready 信号失效（20s 兜底揭示，安全）；偏移漂移可能误判 |
| 语言旁路偏移：`libgame` `0x9235C8`、`libgame134` `0x90F6CC`，prologue 期望 `0xD10203FF` | `krkr_bridge.cpp:135-137` | prologue 不匹配 → 放弃旁路（fail-open，语言检查复活） |

#### 2. 更新库后的核验流程（按序执行）

##### 2.1 Build ID 重钉

```sh
llvm-readelf.exe -n libgame.so      # 1.3.9
llvm-readelf.exe -n libgame134.so   # 1.3.4
```

替换 `kirikiroid_library.h:40, :43` 的两个字面量。**同时**：
1. `app/src/main/nativeplugins/kirikiroid2/manifest.json` 的 `pluginVersion` **递增**（当前 2；不递增设备会继续用旧解压 so）。
2. 若走用户导入渠道：更新 `rinne.kirikiroid2.zip.sha256` override/meta-data。
3. `docs/krkr-local-raw-filters.md` 二进制身份表追加新值。

##### 2.2 符号与 vtable 核验

```sh
llvm-readelf.exe -s libgame.so | findstr "UpdateDrawBuffer thunk _ZTV"
llvm-readelf.exe -s libgame134.so | findstr "UpdateDrawBuffer thunk _ZTV"
```

核对：`UpdateDrawBuffer` 的 mangled 参数类型（`P13iTVPTexture2D`）未变；thunk 符号存在且偏移仍是 720 / 1040；`_ZTV14TVPWindowLayer` 的 `st_size` 仍 ≥ 扫描窗（2360）；`_ZTVN7cocos2d6SpriteE` `st_size` ≥ 1632。符号清单有任何增删 → 同步 `krkr_raw_frame_bridge.cpp:244-286`。

##### 2.3 源码/反编译对照

1. **`TrianglesCommand::init` 调用点**：确认窗口层的舞台绘制仍经 `TrianglesCommand`（PLT hook 前提）；若改用其它命令类 → 更新 hook 符号。
2. **vtable 布局**：确认 `TVPWindowLayer` / `Sprite` 的 vtable 中 `UpdateDrawBuffer` / `getTexture` / `draw` / 析构槽**按值唯一命中**（扫描窗内多义命中会误改其它槽）。
3. **thunk 偏移**：反汇编 thunk 符号确认首指令仍为次级基址调整（偏移 720 / 1040 是类布局承诺）。
4. **`krkr_bridge.cpp` 私有偏移**（若该桥也在用）：反汇编确认 `scene+0x348` / `0x238` 与语言旁路偏移处指令签名。

##### 2.4 设备冒烟

实机跑一次：滤镜生效（看 `initHook` 命中）、overlay/Live2D 帧不丢层、暂停/恢复/退出无崩溃。logcat 关键标记：`Kr2RawFrame raw_install_stage=%d`（0=成功）。

#### 3. 当前钉版常量速查（HEAD 源码核验）

| 项 | 值 | 定义处 |
| --- | --- | --- |
| `libgame.so` Build ID | `de22234bffa0545d276b705487ca0c3d35101386` | `kirikiroid_library.h:40` |
| `libgame134.so` Build ID | `97c91143725ba2cf99471c0aed309cf66703fc79` | `kirikiroid_library.h:43` |
| 引擎锚符号 | `TrianglesCommand::init`（`dladdr` → `fbase`） | `krkr_raw_frame_bridge.cpp:28` |
| vtable 扫描窗 | `TVPWindowLayer` 2360、`Sprite` 1632 | `krkr_raw_frame_bridge.cpp:276-286` |
| thunk 偏移 | `Sprite` 720、`TVPWindowLayer` 1040 | mangled 名内嵌（`:256, :258, :268-269`） |
| 语言旁路偏移 | `libgame` `0x9235C8`、`libgame134` `0x90F6CC`（prologue `0xD10203FF`） | `krkr_bridge.cpp:135-137` |
| 私有布局偏移 | `scene+0x348`、vtable `0x238/8` | `krkr_bridge.cpp:32-33` |
| JNI 签名 | `process (IIIFFFFIIZZ)I` | `krkr_raw_frame_bridge.cpp:311` |
| ABI 断言 | `Vertex` 24 / `Triangles` 24 / `Blend` 8 | `krkr_raw_frame_bridge.cpp:29-32` |
| 插件 `pluginVersion` | `2`（4 库：`libSDL2`/`libffmpeg`/`libgame`/`libgame134`） | `app/src/main/nativeplugins/kirikiroid2/manifest.json` |

#### 4. 非回退安全断裂（重点盯防）

安装期与每帧校验失败都会安全回退（引擎直出），但以下断裂**直接产生错误画面/崩溃**：

1. **thunk 偏移漂移**（符号存在但类布局变）→ hook 落在错误偏移 → 崩溃。对策：§2.3 #3 的反汇编确认。
2. **vtable 扫描窗内多义命中** → 每处都改（含误改非目标槽）。对策：反汇编确认窗内唯一性。
3. **`UpdateDrawBuffer` 参数语义变化**（mangled 名相同但含义变）→ 类型混淆。对策：§2.3 对照源码。
4. **ABI 断言被删或真实布局变化** → 顶点错位花屏。对策：`static_assert` 保留 + 上游 Cocos 版本 diff。
5. **`pluginVersion` 未递增** → 设备静默使用旧库（Build ID 过旧 → hook 失败直出，表现为"滤镜失效但无报错"）。对策：更新 so 必须同步 manifest。

#### 5. 失效时的降级路径

任一核验失败 → 滤镜不安装、引擎直出、已保存滤镜设置保留（设置页显示"当前版本不支持"）。运行期每帧校验失败 → 单帧直出。日志定位：`Kr2RawFrame raw_install_stage=%d`（阶段号见 `InstallStage` 枚举 0–10）。

#### 6. 长期架构演进与 Hook 技术退场策略

1. **历史定位与维护边界**：Kirikiroid2 闭源运行库（当前保留 1.3.4 / 1.3.9；1.2.6 已移除）属于冻结期遗留方案，上游已无活跃开发。当前采用 Build ID 严苛锁定与 PLT/vtable hook 是为了在不动预编译库的前提下提供共用画质滤镜能力。维护策略为**只修不扩**，不为第三方魔改版新增符号探测或虚表偏移推导。
2. **开源替代终局**：基于现代化 SDL3 + GLES3 栈的开源 krkrsdl3 正在积极演进（OpenGL 舞台重定向已接入共用滤镜链）。随着 krkrsdl3 的脚本与插件兼容性达到生产标准，项目将逐步把默认吉里吉里启动流导向 krkrsdl3。
3. **退场判据**：当 krkrsdl3 能够无缝兼容既有 Kirikiroid2 游戏库且通过全量实机验证后，旧版闭源 `libgame.so` 的 Hook 适配层将转入非活动归档状态，最终解除对内部虚表与私有偏移的维护依赖。


---

### 2. 官方 Artemis 滤镜 Hook 维护指南（V1 / V6；V3/V4/V5 已移除）

本文面向维护者：当官方 Artemis 运行库（`libartemis*.so`）更新或新增官方内核版本后，如何判定滤镜适配器是否仍然成立、如何重新核验与登记（历史 V3/V4/V5 内核已从当前仓库移除，现存官方内核仅 V1 / V2 / V6，另附自研 `libartemis-clean.so`）。实现原理见 [共用画质滤镜链](graphics-filters.md) 的官方 Artemis 小节。

#### 1. 适配器拓扑与三层身份锁

适配器 = `artemis_loader.cpp`（bootstrap，`dlopen` 真实引擎并转发 `NativeActivity`）+ `artemis_official_graphics.cpp`（当前 3471 行，含 49 项强制 PLT hook [12 项 EGL/输入/`CStage::Display` + 37 项强制 GL 命令]、19 项可选 GL hook 与 117 项 deny 集，支持单上下文与 producer 双路线）+ `artemis_host_class.cpp`（ClassLoader 桥）。自研 clean 内核（`libartemis-clean.so`）完全不装 hook。

**三层身份锁（换库必须三层一起审计）**：

| 层 | 机制 | 定义处 |
| --- | --- | --- |
| ① so 文件 SHA-256 | Vorbis 修复派生表（保留 4 条历史/现存官方库修补项，其余原样直通）；派生只改 `.text` 的 CMP sf 位，**Build ID note 不变** | `ArtemisVorbisRepair.kt:19-47` |
| ② ELF GNU Build ID | `ArtemisLibraryIdentity.java:13-14` 映射表（2 条：`libartemis.so` / `libartemis-v6.so`）；native `loadedBuildId()` 读 `PT_NOTE` 后 JNI 比对 | `ArtemisLibraryIdentity.java:10-30` |
| ③ `CStage` 布局偏移 | `CStage::Display` 入口从对象 `+0x100`（宽）`+0x104`（高）读舞台逻辑尺寸——**偏移正确性由 Build ID 门禁背书，两表必须同源更新** | `artemis_official_graphics.cpp:2993-2994`（`displayStage` 全函数 `:2988-3029`，读取与钳制 `:2988-2997`） |

##### 锁定面清单

| # | 依赖 | 核验位置 | 断裂后果 |
| --- | --- | --- | --- |
| 1 | `engineLibName` 白名单（4 库名 / 3 官方枚举） | `artemis_loader.cpp:121-126`、`NativePluginConstants.ARTEMIS_REQUIRED_LIBS`（`:66-71`）、`app/src/main/nativeplugins/artemis/manifest.json` 维护 4 个库名（`libartemis.so` / `libartemis-compatible.so` / `libartemis-v6.so` / `libartemis-clean.so`）；`ArtemisVersion.java:9-12` 维护 3 个官方内核枚举（`V1` / `V2` / `V6`，`artemis-clean` 走独立 `ArtemisActivityClean` 不在枚举中） | 不在白名单 → `finishActivity` |
| 2 | 2 条官方库 Build ID（V2 无 `PT_NOTE` 永不通过；clean 不在表） | `ArtemisLibraryIdentity.java:13-14` + native `verifyLibrary` | 不符 → `failed()` → 直出（安全） |
| 3 | 49 项强制 PLT 导入（12 项 EGL/输入/`CStage::Display` + 37 项强制 GL 命令）+ 19 项可选 GL 导入 + 117 项 `kDeniedContentChangeImports` deny 集（见 `graphics-filters.md` 与 `SPEC` §12） | `artemis_official_graphics.cpp:3206-3253, :3331-3452` | 任一强制导入缺失 → `failed()` 整体回退直出（安全）；仅命中 deny 集才置 `singleContextDenied = true` 回退 producer 路径（安全） |
| 4 | `CStage` 偏移 `+0x100`/`+0x104`（int 宽高）+ 合理性钳制 `0<w,h≤8192` 且 `w*h*16<256MB` | `artemis_official_graphics.cpp:2988-2997` `displayStage` | **偏移漂移 = 错误画面（拉伸/错位）而非回退**——钳制是唯一防线 |
| 5 | `original` 帧判定：默认帧缓冲 + 全视口 + 尺寸==舞台逻辑宽高 | `artemis_official_graphics.cpp:2976-2977` `swap` 内 | 不成立 → 滤镜静默旁路（画面正常但滤镜无效） |
| 6 | `submitted` 时间戳 ↔ `SurfaceTexture.getTimestamp` 同源（producer 路径，frames 容量 12，丢旧） | cpp `swap` ↔ Proxy 消费 | 不匹配 → 丢帧/黑帧 |
| 7 | `DisconnectResult` 数值协议 `0/1/2` | cpp ↔ `ArtemisOfficialDisconnect.java` 两侧硬编码 | 不对齐 → 直出恢复死锁 |
| 8 | R8 keep：`ArtemisOfficialBridge` public static（`consumer-rules.pro`） | 构建配置 | 混淆 → `failed()` → 直出（安全） |
| 9 | Vorbis 派生：源 SHA-256 → 补丁点旧指令字 → 输出 SHA-256 | `ArtemisVorbisRepair.kt:19-47` | 新库不命中表 → 原样直通（安全，但音频 bug 回归） |

#### 2. 更新/新增官方内核后的核验流程（按序执行）

##### 2.1 二进制层

```sh
llvm-readelf.exe -n libartemis-<ver>.so          # GNU Build ID → ArtemisLibraryIdentity 登记
llvm-nm.exe -D --defined-only libartemis-<ver>.so | findstr "CStage7Display"
llvm-readelf.exe -r libartemis-<ver>.so | findstr "eglCreateWindowSurface CStage7Display AMotionEvent_getX"
# 确认 49 项强制 hook（12 项 EGL/输入/CStage + 37 项 GL）的 JUMP_SLOT 仍在（任一缺失将整体回退直出）；若走单上下文，同时核对 GL 导入面不在 117 项 kDeniedContentChangeImports deny 集内（命中将回退 producer 路径）
certutil -hashfile libartemis-<ver>.so SHA256     # 若命中 Vorbis 修复范围，同步 ArtemisVorbisRepair 表
```

##### 2.2 反编译核验（Ghidra/IDA，最高危项）

1. **`CStage::Display`**：确认舞台宽高仍在对象 `+0x100`/`+0x104`（int）；若布局变 → 更新 `artemis_official_graphics.cpp:2993-2994` 偏移常量并**实测**（8192/256MB 钳制只挡离谱值，不挡"合理但错误"的宽高）。确认 `SetupScale` 的 `SCVTF` 转换仍消费这两个 int。
2. **EndRender/swap 路径**：确认 `eglSwapBuffers` 时 FBO 绑定为 0、viewport 为全屏、帧尺寸等于舞台逻辑宽高（`original` 判定的四个条件）。
3. **eglCreateWindowSurface 调用序**：确认引擎每帧/每会话的调用模式未变（代理 Surface 的注入点）。
4. **`CSoundTrack::Read`**（音频守卫）：确认 vtable 槽 16 语义未变（`artemis_loader.cpp:148`）。

##### 2.3 登记与同步（七处）

1. so 放入 `engine/src/main/nativeplugins/artemis/arm64-v8a/`。
2. Vorbis 修复范围命中 → `ArtemisVorbisRepair.kt` 登记三组值；未命中 → 原样直通（确认音频 bug 状态）。
3. `ArtemisLibraryIdentity.java` 登记 Build ID。
4. `artemis_official_graphics.cpp:2993-2994` 复核 `CStage` 偏移（`dladdr`+`dlsym` 实测）。
5. `app/src/main/nativeplugins/artemis/manifest.json` 的 `pluginVersion` **递增**（当前 `33`）。
6. 白名单同步（`artemis_loader.cpp:121-126` ↔ `NativePluginConstants.ARTEMIS_REQUIRED_LIBS` ↔ manifest `libs` 当前维护 4 库名，含 `libartemis-clean.so`；`ArtemisVersion` 维护 3 个官方内核枚举 `V1` / `V2` / `V6`，clean 走独立 `ArtemisActivityClean`）。
7. 回归 `ArtemisLibraryIdentityTest` / `ArtemisVorbisRepairTest` / `ArtemisVersionTest`。

##### 2.4 设备冒烟

实机跑官方内核样本：滤镜生效（分析器显示节点）、回退链落到不支持版本时滤镜自动关闭（`updateGraphicsRequest`）、直出恢复不崩溃、overlay 面板数据正常。**实际游玩验收的既有边界见 `graphics-filters.md` 的官方 Artemis 小节**（两小时游玩验收为历史版本的记录，新内核需重新积累）。

#### 3. 当前钉版常量速查（HEAD 源码核验）

| 项 | 值 |
| --- | --- |
| `libartemis.so`（V1）Build ID | `e76366a3d04e34520fa9fe30a1b6f0d626ececb5`（`ArtemisLibraryIdentity.java:13`） |
| `libartemis-v6.so`（V6）Build ID | `2f23ca4efda5953cefd19f0fa09687844a008e42`（`ArtemisLibraryIdentity.java:14`） |
| `libartemis-compatible.so`（V2） | **无 `PT_NOTE`**（永不通过滤镜门禁，直出） |
| `libartemis-clean.so` | 自研内核（不在 Build ID 映射表，不装官方 hook） |
| 历史移除内核（V3 / V4 / V5） | 已移除出包、`ArtemisVersion` 与 `ArtemisLibraryIdentity` |
| `CStage` 偏移 | `+0x100` 宽、`+0x104` 高（`artemis_official_graphics.cpp:2993-2994`） |
| 强制 PLT hook（49）与可选/deny | 12 项非 GL（`CStage::Display`、`eglChooseConfig`/`CreateContext`/`DestroyContext`/`CreateWindowSurface`/`DestroySurface`/`QuerySurface`/`MakeCurrent`/`SwapBuffers`、`AMotionEvent_getX`/`getY`、`AInputQueue_getEvent`）+ 37 项强制 GL；另有 19 项可选 GL（`optionalHooks`）与 117 项 `kDeniedContentChangeImports` deny 集（`artemis_official_graphics.cpp:3206-3402`） |
| 滤镜支持版本 | V1 / V6（V2 不支持；clean 待上游 PR 接入） |
| `pluginVersion` | `33`（`app/src/main/nativeplugins/artemis/manifest.json`，4 库） |

#### 4. 非回退安全断裂（重点盯防）

1. **`CStage` 偏移漂移**：Build ID 登记了新库但偏移未核对 → 错误画面（钳制只挡离谱值）。
2. **`original` 判定失效**：新内核 viewport/FBO 语义变化 → 滤镜静默旁路（表现为"滤镜无效"）。
3. **时间戳失配**：`submitted` ↔ `getTimestamp` 不同源 → producer 路径丢帧/黑帧。
4. **白名单不同步**：cpp / 常量 / manifest（当前 4 个库名，含 `libartemis-clean.so`）与 `ArtemisVersion`（当前 3 个官方内核枚举 `V1` / `V2` / `V6`，clean 走独立 Activity）任一遗漏 → 该版本 `finishActivity` 或回退链断裂。
5. **Vorbis 派生表过期**：新库 SHA 不命中 → 原样直通，音频 bug 回归（非滤镜问题但同批更新）。

#### 5. 与其它宿主指南的差异

- Artemis 的强制 PLT hook 面（49 项：12 项 EGL/输入/`CStage` + 37 项强制 GL）比 krkrsdl3-SW（已归档 8 个 SDL 导入）覆盖更广，其中 EGL/输入 API 属平台稳定 ABI，**断裂主因是 `CStage` 布局、Build ID 与 117 项单上下文 GL 导入 deny 集**（任一强制导入缺失会整体回退直出，命中 deny 集才回退 producer 路径）。
- Artemis 有版本回退链与逐候选滤镜门禁（`updateGraphicsRequest`），断链后果是"落回不支持版本"（安全）；krkrsdl3 无回退链。
- 官方 Artemis 支持单上下文 GLES3 与完整代理（OES → 归一 → 滤镜 → letterbox 上屏）双路线，与 krkrsdl3-SW 的"命令队列注入"不同——无 SDL 命令队列化风险。

#### 6. 长期架构演进与 Hook 技术退场策略

1. **闭源技术债务现状**：官方 Artemis 闭源内核（当前保留 V1 / V2 / V6）存在 `CStage` 内存偏移反编译硬编码、版本白名单多处同步及 Vorbis 解码内存修补等固有包袱，维护成本随版本裂变而升高。
2. **Clean 内核替代路线**：项目主导开发的自研开源内核 `libartemis-clean.so` 走独立 `ArtemisActivityClean` 路径，避免了对 `CStage` 偏移、PLT hook 与 Vorbis SHA 派生表的依赖；其共用画质滤镜接入待 [artemis-compat PR #11](https://github.com/Weiss-UltimateSavior/artemis-compat/pull/11) 合并后落地（与 `docs/graphics-filters.md` 一致）。
3. **退场与交接机制**：待 `artemis-compat` 上游分支合并且 Clean 内核通过实机游戏全量验证后，Clean 内核将作为官方推荐默认引擎。官方闭源内核 Hook 降级为旧作品兼容兜底，不再登记新衍生官方内核，实现从二进制 Hook 方案向无侵入标准路线的安全退场。


---

### 3. krkrsdl3 软件渲染滤镜 Hook 维护指南（方案 A，**已归档**）

> **状态：已归档。** 方案 A（hook）经实机验证不可行，实现已完整剥离（见本文件第一部分 §3.1）。以下内容仅作为历史参考与 git 考古索引保留；若未来重新评估 hook 路线，可参照本节的锁定面清单与核验流程。

本文面向维护者：当 `libkrkrsdl3.so` / `libSDL3.so` 更新后，如何判定 SW 滤镜 hook（`krkrsdl3_graphics.cpp` software 分支，实验分支 `exp/fbgames-graphics-filter`）是否仍然成立、如何重新核验与改钉。实现原理与边界见 [接入与审查报告](krkr-local-raw-filters.md) 的 krkrsdl3 软件渲染小节；B 路线（无锁版）的取舍见本文件第一部分 §3.1。

#### 1. hook 依赖的锁定面（更新库 = 检查清单）

适配器对打包库的依赖分五层，**任一层变化都可能使 hook 失效或行为错误**。安装期会逐项核验并在失败时安全回退引擎直出（`status = MISSING_ABI / SIZE_UNAVAILABLE / NATIVE_FAILED`，日志 tag `Krkrsdl3Graphics`），所以断裂的表现是"滤镜静默失效、游戏正常直出"，不会崩溃。

| # | 依赖 | 核验位置 | 断裂后果 |
| --- | --- | --- | --- |
| 1 | 双库 GNU Build ID（`e088c456…` / `c6ae4383…`） | `krkrsdl3_graphics.cpp` `identify()`，常量在 `nativeInstall` | install 失败 → 引擎直出 |
| 2 | 8 个 SDL3 导入各恰一条 `R_AARCH64_JUMP_SLOT` | `collectPltSoftware`（counts==1 强制） | collect 失败 → install 失败 → 直出 |
| 3 | 15 个 SDL 函数（8 个被 hook 的原函数 + 7 个 Properties/Blend/GLAttr 辅助函数）经 `dlsym` 可解析且落在 libSDL3 代码段 | `nativeInstall` 的 `sdlFunction` lambda | install 失败 → 直出 |
| 4 | **调用顺序契约**：每帧 = 主纹理 `SDL_UpdateTexture` → 首个 `SDL_RenderTexture` 画主纹理 → overlay 画纹理 → `SDL_RenderPresent` | 无运行时核验，靠 Build ID 锁版 + 源码对照 | **顺序变化 = 错误画面而非回退**（见 §4） |
| 5 | 语义契约：`SDL_UpdateTexture` pixels 生命周期至调用返回；GLES2 renderer 接受 ES≥2 profile + OGL flag；`SDL_GetWindowSize`（逻辑点）= CPU 帧坐标 | 源码/反编译对照 | 行为错误或回退 |

#### 2. 更新库后的核验流程（按序执行）

##### 2.1 Build ID 重钉

```sh
# 使用 %ANDROID_NDK_HOME% 或本地 NDK toolchains 目录下的 llvm-readelf：
llvm-readelf.exe -n libkrkrsdl3.so     # 取 NT_GNU_BUILD_ID
llvm-readelf.exe -n libSDL3.so
```

把输出值替换 `krkrsdl3_graphics.cpp` `nativeInstall` 内的两处字面量。**只改 Build ID 不够**——继续 §2.2。

##### 2.2 导入符号与重定位核验

```sh
llvm-nm.exe -D --undefined-only libkrkrsdl3.so
# 确认以下 8 个符号仍在（SDL_UpdateTexture / SDL_RenderTexture / SDL_RenderPresent /
#   SDL_CreateTexture / SDL_DestroyTexture / SDL_CreateRenderer / SDL_DestroyRenderer / SDL_CreateWindowWithProperties）

llvm-readelf.exe -r libkrkrsdl3.so
# 确认上述 8 个符号各恰一条 R_AARCH64_JUMP_SLOT（计数 != 1 → collectPltSoftware 直接失败）
```

符号消失 / 改名（如 SDL 大版本弃用 `SDL_RenderTexture`）→ 更新 `collectPltSoftware` 的 hooks 表与对应 wrapper 签名。

##### 2.3 上游源码对照（有源码时）

对照本地外部参考路径 `../reference/krkrsdl3-upstream/src`（位于工作区外层参考目录，未入库）与新版本源码 diff，逐项确认：

1. **`core/render/TVPCompositor.cpp` `TVPRenderOnce`**：仍是 BeginFrame → 主纹理 `DrawWindowTexture` → overlay 循环 → EndFrame 的固定顺序，且主纹理仍是第一个绘制（帧内首绘制识别主纹理的唯一依据；首帧学习主纹理期间放行原图直出，次帧起正常捕获与滤镜处理）。
2. **`core/render/backend/SWRenderBackend.cpp`**：`UpdateWindowTexture` 仍整帧更新（无部分矩形）、`EndFrame` 仍直通 `TVPRenderPresentBackend`。
3. **`environ/sdl3/sdl3_app.cpp`**：`TVPUpdateTextureBackend` 仍调 `SDL_UpdateTexture`（整帧、`nullptr` rect）；`TVPCreateWindowForBackend` 仍调 `SDL_CreateWindowWithProperties(props)`；窗口创建 flags 是否新增了 `SDL_WINDOW_OPENGL`（若已带，`SDL_CreateWindowWithProperties` hook 的属性注入成为唯一生效点，行为不变）；`SDL_CreateRenderer` 仍为 `NULL` 驱动名。
4. **SDL3 `src/render/opengles2/SDL_render_gles2.c` `GLES2_CreateRenderer` 与 `GLES2_CreateTexture`**：接受条件是否仍为「窗口带 `SDL_WINDOW_OPENGL` + profile=ES + major≥2」；`SDL_GLAttr` 枚举值（MAJOR=17/MINOR=18/PROFILE_MASK=20）与 `SDL_GL_CONTEXT_PROFILE_ES=0x0004` 是否漂移。确认 `GLES2_CreateTexture`（`SDL_render_gles2.c:1639-1658`）行为：SDL3 对 `SDL_PROP_TEXTURE_CREATE_OPENGLES2_TEXTURE_NUMBER` 置 `texture_external = true`（使 `DestroyTexture` 不删除 GL 纹理名），但创建期间仍会执行 `glTexImage2D(..., NULL)`；因此适配器包装专用的呈现纹理（`swResources.presentTexture`），按匹配的输出尺寸跨帧缓存 wrapper，并在 wrapper 创建后通过 `glCopyTexSubImage2D` 写入内容，规避初始冲刷。
5. **SDL3 `src/render/SDL_render.c`**：`SDL_RenderTexture`/`SDL_RenderClear` 仍是命令队列（flush 于 `SDL_RenderPresent`）——若 SDL 改为立即执行，注入的 wrapper 绘制顺序语义需要重审。

##### 2.4 无源码时的反编译核验（Ghidra/IDA 要点）

对新的 `libkrkrsdl3.so`（arm64），按以下顺序在反编译器中确认（函数名经 dynsym 可直接定位；未导出的 `TVPRenderPresentBackend` 等通过 `SWRenderBackend::EndFrame` 的调用图找到）：

1. **`TVPRenderOnce`（`_ZN8krkrsdl313TVPRenderOnceEii`）**：确认控制流仍是「GetRenderBackend → BeginFrame → Get_Current_Sprite → DrawWindowTexture(主) → overlay 循环 → EndFrame」线性结构；主纹理 draw 是第一个 `DrawWindowTextureEx` 调用。
2. **`SWRenderBackend::UpdateWindowTexture`**：确认只转发一次 `SDL_UpdateTexture` 且 rect 参数为 0。
3. **`sdl3_app.cpp` 窗口创建**：找 `SDL_CreateWindowWithProperties` 调用点，确认 flags 不含 `0x2`（SDL_WINDOW_OPENGL）——若已含，保留 hook（属性注入仍必要）；若不含，hook 的 flag 注入生效。
4. **`SDL_GL_SetAttribute` 交叉引用**：确认引擎自身是否在任何 `SDL_CreateRenderer` 之前预设了 GL 属性（若预设了 ES3，注入为幂等冗余，无害）。
5. **`SDL_UpdateTexture` 调用图**：确认除 `TVPUpdateTextureBackend` 外没有第二个调用者（否则会误吞第三方更新）。

##### 2.5 设备冒烟

重新钉版后必须实机跑一次 krkrsdl3 软件渲染样本：滤镜生效、overlay 帧不丢层、退出无崩溃。日志确认 install 成功（无 `Krkrsdl3Graphics` ERROR 行）。

#### 3. 当前钉版常量速查（2026-11-01 核验）

| 项 | 值 | 定义处 |
| --- | --- | --- |
| libkrkrsdl3.so Build ID | `e088c4563fdbc75118564e68acf6e3e70ec83a91` | `krkrsdl3_graphics.cpp` nativeInstall |
| libSDL3.so Build ID | `c6ae4383b991cdf2c14bc5555f7273923e47581a` | 同上 |
| 上游版本锚 | `e5cabc20a0ebcc4bc024fa245fc661036bc1790a` | krkrsdl3/krkrsdl3 |
| hook 符号（8） | SDL_UpdateTexture / SDL_RenderTexture / SDL_RenderPresent / SDL_CreateTexture / SDL_DestroyTexture / SDL_CreateRenderer / SDL_DestroyRenderer / SDL_CreateWindowWithProperties | `collectPltSoftware` |
| SDL_GLAttr | MAJOR=17, MINOR=18, PROFILE_MASK=20 | SW section 常量 |
| SDL_GL_PROFILE_ES | 0x0004 | 同上 |
| SDL_WINDOW_OPENGL | 0x0000000000000002 | 同上（`kWindowOpenGl`） |
| 引擎 GLES 锚符号 | `_ZN8krkrsdl322TVPOpenGLRenderBackend10BeginFrameEii`（`kBegin`，identify anchor） | `nativeInstall` |
| GL 模式 vtable | `TVPOpenGLRenderBackend` 304 字节（GL 模式专用，SW 不依赖） | `collectVtable` |

#### 4. 已知的非回退安全断裂（重点盯防）

安装期失败会安全回退，但**调用顺序契约（§1 #4）在运行期断裂不会回退**——适配器会继续按错误顺序合成画面（错误内容而非崩溃）。以下上游变化必须通过 §2.3/§2.4 提前发现：

- 主纹理不再是帧内首个 `SDL_RenderTexture`（如新增 Underlay 先画）。
- `SDL_RenderTexture` 出现部分纹理绘制（src 非空导致主纹理被裁切绘制）。
- overlay 与主纹理共用同一 SDL_Texture（帧内首绘制误判）。
- `SDL_UpdateTexture` 的 pixels 生命周期缩短到调用内部分有效（memcpy 仍安全，但任何"延迟上传"优化都会读到悬垂数据）。

出现任一变化时的处置：更新 wrapper 契约逻辑；无法安全适配时，将 `collectPltSoftware` 的 hooks 表清空或给 `nativeInstall` 的 software 分支直接返回失败——hook 完全不安装，引擎直出，已有滤镜设置保留不丢（设置页显示为"当前版本不支持"）。

#### 5. 向 B 路线迁移的判据

满足任一条即建议放弃 A、转向 B（见本文件第一部分 §3.1）：

- 一次上游更新同时破坏 ≥2 层锁定面（如 Build ID + 调用顺序）。
- 反编译确认 `TVPRenderOnce` 结构性重写（不再可按首绘制识别主纹理）。
- 需要为 overlay/Live2D 提供滤镜（A 的逐帧契约无法覆盖多后端混合）。
- krkrsdl3 上游进入活跃迭代期（月更级），锁版维护成本超过 B 的一次性 Java 胶水改造。

> System35 滤镜接入已摘除（视图尺寸观察 hook 与宿主代理一并回退），本节维护指南随之移除；System35 引擎本身仍按上游原样启动，无滤镜入口。
