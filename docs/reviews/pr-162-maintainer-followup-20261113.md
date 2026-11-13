## 按 AGENTS.md 的审核结果

审查范围：PR #162 的本轮代码提交 `79edea3924916377a15966d768f04136514d1c91`，基线 `e1f61aa8a36a5d8f44d631b8af55fa85c4b59bad`；43 个文件、+2123/−272。上游基线仍为 `main` / `e04f9a155bab64f4d88de933dc56743c4c8c94ea`。

依据[维护者最新审核](https://github.com/Weiss-UltimateSavior/Tyranor-Next/pull/162#pullrequestreview-5889855322)，并沿用其问题编号。三名独立子 Agent 分别承担逻辑/架构、健壮性、回归/规范审核，再交叉复查其他 Agent 的补丁；主 Agent 复核证据和集成，统一运行本地检查。

### 结论摘要

维护者清单中除第 2 项 SystemNNN 外的 12 项均已整改，本地 187 项 UI JVM、app lint、release/R8 与离线门禁通过；未发现本轮新增的已确认代码阻断项。第 2 项按用户明确要求不处理，不计为已解决。遮罩最终版本、默认外观分类栏像素对照等真机验收按用户最新要求停止，不能据此宣布全部视觉验收完成或代替维护者作合并决定。

本轮同时处理用户录屏中的遮罩闪烁，并按“二层无需更深遮罩”统一协调已有暗度；底栏、图标运动、版本号及 `engine/` 均未改动。

### 已执行验证

- PowerShell 设置 `$env:TYRANOR_APPLICATION_ID='com.tyranor.next.motionv2'` 后运行：`./gradlew.bat :app:assembleRelease :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest --tests 'com.tyranor.next.ui.*' :app:lintDebug --offline --no-daemon --max-workers=4 --console=plain` → `BUILD SUCCESSFUL`。最终测试报告 29 类、187 项，0 失败、0 错误、0 跳过；包含 8 项遮罩协调、6 项退出异常、4 项下拉运动/epoch、3 项快照观察、4 项 pop 所有权回归。
- 同一命令完成 `minifyReleaseWithR8`、`compileReleaseArtProfile` 和 release APK 打包；app lint 0 错误（仍有既有警告）。设备测试仅编译，未把源码编译记成设备通过。
- `python -B tools/test_motion_boundaries.py` → 11/11 通过，包含新容器与 receiver/alias 夹具；`python tools/check-motion-boundaries.py` → 347 个 Kotlin 文件、0 违规。
- `python tools/check-hardcoded-ui-strings.py` → 通过；`git diff --check` → 通过；`git diff --exit-code -- engine app/build.gradle.kts` → 无改动。
- 独立复审重新读取本机实际 Miuix 0.9.2 与 Material3 1.4.0 源码/字节码；M3 DefaultEffects 是 damping=1、stiffness=1600，MotionScheme 在该版本为 internal，因此使用共享 owner 内的固定等价规格，不引入反射。
- 未执行：用户叫停后的任何真机/模拟器验收、最终包安装、默认分类栏像素对照、平板/深浅色完整矩阵、release 性能对照、真实游戏兼容性及本轮全量 JVM。早前中间遮罩版的设备通过不计入最终提交验收。推送后的 CI 以 [PR checks](https://github.com/Weiss-UltimateSavior/Tyranor-Next/pull/162/checks) 的实际新 head 为准。

### 问题清单

| 原编号 | 级别 | 问题 | 建议及最终处置 | 修复证据 |
|---|---|---|---|---|
| 1 | 中，已修复 | Miuix final 字段反射无法作为受支持的动画入口 | 删除反射、ProGuard keep 与失效 profile 规则；库动画与自绘弹簧的归属写清 | [MotionMode.kt:18](https://github.com/Weiss-UltimateSavior/Tyranor-Next/blob/79edea3924916377a15966d768f04136514d1c91/app/src/main/java/com/tyranor/next/ui/common/motion/MotionMode.kt#L18) |
| 3 | 中，已修复 | 引擎分类切换期间画面与命中区域不一致 | 当前页与离开页停稳前同时屏蔽触摸和语义，停稳后恢复 | [EngineScreen.kt:955](https://github.com/Weiss-UltimateSavior/Tyranor-Next/blob/79edea3924916377a15966d768f04136514d1c91/app/src/main/java/com/tyranor/next/ui/engine/EngineScreen.kt#L955) |
| 4 | 中，已修复 | 分帧组合判定订阅进场动画，逐帧重组页面 | 工厂内用 withoutReadObservation 一次读取，新增快照观察回归 | [StagedCompose.kt:38](https://github.com/Weiss-UltimateSavior/Tyranor-Next/blob/79edea3924916377a15966d768f04136514d1c91/app/src/main/java/com/tyranor/next/ui/common/StagedCompose.kt#L38) |
| 5 | 低，已修复 | 普通档预组合全部引擎分页 | 仅增强档预热/缓存分页，普通档只组合当前页，切档重建 pager | [EngineScreen.kt:279](https://github.com/Weiss-UltimateSavior/Tyranor-Next/blob/79edea3924916377a15966d768f04136514d1c91/app/src/main/java/com/tyranor/next/ui/engine/EngineScreen.kt#L279) |
| 6 | 低，已修复 | 下拉静止空转与跨会话复用坐标 | 静止后等待输入；分开手势/会话 reset；几何或选项变动换会话，选择 epoch 防旧任务误关新窗口 | [OverlayDropdownPreference.kt:585](https://github.com/Weiss-UltimateSavior/Tyranor-Next/blob/79edea3924916377a15966d768f04136514d1c91/app/src/main/java/com/tyranor/next/ui/common/OverlayDropdownPreference.kt#L585) |
| 7 | 低，已修复 | 左缘系统返回唤出侧栏缺少版本边界 | README、SPEC、AGENTS 明确 API 34+；较低版本保留原有系统返回与页内右滑入口 | [SPEC.md:31](https://github.com/Weiss-UltimateSavior/Tyranor-Next/blob/79edea3924916377a15966d768f04136514d1c91/SPEC.md#L31) |
| 8 | 低，已修复 | 关闭系统动画后退出仍 snap 到最大翻折 | 零/负时长直接归位 1f，含入场中途退出 | [PageTransitions.kt:430](https://github.com/Weiss-UltimateSavior/Tyranor-Next/blob/79edea3924916377a15966d768f04136514d1c91/app/src/main/java/com/tyranor/next/ui/common/motion/PageTransitions.kt#L430) |
| 9 | 低，已修复 | README 与 CONTEXT 缺少动效入口/术语或描述失效 | 补齐两档、提示胶囊、API 边界，收紧隐藏绘制的适用范围 | [CONTEXT.md:382](https://github.com/Weiss-UltimateSavior/Tyranor-Next/blob/79edea3924916377a15966d768f04136514d1c91/CONTEXT.md#L382) |
| 10 | 低，已修复 | 设备测试固定私有包名，文档运行会静默跳过 | 改为 motion_target_package + 显式 opt-in；缺参、正式包或目标不匹配直接失败，README 给完整命令 | [MotionDeviceAcceptance.kt:9](https://github.com/Weiss-UltimateSavior/Tyranor-Next/blob/79edea3924916377a15966d768f04136514d1c91/app/src/androidTest/java/com/tyranor/next/ui/common/MotionDeviceAcceptance.kt#L9) |
| 11 | 低，已修复 | 更新弹窗两处条目未禁用默认 indication | 两处补 indication = null，保留统一按压反馈 | [SettingsScreen.kt:728 / 750](https://github.com/Weiss-UltimateSavior/Tyranor-Next/blob/79edea3924916377a15966d768f04136514d1c91/app/src/main/java/com/tyranor/next/ui/settings/SettingsScreen.kt#L728-L750) |
| 12 | 低，已修复 | 退出动作异常/取消后未清理触摸标志与 guard | 按钮动作与 dismiss 共用异常 helper；接受后只交付一次，取消继续传播，finally 清理 | [AppAlertDialog.kt:410](https://github.com/Weiss-UltimateSavior/Tyranor-Next/blob/79edea3924916377a15966d768f04136514d1c91/app/src/main/java/com/tyranor/next/ui/common/AppAlertDialog.kt#L410) |
| 13 | 低，已修复 | 公共 Activity 启动 helper 放在游戏域 | 迁到 ui/common/motion 并统一 import；游戏启动结果链路保持原样 | [PageTransitions.kt:206](https://github.com/Weiss-UltimateSavior/Tyranor-Next/blob/79edea3924916377a15966d768f04136514d1c91/app/src/main/java/com/tyranor/next/ui/common/motion/PageTransitions.kt#L206) |

**明确排除：第 2 项 SystemNNN 启动结果。** 用户指定不修此类问题。`GameScreen.kt` 的差异仅移动公共 helper 及调整 imports；SystemNNN 请求、completion 消费、失败提示逻辑未变。本轮没有将该项误标为关闭。

### 重点问题说明

**A.【高，已修实现；真机待验】最上层零 dim 抢占仍显示的下层遮罩。**

修复前父管理弹窗保持 native dim=0.5，子确认框却先把自己的 dim 退到 0，再移除窗口；WMS 选择最上层请求，导致背景全亮后恢复。原版负对照实际屏幕探针从父基线 127 跳至 255，和用户录屏一致；Compose `captureToImage` 看不到 WMS dim，因此不能替代屏幕证据。负对照、录屏与截图为本地诊断资料，不提交用户媒体。

实现集中在 [DialogDimStack.kt:71](https://github.com/Weiss-UltimateSavior/Tyranor-Next/blob/79edea3924916377a15966d768f04136514d1c91/app/src/main/java/com/tyranor/next/ui/common/motion/DialogDimStack.kt#L71) 和 [DialogWindowMotion.kt:79](https://github.com/Weiss-UltimateSavior/Tyranor-Next/blob/79edea3924916377a15966d768f04136514d1c91/app/src/main/java/com/tyranor/next/ui/common/motion/DialogWindowMotion.kt#L79)。每个 Activity 实例、真实 Window 分别登记 raw/full native 与自绘暗度；上层继承下层已有合成暗度，自身进退场控制接力，不额外叠深。用实际合成剩余亮度计算 native/自绘互补，覆盖搜索、较浅裁剪及纯自绘抽屉；不会把继承值写回 raw。

首次绑定直接写有效遮罩，真实 attach 决定顺序；detach 仍保存最新 raw，重挂重新登记。dispose/Activity 销毁幂等清理，撤销 dim flag，避免注销到窗口移除间隙再次抢占。搜索与裁剪不再直接 `setDimAmount(0)` 绕过共享入口。抽屉仅登记自绘来源，保持无原生 dim；普通档采用当前 M3 对应的原始遮罩弹簧，增强档仍使用 cover。单窗口完整暗度与运动 token 保持，自绘进度现在量化到 8 位，不声称浮点逐帧值完全相同。

新增实际屏幕采样设备用例同时检查“不能变亮”和“不能叠深”，另有三层/移除/释放及引擎切页输入用例源码；用户已要求暂不真机验证，最终版本这些用例尚未运行。

**B.【中，已修复】共享状态与取消交付。**

下拉会话按选项和窗口几何换代，手势 reset 保留同会话布局，完整关闭才清坐标；静止后等待输入，不逐帧空转。选择 epoch 既防旧延迟任务关闭新窗口，也允许本次选择即时切普通后完成收尾。异常退出 helper 保证已接受动作只尝试一次，取消原样传播，finally 释放输入状态。相应 JVM 用例已通过，真实输入/窗口行为仍待设备验收。

### 非阻塞建议的处置

- 原始 `AnimatedContent` / `AnimatedVisibility` / `Crossfade` 及 receiver/alias 现纳入门禁；归档仅显式使用已登记 owner spec 时放行，新增夹具已通过。
- baseline profile 删除失效类，ARCHITECTURE 明确历史采集命令、APK 哈希及设备 fingerprint 未完整保留，不补造旧身份；记录后续采集步骤，Android CI 增加 release profile 编译。本地实际编译已成功，编译不证明性能收益。
- 默认分类栏像素对照按用户要求暂不执行；注释收紧为沿用配色和圆角，不把未经本轮验证的像素等价写成已证实事实。
- [PageTransitions.kt:170](https://github.com/Weiss-UltimateSavior/Tyranor-Next/blob/79edea3924916377a15966d768f04136514d1c91/app/src/main/java/com/tyranor/next/ui/common/motion/PageTransitions.kt#L170) 与 AppScreen 将 pop 层绑定启动 token 和实际 Activity 实例；直接打开的二级页不误弹其他层，重建可改绑，新 Intent 使用副本，启动失败精确撤销。现有正常 LIFO 导航有 JVM 覆盖，未扩展为任意非栈顶 Activity 外部关闭的导航重排。

### 已核对无问题的部分

- 共享效果仍由公共 UI owner 实现，未把 Compose 或窗口状态引入 core/engine；整页变换仍走 drawPhaseLayers。
- 普通档原有进退场参数和增强档运动 token 没有用关闭动画替代；仅系统确已关闭动画时直接归位。
- SystemNNN 排除项、最终真机待验和历史 profile 来源缺口均明确记录，没有以旧设备结果或配置存在替代实际执行结果。
