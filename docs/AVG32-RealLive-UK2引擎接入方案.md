# AVG32 / RealLive / UK2 三引擎接入方案（framebuffer 引擎组）

> 状态：**方案待评审（未实施）；M0 实测已完成（见 §2.6）**
> 关联上游：`Weiss-UltimateSavior/siglus_rs` @ `85730c2`（新增 `game_launcher` / `engine-detect` / `avg32` / `reallive` / `uk2`）
> 结论摘要：**复用上游统一库 `game_launcher` + 自研 JNI shim + 新增一个 framebuffer 宿主 Activity**；
> M0 实测确认单库同时导出 `siglus_android_*`（含 fork 新增的输入 ABI）与 `game_*`，体积仅 +4.0MB，
> **方案 A 定稿**，Siglus 链路继续复用同一 `.so`、宿主代码不动。
> 一期范围 = 扫描识别 → 启动 → 文本/音频/画面 → 存档读写 → 文本编码设置；**不做封面在线抓取、不做独立存档镜像**。

---

## 0. 术语与命名

| 项 | 取值 |
|---|---|
| EngineType | `AVG32("AVG32")`、`REALLIVE("RealLive")`、`UK2("UK2")` |
| 引擎页分组 | GAL（`EngineTab.GAL`） |
| launchMode | `internal.framebuffer`（三引擎共用） |
| 宿主类 | `com.core.games.FramebufferGameActivity`（进程 `:fbgames`） |
| JNI 包装 | `com.core.games.NativeGames`（`System.loadLibrary("games_bridge")`） |
| 自研 shim | `engine/src/main/cpp/games_bridge.cpp` → `libgames_bridge.so` |
| 原生引擎库 | `libsiglus.so`（= 上游 `libgame_launcher.so` 改名）**或** `libgames.so`（方案 B，见 §3.1） |
| 存档目录 | 见 §6（AVG32 = `<根>/SAVE.INI` 单文件；RealLive = `<根>/savedata_rs`；UK2 = `<根>/FLAG00.DAT`…`FLAG09.DAT`） |
| 引擎设置分类 | 新增 `EngineSettingsKind.FRAMEBUFFER`（文本编码） |

---

## 1. 背景

`siglus_rs` 上游已从「SiglusEngine 的移植」扩展为**四引擎 Rust 重实现**，并把多引擎能力收敛进两个新 crate：

| 引擎 | 上游 crate | 规模 | 单测 | 渲染方式 |
|---|---|---|---|---|
| SiglusEngine | `siglus_scene_vm` | — | — | GPU（wgpu）+ 平台原生宿主 |
| RealLive | `reallive` | ~94.9k 行 | 62 | CPU 软渲染 → RGBA 帧 |
| AVG32 | `avg32` | ~15.8k 行 | 34 | CPU 软渲染 → RGBA 帧 |
| UK2（PC-98） | `uk2` | ~18.1k 行 | 34 | CPU 软渲染 → RGBA 帧 |

TyranorNext 现状：`EngineType` 已有 `SIGLUS`、`FVP` 等内置引擎；宿主模型是「每引擎一个 `Activity` + 一个自研 `*_bridge.so`（dlopen 引擎 .so）」。FVP 的 `com/core/fvp/{FvpActivity, NativeRfvp}.java` + `librfvp_bridge.so` + `librfvp.so` 就是本方案要照抄的模板。

**关键差异**：Siglus 是 `SurfaceView + wgpu + siglus_android_*` ABI；AVG32/RealLive/UK2 是 `game_fb_*` 帧缓冲 ABI（CPU 出帧 → 宿主上传纹理/位图），**不能复用 `SiglusActivity`**。

---

## 2. 上游能力勘察（实测/读源）

### 2.1 C ABI（`game_launcher`，见 `crates/game_launcher/src/ffi.rs`）

```c
/* 探测 / 识别 */
char* game_scan_json(const char* path, int depth, const char* cover_cache_dir);
char* game_probe_json(const char* root, const char* nls, const char* cover_cache_dir);
/* 探测结果字段：id/root/engine/engine_name/supported/unsupported_reason/title/
 *               cover/cover_kind/nls/nls_options[{id,label}] */

/* 运行（RealLive / AVG32 / UK2） */
void* game_fb_open(const char* root, const char* engine, const char* nls,
                   char** error_out);              /* 失败返回 NULL 并回填错误串 */
int   game_fb_step(void* h, uint32_t dt_ms);       /* 0=运行中 1=结束 */
const uint8_t* game_fb_frame(void* h,
                             uint32_t* width, uint32_t* height);
                                                   /* 紧排 RGBA8；指针下次调用前有效 */
void  game_fb_pointer_move(void* h, int32_t x, int32_t y);
void  game_fb_pointer_button(void* h, int32_t button, int32_t pressed); /* 0=左 1=右 */
void  game_fb_wheel(void* h, int32_t up);
void  game_fb_key(void* h, uint32_t code, int32_t pressed);
void  game_fb_text(void* h, const char* utf8);     /* IME 提交 */
int   game_fb_cursor_visible(void* h);             /* 1=宿主应显示指针 */
const char* game_fb_title(void* h);                /* 不释放，下次调用前有效 */
void  game_fb_close(void* h);

void  game_add_font_file(const char* path);
void  game_string_free(char* ptr);
```

键码：`1 Enter / 2 Escape / 3 Space / 4-7 上左下右 / 8 PageUp / 9 PageDown / 10 Home / 11 End / 12 Backspace / 13 Tab / 14 Ctrl / 15 Shift / 0x100+n Fn(n=1..12) / 0x10000+codepoint 字符`。
注意：**没有独立的 `frame_size` ABI**——每帧直接调用 `game_fb_frame(h,&w,&h)` 取尺寸与像素指针（上游 shim 的 `fbFrameSize`/`fbCopyFrame` 只是它的 JNI 包装）。

### 2.2 引擎识别（`engine-detect`）

| 引擎 | 磁盘证据 |
|---|---|
| SiglusEngine | `Scene.pck` 或 `Gameexe.dat` |
| UK2 | `UK2.CFG`，或含 `<< UK2 TEXT Ver1.00 >>` 头的 `.MES` |
| AVG32 | `Gameexe.ini` + `PACL` 的 `SEEN.TXT`，或散装 `TPC32` `SEEN###.TXT` |
| RealLive | `Gameexe.ini` + 10000 项 TOC 的 `SEEN.TXT`（场景头 `0x1d0`／AVG2000 `0x1cc`），或散装 `SEEN####.TXT` |

RealLive 的档案位置通过 `Gameexe.ini` 的 `#FOLDNAME.TXT` 定位，回退游戏根与 `DAT/`。

### 2.3 上游 Android 实现（可参考，shim 建议自研）

- `com.chino.siglus.NativeGames`：`System.loadLibrary("siglus")` + `loadLibrary("siglus_jni")`；方法 `scanJson/probeJson/addFontFile/fbOpen/fbStep/fbFrameSize/fbCopyFrame/fbPointerMove/fbPointerButton/fbWheel/fbKey/fbText/fbClose`。
- `FramebufferGameActivity`：`Choreographer` 帧循环；`fbStep` → `fbCopyFrame`（DirectByteBuffer）→ `FramebufferView` 画位图；触摸 tap=左键、双指 tap=右键、双指滑动=滚轮；按钮 skip/menu；`EXTRA_ROOT/EXTRA_ENGINE/EXTRA_NLS/EXTRA_TITLE`。
- `siglus_jni.cpp`：`dlopen("libsiglus.so")` + `dlsym`（既取 `siglus_android_*` 也取 `game_*`）。
- 上游构建脚本 `platform/scripts/package_android_apk.sh`：`LAUNCHER_CARGO_PKG=game_launcher`，产物 `libgame_launcher.so` **改名 `libsiglus.so`**，单库同时导出两类 ABI。

### 2.4 缺口清单（须处理）

| # | 缺口 | 处理方式 |
|---|---|---|
| G1 | 单库是否同时导出 `siglus_android_*` 与 `game_*` | **M0 已解决**：实测两类 ABI 全部导出（§2.6），无需方案 B |
| G2 | 非 Siglus 路径无 `android_logger` 初始化 | shim 内自行 `android_logger` 初始化，或复用 `siglus_android_init_context` |
| G3 | 三引擎都需要 CJK 字体 | 启动前 `game_add_font_file`：优先游戏自带 `dat/*.ttf`，回退 FVP/Siglus 的默认字体 |
| G4 | AVG32 存档是单文件 `SAVE.INI` | 存档管理需支持「文件模式」或 `root + 文件名过滤`（§6） |
| G5 | UK2 存档文件名/目录 | **M0 已解决（读源确认）**：`<游戏根>/FLAG00.DAT`…`FLAG09.DAT`（10 槽，见 §6） |
| G6 | CPU 软渲染性能（1080p 拉伸） | 位图上传由 Canvas/GPU 完成；实测帧率与内存（§7） |
| G7 | 三语言文案、三语言严格一致 | 新增 strings 键并过 `tools/check-hardcoded-ui-strings.py` |
| G8 | 标题/封面已有 Kotlin 实现 | 可选接入 `game_probe_json` 提升非 Siglus 封面/标题质量 |
| G9 | 仅 arm64-v8a 目标 | 与现有 `engine` 模块一致；x86_64 模拟器二期 |

### 2.6 M0 实测记录（2026-09-25，siglus_rs @ `85730c2`）

构建命令：

```bash
ANDROID_NDK_HOME=$HOME/Library/Android/sdk/ndk/28.0.13004108 \
  cargo ndk -t arm64-v8a -o /tmp/games-android build --release -p game_launcher
# 1m57s，产物 libgame_launcher.so
```

| 指标 | 实测值 | 结论 |
|---|---|---|
| 产物 | `libgame_launcher.so`，24,602,928 B，stripped | 单库可用 |
| 体积增量 | 对比现 `libsiglus.so`（20,645,832 B）**+3,957,096 B（+4.0MB）** | 三引擎总增量仅 4MB，可接受 |
| 动态依赖 | 仅 `liblog/libandroid/libdl/libOpenSLES/libm/libc` | 无第三方运行库 |
| LOAD 对齐 | `0x4000`（16KB） | 满足 Android 15+/16KB page size |
| `siglus_android_*` 导出 | 16 个全在，含 `create/step/touch/key_event/ime_area/ime_preedit/text_input/editbox_accepts_direct_text/set_native_messagebox_callback/submit_messagebox_result/init_context/set_surface/resize/destroy/key_down/key_up` | **G1 解决**：现有 `libsiglus_bridge.so` dlopen 链路不变 |
| `siglus_*` 其他导出 | `siglus_game_name_from_dir`、`siglus_game_cover_path_from_dir`、`siglus_game_cover_mime_from_dir`、`siglus_string_free` | 标题/封面 ABI 也在 |
| `game_*` 导出 | 15 个全在：`game_fb_open/step/frame/pointer_move/pointer_button/wheel/key/text/close/cursor_visible/title`、`game_scan_json`、`game_probe_json`、`game_add_font_file`、`game_string_free` | 新宿主所需 ABI 齐全 |

**结论**：方案 A 通过；`engine/src/main/jniLibs/arm64-v8a/libsiglus.so` 可直接替换为本产物（后续 Siglus 回归见 §7.2）。

---

## 3. 总体设计

### 3.1 路线选择

| | 方案 A：单库（推荐） | 方案 B：双库（回退） |
|---|---|---|
| 做法 | `libsiglus.so` 换成 `game_launcher`（超集，含四引擎） | 保留 `libsiglus.so`（siglus_scene_vm），另加 `libgames.so`（game_launcher） |
| 优点 | 与上游一致；无重复 Siglus 代码；Siglus bridge 无需改动（符号不变） | 完全不动已验证的 Siglus 内核；风险隔离 |
| 缺点 | 任一引擎问题进入同一个 .so；体积增加 | 两个 .so 都含 siglus_scene_vm，重复约 20MB |
| 适用前提 | M0 实测符号导出 + Siglus 回归通过 | 方案 A 验证失败或体积不可接受 |

**结论**：M0 实测（§2.6）已确认方案 A 的符号导出与体积均达标，**定稿走 A**；方案 B 仅作为将来出现回归时的排障手段保留。

### 3.2 数据流

```
App（游戏库卡片点击）
  └─ EngineLauncher.buildFramebufferIntent(engine)
       extras: PATH/GAME_PATH/PROJECT_ROOT/GAME_DIR/ROOT_URI/LAUNCH_TARGET
               LAUNCH_MODE=internal.framebuffer、GAMES_ENGINE、GAMES_NLS
  └─ FramebufferGameActivity（:fbgames）
       ├─ System.loadLibrary("games_bridge")（shim dlopen libsiglus.so）
       ├─ game_add_font_file(字体) → game_fb_open(root, engine, nls, &err)
       ├─ Choreographer 每帧：game_fb_step(dt) → game_fb_frame(h,&w,&h)
       │     → Bitmap → FramebufferView（letterbox 缩放）
       ├─ 触摸/按键/IME → game_fb_pointer_* / fb_key / fb_text
       └─ step 返回 1 或 onDestroy → game_fb_close
```

### 3.3 帧渲染

- `game_fb_frame(h, &w, &h)` 拿原始尺寸（AVG32 640×480、RealLive 800×600、UK2 640×400）
  与紧排 RGBA8 指针，按容器 aspect-fit 居中（与 Siglus 的 viewport 语义一致）。
- 宿主用一块复用 `Bitmap`（`Bitmap.Config.ARGB_8888`）+ DirectByteBuffer，避免每帧分配；
  把 `game_fb_frame` 的像素拷进 Bitmap 后 `Canvas.drawBitmap`。
- wgpu/Metal 不参与，纯 Canvas/GPU 拉伸；1080p 目标设备压力主要在 `game_fb_step`（CPU），
  以 §7 的帧率指标为准；必要时降采样目标分辨率（宿主只缩放，不改引擎内部）。

### 3.4 输入映射

| 输入 | 行为 |
|---|---|
| 单指点按 | 左键 down+up（推进对话） |
| 双指点按 | 右键（系统菜单/取消） |
| 双指上下滑 | 滚轮（回放/历史） |
| Back 键 | 右键；长按或双击退出宿主 |
| 音量键 | 透传系统 |
| 其它按键 | 按 §附录 B 映射；未映射且是字符 → `game_fb_text` |
| IME | 名字输入场景：`game_fb_text`（一期只做「文本提交」，不做光标锚点） |

### 3.5 引擎识别

两段式，避免重写上游识别逻辑，也不让扫描强依赖 native：

1. **Kotlin 快速特征**（`EngineScanner`）——大小写不敏感地收集：
   `Gameexe.ini`、`SEEN.TXT`（读前 4 字节判 `PACL`；或按 10000 项 TOC / `0x1d0`、`0x1cc` 判 RealLive）、
   散装 `SEEN*.TXT` 头（`TPC32`／RealLive 头）、`UK2.CFG`、`.MES` 头 `<< UK2 TEXT Ver1.00 >>`。
2. **native probe 增强**（可选，导入时/启动前）——`game_probe_json` 返回引擎、标题、封面、NLS 选项；
   失败时回退 Kotlin 结果。

判定顺序建议：`SIGLUS → FVP → AVG32 → REALLIVE → UK2 → YURIS → CS2 …`
（`Gameexe.ini` 单独出现不再默认 Siglus 之外，注意与现有 `hasGameexeIni → SIGLUS 85` 的冲突，需在打分链里按 `SEEN.TXT` 证据优先）。

### 3.6 文本编码（NLS）

- 复用 FVP 的编码设置模式：全局默认 + 单游戏覆盖。
- 取值：`auto / sjis / gbk / big5 / utf8 / korean`（RealLive 全量；AVG32/UK2 子集）。
- 新版 `GameInfo.nls_options` 由 native 给出，UI 文案用本地资源渲染。
- 语义：仅影响非 Siglus 引擎的文本/脚本/存档解码；`auto` = 引擎默认（RealLive 依 RLdev 记录，否则 SJIS）。

### 3.7 标题与封面（可选增强）

- `game_probe_json` 已能给出 `title`、`cover`（cover 图 → `.ico` → exe 内嵌图标 → 游戏内图片）。
- 一期建议：**扫描识别必须做，封面/标题增强可延后**（现有库封面逻辑已可用），避免一次改动面过大。

### 3.8 与 Siglus 的隔离

- `libsiglus_bridge.so` + `SiglusActivity` + `:siglus` 进程完全不动；
- 新引擎独立 `libgames_bridge.so` + `FramebufferGameActivity` + `:fbgames` 进程；
- 二者只共享原生 `libsiglus.so`（方案 A）或各自持有 .so（方案 B）。

---

## 4. 改动清单

### 4.1 上游 `siglus_rs`（M0 验证 / 可能的少量改动）

| 文件 | 改动 |
|---|---|
| — | 构建 `-p game_launcher` 的 arm64 `.so`，`llvm-nm` 核对 `siglus_android_*`、`siglus_android_key_event/ime_area/editbox_accepts_direct_text`、`game_*` 均导出 |
| `crates/game_launcher/src/ffi.rs` | 无需改动；`game_fb_frame` 返回 RGBA8 指针（下次调用前有效），供宿主每帧拷贝 |
| （可选）`crates/game_launcher/src/lib.rs` | 若方案 A 符号缺失：补 `pub use siglus_scene_vm::android_host;` / `#[used]`，或加 android-only feature 控制 Siglus 依赖 |

### 4.2 engine 模块

| 文件 | 改动 |
|---|---|
| `engine/src/main/cpp/games_bridge.cpp` | **新增**：自研 shim，`dlopen("libsiglus.so" \| "libgames.so")` + `dlsym` `game_*`；JNI 方法给 `NativeGames`；日志用 `android_logger`/`__android_log_print`；符号缺失仅日志并让 `fbOpen` 返回 0 |
| `engine/src/main/cpp/CMakeLists.txt` | 新增 `add_library(games_bridge SHARED games_bridge.cpp)`，链接 `android/log/dl` |
| `engine/src/main/java/com/core/games/NativeGames.java` | **新增**：JNI 包装（`System.loadLibrary("games_bridge")`），方法对齐 §2.1 |
| `engine/src/main/java/com/core/games/FramebufferGameActivity.java` | **新增**：宿主（帧循环/输入/IME/返回键/错误提示/退出），参照上游 `FramebufferGameActivity` 与既有 `FvpActivity` 风格 |
| `engine/src/main/java/com/core/games/FramebufferView.java` | **新增**：位图绘制 + letterbox |
| `engine/src/main/AndroidManifest.xml` | 新增 `FramebufferGameActivity`（`android:process=":fbgames"`、`exported=false`、`sensorLandscape`、`configChanges` 同 Siglus） |
| `engine/src/main/java/com/core/engine/LaunchContract.kt` | 新增 `LAUNCH_MODE_FRAMEBUFFER`、`GAMES_ENGINE`、`GAMES_NLS` |
| `engine/src/main/res/values{,-ja,-en}/strings.xml` | 宿主文案（错误、退出提示等）三语言 |
| `engine/src/main/jniLibs/arm64-v8a/` | 方案 A：替换 `libsiglus.so`；方案 B：新增 `libgames.so` |

### 4.3 app 模块

| 文件 | 改动 |
|---|---|
| `core/engine/EngineType.kt` | 新增 `AVG32("AVG32")`、`REALLIVE("RealLive")`、`UK2("UK2")` |
| `core/game/scan/EngineScanner.kt` | 新增特征与打分（§3.5）；修正 `Gameexe.ini` 与 Siglus 的重叠判定 |
| `core/game/launch/EngineLauncher.kt` | `supportedEngines` 加入三引擎；`buildIntent` 分支 → `buildFramebufferIntent` |
| `core/engine/plugin/EnginePluginBootstrap.kt` | `when` 三引擎 → `true`（内置无插件） |
| `core/game/save/GameSaveManager.kt` | `resolveSaveLocation` 三分支（§6）；AVG32 单文件模式 |
| `ui/engine/EngineScreen.kt` | GAL 分组、`engineDescription`、内置 `installed=true` |
| `ui/game/GameScreen.kt` | `coverColor()` 占位色 |
| `core/settings/EngineSettingsStore.kt` | `fb_nls` 键与白名单（`auto/sjis/gbk/big5/utf8/korean`） |
| `core/settings/EngineSettingsResolver.kt` / `ResolvedEngineSettings` | 新增 `fbNls` 字段（全局+单游戏合并） |
| `core/settings/PerGameSettingsStore.kt` | `F_FB_NLS`（随 tyrano 分区持久化） |
| `ui/settings/EngineSettingsMenuActivity.kt` | `EngineSettingsKind.FRAMEBUFFER` |
| `ui/settings/SettingsScreen.kt` / `PerGameSettingsScreen.kt` | 编码下拉 + 信息卡（存档位置/返回键说明） |
| `app/src/main/res/values{,-ja,-en}/strings.xml` | 15± 键三语言严格一致 |
| `README.md` / `CONTEXT.md` | 引擎支持表与术语（AVG32/RealLive/UK2、framebuffer 宿主） |
| 测试 | `EngineScannerAvg32Test` / `RealliveTest` / `Uk2Test`；`EngineSettingsResolverTest` 扩展 |

### 4.4 构建与打包

```bash
# 方案 A：单库（上游同构）
cd /Users/weiss/Desktop/sg/siglus_rs
ANDROID_NDK_HOME=... cargo ndk -t arm64-v8a -o /tmp/games-android build --release -p game_launcher
cp /tmp/games-android/arm64-v8a/libgame_launcher.so \
   /Users/weiss/opencode/rma/TyranorNext/engine/src/main/jniLibs/arm64-v8a/libsiglus.so
```

- 与现有 Siglus 内核入库方式一致：**CI 不需要 Rust 工具链**（.so 入库）。
- 记录基线 commit 到 README 与本文档。
- 构建后核对符号（M0）：
  `llvm-nm -D --defined-only libsiglus.so | grep -E 'siglus_android_|game_fb_'`

---

## 5. 关键交互细节

### 5.1 帧循环与生命周期

- `Choreographer.postFrameCallback`；`dt` clamp 250ms（与 Siglus 宿主一致）。
- `onPause` 停循环但**不关闭引擎**（保留进度）；`onDestroy` `game_fb_close`。
- `fb_step` 返回 `1` = 游戏内退出 → `finish()`；`fb_open` 失败 → Toast + `finish()`。

### 5.2 错误与不支持

- `game_probe_json` 的 `supported=false` / `unsupported_reason` 在启动前拦截并展示。
- 未知引擎（`Unknown`）不进入宿主。

### 5.3 IME / 文本输入

- 一期：与 Siglus 同样的 1×1 透明 `View` + `InputConnection`，`commitText → game_fb_text`；
  组合文本（`setComposingText`）不转发（三引擎名字输入均为逐字确认）。
- 仅在游戏请求文本时显示输入法——`game_fb_*` 无「是否需要 IME」查询，接口不足，
  一期可用「按钮手动唤起/收起输入法」（宿主菜单项），列为已知体验缺口。

### 5.4 日志

- shim 内 `__android_log_print` 打印 `fbOpen/fbStep` 错误；Rust `log` 若无 logger 则丢弃（可接受）。
- 可选：宿主在 create 前调用同库的 `siglus_android_init_context` 以初始化 `android_logger`
  （SIGLUS_LOG 控制级别），但需 JavaVM/Context，属 M1 细节。

---

## 6. 存档语义

| 引擎 | 引擎默认写入 | TyranorNext `SaveLocation` | 备注 |
|---|---|---|---|
| AVG32 | `<游戏根>/SAVE.INI`（`AVG32_SAVE_DIR` 可覆盖） | **单文件模式**：`File(root, "SAVE.INI")`，或新增 `SaveLocation.file` 字段 | 全局 flags 也存于此文件 |
| RealLive | `<游戏根>/savedata_rs`（`REALLIVE_SAVE_DIR` 可覆盖） | 目录 `<root>/savedata_rs` | 目录式，直接复用现有列表/导入导出 |
| UK2 | `<游戏根>/FLAG00.DAT`…`FLAG09.DAT`（10 槽，源确认） | `root + ^FLAG\d\d\.DAT$ 过滤` 或目录模式 | PC-98 存档；引擎内部名 `flagNN.dat1` 经虚拟扩展名映射 + 大写后落盘 |
| Siglus | `<游戏根>/savedata` | 目录（现状） | 不变 |

**建议**：为 `SaveLocation` 增加「文件集合（目录 + 文件名白名单）」能力，
AVG32/UK2 走该模式；RealLive 走现有目录模式。删除游戏时按现有 KRKR 非镜像策略处理。

---

## 7. 测试与验收

### 7.1 单元测试

| 用例 | 内容 |
|---|---|
| `EngineScannerAvg32Test` | `Gameexe.ini` + `PACL SEEN.TXT`、散装 `TPC32`、大小写变体、不应误判 RealLive |
| `EngineScannerRealliveTest` | 10000 项 TOC / `0x1d0`、`0x1cc`、`#FOLDNAME.TXT` 指向 `DAT/`、散装 `SEEN####.TXT` |
| `EngineScannerUk2Test` | `UK2.CFG`、`.MES` 头命中、无特征目录 |
| `EngineSettingsResolverTest` | `fb_nls` 全局/单游戏覆盖、非法值回落 auto |
| `GameSaveManagerTest` | AVG32 单文件、RealLive 目录、UK2 过滤模式 |

### 7.2 真机验收（每引擎至少 1 款）

| 引擎 | 建议样机 | 验收点 |
|---|---|---|
| AVG32 | Kanon / AIR | 识别 → 启动 → 立绘/转场/选项 → 文本编码切换 → SAVE.INI 存读 |
| RealLive | CLANNAD / Little Busters! | 识别 → 启动 → 文本/语音/电影 → 系统菜单存读 → 文本编码 |
| UK2 | Sorcer Kingdom | 识别 → 启动 → 地图/战斗 → 音乐 → flagNN 存读 |
| Siglus（回归） | 銀色、遥か / 月の彼方 | 输入法唤起 + GLOBAL.NAMAE 流程不回归 |

### 7.3 性能指标

- 帧率：目标 ≥ 30fps（1080p 拉伸下）；内存峰值、连续运行 30min 无泄漏。
- 启动时间：`fb_open` ≤ 3s（不含资源首解）。

---

## 8. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| 单库符号导出不成立（G1） | Siglus 宿主 dlopen 失败 | **已消除**：M0 实测两类 ABI 全导出（§2.6） |
| 单库体积增长 | APK 增大 | M0 记录实测；必要时拆库/裁剪 |
| CPU 软渲染性能不足 | 卡顿 | 目标分辨率/帧率实测；宿主只做缩放；后续可优化 `fb_frame` 零拷贝 |
| AVG32 单文件存档语义 | 存档管理不可用 | 扩展 `SaveLocation` 文件模式 |
| UK2 存档路径 | 存读失败 | **已消除**：源确认 `<根>/FLAG00.DAT`…`FLAG09.DAT`（§6）；M1 真机复核 |
| 字体缺失 | 文本乱码/方块 | `game_add_font_file` 注册游戏字体/默认字体 |
| SAF/权限 | 无法写存档 | 沿用 `requestAllFilesAccessIfNeeded`（MANAGE_EXTERNAL_STORAGE） |
| IME 无按需唤起接口 | 名字输入体验差 | 一期宿主手动唤起按钮；二期可向上游补 ABI |
| 许可（MPL-2.0） | 合规质疑 | 保留声明 + README/CONTEXT 注明来源；shim 自研不混入上游 C++ |

---

## 9. 里程碑（建议实施顺序）

| 阶段 | 内容 | 依赖 | 预估 |
|---|---|---|---|
| M0 | ~~构建 `game_launcher` arm64；符号核对；方案 A/B 定稿；确认 UK2 存档路径~~ **已完成（§2.6）** | 无 | ✅ |
| M1 | shim + `NativeGames` + `FramebufferGameActivity/View` + Manifest；~~跑通 1 款 RealLive~~ **代码已完成，实机待 M4**（见「实施记录」） | M0 | ✅ |
| M2 | app：EngineType/扫描/启动/存档/插件分支 + 文案 | M1 | ✅ |
| M3 | 文本编码设置链路（全局/单游戏）**已完成**；字体注册与封面/标题增强延后（见偏差 3） | M2 | ✅ |
| M4 | 三引擎逐款真机验收 + Siglus 回归 | M3 | 1–2 天 |
| M5 | 打包、体积记录、README/CONTEXT/文档更新 **已完成**；性能指标随 M4 | M4 | ✅ |

---

## 附录 A：ABI 对照表

| shim JNI（`NativeGames`） | C ABI（`game_launcher`） | 备注 |
|---|---|---|
| `scanJson(path,depth,cache)` | `game_scan_json` | 返回 JSON |
| `probeJson(root,nls,cache)` | `game_probe_json` | 含 title/cover/nls |
| `addFontFile(path)` | `game_add_font_file` | 启动前调用 |
| `fbOpen(root,engine,nls,err)` | `game_fb_open` | 返回 handle；失败回填 err |
| `fbStep(h,dt)` | `game_fb_step` | 0 运行 / 1 结束 |
| `fbFrameSize(h)` | `game_fb_frame` | shim 用空 w/h 调用取尺寸 |
| `fbCopyFrame(h,buf)` | `game_fb_frame` | RGBA8 指针 → memcpy 到 DirectByteBuffer |
| `fbPointerMove/Button` | `game_fb_pointer_move/button` | button 0=左 1=右 |
| `fbWheel(h,up)` | `game_fb_wheel` | |
| `fbKey(h,code,pressed)` | `game_fb_key` | 见附录 B |
| `fbText(h,utf8)` | `game_fb_text` | IME 提交 |
| `fbClose(h)` | `game_fb_close` | |
| `stringFree(ptr)` | `game_string_free` | 释放 JSON/错误串 |

## 附录 B：键码表（`game_fb_key`）

| 语义 | 码 | 语义 | 码 |
|---|---|---|---|
| Enter | 1 | PageUp / PageDown | 8 / 9 |
| Escape | 2 | Home / End | 10 / 11 |
| Space | 3 | Backspace | 12 |
| Up / Down | 4 / 5 | Tab | 13 |
| Left / Right | 6 / 7 | Ctrl / Shift | 14 / 15 |
| F1–F12 | `0x100+1..12` | 字符 | `0x10000 + codepoint` |

## 附录 C：识别特征对照

| 特征 | 判定 |
|---|---|
| `Gameexe.ini` + `SEEN.TXT` 头 `PACL` | AVG32（96） |
| `Gameexe.ini` + 散装 `SEEN###.TXT` 头 `TPC32` | AVG32（92） |
| `Gameexe.ini` + `SEEN.TXT` 10000 项 TOC（`0x1d0`/`0x1cc`） | RealLive（96） |
| `Gameexe.ini` + 散装 `SEEN####.TXT` RealLive 头 | RealLive（92） |
| `UK2.CFG` | UK2（95） |
| `.MES` 头 `<< UK2 TEXT Ver1.00 >>` | UK2（90） |
| 以上皆无 | 回退现有启发式 |

## 附录 D：新增文案键（三语言）

`engine_desc_avg32`、`engine_desc_reallive`、`engine_desc_uk2`、
`engine_settings_framebuffer_title`、`engine_settings_fb_nls_title`、
`engine_settings_fb_nls_auto/sjis/gbk/big5/utf8/korean`、
`engine_fb_exit_hint`、`engine_fb_ime_toggle`、`engine_fb_open_failed`、
`save_location_avg32_file`、`save_location_reallive_dir`、`save_location_uk2_dir`。

## 附录 E：文件改动总览

```
engine/src/main/cpp/games_bridge.cpp                              [新增]
engine/src/main/cpp/CMakeLists.txt                               [修改]
engine/src/main/java/com/core/games/NativeGames.java              [新增]
engine/src/main/java/com/core/games/FramebufferGameActivity.java  [新增]
engine/src/main/java/com/core/games/FramebufferView.java          [新增]
engine/src/main/AndroidManifest.xml                               [修改]
engine/src/main/java/com/core/engine/LaunchContract.kt            [修改]
engine/src/main/jniLibs/arm64-v8a/libsiglus.so | libgames.so      [替换/新增]
app/src/main/java/.../core/engine/EngineType.kt                   [修改]
app/src/main/java/.../core/game/scan/EngineScanner.kt             [修改]
app/src/main/java/.../core/game/launch/EngineLauncher.kt          [修改]
app/src/main/java/.../core/engine/plugin/EnginePluginBootstrap.kt [修改]
app/src/main/java/.../core/game/save/GameSaveManager.kt           [修改]
app/src/main/java/.../core/settings/{EngineSettingsStore,
    EngineSettingsResolver,PerGameSettingsStore}.kt               [修改]
app/src/main/java/.../ui/{engine/EngineScreen,game/GameScreen,
    settings/EngineSettingsMenuActivity,settings/SettingsScreen,
    settings/PerGameSettingsScreen}.kt                            [修改]
app/src/main/res/values{,-ja,-en}/strings.xml                     [修改]
app/src/test/java/.../EngineScanner{Avg32,Reallive,Uk2}Test.kt    [新增]
README.md / CONTEXT.md                                            [修改]
```

---

## 实施记录（M1–M3 + M5，M4 待实机）

### M1 engine 模块（native + Java 宿主）

| 文件 | 改动 |
|---|---|
| `engine/src/main/cpp/games_bridge.cpp` | **新增**：自研 shim，`dlopen("libsiglus.so")` + `dlsym` `game_*`，另解析 `siglus_android_init_context` 用于 ndk-context/日志初始化；JNI 方法对齐 `NativeGames` |
| `engine/src/main/cpp/CMakeLists.txt` | 新增 `games_bridge` 共享库目标 |
| `engine/src/main/java/com/core/games/NativeGames.java` | **新增**：JNI 包装 + 键码/鼠标键常量 + `initAndroidContext` |
| `engine/src/main/java/com/core/games/FramebufferView.java` | **新增**：位图 letterbox 绘制与坐标换算 |
| `engine/src/main/java/com/core/games/FramebufferTextInputView.java` | **新增**：1×1 隐藏输入视图，IME 提交 → `game_fb_text` |
| `engine/src/main/java/com/core/games/FramebufferGameActivity.java` | **新增**：`Choreographer` 帧循环、手势/按键/IME、控制条（输入法/跳过/菜单）、返回键（单击=右键、双击=退出确认）、加载层 |
| `engine/src/main/AndroidManifest.xml` | 新增 `FramebufferGameActivity`（`:fbgames`、`sensorLandscape`、`Theme.FramebufferGames`） |
| `engine/src/main/res/values/styles.xml` | 新增 `Theme.FramebufferGames` |
| `engine/src/main/res/values{,-ja,-en}/strings.xml` | 新增 8 个 fb 文案（缺目录/初始化失败/返回退出/输入法/跳过/菜单） |
| `engine/src/main/java/com/core/engine/LaunchContract.kt` | 新增 `LAUNCH_MODE_FRAMEBUFFER`、`GAMES_ENGINE`、`GAMES_NLS`、`GAMES_TITLE` |
| `engine/src/main/jniLibs/arm64-v8a/libsiglus.so` | 替换为 `game_launcher` 产物（单库含四引擎，24,602,928 B） |

### M2 app 模块

| 文件 | 改动 |
|---|---|
| `core/engine/EngineType.kt` | 新增 `REALLIVE` / `AVG32` / `UK2` |
| `core/game/scan/EngineScanner.kt` | 新增特征与内容嗅探：`UK2.CFG`、MES 头、`SEEN.TXT`（`PACL`→AVG32 / 10000 项 TOC→RealLive）、散装 `SEEN###/####.TXT`；搜索目录新增 `dat`；SAF/File 会话新增 `readHead` |
| `core/game/launch/EngineLauncher.kt` | `supportedEngines` + `buildFramebufferIntent` + `framebufferEngineId` |
| `core/engine/plugin/EnginePluginBootstrap.kt` | 三引擎 → 内置（`return null`） |
| `core/game/save/GameSaveManager.kt` | RealLive → `<根>/savedata_rs`；AVG32/UK2 → 游戏根 + 白名单（`SAVE.INI` / `FLAGnn.DAT`），导入时其余游戏文件从备份移回（同 ARTEMIS 语义） |
| `ui/engine/EngineScreen.kt` | GAL 分组、描述、`game_launcher-xmoezzz` 内置条目 |
| `ui/game/GameScreen.kt` | 三引擎占位色 |
| `app/src/main/res/values{,-ja,-en}/strings.xml` | 3 条引擎描述 + 7 条 fb 编码设置 + 说明，三语言严格一致 |
| `app/src/test/.../EngineScannerFramebufferTest.kt` | **新增** 7 个单测 |

### M3 文本编码设置链路

- `EngineSettingsStore`：`fb_nls`（auto/sjis/gbk/big5/utf8/korean）+ getter/setter/normalize
- `EngineSettingsResolver` / `ResolvedEngineSettings.fbNls`（全局 + 单游戏合并）
- `PerGameSettingsStore.F_FB_NLS` + `GameOverridePartitions.KEY_FB_NLS`（随 tyrano 分区持久化）
- `EngineSettingsKind.FRAMEBUFFER` + 设置页/单游戏设置卡片（`fbNlsOptions`）

### 与方案的偏差（已实现口径）

1. **存档管理**：AVG32/UK2 采用「游戏根 + 文件名白名单 + 导入后从备份移回其余文件」，复用 ARTEMIS 的既有语义，未新增 `SaveLocation.file` 模式；AVG32 `#SAVEFILE` 自定义名暂不识别。
2. **IME**：引擎无「是否需要输入法」ABI，宿主用控制条「输入法」按钮手动唤起/收起（方案一期口径）。
3. **字体**：未调用 `game_add_font_file`；依赖 `game_fs` 内置的 Android 系统 CJK 字体路径回退。
4. **引擎 id**：`EngineType` → `reallive/avg32/uk2` 下发；内容嗅探失败时为 null，由引擎按文件内容自动探测。
5. **识别**：`SEEN.TXT` 用文件头内容区分（`PACL` / TOC），复刻上游 `engine-detect`，避免 AVG32 误判为 RealLive。

### 验证结果

- `./gradlew :app:assembleDebug --no-daemon` 通过（含 native shim 编译、`.so` 打包）。
- `./gradlew :app:testDebugUnitTest --no-daemon` 通过；`EngineScannerFramebufferTest` 7/7。
- `python3 tools/check-hardcoded-ui-strings.py` 通过；`git diff --check` 通过。
- **待办（M4）**：实机回归 Siglus（銀色、遥か IME / 月の彼方 NAMAE）+ 每引擎至少一款真机验收（AVG32/RealLive/UK2）；CPU 软渲染帧率/内存指标。

### M4 实机验证记录（2026-09-25，HONOR MAA-AN10 / Android 16）

| 项目 | 结果 |
|---|---|
| 构建/安装 | `assembleDebug` 通过；`adb install -r -t -d` 成功（本地产物 versionCode 低于设备已装版本，测试用 `-d` 允许降级） |
| 引擎页展示 | GAL 分组出现 RealLive / AVG32 / UK2，描述与「已集成」条目正确 |
| **Siglus 回归** | 銀色、遥か：警告页 → 标题 → 初めから → 名字变更页，点击 `姓` 后 `mInputShown=true`（软键盘正常），**换用 game_launcher 单库后 Siglus 链路零回归** |
| 扫描识别（合成样本） | `Gameexe.ini` + 8B `PACL` `SEEN.TXT` → AVG32 徽标；`UK2.CFG` → UK2 徽标（UI + DB 双向确认） |
| 宿主链路（合成样本） | 启动后进入 `FramebufferGameActivity`（`:fbgames` 进程）；UK2 打开失败 → 本地化「启动失败 / uk2: UK2.CFG is missing MIDI_EXT」对话框；AVG32 打开成功、引擎运行到缺失场景后 `fbStep` 返回结束并优雅退出 |
| 实机发现并修复 | shim 的 `nativeInitAndroidContext` JNI 符号漏写 `native` 前缀导致 `UnsatisfiedLinkError` 崩溃；已修复并重新验证（全部 16 个 JNI 符号与 Java native 一一对应） |
| 未覆盖 | 无真机 RealLive/AVG32/UK2 游戏（设备上只有 Siglus/ONS/KRKR 等），实际画面/音频/存档读写与性能指标（G6）待有真实游戏后回归 |

> 合成样本目录已删除；其库记录因目录缺失不参与 UI 展示，后续全量重扫会清理。
