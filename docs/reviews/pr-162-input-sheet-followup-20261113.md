# PR #162：输入光标柄与父抽屉遮罩补充审核

本轮审查实现提交 [`5a7dfb594759190724aa82ef8efe588c0d794644`](https://github.com/yuukana1/Tyranor-Next/commit/5a7dfb594759190724aa82ef8efe588c0d794644)，相对 `da966622` 共 16 个文件、+737/−36。基线沿用上轮已合入的最新 main；SystemNNN 引擎问题仍按用户要求排除。只验证隔离包，不启动、删除或修改真实游戏。

## 结论摘要

本轮两处用户反馈已修复，相关真机竖屏 26 项、横屏关键 8 项及 JVM 45 项全部通过，无本轮遗留阻断项。输入框仍保留图标反馈；抽屉面板遮罩恢复，底层页面不叠深，接受关闭后无需等待动画结束。其它设备、IME 与完整性能范围仍未复测。

## 问题清单与处理

| No. | 级别 | 问题与证据 | 修复 |
|---|---|---|---|
| 1 | 中 | `AppSearchField` 整个编辑区域聚焦缩放时，粉色水滴选择柄反复消失。原 debug 同机四例中，增强档默认/兼容分支各有 24 个必需采样帧丢柄，焦点、字段 ID、窗口与文本仍保持不变；普通档通过。 | 仅移除整个编辑区域的聚焦缩放，保留前导图标回弹；两输入分支统一修复。 |
| 2 | 中 | 父抽屉的遮罩画在自身面板后。子窗口只补根页面缺少的暗度时，其 native dim 为零，抽屉面板仍全亮。真实 `AppBottomSheet` 基线两例均失败：页面亮度 173，父面板仍 255。 | 保留原页面遮罩分配，为被遮挡的父面板局部补足暗度；按面板圆角和实际偏移绘制，不覆盖空白与页面。 |
| 3 | 中 | 仍可见的中层窗口退出后停止贡献遮罩，但仍应接受最上层遮罩；超框封面也应与卡片一致变暗。 | 分离“释放遮罩来源”与“仍需被遮挡”；对超框已绘内容用 `SrcAtop` 补暗，保留透明轮廓。 |

代码位置与可核对证据：

- 问题 1：[基线 AppSearchField.kt:107](https://github.com/Weiss-UltimateSavior/Tyranor-Next/blob/da966622ae98906c9a0d32e0bdb9d52389eb3c35/app/src/main/java/com/tyranor/next/ui/common/AppSearchField.kt#L107)，整条编辑器的聚焦缩放。
- 问题 2：[基线 DialogDimStack.kt:122](https://github.com/Weiss-UltimateSavior/Tyranor-Next/blob/da966622ae98906c9a0d32e0bdb9d52389eb3c35/app/src/main/java/com/tyranor/next/ui/common/motion/DialogDimStack.kt#L122)，`missingAlpha(target, lowerScrim)` 保住页面暗度，却未给抽屉面板补暗。
- 问题 3：[DialogDimStack.kt:117](https://github.com/yuukana1/Tyranor-Next/blob/5a7dfb594759190724aa82ef8efe588c0d794644/app/src/main/java/com/tyranor/next/ui/common/motion/DialogDimStack.kt#L117) 保留退出窗口的真实可见性；[AppAlertDialog.kt:363](https://github.com/yuukana1/Tyranor-Next/blob/5a7dfb594759190724aa82ef8efe588c0d794644/app/src/main/java/com/tyranor/next/ui/common/AppAlertDialog.kt#L363) 覆盖超框已绘内容。
- 最终实现与回归：[DialogDimStack.kt](https://github.com/yuukana1/Tyranor-Next/blob/5a7dfb594759190724aa82ef8efe588c0d794644/app/src/main/java/com/tyranor/next/ui/common/motion/DialogDimStack.kt)、[关闭窗口双探针](https://github.com/yuukana1/Tyranor-Next/blob/5a7dfb594759190724aa82ef8efe588c0d794644/app/src/androidTest/java/com/tyranor/next/ui/common/motion/ClosingWindowScrimDeviceTest.kt)、[选择柄回归](https://github.com/yuukana1/Tyranor-Next/blob/5a7dfb594759190724aa82ef8efe588c0d794644/app/src/androidTest/java/com/tyranor/next/ui/common/AppSearchFieldHandleDeviceTest.kt)。

## 实现与独立审核

`DialogDimStack` 第一遍保留原 root/native/self-scrim 公式和窗口属性更新顺序。第二遍按 Activity 身份逆序累计上方实际遮罩，给仍可见的父面板计算缺失暗度。上层接受关闭即停止贡献，父面板补足同步清零；其自身页面遮罩继续保持。仍被更高窗口遮挡的退出中层继续接收补足，直到隐藏或移除。

面板入口覆盖公共弹窗、侧栏、普通/增强抽屉、封面搜索和裁剪；裁剪遮罩位于 IME padding 内。公共弹窗的超框内容只处理已绘像素，不把透明外侧填黑。

独立 Agent 分别检查了架构与公共入口、输入逻辑与回归、遮罩健壮性与生命周期，并交叉检查规范及测试。两个审核中发现的边界（退出中层与超框封面）已纳入修复。

## 可复现对照

- 真机：Honor PPG_AN00 / API 37，隔离包 `com.tyranor.next.motionv2`；所有 adb 均指定本轮授权设备。
- 光标 A/B：原 debug APK SHA256 `BFFEAC32ADB2036F1DA7EDCBD3946EA99ACB7ACEB93B98CB96B2D22D94FF83D6`。同一新版 androidTest 先跑原版，再跑只移除编辑区域缩放的候选。原版 4 测 2 失败，候选 4 测全通过；真实点击、移动、长按、拖拽均有正控，逐帧同步检查焦点、文本、节点与窗口身份。
- 录像辅助对照按原始 PTS 解码，没有固定 fps 重采样。在双方水滴已出现的共同 `.85–4.5s` 区间，原版 150 帧中 23 帧缺失，候选 156 帧中零缺失。该录像分别来自原 release/候选 debug，只作为画面辅助；可归因结论来自同 variant 的 debug A/B。
- 遮罩正控使用真实 `AppBottomSheet`，独立采样父面板和底层页面。关闭检查保留子窗口 attached/shown、raw progress=1，冻结动画时钟，排除“等待退场后才变亮”。
- 曾试验把下层自绘遮罩转交最上层 native 窗口。离散稳态测试通过，但连续步进真实屏幕发现进场闪亮和关闭叠暗，已否决该实现。最终实现不搬运根遮罩，不沿用该试验的通过结论。

## 验证命令与范围

构建前设置 `TYRANOR_APPLICATION_ID=com.tyranor.next.motionv2`。

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest --tests 'com.tyranor.next.ui.common.motion.*' --offline --console=plain
./gradlew.bat :app:assembleRelease :app:lintDebug --offline --console=plain
python tools/check-hardcoded-ui-strings.py
python tools/check-motion-boundaries.py
python tools/test_motion_boundaries.py
```

设备测试保留 `-e motion_device_acceptance true -e motion_target_package com.tyranor.next.motionv2`，runner 为 `com.tyranor.next.motionv2.test/androidx.test.runner.AndroidJUnitRunner`，本轮相关类为：

- `com.tyranor.next.ui.common.AppSearchFieldHandleDeviceTest`
- `com.tyranor.next.ui.common.motion.ClosingWindowScrimDeviceTest`
- `com.tyranor.next.ui.common.motion.NestedWindowScrimDeviceTest`
- `com.tyranor.next.ui.common.motion.DialogWindowDimDeviceTest`
- `com.tyranor.next.ui.game.MotionCoverSearchDialogDeviceTest`

最终执行结果：

| 检查 | 结果 |
|---|---|
| Debug / androidTest 构建 | 通过 |
| UI motion JVM 范围 | 6 类、45 项，0 失败/错误/跳过；其中 DialogDimStack 22 项 |
| 真机竖屏五个相关测试类 | 26 项通过；其中选择柄 4 项、冻结关闭双探针 4 项、嵌套转场 12 项 |
| 真机横屏选择柄与冻结关闭 | 8 项通过 |
| release / R8 / ART profile | 构建通过 |
| app lintDebug | 0 errors、402 warnings、10 hints |
| 三语言/硬编码检查 | 通过 |
| motion boundaries / checker 自测 | 347 Kotlin、0 违规；11 项通过 |

本轮竖屏 12 条嵌套转场各取得 156 个比较采样，共 1,872 个。每条的 root 亮度均等于其 parent-alone 基线：增强弹窗/侧栏 127、普通弹窗/侧栏 51、两档抽屉 173，未发现亮起或叠深。该次结果不替代上轮不同构建的采样记录。

真实抽屉的冻结关闭正控（两档均一致）：页面 173、父面板 255 → 子窗口打开后页面 173、父面板 173 → 接受关闭后页面 173、父面板 255；后两状态使用同一冻结时钟 1728 ms、raw=1，窗口仍 attached/shown。原生父弹窗两档也通过同样的双探针规则。

最终 release APK SHA256：`97B149E1634FD90C9558A5895A44E12948A6371E43199D61B2FF96B7A590CDA7`。已恢复竖屏 0/0 并安装到隔离包，`cmd package compile -m speed -f` 成功。

最终 release 人工路线已完成：游戏 → 游戏操作抽屉 → 搜索封面来源 → VNDB → 点击输入框并录屏 → 系统返回收键盘 → 关闭搜索返回原抽屉。已实际查看每一步截图：上层打开时父抽屉变暗，返回后抽屉恢复亮度而游戏页面仍被遮罩。未执行网络搜索或游戏启动。

设备测试是虚拟时钟步进后的真实屏幕及窗口采样，不能代表每个连续物理帧、完整性能矩阵或商业游戏兼容性。未覆盖其它 IME、API 26/27、平板与所有字体尺寸。选择柄测试读取 Foundation 1.11.2 的内部语义，升级依赖需重新核对。超框补暗的离屏层沿局部布局边界裁切，不能据此承诺任意越界阴影或位移不受裁切。
