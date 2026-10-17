# PR #108 画质滤镜链自我审查报告

## 1. 结论

本轮确认 **1 项新增 P2**：裁剪与分块路径的读 FBO 名称分配失败绕过目标压力分类，可能把纯 OOM 升级为永久滤镜失败。已在追加提交 `d9c785f0` 修复，并新增四项纯 JVM 错误注入回归。

在下述实际完成的静态范围内，未确认其他新增 P0/P1/P2/P3。此结论不是“所有驱动与设备均无缺陷”的保证；明确未完成的二进制和运行时验证见第 7 节。后续提交按提交者要求只追加，不再改写本报告审查后的历史。

## 2. 基线、范围与顺序

- 审查日期：2026-10-14。
- 上游基线：`87615940bbdd379d9ef22f58747a59278a365c15`。
- 主审候选：`16fb524817f0415f5a2af3e0416224c3062f6c15`，基线到候选净差异为 157 个文件、八项功能提交。
- 新修复：`d9c785f0b34bd7b412572504247b93d39ce175fc`，直接继承上述候选；本报告为后续独立文档提交。
- 按提交者要求，先完成提交整理和 PR 说明更新，再开展本轮自审。整理后的代码、原生库及资源与已验收 `002217b7` 的 Git blob 一致，整理时仅更改验收文档；本次 FBO 修复是自审之后新增的代码变化。
- 对照 `AGENTS.md`、`CONTRIBUTING.md` 的代码/Commit/PR/Bug 修复条款，以及 `CONTEXT.md` 中滤镜贡献、游戏原始帧和尺寸限制舞台输出的术语。

## 3. 审查方式与独立性

采用独立 Agent 交叉审查与主 Agent 复核，避免由原修复作者单独判定自己的修改：

|角色|实际完成范围|
|---|---|
|界面/配置审查 Agent|净差异中的 UI、主题、菜单、参数、继承迁移、启动协议及三层依赖；完成默认和两种玻璃上下文收尾核对|
|引擎桥审查 Agent|官方 Artemis 的 JNI/锁序/待解绑与别名，Siglus 几何/单帧信用/窗口恢复，KR2 拒绝入口及原绘制保留路径|
|GL 审查 Agent|独立确认本报告 P2，并对主 Agent 新增的两文件修复做一次小范围终审，核对分类、释放和恢复次序|
|主 Agent|复核触发链，补读引擎恢复及公共输入路径，检查资源哈希、贡献与提交边界，汇总实际覆盖与未完成项|

代理的长轮次曾因调用额度中止；通过限定范围收尾任务取得上述最终结论，没有把中止时尚未读完的内容直接标为通过。完整报告记录的是明确列出的代码路径和证据，不声称每位 Agent 都独立审完全部文件。

`AGENTS.md` 要求“必须让Agent使用阅读 Android CLI 中的审查技能”。当前已检索可用工具/项目 skills，并检查 Android/Android CLI 命令，未找到该审查技能或入口；因此没有执行，也没有用其他工具冒充。此项是审查能力限制，独立静态复核与 CI 不能等同于该技能。

## 4. 已完成的审查矩阵

|领域|核对内容与主要文件|结果|
|---|---|---|
|三层架构|`core/engine/graphics`、`core/settings`、`ui/settings`、`engine` 的依赖；净差异中 core 无 Compose import，UI 无底层引擎 import|未确认新增违规；静态导入检查结果为零|
|Miuix/主题/玻璃|`AppAlertDialog` 的颜色上下文、`MiuixSettingsTheme` 动态容器/语义色、公共输入/标签/滑块、两种玻璃各自材质|未确认新增主题硬编码；未把静态颜色路径核对当成视觉回归|
|菜单和节点操作|`GraphicsFilterPicker` 的单步添加、单滤镜无二级菜单、16dp 无图标入口豁免；`GraphicsChainEditor` 的键、移除、上下移、拖拽取消和禁用门禁|保留右箭头与节点参数按钮；例外不扩散到侧边栏|
|参数与配置|单一等比倍率、适应模式隐藏倍率输入、有限值/范围验证、全局与单游戏自定义链保留、空链/继承及旧 marker 迁移|未确认新增功能缺陷；内部旧 wire 横纵字段不等于 UI 提供横纵分别设置|
|Android 8 输入|`AppSearchField` API 26/27 兼容分支、`EmbeddedSearchInputField` 的编辑/IME/主题/清除操作；API 28+ 保留原 InputField|无主动展开或自动聚焦路径；实际低版本键盘交互未复测|
|启动协议|版本预览与启动同源门禁、应用 UI Locale、模块已有 extras 的保护、单快照读取|未确认新增错配或覆盖|
|几何与资源预算|`GraphicsFilterPlan`、`GraphicsOutputSize`、`GraphicsShaderTargets`、Pipeline 的八节点、尺寸/溢出/预算、临时与永久失败、原画面回退|确认第 5 节的一项 P2，已追加修复；未确认其他新问题|
|GL/生命周期|目标分配边界、借用纹理不删除、纹理复用/分块、状态捕获恢复、owner context 释放/abandon|新增修复经独立终审；真实 GL 故障仍需要驱动覆盖|
|性能分析|`GraphicsGpuTimer` 和 native timer 的 owner context、查询环、非阻塞读取、disjoint、宿主已有查询避让；Stats 的1000ms异常过滤及缺失值|未确认新增问题；没有 CPU 延迟统计或旧调试数据入口|
|官方 Artemis|JNI `()I` 三态一致、operation→lifecycle→registry 锁序、短注册表锁、pending 丢帧保留 producer、确认解绑后连接 screen、实际版本支持门禁|有限延迟不会由 pending 直接触发官方设备重置；首次未建立有效 producer 的失败不伪造有效 Surface|
|Siglus|十字段几何 ABI/1:1 验证、Surface 引用所有权、generation、单帧信用、异步 detach、未接管的同窗口仅 resize、暂停保留 VM|未确认新增问题；原生库与来源记录未改变|
|KR2|已加载 ELF 身份、单 Sprite/owner gate、整数 UV 矩形、借用纹理、失败 `output=0` 保留原绘制，替换只改纹理和 UV|未确认新的输入坐标修改；不回退到屏幕级增强|
|资源/许可/构建|贡献目录、固定来源与 SHA、Siglus 旧 ABI 锚点/CI 来源、字节保留属性、两个可选贡献完整自带菜单/颜色/资源/许可|24 项本地哈希全部一致；构建/生成器本轮未重跑，ABI/16KiB 运行结果沿用相同库的已有 CI 证据|
|提交边界|八项功能提交及本次追加 fix/report；CuNNy2、自研 Artemis 画质接入、暂停导入、调试包与附件|贡献目录仅 `base.json`、`anime4k-extra.json`、`cas.json`；实验/本地路径未进入本 PR 新增文件|

## 5. 新发现 P2：读 FBO 分配失败被永久锁存

### 原位置和触发条件

审查候选 `16fb5248` 的 `Anime4kPostProcessor.java:815–816`（`normalizeBorrowedTexture`）和 `:902–903`（`runTiledPasses`）。在需要裁剪或分块的帧中，读 FBO 第一次生成名称失败，返回 0，且 GL 错误只有可重试的分配压力，例如 `GL_OUT_OF_MEMORY`。

### 原调用链与影响

1. 两处 `glGenFramebuffers` 后直接调用普通 `requireName`。
2. 零名称抛出 `IllegalStateException`，既未按目标压力分类，也没有先完整消费分配错误。
3. 裁剪路径由 `GraphicsFilterPipeline.renderCroppedTexture` 的 runtime catch 设置 `failed=true`；分块路径经节点 `fail` 设置永久失败，再传递给 Pipeline。
4. `prepareGeometry` 首先检查永久 `failed`，因此后续缩小处理尺寸仍不重试；需要释放或重建 context。

这与已修复的普通纹理/FBO 目标分配协议不一致。本次独立 Agent 先确认，主 Agent 按实际调用链复核；不是由截图或假设驱动得出的结论。

### 追加修复

- `d9c785f0` 的 `Anime4kPostProcessor.java:801`：两条路径共用读 FBO 分配入口。
- `:809`：先完整读取有界错误队列，再检查名称；纯 OOM/可重试附件错误抛 `TargetUnavailableException`，非法调用或无法清空的状态仍为永久失败；非零名称不掩盖错误。
- 裁剪的附件解绑、分块的外层目标释放、状态恢复顺序不变；新名称仍归原 owner，借用的游戏纹理不被删除。

### 新增回归及限制

`Anime4kGlErrorBoundaryTest` 追加四项：零名称与多分配错误不污染恢复/下一次分配；零名称无错误；OOM 混合非法调用仍永久失败；非零名称不掩盖 OOM。纯 JVM 回归验证错误协议，不模拟真实 GPU 内存分配，也不等于实际跑过整个裁剪/分块渲染器。

## 6. 资源与历史证据

静态哈希核对范围：原打包 Anime4K 7 项、Anime4K 扩展及许可证 6 项、CAS 生成资源及许可证 8 项、Siglus 补丁/旧导出锚点/打包库 3 项，共 24 项，均与已提交 manifest 一致。

Siglus 固定公开来源 `5f358ef1`；本轮未编辑参考上游、Rust 补丁或运行库。打包库 SHA-256：`d206e7e104dcf794767de120d0264374afed42735de16d121875ec523de02612`。完整对照/补丁构建、旧 ABI 和16KiB证据见[首次原生 CI](https://github.com/AAAA1111-Lab/Tyranor-Next/actions/runs/38421811796)与[缓存再核验 CI](https://github.com/AAAA1111-Lab/Tyranor-Next/actions/runs/38424292641)，不宣称本轮重新执行原生构建。

此前 Android CI 的明确 head 为 `fec1f976`，[结果链接](https://github.com/AAAA1111-Lab/Tyranor-Next/actions/runs/38569135320)。提交者确认本地编译和入口修复测试通过的结果属于本次 FBO 修复之前的版本；不得当作此追加修复的实机证明。

本轮追加修复的验证范围为 Android CI 中的文案/资源检查、JVM 回归和 APK 编译；具体 run、head、结果由 PR 的“测试情况”记录，本报告不把旧 CI 通过写成新 head 通过。本轮无本地编译、设备测试、新截图或原生库重建。

## 7. 未完成与明确延期

以下未标为通过，也不作为未经证实的新缺陷：

- 未找到指定 Android CLI 审查技能/入口。
- KR2 固定 vtable 长度、官方 Artemis 舞台成员布局与所有打包库反汇编的逐项核对；本轮只对已有固定身份门禁和源代码路径审查。
- 各厂商 EGL/GL 的 pending 时序、线程迁移、Surface callbacks、真实 OOM/上下文丢失与原生故障注入。
- KR2 裁剪上下方向、实机输入坐标，以及 Siglus 共享软渲染引擎的完整设备回归。
- API 26/27 的实际键盘操作；默认及两种玻璃本轮运行时视觉/触摸回归。
- 永久驱动挂起不能安全跨线程强制释放。

既有延期项目保持原范围：后台分析器位置下移、原版 KR2 亦出现的纯色偏色、文本排除及 krkrsdl3 接入。自研 Artemis 画质增强接入仍待上游合并；CuNNy2 和用户自定义导入继续排除。

## 8. 给维护者的结论边界

报告完整记录本轮的范围、方法、唯一已确认发现、追加修复、资源证据和未完成项。静态审查及 CI 不能证明所有运行时组合安全；未知项保留供维护者决定后续覆盖。功能验收由提交者完成，提交者没有对代码进行人工审查；本报告是 AI 代码复核，不能标为人工代码审查通过。
