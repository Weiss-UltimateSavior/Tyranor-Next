# PR #195 返修记录

审查对象：PR head `1dc3c2d1e8e491552b746c69f37442149fe23926` 与本地已有返修；本轮仅在 `I:\AI\TyranorNext-pr195-review` 集成。主工作树的存档和引擎改动未修改。

## 问题清单对应

| 项目 | 处理结果 |
| --- | --- |
| 1 滑条取消 | 增强离散滑条取消不默认调用完成回调；协程 finally 仅做视觉清理。横拖成立后也尊重已消费移动事件。普通档无显式取消回调时保留 Miuix 既有行为。设备上节点移除会先收到 Compose 合成手势取消，显式回滚至多一次且不提交。 |
| 2 切页重组 | 可见性读取移入独立 UpdateWaterfallReflow 组合范围，夹具验证显隐不重组调用方。 |
| 3 初始化 | MainActivity 在 setContent 前初始化风格及列数，游戏页只订阅。 |
| 4 超时触底 | Foundation 1.11.2 对短尾同步回填；高尾卡按实际可滚距离分帧补齐。零预算分别覆盖高尾卡和带 24dp 底部内边距的短尾。三列尾部不满整行时以所有列最底边判定触底，末项不必处于最长列。 |
| 5 UI 截图 | 仍待补齐手机/平板横竖、深浅色及两档玻璃的真实页面前后截图。本机只连接 TB323FU 平板；隔离应用当前游戏库为空，空页截图不算卡片验收。 |
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
- `tools/check-motion-boundaries.py`：398 Kotlin 文件，0 违规。
- `git diff --check`：通过。
- TB323FU 平板（Android 16 / API 36）四组设备测试：50 项登记，46 项通过、4 项按条件跳过、0 失败，203.536 秒。跳过项为 3 个历史诊断假设用例（未开启 waterfall_diagnostics）和 1 个仅适用于增强档的祖先移动接管用例在普通档的参数组合。覆盖 MotionSliderDeviceTest、GridReflowDeviceTest、WaterfallRotationScrollDeviceTest、WaterfallRestoreResponsivenessDeviceTest；100/3000 卡恢复、输入延迟阈值、真实绘制像素检查均通过。

本轮未执行全量 JVM 测试；合成卡片设备回归不替代真实游戏库、真实页面主题截图或发布包流畅度验收。

## 第三轮返修（审查输入 `6804195b`）

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
