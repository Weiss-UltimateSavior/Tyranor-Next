# `codex/bangumi-vndb-play-status` 分支审计报告（2026-11-16 · R8 工作区最新完整状态）

- **审计对象**：`codex/bangumi-vndb-play-status`（[wt-binding](file:///d:/Tyranor-Next/wt-binding)，`HEAD cf56a52e` 叠加当前工作区未提交修改；相对 `origin/main` `709687a3` 共涉及 **105** 个跟踪文件改动及 **11** 个新增未跟踪代码/测试文件：`androidTest/` 4 个、`test/` 7 个）。
- **审计基准**：[AGENTS.md](../AGENTS.md)、[CONTEXT.md](../CONTEXT.md)、[ARCHITECTURE.md](../ARCHITECTURE.md)、[SPEC.md](../SPEC.md)。
- **审计方式**：严格遵循 `AGENTS.md`「测试与审核」强制要求，分派 **5 个相互独立的只读审查子 Agent**（代码逻辑、架构与文档一致性、代码健壮性/安全与生命周期、回归风险与测试质量、代码规范/UI/动效与三语言文案）直接核查磁盘最新文件，并由主 Agent 逐一交叉核实精确路径与行号、重跑本地门禁、定向与全量 JVM 单测、`androidTest` 编译与 Android Lint 增量检查。

> 说明：历史审计快照（`docs/play-status-audit-20261114.md`、`20261115*.md`、`20261116.md`）均完整保留不覆盖。R7（`play-status-audit-20261116.md`）列出的 8 项中严重度问题在工作区后续修订中已基本修复或收敛（见第三节）；本报告反映当前最新磁盘状态的独立重审结果。

---

## 一、已执行验证与实测结果

| 检查项 | 命令 | 实测结果 |
| --- | --- | --- |
| 三语言字符串与硬编码 CJK 门禁 | `python tools/check-hardcoded-ui-strings.py` | **通过**（`Hardcoded UI string check passed.`，zh / en / ja 键集完全对齐，无新增 Kotlin 硬编码 CJK） |
| 动效边界门禁 | `python tools/check-motion-boundaries.py` | **通过**（`Motion boundaries: 366 Kotlin files, 0 violations.`） |
| 本地门禁脚本单测 | `python tools/test-hardcoded-ui-strings.py` & `python tools/test_motion_boundaries.py` | **通过**（5 项 + 11 项全通过） |
| 图形资源归属回归 | `./gradlew.bat -p buildSrc graphicsResourcesRegression --no-daemon` | **通过**（`Graphics resource regressions passed: 24`，`BUILD SUCCESSFUL`） |
| 本分支定向 JVM 单元测试 | `./gradlew.bat :app:testDebugUnitTest --rerun --tests "com.tyranor.next.core.auth.*" --tests "com.tyranor.next.core.cover.*" --tests "com.tyranor.next.core.status.*" --tests "com.tyranor.next.core.net.*" --tests "com.tyranor.next.core.backup.*" --tests "com.tyranor.next.core.game.scan.*" --tests "com.tyranor.next.core.game.storage.*" --tests "com.tyranor.next.ui.auth.*" --tests "com.tyranor.next.ui.cover.*" --tests "com.tyranor.next.ui.game.*" --tests "com.tyranor.next.ui.main.*" --no-daemon` | **全部通过**（`files=65 tests=497 failures=0 errors=0 skipped=0`，上轮 `OAuthExchangeAndIdentityTest` 偶发竞态已消除） |
| 全量 App JVM 单元测试 | `./gradlew.bat :app:testDebugUnitTest --no-daemon --continue` | **本分支定向用例全通过**（1827 项完成、31 项失败、18 项跳过；31 项失败均为 Windows 宿主环境既有路径/符号链接测试：`RuffleRuntimeEnvironmentTest` 3、`EthornellOperationGateTest` 5、`LaunchEntryPathTest` 7、`EthornellSaveStoreTest` 14、`EthornellSettingsTest` 2） |
| AndroidTest 编译检查 | `./gradlew.bat :app:compileDebugAndroidTestKotlin --no-daemon` | **通过**（`BUILD SUCCESSFUL`） |
| Android Lint 增量检查 | `./gradlew.bat :app:lintDebug --no-daemon` + `python tools/check-lint-diff.py --base origin/main app/build/reports/lint-results-debug.xml` | **变更源码 0 Error**（`Lint errors in reports: 1. Errors on changed sources: 0.`；唯一 Error 为本机 `local.properties:1` `[PropertyEscape]`） |
| 未执行项 | 真机/模拟器 `connectedDebugAndroidTest`（`adb devices` 虽检测到在线设备 `519ae39e`，但未获授权安装隔离验收 APK，本轮仅做离线审计）、真实浏览器 OAuth 回调闭环、真实 AndroidKeyStore 密钥失效恢复、360dp 窄屏真机渲染、`:engine` 相关检查（本分支 `engine/` 零改动） | **未执行**（不得视同已验收） |

---

## 二、总体审计结论

- **架构、逻辑与安全防线扎实**：本分支新增的 `core/auth`、`core/cover`、`core/status`、`core/net`、`core/game/storage` 与主要 UI 路径在并发单飞刷新、`credentialEpoch` / `sessionEpoch` 防串号、`bindingRevision` 防封面回滚、Room v3→v6 显式迁移、备份排除、OAuth PKCE S256 / 环回监听 / 日志脱敏及 `core -> ui` 类型化错误协议方面均已闭环，定向 497 项 JVM 单测实测一次通过。
- **仍存 1 项 High、6 项 Medium 与 14 项 Low 问题**：
  - **High（1 项，主界面既有路径漏改）**：`SettingsScreen.kt:597-618`（`:614`）手动输入扫描路径时仍在 `Main` 线程调用 `GameLibraryFacade.saveRoot`，与同文件已迁至 IO 的 `dirPicker` 不一致，存在主线程等待 `cacheLock` + `readBlocking` 的卡死风险。
  - **Medium（6 项）**：涵盖 `GameLibraryRepository.init` 协程吞并 `CancellationException`、`AppAlertDialog` 退场缺少 `ON_PAUSE` 提前交付导致 `rememberSaveable` 弹窗旋转复活、抽屉内 `AppNavItem` 未传 `indication = null` 残留 Material 涟漪、测试质量与清理健壮性（JVM `Proxy` 迁移、`androidTest` 绕过生产 `ensureMigrated` 入口及清理无 `try/finally`/残留 Keystore 别名）、关键路径自动化测试覆盖缺口，以及三语言 `strings.xml` 术语与日文语义精度问题。

---

## 三、前序（R7）重点问题修复核查表

| 前序编号 | 原问题摘要 | 当前核查状态 | 核实位置与说明 |
| --- | --- | --- | --- |
| R7 #1 | 三端 `completeOAuthTokenExchange` 裸线程泄漏及 `CoverScrapeTaskManager.resetForTest()`/`AuthAndCoverTestStubsRule` 清理不完整 | **已完整修复** | 三端身份回查迁至受管 `identityScope` 并提供 `resetForTest()` 取消等待（如 `BangumiAuthService.kt:344-362`）；`CoverScrapeTaskManager.kt:58-205` 通过 `activeGeneration` + `cancelAndJoin` 隔离；`AuthAndCoverTestStubsRule.kt:50-73` 补齐三端 service、`HikarinagiAuthStore.resetForTest()` 与 `GameLibraryFacade.seedGamesForTest(null)`；497 项单测重跑稳定通过。 |
| R7 #2 | `CredentialTokenDialog`「清空」按钮继承 `LocalDialogExit` 致异步超时 80ms 闪回 | **已完整修复** | `CredentialTokenDialog.kt:284-305` 已用 `CompositionLocalProvider(LocalDialogExit provides null)` 包裹清空按钮。 |
| R7 #3 | `getValidAccessTokenResult` 在 `validStoredAccessToken` 与 `readRefreshSnapshot` 间并发新登录误判 | **已修复** | `BangumiAuthService.kt:425-428`（及 Hikarinagi/NextMoe 同款分支）在凭据缺失分支复查 `validStoredAccessToken`；`getValidAccessTokenResult` 先采样 `initialSnapshot`（含 `credentialEpoch`）再进闸，`SecureAuthStore.adoptSingleFlightAccessToken`（`311-333`）按 `credentialEpochChanged && withinFastPath` 正确采纳并发新登录。 |
| R7 #4 | `MainLibraryViewModel.replaceGame` 异步窗口覆写整行回滚并发字段 | **已修复** | 已拆分为按责任字段合并的局部更新方法（`applyCoverUpdate`、`applyBindingUpdate`、`renameGame`、`updateLaunchFile`、`applyScrapedGame`），不再使用旧整行快照覆盖。 |
| R7 #5 | `GameLibraryFacade.cacheLock` 与主线程 `dirPicker` / `bindingRevision` 争锁 | **部分修复（遗留 1 处 High）** | `bindingRevisionLock` 已从 `cacheLock` 拆出（`GameLibraryFacade.kt:32, 271`），`SettingsScreen.kt:207-222` `dirPicker` 已迁入 `Dispatchers.IO`；但同文件 `:614` `savePath` 仍直接在主线程调用 `saveRoot`（见第四/五节 #1）。 |
| R7 #6 | `GameOverlayMotion.dismiss` 与 `MotionSheetController.animateClose` 退场期旋转取消不交付关闭回调 | **已修复（同类遗留延至 `AppAlertDialog`）** | `GameOverlayMotion.kt:188-198` 与 `SheetMotion.kt:272-322, 433-447` 已补齐 `NonCancellable` 与 `ON_PAUSE` 提前交付；同类 `AppAlertDialog` 仍缺 `ON_PAUSE` 交付（见 #3）。 |
| R7 #7 | 测试质量与覆盖缺口（`v1/v99` 恢复、codec、Facade+Room 闭环等） | **部分改善，仍有缺口** | `GameLibraryDatabaseDeviceMigrationTest.kt:224-357` 已补 `v1→v6`、`v99→v6` 与 Facade+Room+prefs 往返，但 `v1/v99` 测试绕过了生产 `ensureMigrated`（见 #5/#6）。 |
| R7 #8 | 三语言术语、`docs/应用持久化数据清单.md` 错归与 `GitHubUpdateChecker` 取消语义 | **主体已修复，留文案细节** | `docs/应用持久化数据清单.md:176, 190` 已纠正 `configuration.json` 归属；`GitHubUpdateChecker.kt:122` 已显式重抛 `CancellationException`；`strings.xml` 仍留个别术语与日文偏差（见 #7）。 |

---

## 四、问题清单总览（R8 最新工作区状态）

| No. | 级别 | 维度 | 问题摘要 | 主要位置 |
| --- | --- | --- | --- | --- |
| 1 | **High** | 线程安全/性能 | `SettingsScreen` 手动输入目录 `savePath` 在主线程调用 `GameLibraryFacade.saveRoot`，获取 `cacheLock` 并可能同步 `readBlocking`（基线遗留，同文件 `dirPicker` 已迁 IO） | `SettingsScreen.kt:597-618`（`:614`）、`GameLibraryFacade.kt:450-453, 505-507` |
| 2 | Medium | 健壮性/协程 | `GameLibraryRepository.init` 协程内 3 处 `catch (t: Throwable)` 吞掉 `CancellationException`，取消后继续执行后续步骤（基线遗留） | `GameLibraryRepository.kt:74-94`（`:78-92`） |
| 3 | Medium | 生命周期/UI | `AppAlertDialog` 退场缺少 `ON_PAUSE` 提前交付，关闭动画期间旋转重建可使 `rememberSaveable` 弹窗复活 | `AppAlertDialog.kt:160-227`（对比 `GameOverlayMotion.kt:188-195`、`SheetMotion.kt:433-444`） |
| 4 | Medium | UI/规范 | `AppBottomSheet` 独立窗口未提供 `NoIndication`，抽屉内 `GameActionsSheet` `AppNavItem` 未传 `indication = null` 仍保留 Material 涟漪 | `AppNavItem.kt:74, 94`、`AppBottomSheet.kt:76, 124`、`GameScreen.kt:1132-1145, 1221-1236` |
| 5 | Medium | 测试质量 | `GameLibraryDatabaseMigrationTest` 为 `Proxy` 假迁移；真机 `v1/v99` 测试绕过 `GameLibraryRepository.ensureMigrated` 入口；`PlayStatusDrawerSummaryTest` 与 `DeviceStorageAndAuthCryptoTest` 清理存在状态污染隐患 | `GameLibraryDatabaseMigrationTest.kt:23-50`、`GameLibraryDatabaseDeviceMigrationTest.kt:224-285`、`PlayStatusDrawerSummaryTest.kt:59-70`、`DeviceStorageAndAuthCryptoTest.kt:46-52, 95, 152` |
| 6 | Medium | 测试覆盖 | `ensureMigrated` 降级自愈、OAuth 回调 Activity、`PlayStatusDialog`/`CredentialTokenDialog`/`CoverScraperSettingsActivity` Compose UI 与扫描持久化端到端链缺自动化测试 | 见第五节 #6 |
| 7 | Medium | 三语言文案 | `cover_logout` 仍用「退出 / Log out / ログアウト」未配对 `cover_authorize`；英文 `Signed-in` vs `Authorized` 混用；日文 `play_error_auth_session_changed` 语义偏差及 `カバー画像取得`/`項目` 不统一 | `values*/strings.xml:554, 745, 791`、`values-ja/strings.xml:107, 254, 721, 734-788` |
| 8 | Low | 逻辑/时序 | 批量刮削终态 `acceptPersistedGames` 与 `MainLibraryViewModel` FIFO 写命令竞态导致 UI 短暂闪回旧值（队列收口会纠偏） | `MainLibraryViewModel.kt:126-162, 214-223`、`CoverScrapeTaskManager.kt:95-98` |
| 9 | Low | 逻辑/性能 | `BangumiAuthStore.setAuthInProgress` 缺同值短路与流代次比对；三端进闸 `getStatus(context).needsReauth` 触发冗余 Keystore 解密 | `BangumiAuthStore.kt:367-374`、`BangumiAuthService.kt:150-158, 224-230, 437`、`HikarinagiAuthService.kt:439`、`NextMoeAuthService.kt:882` |
| 10 | Low | 逻辑/交互 | `CoverScraperSettingsActivity` Bangumi 在 `needsReauth` 下缺行内直登出；`PlayStatusPickDialog` VNDB/Hikarinagi 读失败无行内重试；`GameScreen` 抽屉刷新期间版本号变化可能被 `finally` 覆盖门控快照 | `CoverScraperSettingsActivity.kt:628-654`、`PlayStatusDialog.kt:682-708`、`GameScreen.kt:933-977` |
| 11 | Low | 健壮性/安全 | `SettingsScreen.kt:180` 裸用 `collectAsState()`；`GameQuickActionsDialog` 主线程同步删裁剪临时图；`SheetMotion.navigateAway` 延迟窗口期重建；`NextMoeAuthService.parseLoopbackCallbackQuery` 缺 `expectedState.isBlank()` 与 `GameScreen.deleteCoverFile` 缺 `file:` scheme 前置检查 | `SettingsScreen.kt:180`、`GameQuickActionsDialog.kt:148, 165`、`SheetMotion.kt:241-272, 437-443`、`NextMoeAuthService.kt:653-658`、`GameScreen.kt:403-413` |
| 12 | Low | 边界/测试细节 | `AppNavItem.kt:175-185` `constraints.maxWidth == 0` 下限钳制可超过最大宽；`GameLibraryFacade.resetCachesForTest()` 与 `seedGamesForTest(null)` 口径不一；8 份 JVM 单测未接入 `AuthAndCoverTestStubsRule`、`OAuthExchangeAndIdentityTest` 环回线程 `join` 未入 `finally` 等 | `AppNavItem.kt:175-185`、`GameLibraryFacade.kt:68-73, 139-151`、`OAuthExchangeAndIdentityTest.kt:104-130` 等 |
| 13 | Low | 代码规范/UI | 次级文本沿用 `onSurfaceVariant` 未收口 `Color.kt`；超长 Composable 函数（`PlayStatusDialog`、`CoverScraperSettingsScreen`、`GameActionsSheet`、`CoverSearchDialog`）；冗余全限定名/别名与重复 `sheetMotion.interact()`；`cover_bangumi/nextmoe_reauth_summary` 冗余分支；三语言标题/引号微瑕 | `PlayStatusDialog.kt`、`CoverScraperSettingsActivity.kt`、`GameScreen.kt`、`CredentialTokenDialog.kt`、`values*/strings.xml` |
| 14 | Low | 文档精度 | `docs/应用持久化数据清单.md` 第 71 行 `nav_style` 漏列白雾玻璃、第 116 行两处行号略偏、第 190 行 `configuration.json` 策略 A/B 表述混写；`AGENTS.md:138` `play_error_*` 借用列举略窄于 `PlayStatusErrors.kt` KDoc | `docs/应用持久化数据清单.md:71, 116, 190`、`AGENTS.md:138`、`PlayStatusErrors.kt:12-16` |

---

## 五、重点问题详情与修复建议

### 1.【High / #1】`SettingsScreen` 手动输入目录 `savePath` 在主线程调用 `GameLibraryFacade.saveRoot`

- **位置**：
  - `app/src/main/java/com/tyranor/next/ui/settings/SettingsScreen.kt:597-618`（第 614 行）
  - `app/src/main/java/com/tyranor/next/core/game/storage/GameLibraryFacade.kt:450-453, 505-507`
  - `app/src/main/java/com/tyranor/next/core/game/storage/GameLibraryRepository.kt:422-428`
- **根因与影响**：`SettingsScreen` 第 207–222 行的 `dirPicker` 已将 `GameLibraryFacade.saveRoot` 下沉到 `withContext(Dispatchers.IO)`，但第 597–618 行的 `savePath`（基线既有实现）仅将 `target.isDirectory` 切到 IO，第 614 行仍在主线程直接调用 `GameLibraryFacade.saveRoot(ctx, target.absolutePath)`。`saveRoot` 会请求 `synchronized(cacheLock)`，一旦 `rootsCache` 未预热或后台正在 `loadGamesLocked` 持锁执行 `readBlocking`（`runBlocking` 等待 Room 迁移与查询），主线程将直接阻塞引发卡顿甚至 ANR。
- **修复建议**：将第 614 行包裹至 `withContext(Dispatchers.IO) { GameLibraryFacade.saveRoot(ctx, target.absolutePath) }`。

### 2.【Medium / #2】`GameLibraryRepository.init` 捕获 `Throwable` 未放行 `CancellationException`

- **位置**：`app/src/main/java/com/tyranor/next/core/game/storage/GameLibraryRepository.kt:74-94`（第 78–92 行）
- **根因**：`init` 启动的 `writeScope.launch` 内连续三个 `try { ... } catch (t: Throwable)` 未检查 `if (t is CancellationException) throw t`（同文件 `mirrorJob` 在第 339–344 行已正确重抛）。若作用域被取消，`CancellationException` 会被吞并，且后续 `ensureSynced` 与 `prewarmCaches` 仍继续启动。
- **修复建议**：在三个 `catch` 块首行补充 `if (t is CancellationException) throw t`。

### 3.【Medium / #3】`AppAlertDialog` 退场缺少 `ON_PAUSE` 提前交付，关闭动画期旋转会复活

- **位置**：`app/src/main/java/com/tyranor/next/ui/common/AppAlertDialog.kt:160-227`（对比 `GameOverlayMotion.kt:188-195`、`SheetMotion.kt:433-444`）
- **根因**：`GameOverlayMotion` 与 `SheetMotion` 均通过 `LifecycleEventObserver` 在 `ON_PAUSE`（先于 `onSaveInstanceState`）立即提交已经进入关闭动画的 dismiss 回调；而 `AppAlertDialog` 在 ~260ms 退场动画期间无此观察者。对于由 `rememberSaveable` 控制显隐的弹窗（如 `SettingsScreen.kt:186`、`CoverScraperSettingsActivity.kt:158-159`、`PlayStatusDialog.kt:331-332`、`GameScreen.kt:853`、`InputSettingsScreen.kt:162-164`），若退场期发生旋转或配置重建，弹窗会在重建后再次弹出。
- **修复建议**：参照 `GameOverlayMotion` / `SheetMotion`，为 `AppAlertDialog` 常规退场路径增加 `ON_PAUSE` 提前交付守卫（仅在纯关闭态触发，不误触发 `DialogExitController` 的可挽回动作）。

### 4.【Medium / #4】抽屉内 `AppNavItem` 未禁用涟漪指示器

- **位置**：
  - `app/src/main/java/com/tyranor/next/ui/common/AppNavItem.kt:74, 94`
  - `app/src/main/java/com/tyranor/next/ui/common/AppBottomSheet.kt:76, 124`
  - `app/src/main/java/com/tyranor/next/ui/game/GameScreen.kt:1132-1145, 1221-1236`
- **根因**：`ModalBottomSheet` 运行在独立 Dialog 窗口中，不继承页面侧 `AppScreenScaffold` 的 `WithoutPressIndication`，且 `AppBottomSheet` 未在内容槽提供 `LocalIndication provides NoIndication`。`GameActionsSheet` 新增「游玩状态」与既有抽屉条目调用 `AppNavItem` 时均沿用默认 `indication = LocalIndication.current`，未按 `AppNavItem.kt:74` KDoc（`弹窗/抽屉内条目传 null 禁用`）传 `indication = null`，按压同时叠加 `pressSquish` 与 M3 `ripple()`。
- **修复建议**：在 `AppBottomSheet.kt:124` 的 `CompositionLocalProvider` 中合并提供 `LocalIndication provides NoIndication`，并为抽屉内 `AppNavItem` 调用显式传 `indication = null`。

### 5.【Medium / #5】测试质量与夹具清理隐患

- **位置**：
  - `app/src/test/java/com/tyranor/next/core/game/storage/GameLibraryDatabaseMigrationTest.kt:23-50`
  - `app/src/androidTest/java/com/tyranor/next/core/game/storage/GameLibraryDatabaseDeviceMigrationTest.kt:224-285`
  - `app/src/androidTest/java/com/tyranor/next/ui/game/PlayStatusDrawerSummaryTest.kt:59-70`
  - `app/src/androidTest/java/com/tyranor/next/core/cover/DeviceStorageAndAuthCryptoTest.kt:46-52, 95, 152`
- **根因**：
  1. `GameLibraryDatabaseMigrationTest` JVM 测试仍以 `Proxy` 拦截 `execSQL` 字符串比对，属于镜像实现断言（真实 SQLite v2–v6 已由设备测试覆盖）。
  2. `GameLibraryDatabaseDeviceMigrationTest` 的 `v1→v6` 与 `v99→v6` 降级重建用例直接手工调用 `LegacyGameLibraryImportPlan.build` + `dao.insert*`，绕过了生产入口 `GameLibraryRepository.ensureMigrated(context)` 及其关键自愈分支（`GameLibraryRepository.kt:102-131`）。
  3. `PlayStatusDrawerSummaryTest.tearDown()` 未使用 `try/finally` 且先执行 `*AuthStore.clear(context)` 再置空 `SecureAuthStore.testCipherDelegate = null`，异常时可污染后续设备用例；`DeviceStorageAndAuthCryptoTest` 未调用 `SecureAuthStore.clearKeyCacheForTest()`、在 `AndroidKeyStore` 残留 `test_tyranor_device_key` 别名，且第 152 行直接 `android.app.Activity()` 构造假 `Activity` 依赖 `CLIENT_ID_MISSING` 早退。
- **修复建议**：让真机 `v1/v99` 迁移测试通过生产 `ensureMigrated` 路径验证自愈恢复；为 `PlayStatusDrawerSummaryTest.tearDown` 增加 `try/finally`；在 `DeviceStorageAndAuthCryptoTest` 清理 `KeyStore` 测试别名与 `keyCache` 并使用轻量测试场景替换裸 `Activity()` 构造。

### 6.【Medium / #6】自动化测试覆盖缺口清单

1. **`GameLibraryRepository.ensureMigrated` 降级/备份恢复自愈路径**：`storage_migration_v1_done == true` 且 Room `countGames() == 0` 时由 prefs 镜像触发幂等重导入的端到端实跑。
2. **OAuth 回调 Activity 生命周期**：`BangumiOAuthCallbackActivity` / `HikarinagiOAuthCallbackActivity` 的 `onCreate` / `onNewIntent` 参数解析与流程校验。
3. **`PlayStatusDialog` 交互写流程 Compose UI 测试**：当前 `CoverAndSettingsNarrowUiTest.kt:218-240` 仅测了初始四端标题渲染，缺少候选搜索绑定、状态写入、评分/评论提交、解绑、内联报错与写入期关闭拦截。
4. **`CredentialTokenDialog` 提交与 `CoverScraperSettingsActivity` 整页 UI 测试**：缺少无效令牌提交报错 UI，以及开关、拖拽排序、授权状态流变更刷新整页 UI 覆盖。
5. **扫描合并 + 真实持久化 + 快捷方式集成链路**：串联 `EngineScanner` 重扫保留四源 ID、自定义封面切换与 `GameShortcutManager` 的集成验证。

### 7.【Medium / #7】三语言 `strings.xml` 术语与日文语义精度

- **位置**：
  - `app/src/main/res/values/strings.xml:554`、`values-en/strings.xml:554, 745, 791`、`values-ja/strings.xml:107, 254, 554, 721, 734-791`
- **详情**：
  1. 封面设置已将入口统一为「授权 / Authorize / 認証」，但 `cover_logout`（`:554`）仍为「退出 / Log out / ログアウト」，且 `values-en/strings.xml:745`（`Signed-in account changed`）与 `:791`（`Authorized account changed`）混用。
  2. `values-ja/strings.xml:791`（`play_error_auth_session_changed`）译为「認証状態が変更されたため…」（认证状态变更），偏离中英文「授权账号变更」（宜为 `認証アカウント`）；此外 `:107, 254` 用 `カバー画像取得` 而 `:721, 773-787` 用 `カバー取得設定`，`:734..784` 用 `項目` 而 `:775, 788` 用 `作品`。
- **修复建议**：统一 `cover_logout` 为「取消授权 / Revoke authorization / 認証解除」（或与项目 UI 约定对齐），同步英文 `Authorized`、修正日文 `:791` 账号语义并统一「カバー画像取得設定」与「項目」。

### 8.【Low】其余低严重度细节汇总（#8–#14）

- **#8 队列乐观态瞬态闪回（`MainLibraryViewModel.kt:126-162, 214-223`、`CoverScrapeTaskManager.kt:95-98`）**：用户在批量刮削收尾瞬间发起改名/改启动文件/删除时，`acceptPersistedGames` 会先以刮削终态旧库快照覆盖 `_uiState`，紧接着队列中排在其后的 `publishStorageSnapshot` 再刷新回最新值，仅存在短暂视觉回闪。
- **#9 冗余写盘与冗余解密**：`BangumiAuthStore.setAuthInProgress`（`367-374`）缺少同值短路，且 `BangumiAuthService` `cancelCallback`（`150-152`）未带 `EXTRA_EXPECTED_GENERATION`；三端刷新 `task` 内调用 `getStatus(context).needsReauth`（`BangumiAuthService.kt:437`、`HikarinagiAuthService.kt:439`、`NextMoeAuthService.kt:882`）会触发对 `access_token` / `refresh_token` 的多余 Keystore 解密（`needsReauth` 只需读明文 `KEY_LAST_ERROR`）。
- **#10 UI 交互与门控微瑕**：`CoverScraperSettingsActivity.kt:628-654` Bangumi 失效（`needsReauth`）时行内无直接「登出/解除授权」按钮；`PlayStatusPickDialog`（`PlayStatusDialog.kt:682-708`）仅对 NextMoe 提供行内重试；`GameScreen.kt:933-977` 抽屉拉取进行中状态版本变更时，`finally` 用 `postGates` 覆盖 `lastPlayStatusGates` 可能跳过当次摘要重拉。
- **#11 健壮性与安全对称性**：`SettingsScreen.kt:180` `GitHubUpdateChecker.hasUpdate.collectAsState()` 未用 `collectAsStateWithLifecycle()`；`GameQuickActionsDialog.kt:148, 165` 在主线程同步删裁剪临时图；`SheetMotion.kt:241-272, 437-443` `navigateAway` 推页延迟窗口内强杀重建不清空选定态；`NextMoeAuthService.parseLoopbackCallbackQuery`（`653-658`）可补 `expectedState.isBlank()` 防卫，`GameScreen.deleteCoverFile`（`403-413`）可补 `file:` scheme 检查与 `CoverSupport` 对齐。
- **#12 布局边界与测试细节**：`AppNavItem.kt:175-185` 在 `constraints.maxWidth == 0` 时未 `.coerceAtMost(constraints.maxWidth)`；`GameLibraryFacade.resetCachesForTest()` 与 `seedGamesForTest(null)` 对 `bindingRevisions` 清理口径不一致；`BangumiTokenVerificationTest`、`CoverScrapeMergeTest`、`SingleFlightRefreshTest`、`AuthStatusLastErrorKindTest` 等 8 个 JVM 单测尚未切入 `AuthAndCoverTestStubsRule`；`OAuthExchangeAndIdentityTest.kt:104-130` `flowThread.join` 未入 `finally`；`CustomCoverAndDownloadTest` 与 `CoverAndSettingsNarrowUiTest:154` 含 `Thread.sleep` 轮询/延时。
- **#13 代码整洁与 UI 微瑕**：多处次级文案沿用 `MaterialTheme.colorScheme.onSurfaceVariant` 未收口 `Color.kt`；`PlayStatusDialog`、`CoverScraperSettingsScreen`、`GameActionsSheet`、`CoverSearchDialog` 单个 Composable 超过 340–540 行；`GameScreen.kt:761` 冗余 `com.tyranor.next.ui.common.NoRippleButton`、多处内联 `android.util.Log.*`/`android.widget.Toast.*`、`CoverScraperSettingsActivity.kt:47` 多余 `ComposeColor` 别名；`CoverScraperSettingsActivity.kt:815-821, 850-856` `EXPIRED`/`else` 返回同资源；`GameScreen.kt:1157, 1167` 重复调用 `sheetMotion.interact()`；`CredentialTokenDialog.kt:115` `statusIsError = false` 不可达；三语言标题动词（「粘贴」vs「设置」）及 VNDB 选项引号微差。
- **#14 文档精度**：`docs/应用持久化数据清单.md` 第 71 行 `nav_style` 缺白雾玻璃说明、第 116 行 `VndbCoverService.kt`（实际 `84-182`）与 `GameScreen.kt`（实际 `403-413`）行号略偏、第 190 行将 RPG Maker XP 写 `settings.useRuby18` 创建文件与 Ren'Py/Ruffle 策略 A 混写；`AGENTS.md:138` 关于 `play_error_*` 借用位置的括号列举略窄于 `PlayStatusErrors.kt:12-16` KDoc。

---

## 六、已核实合规的关键领域

1. **三层架构与错误协议**：`core/` 零 Compose/UI 导入，[HttpJson.kt](../app/src/main/java/com/tyranor/next/core/net/HttpJson.kt) 保持严格中性，`ui/` 无引擎包越层引用，`engine/` 本分支零改动；全部新增/修改 `core` 接口返回类型化结果或携带错误码异常，`CancellationException` 均正确传播（除 #2 指出的基线 `GameLibraryRepository.init`）。
2. **数据持久化、OAuth 与密钥安全**：`GameEntity` / `GameLibraryDao` / `GameLibraryDatabase` v3→v6 与 `app/schemas/{4,5,6}.json`、`GameRecordCodec`（16 字段）及 `EngineScanner.mergeScannedGame` 完全一致；`backup_rules.xml` 与 `data_extraction_rules.xml` 排除全部 4 套 `*_auth_secure.xml`、`hikarinagi_auth.xml`、`webdav_sync_secure.xml` 与 `game_library.db*`；回调 Activity `exported="false"`、`PendingIntent.setPackage`、PKCE S256、NextMoe `127.0.0.1:0` 绑定、`state`/`nonce` 恒定时间比对与日志脱敏均核实合规。
3. **UI 规范、动效与门禁**：`AppSecondaryTopBar`、弹窗 `AppAlertDialog` + `DialogTextButton` + `NoIndication`、`AppSearchField`、`Switch`/`SwitchPreference`、`AppNotice`（无新增 `Toast.makeText`）、`AppComponentShape`、`Color.kt` 动态常量与具名动效入口均按 `AGENTS.md` 执行；三语言文案门禁、动效边界门禁、脚本自测、图形资源回归、65 个定向单测文件 497 项用例与增量 Lint 均通过检查。
