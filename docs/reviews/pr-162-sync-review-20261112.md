## 按 AGENTS.md 的审核结果

审查范围：PR #162 代码 head `cc5912f9a61aa1b566b3495226c21943bff148c5`（基线 `main` / `e04f9a155bab64f4d88de933dc56743c4c8c94ea`），195 个文件、+33624/−2615。本报告为随后独立提交的审核文档，原 PR head 为 `c3d30797`。

本轮分别由五个独立子 Agent 承担代码逻辑、架构、健壮性、回归风险和规范审核；主 Agent 复核证据、处理交叉集成并统一构建、设备验收。审核依据为当前 `AGENTS.md`、`SPEC.md`、`CONTEXT.md` 与 `PR-REVIEW-TEMPLATE.md`。旧版本的设备记录不计为本次合并代码的验收。

### 结论摘要

确认 9 项问题，均已修复，本地专项验证未发现未修复的阻断项；建议 PR 当前 head 的远端 CI 全部成功后合并。最高优先级问题是启动器 alias 与实际 applicationId 不匹配，隔离安装曾在 Application 初始化时崩溃；修复后已通过实际启动、两向切换和恢复验证。Windows 全量 JVM 仍有 43 项失败，不能声称本机全量测试通过；CI 当前结果以 [PR Checks](https://github.com/Weiss-UltimateSavior/Tyranor-Next/pull/162/checks) 为准。

### 已执行验证

- `python tools/check-hardcoded-ui-strings.py` → 通过。
- `python tools/check-motion-boundaries.py` → 346 个 Kotlin 文件、0 违规；`python -B tools/test_motion_boundaries.py` → 9/9 通过。
- PowerShell 设置 `$env:TYRANOR_APPLICATION_ID = 'com.tyranor.next.motionv2'` 后执行 `./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --offline --no-daemon --max-workers=4` → 成功；随后测试源码修正的 `:app:assembleDebugAndroidTest` 也成功。
- `./gradlew.bat :app:testDebugUnitTest --tests 'com.tyranor.next.ui.*' --offline --no-daemon --max-workers=4` → 162/162 通过，包含新增关闭守卫与拒绝越阈拖动回弹的 JVM 回归。
- `./gradlew.bat testDebugUnitTest --continue --offline --no-daemon --no-configuration-cache --max-workers=4` → 共 2174 项：2109 通过、43 失败、22 跳过。app 为 1535 项、27 失败、18 跳过；engine 为 639 项、16 失败、4 跳过。失败集中在 Ruffle/Ethornell/Artemis/System4 的 Windows 路径与符号链接用例，相关实现和测试相对 `origin/main` 无改动。没有把这些失败改掉或当作通过。
- `./gradlew.bat :app:lintReportDebug :engine:lintReportDebug --offline --no-daemon` → 报告生成，app 0 错误、engine 54 错误；`python tools/check-lint-diff.py --base origin/main app/build/reports/lint-results-debug.xml engine/build/reports/lint-results-debug.xml` → 变更行 0 错误。
- `./gradlew.bat :engine:jsHarness --offline --no-daemon` → 全部通过。
- 真机 `PPG_AN00` / API 37，隔离包 `com.tyranor.next.motionv2`：`adb shell am instrument -w -r -e class com.tyranor.next.ui.common.AppBottomSheetDeviceTest,com.tyranor.next.ui.main.HomePlaytimeActivationDeviceTest,com.tyranor.next.ui.common.OrdinarySearchBarMotionDeviceTest,com.tyranor.next.ui.common.MotionActivityInitializationDeviceTest -e motion_device_acceptance true com.tyranor.next.motionv2.test/androidx.test.runner.AndroidJUnitRunner` → 10/10 通过。
- 同一设备执行 `MotionNoticeActivityDeviceTest,MainScreenBackDeviceTest,DrawPhaseLayersDeviceTest` → 7/7 通过。
- 同一真机横屏重跑 `AppBottomSheetDeviceTest` → 4/4 通过；本轮设备最终验收组共 21 项通过。旋转设置在操作后恢复。未执行：本轮平板、深浅色与全页面完整矩阵、release 性能对照、真实游戏兼容性。按用户要求关闭模拟器，使用真机验证；旧版平板视频不作为本次合并代码的验收。
- `git diff --check origin/main` → 通过；版本文件相对基线无改动。

首轮设备回归有两项测试问题，修正后完整重跑上述 10 项通过：增强滑选弹层以 `withFrameNanos` 持续请求帧，自动推进的测试不能自然 idle，改为有限推进并在暂停时钟时验证 idle、真实窗口数量和返回顺序；未修改生产动效。alias 测试原先把 LeakCanary 的独立入口也算进业务图标，现限定实际两个业务 alias 并额外验证工具入口状态不变。横屏补测曾在首布局完成前检查标题，改为原 5 秒内等待标题、排序条目真实显示且标题坐标连续稳定，再进入窗口断言；没有增大固定等待、删除显示断言或改生产布局。最终重新执行横屏 4 项及竖屏 10 项。首次缺失 Gradle worker bootstrap 的本机缓存已在仓库外备份重建，未修改构建或测试门槛。

### 问题清单

| No. | 级别 | 问题 | 建议及处置 | 位置 |
|---|---|---|---|---|
| 1 | 高，已修复 | 上游固定 namespace 的启动器组件名与 PR 的 `${applicationId}` alias 不一致，隔离包启动即崩溃 | 按安装包名构造 alias，设备回归读取实际 manifest 并验证两向切换、幂等与状态恢复 | [AppIconSwitcher.kt:22](https://github.com/Weiss-UltimateSavior/Tyranor-Next/blob/cc5912f9a61aa1b566b3495226c21943bff148c5/app/src/main/java/com/tyranor/next/core/settings/AppIconSwitcher.kt#L22)；[AndroidManifest.xml:164](https://github.com/Weiss-UltimateSavior/Tyranor-Next/blob/cc5912f9a61aa1b566b3495226c21943bff148c5/app/src/main/AndroidManifest.xml#L164) |
| 2 | 中，已修复 | 游戏页搜索/筛选行迁到 `ExpandVisibility` 后，普通档原有展开淡入、收起淡出变成瞬时显隐 | 为具名入口增加预定义普通档回退，仅该原有动画场景启用；逐帧对照上游原动画 | [ContentMotion.kt:332](https://github.com/Weiss-UltimateSavior/Tyranor-Next/blob/cc5912f9a61aa1b566b3495226c21943bff148c5/app/src/main/java/com/tyranor/next/ui/common/motion/ContentMotion.kt#L332)；[GameScreen.kt:480](https://github.com/Weiss-UltimateSavior/Tyranor-Next/blob/cc5912f9a61aa1b566b3495226c21943bff148c5/app/src/main/java/com/tyranor/next/ui/game/GameScreen.kt#L480) |
| 3 | 中，已修复 | 原生首页未收到激活信号，跳过上游新增的选中首页时长刷新 | 首页始终报告真实激活状态；其余页面保持按引导需要订阅 | [MainScreen.kt:816](https://github.com/Weiss-UltimateSavior/Tyranor-Next/blob/cc5912f9a61aa1b566b3495226c21943bff148c5/app/src/main/java/com/tyranor/next/ui/main/MainScreen.kt#L816)；[HomeScreen.kt:142](https://github.com/Weiss-UltimateSavior/Tyranor-Next/blob/cc5912f9a61aa1b566b3495226c21943bff148c5/app/src/main/java/com/tyranor/next/ui/home/HomeScreen.kt#L142) |
| 4 | 中，已修复 | 新公共抽屉未接入统一控制器、页面景深和提示窗口边界；关闭确认没有覆盖增强档返回、遮罩与拖动 | `AppBottomSheet` 统一复用控制器，所有关闭入口读取最新业务守卫；被拒绝的拖动回弹 | [AppBottomSheet.kt:73](https://github.com/Weiss-UltimateSavior/Tyranor-Next/blob/cc5912f9a61aa1b566b3495226c21943bff148c5/app/src/main/java/com/tyranor/next/ui/common/AppBottomSheet.kt#L73)；[SheetMotion.kt:254](https://github.com/Weiss-UltimateSavior/Tyranor-Next/blob/cc5912f9a61aa1b566b3495226c21943bff148c5/app/src/main/java/com/tyranor/next/ui/common/motion/SheetMotion.kt#L254) |
| 5 | 中，已修复 | 筛选抽屉直接用库下拉，绕过统一反馈；独立窗口需要对应 popup 宿主 | 复用 `OverlayDropdownPreference`，在公共组件内按抽屉上下文选择窗口宿主，保留普通档窗口行为 | [GameFilterSheet.kt:154](https://github.com/Weiss-UltimateSavior/Tyranor-Next/blob/cc5912f9a61aa1b566b3495226c21943bff148c5/app/src/main/java/com/tyranor/next/ui/game/GameFilterSheet.kt#L154)；[OverlayDropdownPreference.kt:389](https://github.com/Weiss-UltimateSavior/Tyranor-Next/blob/cc5912f9a61aa1b566b3495226c21943bff148c5/app/src/main/java/com/tyranor/next/ui/common/OverlayDropdownPreference.kt#L389) |
| 6 | 中，已修复 | 新二级返回按钮缺少统一按压反馈，裸 clickable 未显式禁用 indication | 复用 `pressSquish` 并禁用 indication，返回仍经系统 dispatcher | [AppSecondaryTopBar.kt:52](https://github.com/Weiss-UltimateSavior/Tyranor-Next/blob/cc5912f9a61aa1b566b3495226c21943bff148c5/app/src/main/java/com/tyranor/next/ui/common/AppSecondaryTopBar.kt#L52) |
| 7 | 中，已修复 | 动效设置选择行裸订阅设置流，偏离生命周期订阅规范 | 改用 `collectSettingAsState`；需要后台即时生效的全局动效环境仍保留独立订阅 | [AppSettingsActivity.kt:238](https://github.com/Weiss-UltimateSavior/Tyranor-Next/blob/cc5912f9a61aa1b566b3495226c21943bff148c5/app/src/main/java/com/tyranor/next/ui/settings/AppSettingsActivity.kt#L238) |
| 8 | 中，已修复 | 提示胶囊六色、分类透镜两个中性描边色散落在组件 | 原值移入 `theme/Color.kt`；8 个 ARGB 值与反代源码等价，未改阴影或运动参数 | [NoticeHost.kt:399](https://github.com/Weiss-UltimateSavior/Tyranor-Next/blob/cc5912f9a61aa1b566b3495226c21943bff148c5/app/src/main/java/com/tyranor/next/ui/common/notice/NoticeHost.kt#L399)；[SlidingTabRow.kt:323](https://github.com/Weiss-UltimateSavior/Tyranor-Next/blob/cc5912f9a61aa1b566b3495226c21943bff148c5/app/src/main/java/com/tyranor/next/ui/common/SlidingTabRow.kt#L323)；[Color.kt:38](https://github.com/Weiss-UltimateSavior/Tyranor-Next/blob/cc5912f9a61aa1b566b3495226c21943bff148c5/app/src/main/java/com/tyranor/next/theme/Color.kt#L38) |
| 9 | 低，已修复 | 主题入口迁移后文档仍把界面动效定位在外观主题卡片内 | 修正为“应用设置 → 界面动效”独立卡片 | [AGENTS.md:282](https://github.com/Weiss-UltimateSavior/Tyranor-Next/blob/cc5912f9a61aa1b566b3495226c21943bff148c5/AGENTS.md#L282)；[SPEC.md:28](https://github.com/Weiss-UltimateSavior/Tyranor-Next/blob/cc5912f9a61aa1b566b3495226c21943bff148c5/SPEC.md#L28) |

问题位置链接固定到本次已验证的代码提交，不依赖后续分支移动。

### 重点问题说明

**1.【高】隔离安装的启动器组件名错配。**

自动合并保留了 PR manifest 的 `${applicationId}.LauncherDefault` / `.LauncherClassic`，同时引入上游固定 `com.tyranor.next.LauncherDefault` / `.LauncherClassic` 的代码。真机 instrument 在测试开始前抛出 `IllegalArgumentException: Component class com.tyranor.next.LauncherDefault does not exist in com.tyranor.next.motionv2`。正式包名下两种命名相同，隔离包名下不同。修复后组件 class 与实际 applicationId 一致；targetActivity 继续使用源码 namespace。新回归从 PackageManager 枚举实际 launcher alias，检查切换后只剩一个可解析入口，并恢复原组件状态，不依赖复制预期 alias 字符串。

**2–3.【中】普通档过渡和首页业务激活的合并回归。**

搜索/筛选行在新入口的普通档分支原为 `EnterTransition.None` / `ExitTransition.None`，与上游显式 `expandVertically + fadeIn` / `shrinkVertically + fadeOut` 不同。新增设备回归逐帧比较尺寸和像素，覆盖收起、半途重入与重新展开。

原生首页的 `foldDesaturate = true` 使原 `onboarding || (page == 0 && !foldDesaturate)` 永远为 false，上游 `LaunchedEffect(isActive, homeStyle)` 因而提前返回。Application 的前台刷新仍存在，不能把该问题描述为全部时长统计失效。新增两档设备回归经真实分页宿主与 HomeScreen 导入随机隔离 checkpoint，覆盖隐藏不导入、选中导入、重入、关闭及恢复首页，并检查 `0.1h → 0.2h → 0.3h` 显示。

**4–5.【中】抽屉与下拉应共享窗口和关闭契约。**

游戏操作、游戏筛选和扫描目录均复用 `AppBottomSheet`。动作抽屉仍保留原 `GameSheetNavigation` 控制器的跳转交接；公共壳接入同一控制器、Backdrop 和 NoticeCover，目录内容重复的入场作用域已移除。下拉展开期间，确认关闭的业务判断不能被增强档的 BackHandler 或拖动分支绕过。守卫通过 `rememberUpdatedState` 读取新值，拒绝越阈拖动时回弹。新设备回归在两档中打开实际筛选下拉，检查返回先关闭下拉、再关闭抽屉，并验证守卫、最新回调和只移除一次。

### 已核对无问题的部分

- 最新上游的筛选与排序、时长统计、主题入口迁移及二级顶栏均保留；没有整体选择 ours/theirs 丢弃一侧功能。
- UI → core → engine 的依赖方向保留；动效实现仍归公共入口，整页位置变换使用 `drawPhaseLayers`。
- 底栏视觉及图标运动参数、版本号未修改；固定颜色仅搬移，第三方署名保留。
- 字符串、动效边界检查不能替代 UI、性能或真实游戏验收；本轮不声称完成这些额外范围。
