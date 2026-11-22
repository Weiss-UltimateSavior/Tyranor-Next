# PR #195 返修记录

本文记录 PR #195 的返修轮次与验证结果。各轮「审查输入」记录当轮开始时的 head；最终 head 以 PR 页面为准，正文不写工作站绝对路径。

## 问题清单对应

| 项目 | 处理结果 |
| --- | --- |
| 1 滑条取消 | 增强离散滑条取消不默认调用完成回调；协程 finally 仅做视觉清理。横拖成立后也尊重已消费移动事件。普通档无显式取消回调时保留 Miuix 既有行为。设备上节点移除会先收到 Compose 合成手势取消，显式回滚至多一次且不提交。 |
| 2 切页重组 | 可见性读取移入独立 UpdateWaterfallReflow 组合范围，夹具验证显隐不重组调用方。 |
| 3 初始化 | MainActivity 在 setContent 前初始化风格及列数，游戏页只订阅。 |
| 4 超时触底 | Foundation 1.11.2 对短尾同步回填；高尾卡按实际可滚距离分帧补齐。零预算分别覆盖高尾卡和带 24dp 底部内边距的短尾。三列尾部不满整行时以所有列最底边判定触底，末项不必处于最长列。 |
| 5 UI 截图 | 平板四主题（默认浅色、默认深色、复古玻璃、白雾玻璃）横竖两方向的修复后截图已入库，见[平板证据](pr195-evidence/README.md)。手机矩阵、修复前对照与人工视觉审看仍缺：本机只连接 TB323FU 平板，且当前模型无图像输入，未对截图做人工排版判读。 |
| 6 宿主重建 | 保留重建列历史以保证回滚稳定的取舍，SPEC 明确非旋转重建也暂时隐藏视口及时间预算边界。 |
| 7–10 注释/命名/可观察状态/import | 新增注释中文化；公共列数命名改为 GameCard；列数、方向、gridState 原子可观察发布；删除未使用 import。 |
| 11 测试依赖 | 保留 debug test.core，注释说明 createComposeRule 经 ActivityScenario 启动目标包辅助 Activity 的需要。 |
| 12–14 文档 | CHANGELOG 空行及最终取消语义、README 功能简述、AGENTS 中性标题措辞均同步。 |
| 15 提交历史 | 保留已有 merge 历史，本轮返修作为独立提交；建议合并 PR 时 squash。 |
| 16 重复布局 | 共用方向/列数选择、内容边距与列宽计算；reflow 使用公共类型重载，瀑布保留其恢复和旧偏移接入。 |

额外修复：风格初始化与 setter 也纳入共享锁，锁闩回归覆盖两种竞争顺序；第三段前扫在按旧索引完成前检查最新 URI，避免原游戏重新出现时恢复到其他游戏。

## 首轮验证

- JDK 17，隔离包名 `com.tyranor.next.pr195fix`。
- `:app:testDebugUnitTest --tests '*GameCardStyleSettingsTest' --tests '*GridReflowSnapshotTest'`：29 项通过（14 + 15），0 失败。
- `:app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`：通过。lint 报告有 421 Warning、11 Hint、0 Error；未宣称全仓无警告。
- `tools/check-hardcoded-ui-strings.py`：通过。
- `tools/check-motion-boundaries.py`：当轮 398 个 Kotlin 文件，0 违规（文件数随本分支新增文件增长，最新值见文末本轮记录）。
- `git diff --check`：通过。
- TB323FU 平板（Android 16 / API 36）四组设备测试：50 项登记，46 项通过、4 项按条件跳过、0 失败，203.536 秒。跳过项为 3 个历史诊断假设用例（未开启 waterfall_diagnostics）和 1 个仅适用于增强档的祖先移动接管用例在普通档的参数组合。覆盖 MotionSliderDeviceTest、GridReflowDeviceTest、WaterfallRotationScrollDeviceTest、WaterfallRestoreResponsivenessDeviceTest；100/3000 卡恢复、输入延迟阈值、真实绘制像素检查均通过。

本轮未执行全量 JVM 测试；合成卡片设备回归不替代真实游戏库、真实页面主题截图或发布包流畅度验收。

## 第二轮返修（审查输入 `6804195b`）

- 整合主分支 `03634f459eb8fe9c005faa38343352b57c27a0ba`，解决 CHANGELOG 冲突，保留快捷方式修复及既有卡片条目；不改写已推送历史。
- 瀑布封面改为按实际解码目标宽高选择最小可覆盖档位，显示比例与四档缓存保持原样。`456px / 0.67` 原 CARD 目标为 `444×683`，现 FLOW 为 `666×1024`；`493px / 0.60` 仍选 FLOW。最大档超界仍取最大档，小源图仍不放大。
- README 收敛为功能概述，CHANGELOG 明确竖屏平板默认列数从 6 改为 4；清理两个未使用测试导入。PR 正文更新取消语义与参数展开后的统计。
- JVM 定向回归 41 项通过，0 失败、0 跳过：Style 14、Badge 2、Corner 1、Snapshot 15、CoverDecodeTier 9。封面测试覆盖 21 个比例、126 个档位边界。
- 隔离包 `:app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` 通过，lint 421 Warning、11 Hint、0 Error。首次 lint 在文件并发编辑时发生 `Unknown annotation target AndroidJUnit4` 内部分析异常，冻结文件后重跑通过。硬编码文案检查、动效边界检查、`git diff --check` 通过。
- 最终代码 TB323FU / API 36 原四类设备回归 181.564 秒：50 项登记，46 通过、4 条件跳过、0 失败；跳过原因与首轮相同。新增 3000 条真实主页面默认浅色两方向通过（9.635 秒），返回画面横/竖观察上界 1879/1822ms，正确抽屉 2302/2229ms；原记录集合保留，本轮剩余 0。
- 新增真实 `MainActivity` 验收：默认 100 条私有原创封面记录，显式 `pr195_game_count=3000` 加压；隐藏游戏标签后旋转，再返回核对尾部锚点、系统截图中的封面颜色与一次真实触摸打开正确游戏抽屉。四主题分轮运行，参数 `pr195_appearance=default-light|default-dark|retro-glass|milk-glass`。仅支持显式指定的隔离包，保留证据并清理本轮数据、还原设置与方向。
- TB323FU / API 36 四主题各两方向通过，原触底、封面颜色、完整卡片及单次原生触摸抽屉断言均通过。返回画面观察上界 521–634ms，正确抽屉 985–1647ms。8 张原始系统截图、分主题数据及视觉验收边界见 [平板证据](pr195-evidence/README.md)。手机、修复前对照与人工视觉审看仍缺。
- 真实页面验收发现隐藏时旧瀑布仍随新窗口重测，返回才捕获锚点会丢失离页时触底状态。现首次离页保存锚点与偏移快照；隐藏期不覆盖，返回换布局优先使用，未换布局则丢弃快照。
- 导航通过生产条目的 `performClick` 语义回调切换，卡片仍单次系统触摸；测试规则自动推进 Compose 动画时钟，使用系统单调时钟记录包含空闲等待、截图与抽屉响应的观察上界，不代表自然显示帧下的最早可交互时刻或硬件延迟。未覆盖 Activity 重建或逐帧零闪现；debug 数据不当作发布包流畅度证据。

## 第三轮返修（审查输入 `5af6fc81`）

- 整合主分支 `4704d757`（含 #207 桌面快捷方式与 #209 Android 10 下载目录启动修复），只保留 CHANGELOG 双方条目，不改写已推送历史。
- 恢复流程补齐异常兜底：`restore()` 外层 `try/catch(Exception)`，取消仍原样重抛并保留锚点（`WaterfallRotationScrollDeviceTest` 的「取消不得清空 pending anchor」断言不变），非取消异常记日志后解除遮蔽；复位抽成 `finishRestore()` 并校验 `gridState` 身份，避免上一代网格把新一代的恢复状态清掉。
- 保存态七字段改为 `size == 7` + `runCatching` + 列数 `coerceIn(2, 10)` 与锚点 `y` 限幅，坏载荷回落新建状态而不是让 `rememberSaveable` 恢复阶段抛异常。
- 三处「拖动只改内存态、抬手才落盘」的滑条补 `onValueChangeCancelled` 回滚草稿：应用设置扫描深度、背景图不透明度与模糊。其余即时落盘的调用点与唯一已有回调的列数滑条保持原样；未传回调时普通档仍与基线逐字节等价。
- 封面选档口径统一：等高网格改用 `forDisplay(列宽, 3:4)` 并把固定比例提为 `UniformGameCardAspect`，`forWidth` 只留封面流；穷举 1–3000px 宽度两函数选档完全一致，缓存键与视觉不变。
- 文档与规范：`docs/pr195-revision.md` 去掉旧 head 与工作站绝对路径、截图状态与文末对齐、Kotlin 文件数标注当轮、修正轮次标题；CHANGELOG 补手机竖屏 3→4；`docs/README.md` 登记平板证据目录；动效门禁 `OWNERS` 补齐 `ui/common/motion/` 全部 31 个文件；`initGameCardStyle` 只取一次 `prefs`；`MainScreen` 缩进对齐。
- 更正前两轮结论：`gridReflowHost` 的 alpha 与 `waterfallReflowHost` 的遮蔽**先后串接、互不重叠**（重排窗口 → 恢复期），不是两个宿主同时设值；删除它会让瀑布重排卡在第 0 帧就完成入场，`GridReflowDeviceTest` 三项逐帧错峰断言随即失败，故保留并按两段边界补注释。`coarseRestore` 的尾部补齐故意不受扫描预算约束，零预算用例要求它仍滚到底，因此不给它加预算门。
- JVM 定向 41 项通过（Style 14 / Badge 2 / Corner 1 / Snapshot 15 / CoverDecodeTier 9），0 失败 0 跳过。
- TB323FU / API 36 设备本轮登记 56 项：**52 项通过、4 项按条件跳过、0 失败**。四类登记 52 项（`GridReflowDeviceTest` 12、`MotionSliderDeviceTest` 16、`WaterfallRotationScrollDeviceTest` 20、`WaterfallRestoreResponsivenessDeviceTest` 4）实测 48 通过、4 跳过，另四主题各一轮 `GameScreenMainActivityAcceptanceDeviceTest` 4 项通过。跳过项为 3 个需显式传 `-e waterfall_diagnostics true` 的诊断假设用例，以及 1 个仅适用于增强档的 `consumedMoveAfterHorizontalDragDoesNotFinish[enhanced=false]` 参数组合。`MotionSliderDeviceTest` 位于 `com.tyranor.next.ui.common`，不带 `.motion`。补上连续档取消用例后登记数由 54 增至 56（`MotionSliderDeviceTest` 14 → 16）。
- 全量 JVM 2092 项中 36 项失败。基线对照：在 `4704d757`（`origin/main`）独立工作树上跑同一任务得 2062 项、**同样这 36 项失败**，失败类与用例名逐条一致（RuffleRuntimeEnvironmentTest 3、RuntimeModuleFileImportTest 1、EthornellOperationGateTest 5、EthornellSaveStoreTest 14、EthornellSettingsTest 2、WebDavClientTest 6、WebDavRequestGuardTest 5），本分支未触碰这些路径 → 属基线既有失败，非本次返修引入。
- 隔离包 `:app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` 通过，lint 431（420 Warning、11 Hint、0 Error）；硬编码文案检查、动效边界检查（400 个 Kotlin 文件、0 违规）、`git diff --check` 通过。

### 第三轮自审补修

- 恢复异常路径改调 `finishRestore(state)`：此前只把 `isRestoring` 置假、留下非空 `pendingAnchor`，会让下一次换布局跳过重新捕获、拿旧列宽的 `index/y` 去滚；现在异常后锚点一并清空，`isRestoring == (pendingAnchor != null)` 不变量在所有出口成立。捕获类型收窄到 `Exception`，不再吞 `Error`。
- 保存态列数范围由 `1..32` 收紧到业务值域 `2..10`，并给锚点 `y` 加 `±2^20` 限幅，避免坏载荷把 `Int.MIN_VALUE` 取负后仍为负。
- `MotionSliderDeviceTest` 补一条连续档（`steps = 0`）的系统取消用例：应用设置页两个背景图滑条正是连续档，此前只有离散档覆盖。
- `GameSheetHeader` 的 78dp 头部封面显式说明仍用 GRID 档：不跟列表 `forDisplay` 走是为一张小图不常驻更大的解码结果，与列表各取所需而非口径遗漏。
- 更正 `gridReflowHost` 的 KDoc：两层 alpha 是相乘而非「单一来源」，值恒等靠调用方的 `ready` 绑定（恢复期两层同为 0、完成帧两层同为 1）。
- 恢复异常路径补日志：这条路径会同时解除整表遮蔽、放开吞输入与无障碍移除，静默收尾无从定位，故 `Log.w` 记录一次。
- `coarseRestore` 的尾部补齐在不加预算门的前提下补上步数上限（`列数规模 × 4 + 1024`），保留零预算仍滚到底的语义，同时排除无限滚动的静态可能。
- 验收用例的旋转恢复拆成「锁回原方向」与「解冻」两个动作：前者失败不再跳过解冻，避免设备带着测试写入的固定旋转退回桌面。
