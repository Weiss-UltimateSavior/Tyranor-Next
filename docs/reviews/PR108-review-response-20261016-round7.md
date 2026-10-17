# PR #108 第七轮人工审查响应

## 对象与结论

本轮响应维护者的 [独立审查 6209703482](https://github.com/Weiss-UltimateSavior/Tyranor-Next/pull/108#issuecomment-6209703482)。起始 head 为 `317ecf6ade72d4c7bb969ec164325015d3fd3970`，基线为 `91dab282a3560ee25cb4bee023e80e1f31795da0`。没有待续中断任务；本轮按新人工意见处理，不把上一轮结论当作此次修复验证。

两项 P2 均已实现，并交由非作者独立终审；一项纯 UI 规范 P3 保留待维护者确认，**没有标为已解决**。本轮不修改 AGENTS.md，不 force-push；官方窗口修复、来源门禁及文档按功能普通追加。

## P2：官方 Artemis 原地窗口尺寸不同步

### 原因与触发

旧消费者只在 connect 时缓存屏幕宽高，native 输入映射也只缓存创建时尺寸。相同 NativeWindow/producer/EGLSurface 在多窗口、折叠屏等场景原地变尺寸后，游戏舞台仍正常输出，但滤镜目标、显示 viewport 和触摸换算继续使用旧显示边界。producer 的逻辑尺寸不等于屏幕尺寸，不能通过改变引擎端 querySurface 的语义修复。

### 修复方式

- 输出 EGLSurface 所属的 worker 在每次绘制前 makeCurrent 并查询其实际 EGL_WIDTH / EGL_HEIGHT；滤镜目标和最终 viewport 使用同一组本帧尺寸，舞台输入尺寸与 producer 不变。
- 只有 eglSwapBuffers 成功后，才用一个 volatile long 发布本帧实际显示使用的宽高，同时更新 surface 统计。无效查询或未成功显示不会覆盖此前成功快照；新连接清除旧输出快照。
- public static Bridge.outputSize() 无 synchronized、无 worker 等待。native 输入需要映射时直接读取这组尺寸，不把并发读取结果反序写回缓存；首次成功显示之前使用创建时的成对尺寸，JNI 不可用时仍有原兜底。
- native 创建尺寸改为成对原子值，不混读宽高。直出及无有效舞台的输入不调用新增 getter；DOWN 黑边命中、UP/CANCEL 序列、Surface 所有权与锁序保持。
- 新 getter 已受既有 R8 public static keep 规则保护；JNI 方法查找作为安装门禁，缺方法仍回原路径。

### 覆盖与限制

新增三项生产纯状态辅助逻辑的 JVM 回归：同一输出改尺寸后的等比 viewport、非法尺寸不覆盖成功快照、重连清除旧快照。Java GraphicsOutputSize 与 native 的整数等比/居中公式经静态对照一致。它们不执行 EGL 查询、NativeWindow 原地 resize 或真实四角触摸。

实际 EGL 驱动可能在 swap 时才应用窗口尺寸，新尺寸会在后续一帧的查询中观察到；不宣称零延迟事件通知。未做设备、多窗口/折叠屏、四角触摸、黑边、暂停恢复或真实 JNI 故障测试；本轮仅 CI。JNI 读取故障时的创建尺寸兜底仍有边界，不将其称为全部输入故障恢复。

## P2：Siglus 来源验证未纳入 PR 门禁

### 原因

旧原生 workflow 实现了固定来源构建与产物比对，但仅限定路径 push/手动运行，未成为 Android PR 构建依赖，且漏掉入库二进制路径。普通 Gradle 只能验证提交文件与提交内 manifest 自洽；同时更换两者不能证明库来自固定源码。

### 修复方式

- 原生 workflow 增加 workflow_call；Android CI 的 Debug 打包和版本变化后的 Beta 发布均显式依赖它。PR、main push 与手动 Android CI 均无法越过失败/未完成门禁打包。
- 保留专属 push/手动入口，push 路径增加实际 `engine/src/main/jniLibs/arm64-v8a/libsiglus.so`。不同调用方的并发组独立，避免专属 push、Android CI、Beta 发布互相取消。
- 保留固定源码归档、Git blob baseline tree、Cargo.lock、下游补丁、完整 Rust/NDK、55 项旧 ABI、唯一新增几何 ABI 与 16 KiB ELF 对齐验证。
- 入库路径固定，manifest 不可改指其他文件；强制最终 `ci-rebuilt-patched` 状态，移除已完成过渡的 awaiting-baseline 豁免。实际入库字节必须与已验证的 source-built patched 库一致，不只是 SHA 自报一致。
- 缓存仍以源码/补丁/工具链/ABI 锚点/工具和 workflow 指纹精确命中，不以入库 SHA 作为来源证明；命中后重验报告来源字段、字节、ABI 与 ELF。此次 workflow 指纹改变，首次需完整构建。

### 验证边界

比较对象是入库库与固定来源重建库；Gradle strip 后 APK 的文件 SHA 可以不同，不将其错误等同。既有实际 APK 检查不被删除。来源门禁不运行游戏，不替代 Siglus 及共享 RealLive / AVG32 / UK2 的设备兼容回归。

本轮不更新 Siglus 固定源码、Rust 补丁、工具链或入库库来规避验证。CI 应在当前 head 重建/复核来源，并完成 Android 检查及 APK 构建；最终 run、head 和实际结果在 PR 列明。若冷构建失败，不能把旧缓存成功或旧来源报告标作本次已通过。

CAS 的独立上游生成复核仍属原延期项；此次解决的是 Siglus 必经来源门禁，不宣称解决所有外部资源重建校验。

## P3：无图标跳转条目，待维护者明确确认

维护者指出 AGENTS.md 的功能跳转条目要求左图标，现行明文只允许纯动作隐藏图标；代码理由注释不等同于维护者批准。该规范问题成立，不能仅以编译、功能测试或已加注释宣称合规。

提交者此前明确要求“添加滤镜”和算法分类与同卡片设置项保持无图标左对齐，并要求完整回滚对 AGENTS.md 的修改、仅在实际代码处说明。为了遵守该明确界面要求，本轮不擅自恢复图标，也不通过更换组件名称绕过跳转语义；继续复用 AppNavItem、保留右箭头及局部说明，AGENTS 无净差异。

PR 评论将明确请维护者确认这两类入口的局部无图标例外。**确认前本项仍待裁决，不列为修复或合规通过**。点击即添加的单预设和末级动作不在此次争议内；其他页面不援引例外。没有修改 UI、补拍截图或声称新增视觉验收。

## 独立审查与规范

|角色|实际范围|结论边界|
|---|---|---|
|官方窗口修复作者|Proxy 输出查询、成功显示快照、Bridge/JNI、native 输入与 producer 分离、三项 JVM 用例|只实现本轮原地 resize；不声称设备故障覆盖|
|来源门禁修复作者|workflow_call、Android/Beta needs、push 路径、固定入库与重建比对、精确缓存与并发|不改 Rust 或库；构建结果以当前 CI 为准|
|非作者 GL 复核者|两项实际 diff 的线程/锁序/连接世代、JNI/keep、来源证明与门禁依赖|独立静态终审；不把代码阅读当运行时测试|
|交叉复核与主 Agent|配置/生命周期接口、宽高原子快照、直出短路、共享等比公式、UI 指令与规范冲突、提交/CI/PR对应关系|未解决项明确记录，避免重复定级或扩大修改|

按 CONTRIBUTING.md 的代码、Commit、PR 和 Bug 修复条款执行；AGENTS 的多 Agent 独立审查已分工完成。当前环境仍未找到指定 Android CLI 审查技能，未执行或冒充；维护者报告在其环境执行的技能、Debug/Release 构建和测试属于其独立证据，不冒充本轮自行执行。

本轮不运行本地测试、编译、设备操作或发布任务，仅执行 CI；新 JVM 用例交 CI 运行。上一轮 `317ecf6a` 的成功及历史两小时游玩只作历史证据，不能替代新修复验证。11 张既有截图与提交者说明保留，附件和纯本地工具不进入 Git。

## 保留项

P3 图标例外待维护者确认；进程级着色器失败锁存、CAS 来源生成复核、后台分析器位置、原版 KR2 纯色偏色、文本排除和 krkrsdl3 接入仍延期。未支持 KR2 后续适配计划不变；自研 Artemis 画质接入待上游合并。CuNNy2、自定义导入及调试工具继续排除，独立实验工作区不修改。
