# PR #108 第二轮后端审查报告

## 1. 结论与范围

仅审查后端，确认 **1 项新增 P2**：官方 Artemis 首次连接窗口尚未被消费线程领取时，超时取消仍被报告为待解绑，导致官方建窗返回 `EGL_NO_SURFACE`。已在追加提交 `7f1b87d3` 修复，并经另一名 Agent 独立终审通过；新增三项所有权回归交由 CI。

在实际完成的后端静态范围内，未确认其他新增 P0/P1/P2/P3。此结论不包含前端、设置布局、分析器位置或主题，也不证明所有驱动和设备组合无缺陷。本报告保留第一轮报告，不以第二轮覆盖此前结论或未完成项。

- 日期：2026-10-15（Asia/Singapore）。
- 上游基线：`87615940bbdd379d9ef22f58747a59278a365c15`。
- 本轮起始候选：`54203f73d2f0062bb796053ccc7e061a97ae3e2e`，与 PR head 一致。
- 修复：`7f1b87d38a09dfda2097dcbd9980ac913ac531ca`，直接继承起始候选；本报告单独以 docs 追加。
- 按 `AGENTS.md` 的独立审查要求，以及 `CONTRIBUTING.md` 的代码、Commit、PR 和 Bug 修复条款复核。只有后端修复与本报告进入新提交；不修改 UI、内核二进制或原始上游参考目录，不 force-push。

## 2. 方法、独立性与实际覆盖

|审查者|实际复核范围|结论|
|---|---|---|
|共享执行后端 Agent|Pipeline/Plan/OutputSize/ShaderTargets/ShaderGeometry、PostProcessor/Runtime、MpvHookShader、Lanczos、GpuTimer/Stats 与 native timer；重追三引擎的调用约束|未确认新增问题；随后独立终审本轮三文件窄修|
|官方 Artemis Agent|JNI/类加载、Build ID 门禁、PLT 安装及回滚、创建/查询/绑定/交换/销毁、别名、锁序、线程迁移、producer/consumer、超时和清理|确认本报告 P2；补读一份打包库 Init 的静态反汇编，不代替全部版本的二进制核对|
|配置/贡献/KR2 Agent|生成器和资源校验器、描述器与许可证归属、全局/单游戏迁移和快照、启动协议、KR2 native/Java 门禁及原绘制回退|未确认新增问题|
|主 Agent|独立重追本报告触发链；Siglus Activity/Proxy/FrameGate/SurfaceBinding/Viewport/StageBridge/JNI、固定公开源码的 Surface 替换和 render serial；交叉阅读尺寸预算、GL 保存/恢复、分配及释放路径|确认 P2 与窄修证明；未确认其他新增问题|

各 Agent 只读并返回所读代码的结论，主 Agent 实施修复；终审由非修复作者执行。审查覆盖分工列出的文件和调用链，不声称每位审查者都逐行检查了全部后端。没有审查前端、UI 组件或分析器视觉行为。

`AGENTS.md` 指定的 Android CLI 通用审查技能/入口仍未在当前环境中找到，未执行，也未用静态阅读或 CI 冒充。第一轮报告已记录该限制。本轮没有本地编译、设备操作、新截图或原生库重建。

## 3. 后端审查矩阵

|领域|具体核对|结果与边界|
|---|---|---|
|滤镜链规划|整链原子回退、最多八节点、单一等比倍率、适应尺寸、无执行节点、无效/溢出/纹理预算|未确认新增问题；Lanczos 1X 由 Plan 跳过，不把内部兼容横纵字段当作独立 UI 参数|
|GL 执行|目标生命周期复用、输入/输出反馈禁止、RGBA16F 能力、裁剪与分块读 FBO、目标错误分类、状态恢复、owner 释放及上下文遗弃|第一轮 `d9c785f0` 保留；未确认新的错误分类/所有权缺陷；实际 GPU 故障未注入|
|着色器与缩放|MpvHook 的绑定/保存/最终输出约束、尺寸及动态 Lanczos 支持范围、编译错误与几何可重试错误分离|未确认新增问题；不宣称厂商驱动编译/画质运行通过|
|GPU 数据|查询环、owner context、宿主已有查询避让、非阻塞结果、disjoint、失效样本与1000ms异常过滤|未确认新增问题；没有检查分析器布局或颜色|
|官方 Artemis|初次连接/取消、尺寸限制、JNI 三态、consumer 解绑证明、producer/别名保活、恢复直出、资源关闭与有界清理|确认首次未领取请求的 P2；已领取请求的 Pending 保护和永久驱动故障保留策略未扩大|
|Siglus|Java 主线程 handle 所有权、NativeWindow acquire/release、单帧信用与重复通知、render serial、十字段几何与1:1门禁、generation、同窗口 resize、异步解绑后恢复、暂停保留 VM|未确认新增问题；对固定公开源码读取，未修改它或重建库；不宣称实际 Vulkan/GLES 互操作已复测|
|KR2|已加载 ELF 身份与符号、线程 owner、单 Sprite 门禁、有限整数 UV、借用纹理、失败 output=0 保留原 vertices/matrix/texture、上下文再装配|未确认新增问题；裁剪方向、全部二进制 vtable 与运行时 hook 未完整验证|
|配置与启动|单快照迁移、marker 防旧值复活、显式空链/继承、保留 savedChain、未知链/非法数值完整拒绝、实际版本门禁、外置模块只补缺失 extras|未确认新增问题；未执行跨进程并发或外置插件运行验证|
|贡献与资源|descriptor 字段/模式/范围/重复 ID、canonical 路径与文件存在、shader 来源覆盖及 SHA 校验挂接、CAS/Anime4K 扩展自带菜单/参数/颜色/许可|未确认新增问题；本轮静态阶段未逐字节重验所有上游 Git blob，也未实际删除贡献后构建|

## 4. 唯一新发现：首次未领取连接被误判为 Pending

### 问题原因与可达条件

起始候选 `54203f73` 的 `ArtemisOfficialSurfaceProxy.java:130–137`：第一次连接的 worker 在四秒等待期内仍未领取请求，调用方的 `pendingScreen.getAndSet(null)` 返回非空窗口。该原子结果证明此请求从未执行 worker 的窗口接管或 EGL 连接；调用方释放该引用并抛出超时异常。

`ArtemisOfficialBridge.java:42–52` 将异常转换为 null。随后 `artemis_official_graphics.cpp:166–169` 调用 disconnect：它向同一尚未执行的 worker 再排解绑任务，因 `workerTimedOut` 不等待，得到 Pending，最终返回 `EGL_NO_SURFACE`。此时尚未创建或登记 `SurfaceState`/producer，没有可供后续 swap 轮询恢复的句柄。静态 Init 路径会保存返回值并继续绑定/查询，形成初始化失败或无可呈现表面。

普通线程调度延迟或进程冻结后的调度次序即可到达，不要求永久驱动损坏。因此不属于已明确延期的“驱动永久挂起无法强制释放”。没有声称实机复现退出。

### 修复方式与证明

- `ArtemisOfficialConnectRequest` 把 worker 领取与 caller 取消放在同一个 `AtomicReference.getAndSet(null)` 上；只允许一方取得 Surface 引用。
- Proxy 单独记录第一次连接请求，不以 `texture==null` 或当前 EGL 字段推断。该计次受现有 Bridge 串行连接调用契约保护。
- 只有**第一次请求且取消方实际取回未领取引用**才可确认 Detached；证明在实例锁下写入已完成的 `pendingDisconnect`，Native 通过既有 JNI 三态直接恢复原窗口。
- 迟到 worker 取得 null 后立即结束，不能再创建该窗口；超时清理仍在同一 worker 串行执行。
- 已领取请求与任何重连取消都没有此证明，继续等待 GLES owner 确认；失败消费者仍不复用。被拒绝排队的首次请求也能使用同一未领取证明。
- 不跨线程销毁 EGL、不解除已接管窗口的 Pending 保护、不改变官方内核或 JNI ABI。

### 影响与回归覆盖

仅官方 Artemis Java 消费桥发生代码修改。三个 JVM 用例分别模拟取消先发生且 worker 迟到、worker 已领取后取消、未领取的重连仍不能证明旧输出已解绑；包含重复取消不撤销首个证明。它们验证生产实际使用的原子所有权辅助逻辑与原三态协议，不执行 Android Handler、EGL 或 JNI 集成链。

新增用例由本轮 Android CI 执行。具体 run、精确 head 与成功/失败结果记录在 PR 测试情况及本地索引，不以起始候选的旧 CI 冒充本修复验证。

## 5. 资源、历史和验证边界

本轮没有修改 native hook、Siglus Rust 补丁、打包库、着色器、贡献 JSON、许可证、全局/单游戏配置实现或启动协议。上述内容重新阅读，不产生功能改写。Siglus 固定公开来源仍为 `5f358ef1`，打包库 SHA-256 仍记录为 `d206e7e104dcf794767de120d0264374afed42735de16d121875ec523de02612`；以前的原生 CI 只作为相同库的历史证据，不宣称本轮重建。

本轮只需要 Android CI 的既有文案/资源检查、JVM 回归和 APK 编译。CuNNy2、自研 Artemis 画质接入、暂停用户导入、调试工具及附件继续不进入新代码提交。没有把功能验收写成人工代码审查；提交者此前明确没有进行人工代码审查。

仍未完成或延期的验证：

- Android CLI 指定技能、各厂商 EGL/GL/Vulkan 故障注入、真实 OOM/线程迁移和 Surface 回调竞态。
- 所有打包库的成员布局/vtable/PLT 反汇编逐项对照；本轮只有一处 Init 路径的辅助反汇编。
- KR2 裁剪上下方向与输入坐标、各官方版本及 Siglus 共享软渲染引擎的设备回归。
- 实际移除贡献后的构建、跨进程配置并发、外置模块接收、全部上游 shader 原始字节来源复核。
- 永久驱动挂起不能安全跨线程强制释放。

既有暂缓项范围不变：分析器后台位置、原版 KR2 亦出现的纯色偏色、文本排除和 krkrsdl3。自研 Artemis 画质接入待上游合并。第二轮没有审查这些前端/延期项目，也没有把它们标为修复。

## 6. 审查后的提交策略

本轮仅追加一个 fix 和一个完整后端报告 docs，不修改或合并已发布提交。CI 确认当前 head 通过后，再更新 PR 的第二轮报告与提交清单；本地候选保持与 PR 一致，独立实验工作区仅保留。
