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
