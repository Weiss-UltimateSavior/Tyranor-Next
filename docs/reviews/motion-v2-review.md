## 按 AGENTS.md 的审核结果

审查范围：`motion-v2` 分支的初始未提交快照，基线为上游 `c65e3c55e288622b5d81f2585c9a3fd5b2173469`。初始 HEAD 与基线相同，没有可用的 PR head SHA；本报告使用仓库内路径与行号，不构造 GitHub permalink。范围为 `git diff c65e3c55` 中的全部已跟踪文件，加 `git status --short --untracked-files=all` 中的全部未跟踪文件，共 **74 个文件、+16,267 / −895 行**。增删行数在任何修复前冻结；未跟踪文本按整文件新增计算，仅行尾/状态变化按 +0/−0 计入。后续新增的设备测试、绘制阶段进度适配器与本报告列在末尾修复记录中，不倒填初始范围。

**用户最终确认的例外，优先于通用规范：** 滑条保留本批获准样式——普通档的已覆盖刻度与进度条同色，增强档不绘制已覆盖刻度。该静止外观差异是用户明确要求保留的例外，不再列为待修问题。**白雾风格的额外未覆盖刻度调色不保留，已恢复基线主题配色。** 新增动效不必完全遵循 AGENTS.md 的 token / tween 写法；不为统一写法改变已经调校的轨迹和节奏。其余静止外观、普通档、禁止修改底栏、快照状态持有与逐帧读取阶段的硬性约束继续适用。MainScreen 根部透明、零 inset 的 Miuix Scaffold 是用户批准的浮层宿主，保留其 dim 覆盖底栏的行为。

### 审查范围文件清单

| 文件 | 类型 | 新增 | 删除 |
|---|---|---:|---:|
| `AGENTS.md` | 已跟踪内容差异 | +21 | −7 |
| `CHANGELOG.md` | 已跟踪内容差异 | +5 | −0 |
| `app/proguard-rules.pro` | 已跟踪内容差异 | +7 | −0 |
| `app/src/main/baseline-prof.txt` | 未跟踪 | +11173 | −0 |
| `app/src/main/java/com/tyranor/next/MainActivity.kt` | 已跟踪内容差异 | +8 | −1 |
| `app/src/main/java/com/tyranor/next/core/settings/AppSettingsStore.kt` | 已跟踪内容差异 | +19 | −0 |
| `app/src/main/java/com/tyranor/next/theme/DefaultPageBackground.kt` | 仅状态变化，无内容差异 | +0 | −0 |
| `app/src/main/java/com/tyranor/next/theme/MiuixSettingsTheme.kt` | 已跟踪内容差异 | +2 | −0 |
| `app/src/main/java/com/tyranor/next/ui/archive/ArchiveUnpackActivity.kt` | 已跟踪内容差异 | +210 | −156 |
| `app/src/main/java/com/tyranor/next/ui/common/AppAlertDialog.kt` | 已跟踪内容差异 | +118 | −14 |
| `app/src/main/java/com/tyranor/next/ui/common/AppNavItem.kt` | 已跟踪内容差异 | +7 | −0 |
| `app/src/main/java/com/tyranor/next/ui/common/AppScreen.kt` | 已跟踪内容差异 | +12 | −6 |
| `app/src/main/java/com/tyranor/next/ui/common/AppSidePanel.kt` | 已跟踪内容差异 | +100 | −9 |
| `app/src/main/java/com/tyranor/next/ui/common/AppTopBar.kt` | 已跟踪内容差异 | +12 | −9 |
| `app/src/main/java/com/tyranor/next/ui/common/ArrowPreference.kt` | 已跟踪内容差异 | +8 | −1 |
| `app/src/main/java/com/tyranor/next/ui/common/DialogClickEffects.kt` | 已跟踪内容差异 | +8 | −1 |
| `app/src/main/java/com/tyranor/next/ui/common/EmbeddedSearchInputField.kt` | 已跟踪内容差异 | +5 | −2 |
| `app/src/main/java/com/tyranor/next/ui/common/MotionSlider.kt` | 未跟踪 | +367 | −0 |
| `app/src/main/java/com/tyranor/next/ui/common/MotionSwitch.kt` | 未跟踪 | +364 | −0 |
| `app/src/main/java/com/tyranor/next/ui/common/NoPressFeedbackButtons.kt` | 已跟踪内容差异 | +3 | −0 |
| `app/src/main/java/com/tyranor/next/ui/common/PlaceholderPage.kt` | 已跟踪内容差异 | +7 | −2 |
| `app/src/main/java/com/tyranor/next/ui/common/SlidingTabRow.kt` | 已跟踪内容差异 | +64 | −22 |
| `app/src/main/java/com/tyranor/next/ui/common/TopBarIcon.kt` | 已跟踪内容差异 | +3 | −0 |
| `app/src/main/java/com/tyranor/next/ui/common/motion/ContentMotion.kt` | 已跟踪内容差异 | +463 | −0 |
| `app/src/main/java/com/tyranor/next/ui/common/motion/DialogWindowMotion.kt` | 未跟踪 | +62 | −0 |
| `app/src/main/java/com/tyranor/next/ui/common/motion/Entrance.kt` | 已跟踪内容差异 | +404 | −0 |
| `app/src/main/java/com/tyranor/next/ui/common/motion/MotionMode.kt` | 未跟踪 | +62 | −0 |
| `app/src/main/java/com/tyranor/next/ui/common/motion/MotionSprings.kt` | 已跟踪内容差异 | +98 | −0 |
| `app/src/main/java/com/tyranor/next/ui/common/motion/MotionTokens.kt` | 未跟踪 | +58 | −0 |
| `app/src/main/java/com/tyranor/next/ui/common/motion/PageTransitions.kt` | 已跟踪内容差异 | +178 | −0 |
| `app/src/main/java/com/tyranor/next/ui/common/motion/PressSquish.kt` | 已跟踪内容差异 | +377 | −0 |
| `app/src/main/java/com/tyranor/next/ui/common/motion/SheetMotion.kt` | 未跟踪 | +368 | −0 |
| `app/src/main/java/com/tyranor/next/ui/cover/CoverScraperSettingsActivity.kt` | 已跟踪内容差异 | +28 | −15 |
| `app/src/main/java/com/tyranor/next/ui/engine/EngineScreen.kt` | 已跟踪内容差异 | +136 | −37 |
| `app/src/main/java/com/tyranor/next/ui/game/AddGameDialog.kt` | 已跟踪内容差异 | +7 | −0 |
| `app/src/main/java/com/tyranor/next/ui/game/AndroidGameAddDialog.kt` | 已跟踪内容差异 | +38 | −5 |
| `app/src/main/java/com/tyranor/next/ui/game/ArtemisPatchChoiceDialog.kt` | 仅状态变化，无内容差异 | +0 | −0 |
| `app/src/main/java/com/tyranor/next/ui/game/GameCoverFlow.kt` | 已跟踪内容差异 | +66 | −15 |
| `app/src/main/java/com/tyranor/next/ui/game/GameScreen.kt` | 已跟踪内容差异 | +277 | −131 |
| `app/src/main/java/com/tyranor/next/ui/game/GameShortcutActivity.kt` | 仅状态变化，无内容差异 | +0 | −0 |
| `app/src/main/java/com/tyranor/next/ui/game/GameShortcutCropDialog.kt` | 已跟踪内容差异 | +9 | −2 |
| `app/src/main/java/com/tyranor/next/ui/game/PcGameAddDialog.kt` | 已跟踪内容差异 | +18 | −0 |
| `app/src/main/java/com/tyranor/next/ui/game/RpgSaveFormatDialog.kt` | 已跟踪内容差异 | +8 | −0 |
| `app/src/main/java/com/tyranor/next/ui/home/HomeScreen.kt` | 已跟踪内容差异 | +97 | −31 |
| `app/src/main/java/com/tyranor/next/ui/main/MainScreen.kt` | 已跟踪内容差异 | +102 | −13 |
| `app/src/main/java/com/tyranor/next/ui/patch/KrkrOnlinePatchActivity.kt` | 已跟踪内容差异 | +87 | −18 |
| `app/src/main/java/com/tyranor/next/ui/runtime/EngineModuleManagementActivity.kt` | 已跟踪内容差异 | +4 | −1 |
| `app/src/main/java/com/tyranor/next/ui/runtime/EngineModuleManagementScreen.kt` | 已跟踪内容差异 | +71 | −9 |
| `app/src/main/java/com/tyranor/next/ui/save/SaveManagementActivity.kt` | 已跟踪内容差异 | +30 | −18 |
| `app/src/main/java/com/tyranor/next/ui/save/SaveVersionsPanel.kt` | 已跟踪内容差异 | +70 | −62 |
| `app/src/main/java/com/tyranor/next/ui/save/WebDavSettingsActivity.kt` | 已跟踪内容差异 | +30 | −14 |
| `app/src/main/java/com/tyranor/next/ui/settings/AppSettingsActivity.kt` | 已跟踪内容差异 | +49 | −21 |
| `app/src/main/java/com/tyranor/next/ui/settings/EngineSettingsActivity.kt` | 已跟踪内容差异 | +4 | −1 |
| `app/src/main/java/com/tyranor/next/ui/settings/EngineSettingsMenuActivity.kt` | 已跟踪内容差异 | +7 | −2 |
| `app/src/main/java/com/tyranor/next/ui/settings/FontSettings.kt` | 已跟踪内容差异 | +10 | −7 |
| `app/src/main/java/com/tyranor/next/ui/settings/GamepadMapScreen.kt` | 已跟踪内容差异 | +23 | −6 |
| `app/src/main/java/com/tyranor/next/ui/settings/GraphicsChainEditor.kt` | 已跟踪内容差异 | +4 | −1 |
| `app/src/main/java/com/tyranor/next/ui/settings/GraphicsRefreshIntervalPreference.kt` | 已跟踪内容差异 | +5 | −0 |
| `app/src/main/java/com/tyranor/next/ui/settings/GraphicsSettingsEditor.kt` | 已跟踪内容差异 | +11 | −3 |
| `app/src/main/java/com/tyranor/next/ui/settings/InputSettingsScreen.kt` | 已跟踪内容差异 | +15 | −2 |
| `app/src/main/java/com/tyranor/next/ui/settings/PerGameSettingsActivity.kt` | 已跟踪内容差异 | +4 | −1 |
| `app/src/main/java/com/tyranor/next/ui/settings/PerGameSettingsScreen.kt` | 已跟踪内容差异 | +119 | −71 |
| `app/src/main/java/com/tyranor/next/ui/settings/RenPySettingsCard.kt` | 已跟踪内容差异 | +27 | −12 |
| `app/src/main/java/com/tyranor/next/ui/settings/RpgMakerSettingsCard.kt` | 已跟踪内容差异 | +93 | −67 |
| `app/src/main/java/com/tyranor/next/ui/settings/RuffleSettingsScreen.kt` | 已跟踪内容差异 | +31 | −22 |
| `app/src/main/java/com/tyranor/next/ui/settings/SettingsScreen.kt` | 已跟踪内容差异 | +134 | −78 |
| `app/src/main/res/anim/page_pop_enter.xml` | 已跟踪内容差异 | +11 | −0 |
| `app/src/main/res/anim/page_pop_exit.xml` | 已跟踪内容差异 | +11 | −0 |
| `app/src/main/res/anim/page_push_enter.xml` | 已跟踪内容差异 | +11 | −0 |
| `app/src/main/res/anim/page_push_exit.xml` | 已跟踪内容差异 | +11 | −0 |
| `app/src/main/res/interpolator/page_push.xml` | 已跟踪内容差异 | +7 | −0 |
| `app/src/main/res/values-en/strings.xml` | 已跟踪内容差异 | +3 | −0 |
| `app/src/main/res/values-ja/strings.xml` | 已跟踪内容差异 | +3 | −0 |
| `app/src/main/res/values/strings.xml` | 已跟踪内容差异 | +3 | −0 |

已执行验证（构建与安装统一通过环境变量 `TYRANOR_APPLICATION_ID=com.tyranor.next.motionv2` 隔离；未更改版本号）：

| 命令 | 基线 c65e3c55 | 初始改动 | 修复后 |
|---|---|---|---|
| `./gradlew.bat :app:compileReleaseKotlin` | 通过 | 通过 | 通过 |
| `./gradlew.bat testDebugUnitTest --no-daemon` | 失败，先在 engine 停止 | 失败，先在同一 engine 任务停止 | 同样失败 |
| `./gradlew.bat testDebugUnitTest --continue --no-daemon` | 1,969 项，43 失败：app 27 / engine 16 | 1,969 项，43 失败，失败用例集合与基线相同 | 1,969 项，43 失败，**新增失败 0** |
| `./gradlew.bat :app:lintDebug --no-daemon` | 29 Error / 334 Warning / 8 Hint | 31 Error / 335 Warning / 8 Hint | 29 Error / 335 Warning / 8 Hint，**新增 Error 0** |
| `python tools/check-hardcoded-ui-strings.py` | 通过 | 通过 | 通过 |
| `./gradlew.bat :app:assembleRelease` | 未执行，不作为基线比较项 | 通过，隔离包名 | 通过，隔离包名，含 R8 / baseline profile 打包 |
| `./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon` | 未执行 | 未执行 | 通过 |
| `adb -s "$motionSerial" shell am instrument -w -r -e class com.tyranor.next.ui.common.MotionControlsDeviceTest -e motion_device_acceptance true com.tyranor.next.motionv2.test/androidx.test.runner.AndroidJUnitRunner` | 未执行 | 未执行 | **OK (3 tests)** |
| `git diff --check` | 不适用 | 两处 EOF 空行 | 通过 |

完整最终验证还执行了 `./gradlew.bat :app:compileReleaseKotlin testDebugUnitTest :app:lintDebug :app:assembleRelease :app:assembleDebug :app:assembleDebugAndroidTest --continue --no-daemon`：总退出码为 1，失败任务只有既有单元测试与既有 lint；编译与各 APK 构建成功。不能把该命令写成全绿。两次补跑使用 `--continue`，避免 engine 失败掩盖 app 的 27 个失败。失败比较按测试全限定类名与方法名去重，未将基线失败计为本批回归。剩余一个新增 lint Warning 是私有 modifier 工厂签名风格，不是新增 Error；按用户放宽的新增动效规则不作为阻断项。

设备验证为一台 Honor PPG-AN00、Android 17、1264×2800。审查前经 Android CLI 发现并阅读 `android-cli`、适用的测试与适配技能，沿用仓库 JUnit 4 / Compose AndroidTest，不新增测试框架。ADB 每次 `input tap` / `input swipe` 前均执行 `dumpsys window | grep mCurrentFocus`，仅在焦点为隔离测试包时继续；一次启动首帧为 null 时安全停止，待独立读取确认测试包前台后才重试。设备测试的数值动作通过隔离 Compose 树的 semantics 调用，没有向其他应用注入触控。未操作、修改或卸载正式包 `com.tyranor.next`，未修改系统设置。

真机修复前后分别安装隔离 Release APK，录制同一组设置页入场、刻度滑条拖动/连续操作、下拉菜单、标签切换、侧栏开关及关闭途中重开。录屏仅存本地，逐帧导出为 30fps 序列用于核对；背景粒子、系统时钟与设备负载不同，不能宣称整屏逐像素相同。进度绘制迁移另用真机 Compose 测试时钟逐帧推进 64 帧，对比旧版 Miuix + 同一弹簧与新实现，每通道容差 1/255。该 64 帧像素检查已通过。

未执行：平板/折叠屏/横屏完整验收，低端机与多机型，全部主题/深浅色组合，系统动画关闭、RTL 与大字体全矩阵，所有引擎真实游戏启动/存档/退出，快捷方式冷进程的真实 Artemis / MV / MZ 游戏确认链路，游戏抽屉所有导航返回分支，release ART 反射替换字段的直接断言，baseline profile 的编译命中与冷启动收益、Perfetto / 帧耗时 / 功耗定量评估。单机录屏与测试不能代替这些验收。

### 结论摘要

初审发现的确认问题经主 agent 回源码核对、去重后已修复；新独立 agent 对修复本身及最终滑条例外补充复审，未发现新增高、中级问题。**可提交上游 PR 供维护者评审，尚不能据此宣称全部设备与性能验收完成。** 单元测试与 lint 仍有明确的基线失败，本批没有新增失败用例或新增 lint Error。主要剩余风险是未执行的平板/主题/引擎入口矩阵与性能收益验证，不将这些写成已通过。

### 独立审核执行

| 视角 | 范围与方式 | 结果复核 |
|---|---|---|
| 代码逻辑 | 状态流、普通档、可打断、控件与入口；只读独立审查 | 主 agent 回源码复核，重复项去重 |
| 架构 | 三层依赖、动效环境、浮层宿主、主题与 inset；只读独立审查 | 核对 Miuix 0.9.2 实际发布源码 |
| 健壮性 | 生命周期、节点复用、资源释放、连续手势、无障碍、极端路径 | 节点和回调增加真实行为测试 |
| 回归风险 | 各引擎入口、主题、普通档、平板与既有调用链 | 明确设备盲区，不用源码审查冒充设备通过 |
| 代码规范 | 动画值读取阶段、快照持有、重复/无效代码、文档 | 用户放宽 token / tween 要求后移除纯写法阻断项 |
| 新的修复复审 | 只比较初始文件快照与修复差异，独立检查修复本身 | 最终补充复审无新增高、中级问题 |

前五位子 agent 在各自审查期间没有收到其他子 agent 的结论，没有互相共享发现。受并发槽位限制分批启动，共五位初审 + 一位全新修复复审；汇总与源码复核由主 agent 执行。

### 问题清单

以下位置是**初始审查快照行号**；修复后可核对位置见末尾修复记录。级别按模板：高阻断合并，中合并前需确认/修复，低可后续改进。本批未确认高等级问题；所有列出的中、低项均已处理。

| No. | 级别 | 问题 | 建议 / 处理 | 位置（初始快照） |
|---|---|---|---|---|
| 1 | 中 | 新增 `remember` 返回 Unit，增加 `RememberReturnType` lint Error | 返回已加载的 StateFlow，保留首帧同步加载 | `app/src/main/java/com/tyranor/next/ui/common/motion/PageTransitions.kt:145` |
| 2 | 中 | Entrance 仅在 attach 读取 CompositionLocal，未订阅提供者变更，增加 `SuspiciousCompositionLocalModifierRead` Error | `ObserverModifierNode` + `observeReads`，提供者变化时迁移节点注册 | `app/src/main/java/com/tyranor/next/ui/common/motion/Entrance.kt:286` |
| 3 | 中 | 稳定 layerBlock 读取的可替换动画持有者是普通字段，违反快照状态硬约束 | 改为 `mutableStateOf` 持有；不改动画参数 | `app/src/main/java/com/tyranor/next/ui/common/motion/Entrance.kt:253` |
| 4 | 中 | Bounce 节点被回收时动画取消，中间缩放可随节点重新出现 | 重新 attach 同步恢复 scale=1，清理旧 job | `app/src/main/java/com/tyranor/next/ui/common/motion/ContentMotion.kt:138` |
| 5 | 中 | 旧滑条落位任务可在新手势中清除 interacting，外部同步抢占跟手运动 | 新手势取消旧任务并增加所有权代数 | `app/src/main/java/com/tyranor/next/ui/common/MotionSlider.kt:265` |
| 6 | 中 | 快捷方式独立 setContent 冷启动未初始化持久化动效档位，普通档落到默认增强 | 两个独立确认根接入同一动效环境 | `app/src/main/java/com/tyranor/next/ui/game/ArtemisPatchChoiceDialog.kt:94`；`app/src/main/java/com/tyranor/next/ui/game/RpgSaveFormatDialog.kt:98` |
| 7 | 中 | 下载进度在组合期读取逐帧动画值，重组整个进度 Column | 保持 Miuix 几何/颜色/同一 spring，在 Canvas 绘制阶段读取 | `app/src/main/java/com/tyranor/next/ui/settings/SettingsScreen.kt:646` |
| 8 | 中，既有缺口补齐 | 滑条无障碍 SetProgress 不交付完成回调，仅完成回调写盘的调用方不能保存 | 两档都补齐完成回调；明确原库同样存在缺口，不算本批新增回归 | `app/src/main/java/com/tyranor/next/ui/common/MotionSlider.kt:296`；`app/src/main/java/com/tyranor/next/ui/settings/SettingsScreen.kt:308` |
| 9 | 低 | 抽屉说明同时写先关闭和返回保留；速度投影实现与只看位置说明矛盾 | 对齐既有最终行为，只修说明 | `CHANGELOG.md:7`、`CHANGELOG.md:9`、`app/src/main/java/com/tyranor/next/ui/common/motion/SheetMotion.kt:55` |
| 10 | 低 | 两处新增 EOF 空行使 diff --check 失败 | 清理多余 EOF 空行 | `app/src/main/java/com/tyranor/next/ui/common/AppAlertDialog.kt:309`、`app/src/main/java/com/tyranor/next/ui/main/MainScreen.kt:775` |

### 重点问题说明

**1–3.【中】初始化、CompositionLocal 观察与图层状态。** 原来的 `remember { initMotionEnhanced(...) }` 最后表达式为 Unit；原始 lint 的两个新增 Error 精确落在它和 Entrance 的 attach-only Local 读取。修复将初始化结果记为既有 StateFlow，并显式订阅 Local；迁移节拍时从旧提供者移除注册、作废旧播放，再接入新提供者。`anim` 改为快照状态，遵循 layerBlock 不能靠 `invalidateLayer()` 自动重跑的约束。未将静态字段违例夸大成已复现的透明卡死；可见回归与参数保持仍以所测设备场景为限。

**4–5.【中】回收与连续交互的所有权。** Bounce 的长期 Animatable 会在协程取消时保留中间值，相同 trigger 又直接返回，重新挂载需要显式复原。设备用例在暂停时钟下触发形变、明确推进一帧停用、再推进一帧复用，验证角点恢复原始填充。滑条新按下时取消旧落位 job 并递增代数，旧任务不能再清除新手势的 interacting；位置、按压、拉伸、回弹的现有参数均未改。

**6.【中】快捷方式是独立动效环境入口。** `GameShortcutActivity` 可直接调用两个挂起确认 helper；未经过主 Activity / AppScreenScaffold，`MotionMode` 默认 true。补齐两个根的 `ProvideMotionEnvironment` 后在弹窗读取之前同步加载持久化档位。未用真实商业游戏触发这两个冷进程链路，因此仅记录源码修复与编译，不声明该设备链路已验收。

**7.【中】进度绘制迁移保持轨迹。** Miuix 0.9.2 的确定进度是圆角背景加最小宽度为高度的前景。适配器保留同一高度、颜色来源、圆角、最小宽度公式与 `MotionTokens.value()`；不定态和普通档仍用原组件。真机逐帧用旧组件加原 spring 作参照，64 帧每通道差异不超过 1/255；这是绘制迁移的直接证据，不是性能收益评估。

**8.【中，既有】无障碍完成回调缺口。** 新复审核对发布的 Miuix 0.9.2 `Slider.kt:245–249`，其原版 semantics 同样只调用 onValueChange，不调用完成回调。因此撤销“原版会保存、本批才遗漏”的归因。增强自绘组件和普通档外层 semantics 均补齐完成交付，保留各自已有数值规则；设备测试逐档断言显示值、模拟落盘值及完成次数恰为一次。没有声称已完成真实 TalkBack 服务全流程或所有 Store 的重启持久化验收。

### 其余问题

**9.【低】说明对齐。** CHANGELOG 与抽屉 KDoc 已明确：进入二级页面保持抽屉作为返回落点，启动游戏关闭；下拉按位置加有限速度投影判定。此处不调整已有导航或抽屉轨迹。

**10.【低】格式与无效导入。** 清理两处 EOF 空行及本批新增但未使用的三个 import，没有重排无关源码。滑条 KDoc 同时注明用户批准的刻度显示例外，避免继续声称与 Miuix 所有静态像素完全一致。

### 非阻塞建议

- 继续补齐平板/主题/引擎入口矩阵及定量性能验收。上游 main 相对审查基线新增的三笔提交只修改 README 与截图，没有应用代码差异；本报告的应用代码基线仍为 c65e3c55。
- 新增动效的 token 收口和 tween 转弹簧仅作为后续设计选择，遵从用户最新要求，本轮保留现有节奏；不以静默修改规范或替换轨迹消除差异。

### 已核对无问题的部分

- 底部导航受保护文件没有内容改动；`DefaultBottomNavigationBar` 函数体保持基线。MainScreen 仅保留已批准的根浮层宿主及页面动效等原审查范围变更。
- 根 Miuix Scaffold 为透明、零 inset；实际 Miuix 0.9.2 源码将正文放在 `(0,0)`，后绘制 root popup host；宿主包 `MiuixSettingsTheme`，嵌套 Scaffold 复用 root popup 状态。未发现空闲 Surface 新增吞触摸逻辑。真机菜单/侧栏的观察范围见验证记录，平板和全主题仍未执行。
- 未新增 core→Compose 或 UI→底层引擎依赖；可观察动效偏好在 core 使用既有 StateFlow，UI 提供快照镜像。
- 三语言字符串检查通过；没有改版本号、签名材料或引擎运行库，也没有将真机录屏、连接地址、商业游戏或本地构建产物加入仓库。

外部实现核对来源：Maven Central 的 `top.yukonga.miuix.kmp:miuix-ui-android:0.9.2` sources JAR（SHA-256 `aeca4a7bb54fee887fbdcd5cd8f0f8cc02bcb98777c109a6a6049cd02f727251`），读取 Slider、ProgressIndicator、Scaffold、Surface 等实际发布源码；不以其它分支的新版本代替当前依赖。

---

## 修复记录

| No. | 处理结果 | 改动与可核对位置 | 验证 |
|---|---|---|---|
| 1 | 已修复 | `app/src/main/java/com/tyranor/next/ui/common/motion/PageTransitions.kt:145` | Release Kotlin 通过；新增 lint Error 消除 |
| 2 | 已修复 | `app/src/main/java/com/tyranor/next/ui/common/motion/Entrance.kt:296` | Release Kotlin 通过；新增 Local 读取 lint Error 消除；普通入场录屏 |
| 3 | 已修复 | `app/src/main/java/com/tyranor/next/ui/common/motion/Entrance.kt:256` | 新复审确认 snapshot 持有；图层块仍在绘制/布局阶段读值 |
| 4 | 已修复 | `app/src/main/java/com/tyranor/next/ui/common/motion/ContentMotion.kt:143` | `reusedBounceDoesNotKeepInterruptedScale` 真机通过 |
| 5 | 已修复 | `app/src/main/java/com/tyranor/next/ui/common/MotionSlider.kt:203`；`app/src/main/java/com/tyranor/next/ui/common/MotionSlider.kt:276` | 正常/连续拖动 Release 录屏；新复审确认旧任务所有权隔离 |
| 6 | 已修复，真实快捷方式链路未执行 | `app/src/main/java/com/tyranor/next/ui/game/ArtemisPatchChoiceDialog.kt:99`；`app/src/main/java/com/tyranor/next/ui/game/RpgSaveFormatDialog.kt:103` | 两根均编译通过；源码核对首帧加载，未冒充游戏设备验收 |
| 7 | 已修复 | `app/src/main/java/com/tyranor/next/ui/common/MotionLinearProgressIndicator.kt:24`；`app/src/main/java/com/tyranor/next/ui/settings/SettingsScreen.kt:645` | `progressFramesMatchPreviousMiuixRendering` 真机 64 帧像素比较通过 |
| 8 | 已修复，两档补齐既有缺口 | `app/src/main/java/com/tyranor/next/ui/common/MotionSlider.kt:83`；`app/src/main/java/com/tyranor/next/ui/common/MotionSlider.kt:311` | `accessibilitySliderChangeDeliversCompletion` 对两档完成次数、模拟保存值验证通过 |
| 9 | 已修复 | `CHANGELOG.md:7`；`app/src/main/java/com/tyranor/next/ui/common/motion/SheetMotion.kt:55` | 文档与实际导航/投影分支复核，无轨迹参数变化 |
| 10 | 已修复 | AppAlertDialog / MainScreen EOF、MainScreen / SettingsScreen 无效导入、MotionSlider KDoc | `git diff --check` 通过 |
| 滑条静止外观 | 按用户明确例外保留 | `app/src/main/java/com/tyranor/next/ui/common/MotionSlider.kt:79`；`app/src/main/java/com/tyranor/next/ui/common/MotionSlider.kt:357` | 两档均保留本批刻度显示样式；不记为待修 |
| 白雾额外刻度调色 | 按用户要求撤销 | `app/src/main/java/com/tyranor/next/theme/MiuixSettingsTheme.kt` | 新增的 sliderKeyPoint 覆盖已删除，主题恢复基线 |

新增验证文件为 `app/src/androidTest/java/com/tyranor/next/ui/common/MotionControlsDeviceTest.kt:37`，仅在精确隔离包名且显式传入 `motion_device_acceptance=true` 时执行。最初一轮中，复用测试在暂停时钟下未推进停用/复用帧而失败，另一个诊断调用与 Android CLI 的 UiAutomation 服务冲突；已修正测试时钟步骤并移除不需要的第二套 UiAutomation 诊断，停止本轮 CLI 辅助进程后重跑 **3/3 通过**。没有将失败轮删去或写成通过，也没有放宽像素容差或复用恢复断言。

最终清理已核对：本轮电脑检查脚本均已退出，手机没有 screenrecord、perfetto、Android CLI 辅助 instrumentation 或测试 runner 残留进程。Release 前后录屏的滑条静止样式、正常与连续拖动、页面入场、菜单覆盖底栏的 dim、侧栏关闭/重开未观察到新增跳变；输入与采集延迟不同，不声称两次录屏的绝对时间戳相同。最后再次执行 Release Kotlin 编译和 assembleRelease 均通过，字符串与 diff 格式检查也通过。性能、平板与其它未执行项保持上面的明确标注。原始日志与媒体仅作本地验收证据，不提交任何设备地址、用户名路径或私人画面。审核报告按初始未提交范围编写；后续用户已明确授权清理、提交和向上游创建 PR，发布信息以实际 PR 页面为准，不另行冒充已发布 `gh pr review`。


## CodeRabbit 评审修复记录（PR #150，2026-11-07）

审查范围：修复前 PR head `c658192e3321a9d1ccf17120f26db18da142a453`，目标分支 `main`，原动效基线仍为 `c65e3c55`。逐条核对 CodeRabbit 的 **14 条行内评论**及相关调用方。本轮源码与设备测试共 **39 个文件、+1263 / −161 行**，另同步 `CHANGELOG.md` 与本报告。下列位置均为本轮修复后工作树行号，不伪造 commit permalink；本节不改写前面的初始审查快照统计。

### 结论摘要

14 条评论全部完成源码修复或验证补充，最终独立复审未发现剩余确认的高、中级问题，可更新现有 PR 继续上游评审。原弹簧、缓动、运动模糊、位移/尺寸、按压形变和动画 token 参数未调整；普通档保持原版行为，遮罩/触摸与健壮性缺陷修复对两档生效。普通裁剪弹窗恢复平台退场属于回归修复。没有改动受保护底栏文件或 `DefaultBottomNavigationBar`，透明零 inset 的 Miuix 浮层宿主及 dim 覆盖底栏行为保留。滑条例外与白雾刻度配色决定继续沿用本报告前述用户确认。

### 问题清单与处理结果

| No. | 级别 | 问题与评论证据 | 处理结果 | 修复后位置 |
|---|---|---|---|---|
| 1 | 中 | [评论 4496842170](https://github.com/Weiss-UltimateSavior/Tyranor-Next/pull/150#discussion_r4496842170)：增强弹窗退场中再次激活会重复交付按钮动作 | 已修复；增强档退出流程只交付一次；普通档仍立即执行 | `app/src/main/java/com/tyranor/next/ui/common/AppAlertDialog.kt:122` |
| 2 | 中 | [评论 4496842191](https://github.com/Weiss-UltimateSavior/Tyranor-Next/pull/150#discussion_r4496842191)：固定 650ms 使慢速数字滚动提前切回完整文字 | 已修复；等待同一字符 Transition 真正完成，保留运行状态以支持 1→8→1 反向打断 | `app/src/main/java/com/tyranor/next/ui/common/motion/ContentMotion.kt:417` |
| 3 | 中 | [评论 4496842198](https://github.com/Weiss-UltimateSavior/Tyranor-Next/pull/150#discussion_r4496842198)：全局跳过计数会打断其他页面或浮层的入场 | 已修复；各 EntranceState 独立计数，各既有根节点只观察所属状态；移除过期 profile 类规则 | `app/src/main/java/com/tyranor/next/ui/common/motion/Entrance.kt:62` |
| 4 | 中 | [评论 4496842209](https://github.com/Weiss-UltimateSavior/Tyranor-Next/pull/150#discussion_r4496842209)：无效参数在 Compose 前 finish，普通档误用默认增强返回 | 已修复；基类 onCreate 同步持久化档位后才交给子类校验 | `app/src/main/java/com/tyranor/next/ui/common/AppScreen.kt:34` |
| 5 | 中 | [评论 4496842219](https://github.com/Weiss-UltimateSavior/Tyranor-Next/pull/150#discussion_r4496842219)：450ms 超时可能早于侧推而关闭侧栏 | 已修复；仅实际 ON_PAUSE 消费关闭；导航锁保持至让位或明确取消，旧回调按代数作废 | `app/src/main/java/com/tyranor/next/ui/common/motion/PageTransitions.kt:161` |
| 6 | 中 | [评论 4496842229](https://github.com/Weiss-UltimateSavior/Tyranor-Next/pull/150#discussion_r4496842229)：抽屉开始退场后仍可执行内容操作 | 已修复；所有直接条目和公共动作在执行业务时检查 busy，保持原按压外观 | `app/src/main/java/com/tyranor/next/ui/game/GameScreen.kt:1050` |
| 7 | 中 | [评论 4496842241](https://github.com/Weiss-UltimateSavior/Tyranor-Next/pull/150#discussion_r4496842241)：空 keyPoints 在无障碍或松手索引时越界 | 已修复；空列表按未提供处理，有 steps 时保留均分刻度，否则走原连续滑条 | `app/src/main/java/com/tyranor/next/ui/common/MotionSlider.kt:77` |
| 8 | 中 | [评论 4496842251](https://github.com/Weiss-UltimateSavior/Tyranor-Next/pull/150#discussion_r4496842251)：固定标签 RTL 指示器使用了错误方向的偏移 | 已修复；固定标签逻辑坐标用 placeRelative，滚动标签保持物理坐标及左侧锚点；同时修复异步清表丢失首次几何 | `app/src/main/java/com/tyranor/next/ui/common/SlidingTabRow.kt:123` |
| 9 | 中 | [评论 4496842261](https://github.com/Weiss-UltimateSavior/Tyranor-Next/pull/150#discussion_r4496842261)：库列表退场期间陈旧卡片回调仍可开抽屉或启动 | 已修复；网格/封面流点击与长按读取当前 pane 是否 Content | `app/src/main/java/com/tyranor/next/ui/game/GameScreen.kt:605` |
| 10 | 中 | [评论 4496842272](https://github.com/Weiss-UltimateSavior/Tyranor-Next/pull/150#discussion_r4496842272)：封面搜索窗口关闭路径未及时透传触摸 | 已修复；返回、遮罩、关闭按钮及绑定成功在关闭前处理窗口；绑定失败保留交互 | `app/src/main/java/com/tyranor/next/ui/game/GameScreen.kt:1472` |
| 11 | 中 | [评论 4496842292](https://github.com/Weiss-UltimateSavior/Tyranor-Next/pull/150#discussion_r4496842292)：普通档裁剪弹窗被清除平台退场动画 | 已修复；保留原平台 windowAnimations，真正关闭前清零 dim 并禁止触摸；选择新图片不关闭窗口 | `app/src/main/java/com/tyranor/next/ui/game/GameShortcutCropDialog.kt:164` |
| 12 | 中 | [评论 4496842309](https://github.com/Weiss-UltimateSavior/Tyranor-Next/pull/150#discussion_r4496842309)：仅按 400ms 判断重播会隐藏仍可见的页面 | 已修复；保留原间隔，并在点击时核对原 alpha/offset 的实际可见状态 | `app/src/main/java/com/tyranor/next/ui/main/MainScreen.kt:726` |
| 13 | 中 | [评论 4496842317](https://github.com/Weiss-UltimateSavior/Tyranor-Next/pull/150#discussion_r4496842317)：恢复卡片与版本控件被 Box 叠放 | 已修复；Column 恢复原有纵向顺序，保留同一入场 modifier | `app/src/main/java/com/tyranor/next/ui/save/SaveManagementActivity.kt:363` |
| 14 | 低 | [评论 4496853064](https://github.com/Weiss-UltimateSavior/Tyranor-Next/pull/150#discussion_r4496853064)：普通档进度变化缺少原始 Miuix 帧对照保护 | 已补充；普通档原始进度 64 帧对照，增强档也继续逐帧对照，容差 1/255 | `app/src/androidTest/java/com/tyranor/next/ui/common/MotionControlsDeviceTest.kt:112` |

### 重点问题说明

- 数字滚动保留 `AnimatedContent` 本来创建的同一 Transition，只观察真实完成状态；所有字符及其子动画完成后才恢复完整文本。独立复审发现第一版仅比较 `settledText` 会破坏 `1→8→1` 反向，已恢复显式运行状态，并以 4 倍时长、反向打断、最终像素和 0 倍时长设备用例验证。
- 慢启动不再使用固定超时收起侧栏，也不在超时后解除导航锁；等待由生命周期观察器承载，没有永久等待的协程。真实 `ON_PAUSE` 只消费最新动作一次，销毁或移除组合清理动作。取消时作废旧代数，避免旧动作关闭重新打开的侧栏或解除新请求的锁。
- 滚动标签设备测试最初失败，截图中三个文字正常而整个指示器缺失。源码确认异步 `geometry.clear()` 能晚于首次 `onGloballyPositioned` 写回；改成 `remember(tabs.size)` 同步换表，保留原来只按数量重置的范围，同数量改名/重排仍保留指示器弹簧。LTR/RTL、固定/滚动及三个选中位置共 12 组截图断言通过。
- 普通裁剪弹窗在真实 `GameShortcutCropDialog` 取消回调处检查原平台动画 ID、dim=0、`FLAG_NOT_TOUCHABLE`，并强制内容到达实际绘制帧后录制平台退出。选择新图片的父回调会保留裁剪弹窗，因此不会提前禁用该窗口。

### 独立复审

逻辑、架构、健壮性、回归风险、代码规范共五个独立审查视角，分别只读核对本轮 diff 与调用方；未把其他审查者的发现作为其首次审查输入。主 agent 对每条发现回源码复核。两处修复中发现的边界问题（数字反向和慢启动重复请求）及后续取消代数均已修正并再次独立复核。标签缓存修复还由控件审查 agent 单独核对，按其建议将缓存 key 收窄为 `tabs.size`，保持原运动范围。最终全新独立 agent 复审后，还将裁剪窗口的销毁清理改为捕获该 effect 自己的 window，避免条件性的窗口替换时清错新窗口；正常调用链是否触发同子树窗口替换仍标待确认，几行所有权防御修复已完成并再次独立复核，不改变关闭轨迹。

### 验证记录

以下构建统一设 `TYRANOR_APPLICATION_ID=com.tyranor.next.motionv2`。没有更改版本号，没有安装或操作正式包 `com.tyranor.next`。

| 命令 / 检查 | 本轮最终结果 |
|---|---|
| `./gradlew.bat :app:compileReleaseKotlin` | 通过 |
| `./gradlew.bat testDebugUnitTest --continue --no-daemon` | 1,969 项，43 失败（app 27 / engine 16）；按完整类名/方法名与此前实际执行的 `c65e3c55` 基线及修复前集合比较，新增失败 **0** |
| `./gradlew.bat :app:lintDebug --no-daemon` | 29 Error / 335 Warning / 8 Hint；与本轮修复前相同，新增 Error **0**。不能写成 lint 全绿；中途出现的返回值 Composable 命名 Warning 已修正 |
| `python tools/check-hardcoded-ui-strings.py` | 通过 |
| `./gradlew.bat :app:assembleRelease :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon` | 通过，含 R8 和 baseline profile 编译/打包 |
| `./gradlew.bat :app:assembleDebugAndroidTest --no-daemon` | 实际裁剪窗口绘制/录屏补充后再次通过 |
| `./gradlew.bat :app:compileReleaseKotlin :app:assembleRelease :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon` | 最终窗口清理所有权修复后再次通过 |
| `adb -s "$motionSerial" shell am instrument -w -r -e class "$motionClasses" -e motion_device_acceptance true com.tyranor.next.motionv2.test/androidx.test.runner.AndroidJUnitRunner` | **OK (22 tests)**，八个 Motion 设备测试类；未出现跳过 |
| 上一命令的 `-e class com.tyranor.next.ui.game.MotionCropDialogDeviceTest` | 录屏补充后、最终窗口所有权修复后分别独立复跑 **OK (2 tests)**；覆盖两档实际裁剪窗口 |
| `git -c core.safecrlf=false diff --check`、保护文件/默认底栏函数比对、隐私扫描 | 通过；无版本号或保护底栏差异，原始日志、设备地址、私人画面均不进提交 |

`motionClasses` 为 `com.tyranor.next.ui.common` 下的 `MotionControlsDeviceTest`、`MotionRollingDeviceTest`、`MotionEntranceDeviceTest`、`MotionLifecycleDeviceTest`、`MotionDialogDeviceTest`、`MotionActivityInitializationDeviceTest`，以及 `com.tyranor.next.ui.game` 下的 `MotionGameInteractionsDeviceTest`、`MotionCropDialogDeviceTest`，以逗号连接的完整类名。

测试记录保留失败轮次：前两轮标签首测缺失，修复同步缓存后通过；第三轮进度对照的两次独立截图出现高度不一致，改成共同根的一次截图比较两个区域，继续断言组件/截图尺寸和逐像素 1/255 容差，最终 22/22 通过，没有用放宽阈值或删除断言消除失败。增强档与普通档进度各推进 64 帧；慢动画测试通过 Compose 注入 4 倍/0 倍时长，不修改系统设置。游戏操作用合成 UI 夹具，不启动真实引擎。

设备为 Honor PPG-AN00、Android 17、1264×2800。已保存同一组操作的修复前后隔离 Release 录屏，并导出 30fps 序列，逐帧核对设置入场/主标签水平切换、刻度滑条连续拖动/回弹、下拉 dim 覆盖底栏及侧栏关闭重开；所测场景未观察到新增跳变或静止外观变化。时钟、背景粒子、输入采样与录制负载不同，不宣称两次整屏逐像素或绝对时间戳相同。普通裁剪窗口另录制实际内容与退出，可见平台淡出；属性与关闭前 dim/触摸由同一实际组件用例断言。设备输入前逐次检查前台；本轮一次启动首帧为 null 时停止了输入，确认前台恢复为隔离包后才重试。

未执行：全部主题/深浅色组合，平板/折叠屏/横屏完整矩阵，低端机与其他机型，真实游戏的各引擎启动/存档/退出，真实封面下载和快捷方式导出全流程，所有 Miuix 浮层类型、RTL 长标签溢出全矩阵，Perfetto/功耗/性能收益定量评估。已有真实设备通过只覆盖上述具体场景。

### 本轮修复文件清单

| 文件 | 新增 | 删除 |
|---|---:|---:|
| `app/src/androidTest/java/com/tyranor/next/ui/common/MotionActivityInitializationDeviceTest.kt` | +37 | −0 |
| `app/src/androidTest/java/com/tyranor/next/ui/common/MotionControlsDeviceTest.kt` | +128 | −6 |
| `app/src/androidTest/java/com/tyranor/next/ui/common/MotionDialogDeviceTest.kt` | +110 | −0 |
| `app/src/androidTest/java/com/tyranor/next/ui/common/MotionEntranceDeviceTest.kt` | +91 | −0 |
| `app/src/androidTest/java/com/tyranor/next/ui/common/MotionLifecycleDeviceTest.kt` | +241 | −0 |
| `app/src/androidTest/java/com/tyranor/next/ui/common/MotionRollingDeviceTest.kt` | +120 | −0 |
| `app/src/androidTest/java/com/tyranor/next/ui/game/MotionCropDialogDeviceTest.kt` | +117 | −0 |
| `app/src/androidTest/java/com/tyranor/next/ui/game/MotionGameInteractionsDeviceTest.kt` | +119 | −0 |
| `app/src/main/baseline-prof.txt` | +0 | −1 |
| `app/src/main/java/com/tyranor/next/MainActivity.kt` | +1 | −2 |
| `app/src/main/java/com/tyranor/next/ui/archive/ArchiveUnpackActivity.kt` | +2 | −1 |
| `app/src/main/java/com/tyranor/next/ui/common/AppAlertDialog.kt` | +4 | −2 |
| `app/src/main/java/com/tyranor/next/ui/common/AppScreen.kt` | +5 | −2 |
| `app/src/main/java/com/tyranor/next/ui/common/AppSidePanel.kt` | +2 | −1 |
| `app/src/main/java/com/tyranor/next/ui/common/MotionSlider.kt` | +5 | −3 |
| `app/src/main/java/com/tyranor/next/ui/common/PlaceholderPage.kt` | +5 | −3 |
| `app/src/main/java/com/tyranor/next/ui/common/SlidingTabRow.kt` | +14 | −7 |
| `app/src/main/java/com/tyranor/next/ui/common/motion/ContentMotion.kt` | +28 | −24 |
| `app/src/main/java/com/tyranor/next/ui/common/motion/DialogWindowMotion.kt` | +9 | −3 |
| `app/src/main/java/com/tyranor/next/ui/common/motion/Entrance.kt` | +28 | −31 |
| `app/src/main/java/com/tyranor/next/ui/common/motion/PageTransitions.kt` | +27 | −13 |
| `app/src/main/java/com/tyranor/next/ui/cover/CoverScraperSettingsActivity.kt` | +2 | −1 |
| `app/src/main/java/com/tyranor/next/ui/game/AddGameDialog.kt` | +2 | −1 |
| `app/src/main/java/com/tyranor/next/ui/game/GameScreen.kt` | +32 | −15 |
| `app/src/main/java/com/tyranor/next/ui/game/GameShortcutCropDialog.kt` | +25 | −3 |
| `app/src/main/java/com/tyranor/next/ui/game/RpgSaveFormatDialog.kt` | +5 | −1 |
| `app/src/main/java/com/tyranor/next/ui/main/MainScreen.kt` | +76 | −27 |
| `app/src/main/java/com/tyranor/next/ui/patch/KrkrOnlinePatchActivity.kt` | +2 | −1 |
| `app/src/main/java/com/tyranor/next/ui/runtime/EngineModuleManagementScreen.kt` | +2 | −1 |
| `app/src/main/java/com/tyranor/next/ui/save/SaveManagementActivity.kt` | +6 | −5 |
| `app/src/main/java/com/tyranor/next/ui/save/WebDavSettingsActivity.kt` | +2 | −1 |
| `app/src/main/java/com/tyranor/next/ui/settings/AppSettingsActivity.kt` | +2 | −1 |
| `app/src/main/java/com/tyranor/next/ui/settings/EngineSettingsMenuActivity.kt` | +2 | −1 |
| `app/src/main/java/com/tyranor/next/ui/settings/EthornellSettingsScreen.kt` | +2 | −0 |
| `app/src/main/java/com/tyranor/next/ui/settings/GamepadMapScreen.kt` | +2 | −1 |
| `app/src/main/java/com/tyranor/next/ui/settings/InputSettingsScreen.kt` | +2 | −1 |
| `app/src/main/java/com/tyranor/next/ui/settings/PerGameSettingsScreen.kt` | +2 | −1 |
| `app/src/main/java/com/tyranor/next/ui/settings/RuffleSettingsScreen.kt` | +2 | −0 |
| `app/src/main/java/com/tyranor/next/ui/settings/SettingsScreen.kt` | +2 | −1 |

本轮仅更新现有 PR，不合并。结束前停止本轮仪器及采集进程，清除手机上的本轮录屏临时文件，并恢复已验证的隔离 Release 包供继续检查；保留本地验收日志与媒体，不向上游提交隐私内容。

---

## CodeRabbit 第二轮修复与独立复审（2026-11-07）

### 按 AGENTS.md 的审核结果

审查范围：PR [#150](https://github.com/Weiss-UltimateSavior/Tyranor-Next/pull/150) 已发布 head `d4fb956efb6ab1e6d6e6deb3fb3c817415d7f2fc` 后的候选补丁，以该提交为本轮父提交；初始审查基线仍为 `c65e3c55`。本节所记录的候选内容由包含本节的提交识别。原始完整审查范围与第一轮验证保留在上文。

本轮完整清单（含本审核记录）：47 个文件，+444/−79。

| 文件 | 新增 | 删除 |
|---|---:|---:|
| `app/src/androidTest/java/com/tyranor/next/ui/common/MotionActivityInitializationDeviceTest.kt` | 10 | 6 |
| `app/src/androidTest/java/com/tyranor/next/ui/common/MotionControlsDeviceTest.kt` | 13 | 15 |
| `app/src/androidTest/java/com/tyranor/next/ui/common/MotionDeviceAcceptance.kt` | 19 | 0 |
| `app/src/androidTest/java/com/tyranor/next/ui/common/MotionDialogDeviceTest.kt` | 4 | 11 |
| `app/src/androidTest/java/com/tyranor/next/ui/common/MotionEntranceDeviceTest.kt` | 4 | 9 |
| `app/src/androidTest/java/com/tyranor/next/ui/common/MotionLifecycleDeviceTest.kt` | 42 | 13 |
| `app/src/androidTest/java/com/tyranor/next/ui/common/MotionRollingDeviceTest.kt` | 6 | 10 |
| `app/src/androidTest/java/com/tyranor/next/ui/game/MotionCropDialogDeviceTest.kt` | 7 | 2 |
| `app/src/androidTest/java/com/tyranor/next/ui/game/MotionGameInteractionsDeviceTest.kt` | 5 | 10 |
| `app/src/main/java/com/tyranor/next/MainActivity.kt` | 1 | 0 |
| `app/src/main/java/com/tyranor/next/core/settings/AppSettingsStore.kt` | 3 | 0 |
| `app/src/main/java/com/tyranor/next/ui/archive/ArchiveUnpackActivity.kt` | 16 | 0 |
| `app/src/main/java/com/tyranor/next/ui/common/AppAlertDialog.kt` | 1 | 0 |
| `app/src/main/java/com/tyranor/next/ui/common/AppScreen.kt` | 2 | 0 |
| `app/src/main/java/com/tyranor/next/ui/common/AppSidePanel.kt` | 5 | 0 |
| `app/src/main/java/com/tyranor/next/ui/common/MotionSlider.kt` | 6 | 0 |
| `app/src/main/java/com/tyranor/next/ui/common/MotionSwitch.kt` | 6 | 0 |
| `app/src/main/java/com/tyranor/next/ui/common/SlidingTabRow.kt` | 2 | 0 |
| `app/src/main/java/com/tyranor/next/ui/common/motion/ContentMotion.kt` | 6 | 0 |
| `app/src/main/java/com/tyranor/next/ui/common/motion/Entrance.kt` | 12 | 0 |
| `app/src/main/java/com/tyranor/next/ui/common/motion/MotionMode.kt` | 2 | 0 |
| `app/src/main/java/com/tyranor/next/ui/common/motion/PageTransitions.kt` | 2 | 0 |
| `app/src/main/java/com/tyranor/next/ui/common/motion/PressSquish.kt` | 12 | 0 |
| `app/src/main/java/com/tyranor/next/ui/common/motion/SheetMotion.kt` | 8 | 0 |
| `app/src/main/java/com/tyranor/next/ui/cover/CoverScraperSettingsActivity.kt` | 8 | 0 |
| `app/src/main/java/com/tyranor/next/ui/engine/EngineScreen.kt` | 5 | 0 |
| `app/src/main/java/com/tyranor/next/ui/game/GameCoverFlow.kt` | 5 | 0 |
| `app/src/main/java/com/tyranor/next/ui/game/GameScreen.kt` | 32 | 0 |
| `app/src/main/java/com/tyranor/next/ui/game/GameShortcutCropDialog.kt` | 2 | 0 |
| `app/src/main/java/com/tyranor/next/ui/home/HomeScreen.kt` | 4 | 0 |
| `app/src/main/java/com/tyranor/next/ui/main/MainScreen.kt` | 11 | 3 |
| `app/src/main/java/com/tyranor/next/ui/patch/KrkrOnlinePatchActivity.kt` | 6 | 0 |
| `app/src/main/java/com/tyranor/next/ui/save/SaveManagementActivity.kt` | 4 | 0 |
| `app/src/main/java/com/tyranor/next/ui/save/SaveVersionsPanel.kt` | 8 | 0 |
| `app/src/main/java/com/tyranor/next/ui/save/WebDavSettingsActivity.kt` | 5 | 0 |
| `app/src/main/java/com/tyranor/next/ui/settings/EngineSettingsActivity.kt` | 1 | 0 |
| `app/src/main/java/com/tyranor/next/ui/settings/EthornellSettingsScreen.kt` | 4 | 0 |
| `app/src/main/java/com/tyranor/next/ui/settings/GamepadMapScreen.kt` | 1 | 0 |
| `app/src/main/java/com/tyranor/next/ui/settings/GraphicsChainEditor.kt` | 5 | 0 |
| `app/src/main/java/com/tyranor/next/ui/settings/GraphicsRefreshIntervalPreference.kt` | 2 | 0 |
| `app/src/main/java/com/tyranor/next/ui/settings/GraphicsSettingsEditor.kt` | 8 | 0 |
| `app/src/main/java/com/tyranor/next/ui/settings/InputSettingsScreen.kt` | 4 | 0 |
| `app/src/main/java/com/tyranor/next/ui/settings/PerGameSettingsActivity.kt` | 1 | 0 |
| `app/src/main/java/com/tyranor/next/ui/settings/PerGameSettingsScreen.kt` | 1 | 0 |
| `app/src/main/java/com/tyranor/next/ui/settings/RpgMakerSettingsCard.kt` | 4 | 0 |
| `app/src/main/java/com/tyranor/next/ui/settings/SettingsScreen.kt` | 10 | 0 |
| `docs/reviews/motion-v2-review.md` | 119 | 0 |

### 结论摘要

本轮三条新评论复核成立并已修复；五位未参与本轮实现的独立 agent 分别完成逻辑、架构、健壮性、回归、代码规范复审，未发现新的成立问题。唯一生产执行代码变化是页面启动失败时解除导航门控：异常原样传播，成功路径与既有动效时机不变。其余 36 个生产 Kotlin 文件仅补充 KDoc，已按保留字符串和嵌套注释的词法比较核对执行 token 不变。

本地 JVM 与 lint 仍有基线失败，不能写成全绿；真机本轮离线，新增设备用例仅完成编译，未执行本轮仪器测试和录屏。文档覆盖率已补充语义 KDoc，未降低阈值或修改检查配置，最终比例等待 CodeRabbit 在新提交上重算。

### 问题清单

| No. | 级别 | 问题与可核对证据 | 建议 / 处理 | 位置 |
|---|---|---|---|---|
| CR2-1 | 中 | `waitForIdleSync()` 不能保证异步启动的 Activity 已执行初始化，慢设备可在状态仍为增强时误报。[评论 4501378601](https://github.com/Weiss-UltimateSavior/Tyranor-Next/pull/150#discussion_r4501378601) | 已改为最多五秒轮询，主线程读状态，工作线程等待；超时仍失败。 | `app/src/androidTest/java/com/tyranor/next/ui/common/MotionActivityInitializationDeviceTest.kt:27` |
| CR2-2 | 低 | 八类 Motion 设备测试重复隔离包名、显式参数与模式设置，后续修改需同步多处。[评论 4501378606](https://github.com/Weiss-UltimateSavior/Tyranor-Next/pull/150#discussion_r4501378606) | 已抽到 androidTest 共享对象，保留每类原有模式初始化顺序。 | `app/src/androidTest/java/com/tyranor/next/ui/common/MotionDeviceAcceptance.kt:13` |
| CR2-3 | 中 | 原 `navigate()` 仅在 RuntimeException 时解锁，Error 或受检异常离开时会遗留门控。[评论 4501378609](https://github.com/Weiss-UltimateSavior/Tyranor-Next/pull/150#discussion_r4501378609) | 已用成功标志与 finally 清理；不捕获或替换异常。 | `app/src/main/java/com/tyranor/next/ui/main/MainScreen.kt:709` |

### 重点问题说明

**CR2-1.【中】Activity 初始化测试必须等待实际状态。** `startActivity()` 发送异步请求，消息队列的一次空闲不等于目标 Activity 已创建。新循环以单调时钟设截止时间，通过 `runOnMainSync` 读取状态，50ms 等待位于测试线程；两个无效输入 Activity、断言文本与 finally 中的偏好/快照恢复均保留。未创建或未同步档位时仍会失败。

**CR2-3.【中】失败清理覆盖所有启动异常。** 成功标志仅在 `start()` 正常返回后设置，finally 在失败离开时复位 `waiting`。成功后的 `afterPush`、generation 校验与取消逻辑不变。`app/src/androidTest/java/com/tyranor/next/ui/common/MotionLifecycleDeviceTest.kt:45` 新增 RuntimeException、AssertionError、IOException 的行为回归，验证异常对象同一性、失败不登记关闭回调、可重试以及成功重试仍等待既有回调。

### 其余问题与已核对部分

- CR2-2 的共享门控先检查精确隔离包与显式开关，再写模式。Activity 初始化用例传 `enhanced = null`，继续自行设置/恢复；裁剪测试也传 null，并保留通过 SDK 条件后才写指定档位的顺序。旧的六份 `requireMotionTarget()` 已删除。
- 复核旧评论 [4496842229](https://github.com/Weiss-UltimateSavior/Tyranor-Next/pull/150#discussion_r4496842229)：`app/src/main/java/com/tyranor/next/ui/common/motion/SheetMotion.kt:88` 为 `closing || navigating`，`close()` 在启动协程前同步置 closing；直接启动、选择启动文件与共享动作分别在 `app/src/main/java/com/tyranor/next/ui/game/GameScreen.kt:1068`、`app/src/main/java/com/tyranor/next/ui/game/GameScreen.kt:1086`、`app/src/main/java/com/tyranor/next/ui/game/GameScreen.kt:1221` 再次检查 busy。修复已在上一提交完成，其真实组件用例此前通过；本轮确认后将该线程标为 resolved，API 已回报 `isResolved=true`。
- CodeRabbit 上一轮文档告警为 40.00%，阈值 80.00%。本轮补充动作语义、模式分支、取消/复用、窗口释放与业务边界说明，覆盖公共动效、页面、设置及测试中的相关函数；本地不宣称得到远端相同比例。没有降低检查门槛，也没有新增覆盖率配置。
- 独立规范核对发现两处新增 KDoc 把关闭时机概括为必等侧推；已修正为普通档立即关闭、其余时机由 afterPush 决定，未因此改执行代码。
- 保护导航文件本轮无差异，`DefaultBottomNavigationBar` 至文件末尾与父提交逐字相同。透明零 inset 根 Miuix 浮层宿主、滑条只显示未覆盖刻度的明确例外、已撤销的白雾额外调色、现有弹簧/缓动/位移/模糊/节奏全部保持。

### 已执行验证

构建均使用隔离包名环境 `TYRANOR_APPLICATION_ID=com.tyranor.next.motionv2`，未操作官方包。

| 命令 / 检查 | 本轮结果 |
|---|---|
| `./gradlew.bat :app:compileReleaseKotlin` | 通过。 |
| `./gradlew.bat testDebugUnitTest --continue --no-daemon` | 退出 1；1,969 项、43 个失败，与已实际运行的 c65e3c55 基线失败集合完全相同（app 27 / engine 16），新增失败 0。本轮未重复运行基线 checkout，使用上文已保存的完整基线结果逐项比对。 |
| `./gradlew.bat :app:lintDebug --no-daemon` | 退出 1；29 Error / 335 Warning / 8 Hint，与父提交轮次相同，新增 Error 0。 |
| `python tools/check-hardcoded-ui-strings.py` | 通过。 |
| `./gradlew.bat :app:assembleRelease :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon` | 通过；新增设备测试及全部共享门控调用者编译通过。 |
| 本地 `run_navigation_failure_probe.py`（仅主机验收目录） | 直接加载本轮 Release 编译出的生产 MainPanelNavigation 类，RuntimeException / AssertionError / IOException 三种异常的原样传播、成功重试、延迟关闭与门控均通过，3/3；这是主机 JVM 检查，不是设备仪器测试。脚本和输出未加入仓库。 |
| 生产 Kotlin 执行 token 比对 | 36 个文件仅注释变化；MainScreen 唯一执行差异严格等于成功标志 + finally 替换。 |
| `git diff --check` / `git diff --cached --check` | 提交前通过；UTF-8 无 BOM，修改文件归一为 LF。 |
| `adb devices -l`、`adb connect <本地登记的测试设备端点>`、`adb mdns services` | 两个已登记端点均连接失败，设备 offline，mDNS 未发现可连接设备；端点仅存本机验收记录。 |

未执行：本轮新设备用例运行、真机录屏与逐帧对照、全主题/深浅色、平板/折叠屏/横屏、低端机/其它机型、全部引擎真实游戏/存档、真实封面下载/快捷方式、RTL 长标签矩阵、Perfetto/功耗和 baseline profile 性能收益。上文第一轮 22/22 与裁剪 2/2、30fps 录屏仅作为历史证据保留，不能算作本轮重跑。成功导航与所有动画执行 token 未变，因此本轮未调整视觉路径来替代缺失的真机验证。

清理边界：本轮没有在手机启动脚本、screenrecord、perfetto 或 instrumentation，也没有发送任何 input tap/swipe；手机离线，未能再次核对全机进程。电脑上的本轮构建与验收脚本已结束。原始日志、设备地址、媒体、构建包、认证资料及本地验收脚本未纳入提交；未更改版本或受保护导航。

### 修复记录

| 项目 | 处理结果 | 文件与行号 | 验证 |
|---|---|---|---|
| CR2-1 | 已修复；本轮真机复跑未执行（设备离线） | `app/src/androidTest/java/com/tyranor/next/ui/common/MotionActivityInitializationDeviceTest.kt:27` | Release/Debug/AndroidTest 编译与五视角源码复审通过；finally 恢复保留。 |
| CR2-2 | 已修复 | `app/src/androidTest/java/com/tyranor/next/ui/common/MotionDeviceAcceptance.kt:13` 及八类 Motion 测试调用 | AndroidTest APK 构建通过，包名/参数只保留一个定义。 |
| CR2-3 | 已修复 | `app/src/main/java/com/tyranor/next/ui/main/MainScreen.kt:709`；`app/src/androidTest/java/com/tyranor/next/ui/common/MotionLifecycleDeviceTest.kt:45` | 实际生产 Release 类主机异常恢复 3/3；设备新用例编译通过、运行未执行。 |
| 旧抽屉线程 | 上一轮已修复，本轮复核并标记 resolved | `app/src/main/java/com/tyranor/next/ui/game/GameScreen.kt:1221` | 源码门控与上一轮真实组件用例一致；GitHub API 已确认 resolved。 |
| 文档覆盖率告警 | 已补 KDoc，远端百分比待新提交重算 | 本节完整范围中的生产/设备测试 Kotlin 文件 | 词法比较与编译通过；未更改门禁阈值。 |
| 新增文档时机概括 | 已修正（低风险注释修改） | `app/src/main/java/com/tyranor/next/ui/main/MainScreen.kt:240`；`app/src/main/java/com/tyranor/next/ui/main/MainScreen.kt:704` | 新独立规范复审确认与实现一致。 |

---

## 上游冲突整合与第三轮修复（2026-11-07）

### 按 AGENTS.md 的审核结果

审查范围：PR #150 第一父提交 `da58c7d0ba6704847110df041d221660c43b6241`，合入上游 main `25134650cbd15836ea3ac272b3bb409c7fbe0b66`，保留双亲合并记录，不重写已发布历史。候选内容由包含本节的提交识别。最初 c65e3c55 完整范围与前两轮记录保持不变。

相对第一父提交的完整清单（包括导入上游及本节记录）：45 个文件，文本 +2503/−803；另有六张上游截图为二进制文件。上游代码与图片属于本轮合并输入。

| 文件 | 新增 | 删除 |
|---|---:|---:|
| `AGENTS.md` | 3 | 3 |
| `ARCHITECTURE.md` | 4 | 4 |
| `CHANGELOG.md` | 7 | 0 |
| `CONTEXT.md` | 8 | 0 |
| `README.md` | 26 | 27 |
| `SPEC.md` | 12 | 2 |
| `app/build.gradle.kts` | 1 | 1 |
| `app/src/androidTest/java/com/tyranor/next/ui/common/MotionLifecycleDeviceTest.kt` | 35 | 0 |
| `app/src/androidTest/java/com/tyranor/next/ui/game/MotionCoverSearchDialogDeviceTest.kt` | 155 | 0 |
| `app/src/androidTest/java/com/tyranor/next/ui/game/MotionGameInteractionsDeviceTest.kt` | 37 | 10 |
| `app/src/main/java/com/tyranor/next/MainActivity.kt` | 109 | 13 |
| `app/src/main/java/com/tyranor/next/Navigation.kt` | 12 | 3 |
| `app/src/main/java/com/tyranor/next/TyranorNextApplication.kt` | 26 | 3 |
| `app/src/main/java/com/tyranor/next/core/game/save/sync/SaveHostProbe.kt` | 10 | 0 |
| `app/src/main/java/com/tyranor/next/core/game/save/sync/SaveSyncRuntime.kt` | 11 | 3 |
| `app/src/main/java/com/tyranor/next/core/game/storage/GameLibraryDatabase.kt` | 2 | 1 |
| `app/src/main/java/com/tyranor/next/core/game/storage/GameLibraryRepository.kt` | 4 | 16 |
| `app/src/main/java/com/tyranor/next/core/process/AppProcess.kt` | 27 | 0 |
| `app/src/main/java/com/tyranor/next/core/settings/AppSettingsStore.kt` | 56 | 1 |
| `app/src/main/java/com/tyranor/next/core/settings/AppStorageNames.kt` | 10 | 0 |
| `app/src/main/java/com/tyranor/next/core/settings/OnboardingPreferences.kt` | 106 | 0 |
| `app/src/main/java/com/tyranor/next/core/settings/PrefsRenameMigration.kt` | 1 | 1 |
| `app/src/main/java/com/tyranor/next/theme/Color.kt` | 3 | 0 |
| `app/src/main/java/com/tyranor/next/ui/common/OnboardingCoachAnchor.kt` | 20 | 0 |
| `app/src/main/java/com/tyranor/next/ui/engine/EngineScreen.kt` | 23 | 1 |
| `app/src/main/java/com/tyranor/next/ui/game/GameCoverFlow.kt` | 24 | 21 |
| `app/src/main/java/com/tyranor/next/ui/game/GameQuickActionsDialog.kt` | 349 | 0 |
| `app/src/main/java/com/tyranor/next/ui/game/GameScreen.kt` | 156 | 494 |
| `app/src/main/java/com/tyranor/next/ui/home/HomeScreen.kt` | 51 | 169 |
| `app/src/main/java/com/tyranor/next/ui/main/MainScreen.kt` | 65 | 24 |
| `app/src/main/java/com/tyranor/next/ui/onboarding/OnboardingScreen.kt` | 398 | 0 |
| `app/src/main/java/com/tyranor/next/ui/settings/SettingsScreen.kt` | 22 | 3 |
| `app/src/main/res/values-en/strings.xml` | 23 | 1 |
| `app/src/main/res/values-ja/strings.xml` | 23 | 1 |
| `app/src/main/res/values/strings.xml` | 23 | 1 |
| `app/src/test/java/com/tyranor/next/core/game/save/sync/SaveHostProbeTest.kt` | 20 | 0 |
| `app/src/test/java/com/tyranor/next/core/settings/OnboardingPreferencesTest.kt` | 278 | 0 |
| `docs/SoftPAL-sena-rs引擎接入方案.md` | 237 | 0 |
| `docs/reviews/motion-v2-review.md` | 126 | 0 |
| `screenshots/index1.png` | - | - |
| `screenshots/index2.png` | - | - |
| `screenshots/index3.png` | - | - |
| `screenshots/index4.png` | - | - |
| `screenshots/index5.png` | - | - |
| `screenshots/index6.png` | - | - |

### 结论摘要

八个真实内容冲突均已逐处整合，本地冲突索引为空；提交后须再核对 GitHub 状态。上游首次使用引导、长按管理弹窗、单点启动抽屉及最新布局均保留，同时继续接入本 PR 的两档动效、抽屉自绘运动、按压/入场、根 Miuix 浮层宿主和动作门控。抽屉单行动作、固定启动按钮及紧凑把手是上游产品变更，不能将合并前后静止布局宣称为像素一致；本轮没有调节既有增强动效曲线或参数。

CodeRabbit 最新 [review 5812153375](https://github.com/Weiss-UltimateSavior/Tyranor-Next/pull/150#pullrequestreview-5812153375) 的封面搜索平台动画问题成立并已修复。五个未参与实现的独立 agent 分别从逻辑、架构、健壮性、回归、规范复审；发现一项引导自动切页未重播入场的合并遗漏，修复后再次由独立逻辑及其余视角复核，未发现新的成立问题。

最终 Release / Debug / AndroidTest 构建通过，Windows JVM 1,984 项的失败集合仍为既有 43 项，新增失败 0。真机未连接，本轮设备运行和录屏对照未执行；不以编译、旧录屏代替本轮设备验收。

### 问题清单

| No. | 级别 | 问题与证据 | 建议 / 处理 | 位置 |
|---|---|---|---|---|
| CR3-1 | 高 | GitHub 为 CONFLICTING / DIRTY，本地合并确认 CHANGELOG 与七个 Kotlin 文件内容冲突。 | 已逐处整合两侧逻辑，最终冲突索引为空。 | 本节完整清单与下方说明 |
| CR3-2 | 中 | 封面搜索使用默认 syncDialogWindow，普通档平台进退场被清除；review 5812153375 的 outside-diff 评论指向旧 GameScreen:1599。 | 仅普通档保留平台动画；关闭和 dispose 同步清 dim 与触摸，按捕获窗口清理。 | `app/src/main/java/com/tyranor/next/ui/game/GameScreen.kt:1254` |
| CR3-3 | 中 | 初版整合的 coachPage effect 只改 selectedIndex，未走点击的重播路径；无 key remember 不会重新执行 playOnStart，未访问页 generation 为 0。 | 引导与点击共用 changeSelectedPage，保留离屏和最小间隔门控。 | `app/src/main/java/com/tyranor/next/ui/main/MainScreen.kt:377` |

### 重点问题说明

**CR3-1.【高】冲突整合保留两侧功能。**

- MainActivity 的 ProvideMotionEnvironment 与页面压暗包裹上游 AppEntry，引导层和主导航使用同一份组合；初始化判定与通知权限时机保留上游行为。
- MainScreen 的 activeIndex 同时驱动引导、转场和导航。普通引导保持上游 80ms，普通常规页保持 200/160ms；增强档仍用原弹簧。页面完全停稳后启用引导锚点，增强尾段交互和 Web 首页采样锁存保留。
- Home / Game / Engine / Settings 的引导回调、isActive、锚点清理接回；保留异步安装态查询和令牌保护、入场、按压。清除自动合并产生的重复 modifier 参数与实参。
- 游戏管理动作按上游迁至长按 GameQuickActionsDialog；单点抽屉仍由 MotionSheetController 管理。运动 surface、跟手下拉、生命周期交接、14dp / 16ms 入场参数与 busy 守卫保留。管理操作没有消失，也没有重复放回抽屉。
- GameCoverFlow 使用上游 combinedClickable 的最新回调与无障碍语义，保留原增强吸附、侧卡居中和按压图层。
- DefaultBottomNavigationBar 与两个父提交逐字相同，其余保护导航路径相对两侧均无差异；滑条、开关、MotionTokens / MotionSprings / SheetMotion / PressSquish 与第一父提交相同。根透明、零 inset Miuix 浮层宿主保持，白雾额外刻度调色未恢复。
- app/build.gradle.kts 与合入 main 逐字相同；版本 1.64 来自上游 e71c0bd5，本轮没有额外修改版本或构建配置。

**CR3-2.【中】封面搜索普通档窗口退场。** `app/src/main/java/com/tyranor/next/ui/game/GameScreen.kt:1149` 在父级关闭前解除触摸并清零 dim；syncDialogWindow 仅普通档保留平台动画，增强档维持旧策略。DisposableEffect 捕获实际 window，旧窗口 dispose 不会通过共享数组误清新窗口。绑定成功仍经 beforeClose 清理，失败保留界面、取消原样传播。新增 `app/src/androidTest/java/com/tyranor/next/ui/game/MotionCoverSearchDialogDeviceTest.kt:42` 两档真实 UI 设备用例，对比相同 fullscreen properties 的平台参考，再检查显式关闭和外部移除；不执行搜索、下载或游戏启动。本轮仅编译，运行未执行。

**CR3-3.【中】引导自动切页接回原入场。** 独立逻辑复审由 EntranceState 无 key remember、generation 初值与 isFresh 条件确认遗漏。最终引导 effect 只在实际索引变化时调用共享路径，同一游戏页内推进多步不会重播；用户点击保留原索引、普通档守卫、离屏和最小间隔判定。`app/src/androidTest/java/com/tyranor/next/ui/common/MotionLifecycleDeviceTest.kt:201` 新用例在四倍 Compose 缩放下用 512ms 区分普通引导短转场和常规转场；本轮编译通过、设备运行未执行。

### 其余问题与已核对部分

- **待确认，低级建议：PressSquish onDetach 取消任务。** CodeRabbit 将其标为 Unverified。本轮核对实际 Compose UI Android 1.11.2：Modifier.kt:178–179 保证 onDetach 后取消节点 scope；:284–297 先设置 isAttached=false 再取消整个 scope；NodeChain.kt:521–529 在同一同步栈执行 runDetachLifecycle → markAsDetached，无挂起点。委托 pointer 节点取消时的 finally 可调用 release，但其任务仍归父 scope，随后被整体取消，没有找到旧 scope 跨重挂运行的证据。保持执行代码，不把未证实建议写成已修复缺陷。来源：[Google Maven 官方 sources](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-android/1.11.2/ui-android-1.11.2-sources.jar)，SHA-256 `aafb071a46db33fc841234f58061c5bc543ddaa2c8e56247409cee8e81a7b6d2`；依赖源码只留本机。
- 上一提交 da58c7d0 的 CodeRabbit 文档覆盖率实测 **87.50%**（136 函数 / 69 文件，阈值 80%），已通过。本轮新提交仍待重算，未降低阈值。

### 已执行验证

构建环境为隔离包名 `TYRANOR_APPLICATION_ID=com.tyranor.next.motionv2`。

| 命令 / 检查 | 结果 |
|---|---|
| `git fetch origin main`；`git merge --no-commit --no-ff origin/main` | 合入上游 25134650；确认八个冲突文件后逐处解决。提交前再次 fetch，main 未变化。 |
| `git ls-files --unmerged`；`git diff --cached --check` | 最终均无输出，冲突索引与格式检查通过；解决文件 UTF-8 无 BOM、LF。 |
| `./gradlew.bat :app:compileReleaseKotlin` | 通过；最后完整打包再次编译通过。 |
| `./gradlew.bat testDebugUnitTest --continue --no-daemon` | 冻结最终源码后复跑：1,984 项、43 失败（app 27 / engine 16），失败 ID 与实际跑过的 c65e3c55 基线及 da58c7d0 相同，新增失败 0；新增 15 项上游测试计入总数。本轮未重复运行基线 checkout。 |
| `./gradlew.bat :app:lintDebug --no-daemon` | 最终退出 1；29 Error / 337 Warning / 10 Hint。Error 集合无新增；增加提示来自上游引导/管理弹窗代码，不能写成全绿。 |
| `python tools/check-hardcoded-ui-strings.py` | 通过，三语言自动合并结果一致。 |
| `./gradlew.bat :app:assembleRelease :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon` | 初轮失败：lintVitalAnalyzeRelease 内部异常，报 MainScreen 的 FIR 属性声明缺失，未记作通过。 |
| `./gradlew.bat :app:compileReleaseKotlin :app:assembleRelease :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon` | 冻结源码后直接重跑通过，包含 release lintVitalAnalyzeRelease；没有禁用 lint、加 suppression 或跳过任务。新增及迁移设备用例编译通过。 |
| 两侧源码核对 | 保护导航、DefaultBottomNavigationBar 与两侧一致；滑条/开关/动效 token/控制器/PressSquish 与第一父提交一致；版本/构建脚本与上游一致。 |
| `adb devices -l`；`adb mdns services` | 无已连接设备，未发现可用 mDNS 目标。 |

未执行：本轮新普通引导转场、抽屉导航防重和两档封面窗口设备用例运行；真机录屏逐帧对照；引导全流程与长按管理链路实际操作；所有主题/深浅色、平板/折叠屏/横屏、低端机/其它机型、真实引擎游戏/存档/封面下载/快捷方式、Perfetto/功耗/baseline profile 性能收益。旧 22/22、裁剪 2/2 和录屏仅为历史证据，不算本轮通过。

本轮未启动手机脚本、screenrecord、perfetto 或 instrumentation，未发送 input tap/swipe，未操作官方包或修改系统设置。手机未连接，不能再次核对全机进程；本轮电脑验收脚本结束后停止自身监看进程。原始日志、依赖源码、私有路径/端点、媒体和构建包不纳入提交。

### 修复记录

| 项目 | 处理结果 | 文件与行号 | 验证 |
|---|---|---|---|
| CR3-1 冲突 | 已解决，保留双亲历史 | 八个冲突文件见本节清单 | 无冲突索引，最终编译/三类 APK 通过；提交后再核对 GitHub 状态。 |
| CR3-2 封面窗口 | 已修复，真机未执行 | `app/src/main/java/com/tyranor/next/ui/game/GameScreen.kt:1149`、`app/src/main/java/com/tyranor/next/ui/game/GameScreen.kt:1254`；`app/src/androidTest/java/com/tyranor/next/ui/game/MotionCoverSearchDialogDeviceTest.kt:42` | 两档真实 UI 用例编译通过，运行/录屏未执行。 |
| CR3-3 引导入场 | 已修复 | `app/src/main/java/com/tyranor/next/ui/main/MainScreen.kt:377`；`app/src/androidTest/java/com/tyranor/next/ui/common/MotionLifecycleDeviceTest.kt:201` | 独立复核确认修复成立；最终编译通过。 |
| PressSquish 建议 | 待确认，本轮不改 | 原 PressSquish.kt 与实际 Compose 1.11.2 卸载调用栈 | 未找到支持竞态的证据，保留现有生命周期实现。 |
| 抽屉测试迁移 | 已适配上游接口并补导航防重 | `app/src/androidTest/java/com/tyranor/next/ui/game/MotionGameInteractionsDeviceTest.kt:28`、`app/src/androidTest/java/com/tyranor/next/ui/game/MotionGameInteractionsDeviceTest.kt:66` | AndroidTest 构建通过，设备运行未执行。 |


---

## 第四轮 CodeRabbit 修复（2026-11-07）

### 按 AGENTS.md 的审核结果

审查范围：PR #150，父提交 `f88bc932af30df3cbf6d207fbeacb2eeebd8176f`，本节候选由包含本节的提交识别；原始审查基线仍为 c65e3c55。范围包含本轮全部已跟踪修改、三个未跟踪源码/测试文件及本节记录，21 个文件、+600/−71。不把日志或构建产物算作源码范围。

| 文件 | 新增 | 删除 |
|---|---:|---:|
| `CHANGELOG.md` | 1 | 0 |
| `SPEC.md` | 2 | 2 |
| `app/src/androidTest/java/com/tyranor/next/ui/common/MotionActivityInitializationDeviceTest.kt` | 25 | 1 |
| `app/src/androidTest/java/com/tyranor/next/ui/home/MotionHomeRestorationDeviceTest.kt` | 73 | 0 |
| `app/src/main/java/com/tyranor/next/MainActivity.kt` | 32 | 5 |
| `app/src/main/java/com/tyranor/next/TyranorNextApplication.kt` | 6 | 3 |
| `app/src/main/java/com/tyranor/next/core/game/storage/GameLibraryDatabase.kt` | 11 | 2 |
| `app/src/main/java/com/tyranor/next/core/game/storage/GameLibraryFacade.kt` | 31 | 8 |
| `app/src/main/java/com/tyranor/next/core/settings/AppSettingsStore.kt` | 14 | 1 |
| `app/src/main/java/com/tyranor/next/core/settings/OnboardingPreferences.kt` | 22 | 6 |
| `app/src/main/java/com/tyranor/next/core/settings/PrefsRenameMigration.kt` | 1 | 0 |
| `app/src/main/java/com/tyranor/next/ui/engine/EngineScreen.kt` | 6 | 2 |
| `app/src/main/java/com/tyranor/next/ui/game/GameLaunchPreparation.kt` | 29 | 0 |
| `app/src/main/java/com/tyranor/next/ui/game/GameScreen.kt` | 55 | 35 |
| `app/src/main/java/com/tyranor/next/ui/home/HomeScreen.kt` | 2 | 2 |
| `app/src/main/res/values-en/strings.xml` | 1 | 1 |
| `app/src/main/res/values-ja/strings.xml` | 1 | 1 |
| `app/src/main/res/values/strings.xml` | 1 | 1 |
| `app/src/test/java/com/tyranor/next/core/settings/OnboardingPreferencesTest.kt` | 102 | 1 |
| `app/src/test/java/com/tyranor/next/ui/game/GameLaunchPreparationTest.kt` | 89 | 0 |
| `docs/reviews/motion-v2-review.md` | 96 | 0 |

已执行验证：

- `./gradlew.bat :app:compileReleaseKotlin` → 通过。
- `./gradlew.bat testDebugUnitTest --continue --no-daemon` → 退出 1；1,991 项、43 失败（app 27 / engine 16）、22 跳过，失败 ID 与已实际跑过的 c65e3c55 基线及父轮完全相同，新增失败 0。本轮没有重复运行基线 checkout。
- `./gradlew.bat :app:lintDebug --no-daemon` → 退出 1；29 Error / 337 Warning / 10 Hint，Error 签名与基线无新增；不记为 lint 通过。
- `python tools/check-hardcoded-ui-strings.py` → 通过。
- `./gradlew.bat :app:assembleRelease :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon` → 通过；新增 Home 恢复和权限 contract 用例编译通过。全部构建使用隔离包名 `TYRANOR_APPLICATION_ID=com.tyranor.next.motionv2`。
- `git -c core.autocrlf=false diff --check` → 通过；本轮修改均为 UTF-8 无 BOM、LF。
- 42 个保护/动效/构建文件与父提交逐字比较 → 无差异，包含整个 MainScreen（因此 DefaultBottomNavigationBar 与根浮层宿主均保持）、滑条/开关、motion 工具集、anim/interpolator、baseline profile、版本及构建脚本。
- `android skills list` → 已发现可用技能并读取 android-cli 的设备交互说明；本机 CLI 不支持文档中的 `android skills search review`，没有据此宣称额外审查技能已安装或运行。`adb devices -l` 初次为空，随后 mDNS 发现并连接 Honor PPG-AN00 / API 37；`dumpsys window` 确认前台不是隔离包后，按用户规则立即停止设备操作。
- 未执行：本轮设备用例运行、真机录屏逐帧对照、实际权限 UI 中断/旋转/进程重建、完整 SQLite 恢复及真实磁盘故障、全主题/深浅色、平板/折叠屏/横屏、低端机、真实游戏/引擎启动与存档、真实封面绑定、Perfetto/功耗/baseline profile 收益。未抢占正在使用的手机；旧设备结果和旧录屏不算本轮通过。

### 结论摘要

已逐条复核 [CodeRabbit review 5816724208](https://github.com/Weiss-UltimateSavior/Tyranor-Next/pull/150#pullrequestreview-5816724208) 的三条 inline、五条 outside-diff 与一条 nit。七项成立的产品/文案问题已修复，一项锚点清理防御改进已完成，一项低级封面绑定成功路径的设备覆盖建议保留未实现。没有通过扩大生产测试接口来掩盖测试缺口。

五个未参与实现的独立 agent 分别从逻辑、架构、健壮性、回归和规范复审冻结后的全部修复，各自仅向主 agent 报告；主 agent 回到源码逐项核对，未发现新的成立问题。此轮没有调节增强动效视觉、轨迹、时长、弹簧或静态布局；普通档沿用同一缺陷修复。滑条明确例外保持，未恢复白雾刻度调色。设备证据仍缺失，因此不宣称本轮视觉逐帧验收通过。

父提交在本轮开始时六项远端检查均成功，CodeRabbit 文档覆盖率为 86.67%（135 函数 / 70 文件，阈值 80%）；这不是本轮新提交的检查结果。检查期间上游 main 新增 ccf010a3、4fa21e8c，PR 再次出现冲突，需继续整合；本节不作为当前可合并的声明。

### 问题清单

| No. | 级别 | 问题与可核对证据 | 建议 / 处理 | 位置 |
|---|---|---|---|---|
| CR4-1 | 高 | 插件原始线程直接调用 provisionIfNeeded；插件版本 getInt 位于其内部异常保护之前，类型损坏偏好会向线程外抛 ClassCastException。 | 已在原线程捕获并记录 Exception，首次安装 gate 也置于同一异常边界内。 | `app/src/main/java/com/tyranor/next/MainActivity.kt:106`；`app/src/main/java/com/tyranor/next/core/engine/plugin/EnginePluginBootstrap.kt:127` |
| CR4-2 | 中 | beginLaunch 原来在异步准备结束前没有同步所有权，sheetMotion.busy 仅在导航阶段变为真，重复激活可安排两条准备链。 | 已在安排协程前取得一次所有权，跨确认/存档检查交接，异常、取消或最终交付后释放。 | `app/src/main/java/com/tyranor/next/ui/game/GameScreen.kt:744`；`app/src/main/java/com/tyranor/next/ui/game/GameLaunchPreparation.kt:8` |
| CR4-3 | 中 | 初始化标记与独立快照均失败后，自动迁移/空 Room 库仍能写出“既有安装”证据，重启丢失首次安装判定。 | 已先保存原始判定；双失败暂缓迁移与首次建库，冷读返回不缓存空值并重试；旧安装凭已有证据继续。 | `app/src/main/java/com/tyranor/next/core/settings/AppSettingsStore.kt:52`；`app/src/main/java/com/tyranor/next/core/settings/OnboardingPreferences.kt:27`；`app/src/main/java/com/tyranor/next/core/game/storage/GameLibraryDatabase.kt:64` |
| CR4-4 | 中 | 通知权限原来在实际结果前写入已请求，系统中断也会永久失去后续请求机会。 | 已以结果 map 中包含权限项区分明确授权/拒绝与中断，用无 Context 的 ViewModel 保留旋转期间 inFlight。 | `app/src/main/java/com/tyranor/next/MainActivity.kt:52`；`app/src/main/java/com/tyranor/next/MainActivity.kt:156` |
| CR4-5 | 中 | 恢复后游戏库尚未加载，Home 的空列表 effect 立即清除 rememberSaveable 管理目标 URI。 | 已把 loaded 加入 effect，仅加载完成后清理不存在的目标。 | `app/src/main/java/com/tyranor/next/ui/home/HomeScreen.kt:133` |
| CR4-6 | 中 | Engine 行 coachAnchor 在入场变换外侧读取坐标，重建时引导框不能随行入场。 | 已把原 staggeredEntrance 放到 coachAnchor 之前，不调整运动参数或启用条件。 | `app/src/main/java/com/tyranor/next/ui/engine/EngineScreen.kt:310` |
| CR4-7 | 低 | 高亮“添加游戏”实际用于手动添加 PC/安卓，引导却声称可添加扫描目录。 | 已同步三语言，目录扫描指向添加文件夹/设置入口。 | `app/src/main/res/values/strings.xml:923`；同键 values-en、values-ja |
| CR4-8 | 低，防御改进 | 旧首行 DisposableEffect 无条件发送 Rect.Zero，缺少目标身份限定；设备上新旧回调的竞态帧序未复现。 | 已仅允许当前目标或无目标时清理，避免旧行清除新拥有者边界；不把未复现帧序写成已证实缺陷。 | `app/src/main/java/com/tyranor/next/ui/engine/EngineScreen.kt:269` |
| CR4-9 | 低，测试增强 | 封面窗口设备用例仅覆盖显式关闭/外部移除，缺少成功绑定时在 onGameUpdated 内、dispose 前检查清理的真实 UI 断言。 | 未实现；保留当前测试和讨论，原因见下文。 | `app/src/androidTest/java/com/tyranor/next/ui/game/MotionCoverSearchDialogDeviceTest.kt:122`；`app/src/main/java/com/tyranor/next/ui/game/GameScreen.kt:1064` |

### 重点问题说明

**CR4-2.【中】启动准备所有权。** GameLaunchPreparation 只负责 UI 交互编排，主线程同步 tryBegin 发生在 scope.launch 之前；成功交接仍持有，step 的 finally 仅在异常/取消时释放，真正启动的 finally 负责最终释放。Artemis 确认先同步消费 showPatchConfirm，取消释放；恢复的确认可以取得新内存所有权。RPG 对话框“否”仍为不转换而直接启动，未改为取消。原 EngineLauncher、sheet 导航门控和进退场绘制均保留。新增四个 JVM 行为测试全部通过，包括调度前/挂起中防重、阶段交接、原 Throwable 传播和取消恢复。

**CR4-3.【中】首次安装双失败。** OnboardingPreferences 保留未持久化原始判定，失败 commit 留下的内存 marker 不能覆盖该值；独立快照、可靠标记或既有安装数据成立时才允许基础设施写入。Application 自动迁移、PrefsRenameMigration、System4 标记、插件及 Room builder 入口共用 prepareInstallationData。GameLibraryFacade 四个冷读入口返回空而不写缓存，保留失败监听和下次重试，正常已有缓存路径不增加磁盘访问。三个新增测试通过实际 Facade/Room 前置入口验证双失败、恢复先保存 false 再迁移、模拟重启、快照快路径及完成失败后保留原判定；OnboardingPreferencesTest 共 17 项通过。真实 Room 建库成功和跨进程磁盘故障未执行。

**CR4-9.【低】成功绑定设备覆盖仍有缺口。** 生产成功路径已逐行确认 `GameScreen.kt:1064` beforeClose() 紧接在 `:1065` onGameUpdated(updated) 之前。实际候选只能来自固定的外部 VNDB/Bangumi/Steam HTTPS 服务，没有现成的本地可控候选/下载夹具；当前对话框保持私有。补全真实成功路径需外部网络或新增生产注入/接口，不属于本次小而低风险的测试改动。本轮不改生产业务边界、不用假的关闭流程冒充绑定成功、不将该讨论标为已修复。后续可在有可控服务夹具时补真实成功绑定断言。

### 已核对无问题的部分

- core 仍不依赖 Compose/UI；临时权限状态无 Activity/Context 引用，未引入新的数据库 schema 或错误协议。
- 未发现 Facade 缓存锁、数据库单例锁与 onboarding monitor 的反向锁链；异常与协程取消保留原始传播。
- Home 新设备用例调用真实 HomeScreen 和 StateRestorationTester，权限用例调用实际 AndroidX parseResult；均使用共享隔离包名门控，没有生产测试后门。仅编译通过，不当作设备运行通过。
- 本轮未启动手机脚本、screenrecord、perfetto 或 instrumentation，未发送 input tap/swipe、操作官方包或修改系统设置。发现非隔离包前台后停止设备操作，未核对全机进程；本轮没有自身设备进程需要回收。原始日志、私有路径/端点、媒体、依赖源码和构建包不入提交。

### 修复记录

| 项目 | 处理结果 | 改动文件与行号 | 验证 |
|---|---|---|---|
| CR4-1 插件线程异常 | 已修复 | `MainActivity.kt:106` | Release/Debug 编译与五视角源码复审；真实损坏偏好启动未执行。 |
| CR4-2 启动准备防重 | 已修复 | `ui/game/GameLaunchPreparation.kt:8`；`ui/game/GameScreen.kt:712`、`:744`、`:758`、`:1003` | 新增四项 JVM 全通过；真实引擎设备启动未执行。 |
| CR4-3 首次安装双失败 | 已修复 | `core/settings/AppSettingsStore.kt:52`、`:354`；`OnboardingPreferences.kt:27`、`:53`、`:68`；`PrefsRenameMigration.kt:26`；`TyranorNextApplication.kt:52`；`core/game/storage/GameLibraryDatabase.kt:64`；`GameLibraryFacade.kt:82`、`:106`、`:170`、`:209`、`:331` | OnboardingPreferencesTest 17/17；全 JVM 无新增失败，完整设备 SQLite 恢复未执行。 |
| CR4-4 权限中断 | 已修复 | `MainActivity.kt:52`、`:156`、`:197`；`ui/common/MotionActivityInitializationDeviceTest.kt:23` | contract 用例编译通过，真实权限 UI/旋转未执行。 |
| CR4-5 Home 恢复 | 已修复 | `ui/home/HomeScreen.kt:133`；`ui/home/MotionHomeRestorationDeviceTest.kt:29`（androidTest） | 新真实组件恢复用例编译通过，设备运行未执行。 |
| CR4-6 锚点跟随 | 已修复 | `ui/engine/EngineScreen.kt:310` | Modifier 调用顺序及五视角复审通过；设备帧对照未执行。 |
| CR4-7 引导文案 | 已修复 | 三语言 `strings.xml:923` | 字符串脚本通过。 |
| CR4-8 清理身份 | 已完成防御改进，原竞态帧序待确认 | `ui/engine/EngineScreen.kt:237`、`:269` | 当前拥有者条件源码复核；设备竞态未复现。 |
| CR4-9 成功绑定测试 | 未实现，非阻塞测试增强 | `ui/game/MotionCoverSearchDialogDeviceTest.kt:122`（androidTest）；`ui/game/GameScreen.kt:1064` | 成功路径清理顺序源码确认；真实成功绑定与回调内设备断言未执行。 |
