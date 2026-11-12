# Ethornell / BGI 宿主重构计划方案

> 文档性质：**由计划转为实施状态记录（2026-11-02 回填）**。P0–P4 的原始计划文字保留在 §6；实际“已实施/已验证/剩余”以 §6.6 状态表和 §0 当前状态为准。本文不把未跑项写成通过。
>
> 调查日期：2026-11-02（计划编写时基线）；实施回填日期：2026-11-02。
> TyranorNext 计划基线：`3ad9d59529ca011685cefbfe2c6b39904fe95657`（`3ad9d595`）；实施回填工作区：`a81543a75e44d2538aeb491ebf1a1956c1a37913` + 未提交的宿主/存档实现变更。
> 上游 ethornell-rs 基线：`/Volumes/HUAWEI-M2LITE/app/ethornell-rs`，HEAD `0cfff1d274ecc4554d0af8af1c7b1c7908595e96`（计划时 clean；当前 `043fef05` 库为同一 revision + 1 个本地补丁，构建 checkout 故意 dirty）。
> 证据记号：`T:` 表示 TyranorNext 仓库相对路径；`E:` 表示上游仓库相对路径；`file:line` 为本次阅读到的行号。
> 历史材料：旧移植计划与测试验收记录中的“已通过”只属于其记录时的候选/二进制；**不是当前 HEAD 的复跑结果**。本文不把旧候选证据改写成当前结论。

## 阅读导航

- 结论与边界：§0–§1。
- 上游 ABI/事件/存储/构建事实：§2。
- 当前宿主基线：§3。
- 差距矩阵与优先级：§4。
- 目标架构、数据流、关键决策：§5。
- P0–P4 阶段计划：§6。
- 文件级影响、验收矩阵、风险回滚、开放问题：§7–§10。
- 文件 SHA、行号索引与未验证项：§11。

## 0. 结论摘要

1. **计划编写时 TyranorNext 是旧 14 符号 ABI 宿主；2026-11-02 已升级并验证。** 当前 C ABI 为 15 个导出（含 `request_payload`）、Event kind 1–5、kind 5 JSON 回答，存档管理已按受限范围开放（`T:engine/src/main/cpp/ethornell_bridge.cpp`、`T:engine/src/main/cpp/ethornell_android.h`、`T:app/src/main/java/com/tyranor/next/core/game/save/EthornellSaveCapability.kt`）。旧 14 符号描述只保留为计划基线。
2. **上游 `0cfff1d` 的 ABI 版本仍是 1，但接口面增加第 15 个导出 `ethornell_android_request_payload`，并新增 Event kind 5（输入对话框）。** `EthornellResponseV1.text` 从“必须为空”改为“kind 5 的 JSON 回答”（`E:platform/android/include/ethornell_android.h:7,29,35-39,68-70`）。
3. **这不是“只补三个 ABI 点”。** `7ee2067..0cfff1d` 共 152 个提交，覆盖 app 106、VM 82、script 42、render 9、audio 4、archive 3 等路径；旧设备证据不能直接外推到新库的 GPU/音频/VM/存储行为（§2.7）。
4. **目标架构保持不变：** 保留 TyranorNext 自有 C ABI/JNI 桥、私有 `:ethornell` Activity、dispatch/存档门禁；不整包搬入上游 Android app，也不把上游 JNI 类当成本项目传输层。
5. **kind 5 的关键约束是线程：** 完整 payload 必须由 engine owner 线程（当前 `Ethornell-engine` worker）通过 `request_payload` 取回，再交给 UI；UI 线程不得直接调用 native handle，也不得把 dialog 结果直接写回 native（§5）。
6. **还有三个与 kind 5 同级的硬约束（计划时风险，P1 已处理）：** Sys80:3C Retry 会复用同一 `request_id`，不能按历史 id 永久去重；上游 pending 上限是 16；kind 5 的 malformed JSON answer 会 poison session，宿主必须在发送前本地校验并以合法 `{"accepted":false}` 取消。当前实现用 pending + 单个 retryCandidate 区分 Retry，并在发送前校验 JSON（§2.2–§2.3、§4 G21–G23、§6 P1）。
7. **存档管理必须继续独立门禁。** 上游 `STORAGE.md` 的 user_root、atomic write、flock、64MiB、engine-thread barrier 契约不因 ABI 升级自动满足；`0cfff1d` 没有新增 save/import/export API；P3 前保持 `available=false`，本次已按 P3 设备验收后置为 `true`，但范围仅限 App 私有 user-root 备份/恢复/删除（§4、§6 P3）。
8. **计划编写时没有当前 HEAD 的设备测试结论；2026-11-02 已补 `043fef05` 的限定范围证据（见 §6.6/§8）。** 任何新结论都必须绑定四元组：App commit、`libethornell.so` SHA-256、vendored header SHA-256、上游 source revision；缺一项即视为不可采纳证据。

## 1. 目标、范围与非目标

### 1.1 目标

- 把 TyranorNext 的 Ethornell 宿主从旧 14 符号 ABI 升到上游 `0cfff1d` 的 v1 接口面：15 个导出、Event kind 5、`request_payload`、JSON response。
- 在**不改变** App UI 分层、不改变私有 `:ethornell` 进程模型、不改变 dispatch/存档门禁的前提下，完成宿主内部重构。
- 为 kind 5 的四类输入对话框（`input` / `segmented` / `select` / `date`）提供可用的 Android 宿主 UI 与 IME 行为。
- 明确生命周期、Surface、输入取消、stale/unknown 响应、Sys80:3C Retry 同 id、pending 16、malformed JSON、存储 barrier 与存档开放边界。
- 给出可执行的分阶段验收门禁，避免“ABI 已换、UI 未适配、存档误开放”的半升级状态。

### 1.2 范围

**包含：**

- `engine` 模块的 Ethornell C ABI shim、JNI 传输、Session、私有 Activity、输入/对话框适配。
- `app` 模块中与 Ethornell 启动、存档能力门禁、错误映射、测试相关的**最小**改动。
- `tools/ethornell` 的 ABI/符号/provenance 检查、`app/src/androidTest` 与 `app/src/releaseProbe` 的 15 符号探针。
- `docs` 中的增量计划、旧计划阅读导航、集成契约说明。
- 构建产物身份：vendored header、`libethornell.so`、provenance、上游 source revision 必须成套更新。

**不包含：**

- 不整包复制上游 `platform/android` app（Launcher/GameLibrary/GameActivity）。
- 不把上游 `crates/ethornell-mobile` 的 JNI 导出（`Java_com_xmoezzz_ethornell_NativeEngine_*`）直接作为 TyranorNext 的传输层。
- 不修改 App 的 Compose 页面结构、导航、视觉规范；不把 engine 宿主 UI 混入 App UI 层。
- 不开放存档管理、不宣称 PC 存档互通、不做整根资源镜像、不自动导入 `resource/UserData/*`。
- 不宣称完整 IME、BMV/WMV/MP4、完整 SWF、电影音轨等上游未声明能力。
- 不把 ABI 升到 2；不新增与上游不兼容的 C 导出（除经评审的宿主侧扩展，且默认不做）。
- 不在本计划内运行 Gradle、设备仪器、真实游戏或发布流程。

### 1.3 与旧移植计划/历史验收记录的关系

- `docs/Ethornell-BGI-Android化移植计划方案.md` 是**原始移植设计与实施记录**，其 §0.3、§12.5 和 `docs/Ethornell测试验收记录.md` 记录了旧候选的设备/样本证据。本文只增加“阅读导航”指向，不重写其历史、不删除旧结论、不把旧通过项搬到当前 HEAD。
- 旧文档的 TyranorNext 调研基线是 `7a3f007...`，引擎调研/实施基线是 `5556c36...`（dirty 工作区）；**计划编写时打包库的 provenance 是 `7ee2067...`**，两者不能混为同一 revision。当前工作区的库/header 为 `0cfff1d...`（库另带 1 个本地补丁，§11.1），旧设备证据仍只对应旧候选。
- 旧记录中的“14 ABI / symbols=14”是计划编写时的代码事实；当前 probe 与发布门禁已改为 15 符号（`T:app/src/androidTest/cpp/ethornell_test_probe.cpp`、`T:app/src/releaseProbe/README.md`）。14→15 已完成，不再作为待迁移项。
- 旧记录中的候选哈希、APK 字节、设备日志只可用于其对应候选。新库、新 header、新 App commit 必须重新建立证据链。

### 1.4 计划编写时状态与“历史已通过”的差异

本节保留计划起点的对照；当前实现/验收已由 §6.6 回填。

| 事项 | 计划编写时事实 | 当前（2026-11-02） |
| --- | --- | --- |
| 是否支持 15 符号/kind5/payload | 否；14 符号、kind 1–4、int response | 已实施并验证；见 §6.6 |
| 是否已通过设备测试 | 本文原不主张 | 指定范围内通过：device 6/6、save 9/9、release probe SJIS/GBK 各 3/3、pause 3/3（多 stage/step 夹具） |
| 旧记录中的“已通过”是否可复用 | 不可直接复用 | 仍不可复用；旧 hash/库/行为不同 |

原始“退出标准”已由 §6.6 按实际证据回填；未列证据仍按待执行/未验证处理。任何验收记录必须写明四元组：App commit、library SHA-256、header SHA-256、source revision；并注明测试范围与未覆盖项。

## 2. 上游宿主/接口变更事实

### 2.1 ABI 版本与导出面

上游 `0cfff1d` 的 header 仍写 `ETHORNELL_ANDROID_ABI_VERSION 1u`（`E:platform/android/include/ethornell_android.h:7`）。变化是**加法**：

| 项目 | 旧 `7ee2067` | 上游 `0cfff1d` | 证据 |
| --- | --- | --- | --- |
| ABI 版本 | 1 | 1（未升） | `E:platform/android/include/ethornell_android.h:7` |
| 导出数 | 14 | 15 | 旧 header 与当前 `T:engine/src/main/cpp/ethornell_bridge.cpp:25-26`；新 header `:68-70` |
| 新导出 | 无 | `ethornell_android_request_payload` | `E:platform/android/include/ethornell_android.h:68-70` |
| `EthornellEventV1.kind` | 1–4 | 1–5（5=dialog request） | `E:platform/android/include/ethornell_android.h:29` |
| `EthornellResponseV1.text` | 保留，必须为空 | kind 5 的 JSON 回答；其他情况为空 | `E:platform/android/include/ethornell_android.h:35-39` |
| 结构体尺寸 | Config 88 / Input 32 / Event 2072 / Response 24 | **不变** | 当前探针断言 `T:app/src/androidTest/cpp/ethornell_test_probe.cpp:9-15`；上游 header 字段未变 |

**结论：** 不需要改结构体布局，也不需要升 ABI 版本；但正式发布必须把 15 个符号 + header + provenance 作为**成套要求**。运行时可以把 `request_payload` 作为可选 `dlsym` 做渐进回滚/旧库兼容，但必须分别暴露 `payloadSymbolAvailable=false` 与 `inputDialogsSupported=false`，且绝不能把缺失该符号时的 kind 5 当成成功；P0 即使符号可用也强制 `inputDialogsSupported=false`。CI/发布门禁仍要求 15 符号（§5.3 D2、§5.4、§9 R01）。ABI 版本不升意味着旧宿主不会因版本检查自动拒绝新库，必须由宿主显式 `dlsym` 校验并做能力检查。

### 2.2 Event kind 5、request_payload 与 JSON response

上游 ABI.md 把 kind 5 定义为“输入对话框请求”，`text` 给出类型名，完整请求通过 `request_payload` 获取（`E:platform/android/include/ABI.md:37-54`）：

| 类型 | 请求字段 | 回答 |
| --- | --- | --- |
| `input` | `title`、`fields[]`（每项 `label`、`initial`、`max_bytes`、`numeric`；1 或 2 个字段） | `texts[]`，每个字段一项 |
| `segmented` | `title`、`prompt`、`segments`、`max_bytes`、`numeric` | `texts[]`，每个 segment 一项 |
| `select` | `title`、`prompt`、`options[]` | `index`（从 0 开始） |
| `date` | `fields[4]`、`month`、`day`（均从 0 开始） | `texts[4]`、`month`、`day` |

回答规则：

- 每个成功回答带 `"accepted": true`；`{"accepted": false}` 表示取消（`E:platform/android/include/ABI.md:50-51`）。
- 引擎自己执行桌面端过滤：`numeric` 保留数字与前导负号，segmented 保留 ASCII 字母/数字，`max_bytes` 按 Shift-JIS 字节计数；宿主可以只显示普通文本字段（`E:platform/android/include/ABI.md:51-54`）。
- **畸形/ schema 错误的回答会 poison session，不是 cancel。** `response_from_json` 对非 JSON、select 缺 integer `index`、date 缺 `month`/`day` 等返回 `Err`（`E:crates/ethornell-app/src/dialog_protocol.rs:92-167`），`complete_dialog` 把它转成运行时错误（`E:crates/ethornell-app/src/host_runtime.rs:834-843`），`with_session` 因错误文本既不是 `invalid/stale surface`、`unknown/stale request` 也不是 `non-monotonic host clock` 而把 `session.failed=true`（`E:crates/ethornell-mobile/src/lib.rs:113-121`）。注意：合法的 `{"accepted":false}`（或上游把缺失 `accepted` 视为 false）是 cancel，不是 malformed；宿主必须**显式发送** cancel，不能依赖缺失字段。宿主必须在**发送前**本地校验/构造合法 JSON；构造失败时发送 `{"accepted":false}`，绝不能发送半成品、空字符串或依赖引擎兜底（kind 5 发空字符串会走 int 分支，`complete_request` 因存在 dialog 返回 “dialog request needs a JSON response”，同样会被判为错误并 poison；见 `E:crates/ethornell-app/src/host_runtime.rs:855-859`）。
- 上游示例 App 在请求 payload 解析失败时直接 `completeDialog(requestId, null)`（即 accepted:false）（`E:platform/android/app/src/main/java/com/xmoezzz/ethornell/GameActivity.java:198-205`）；这只能作为“请求不可读时取消”的 UI 参考，**不能**作为“畸形 answer 可以当 cancel”的依据。
- `request_payload` 返回**包含结尾 NUL 的所需字节数（正数）**；`NULL/0` 是 size query；容量足够时才拷贝（`E:platform/android/include/ethornell_android.h:68-70`，`E:crates/ethornell-mobile/src/lib.rs:259-268,517-537`）。未知/过期 request 返回 `-4` 且**不 poison**（`E:crates/ethornell-mobile/src/lib.rs:92-135,517-537`）。因此 bridge 不能用普通 `status()==0`/`<0` 逻辑处理它：正数是长度，`-4` 是“取不到/已过期”，不是 transport 失败。
- **宿主侧 payload 边界（本文提议，不是上游 ABI）：** 拷贝后只去掉**一个**结尾 NUL（`copied-1`），拒绝内嵌 NUL；严格 UTF-8 解码；单 payload 上限 **64KiB**；`segmented` 段数上限建议 **64**、`select` options 上限建议 **256**（待真实样本测量后定）。超限时 UI 显示“payload 不可用/拒绝”并可取消，**不得截断 JSON 后继续**。这些上限必须进 bridge/协议测试，不能只写在文档。
- `complete_request` 的 `text` 为空时走整数结果；非空时走 dialog JSON；kind 5 的整数结果被忽略（`E:crates/ethornell-mobile/src/lib.rs:486-512`、`E:platform/android/include/ABI.md:41-42`）。

**必须注意：** `complete_request` 的“空 text 走 int、非空 text 走 dialog”是全局路由，不只是 kind 5。对 kind 2 确认请求误传非空 text，会被上游当成 dialog answer 并失败；宿主必须按请求类型严格分流（§5.5）。

### 2.3 线程、Surface、输入与生命周期契约

上游 ABI.md 的宿主契约（`E:platform/android/include/ABI.md:10-35`）：

- `create`、surface、input、step、response、destroy 必须串行在创建 handle 的 engine 线程上；错误线程按无效 handle 拒绝（`:10-14`）。
- JNI 层拥有 Application GlobalRef 与 `ANativeWindow` 引用；`surfaceDestroyed` 返回前必须等待 detach ACK；detach/destroy ACK 后才能释放 window（`:10-16`）。
- `set_surface` 失败不得保留新 window 的借用；成功替换时旧借用先结束；尺寸为 0 必须 detach 而不是 attach（`:15-16`）。
- 无 Surface、pause mask 非零或存在 pending confirmation 时，叙事不前进；`step` 用 `CLOCK_MONOTONIC` 纳秒，约 16ms gate，不重放错过的 VM 帧（`:18-20`）。**`step` 不保证每步一帧：** `take_present_due()` 受 Graph90:01/02 控制，可能返回 false（`E:crates/ethornell-app/src/host_runtime.rs:688-790`、`E:crates/ethornell-app/src/lib.rs:3673-3693`）；像素/画面验收必须按实际 present/readback，不得按 step 次数或“跑了 N 步”推断。
- pause/detach 会清除按下输入并重置 clock；move 可合并，down/up 必须 FIFO；输入使用与渲染相同的 aspect-fit viewport；主触摸映射左键；宿主必须提供可访问的右键/backlog 控件（`:20-24`）。
- `poll_event` 空返回 1；confirmation 请求挂起真实 VM continuation；`complete_request` 只能结算一次；不得投机返回默认答案；destroy 取消 pending（`:26-31`）。
- **`last_error` 是 owner 线程 TLS：** 错误字符串存在 `thread_local! ERROR`（`E:crates/ethornell-mobile/src/lib.rs:76-83`），`last_error` 在同一线程读取（`:288-289`）；ABI 文档也明确它是 thread-local（`E:platform/android/include/ethornell_android.h:47-50`）。错误后必须同线程读，不能跨线程猜。
- **`request_close` 不是强制退出：** 它走 `handle_host_window_close`，只有游戏 close mode 允许时才 `close()`；被阻塞的 pending dialog 只能由显式 `destroy` 取消，不能由 close 伪造回答（`E:crates/ethornell-app/src/host_runtime.rs:806-817`）。
- **`destroy` 不产生 kind 3：** `destroy` 只是移除 session/drop 运行时（`E:crates/ethornell-mobile/src/lib.rs:538-548`）；kind 3 由 `HostRuntime::close` 产生（`E:crates/ethornell-app/src/host_runtime.rs:793-804`）。cleanup 不能等 kind 3，destroy ACK 就是完成条件。
- **stale/unknown 不 poison：** 被拒绝的旧 Surface generation、未知/过期 request ID、回退的 clock 时间戳不会 poison 健康会话；真正的 runtime/GPU/audio/policy 失败才会（`:31-35`，`E:crates/ethornell-mobile/src/lib.rs:92-135`）。
- 黑边 pointer release 必须取消手势（包括 queued edges），不能把 UP 映射到旧内容坐标（`:34-35`；上游实现见 `E:crates/ethornell-app/src/host_runtime.rs:558-600`）。
- 输入对话框与 confirmation 一样挂起 VM，以 kind 5 到达；完整请求用 `request_payload`，回答用 `complete_request` 的 text（`:37-54`）。
- **Sys80:3C Retry 复用同一 `request_id`。** `request_retry` 的注释明确“Retry rechecks the file and, if it is still missing, asks again under the same id”（`E:crates/ethornell-app/src/host_runtime.rs:156-163`）；`complete_request` 在文件仍缺失时重新 push 同 id 的 kind 2 事件并直接返回，**不从 `pending` 移除**（`E:crates/ethornell-app/src/host_runtime.rs:855-881`；测试断言 `again.request_id == event.request_id` 见 `:1226-1236`）。宿主不能按历史 id 永久去重。
- **pending host request 上限是 16。** 上游在第 17 个请求时返回 `host request queue limit` 并 poison（`E:crates/ethornell-app/src/lib.rs:21685-21686,21706-21707,21743-21744`）。Retry 的 id 在上游仍是 `host.pending` 的未结算请求（`E:crates/ethornell-app/src/host_runtime.rs:156-163,869-877`），所以宿主预算必须按 `pending.size() + (retryCandidate != 0 ? 1 : 0) <= 16` 计算；不能一边允许 64、一边假设引擎还能继续接收。
- **上游示例 JNI 的 detach 不是契约。** 示例 `nativeDetachSurface` 无论 `detach_surface` 返回什么都会 `release_window`（`E:crates/ethornell-mobile/src/android_jni.rs:148-158`）；示例 Activity 只等 5 秒就返回（`E:platform/android/app/src/main/java/com/xmoezzz/ethornell/GameActivity.java:359-374`）。TyranorNext 必须只在 detach ACK 后释放 window；bridge 的 detach ACK/释放路径见 `T:engine/src/main/cpp/ethornell_bridge.cpp:207-215`，超时 watchdog 在 `T:engine/src/main/java/com/core/ethornell/EthornellSession.java:153-187`；不得照抄 5 秒后继续使用旧 Surface 的写法。

### 2.4 存储契约（STORAGE.md）

**事实校正：** `git diff 7ee2067..HEAD -- platform/android/include/STORAGE.md` 为空；`STORAGE.md` 在旧库 revision 已存在，SHA-256 为 `3101e53919d330bb9db1b90dbd452a48419f4135948235a2db80f8353bd3033e`。因此它不是 `0cfff1d` 新增的 ABI 文本；但当前宿主仍未按它开放存档管理。

**0cfff1d 没有新增 save/import/export API。** header 的 15 个导出只增加 `request_payload`，没有任何存档/导入/导出函数（`E:platform/android/include/ethornell_android.h:46-71`）；`ABI.md` 仍把 user_root 作为唯一显式可写根，并明确“在游戏特定 user-file 行为被验证前不要暴露整游戏 save export”（`E:platform/android/include/ABI.md:56-64`）。因此 ABI 升级不能作为开放存档管理的理由。下面的事实来自 `STORAGE.md`：

- 可写集合是显式 `user_root` 下的全部合法 guest 相对文件，排除任何 `.ethornell-*` 组件；不是整个资源根，也不自动迁移 `resource/UserData/*`（`E:platform/android/include/STORAGE.md:51-62`）。
- 每个用户写都在 engine 线程同步完成：创建父目录、临时文件、写入、文件 `sync_all`、目录内原子 rename、目录 `sync_all`；没有后台 save writer（`:64-67`）。
- 最后一条写之后排队的命令，或同一 engine 线程上完成的 destroy，构成 IO completion barrier；**pause 本身不证明已排队的写已完成**；mirror/export 前必须等待显式有序 barrier（`:67-70`）。
- 原子写上限 64MiB；`.ethornell-*` 路径组件保留给 writer flock，guest 不能读/改，枚举时隐藏（`:34-36`）。
- rename 失败前旧目标保留；rename 成功后目录 sync 失败表示“已提交但持久性不确定”，不能说“旧存档一定保留”（`:73-75`）。
- 资源根只读；user_root 是独立的显式可写根；不暴露整游戏 save export，直到游戏特定 user-file 行为被验证（`E:platform/android/include/ABI.md:56-64`）。

### 2.5 上游 Android app 与 JNI 分层（仅参照）

上游仓库自带 Android app 与 `ethornell-mobile` JNI，但它们不是 TyranorNext 的宿主：

- **上游 JNI 是示例 App 的私有适配层，不是稳定 ABI。** 它的类名/包名固定为 `com.xmoezzz.ethornell.NativeEngine`，导出名是 `Java_com_xmoezzz_ethornell_NativeEngine_*`；只有 C header 是稳定契约（`E:platform/android/app/src/main/java/com/xmoezzz/ethornell/NativeEngine.java:11-14`、`E:crates/ethornell-mobile/src/android_jni.rs:58,79,116,149,161,171,194,204,215,247,268,301,324,335`、`E:platform/android/README.md:49-50`）。
- 上游 `platform/android/app/src/main/java/com/xmoezzz/ethornell/NativeEngine.java` 加载 `libethornell_mobile.so`，JNI 类名/包名固定为 `com.xmoezzz.ethornell`（`E:platform/android/app/src/main/java/com/xmoezzz/ethornell/NativeEngine.java:11-14`）。
- 上游 `GameActivity` 自己创建 `HandlerThread("ethornell-engine")`，在该线程上 `nativeStep`/`drainEvents`，kind 5 时在同一线程 `nativeRequestPayload`，再把 UI 工作 post 到主线程（`E:platform/android/app/src/main/java/com/xmoezzz/ethornell/GameActivity.java:88-120,143-146`）。
- 上游 app 的 Manifest 未声明 `android:process`，`GameActivity` 运行在应用默认进程；它是独立 launcher/player，不是 TyranorNext 的 `:ethornell` 私有进程 + dispatch token 模型（`E:platform/android/app/src/main/AndroidManifest.xml:21-26`）。
- 上游 app 的对话框是普通 `AlertDialog + EditText`，没有针对 TyranorNext 的 IME/adjustNothing/insets 处理（`E:platform/android/app/src/main/java/com/xmoezzz/ethornell/GameActivity.java:198-303`）。

**决定：** 上游 app 只作为“协议形状/交互参考”，不整包搬入；上游 JNI 只作为“request_payload 两段式读取、dialog JSON 路由”的实现参考，不作为本项目传输层（§5.3 D1）。

### 2.6 构建、产物与 provenance

- 上游构建脚本输出 `libethornell_mobile.so`，不是 `libethornell.so`（`E:platform/android/build.sh:2,58-61`；`E:platform/android/README.md:4,36`）。**它不生成 provenance JSON**：脚本只做 cargo-ndk/verify_elf/APK（`E:platform/android/build.sh:1-62`）；当前 `libethornell.provenance.json` 已由项目侧 builder 按 `0cfff1d` + 1 个本地补丁重建，记录 `dirty=true`；`--local-static` 通过，严格来源审计不通过（§6 P0/§6.6）。
- 链接参数包含 16KB page、full RELRO、`--no-undefined`（`E:platform/android/build.sh:44-48`）。
- 当前 TyranorNext 的正式路径是 `engine/src/main/jniLibs/arm64-v8a/libethornell.so`，provenance 中 `crate_artifact` 为 `libethornell_mobile.so`、`packaged_name` 为 `libethornell.so`，并注明 ELF 无 `DT_SONAME`（`T:engine/src/main/jniLibs/arm64-v8a/libethornell.provenance.json`）。
- 当前库的 provenance `source_revision` 是 `0cfff1d274ecc4554d0af8af1c7b1c7908595e96`，库 SHA-256 `043fef05d0485f027cd5b344a93ddfecd52f97e8e7f8a12fe2986dbcab376d3e`，大小 `27,259,224` 字节（§11.1）；provenance `dirty=true`、`local_patches=1`（`0001-user-data-double-bgi-resource.patch`，SHA-256 `77b99d93...`）；旧 `0401e38d`/`27,224,984`、`541bf6d6`/`27,256,440` 与 `7ee2067`/`09fef3bc...`/`26,260,872` 只作历史基线。
- 当前 `build_info` 通过 `ETHORNELL_SOURCE_REV` 嵌入 source revision（`E:crates/ethornell-mobile/build.rs:6-14`、`E:crates/ethornell-mobile/src/lib.rs:274-286`）；当前库基于 `0cfff1d` + 本地补丁构建，设备 probe 已验证 `build_info`/ABI；仍不能只信 provenance 文本，发布须按四元组核对。
- **`build_info` 与初始事件仍声明不支持的能力：** `build_info` 文本明确 `MPEG video-only; SWF partial; no WMV/BMV/MP4`（`E:crates/ethornell-mobile/src/lib.rs:274-286`）；初始化事件文本明确 `full SWF/WMV/BMV/MP4/IME/CDDA not supported`（`E:crates/ethornell-app/src/host_runtime.rs:268-278`）。`ABI.md` 的 diff 把 “Full IME” 从“未声明能力”句子中移除（`E:platform/android/include/ABI.md:65-68`），但同一句仍列 `movie audio`、BMV/WMV/MP4、完整 SWF 未声明，且运行时诊断仍保留 full IME 不支持；因此只能声明 kind 5 的对话框文本输入，不能声明通用/游戏内完整 IME 或这些媒体能力。

### 2.7 152 个提交的行为面

`git log --oneline 7ee2067..HEAD | wc -l` 得到 **152**。按路径统计（同一提交可能跨路径，不能相加为 152）：

| 路径 | 提交数 | 说明 |
| --- | ---: | --- |
| `crates/ethornell-app` | 106 | 图形、输入、音频、host policy、文字/窗口、VM 边界等 |
| `crates/ethornell-vm` | 82 | VM、scheduler、host request、user dialog、native chain |
| `crates/ethornell-script` | 42 | 脚本/opcode/ABI |
| `crates/ethornell-render` | 9 | 渲染/字体 |
| `crates/ethornell-audio` | 4 | 音频 |
| `crates/ethornell-archive` | 3 | 归档 |
| `crates/ethornell-mobile` | 2 | Android/iOS JNI 与 ABI |
| `platform/android` | 1 | Android app/ABI 文档 |

代表性提交（示例，不是完整清单）：

- `30489ed` Mobile: host-driven UserB0 input dialogs (kind 5 events, JSON payloads)。
- `f46d6fb` Host mode: Sys80:3C Retry/Quit prompt and UserB0:A0 modeless dialog。
- `46d9998` Host file policy: hosted roots for web/consoles。
- `9bc5c8e` Sys80:33/36/37/38/39: delete files, loose search paths, composite archives。
- `f45d10b` Audio: long sounds and BGM pairs decode while they play。
- `9a01c73` Engine memory: shared program bodies, parked thread windows, archive image sweep。
- `0ebb370`/`a425c3c` Scheduler yield/display changes。
- `c29c3f4`/`d9cc940` Message wrap/text priority changes。
- `a31122c` Graph movie frame changes。

**结论：** 即使 ABI 只加了一个符号，运行时行为、资源读取、GPU/音频、VM/scheduler、文本布局都可能变化。旧设备证据只能证明旧库；新库必须按 §8 重新验收，不能把“14 符号 ABI 通过”当作新库兼容性证据。

### 2.8 必须适配 / 可选 / 上游内部

| 类别 | 项目 | 处理 |
| --- | --- | --- |
| 必须适配 | 第 15 个导出 `request_payload` | `REQUIRED_API_LIST` 14 + `OPTIONAL_API(request_payload)`；记录 `payloadSymbolAvailable`/`inputDialogsSupported`；P0 强制后者 false；正式发布 15 + provenance（P0） |
| 必须适配 | Event kind 5 接受与路由 | bridge/Session/Activity 全链路（P1） |
| 必须适配 | `request_payload` 两段式读取 | worker 线程、size query（正数=含 NUL 长度）、容量重试、上限；`-4` 非致命（P1） |
| 必须适配 | JSON response | 空 text=int、非空 text=JSON；kind2 严格空 text；**发送前本地校验，畸形 answer 会 poison**（P1） |
| 必须适配 | Sys80:3C Retry 同 id | 用 `pending` + 最多一个 `retryCandidate` 区分重复/Retry；不按历史 id 永久去重；同 id 重入要重新入队/展示（P1） |
| 必须适配 | pending 上限 16 | 与上游对齐；`pending`（未回答 kind2/kind5）+ 最多 1 个 `retryCandidate` 总共不超过 16；第 17 个请求在调用 native 前诊断（P1） |
| 必须适配 | stale/unknown 不 poison | 非致命返回/忽略；不得把所有负码都抛成 FAILED（P1） |
| 回归验证（非新增改造） | 黑边 release=CANCEL | engine 已对越界 release `clear_input`；宿主只做回归，不新增 Activity viewport 计算（P2 验证） |
| 必须适配 | IME/adjustNothing | Activity 已 adjustNothing；dialog window 自行 pan/resize，Surface 不重挂（P1） |
| 必须适配 | detach ACK | 不照抄示例 JNI 的失败即释放/5s 返回；只在 ACK 后释放，超时 watchdog 杀进程（P2） |
| 必须适配 | 存档 barrier/flock/64MiB | 仅 P3 验收；不因 ABI 升级开放管理；0cfff1d 无 save/import/export API |
| 必须适配 | provenance/header/hash/符号 | 与库同批更新（P0） |
| 必须适配 | `build_info`/初始事件限制 | 仍不支持 full IME/电影音频/BMV/WMV/MP4/完整 SWF；不得写成已支持（P4） |
| 可选 | `font_face_index` / `backend` / `flags` | 当前只设 encoding 与 debug trace；无 UI 需求前不扩（P2/Q11） |
| 可选 | `build_info` 文本/特性查询 | 不新增导出；用 `dlsym` + 现有 build_info + 宿主能力位（Q3） |
| 上游内部 | Launcher/GameLibrary/GameActivity | 不搬入 |
| 上游内部 | 上游 JNI 类名/包名 | 不直接复用；示例 App 私有适配层，不是稳定 ABI |
| 上游内部 | 双指右键/滚轮手势 | 仅参考；TyranorNext 已有 InputRemap/CanonicalKeys 体系 |
| 上游内部 | 存档 UI/文件夹导入 | 不搬入；由 App 门禁控制 |

## 3. 宿主基线（2026-11-02 实现后）

> 本节 3.2–3.5 已按 15 符号/kind 5/payload/存档开放回填；§3.6 的测试/工具项仍保留部分计划时描述，实际结果以 §6.6、§8 与 §11 为准。

### 3.1 启动、进程与 dispatch

- App 通过显式 Intent 启动私有 `com.core.ethornell.EthornellActivity`，Manifest 声明 `android:process=":ethornell"`、`singleInstance`、`taskAffinity`、`sensorLandscape`、`adjustNothing`（`T:engine/src/main/AndroidManifest.xml:167-176`）。
- 启动前由 `EthornellOperationGate` 在主进程串行化 preflight→startActivity，并用 `EthornellDispatchRegistry` 持久化 ISSUED/CLAIMED/TERMINAL；Activity 在创建 runtime 前 claim token（`T:app/src/main/java/com/tyranor/next/core/game/launch/EthornellOperationGate.kt:12-20,56-58`、`T:engine/src/main/java/com/core/ethornell/EthornellActivity.java:118-127`）。
- 正常关闭由 `Listener.closed` 触发：先等 native destroy ACK，再 finish dispatch、清 registry、退出私有进程（`T:EthornellActivity.java:163-171,483-490`）。
- `LaunchResult.Success` 只代表 Activity dispatch 成功，不代表 native 初始化成功；这是现有契约（`T:app/ETHORNELL-INTEGRATION.md:3-4`）。

### 3.2 JNI 与 C ABI（当前实现）

- 正式库有 15 个 `ethornell_android_*` 导出；bridge 的 `REQUIRED_API_LIST` 仍是 14 个基础符号，`request_payload` 独立 `dlsym` 为第 15 个。正式发布门禁要求 15；旧 14 符号库只能以 `payloadSymbolAvailable=false` 的 legacy 能力加载，kind 5 不可用（`T:engine/src/main/cpp/ethornell_bridge.cpp`）。
- `load()` 用固定 SONAME `libethornell.so` `dlopen(RTLD_NOW|RTLD_LOCAL)`，逐个解析基础符号并校验 `api_abi_version()==1`；基础符号缺失或版本不符即失败。
- `create()` 校验 Application、编码、路径，设置 `struct_size`/`abi_version`/roots/encoding；只在 Debug + 授权文件存在时设 trace flag。
- `poll()` 接受 kind 1–5，越界仍拒绝；`respond()` 对空 text 走整数结果，非空 text 作为 kind 5 JSON 回答，kind 2 不使用 JSON。
- `requestPayload` 在 owner 线程两段式读取：size query 正长度为含结尾 NUL 的字节数，容量足够才拷贝，未知/过期 id 返回 null 且不 poison。
- 桥用 `sessions` 表保存 `nativeHandle`、owner thread、window/generation；所有调用检查 owner thread，token 不是指针。
- `JNI_OnLoad` 注册 11 个 native 方法，包含 `requestPayload` 与 4 参 `completeRequest`；`NativeEthornell` 同时保留 3 参整数应答便捷重载（`T:engine/src/main/java/com/core/ethornell/NativeEthornell.java`）。

### 3.3 Session（单 owner 运行时，当前实现）

- `EthornellSession` 有一个 `Thread("Ethornell-engine")` worker，所有 backend 调用都在该线程；`Backend` 接口包含 `requestPayload` 与 `respond(..., String text)`（`T:engine/src/main/java/com/core/ethornell/EthornellSession.java`）。
- `Event` 在 `kind/requestId/code/text` 之外增加 `payload` 与 `payloadError`；kind 5 的完整 JSON 由 worker 在投递 listener 前通过 `requestPayload` 取回，UI 线程不直接调用 native。
- `pending` 只保存当前未回答的 kind 2/kind 5；`retryCandidate` 只保留最近一次已回答的 kind 2 id，下一次 poll 若先看到同 id 的 kind 2 视为 Sys80:3C Retry，重新入 pending 且不额外占预算。预算为 `pending + (retryCandidate != 0 ? 1 : 0) <= 16`，不再使用 64 或永久 `seenRequests`。
- 重复/未知/过期回答静默丢弃，不调用 native、不 poison；`destroy`/close 清理 pending 与 retryCandidate。
- queue 上限 4096；key/button 边沿不允许静默丢弃；`detachAndWait` 在 UI 线程等待 worker ACK，先发 `Input.cancel()`，再 detach。
- `frame()` 只保留最新 time/generation，最多一个 outstanding tick；失败进入 `FAILED` 并 cleanup，`listener.closed` 只在 destroy 后触发。

### 3.4 Activity、Surface、输入、音频（当前实现）

- Activity 用 `SurfaceView` + `Choreographer`；`doFrame` 只提交 time/generation，实际 `step` 在 worker。
- `surfaceChanged` 先 `detachAndWait` 旧 generation，再 attach 新 generation；`surfaceDestroyed` 同样等待 detach。
- 触摸：单指映射左键，`ACTION_CANCEL` 调 `releaseInput()`（发 CANCEL）；越界 UP 仍依赖 Rust 的 aspect-fit 转换，不在 Activity 复制 viewport。
- 鼠标/滚轮/键盘映射沿用既有 `EthornellInputSink`；`releaseAll()` 发 CANCEL。
- 音频：`AudioFocusRequest` + `ACTION_AUDIO_BECOMING_NOISY` 控制 `PAUSE_AUDIO`。
- 对话框：kind 2 走宿主确认框，kind 5 走 `EthornellHostDialog`（`input`/`segmented`/`select`/`date` 四类），使用独立 dialog window 与 IME 模式，Activity 保持 `adjustNothing`，不因键盘改变 Surface；取消发送 `{"accepted":false}`。
- `hostEvent` 处理 kind 2/5（暂停并展示）、kind 3（关闭并 dismiss dialog）；`onPause`/焦点丢失/`onDestroy` 维护 dialog 取消与 `PAUSE_DIALOG` 原因。真实商业游戏的对话框/IME 尚未验证。

### 3.5 存档与 App 门禁

- 计划基线为 `DEVICE_SAVE_IO_VERIFIED=false`、`HOST_DISPATCH_PROTOCOL_VERIFIED=true`、`available=false`；**本次 2026-11-02 设备验收后两者均为 true**，`available=true`，范围仅私有 user-root（`T:app/src/main/java/com/tyranor/next/core/game/save/EthornellSaveCapability.kt`）。
- `GameSaveManager` 对 ETHORNELL 只返回 identity-scoped 私有目录，`enabled = directory != null && available`（`T:app/src/main/java/com/tyranor/next/core/game/save/GameSaveManager.kt:63-80`）。
- `EthornellSaveStore` 是 App 侧 ZIP 备份/导入，不是 native 存档格式转换器；默认 20,000 文件 / 1GiB ZIP 预算（`T:app/src/main/java/com/tyranor/next/core/game/save/EthornellSaveStore.kt:29-35`），保留 `.ethornell-*`、64 层/100,000 节点（`:333-339`）。
- `EthornellOperationGate` 明确是**主进程协调**，不是 native writer flock 的替代；活动 host 存活/未 destroy ACK 时存档操作保持 busy（`T:EthornellOperationGate.kt:12-20`）。
- `EthornellSaveStoreTest#gateOpensAfterDeviceFixtureAcceptance` 已同步为断言 `DEVICE_SAVE_IO_VERIFIED=true`（`T:app/src/test/java/com/tyranor/next/core/game/save/EthornellSaveStoreTest.kt:48-50`）；P3 前的关闭断言只保留在历史记录中。
- 上游 `0cfff1d` 的 15 个导出中没有 save/import/export API（`E:platform/android/include/ethornell_android.h:46-71`），所以当前门禁不能因 ABI 升级而打开；只能按 P3 的 user_root/barrier/flock 证据独立评审。

### 3.6 测试、工具与文档

- JVM：`EthornellSessionTest`、`EthornellDialogProtocolTest`、`EthornellInputSinkTest`、`EthornellLifecycleTest`、`EthornellPathsTest`、`EthornellDispatchRegistryTest`、`EthornellProcessExitTest`、`EthornellFailureDetailTest`、`EthornellInputTraceTest`（`T:engine/src/test/java/com/core/ethornell/`）。
- 设备：`EthornellDeviceTest`、`EthornellPauseDeviceTest`、`EthornellDispatchDeviceTest`、`EthornellPrivateActivityTest`、`EthornellSaveDeviceTest`（`T:app/src/androidTest/...`）；本轮 device 6/6、save 9/9、pause 3/3（多 stage/step 夹具；范围见下）。
- release probe：纯 Java instrumentation，加载目标 APK 的 `NativeEthornell`，C++ probe 检查 15 符号/build_info/ABI 布局；事件校验 `kind >= 1 && kind <= 5`（`T:app/src/releaseProbe/java/com/tyranor/next/releaseprobe/EthornellReleaseInstrumentation.java:121,177-179`）；本轮 SJIS/GBK 各 3/3 通过。
- C++ probe 输出 `symbols=15`（`T:app/src/androidTest/cpp/ethornell_test_probe.cpp:41`），README 同步为 15 exports（`T:app/src/releaseProbe/README.md`、`T:app/src/androidTest/README.md`）。
- `tools/ethornell/verify_provenance.py` 校验 header 原始字节 SHA 与 source inventory、库字节、ABI 版本；`verify_native.py` 从 header 解析 required ABI 原型（`T:tools/ethornell/verify_provenance.py:107-139`、`T:tools/ethornell/verify_native.py:41-56,222-238`）。
- CI 固定三条正式路径并拒绝缺失：`libethornell.so`、`libethornell.provenance.json`、`ethornell_android.h`（`T:.github/workflows/ethornell-native.yml:44-52`）。

## 4. 差距矩阵

**优先级定义：**

- **P0**：ABI/产物身份或加载安全；不解决就不能合并/发布。
- **P1**：首个可用的 kind 5 构建必需；不解决就没有输入对话框能力。
- **P2**：发布前必须完成的生命周期/输入/存储/回归项；不解决不能宣称兼容或开放存档。

| ID | 差距 | 当前事实 | 上游 `0cfff1d` 要求 | 影响 | 优先级 |
| --- | --- | --- | --- | --- | --- |
| G01 | 15 符号 | 14 符号；`request_payload` 缺失 | 第 15 个导出；正式发布 15 + provenance，运行时可选 dlsym + 能力位 | 新库 + 旧桥 = kind5 时桥抛错；旧库 + 新桥 = 仅能在 dialogs disabled 的 legacy 模式运行 | P0 |
| G02 | kind 5 事件 | bridge 只接受 1–4，Activity 只处理 1–4 | kind 5 = dialog request，text=类型 | 任何输入对话框都会使旧宿主失败或忽略 | P0/P1 |
| G03 | `request_payload` | 没有 JNI/C 方法 | size query 返回正数（含 NUL）；`-4` 未知/过期且不 poison | 无法获得完整 dialog 请求；用错 status 逻辑会截断/误判 | P1 |
| G04 | JSON response | `respond` 只有 int，`text` 恒空 | 空 text=int；非空 text=JSON；kind5 忽略 int；畸形 answer poison | 无法提交用户输入；误传 text 破坏 kind2；半成品 JSON 可杀会话 | P1 |
| G05 | stale/unknown 不 poison | `status()` 对任何负码都抛异常；Session 会 FAILED | 旧 generation/未知 request/回退 clock 不 poison；payload -4 非致命 | 重复点击/旧对话框可能误杀健康会话 | P1 |
| G06 | 未知事件 kind 的前向兼容 | `kind < 1 \|\| kind > 4` 直接抛 | 只定义 1–5；未规定未来 kind | 未来加法会再次造成整会话失败 | P1/P2（提议） |
| G07 | 黑边 release=CANCEL | 越界 UP 发 ButtonUp，依赖 Rust 转换；仅 `ACTION_CANCEL` 显式 CANCEL | 上游 `HostRuntime::input` 对越界 release 已 `clear_input`，宿主不需新增 viewport 计算 | 可能出现 phantom UP/旧按钮激活 | P2（回归验证，非新增改造） |
| G08 | IME / adjustNothing | Manifest 已 `adjustNothing`；但无 EditText/IME，对话框只有 TextView | kind5 输入框需要文本输入；上游未声明“完整 IME” | 无法输入文本；若改 Activity resize 会重建 Surface | P1 |
| G09 | Surface/detach | 已有 detachAndWait/generation；只在 ACK 后释放 | detach ACK 后才能释放 window；0 尺寸 detach；示例 JNI 的失败即释放/5s 等待不是契约 | 旧 Surface 借用/黑屏/崩溃风险 | P2 |
| G10 | pause/clock/input | 已有独立 pause mask、clock gate、queue | 与旧契约相同；新库行为可能变化 | 暂停推进/输入丢失/队列积压 | P2 |
| G11 | user_root / barrier | App 只在 host 停止后备份；无 native barrier 测试 | 写后命令/destroy 才是 IO barrier；pause 不是 | 导出可能缺少最后一次写 | P2/P3 |
| G12 | 64MiB / atomic write | App ZIP 预算 1GiB，不能代表 native 单次写上限 | 原子写上限 64MiB；rename/sync 语义 | 误判“写成功”/大文件失败 | P3 |
| G13 | flock / reserved | App gate 不是 native writer flock；store 保留 `.ethornell-*` | writer flock 由 engine 持有；reserved 不可读改 | 并发写/损坏存档 | P3 |
| G14 | 存档管理门禁 | 曾为 `available=false`；本次 P3 验收后为 `true`（范围受限） | 不因 ABI 升级自动开放；设备验收后才翻转 | 误开放导致数据风险 | P3（已完成） |
| G15 | provenance/header/hash | 当前 header/lib 都对应 `7ee2067` | 新 header/lib/provenance/source revision 成套 | 半套产物无法复现/审计 | P0 |
| G16 | 测试/文档 14→15 | probe/README/releaseProbe 均写 14 | 15 符号 + kind5 | CI 漏检第 15 符号 | P0/P4 |
| G17 | build_info/source rev | 当前嵌入 `7ee2067` | 新库应嵌入 `0cfff1d` | 无法证明运行库来源 | P0 |
| G18 | 库名 | 当前打包 `libethornell.so`；上游产物 `libethornell_mobile.so`，ELF 无 SONAME | 上游 app 加载 `ethornell_mobile` | 改名会影响 `dlopen`/probe/CI | P2（Q1） |
| G19 | 152 提交行为变化 | 旧设备证据只对旧库 | 新库 GPU/音频/VM/存储可能变化 | 旧“已通过”不可外推 | P2/P4 |
| G20 | 音频/GPU/真实游戏回归 | 旧候选证据 | 无 ABI 变化，但行为需重验 | 兼容性声明可能失真 | P4 |
| G21 | Sys80:3C Retry 同 id | `seenRequests` 永久去重；第二次同 id 被丢弃 | 第一次回答后文件仍缺失，会以同 id 再发 kind 2，pending 未结算 | VM 永久等待；表现为“确认后无反应” | P1 |
| G22 | 请求预算 16 | 当前允许 64；若把 candidate 排除会算少，若把普通确认历史累积会误堵 | 第 17 个 host request 返回 `host request queue limit` 并 poison；宿主预算 = `pending`（kind2+kind5）+ 最多 1 个 `retryCandidate`，总数 ≤16；15 pending + 1 candidate 时新请求必须在调用 native 前诊断 | 普通确认累积误堵；candidate 被漏算导致第 17 个 native 请求才失败 | P1 |
| G23 | malformed JSON poison | 当前无 JSON 校验；若直接发送半成品会杀会话 | malformed answer 是错误并 poison；host 发送前本地校验，失败发 `{"accepted":false}` | 一次 UI 构造错误可导致整局失败 | P1 |
| G24 | payload 正长度/`-4` | 易套用 `status()` 的 0/负值逻辑 | 正数=含 NUL 所需字节；`-4` 未知/过期非致命 | 截断/误判/误 FAILED | P1 |
| G25 | 示例 detach 行为 | 当前实现只在 ACK 后释放，已正确 | 示例 JNI 失败也释放、Activity 等 5s；不是契约 | 照抄会提前释放 window | P2 |
| G26 | 无 save API | 曾为 `available=false`；本次已按 P3 开放 | `0cfff1d` 没有 save/import/export API；user_root 契约未变 | 误以为 ABI 升级可开放管理 | P3（已完成） |
| G27 | build_info/初始事件限制 | 旧文档可能只写“IME 未实现” | 仍不支持 full IME/电影音频/BMV/WMV/MP4/完整 SWF | 能力声明夸大 | P4 |

## 5. 目标架构与关键决策

### 5.1 分层与不变式

目标结构保持三层：

```
App UI（Compose）
  ↓
App core（启动编排 / dispatch / 存档门禁 / 设置）
  ↓
engine host（私有 :ethornell Activity / Session / JNI bridge / libethornell.so）
```

不变式：

1. **一个 engine owner 线程**：`create`、surface、input、step、poll、payload、response、destroy 全部在 `Ethornell-engine` worker 上串行；UI 线程不持有 native handle。
2. **payload 在 worker 取**：kind 5 事件到达 worker 后，worker 立即做 `request_payload` 的 size query + copy；UI 只收到不可变 `String payload` 与 `requestId`。
3. **dialog 只做 UI 适配**：Activity 只解析/展示/收集，不执行 VM、GPU、文件策略；JSON 编解码放在 engine host 的协议类，不放到 App UI。
4. **请求恰好结算一次**：kind 2 用 int，kind 5 用 JSON text；空 text 与非空 text 严格分流；重复/过期 id 不重复回答。
5. **不投机回答**：没有默认 yes/no；取消必须是显式 `{"accepted":false}` 或用户动作。
6. **存储独立**：engine 的 user_root 写与 App 的存档备份/导入是两条链；App 只在 host 停止且 barrier 成立后操作，不把 `pause` 当 flush。
7. **身份成套**：header、库、provenance、source revision、15 符号、App commit 必须同批更新；不允许“只换库”。
8. **私有 Activity/dispatch 不变**：不把上游 app 的 Launcher/GameActivity 进程模型引入。
9. **`last_error` 是 owner 线程 TLS**：错误状态只在发生错误的同一 engine 线程读取；payload 的 `-4`（未知/过期）不走 `status()`，也不借 `last_error` 判 fatal。
10. **`request_close` 不是强制退出**：它保留 WM_CLOSE 语义，不能取消 pending dialog；关闭被阻塞的 dialog 只能由显式 `destroy` 取消，不能伪造回答。
11. **`destroy` 不产生 kind 3**：kind 3 只由游戏/窗口 close 路径产生；cleanup 不能等待 kind 3，destroy ACK 本身就是清理完成条件。
12. **step 不保证每步一帧**：`take_present_due()` 可能为 false；画面/像素验收只按实际 present/readback，不按 step 次数。

### 5.2 数据流

```mermaid
flowchart TD
    A[App core: launch/dispatch] -->|Intent + token| B[private :ethornell Activity]
    B --> C[EthornellSession worker: Ethornell-engine]
    C -->|create / setPaused / setSurface| D[libethornell.so C ABI]
    E[Choreographer UI] -->|frame(time, generation)| C
    C -->|step| D
    D -->|poll_event| C
    C -->|kind 2 confirmation| F[UI confirmation dialog]
    C -->|kind 5 + payload: worker request_payload| G[UI input dialog]
    F -->|int result| C
    G -->|JSON text| C
    C -->|complete_request: empty text / JSON| D
    C -->|kind 3 closed| H[destroy ACK -> finish]
```

文本版：

```text
UI Choreographer
  → Session.frame(gen,time)                 (只投递，不调 native)
  → worker step(now)
  → worker poll_event()
      kind 2 → Event(confirm) → UI dialog → respond(int) → worker complete_request(text="")
      kind 5 → worker request_payload(id)  (size query + copy)
             → Event(type,payload) → UI dialog
             → respondJson(JSON) → worker complete_request(text=JSON)
      kind 3 → close → worker destroy → ACK → UI finish
```

关键点：

- `request_payload` 必须在 worker 上调用；如果 Activity 在 UI 线程直接调 `NativeEthornell.requestPayload`，会触发 owner-thread 拒绝。
- kind 2 可能因 Sys80:3C Retry 以**同一 `request_id`** 再次出现；这不是重复事件，而是 VM 仍在等待。Session 必须用 `pending` + `retryCandidate` 区分“当前未回答的重复”和“已回答后的 Retry”，不能在第一次回答后把 id 永久加入黑名单（`E:crates/ethornell-app/src/host_runtime.rs:156-163,855-881`）。
- UI 对话框显示期间，Session 设置 `PAUSE_DIALOG`（bit 3，值 8）；VM 被 pending request 挂起，不需要额外暂停 Surface。
- UI 取消/关闭/Activity destroy 必须走显式路径：取消 → `{"accepted":false}`；Activity destroy → `session.close()` → destroy 取消 pending；不能只把 UI 队列清空而留下 native pending。
- 任何 payload 解析失败都不应让 worker 抛异常；应生成“不可用”事件，让 UI 显示可取消的错误，而不是把会话标记 FAILED。

### 5.3 关键决策

| 决策 | 选择 | 理由 | 被否决的替代 |
| --- | --- | --- | --- |
| D1 宿主边界 | 保留 TyranorNext 自有 C ABI shim + JNI + 私有 `:ethornell` Activity | 已有 dispatch、身份 user_root、存档门禁、进程退出协议；上游 app 是独立 launcher/player，搬入会破坏分层 | 整包复制上游 app；直接复用上游 JNI 类名 |
| D2 ABI 版本 | 保持 v1，不升 2；**正式发布 15 符号 + provenance 必需；运行时拆 `payloadSymbolAvailable` 与 `inputDialogsSupported` 两个宿主能力位** | 上游未升版本，结构体不变；可选 dlsym 支持旧库回滚；P0 只能让 `payloadSymbolAvailable=true`，`inputDialogsSupported` 必须 false | 本地升 2；把 `payloadSymbolAvailable` 当 UI 可用；把 P0 当发布候选 |
| D3 payload 线程 | worker 取完整 payload，再 post UI | 满足 owner-thread；避免 UI 阻塞/死锁；payload 可做边界校验 | UI 线程直接调 native；把 payload 放进 Event.text 固定 2048 缓冲 |
| D4 dialog UI | engine host 内原生对话框 + 独立协议类 | engine 不能依赖 App Compose；保持游戏画面/IME 控制；JVM 可测协议 | 在 App UI 层做 dialog；把 Dialog 混进 Session |
| D5 错误策略 | 只有真实 runtime/GPU/audio/policy 失败 poison；stale/unknown/重复回答非致命；**malformed answer 是 poison，必须发送前本地校验** | 上游明确 stale 不 poison；malformed 明确 poison；避免旧 UI 事件误杀会话 | 所有负码统一抛异常/FAILED；把 malformed 当 cancel |
| D6 存储 barrier | 只用 destroy/进程死亡作为导出前 barrier；不新增 flush API | 上游没有 live flush 命令；pause 不是 barrier；App 现有 gate 已禁止 live 导出 | 把 pause 当 flush；新增私有 flush 导出 |
| D7 存档门禁 | 两个 flag：`DEVICE_SAVE_IO_VERIFIED`（含 native storage contract）+ `HOST_DISPATCH_PROTOCOL_VERIFIED`；P3 前保持 false，本次验收后均为 true（范围受限） | 存档是数据安全边界，不能随 ABI 升级开放；0cfff1d 无 save API | 三个 flag/ABI 升级即打开/用 UI 开关代替证据 |
| D8 产物身份 | header + lib + provenance + source rev + 15 符号成套 | 防止半升级/错库；CI 可审计 | 只换 `libethornell.so`，header/provenance 不动 |
| D9 request_id 策略 | kind 2 用 `pending`（当前未回答）+ **最多一个** `retryCandidate`（最近已回答的 kind2 id）区分重复与 Retry；kind 5 按 pending/seen 防重复 | 上游 Sys80:3C 在 `complete_request` 内立即重发同 id；宿主回答后下一次 `poll()` 就能看到；永久 seen 会挂死 VM，只按 pending 会把 Retry 当重复 | 所有 kind 永久按 id 去重；把每个已回答 kind2 都保存为历史 |
| D10 请求预算 | **`pending`（未回答 kind2+kind5）+ 最多 1 个 `retryCandidate` ≤ 16**；第 17 个请求必须在调用 native 前诊断，不静默积压 | 上游 `host.pending` 包含 Retry 的 id（`host_runtime.rs:156-163,869-877`）；排除 candidate 会少算，累积普通确认历史会误堵 | 保留 64；低估 candidate；累积历史 |

**D2 的取舍结论：** `request_payload` 在**运行时**是可选的 `dlsym`，但必须拆成两个宿主侧布尔值：

- `payloadSymbolAvailable`：第 15 个符号是否解析成功（旧库 + 新桥可为 false）。
- `inputDialogsSupported`：真正允许展示/回答 kind 5 的能力；只有 `payloadSymbolAvailable && completeRequestTextAvailable` 且 **P1 的 UI 已交付**时才为 true。P0 强制 `inputDialogsSupported=false`，即使新库的 `payloadSymbolAvailable=true`。

正式发布门禁仍是 15 符号 + provenance。这样既能渐进回滚，又不会把“新库 + 旧桥”或“P0 半成品”的 kind 5 当成功。任何把“可选 dlsym”误解为“可以发布 14 符号库”或“P0 可发布”的实现都违反本计划。

> `payloadSymbolAvailable` / `inputDialogsSupported` / `retryCandidate` 是本文提议的**宿主侧**布尔值/状态名，不是上游导出、结构体字段或 ABI 术语；上游只提供 `request_payload`、kind 5 与 request_id。

### 5.4 ABI 版本策略（重要）

上游仍是 1，**本地不要升 2**。原因：

- 升 2 会让旧库/旧宿主立即不兼容，但上游/其他宿主仍按 1 编译；这会把“加法升级”变成私有分叉。
- 不升 2 的风险是：新库 + 旧桥的 `abi_version()` 仍返回 1，旧桥的 14 符号检查会通过；直到游戏打开输入对话框，kind 5 才触发旧桥的 `Invalid Ethornell event`，会话才失败。这就是“半升级”最危险的形态。
- 对策不是改版本号，而是采用**双层策略**：
  1. **正式发布门禁**：header + lib + provenance + source revision + 15 符号必须成套；CI/release probe 要求 `symbols=15`，缺一拒绝发布。
  2. **运行时能力检查**：新 bridge 在 `create` 前完成 `build_info`/ABI/基础 14 符号/可选 `payloadSymbolAvailable`/`completeRequestTextAvailable` 快照；`inputDialogsSupported` 只由这两个宿主能力位和 P1 UI 交付共同决定。旧库 + 新桥可以加载，但 `payloadSymbolAvailable=false`、`inputDialogsSupported=false`，不得展示/宣称 kind 5。
  3. **P0 fail-closed**：P0 即使 `payloadSymbolAvailable=true` 也强制 `inputDialogsSupported=false`。kind 5 若真的到来，必须明确记录“input dialog unsupported”并结束该会话（fail-closed），不得自动发送 `{"accepted":false}` 假装取消成功，也不得返回成功/默认答案。
  4. **新库 + 旧桥仍然禁止**：旧桥没有能力位，无法安全处理 kind 5；只能靠“库/桥同批提交”、JNI 方法表/反射门禁与 CI/provenance 阻止。
  5. 任何“只换库”的提交直接拒绝；任何把 `request_payload` 缺失或 P0 半成品当“可用”的实现直接拒绝；构建/CI 必须有 P0 禁止进入 release 的显式 gate。

如果未来上游升 2，再按上游 ABI 迁移；不要预支。

### 5.5 错误、取消与 poison 策略

| 场景 | 处理 | 是否 poison | 证据/约束 |
| --- | --- | --- | --- |
| `request_payload` 未知/过期 id | 返回 null/不可用事件；UI 可取消；不抛 FAILED；`-4` 不当 transport 失败 | 否 | `E:crates/ethornell-mobile/src/lib.rs:92-135,517-537`；ABI.md 31-35 |
| `request_payload` 正长度 | 正数=含 NUL 所需字节；先 size query 再分配；不能套 `status()==0` | 否 | `E:crates/ethornell-mobile/src/lib.rs:259-268,517-537` |
| 重复点击/旧 dialog 回答 | 先检查 `pending`/`uiPending`；重复回答忽略 | 否 | 上游 `with_session` 对 unknown/stale 不 poison；宿主仍应防重复 |
| kind 2 Retry 同 id | `pending` 表示当前未回答；回答后只保留**最多一个** `retryCandidate`；下一次 poll 同 id 先到 → 视为 Retry，移入 pending 重新展示（总数不变），否则清除；普通确认不累积；candidate 存在时占 1 个预算 | 否 | `E:crates/ethornell-app/src/host_runtime.rs:156-163,855-881`；测试 `:1226-1236` |
| pending 达到 16 | 不再接受新 UI 请求；明确诊断/停止，不静默排队 | 由上游 engine 决定；宿主不能假装成功 | `E:crates/ethornell-app/src/lib.rs:21685-21686,21706-21707,21743-21744` |
| kind 2 误传非空 text | 宿主代码层禁止；若发生，记录协议错误并取消该对话框 | 不应由一次 UI 错误 poison；但需修复代码 | `E:crates/ethornell-mobile/src/lib.rs:486-512` |
| kind 5 发送 malformed JSON | 发送前本地校验；构造失败改发 `{"accepted":false}`；绝不发半成品 | 是（如果 malformed 到达引擎） | `E:crates/ethornell-app/src/dialog_protocol.rs:92-167`、`E:crates/ethornell-app/src/host_runtime.rs:834-843` |
| payload 超过宿主上限/UTF-8 非法 | 不调用 native；生成“payload 不可用”UI；用户可取消 | 否 | 宿主策略，不是上游语义（提议） |
| 未知 event kind（>5） | 记录有界数字，丢弃该事件，不 poison；继续 poll | 否（提议） | 上游只定义 1–5；为未来加法留余地 |
| 真实 GPU/VM/audio/policy 失败 | 按现有 `status()` 失败路径；FAILED/cleanup | 是 | `E:crates/ethornell-mobile/src/lib.rs:92-135` |
| Activity destroy 时 dialog 未回答 | `session.close()` → destroy 取消 pending；不伪造回答 | 会话关闭，不是 poison | `T:EthornellSession.java:215-223,244-257` |

**建议实现细节（未验证）：** 给 `Backend` 增加 `String requestPayload(...)` 和 `void respond(..., int result, String text)`；`Session.poll()` 在 `listener.event` 之前调用 payload；payload 失败时构造 `Event(kind=5, payload=null, payloadError=...)`，Activity 显示“无法加载输入对话框”并只提供“取消”。这样既不把 UI 错误升级为会话 FAILED，也不默认回答 yes。请求 id 侧把 `seenRequests` 从“永久历史门”改为“pending + 单个 retryCandidate”：kind 2 回答后只记录最近一个已答 id；下一次 `poll()` 同 id 先到就视为 Retry，移入 pending 重新展示，否则立即清除；普通确认不累积成历史。16 预算按 `pending.size() + (retryCandidate != 0 ? 1 : 0) <= 16`；candidate 存在时占 1 个，但 Retry 移入 pending 后总数不变。kind 5 仍按 pending/seen 防重复。

## 6. 分阶段实施计划

> 阶段名 P0–P4 与 §4 的“差距优先级”不是同一套编号。每个阶段都必须保持“可构建、可审查、不可半交付”的边界。

### 阶段 P0 — ABI 与产物闭环

**目标：** 把新库、新 header、新符号、provenance 作为一套引入；建立**完整 response text 通路**（bridge/JNI/Session）与 P0 的 kind 5 fail-closed 语义；但**不交付**输入对话框 UI，P0 不得进入 release。

**改动文件：**

- `engine/src/main/cpp/ethornell_android.h`：替换为上游 `0cfff1d` 的原始字节（SHA `566f475c...`，见 §11）。
- `engine/src/main/cpp/ethornell_bridge.cpp`：改为 `REQUIRED_API_LIST`（14）+ `OPTIONAL_API(request_payload)`；第 15 个符号单独 resolve，记录 `payloadSymbolAvailable`（不并入基础 14 的 `valid` 布尔）；`completeRequest` 增加 text 参数并实现空 text/int、非空 text/JSON 分流；`poll()` 接受 kind 1–5；kind 5 先转发为最小事件（payload 可为空），不得抛 `Invalid Ethornell event`。
- `engine/src/main/java/com/core/ethornell/NativeEthornell.java`：增加 `requestPayload(long,long)` 与 `completeRequest(long,long,int,String)` 的 JNI 声明/最小实现（P0 必须可被反射检查；P1 完整两段式读取）。
- `engine/src/main/java/com/core/ethornell/EthornellSession.java`：`Event` 增加 nullable payload；`Backend` 增加 `requestPayload` 与 `respond(..., int, String)`；`poll()` 对 kind 5 不失败；P0 的 kind 5 只允许报 `input dialog unsupported` 并 fail-closed，不允许自动取消/默认回答。
- `engine/src/main/java/com/core/ethornell/EthornellActivity.java`：kind 5 到来时明确显示/记录“input dialog unsupported”，通过 `session.close()`/destroy 结束会话（fail-closed）；P0 不提供四类 UI，不发送 `{"accepted":false}` 假装取消成功。
- 构建/CI gate：P0 的 `inputDialogsSupported` 必须是 false；release 构建/发布脚本显式拒绝 P0 标记（见 P0 退出标准与 P4 CI）。
- `engine/src/main/jniLibs/arm64-v8a/libethornell.so` 与 `.provenance.json`：从 `0cfff1d` 重新构建并成套替换（P0 分支；P1 才允许进入正式发布包，见“不可交付中间态”）。
- 测试/工具：`app/src/androidTest/cpp/ethornell_test_probe.cpp` 符号列表与 `symbols=15`；`EthornellReleaseInstrumentation` 的 kind 上界；`app/src/releaseProbe/README.md`、`app/src/androidTest/README.md`；`tools/ethornell` 的 manifest/header 期望。

**关键接口：**

```java
// NativeEthornell (P0 必须有这些方法；P1 完整实现 payload 两段式读取)
static native String requestPayload(long handle, long requestId);
static native void completeRequest(long handle, long requestId, int result, String text);
```

```cpp
// 基础 14 符号：缺失即 load 失败。
#define REQUIRED_API_LIST(X) \
    X(abi_version) X(build_info) X(last_error) X(init_context) X(create) X(set_surface) \
    X(detach_surface) X(step) X(input) X(set_paused) X(request_close) X(poll_event) \
    X(complete_request) X(destroy)

// 第 15 符号：单独 resolve；不并入基础 valid，缺失只关能力。
#define OPTIONAL_API(name) /* dlsym("ethornell_android_" #name) -> payloadSymbolAvailable */
OPTIONAL_API(request_payload)
```

**P0 的 `inputDialogsSupported` 必须硬编码为 false。** 只有 P1 完成四类 UI、完整 `completeRequest(..., String)` 链路和测试后，才允许在 P1 候选把它置 true；构建/CI 必须拒绝 P0 进入 release（例如检查 `inputDialogsSupported` 的编译常量/资源标记）。

**测试：**

- JVM：`EthornellSessionTest` 的 fake backend 返回 kind 5，断言 Session 不 FAILED、listener 收到事件、pending 状态正确；重复/未知 id 不导致 FAILED；`respond(id, result, text)` 对空 text 走 int、对非空 text 走 JSON；P0 的 kind5 fail-closed 有显式断言。
- JVM/反射：断言 `NativeEthornell` 的 JNI 方法表/Java 反射同时存在 `requestPayload(long,long)` 与 `completeRequest(long,long,int,String)`；仅检查库的 15 个导出不能证明“新库 + 旧桥”已修复。
- native/设备：`loadAbiBuildInfoAndRejectInvalidTokens` 断言 15 符号、ABI=1、结构体尺寸/offset 不变、build_info size query。
- 旧库夹具：保留旧 `libethornell.so` 的只读字节，或生成 **test-only 14 符号 stub**；旧库 + 新 bridge 时 `payloadSymbolAvailable=false`、`inputDialogsSupported=false`，kind 5 不得被当成成功。没有夹具时该退出标准不得勾选。
- release probe：15 symbols；`kind >= 1 && kind <= 5`；用非法/未知 id 调 `requestPayload`，断言返回错误/null 且不毒化会话；反射检查 `completeRequest(String)` 存在。
- 工具/产线：`python3 -m unittest discover -s tools/ethornell/tests -v`；`verify_native.py` 解析 header 时 required ABI 自动包含第 15 个；provenance header hash 与新 header 一致。
- P0 release gate：构建/CI 检查 `inputDialogsSupported=false` 标记；P0 产物不得进入 release 或发布流水线。

**退出标准：**

- 新库 + 新 header + 新 bridge 在 15 符号检查下可加载、create、poll、destroy。
- kind 5 不再导致 bridge 抛错；P0 明确 fail-closed（“input dialog unsupported”并结束会话），不自动取消、不返回成功。
- `completeRequest(..., String)`、`Session.respond(..., String)`、bridge text 路由均存在；空 text/JSON 分流有测试。
- `build_info` 嵌入 `0cfff1d...`；provenance 的 `source_revision`/`library_sha256`/header hash 三者一致，并满足 §6 P0 的完整 provenance 字段（不是只写 `source_revision`）。
- 所有 14→15 的测试/README 字符串已更新；无残留“14”断言（基线描述除外）。
- 可选 dlsym 的 legacy 路径有旧库/test-only stub 夹具：缺失 `request_payload` 时 `payloadSymbolAvailable=false`、`inputDialogsSupported=false`。
- P0 的 `inputDialogsSupported=false` 被构建/CI gate 锁定，P0 不能进入 release。

**不可交付的中间态：**

- **新库 + 旧 bridge**：禁止。旧桥 14 符号检查会通过，但 kind 5 会 FAILED；JNI 方法表/反射门禁必须阻止。
- **旧库 + 新 bridge**：只允许作为 legacy/回滚模式，且 `payloadSymbolAvailable=false`、`inputDialogsSupported=false`、kind 5 不可用；不得作为发布候选，不得宣称输入对话框支持；必须有旧库/stub 夹具。
- **P0 的 fail-closed 构建**：只能作为内部/开发候选，不得作为用户版本；`inputDialogsSupported` 必须 false，release gate 必须拒绝。
- **P0 自动发 `{"accepted":false}`**：禁止；这会伪装成用户取消，违反“不投机回答”。

**P0 provenance / 构建闭环（必须与库同批）：**

- **上游 `platform/android/build.sh` 不生成 provenance JSON**：它只构建 `libethornell_mobile.so`/APK 并运行 `verify_elf.py`（`E:platform/android/build.sh:1-62`）；当前 `libethornell.provenance.json` 是项目侧 builder 的产物（`build_command` 字段记录了 cargo-ndk/strip/改名过程）。P0 必须用本项目 builder 重新生成，不得把上游脚本产物直接当正式交付。
- provenance 必须包含：`abi:1`、`target:"aarch64-linux-android"`、`api:26`、`ndk:"28.0.13004108"`、非空 `rustc`、完整 40 位 `source_revision`、`dirty:false`、`source_inventory_policy:"cargo-workspace-build-inputs-v1"`、完整 `source_file_sha256`（清单必须含 header、根 `Cargo.toml`、`Cargo.lock`，并按 policy 覆盖全部构建输入）、`cargo_lock_sha256`、`library_sha256`、`library_bytes`、`crate_artifact:"libethornell_mobile.so"`、`packaged_name:"libethornell.so"`、`source_repository` 为批准 fork `https://github.com/Weiss-UltimateSavior/ethornell-rs.git`（`T:tools/ethornell/CI.md:31-39,78`、`T:tools/ethornell/verify_provenance.py:23,119-149`）。**该 `dirty:false` 是 clean 发布产物的严格口径**；当前 `043fef05` 为带 1 个本地补丁的 dirty 构建，只能走 `--local-static`/自动发布路径，严格审计须 clean 源码（见 `T:tools/ethornell/patches/README.md`）。
- 最小构建步骤（提议，未执行）：clean checkout `0cfff1d` → `cargo ndk -t arm64-v8a -P 26 -o <out> build -p ethornell-mobile --release --locked` → `-C link-arg=-Wl,-z,max-page-size=16384`、`common-page-size=16384`、`-z relro`、`-z now`、`--no-undefined` → `llvm-strip --strip-unneeded` → 复制/改名 `libethornell_mobile.so` 为 `libethornell.so` → 生成/核对 provenance → `verify_provenance.py --local-static`。所有路径/工具链必须写进 manifest，不能只写“构建通过”。
- 验收必须核对 `library_bytes` 与真实文件、`source_file_sha256` 与 header/Cargo 文件、`dirty=false`、`source_repository` 批准值；`--local-static` 的 `ok=true` 仍不等于严格来源审计或设备验收（`T:tools/ethornell/CI.md:60-66`）。当前 dirty 本地补丁产物不满足严格 `dirty=false`，只能记录为本地静态/manifest 证据并保持 `release_ready=false`。

### 阶段 P1 — kind 5 / payload / UI

**目标：** 完成四类输入对话框的端到端链路：worker 取 payload、UI 展示/收集、JSON 回答、恰好结算一次；同时修复与上游不一致的 **Sys80:3C Retry 同 id** 去重和 **pending 16** 预算。

**改动文件：**

- `engine/src/main/cpp/ethornell_bridge.cpp`：完成 `requestPayload` JNI（size query 正长度 + copy + 去一个结尾 NUL + 拒绝内嵌 NUL + strict UTF-8 + 64KiB 上限）；`completeRequest` 的 text 分流已在 P0 交付，P1 只复核/测试，不重复造路径；payload `-4`/stale 走非致命路径，不套 `status()`。
- `engine/src/main/java/com/core/ethornell/NativeEthornell.java`：完成 `requestPayload`；`completeRequest(long,long,int,String)` 已在 P0 存在，P1 只补测试。
- `engine/src/main/java/com/core/ethornell/EthornellSession.java`：`Event.payload`、`payloadError`；`Backend.requestPayload`；`poll()` 在 worker 取 payload；payload 上限/失败处理；**kind 2 用 pending + 单个 retryCandidate 区分重复/Retry；kind 5 按 pending 防重复；16 预算 = pending（kind2+kind5）+ 最多 1 个 retryCandidate**。
- `engine/src/main/java/com/core/ethornell/EthornellDialogProtocol.java`（新增）：用 `org.json` 解析 payload、构造 answer；四类 schema；未知 type/畸形 payload 返回 typed failure，不静默 yes；**所有 answer 发送前本地序列化并校验，失败改发 `{"accepted":false}`**。
- `engine/src/main/java/com/core/ethornell/EthornellHostDialog.java`（新增）：engine host 的对话框 UI/IME/insets；不依赖 App Compose。
- `engine/src/main/java/com/core/ethornell/EthornellActivity.java`：kind 5 → `EthornellHostDialog`；`PAUSE_DIALOG`；IME 显示/隐藏；取消/确认；onDestroy 取消；队列串行。
- `engine/src/main/res/values{,-en,-ja}/strings.xml`：对话框标题/字段/按钮三语言。
- `engine/src/main/java/com/core/ethornell/EthornellInputSink.java`：只在需要时调整 cancel/IME 交互；不改变既有键位映射语义。
- `app/ETHORNELL-INTEGRATION.md`：记录 kind5/payload/response 契约与“不开放存档”边界。
- `SPEC.md:145`、`ARCHITECTURE.md:108,115`、`app/src/test/java/com/tyranor/next/core/game/launch/EthornellOperationGateTest.kt:37-41`：仅当 P1 能力声明/门禁语义变化时同步；否则保持 K，不把“ABI 升级”写成“存档开放”。

**关键接口：**

```java
public interface Backend {
    // ... existing ...
    String requestPayload(long handle, long requestId); // worker only
    void respond(long handle, long requestId, int result, String text); // empty text => int
}
```

```java
// Event
final int kind; final long requestId; final int code;
final String text;      // kind 5: type name; kind 1/4: message
final String payload;   // kind 5: full JSON, null if unavailable
final String payloadError; // bounded host diagnostic, not native text
```

**协议实现要求：**

- `input`：1 或 2 个 `EditText`；`fields[i].initial` 预填；`numeric` 仅作为输入法提示，最终过滤由引擎做；`texts` 顺序与 fields 一致。
- `segmented`：`segments` 个输入框；`texts` 每段一项；不自行用 `-` 拼接（上游 `response_from_json` 会处理分段）。
- `select`：`options[]` 列表单选；`index` 从 0 开始；取消发 `{"accepted":false}`。
- `date`：4 个字段 + 月/日；显示时 +1，回答时 -1（上游 zero-based）。
- 取消统一 `{"accepted":false}`；成功统一带 `"accepted":true`。请求 payload 无法解析时可以取消；但**answer 本身必须是合法 JSON**，不能把畸形 answer 当取消（畸形 answer 会 poison）。
- UI 线程只持有 `String` payload；不持有 native handle；回答通过 `session.respondJson(token,id,json)` 入队。

**请求 id / 预算 / JSON 校验（P1 必做）：**

- **Retry 同 id：** kind 2 事件不能按历史 id 永久去重，也不能只按 pending 去重（上游 Retry 时该 id 仍在 native pending）。建议状态机：`pending` 表示“当前未回答的 kind2/kind5”；回答 kind2 后只设置**最多一个** `retryCandidate`（最近已回答的 kind2 id）。**下一次 `poll()` 如果先看到同 id 的 kind2**，视为 Retry：移入 pending、重新展示；否则立即清除 `retryCandidate`。普通确认完成后的下一次 poll 就会清除候选，不会累积历史。若最终完成不再重发，candidate 下一次 poll 清除/在 session close 清理，不需要伪造终态（`E:crates/ethornell-app/src/host_runtime.rs:156-163,855-881`）。
- **16 预算 = `pending`（当前未回答的 kind2+kind5）+ 最多 1 个 `retryCandidate`。** 上游 `host.pending` 仍包含 Retry 的该 id（`E:crates/ethornell-app/src/host_runtime.rs:156-163,869-877`），所以 candidate 存在时必须占 1 个预算，不能排除；但 candidate 最多一个、不是历史列表，普通确认下一次 poll 即清除，不会累积。新请求进入前检查 `pending.size() + (retryCandidate != 0 ? 1 : 0) >= 16`，命中即诊断/停止，不能等上游第 17 个请求才 poison；Retry 移入 pending 后 candidate 清空，总数不变（`E:crates/ethornell-app/src/lib.rs:21685-21686,21706-21707,21743-21744`）。
- **kind 5 不做 Retry 特例：** 上游只对 Sys80:3C 文档化同 id 重发；kind 5 仍按 pending/seen 防重复。若未来上游对 kind 5 也复用 id，再按上游文档扩展。
- **UI 串行仍要保留：** 16 项是宿主未回答请求预算，不等于可同时弹 16 个对话框；一次只展示一个，Retry 必须等前一个对话框关闭。第 17 个未回答请求必须明确诊断/停止，不能静默积压。
- **malformed JSON：** 发送前用协议类构造并解析一次 answer；失败时改发合法 `{"accepted":false}`。绝不发送空字符串（会被 `complete_request` 路由成 int）或半成品 JSON（会 poison）（`E:crates/ethornell-app/src/dialog_protocol.rs:92-167`、`E:crates/ethornell-app/src/host_runtime.rs:834-843`）。

**IME / adjustNothing 细节：**

- Activity 已经 `adjustNothing`（`T:engine/src/main/AndroidManifest.xml:175`），不要改成 `adjustResize`；否则 IME 会改变 Surface 尺寸并触发 `surfaceChanged`/重新 attach。
- 输入对话框使用独立 `Dialog` window，设置 `SOFT_INPUT_ADJUST_RESIZE`（或 `ADJUST_PAN`）+ `ScrollView`；键盘只影响 dialog，不影响 Surface。
- 对话框显示后请求焦点并 `showSoftInput`；dismiss 时 `hideSoftInputFromWindow`；`onPause` 不强制关闭对话框（游戏已暂停），但 `onDestroy` 必须取消。
- 不在 App UI 层做 Ethornell 对话框；engine 不依赖 App 的 `AppAlertDialog`/Miuix 组件（跨层约束）。

**测试：**

- JVM：`EthornellDialogProtocolTest`（四类 request/answer、取消、畸形 JSON、越界 index、date 0-based、UTF-8/emoji、answer 发送前校验、payload >64KiB、非法 UTF-8、内嵌 NUL、segments/options 上限）；`EthornellSessionTest`（kind 5 只取一次 payload、payload null、重复回答、stale id、kind 2 空 text、kind 5 非空 text、未知 kind>5 不 poison、**kind 2 Retry 同 id 两次事件**、**连续 20 次普通 kind2 确认不阻塞新请求**、**15 pending + 1 candidate 后新请求被宿主诊断**、**Retry 重新入队不增加总数**、**Retry 后最终完成不残留 candidate**、**第 17 个请求/pending 16 边界**）。
- 设备：真实 `NativeEthornell` 的 `requestPayload` size query/copy（可用未知 id 验证非致命；真实 dialog 需游戏/夹具）；`EthornellPrivateActivityTest` 验证私有进程/Activity 不回归。
- release probe：15 符号、kind 5 上界；不要求真的弹出对话框（release probe 的 scope 仍是 load/create/poll/destroy）。
- 真实游戏/手动：至少一个会触发 UserB0 输入对话框的样本；记录标题、类型、输入、回答、VM 继续。另需一个会触发 Sys80:3C Retry 的样本/夹具，确认第二次同 id 仍能弹出并最终结算。
- **原创夹具方案（必须可复现）：** 在 `tools/ethornell` 增加/扩展一个原创 BP fixture 生成器，覆盖 `input`（1/2 字段）、`segmented`、`select`、`date` 和 Sys80:3C Retry 同 id；夹具只含自生成 BP/固定资源，不依赖商业游戏。若当前工具无法生成某类 dialog/Retry，必须明确写出“用授权样本，未验证”，不能把某个商业游戏当作唯一证据；商业游戏只作补充验收。

**退出标准：**

- 四类 dialog 在设备上可显示、输入、取消、确认；IME 不改变 Surface/不触发重新 attach。
- kind 5 的 payload 只在 worker 取；UI 无线程违规；重复/过期回答不 FAILED。
- kind 2 仍走 int，kind 5 走 JSON；无“空 text 误路由”。
- **Sys80:3C Retry 同 id 在第一次回答后仍能再次展示；连续 20 次普通 kind2 不被 retryCandidate 阻塞；15 pending + 1 candidate 后新请求在宿主侧被诊断；Retry 重新入队不增加总数；Retry 后最终完成不残留；16 边界有测试；malformed answer 不会从宿主发出；>64KiB/非法 UTF-8/NUL/segments/options 超限有 UI 拒绝路径。**
- 15 符号 + kind 5 + payload 的 JVM/设备/probe 证据齐全。
- 没有存档管理开放。

**不可交付的中间态：**

- 只做 UI 不做 payload（UI 空字段）——不可交付。
- 只做 payload 不做 JSON response（用户输入无法提交）——不可交付。
- 保留 `seenRequests` 永久去重——不可交付；Retry 会挂死 VM。
- 保留 pending 64、把 `retryCandidate` 排除在 16 外/累积普通确认历史、或无限队列——不可交付；与上游“16 含未结算的 Retry id”不一致。
- 发送未校验的 JSON/空字符串——不可交付；可能 poison。
- 把 Activity 改成 `adjustResize` 以“让 IME 可见”——不可交付；会破坏 Surface 生命周期。
- 对未知 type/畸形 payload 直接 `accepted:true` 或默认值——不可交付；违反“不投机回答”。

### 阶段 P2 — 生命周期、输入与存储边界

**目标：** 在新库行为下重新验证 Surface/detach、pause/clock、输入取消、音频焦点、IME 交互；建立 user_root/barrier 的宿主侧测试，但**不开放存档管理**。

**改动文件：**

- `engine/src/main/java/com/core/ethornell/EthornellActivity.java`：`onPause`/`onWindowFocusChanged`/`surfaceDestroyed` 与 dialog/IME 的顺序；`dismissDialogs` 与 `session.close()` 的取消路径。**黑边 release 不新增 Activity viewport 计算**；只做回归验证，若真实回归再另开缺陷。
- `engine/src/main/java/com/core/ethornell/EthornellSession.java`：payload pending 与 detach/close 的交互；pending 16 预算；stale generation/clock 的测试钩子。
- `engine/src/main/java/com/core/ethornell/EthornellInputSink.java`：`releaseAll`/cancel 行为；不改键位映射表。
- `engine/src/main/java/com/core/ethornell/EthornellLifecycle.java`：如需记录 payload/dialog 生命周期边界，只加有界、无敏感文本的日志。
- `app/src/main/java/com/tyranor/next/core/game/save/EthornellSaveCapability.kt`：只增加门禁常量/注释，不置 true。
- `app/src/androidTest/...`：pause/detach/输入取消/IME/Surface 测试；`EthornellPauseDeviceTest` 适配 kind 5 不误判。
- `docs`：更新集成契约和验收记录模板；不重写旧历史。

**关键接口/行为：**

- 越界/黑边：上游 `HostRuntime::input` 对越界 release 已执行 `clear_input`（不是普通 UP）；当前宿主继续发 UP 也可由 engine 转换，**不要**在 Activity 复制 viewport 计算或把它列为 P2 必改。P2 只做回归：用已知 aspect-fit 样本/夹具确认越界 release 不会激活旧内容；若新库行为变化，再另开缺陷。
- pause/detach：pause 清输入、重置 clock；detach 等 ACK；dialog pending 不阻塞 detach（detach 先取消输入，destroy 取消 pending）。不照抄上游示例 JNI 的“失败也释放 window / Activity 等 5s”行为；只在 ACK 后释放，超时由 watchdog 终止隔离进程。
- storage：只做“host 停止后读取 user_root”的 barrier 测试；不新增 live flush API；不把 `pause` 当 barrier。
- 音频：沿用现有 AudioFocus/becoming-noisy；新库音频行为变化只做回归，不改 ABI。

**测试：**

- 设备：pause 三例（fractional clock/CANCEL、独立 pause 原因、detach 新 generation 恢复像素）；Surface 反复 detach/attach/旧 generation 拒绝；16KB 页面；音频焦点/路由；**像素/画面按实际 present/readback 验收，不按 step 次数**（`take_present_due` 可 no-op）；用已知 aspect-fit 样本回归黑边/越界 release，不在 Activity 新增 viewport 计算。
- JVM：Session 在 payload pending 时 detach/close 不挂死；queue 溢出仍按现有策略 forceExit；stale event 不 FAILED；`request_close` 不伪造 dialog 回答；`destroy` cleanup 不等待 kind3。
- 设备/JNI：`last_error` 同线程读取；未知 kind>5 不 poison；payload `-4` 不走 status；像素按 present/readback 而非 step 次数。
- 工具/CI：provenance/ELF/16KB 检查保持。

**退出标准：**

- 新库下 pause/detach/Surface/输入取消均通过；无 phantom UP；无 Surface 提前释放。**当前状态：pause 3/3 通过（多 stage/step 夹具，精确 guest tick/held 不在此夹具范围）；音频/焦点/soak 仍未验证。**
- IME 与 Surface 生命周期互不破坏；dialog 关闭后游戏继续。
- `last_error` TLS、`request_close` 不取消 pending dialog、`destroy` 不等 kind3、`take_present_due` no-op 均有测试/证据。
- user_root 写入/停止/读取的 barrier 测试有证据；但 UI 存档管理仍关闭。
- P2 阶段保持 `available=false`；P3 设备验收后置为 `true`（范围受限）。

**不可交付的中间态：**

- 用 `pause` 代替 barrier 导出存档——禁止。
- 为黑边在 Activity 里复制一套 aspect-fit viewport 计算——禁止；以 engine 为准。
- 因 IME 改 Activity `adjustResize`——禁止。

### 阶段 P3 — 存档管理验收

**目标：** 在独立门禁下验证 native user_root 写、barrier、flock、64MiB、真实游戏 save/read；只有全部通过才允许把 `available` 置 true。

**改动文件：**

- `app/src/main/java/com/tyranor/next/core/game/save/EthornellSaveCapability.kt`：保持**两个 flag**——`DEVICE_SAVE_IO_VERIFIED`（含义包含 native storage contract：barrier/flock/64MiB/真实游戏 save-read）与 `HOST_DISPATCH_PROTOCOL_VERIFIED`（独立）；`available = 两者都 true`。不新增第三个 `NATIVE_STORAGE_CONTRACT_VERIFIED`；P3 前 `DEVICE_SAVE_IO_VERIFIED=false`，**本次 2026-11-02 设备验收后已置 true**。
- `app/src/main/java/com/tyranor/next/core/game/save/GameSaveManager.kt`：只在 `available` 为 true 时显示/启用；错误仍走 typed code。
- `app/src/main/java/com/tyranor/next/core/game/save/EthornellSaveStore.kt`：如设备证据发现 64MiB/临时文件/保留名问题，做最小修复；不把 App 1GiB ZIP 预算当 native 上限。
- `app/src/test/java/com/tyranor/next/core/game/save/EthornellSaveStoreTest.kt`：更新 `gateRemainsDisabledUntilDeviceFixtureAcceptance`；只测 JVM 可测的根/保留名/预算/取消，不承担 native barrier/flock。
- `app/src/test/java/com/tyranor/next/core/game/launch/EthornellOperationGateTest.kt:37-41`：`verifiedDispatchAloneDoesNotOpenSaveManagement` 保持；门禁语义变化时同步。
- `app/src/androidTest/java/com/tyranor/next/core/game/save/EthornellSaveDeviceTest.kt`：真实 user_root 写/读、host 停止后备份、并发 busy、64MiB 边界、reserved 文件；fixture 必须来自 native/真实游戏写读，不是 Java 自造档。
- `SPEC.md:145`、`ARCHITECTURE.md:108,115`：同步“Ethornell 存档仍由 capability 关闭/两 flag”的事实。
- `app/src/main/java/com/tyranor/next/ui/game/GameScreen.kt:1069-1082,2278-2281`：入口可见性跟随 `EthornellSaveCapability.available`，不新增第二套开关。
- `docs/Ethornell测试验收记录.md`：只在 P3 完成后追加新日期/新四元组证据；不改历史段落。
- `app/ETHORNELL-INTEGRATION.md`、`README`/`CHANGELOG`：更新存档边界。

**关键接口/门禁：**

```kotlin
object EthornellSaveCapability {
    // P3 计划原值为 false；2026-11-02 设备验收后置 true，范围仅私有 user-root。
    const val DEVICE_SAVE_IO_VERIFIED = true
    internal const val HOST_DISPATCH_PROTOCOL_VERIFIED = true
    val available get() = DEVICE_SAVE_IO_VERIFIED && HOST_DISPATCH_PROTOCOL_VERIFIED
}
```

**测试：**

- 真实游戏：SAVE → 文件落盘 → 正常退出 → destroy ACK/进程退出 → 重开读回；记录 date/thumbnail/file。证据来自真实游戏或 native user_root fixture，不是 Java 自造档。
- 64MiB：单次原子写上限边界；超限必须是 typed failure，旧文件保留；不能产生半文件。**该测试属于设备/native fixture，JVM 不承担。**
- barrier：最后一次写后排队命令/destroy ACK；`pause` 单独不构成 barrier；导出前后哈希一致。**该测试属于设备/native fixture，JVM 不承担。**
- flock/reserved：App 备份不触碰 `.ethornell-*`；host 存活时存档操作 busy；并发导入/导出不破坏 writer flock。**flock 由 engine 持有，App `EthornellOperationGate` 不是替代。**
- 16KB/API26：至少每种环境跑一次 save/read；没有设备则明确未验证。
- 新增设备测试（建议名）`nativeUserRootBarrierAndAtomic64MiB`、`writerFlockRejectsConcurrentMutation`：证据必须来自 native fixture 或授权游戏的真实 user_root 写读，不来自 Java 自造文件；日志至少记录 `barrier=destroy_ack`、`write_bytes`、`rename/sync` 结果、`flock=busy/reject`、`file_sha256_before/after`。

**退出标准：**

- `DEVICE_SAVE_IO_VERIFIED` 与 `HOST_DISPATCH_PROTOCOL_VERIFIED` 两项均有证据；`available=true` 只在同一候选上两项都成立。
- 真实游戏 save/read 闭环，且不是合成 globalSDC/仅 Java 写文件；`nativeUserRootBarrierAndAtomic64MiB` 与 `writerFlockRejectsConcurrentMutation` 的 PASS/日志字段齐全。
- `EthornellSaveStoreTest#gateOpensAfterDeviceFixtureAcceptance` 已同步为两 flag 断言；P3 前的关闭断言只保留在历史记录中。
- `EthornellOperationGateTest#deviceAcceptanceOpensSaveManagement`（`:37-41`）证明当前门禁按设备验收开放；dispatch 单独仍不构成开启依据。
- App 存档导出不包含资源根、不包含 `.ethornell-*`、不自动导入 `resource/UserData/*`。
- `SPEC.md:145`、`ARCHITECTURE.md:108,115` 与 `GameScreen.kt:1069-1082,2278-2281` 的可见性描述与代码一致。
- 文档明确“Android 原生 user_root 备份，不是 PC 存档互通”。

**不可交付的中间态：**

- 只通过合成 VM/Java ZIP 测试就开放管理——禁止。
- 只验证 `pause` 就认为 flush——禁止。
- 把 App 1GiB ZIP 预算当作 native 64MiB 上限——禁止。
- 打开“整根镜像”或猜测 `save/` 子目录——禁止。

### 阶段 P4 — 构建、测试、文档与发布边界

**目标：** 把 15 符号/kind5/payload 的证据固定到 CI、release probe、文档与发布说明；明确许可/兼容声明边界。

**改动文件：**

- `.github/workflows/ethornell-native.yml`、`android-ci.yml`/`android-beta-release.yml`（如涉及）：保持三条正式路径；15 符号/header hash/provenance 检查；**拒绝 `inputDialogsSupported=false` 的 P0 标记进入 release**；不引入 `continue-on-error`。
- `app/src/androidTest/cpp/ethornell_test_probe.cpp`、`EthornellReleaseInstrumentation.java`、`app/src/releaseProbe/README.md`、`app/src/androidTest/README.md`：14→15、kind5 上界、payload 检查。
- `tools/ethornell/verify_native.py`、`verify_provenance.py`、`README.md`、`CI.md`、测试：header 解析/符号/provenance 期望；不硬编码旧 14。
- `docs/Ethornell-BGI-Android化移植计划方案.md`：只加阅读导航行，不重写历史。
- `docs/Ethornell-BGI-宿主重构计划方案.md`（本文）：P4 时更新“已执行”记录（如实际执行），但不得把未跑写成通过。
- `app/ETHORNELL-INTEGRATION.md`、`README`/`README_EN.md`（若存在）、`CHANGELOG.md`：kind5/payload/存档边界/版本说明。
- `SPEC.md:145`、`ARCHITECTURE.md:108,115`、`app/src/test/java/com/tyranor/next/core/game/launch/EthornellOperationGateTest.kt:37-41`、`app/src/main/java/com/tyranor/next/ui/game/GameScreen.kt:1069-1082,2278-2281`：门禁语义/可见性变化时同步；P4 必须核对“dispatch 单独不开放”。
- `CONTEXT.md`/ADR：只有在术语/架构决策真正落地后才更新；不为计划强建 ADR。

**测试/验收：**

- `./gradlew :app:testDebugUnitTest :engine:testDebugUnitTest --no-daemon`
- `./gradlew :app:lintDebug :engine:lintDebug --no-daemon`
- `python3 tools/check-hardcoded-ui-strings.py`
- `python3 -m unittest discover -s tools/ethornell/tests -v`
- 设备/release probe 按 §8；真实游戏矩阵按 §8.4。
- 具体绑定：`EthornellReleaseInstrumentation` 的 `releaseTargetIdentity`、`rtldNowAbiBuildInfo`、`productionJniCreatePollDestroy` 三项；结果包必须含 `NUMTEST=3`、`FAILURES=0`、`passed=true`。`EthornellDeviceTest#loadAbiBuildInfoAndRejectInvalidTokens` 必须报 `symbols=15`（本轮通过）；`EthornellPauseDeviceTest` 三例已按多 stage/step 模型重写并 **3/3 PASS**（范围限于 pause/Surface，精确 tick/held 不在夹具内）；`EthornellSaveDeviceTest#privateTreeZipRoundTripPreservesMetadataResourcesAndInternalInodes`、`#publicApisStayClosedWithClaimedDispatch` 与 `#publicManagerLifecycleAfterCapabilityAcceptance` 本轮 9/9 通过；tools 至少覆盖 `test_provenance`/`test_tools`（79 tests 通过）。
- 独立审查：逻辑、架构、健壮性、回归、规范五个角度；本文不声称已完成。

**退出标准：**

- CI 对 15 符号/header hash/provenance/ELF 16KB 全绿；release probe `NUMTEST=3`、`FAILURES=0`、`passed=true`，且 `symbols=15`/`kind<=5`。
- `EthornellReleaseInstrumentation` 用非法/未知 id 调 `requestPayload` 返回非致命错误/null；反射检查 `completeRequest(String)` 存在。
- 文档中的 ABI 版本、符号数、能力声明与代码一致；不再出现“14 ABI”作为当前事实。
- 真实游戏/16KB/API26/音频/存档证据按范围记录；未验证项明确列出。
- 许可/第三方 notice/SBOM 独立结论；未满足则不进入公开发布。

**不可交付的中间态：**

- CI 仍按 14 符号通过、文档却声称 15——禁止。
- release probe 只改 README 不改代码——禁止。
- 把“构建通过/探针通过”写成“游戏兼容通过”——禁止。
- 许可未决却发布——禁止（沿用旧计划 §10.3 的独立阻塞）。

### 6.5 阶段依赖与不可交付中间态汇总

| 阶段 | 依赖 | 首个可交付点 | 明确不可交付 |
| --- | --- | --- | --- |
| P0 | 上游 `0cfff1d` 构建 | 内部候选/CI 证据 | 新库+旧桥、把旧库+新桥当发布版、无 dialog 的用户版 |
| P1 | P0 | 首个支持四类输入对话框的用户候选 | 空 payload/无 JSON/默认回答、Retry 永久去重、pending 64、malformed JSON |
| P2 | P1 | 生命周期/输入/存储边界通过 | 用 pause 当 barrier、Activity 复制 viewport |
| P3 | P2 | 存档管理可开放的候选 | 合成测试/仅 Java 写档/open 整根 |
| P4 | P3 或明确不开放存档 | 发布候选 | 许可未决、14 符号 CI、未验证却宣称兼容 |

### 6.6 P0–P4 实施/验证状态（2026-11-02）

下表覆盖 §6.1–§6.5 的计划口径；计划中的“待执行”不因本节自动变成通过，未列证据仍按未验证处理。

| 阶段 | 实现状态 | 已验证 | 剩余/未验证 |
| --- | --- | --- | --- |
| **P0 ABI/产物** | **已实施** | 15 个导出（含 `request_payload`）、ABI=1、header 同步；provenance 记录 `dirty=true` + 1 个本地补丁；`--local-static`、debug/release APK exact native、zipalign（build-tools 36.0.0）通过；`EthornellDeviceTest` 的 15 符号/ABI/build_info 通过 | 严格来源审计因 dirty 未通过，`release_ready=false`；旧 14 符号回退夹具本轮未见执行证据；不把该兼容路径写成已验证 |
| **P1 kind 5/协议/UI** | **已实施** | kind 5 事件、owner 线程 payload 两段式、JSON 回答、四类 dialog UI、发送前校验与显式 cancel；JVM `EthornellDialogProtocolTest`/`EthornellSessionTest` 在当前 app 910/engine 583 回归中通过；Release probe 覆盖 kind<=5、stale token、unknown id 与显式 cancel | 真实商业游戏/真实夹具的四类 dialog、IME/insets、Sys80:3C Retry 同 id 的设备时序未验证；不得写成“完整输入对话框兼容” |
| **P2 生命周期/输入/存储** | **PARTIAL** | Surface 像素/detach-reattach/旧 generation、脚本 Closed、合成 VM 全局 user-data write/read/empty negative 通过；`EthornellDeviceTest` 6/6 | **`EthornellPauseDeviceTest` 3/3 通过**（已按多 stage/step 模型重写；夹具覆盖暂停/恢复与 Surface，精确 guest tick/held 序列不再断言，输入映射由 JVM 覆盖）。音频路由、Activity onPause/焦点集成、小时级 soak 未重跑 |
| **P3 存档管理** | **已实施（范围受限）** | `EthornellSaveDeviceTest` 9/9（首轮 8/9 为残留任务占用门禁的测试隔离问题，`pm clear` 后 9/9，2.047s）；`DEVICE_SAVE_IO_VERIFIED=true`、`HOST_DISPATCH_PROTOCOL_VERIFIED=true`、`available=true`；native global user-data 设备证据通过；Amairo 私有 Activity 30s/45s 含语言点击存活（无 `failed_detail`），生成 `BGI.gdb`（约 123,802 B） | 仅覆盖 App 私有 user-root 的备份/恢复/删除、64 MiB 单文件与启动期/全局数据写入；不含商业游戏内槽位保存/重启读回、槽位索引/缩略图、PC 存档互通、资源根 `UserData` 自动导入；新夹具 `UserData` 可能为空；`Sys80:34`/`Sys80:30/31/35` 等读取 API 未验证；16KB 未重跑 |
| **P4 构建/测试/文档/发布** | **已实施（发布仍 blocked）** | Debug + AndroidTest 构建成功；app 910/engine 583（各 0 fail，代码未变时的既有运行）；`checkHardcodedUiStrings`、tools 79、`file_policy` Rust 14/14、Release R8 probe SJIS/GBK 各 3/3 通过；集成/验收/架构文档已回填 | `lintDebug` 已执行但失败：engine 既有 SDL HID 代码 54 errors（非 Ethornell 改动）；CI 全绿未报告；真实游戏矩阵、16KB/API26/音频/字体/电影/长稳未闭环；许可/notice/SBOM 与公开发布仍独立 blocked |

**阶段结论：** P0 已闭环（`043fef05` 为带 1 个本地补丁的 dirty 本地静态产物，严格来源审计未通过）；P1 代码与 JVM/探针契约已闭环，真实 dialog 未验收；P2 仍为 **PARTIAL**，pause/Surface 已 3/3 通过，音频路由/Activity 焦点/soak 未跑；P3 已按受限范围开放；P4 证据已固定但公开发布未通过。

## 7. 文件级影响清单

图例：**K** = 保留（不改或只读）；**R** = 改造；**A** = 新增；**D** = 删除。本文不计划删除任何生产文件（D=0）。

### 7.1 engine

| 文件 | 动作 | 说明 |
| --- | --- | --- |
| `engine/src/main/cpp/ethornell_android.h` | R | 替换为上游 `0cfff1d` 原始字节；ABI 仍 1；kind5/response/payload 注释 |
| `engine/src/main/cpp/ethornell_bridge.cpp` | R | 15 符号（正式）/可选 dlsym 能力位；payload 正长度两段式；kind5 接受；response text；stale 非致命；异常边界 |
| `engine/src/main/java/com/core/ethornell/NativeEthornell.java` | R | 增加 `requestPayload`、`completeRequest(...,String)` |
| `engine/src/main/java/com/core/ethornell/EthornellSession.java` | R | Event payload；Backend 接口；worker 取 payload；**pending 16（含最多 1 个 retryCandidate）；kind2 Retry 同 id 可重入**；错误策略 |
| `engine/src/main/java/com/core/ethornell/EthornellActivity.java` | R | kind5 对话框；IME；取消/关闭；输入 cancel；生命周期顺序 |
| `engine/src/main/java/com/core/ethornell/EthornellDialogProtocol.java` | A | 四类 request/answer JSON；typed failure；org.json；JVM 可测 |
| `engine/src/main/java/com/core/ethornell/EthornellHostDialog.java` | A | engine host 对话框/IME/insets；不依赖 App Compose |
| `engine/src/main/java/com/core/ethornell/EthornellInputSink.java` | R | 仅在 cancel/IME 交互需要时调整；键位映射保持 |
| `engine/src/main/java/com/core/ethornell/EthornellLifecycle.java` | K | 现有 closed/destroy ACK 日志；如需 payload 边界只加有界日志 |
| `engine/src/main/java/com/core/ethornell/EthornellDispatchRegistry.java` | K | dispatch 协议不变 |
| `engine/src/main/java/com/core/ethornell/EthornellPaths.java` | K | identity user_root 规则不变 |
| `engine/src/main/AndroidManifest.xml` | K | 已 `:ethornell`、`adjustNothing`；不改为 resize |
| `engine/src/main/res/values{,-en,-ja}/strings.xml` | A/R | 对话框标题/字段/按钮三语言 |
| `engine/src/main/cpp/CMakeLists.txt` | K | 仍只编 `ethornell_bridge`；不新增 native target |
| `engine/build.gradle` | K | arm64/API26/NDK 不变；如新增测试依赖仅 testImplementation |
| `engine/src/main/jniLibs/arm64-v8a/libethornell.so` | R | 新库；16KB/RELRO/NO-UNDEFINED；strip 后入库 |
| `engine/src/main/jniLibs/arm64-v8a/libethornell.provenance.json` | R | source_revision=`0cfff1d...`；library_sha256/bytes/header inventory 一致 |
| `engine/consumer-rules.pro` | K | 如 R8 规则覆盖新 JNI 方法，P4 再核对；不预先放宽 |

### 7.2 app

| 文件 | 动作 | 说明 |
| --- | --- | --- |
| `app/src/main/java/com/tyranor/next/core/game/save/EthornellSaveCapability.kt` | R | P3 才拆门禁/置 true；P0–P2 保持 false |
| `app/src/main/java/com/tyranor/next/core/game/save/GameSaveManager.kt` | R | 只在门禁通过后启用；错误码不变 |
| `app/src/main/java/com/tyranor/next/core/game/save/EthornellSaveStore.kt` | K/R | 默认 K；设备证据发现 64MiB/reserved/barrier 问题时最小修 |
| `app/src/main/java/com/tyranor/next/core/game/launch/EthornellOperationGate.kt` | K | 主进程 gate 不变；不冒充 native flock |
| `app/src/main/java/com/tyranor/next/core/game/launch/EngineLauncher.kt` | K | 启动/dispatch 不变；只消费既有 LaunchResult |
| `app/src/main/java/com/tyranor/next/core/game/model/EthornellPaths.kt` | K | user_root 派生不变 |
| `app/src/main/java/com/tyranor/next/ui/...` | K | 不在 App UI 做 kind5 对话框；不改页面规范 |
| `app/src/main/res/values{,-en,-ja}/strings.xml` | K/R | 如需新的错误/能力文案才加；不重复 engine 文案 |

### 7.3 tests / tools

| 文件 | 动作 | 说明 |
| --- | --- | --- |
| `engine/src/test/java/com/core/ethornell/EthornellSessionTest.java` | R | kind5/payload/response text/stale/重复/**Retry 同 id/20 次普通确认/15+1 预算/pending 16** |
| `engine/src/test/java/com/core/ethornell/EthornellDialogProtocolTest.java` | A | 四类 JSON、取消、畸形、date 0-based、UTF-8 |
| `engine/src/test/java/com/core/ethornell/EthornellInputSinkTest.java` | K/R | 仅在 cancel 语义变更时改 |
| `app/src/androidTest/cpp/ethornell_test_probe.cpp` | R | 15 符号；`symbols=15`；kind5 不误判 |
| `app/src/androidTest/java/com/core/ethornell/EthornellDeviceTest.java` | R | 15 符号/ABI/build_info；kind5 payload 非致命分支 |
| `app/src/androidTest/java/com/core/ethornell/EthornellPauseDeviceTest.java` | R | 新库 pause/detach/input cancel 回归 |
| `app/src/androidTest/java/com/core/ethornell/EthornellDispatchDeviceTest.java` | K | dispatch 协议不变；只重跑 |
| `app/src/androidTest/java/com/core/ethornell/EthornellPrivateActivityTest.java` | K/R | 私有 Activity 不回归；如需 kind5 入口再加 |
| `app/src/androidTest/java/com/tyranor/next/core/game/save/EthornellSaveDeviceTest.kt` | R | P3 barrier/64MiB/flock/真实 save |
| `app/src/test/java/com/tyranor/next/core/game/save/EthornellSaveStoreTest.kt` | R | 门禁测试更新；新增边界 |
| `app/src/releaseProbe/java/com/tyranor/next/releaseprobe/EthornellReleaseInstrumentation.java` | R | 15 symbols；kind 1..5 |
| `app/src/releaseProbe/README.md`、`app/src/androidTest/README.md` | R | 14→15、kind5、scope 不变 |
| `tools/ethornell/verify_native.py` | K/R | 从 header 自动解析；若测试固定 14 则更新 |
| `tools/ethornell/verify_provenance.py` | K/R | 新 header hash/source inventory；不硬编码旧 revision |
| `tools/ethornell/tests/*` | R | 15 符号/header/hash/provenance 期望 |
| `tools/ethornell/README.md`、`CI.md` | R | 命令/边界/15 符号/两 flag 存档门禁 |
| `tools/ethornell/generate_dialog_fixture.py`（新增或扩展 `generate_fixture.py`） | A/R | 原创 UserB0 四类 dialog + Sys80:3C Retry 同 id 夹具；无法生成时明确“授权样本/未验证” |
| `tools/ethornell/legacy_stub.py`（或等效） | A | 生成/校验 14 符号 test-only stub，或校验保留的旧 `libethornell.so` 字节；只用于 legacy 能力测试，不进入 APK |
| `.github/workflows/ethornell-native.yml` | K/R | 三路径检查不变；报告字段/15 符号/拒绝 P0 release 如需更新 |

### 7.4 docs

| 文件 | 动作 | 说明 |
| --- | --- | --- |
| `docs/Ethornell-BGI-宿主重构计划方案.md` | A | 本文 |
| `docs/Ethornell-BGI-Android化移植计划方案.md` | R | 仅“阅读导航”加一行；不改历史/验收 |
| `docs/Ethornell测试验收记录.md` | K | 不改历史；P3/P4 只追加新日期/四元组证据 |
| `app/ETHORNELL-INTEGRATION.md` | R | kind5/payload/response/存档边界 |
| `README.md`、`README_EN.md`（若存在）、`CHANGELOG.md` | R | P4 记录能力/版本/边界 |
| `CONTEXT.md` | K/R | 只有术语真正变化才改；本文不新增未落地术语 |
| `docs/adr/*` | K | 不为计划强行新建 ADR；满足三门槛再议 |

## 8. 验收矩阵

### 8.1 分层验收

| 层级 | 检查点 | 通过标准 | 当前状态（2026-11-02） |
| --- | --- | --- | --- |
| JVM（不加载 .so） | Session/Protocol 状态机 | `EthornellSessionTest` kind5/payload/response/stale/Retry 同 id/20 次普通确认/15+1 预算/pending 16；`EthornellDialogProtocolTest` 四类 JSON/发送前校验 | **通过**：当前工作区 app 910 tests、engine 583 tests，各 0 fail |
| 设备 ABI | `dlopen(RTLD_NOW)`、15 symbols、ABI=1、结构体尺寸/offset、build_info size query | probe 返回 OK；symbols=15；无 FAIL | **通过**：`EthornellDeviceTest` 6/6；ABI/supplementary 另行 2/2 |
| 设备 kind5 | payload size query/copy（正长度/`-4`）、四类 dialog、IME、取消、Retry 同 id、第 17 个未回答请求/16 预算 | 输入可提交；VM 继续；无 FAILED；Surface 不重挂；Retry 可再次展示；20 次普通确认不误堵 | **PARTIAL**：JVM 协议、release probe kind5 契约通过；真实四类 dialog/IME/Retry 设备时序未验证（Amairo 未到 dialog） |
| release probe | R8 release 目标 APK、15 符号、create/poll/destroy | 3/3（按现有 scope）；kind<=5 | **通过**：SJIS/GBK 各 3/3，`FAILURES=0` |
| 真实游戏 | 标题/对白/选择/输入 dialog/Sys80:3C Retry/退出/重开 | 记录样本/编码/设备；不以外推代替 | **未验证**：Amairo 仅验证语言点击后宿主存活（30s/45s，无 `failed_detail`）与启动期全局数据写入（`BGI.gdb`，约 123,802 B；`UserData` 可能为空）；标题/对白/退出未验证，历史记录只对应旧库，不适用于新库 |
| 16KB | `PAGE_SIZE=16384`、15 符号 load、Surface/像素/payload | 与 4KB 同范围通过 | **本轮未重跑**：旧 16KB 证据不能自动外推到新库 |
| API26 | load/create/thread/基础 ABI | 至少 load/create/thread；GPU 记录实际 adapter | **本轮未重跑**：历史 GPU adapter 缺口仍在，未宣称通过 |
| 音频 | focus/route/becoming-noisy、长音/BGM | 无静音假成功；路由变化正确暂停/恢复 | **本轮未重跑**：无音频路由/长稳证据 |
| 存档 | barrier/64MiB/flock/reserved/真实 save/read | P3 门禁全过；`available=true` 仅此候选 | **通过（范围受限）**：`EthornellSaveDeviceTest` 9/9；两个 flag true；仅私有 user-root，不含商业槽位 |
| 文档/工具 | 14→15、provenance/header/hash、README | 无残留 14 断言；CI 全绿 | **PARTIAL**：15 符号/README/工具（79 tests）与 strings 通过；CI 全绿未报告；`lintDebug` 执行失败于既有 SDL HID 54 errors |

**边界/不变量验收（并入上表，不另立“已通过”）：**

| 检查点 | 层级 | 通过标准 | 当前状态 |
| --- | --- | --- | --- |
| payload >64KiB、非法 UTF-8、内嵌 NUL、segments/options 超限 | JVM protocol + 设备 bridge | 拒绝/可取消，不截断、不 poison、不发送半成品 | **PARTIAL（JVM）**：协议测试覆盖；设备 bridge 未验证 |
| 未知 event kind >5 | JVM Session / 设备 probe | 有界记录 + 丢弃/诊断，不 poison；不得当 kind5 | **PARTIAL（JVM）**：Session 契约测试；设备未验证 |
| `last_error` owner-thread TLS | 设备 JNI | 错误后同线程可读；跨线程不借用；payload `-4` 不走 status | **未验证** |
| `request_close` + pending dialog | JVM Session / 设备 | 不伪造回答、不取消 pending dialog；destroy 才取消 | **PARTIAL（JVM）**：Session 测试；真实 dialog/设备未验证 |
| `destroy` 不产生 kind3 | JVM Session / 设备 | cleanup 不等 kind3；destroy ACK 即完成 | **PARTIAL（JVM + 设备 Closed）**：脚本 Closed/退出重入有证据；pending dialog 场景未验证 |
| `take_present_due` no-op | 设备像素/readback | step 次数不作为呈现证据；实际 present/readback 才计数 | **未验证**：pause 夹具失败正暴露 step 假设不可靠 |
| 旧库 14 符号 stub | 设备/probe | `payloadSymbolAvailable=false`、`inputDialogsSupported=false`；kind5 不成功 | **未验证**：本轮未执行旧库夹具 |

### 8.2 当前不得宣称的范围

即使 §8.1 已有通过项，仍不得写：

- “`EthornellPauseDeviceTest` 已通过”仅指多 stage/step 的 pause/Surface 范围；不要外推精确 guest tick/held 或音频路由。
- “真实商业游戏已完整兼容/商业存档槽位可用。”Amairo 仅验证语言点击后宿主存活（30s/45s，无 `failed_detail`）与启动期全局数据写入（`BGI.gdb`，约 123,802 B；新夹具 `UserData` 可能为空）；商业槽位保存/重启读回、标题/对白与真实输入 dialog 未验证。
- “输入对话框已支持全部游戏/完整 IME。”kind 5 只提供 dialog 文本输入；`build_info`/初始事件仍声明 full IME、电影音频、BMV/WMV/MP4、完整 SWF 不支持。
- “16KB/API26/音频路由/多 GPU/长稳已通过。”本轮未重跑。
- “存档管理已支持 PC 存档互通/整根镜像/资源根 UserData 自动导入。”当前仅 App 私有 user-root 备份/恢复/删除；本次补丁只修启动期/全局数据写入（双重 `UserData` 与顶层 `BGI.gdb`/`BGI####.cad`），读取/存在性 API 未验。
- “Release probe 通过 = 游戏兼容通过。”
- “旧候选的 14 符号证据或旧库标题/正文证据适用于新库。”

### 8.3 可执行检查点（本轮已执行/未执行）

```bash
# JVM 回归
./gradlew :app:testDebugUnitTest :engine:testDebugUnitTest --no-daemon

# Android lint
./gradlew :app:lintDebug :engine:lintDebug --no-daemon

# 三语言/硬编码文案
python3 tools/check-hardcoded-ui-strings.py

# 工具与 provenance（先按本机 SDK/NDK 替换）
python3 -m unittest discover -s tools/ethornell/tests -v
python3 tools/ethornell/verify_provenance.py \
  --local-static \
  --ndk "$ANDROID_NDK_HOME" \
  --host-tag darwin-x86_64 \
  --report ethornell-local-static.json

# 设备 debug 构建（隔离包名；不要覆盖正式安装）
TYRANOR_APPLICATION_ID=com.tyranor.next.ethornelltest ./gradlew \
  :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon

# release probe（纯 Java；按 releaseProbe/README 执行）
TYRANOR_APPLICATION_ID=com.tyranor.next.ethornelltest ./gradlew \
  -PethornellReleaseProbe=true :app:assembleRelease :app:assembleReleaseAndroidTest --no-daemon

# 设备/模拟器命令使用实际 serial；以下为占位，不得照抄为已执行证据
# adb -s '<serial>' shell getconf PAGE_SIZE
# adb -s '<serial>' shell am instrument -w -r ...
```

**本轮执行记录：** Debug/AndroidTest 构建、JVM 回归（app 910、engine 583，代码未变时的既有运行）、`checkHardcodedUiStrings`、tools 79、`file_policy` Rust 14/14、`--local-static`（正式库 + debug/release APK exact native）/zipalign（build-tools 36.0.0）、`EthornellDeviceTest` 6/6（1.426s）、ABI/supplementary 2/2、`EthornellPauseDeviceTest` 3/3（2.999s）、`EthornellSaveDeviceTest` 9/9（`pm clear` 后，2.047s；首轮 8/9 为残留任务占用门禁的测试隔离问题）、Release R8 probe SJIS/GBK 各 3/3 已执行；Amairo 私有 Activity 30s/45s 含语言点击存活（无 `failed_detail`），私有 `BGI.gdb`（约 123,802 B）已记录。**已执行但失败：** `:app:lintDebug :engine:lintDebug`（engine 既有 SDL HID 54 errors，非 Ethornell）；严格来源审计（dirty 本地补丁，`release_ready=false`）。**未报告/未通过：** CI 全绿、真实标题/对白/商业槽位、16KB 重跑。设备 serial、SDK 路径、NDK host tag 仍须按本机实际替换；CI 使用 `linux-x86_64`，macOS 本地使用 `darwin-x86_64`，不能混写。

### 8.4 证据记录模板

| 日期 | App commit | library SHA-256 | header SHA-256 | source revision | 范围 | 结果 | 日志/备注 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 待执行（历史） | `3ad9d595` | `09fef3bc...`（旧库） | `14765ba1...`（旧 header） | `7ee2067...` | 基线复核 | 仅静态阅读 | 计划阶段，未跑 |
| 2026-11-02（历史） | `a81543a7` + 未提交工作区 | `0401e38d3ab2a30ed0f57020688f7d00f10332aa3e6baae9ebedee604e86af3c`（27,224,984 B） | `566f475c02d408816520dda814f2171cf52aacc1f39a5a53482b55015202fa57` | `0cfff1d274ecc4554d0af8af1c7b1c7908595e96`（dirty=false） | `0401` 历史快照：P0/P3/P4、JVM/probe 契约；pause 以旧验收节记录为准 | **部分通过** | 已被后续本地补丁产物（当前 `043fef05`）取代；不得与当前库混用哈希 |
| 2026-11-02（当前） | 当前未提交工作区 | `043fef05d0485f027cd5b344a93ddfecd52f97e8e7f8a12fe2986dbcab376d3e`（27,259,224 B） | `566f475c02d408816520dda814f2171cf52aacc1f39a5a53482b55015202fa57` | `0cfff1d274ecc4554d0af8af1c7b1c7908595e96`（dirty=true；1 个本地补丁 `77b99d93...`） | `--local-static` + debug/release APK exact native/zipalign、JVM/Rust、device 6/6、pause 3/3、save 9/9、SJIS/GBK probe 3/3；Amairo 语言点击后存活 + `BGI.gdb` 启动写入 | **部分通过** | 严格来源审计未过；标题/对白/商业槽位/读取 API、16KB/API26 未验；上一版 `541bf6` 仅修 `UserData`，点击语言后仍失败 |

任何一行缺四元组或范围说明，都不应被当作验收证据。

## 9. 风险与回滚

| ID | 风险 | 触发/影响 | 缓解 | 回滚/停止条件 |
| --- | --- | --- | --- | --- |
| R01 | ABI 版本不升导致半升级 | 新库+旧桥通过 14 符号检查，kind5 时才 FAILED | 正式发布强制 15 + provenance；运行时可选 dlsym 只用于旧库回滚且 `inputDialogsSupported=false`；禁止只换库 | 恢复旧库+旧 header+旧桥整套；不得只回滚库 |
| R02 | 符号/provenance 不成套 | header 15、库 14 或反向；CI 误绿 | 四元组绑定；header 原始字节 SHA；provenance source inventory | 任一项不符即停止合并/发布 |
| R03 | payload 上限/内存 | 大 JSON/恶意长度导致 OOM/截断 | size query 正长度 + 宿主上限（建议 64KiB，待实测）；超限转 UI 取消；不无限重试 | 限制为“payload 不可用”，不 poison；记录数字 |
| R04 | stale 误杀 | 重复点击/旧 dialog/旧 generation 被当 fatal | 非致命路径；pending/uiPending 双重去重；payload `-4` 不当 transport 失败；测试 stale id | 出现误 FAILED 即停止发布，补测试 |
| R05 | IME/布局 | `adjustResize` 改 Surface 尺寸；dialog 被键盘遮住 | Activity 保持 `adjustNothing`；dialog window 自己 pan/resize；ScrollView | 回退 dialog UI，不改 Activity resize |
| R06 | dialog 死锁 | UI 等 worker payload，worker 等 UI 回答；或 destroy 等未回答 dialog | payload 在 worker 取；UI 只入队回答；destroy 取消 pending；watchdog | 出现卡死按现有 forceExit/diagnostic；修顺序后重跑 |
| R07 | 存档误开放/越界 | ABI 升级即打开管理；pause 当 flush；把私有 user-root 当商业槽位/PC 互通 | 两个 flag 均 true 后才开放；仅 App 私有 user-root 备份/恢复/删除；64 MiB 单文件；资源根 `UserData` 不自动导入；OperationGate busy | 出现越界/误导入立即把 `DEVICE_SAVE_IO_VERIFIED` 置 false；保留用户数据；不自动清理 |
| R08 | 152 提交行为变化 | 旧 GPU/音频/VM/文本证据外推；新库回归 | P0 固定 source；P2/P4 重跑矩阵；逐样本记录 | 回退到旧库整套；新库问题单独阻断 |
| R09 | 许可/发布 | 上游/依赖/资产许可未清 | 旧计划 §10.3 独立阻塞；SBOM/Notice；不公开商业资产 | 不发布；仅本地验证 |
| R10 | 库名/加载 | 改名后 `dlopen` 失败；probe 路径错 | 暂保留 `libethornell.so`；provenance 记录 crate/packaged 名；Q1 单独决策 | 回退命名/路径；不改 ABI |
| R11 | 旧证据漂移 | 旧文档“已通过”被当当前 | 四元组；本文明确历史边界；验收记录追加不覆盖 | 发现混用即作废该证据 |
| R12 | 队列/背压 | dialog/输入洪泛导致 queue 4096/pending 64 溢出；与上游 16 不一致 | pending 对齐 16；UI 串行；payload 上限；第 17 个可诊断；forceExit 可诊断 | 不静默丢边沿；按现有 forceExit |
| R13 | JNI 引用/UTF-8 | GlobalRef/ANativeWindow 泄漏；payload 非 UTF-8 | 现有 owner/ref 规则；strict UTF-8；payload 拷贝后释放；测试 emoji/NUL | 失败即停止；不释放仍被 native 借用的 window |
| R14 | Surface detach | 旧 Surface 提前释放/黑屏 | detachAndWait；generation；0 尺寸 detach；destroy ACK；不照抄示例 5s/失败释放 | 回退 Surface 相关改动；保留旧路径 |
| R15 | Retry 同 id 被永久去重 | `seenRequests` 丢弃第二次 Sys80:3C kind 2；VM 永久等待 | kind 2 用 pending + 单个 retryCandidate；下一次 poll 同 id 先到就重新入队；测试两次事件 | 出现“确认后无反应”即停止；补 Retry 测试 |
| R16 | 请求预算 64 假安全 | 上游第 17 个未结算请求 poison；把普通确认历史累积进 16 会误堵 | 16 = `pending`（kind2+kind5）+ 最多 1 个 `retryCandidate`；retryCandidate 最多一个且占 1 个预算，不累积历史；第 17 个请求在宿主侧先诊断 | 保留 64 或把 retryCandidate 排除在 16 外的候选不得发布 |
| R17 | malformed JSON 杀会话 | 发送半成品/空字符串；空字符串走 int 分支并因 dialog 报错 poison，半成品 JSON 也 poison | 发送前本地序列化+校验；失败发 `{"accepted":false}`；测试 | 一旦出现 malformed 发送路径即阻断 |
| R18 | payload 正长度误判 | 把 size query 的正数当 status 0 或把 -4 当 fatal | 专用 payload 解析；正数=长度；-4 非致命；两段式 | 截断/误 FAILED 即回退 bridge payload 实现 |
| R19 | 把上游 JNI 当稳定 ABI | 直接复用 `com.xmoezzz.ethornell.NativeEngine` 导出/类名；上游改示例 App 即破坏 | 保留 TyranorNext C++ bridge/C ABI；上游 JNI 只作参考（Q2） | 出现跨包 JNI 依赖即回退 |
| R20 | 能力声明夸大 | 把 kind5 文本输入写成 full IME，或把 build_info 的“无 WMV/BMV/MP4/完整 SWF”漏掉 | README/验收声明逐条对照 `build_info` 与初始事件；只声明已验证范围 | 发现夸大即撤下声明 |
| R21 | pause 夹具与调度器假设错位 | 新调度器一个 step 可执行多个 guest stage；旧夹具按“每步一 stage/绝对 tick”判断，曾导致 3 例失败（已按多 stage/step 重建并 3/3 通过） | 保留失败证据；按实际 present/guest stage 重建夹具或改写断言，不在生产引擎中伪造每步一 stage | 自 2026-11-02 起 pause/Surface 已有 3/3 证据；若夹具再次与调度器假设错位，未拿到可信证据前不得写成通过 |

### 9.1 回滚顺序（必须原子）

1. 停止发布候选，保留日志与四元组。
2. 同时恢复旧 `libethornell.so`、旧 `ethornell_android.h`、旧 `.provenance.json`。
3. 恢复 bridge/NativeEthornell/Session/Activity 的 14 符号实现（或回退整个提交集）。
4. 恢复 14 符号 probe/README/releaseProbe；确认 `symbols=14` 可加载。
5. 回滚时把 `EthornellSaveCapability.DEVICE_SAVE_IO_VERIFIED` 置回 false，使 `available=false`（当前正常版本为 true，范围受限）；不做存档迁移/清理。
6. 重跑旧库的静态/设备最小门禁；不要用新库日志冒充回滚后证据。

**禁止：** 只回滚 `libethornell.so` 而保留 15 符号 bridge；只回滚 bridge 而保留新库；只改 README 不改 probe。三种都会制造“看起来回滚、实际半升级”的状态。

## 10. 开放问题/决策待定

| ID | 问题 | 选项 | 建议 | 影响 |
| --- | --- | --- | --- | --- |
| Q1 | 是否改用 `libethornell_mobile.so` 真名 | 保留 `libethornell.so` / 改名 | **暂保留**；provenance 明确 `crate_artifact`/`packaged_name`；改名单独立项 | `dlopen`、probe、CI、文档、APK 路径 |
| Q2 | 是否引入上游 `android_jni.rs` | 复用 / 保留自有桥 | **保留自有桥**；只参考两段式 payload/JSON 路由 | 类名/包名/线程/ref 所有权 |
| Q3 | 是否增加 host capability 查询 | 新导出 / 只用 `dlsym`+`build_info` | **不新增 native 导出**；用可选 `dlsym` + `inputDialogsSupported` 宿主能力位；正式发布仍要求 15 + provenance | ABI 分叉/维护成本 |
| Q4 | 新库源码基线 | 上游 `0cfff1d` / 现有 fork rebase | **以 `0cfff1d` 为准**；如 fork 有补丁，先 rebase 并重验；provenance 的 `source_repository` 仍必须是批准 fork `https://github.com/Weiss-UltimateSavior/ethornell-rs.git`。若 fork 尚无 `0cfff1d`，先同步到 fork，并把 fork 的实际 commit SHA 写入 `source_revision`；不能把 upstream URL 写进 manifest | provenance/可复现性 |
| Q5 | payload 上限 | 16KiB / 64KiB / 无上限 | **提议 64KiB**，以真实 dialog 最大 JSON 测量后定；超限取消，不截断 | 内存/兼容 |
| Q6 | 存档门禁拆分 | 2 个 / 3 个 flag | **2 个**：`DEVICE_SAVE_IO_VERIFIED`（含 native storage contract/barrier/flock/64MiB/真实游戏 save-read）+ `HOST_DISPATCH_PROTOCOL_VERIFIED`；不新增第三个 | UI/测试/发布 |
| Q7 | 对话框视觉 | engine 原生 / App Miuix | **engine 原生**；不跨层依赖 App Compose | 视觉一致性/架构 |
| Q8 | 是否支持完整 IME | 通用 IME / 仅 dialog 字段 | **仅 dialog 字段**；不宣称 full IME（上游 ABI.md 65-68 与 host_runtime 274/276 仍有保留） | 能力声明 |
| Q9 | stale 的精确处理 | 全部负码 fatal / 仅 stale 非致命 | **按上游语义**：stale/unknown 非致命；真实 runtime 失败 fatal；用测试锁定 | 误杀/漏报 |
| Q10 | 是否复用上游 app UI | 整包 / 参考 | **参考**；不搬 Launcher/GameLibrary/GameActivity | 进程/dispatch/存档边界 |
| Q11 | 是否暴露 backend/font_face_index/flags | 现在 / 以后 | **以后**；当前只保留 encoding/trace；无 UI 不空开关 | 设置/兼容 |
| Q12 | 是否升 ABI 2 | 升 / 不升 | **不升**；按上游 1 加法迁移 | 兼容/分叉 |

**决策顺序建议：** Q1/Q4（产物身份）→ Q2/Q3（传输层）→ Q5/Q9（协议边界）→ Q6/Q7/Q8（UI/存档门禁）→ Q10/Q11/Q12（范围）。

## 11. 证据附录

### 11.1 关键文件与 SHA-256

| 文件 | SHA-256 / 值 | 说明 |
| --- | --- | --- |
| `T:engine/src/main/cpp/ethornell_android.h` | `566f475c02d408816520dda814f2171cf52aacc1f39a5a53482b55015202fa57` | 当前按上游 `0cfff1d` 同步；ABI=1，kind 1–5，`request_payload` |
| `T:engine/src/main/jniLibs/arm64-v8a/libethornell.so` | `043fef05d0485f027cd5b344a93ddfecd52f97e8e7f8a12fe2986dbcab376d3e` | 当前库；27,259,224 字节；15 导出；`0cfff1d` + 1 个本地补丁 |
| `T:engine/src/main/jniLibs/arm64-v8a/libethornell.provenance.json` | `abea90d216a053bf351954e53893d6b56821567c5ab7939caa53e2b36a0fce27` | source_revision `0cfff1d...`；`dirty=true`；`local_patches=1`（`77b99d93...`）；inventory `d30b0774...`；packaged_name `libethornell.so` |
| `T:tools/ethornell/patches/0001-user-data-double-bgi-resource.patch` | `77b99d9392f521a6d9b7313b6e4220e259e0634028fc01c6f74f395c37051138` | 收敛一层重复 `bgi-resource/` 前缀，并把顶层 `BGI.gdb`/`BGI####.cad` 与 `UserData` 子树映射到私有 user-root；不覆盖读取/槽位 |
| `T:engine/src/main/cpp/ethornell_bridge.cpp` | `6854f1b420e6ebe72e0a30f1a6ac634c6c3ca7850b07e8c16d5f5f53275521d8` | 14 基础导出必需 + 可选 `request_payload`；kind 1–5；JSON response |
| `T:engine/src/main/java/com/core/ethornell/EthornellSession.java` | `46f37e59cd32dee44cf8ee8d1b5a9be2f0c6b233d2a280058422445ce77927f0` | payload/pending 16/retryCandidate/kind5 |
| `T:engine/src/main/java/com/core/ethornell/EthornellActivity.java` | `af9e6fef12697885bf55df88e2fd94ec37ae61e9eb3834528386e758e1ef8364` | kind5 对话框接入、暂停/IME 顺序 |
| `T:engine/src/main/java/com/core/ethornell/EthornellDialogProtocol.java` | `d071e2587ebd8c2d42bab54c9a05542e4e0e59b401035f23695e5dee288d0b5e` | 四类 dialog JSON 解析/回答校验 |
| `T:engine/src/main/java/com/core/ethornell/EthornellHostDialog.java` | `e69ab8bfad057ef353b037fe1781a7f565caf85757e29084290f14ea6d814462` | engine 原生对话框 UI/IME |
| `T:app/src/main/java/com/tyranor/next/core/game/save/EthornellSaveCapability.kt` | `87bfadea526e76169698aa62b51eca075ec0aa871c7072eafdc2c22c5c273ee2` | 两个 flag 均 true，`available=true` |
| `T:app/src/androidTest/cpp/ethornell_test_probe.cpp` | `d388214d6fad19eb2b928bad8640830f296c8f9aeaffa2f35a82674482692c97` | probe 要求 15 符号 |
| `T:app/src/releaseProbe/java/com/tyranor/next/releaseprobe/EthornellReleaseInstrumentation.java` | `98383fc1ed8192db5b61d55ea4736d4f56da8b45e8b6009b34b36f94725b44aa` | release probe 15 符号/kind5 |
| `E:platform/android/include/ethornell_android.h` | `566f475c02d408816520dda814f2171cf52aacc1f39a5a53482b55015202fa57` | 上游 `0cfff1d` 新 header |
| `E:platform/android/include/ABI.md` | `563375fc446df4c65f5fb680eebd99d26c62969af92cdf17011cc155fcec9983` | 上游 `0cfff1d` ABI 文档 |
| `E:platform/android/include/STORAGE.md` | `3101e53919d330bb9db1b90dbd452a48419f4135948235a2db80f8353bd3033e` | 与 `7ee2067` 相同（无 diff） |
| `E:platform/android/include/ethornell_android.h`（旧） | `14765ba11710ea62c7f23b1679514a12eaa81e6ab2be0bb2c41f2625d2dbd2f2` | `git show 7ee2067:...`，历史 |
| `E:platform/android/include/ABI.md`（旧） | `4a2ef41d9ccccfab5fd656edad7303018841a86f1ee93982983146078bb2c9e5` | `git show 7ee2067:...`，历史 |

### 11.2 行号索引（实现后）

> 下列行号是 2026-11-02 实现后的索引，不是待迁移计划项；旧 14 符号/kind1-4 行号只保留在上文历史段落，不再作为当前事实。

| 事实 | 位置 |
| --- | --- |
| 当前正式库 15 导出；bridge 为 14 基础 + 可选第 15 个 `request_payload` | `T:engine/src/main/cpp/ethornell_bridge.cpp:25-34,98-106` |
| 当前 header ABI=1/kind1-5/`request_payload` | `T:engine/src/main/cpp/ethornell_android.h:7,29,35-39,68-70` |
| 当前 bridge 接受 kind 1–5、`requestPayload` 两段式、response text | `T:engine/src/main/cpp/ethornell_bridge.cpp:285-336` |
| 当前 Session worker/Event payload/respond/poll | `T:engine/src/main/java/com/core/ethornell/EthornellSession.java:16-30,50-63,105,225-300` |
| 当前 pending + retryCandidate / 预算 16（非 seenRequests 永久去重） | `T:engine/src/main/java/com/core/ethornell/EthornellSession.java:86-87,105,258-282` |
| 当前 Activity kind5 对话框 + IME/暂停 | `T:engine/src/main/java/com/core/ethornell/EthornellActivity.java:388-447,511-514` |
| 当前私有 Activity/adjustNothing | `T:engine/src/main/AndroidManifest.xml:167-176` |
| 当前存档 `DEVICE_SAVE_IO_VERIFIED=true`/`available=true`（范围受限） | `T:app/src/main/java/com/tyranor/next/core/game/save/EthornellSaveCapability.kt` |
| 当前存档路由 | `T:app/src/main/java/com/tyranor/next/core/game/save/GameSaveManager.kt:63-80` |
| 当前 probe symbols=15 / release kind<=5（实现后行号，非待迁移） | `T:app/src/androidTest/cpp/ethornell_test_probe.cpp:41`、`T:app/src/releaseProbe/java/com/tyranor/next/releaseprobe/EthornellReleaseInstrumentation.java:121,177-179` |
| 上游 ABI=1/kind5/response/payload | `E:platform/android/include/ethornell_android.h:7,29,35-39,68-70` |
| 上游 dialog 协议/回答 | `E:platform/android/include/ABI.md:37-54` |
| 上游 stale/black-bar | `E:platform/android/include/ABI.md:31-35`、`E:crates/ethornell-app/src/host_runtime.rs:558-580` |
| 上游 Sys80:3C Retry 同 id | `E:crates/ethornell-app/src/host_runtime.rs:156-163,855-881,1226-1236` |
| 上游 pending 上限 16 | `E:crates/ethornell-app/src/lib.rs:21685-21686,21706-21707,21743-21744` |
| 上游 malformed JSON poison | `E:crates/ethornell-app/src/dialog_protocol.rs:92-167`、`E:crates/ethornell-app/src/host_runtime.rs:834-843`、`E:crates/ethornell-mobile/src/lib.rs:113-121` |
| 上游示例 App parse failure -> cancel | `E:platform/android/app/src/main/java/com/xmoezzz/ethornell/GameActivity.java:198-205` |
| 上游 storage 契约/无 save API | `E:platform/android/include/STORAGE.md:51-77`、`E:platform/android/include/ABI.md:56-64`、`E:platform/android/include/ethornell_android.h:46-71` |
| 上游 `complete_request` 分流 | `E:crates/ethornell-mobile/src/lib.rs:486-512` |
| 上游 `request_payload` 实现/正长度 | `E:crates/ethornell-mobile/src/lib.rs:259-268,517-537`、`E:crates/ethornell-mobile/src/android_jni.rs:268-297` |
| 上游 JNI payload/dialog | `E:crates/ethornell-mobile/src/android_jni.rs:268-321` |
| 上游 JNI 非稳定 ABI | `E:platform/android/app/src/main/java/com/xmoezzz/ethornell/NativeEngine.java:11-14`、`E:crates/ethornell-mobile/src/android_jni.rs:58-79`、`E:platform/android/README.md:49-50` |
| 上游示例 detach 失败也释放/5s | `E:crates/ethornell-mobile/src/android_jni.rs:148-158`、`E:platform/android/app/src/main/java/com/xmoezzz/ethornell/GameActivity.java:359-374` |
| 上游 build_info/初始事件限制 | `E:crates/ethornell-mobile/src/lib.rs:274-286`、`E:crates/ethornell-app/src/host_runtime.rs:268-278` |
| 上游 app 的 worker/payload | `E:platform/android/app/src/main/java/com/xmoezzz/ethornell/GameActivity.java:88-120,143-146` |
| 上游 app 的对话框/IME 字段 | `E:platform/android/app/src/main/java/com/xmoezzz/ethornell/GameActivity.java:198-303` |
| 上游 build 输出/16KB | `E:platform/android/build.sh:2,44-48,58-61` |

### 11.3 本次核对方式（只读）

- `git rev-parse HEAD`、`git status --short`：TyranorNext `3ad9d595`（除本文档与旧文档阅读导航行外 clean），上游 `0cfff1d`（clean）。
- `git log --oneline 7ee2067..HEAD | wc -l`：152。
- `git log --oneline 7ee2067..HEAD -- <path> | wc -l`：app 106、VM 82、script 42、render 9、audio 4、archive 3、mobile 2、platform/android 1。
- `shasum -a 256`：当前 header/lib/provenance/Session/Activity；上游 header/ABI/STORAGE。
- `git diff 7ee2067..HEAD -- platform/android/include/STORAGE.md`：空；`ABI.md`/header 变化集中在 dialog 协议、`Full IME` 措辞、kind5/response 注释与 `payload` 原型；`host_runtime` 仍列 full IME/媒体不支持。
- 计划阶段为只读；实施后已执行 Debug/JVM、设备 6/6、pause 3/3、save 9/9、release probe SJIS/GBK 各 3/3、`--local-static` 与 exact native/zipalign 检查（见 §6.6/§8）；严格来源审计因 dirty 本地补丁未通过。

### 11.4 仍未验证/不确定的点（实现后）

1. **pause：** `EthornellPauseDeviceTest` 已按多 stage/step 模型重写并 3/3 通过；范围仅 pause/Surface，精确 guest tick/held 序列未在该夹具断言；音频路由/Activity 焦点/soak 仍未验证。
2. **真实商业游戏：** Amairo 样本当前在设备，仅验证私有 Activity 30s/45s 含语言点击存活（无 `failed_detail`）与启动期全局数据写入（`BGI.gdb`，约 123,802 B；新夹具 `UserData` 可能为空）；标题/对白/商业槽位保存/重启读回未验证，历史标题/正文/退出证据只对应旧库。
3. **16KB：** 本轮未重跑模拟器；旧 16KB 证据不能自动外推到新库。
4. **API26/音频/字体/电影/长稳：** 本轮未重跑；API26 GPU adapter 缺口、音频路由、字体/电影能力与小时级 soak 仍无新证据。
5. **真实 dialog 时序：** 四类 dialog 的 IME/insets/横屏、Sys80:3C Retry 同 id 的设备时序、pending 16 的 UI 串行行为未用真实样本验证；JVM/release probe 只覆盖契约。
6. **旧 14 符号回退：** 本轮未执行旧库/test-only stub 夹具；legacy 能力位路径未验证。
7. **last_error TLS / take_present_due：** 未单独做设备证据；pause 夹具失败正说明不能按 step 次数推断 present。
8. **CI/lint/发布：** `lintDebug` 已执行但失败于 engine 既有 SDL HID 54 errors（非 Ethornell）；CI 全绿未报告；许可/notice/SBOM 与公开发布仍独立 blocked。
9. **存档范围：** App 私有 user-root 9/9 已过，但商业游戏内槽位、槽位索引/缩略图、PC 互通与资源根 `UserData` 自动导入都不在已验收范围；本次补丁只修启动期/全局数据写入（双重 `UserData`、`BGI.gdb`、`BGI####.cad`），`Sys80:34`、`Sys80:30/31/35` 与 loose read 未验证。

---

**一句话结论：** 这是一次“ABI 加法 + 宿主协议重构 + 行为回归”的组合升级，不是替换一个 `.so`。P0 已闭环（`043fef05` 为带 1 个本地补丁的 dirty 本地静态产物，严格来源审计未通过）；P1 代码/JVM/探针契约已闭环但真实 dialog 未验收；P2 为 **PARTIAL**（pause/Surface 3/3 通过，音频路由/焦点/soak 未过）；P3 已受限开放；P4 证据已固定，公开发布仍未通过。
