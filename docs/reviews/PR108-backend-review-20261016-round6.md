# PR #108 第六轮后端复核与上游合并报告

## 本轮结果

2026-10-16 12:11（Asia/Singapore）的定时检查未发现待续中断任务或新的人工意见。起始候选为 `bec5d64bea110b19776b828e6b61a488f2c3c773`，最新人工报告仍为 6197195429，已由上一轮逐项响应。GitHub 实时状态显示合并冲突；独立核对上游 main 已更新至 `91dab282a3560ee25cb4bee023e80e1f31795da0`。

本轮通过普通合并提交 `62c7742da893624d4e91a9ec97c4527b11051474` 纳入新上游，不重写既有候选历史。冲突限于三语 app strings 末尾的双方新增项，以及 CMake 的原生链接目标；均保留双方内容。封面来源、Ethornell 实验接入、原生库更新与新 CI 门禁完整保留。`AGENTS.md` 相对最新上游无净差异，不恢复此前已回滚的规范豁免。

本轮仅审查后端，确认并修复 **一项 CI 交互 P2** 与 **一项官方桥低内存 P3**。两项均经非作者独立静态终审。其他已检查路径未确认新的可达缺陷；不把延期或缺少证据的驱动问题重新定级。

## P2：手动 Android CI 误触严格发布门禁

**问题原因**：PR 为 Android CI 保留手动触发入口，新上游将 Ethornell reusable workflow 设为构建前置门禁。父工作流显式传 `strict-release: false`，但子工作流用调用者的 `workflow_dispatch` 事件强制 strict，覆盖了父输入。当前交付 provenance 的 dirty 标记会被严格发布校验拒绝，导致手动 Android CI 无法进入 APK 编译。

**修复方式**：子工作流的手动与复用入口声明同名布尔输入，默认均为 true；执行逻辑统一读取输入。Android CI 继续显式 false，beta release 继续显式 true；所有 ELF、ABI、资源来源、依赖闭包和产物核验均保留，不跳过新上游门禁。

**影响范围**：仅手动/复用工作流的 strict 输入传递。不把调试 CI 的 local-static 成功写成引擎源码可公开复现或 release-ready；其来源待确认状态保留。独立手动运行默认仍严格，选择 false 时明确仅静态校验，发布调用不改变。

**验证边界**：实际手动 Android CI 将验证父传 false 的路径；两处默认值与 beta release 的 true 调用通过静态交叉检查，未额外触发发布任务。

## P3：官方 Artemis 原生桥的分配异常越过引擎边界

**问题原因**：原 createSurface 在取得 producer EGLSurface 和屏幕引用后才分配 SurfaceState/registry 节点；恢复直出则在 EGL 切换成功后才分配真实句柄的 alias。低内存时 bad_alloc 会越过官方引擎回调，且这些副作用无法由原回退链收口。可选安装的 Build ID 字符串和 hooks/map/vector 元数据亦缺少分配异常边界。

**修复方式**：

- 接触 Java consumer、取得窗口引用或创建 producer EGLSurface 之前，分配 state、初始登记节点和一个直出别名备用节点。此时失败直接保留原直出，没有新窗口需要交还。
- 同一默认 allocator 的 C++17 node handle 只改 key/mapped 后插入；检查 inserted，拒绝时归还节点并清空 mapped。备用节点在 state 内不保留指向 state 的引用，避免共享引用自环。
- consumer 确认解绑后创建直出表面，先登记预分配 alias，再切换 EGL。切换失败将 alias 提回备用节点并清空 mapped；旧 producer 及 state 保留供原路径处理，不把失败当解绑成功。成功后原活句柄别名和释放次序不变。
- 安装的 Build ID 与其余元数据分配分别捕获 bad_alloc。全部元数据准备成功后才创建全局类引用和修改 PLT；失败用现有注册失败协议退出，没有部分 hook 写入。

**影响范围**：仅官方 Artemis 可选画质桥，不改变内核二进制或 JNI ABI。锁序继续为 operation → lifecycle → registry；registry 锁不跨 JNI/EGL。关闭滤镜时仍走原路径。该修复不是整个 loader 或进程的通用 OOM 恢复，也不吞掉任意 C++ 异常、Rust panic 或驱动致命故障。

**验证边界**：独立静态复核预分配前后所有权、失败取回、自环、锁序与安装提交点；CI 编译 C++17/Android 桥及整个 APK。本轮未注入真实 malloc 失败、设备 OOM 或 EGL 驱动故障，不能将编译成功当作这些运行时分支实测通过。

## 独立审查矩阵

|审查者|实际范围|结果|
|---|---|---|
|此前配置作者|固定候选的 Siglus Activity/Proxy/JNI/StageBridge/SurfaceBinding、准备超时与迟到回调、窗口世代及固定 Rust 引用语义；合并后补审官方 OOM 修复和 CI 输入|未确认 Siglus 新问题；独立终审两项修复，未发现确证回归|
|此前 GL 作者|官方 Artemis native/Java 的窗口 claim/cancel、FAILED 首次交付后的 DeviceReset、Pending/alias、锁序、slots 与清理；独立终审新 native 修复|确认低内存 P3，主 Agent 实施；终审通过|
|此前运行时作者|配置/启动请求、迁移快照、候选门禁、描述器/来源校验、合并后的新引擎分支和构建/CI依赖|确认 CI 交互 P2 并实施；其余已检查路径未确认新问题|
|主 Agent|核实人工评论/冲突与最新基线、解决合并；共享 Pipeline/Plan/TargetRetry/Stats 的预算/退避/整链回退/计时；核实两项发现并实施官方桥修复，复审 CI 输入|未确认其他新增问题；不声称逐行复审全部着色器、设备行为或新上游引擎|

审查范围限定后端和合并交互，不复审 UI 布局、主题或分析器视觉。新的 Ethornell 实现按上游保留，只检查与本 PR 的启动、设置和构建交集，不将其整体兼容性标为本轮验收。它收到中性禁用的滤镜请求，没有误接到 Siglus 或既有链；原参数与分区键保留。

遵循 CONTRIBUTING 的代码、Commit、PR 与 Bug 修复格式及 AGENTS 的独立审查要求。指定 Android CLI 审查技能仍不可用，没有执行或冒充。独立终审过程只读；CI 修复由发现者实现，官方桥修复由主 Agent 实现；没有运行本地测试、构建、实机操作或新增截图。

## CI、提交与记录边界

本轮按功能追加上游合并、CI 修复、官方桥修复与本报告；不 force-push。只运行合并后当前 head 的 Android CI，包括新上游的 native gate、文案与 JVM 回归、APK 编译及实际产物闭包校验；最终 head/run/结果在 PR 中列出，CI 通过后才更新 PR 说明和评论。

先前 `bec5d64b` 的 CI 与两小时官方 Artemis 游玩验收仅作为历史证据，不能替代此次合并与新故障分支验证。当前原生 Siglus 补丁/库和滤镜资源未被本轮改写；上游自身更新的其他原生库按上游提交保留。原 11 张 UI/分析器截图及提交者说明保留，附件不进新 Git 提交。

## 延期与未完成

上一轮 P3#9 的进程级着色器失败锁存设计、P3#14 的外部来源重建校验增强仍未完成；本轮不扩大修改范围。后台分析器位置、原版 KR2 亦有的纯色偏色、文本排除和 krkrsdl3 接入继续延期。未支持 KR2 的后续适配计划保留；自研 Artemis 画质接入待上游合并。

厂商 EGL/GL/Vulkan 故障、真实 OOM、全部打包库布局、共享软渲染引擎、API 26/27 键盘与新上游功能均没有新增设备验收。CuNNy2、用户自定义导入、调试打包工具和本地审计索引继续排除。提交者没有人工代码审查，本报告仍是 AI 独立静态复核。
