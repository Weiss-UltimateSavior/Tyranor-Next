# `codex/bangumi-vndb-play-status` 分支审计报告（2026-11-16 · R7 工作区最新完整状态）

- **审计对象**：`codex/bangumi-vndb-play-status`（`HEAD cf56a52e` 叠加当前工作区 63 个已修改跟踪文件，以及 `androidTest/`、`test/` 下的 11 个新增未跟踪源码/测试文件；相对基线 `origin/main` `709687a3` 共涉及 99 个跟踪文件变化与 11 个新增未跟踪代码/测试文件）。
- **审计基准**：[AGENTS.md](../AGENTS.md)、[CONTEXT.md](../CONTEXT.md)、[ARCHITECTURE.md](../ARCHITECTURE.md)、[SPEC.md](../SPEC.md)。
- **审计方式**：严格遵循 `AGENTS.md`「测试与审核」强制要求，分派 **5 个相互独立的只读审查子 Agent**（代码逻辑与并发竞态、架构边界与文档一致性、代码健壮性/安全与生命周期、回归风险与测试质量/覆盖、代码规范/UI/动效与三语言文案）对当前磁盘最新完整状态独立审查；主 Agent 对全部发现逐一回源核对文件路径与行号，并实际运行本地门禁、定向与全量 JVM 单测、`androidTest` 编译与 Android Lint 增量检查。

> 说明：工作区既有的历史审计快照（`docs/play-status-audit-20261114.md`、`docs/play-status-audit-20261115.md`、`docs/play-status-audit-20261115-cf56a52e.md`、`docs/play-status-audit-20261115-post-cf56a52e.md`、`docs/play-status-audit-20261115-r5.md` 与 `docs/play-status-audit-20261115-r6.md`）完整保留不覆盖。R6 中的全部 **2 项 High** 与绝大多数 **Medium / Low** 问题已在 2026-11-16 凌晨工作区修订中得到修复或补充（详见第三节）；本报告基于当前最新磁盘状态重新审计。

---

## 一、已执行验证与实测结果

| 检查项 | 命令 | 结果 |
| --- | --- | --- |
| 三语言字符串与硬编码 CJK 门禁 | `python tools/check-hardcoded-ui-strings.py` | **通过**（`Hardcoded UI string check passed.`，zh / en / ja 三语言键完全对齐，无 Kotlin 硬编码 CJK） |
| 动效边界门禁 | `python tools/check-motion-boundaries.py` | **通过**（`Motion boundaries: 366 Kotlin files, 0 violations.`） |
| 本地门禁脚本单测 | `python tools/test-hardcoded-ui-strings.py` & `python tools/test_motion_boundaries.py` | **通过**（5 用例 + 11 用例全通过） |
| 本分支定向 JVM 单元测试 | `./gradlew.bat :app:testDebugUnitTest --rerun --tests "com.tyranor.next.core.auth.*" --tests "com.tyranor.next.core.cover.*" --tests "com.tyranor.next.core.status.*" --tests "com.tyranor.next.core.net.*" --tests "com.tyranor.next.core.backup.*" --tests "com.tyranor.next.core.game.scan.*" --tests "com.tyranor.next.core.game.storage.*" --tests "com.tyranor.next.ui.auth.*" --tests "com.tyranor.next.ui.cover.*" --tests "com.tyranor.next.ui.game.*" --tests "com.tyranor.next.ui.main.*" --no-daemon` | **存在 1 次并发竞态偶发失败（重跑通过）**：首轮 492 项完成、1 项失败——`OAuthExchangeAndIdentityTest > ensureIdentityAndUsernameLoadedBackfillsBlankUsernamesAndSkipsWhenAlreadyPopulated`（`expected:<3> but was:<4>`）；单独与同包重跑及二次全量定向重跑通过（根因见第五节 #1） |
| 全量 JVM 单元测试 | `./gradlew.bat :app:testDebugUnitTest --no-daemon --continue` | **本分支新增/修改用例该轮全通过**（共 1822 项完成、31 项失败、18 项跳过；31 项失败均为 Windows 宿主环境既有路径/符号链接测试：`RuffleRuntimeEnvironmentTest` 3、`EthornellOperationGateTest` 5、`LaunchEntryPathTest` 7、`EthornellSaveStoreTest` 14、`EthornellSettingsTest` 2） |
| AndroidTest 编译检查 | `./gradlew.bat :app:compileDebugAndroidTestKotlin --no-daemon` | **通过**（`BUILD SUCCESSFUL`，含 `GameLibraryDatabaseDeviceMigrationTest`、`DeviceStorageAndAuthCryptoTest`、`CoverAndSettingsNarrowUiTest`、`PlayStatusDrawerSummaryTest`） |
| Android Lint 增量检查 | `./gradlew.bat :app:lintDebug --no-daemon` + `python tools/check-lint-diff.py --base origin/main app/build/reports/lint-results-debug.xml` | **变更源码 0 Error**（`Lint errors in reports: 1. Errors on changed sources: 0.`，唯一 Error 为本机 `local.properties:1` `[PropertyEscape]`；变更文件无新增 Error） |
| 未执行项 | 真机/模拟器 `connectedDebugAndroidTest`（`adb devices` 无在线设备且 CI 未运行）、真实浏览器 OAuth 回调闭环、真实 AndroidKeyStore 密钥失效恢复、360dp 窄屏真机渲染、`:engine` 相关任务（本分支 `engine/` 零改动） | **未执行**（不得视同已验收） |

---

## 二、总体审计结论

- **阻断级缺陷**：**0 项 High 级生产阻断缺陷**。三层架构（`ui -> core -> engine` 与中性 `core/net`）、`core -> ui` 错误协议（新增/修改接口全部类型化并透传 `CancellationException`）、Room v3→v6 显式迁移与 schema 导出、4 套 `*_auth_secure.xml` Keystore 分片锁与备份排除、OAuth PKCE S256 / 恒定时间 `nonce` / 环回防护、`VndbCoverService.saveCustomCover` 原子哈希命名与缓存清理、`CoverScrapeTaskManager` 逐源修订号并发防回滚及 `CoverSearchDialog` / `PlayStatusDialog` / `CoverScraperSettingsActivity` 生命周期收敛均已稳健落地。
- **剩余关注点**：本次 5 维独立审查与主 Agent 实测核验确认 **8 项中严重度（Medium）问题**（#1–#8，涵盖实测复现的裸线程跨用例污染、`CredentialTokenDialog` 清空按钮误入 `LocalDialogExit` 闪回、`MainLibraryViewModel.replaceGame` 事后快照回滚、`getValidAccessTokenResult` 并发登录窗口、`GameLibraryFacade.cacheLock` 主线程争锁、退场动画取消后 `rememberSaveable` 复活、测试夹具隔离与覆盖缺口、三语言术语与基线文档/取消语义细节）及 **12 项低严重度（Low）问题组**（#9–#20）。

---

## 三、前序（R6）重点问题修复核查表

| 前序编号 | 原问题摘要 | 当前工作区核查 | 核实位置与说明 |
| --- | --- | --- | --- |
| R6 #1（高） | OAuth 发起（NextMoe `ServerSocket` 绑定、`storeLock`）与 AppAuth 回调在主线程执行 Keystore 加解密及锁竞争 | **已完整修复** | `BangumiAuthService.kt:124-298`、`HikarinagiAuthService.kt:107-301`、`NextMoeAuthService.kt:165-219` 将 `ServerSocket` 绑定、代次读写与换码落盘全部下沉至 `Dispatchers.IO`；`SecureAuthStore.kt:66-70, 204-279` 改为按 `keyAlias` 分段加锁并缓存 `SecretKey`。 |
| R6 #2（高） | `GameLibraryDatabaseMigrationTest` 为 `Proxy` 假迁移且缺少真实 SQLite `v3/v4/v5 -> v6` 迁移测试 | **设备测试已补齐，待真机实跑** | 新增 `androidTest/.../GameLibraryDatabaseDeviceMigrationTest.kt` 完整覆盖真实 SQLite `v2/v3/v4/v5 -> v6`（含 `runtime_module_package` 索引、`getRecentGames`、`getQuickLaunchGames`、`updateCover`），已通过编译；`adb devices` 无设备故尚未真机运行。JVM 侧保留 `Proxy` SQL 字符串核对 + 真实 `2..6.json` schema 校验（见 #6）。 |
| R6 #3（中） | `VndbCoverService.saveCustomCover` 原地覆盖且异常删旧图、同名覆盖不刷新 `CoverBitmapCache` | **已完整修复** | `VndbCoverService.kt:83-170` 改用临时文件 + 内容 SHA-1 短哈希文件名 `custom_<gameKey>_<hash>.<ext>` + `moveTempCover` 原子移动 + `GameLibraryFacade.updateGameCoverMetadata` + `notifyGamesChanged()` + `deleteCachedCover` 回收旧图，并由 `CustomCoverAndDownloadTest` 与 `DeviceStorageAndAuthCryptoTest` 覆盖。 |
| R6 #4（中） | `NoRippleActionText` 强制单行省略导致 `InputSettingsScreen` 5 按钮 `Row` 与 `CoverSearchDialog` 底部栏窄屏截断 | **已完整修复** | `InputSettingsScreen.kt:590-611` 与 `GameScreen.kt:1756-1786` 均已迁至 `FlowRow`；`CoverAndSettingsNarrowUiTest` 已覆盖 `ProfileRow` 360dp 折行。 |
| R6 #5（中） | `CoverSearchDialog` `binding` 期间未禁用二级返回，退场期未拦截 `onCandidateClick` | **已完整修复** | `GameScreen.kt:1269-1299, 1478, 1521, 1539, 1765-1781` 与 `GameOverlayMotion.kt:85` 增加 `!binding && !overlay.isClosing` 与 `coverSearchSessionId` 守卫（退场动画中途旋转取消见 #5）。 |
| R6 #6（中） | `GameScreen`、`PlayStatusDialog`、`CoverScraperSettingsActivity` 使用 `collectAsState()` 在后台触发重组与 Keystore/网络工作 | **已修复** | `GameScreen.kt:252, 840-843, 1335`、`PlayStatusDialog.kt:308-311`、`CoverScraperSettingsActivity.kt:147-153` 全部迁至 `collectAsStateWithLifecycle()`（`nextmoeAuthEvent` 设 `Lifecycle.State.RESUMED`）并将状态读取下沉 `Dispatchers.IO`（仅 `MainScreen.kt:161` 留有一处 `LaunchFailureBus.failure.collectAsState()`，见 #11）。 |
| R6 #7（中） | `SingleFlightRefresh` 等待方在「在途刷新期间并发完成新登录」场景误判失败 | **主体已修复，留进闸前窗口** | `SingleFlightRefresh.kt:29-55` 新增 `adoptWaiter` 与 `lastResult`，`SecureAuthStore.kt:304-326` 增加 `credentialEpochChanged` 放行分支，并由 `BangumiTokenRefreshTest`、`HikarinagiTokenRefreshTest`、`NextMoeAuthServiceTest` 与 `SingleFlightRefreshTest` 覆盖；仅进闸前更小并发窗口仍存在误判可能（见 #3）。 |
| R6 #8（中） | `SPEC.md`、`AGENTS.md`/`ARCHITECTURE.md` 存量偏差注记与 `CHANGELOG.md` 结构滞后 | **已完整修复** | `SPEC.md:252` 已更新四源授权描述；`AGENTS.md:136` 与 `ARCHITECTURE.md:175` 已补列 `core/updater`；`CHANGELOG.md:3-11` 已理顺至 `## 未发布` 之下。 |
| R6 #9–#11（中） | 10 份自复制非线程安全 `Proxy` `SharedPreferences`、缺公共夹具、多条关键链缺单测 | **主体已显著改善** | 已抽离线程安全 `InMemorySharedPreferences` 与 `AuthAndCoverTestStubsRule` 替换全部测试 `Proxy`；新增 `OAuthExchangeAndIdentityTest`、`CustomCoverAndDownloadTest`、`PlayStatusFullOperationsTest`、`CoverAndSettingsNarrowUiTest`、`PlayStatusDrawerSummaryTest`、`DeviceStorageAndAuthCryptoTest`。剩余夹具竞态与缺口见 #1、#6。 |
| R6 #12–#16（低） | 清空并发锁、`activeFlowId` 可见性、旋转态保存、漏记 `Log.w`、`finally`、原子递增、文档行号与文案 | **主体已修复** | `CredentialTokenDialog` 增加 `clearing`/`busy`、`NextMoeAuthService.activeFlowId` 加 `@Volatile`、`CoverScraperSettingsActivity` 保留 `rememberLazyListState`、`CoverSearchDialog` 二级态 `rememberSaveable`、`CoverSourcePickerDialog` IO 预读、4 处 `Log.w` / `finally` / `update` / `synchronized` 与 `PlayStatusServiceHttpTest` import 均已落地；余留及新出现细节见第四、五节。 |

---

## 四、问题清单总览（当前最新工作区状态）

| No. | 级别 | 维度 | 问题摘要 | 主要位置 |
| --- | --- | --- | --- | --- |
| 1 | 中 | 测试/并发 | 三端 `completeOAuthTokenExchange` 派生无主守护线程导致 `OAuthExchangeAndIdentityTest` 实测偶发失败；`CoverScrapeTaskManager.resetForTest()` 不等取消完成，`AuthAndCoverTestStubsRule` 漏复位部分静态状态 | `BangumiAuthService.kt:332`、`HikarinagiAuthService.kt:331`、`NextMoeAuthService.kt:806`、`CoverScrapeTaskManager.kt:161-189`、`AuthAndCoverTestStubsRule.kt:49-69` |
| 2 | 中 | UI/动效 | `CredentialTokenDialog`「清空」按钮置入 `dismissButton` 槽位误继承 `LocalDialogExit`，异步写 prefs 超过 80ms 时弹窗退场后闪回 | `CredentialTokenDialog.kt:281-302`、`AppAlertDialog.kt:160-196, 347-349`、`DialogClickEffects.kt:31-40` |
| 3 | 中 | 逻辑/并发 | `getValidAccessTokenResult` 在 `validStoredAccessToken` 与 `readRefreshSnapshot` 之间并发新登录（个人令牌或 OAuth）仍可能误判 `ConfigMissing` / `RefreshFailed` | `BangumiAuthService.kt:354-424`、`HikarinagiAuthService.kt:354-426`、`NextMoeAuthService.kt:819-881`、`SingleFlightRefresh.kt:29-55`、`SecureAuthStore.kt:304-326` |
| 4 | 中 | 逻辑/并发 | `MainLibraryViewModel.replaceGame` 在事后取 `before` 并全行覆盖，封面绑定/自定义封面/改名/启动文件异步窗口内可回滚并发更新 | `MainLibraryViewModel.kt:217-241`、`GameScreen.kt:1275-1290, 1308-1312`、`GameQuickActionsDialog.kt:205-220, 328-331, 529, 549` |
| 5 | 中 | 健壮性/线程 | `GameLibraryFacade.cacheLock` 在后台 `readBlocking`（Room 迁移/全表查询）期间与主线程 `dirPicker` 及 `bindingRevision` 争锁 | `GameLibraryFacade.kt:101-104, 185-189, 252-254, 429-441`、`SettingsScreen.kt:216-217`、`GameScreen.kt:305`、`MainLibraryViewModel.kt:95` |
| 6 | 中 | 健壮性/生命周期 | `GameOverlayMotion.dismiss` 与 `MotionSheetController.animateClose` 退场期被旋转取消时不交付 `onDismiss`/`onClosed`，重建后 `rememberSaveable` 弹窗/抽屉复活 | `GameOverlayMotion.kt:84-99, 102-109`、`SheetMotion.kt:272-294`、`GameScreen.kt:248, 831, 1422-1424` |
| 7 | 中 | 测试质量/覆盖 | 真机 SQLite 迁移尚未实跑且缺 v1 破坏性重建/降级重建；`CoverSearchCandidateCodecTest`、`GameLibraryFacade` 真持久化集成、`MainLibraryViewModel` 协程、`PlayStatusDialog`/`CredentialTokenDialog` 窄屏 UI 测试仍有缺口 | 见第六节 |
| 8 | 中 | 文案/文档/基线 | 三语言 `strings.xml`「令牌/token」与「授权/登录」混用；`docs/应用持久化数据清单.md:175, 377` 将 `configuration.json` 错归 Artemis；基线 `GitHubUpdateChecker.check` `runCatching` 吞 `CancellationException` | `values*/strings.xml`、`docs/应用持久化数据清单.md:175, 377`、`GitHubUpdateChecker.kt:51-52` |
| 9 | Low | 安全/边界 | `BangumiAuthService` / `HikarinagiAuthService` OAuth `state` 校验未判空且未用 `MessageDigest.isEqual` | `BangumiAuthService.kt:240-245`、`HikarinagiAuthService.kt:219-224` |
| 10 | Low | 健壮性/存储 | `CoverImageCache.deleteCachedCover` 允许命中目录自身且未限 scheme；`VndbCoverService.saveCustomCoverFromStream` 缺字节上限；并发删游或 `existedBefore` TOCTOU 可留孤儿/删新图 | `CoverSupport.kt:103-121`、`VndbCoverService.kt:115-169`、`CoverScraperService.kt:226-249` |
| 11 | Low | 生命周期 | `MainScreen` 使用 `collectAsState()` 收集 `LaunchFailureBus.failure`；`GameShortcutCropDialog` 裁剪写盘途中取消可能残留单次临时 PNG | `MainScreen.kt:161`、`GameShortcutCropDialog.kt:137-159` |
| 12 | Low | 逻辑/状态同步 | 抽屉 `vndbReady` 将 `PERMISSION` 视为 `needsReauth` 阻断网页补权后自愈；抽屉打开期 401 不刷尾注；`search_cover` 缺 `sheetMotion.interact()`；`nextmoeRequestWithToken` 2xx 未先判 `RATE_LIMITED`；`clearFinished` 竞争 `MainLibraryViewModel` 对齐时机 | `GameScreen.kt:840-857, 876, 1115`、`PlayStatusService.kt:1002-1017`、`CoverScraperSettingsActivity.kt:256-269`、`MainLibraryViewModel.kt:132` |
| 13 | Low | UI/布局细节 | `AppAlertDialog` 与 `GameShortcutCropDialog` 的 `FlowRow + Spacer(weight)` 极端折行产生 4dp 空占位；`NoRippleActionText` 满宽超长译文省略号截断；`AppNavItem` 在 320dp 下 `trailingText` 占满 50% | `AppAlertDialog.kt:336-356`、`GameShortcutCropDialog.kt:361-373`、`NoPressFeedbackButtons.kt:75-76`、`AppNavItem.kt:170-204` |
| 14 | Low | 规范/清理 | `GameScreen.kt:82, 217` 重复 import `mutableIntStateOf`；`NextMoeAuthService.isCurrentFlow` 与 `VndbAuthStore.savePermissions` 未使用；`CoverScraperSettingsActivity.kt:340` 冗余全限定名；`PlayStatusService.kt:764-1018` 三段请求可提取模板；长 Composable | 同左列位置 |
| 15 | Low | 文档/文案微瑕 | `PlayStatusService.HikarinagiStatus.exists` KDoc 仍写 `GET 404`；日文 `play_error_vndb_token_permission` 漏译「改完权限即可用」；`game_play_status_public_hint` 中文句号差异；`docs/应用持久化数据清单.md` 多处行号偏移；`GameShortcutManager.kt:56-75`（基线）`runCatching` 未放行 `CancellationException` | `PlayStatusService.kt:313-317`、`values-ja/strings.xml:782`、`values/strings.xml:733`、`docs/应用持久化数据清单.md`（多行） |

---

## 五、重点问题详情与修复建议

### 1.【中 / #1】实测复现：`completeOAuthTokenExchange` 派生无主守护线程导致 `OAuthExchangeAndIdentityTest` 偶发失败，且 `CoverScrapeTaskManager.resetForTest()` 未等待协程退出

- **位置**：
  - `app/src/main/java/com/tyranor/next/core/auth/BangumiAuthService.kt:332-338`
  - `app/src/main/java/com/tyranor/next/core/auth/HikarinagiAuthService.kt:331-337`
  - `app/src/main/java/com/tyranor/next/core/auth/NextMoeAuthService.kt:806-811`
  - `app/src/test/java/com/tyranor/next/core/auth/OAuthExchangeAndIdentityTest.kt:45-81, 144-179`
  - `app/src/main/java/com/tyranor/next/core/cover/CoverScrapeTaskManager.kt:161-189`
  - `app/src/test/java/com/tyranor/next/testing/AuthAndCoverTestStubsRule.kt:24-69`
- **实测证据与根因**：
  1. 本轮运行 `./gradlew.bat :app:testDebugUnitTest --rerun --tests ...`（62 个测试文件）时，`OAuthExchangeAndIdentityTest > ensureIdentityAndUsernameLoadedBackfillsBlankUsernamesAndSkipsWhenAlreadyPopulated` 在 `OAuthExchangeAndIdentityTest.kt:169` 实测失败一次：`AssertionError: expected:<3> but was:<4>`（随后单独/同套件重跑通过）。
  2. 回溯原因：`OAuthExchangeAndIdentityTest` 第 1 个用例（第 47、72 行）调用 `BangumiAuthService.completeOAuthTokenExchange` 与 `HikarinagiAuthService.completeOAuthTokenExchange`。两者与 `NextMoeAuthService.completeOAuthTokenExchange` 一样，均在落盘后直接启动无句柄的守护线程（`bangumi-identity-load` / `hikarinagi-username-load` / `nextmoe-username-load`）调用 `fetchAndSaveUserIdentity` / `fetchAndSaveUsername` -> `httpJsonResult`。由于第 1 个用例耗时不足 1ms 即结束，该守护线程未能被 `@After`/`AuthAndCoverTestStubsRule` 拦截或等待，在 5–6ms 后进入第 3 个用例设置 `testHttpRoundTrip` 的窗口时触发第 4 次 HTTP 回调，直接造成间歇性失败。
  3. 同样地，`CoverScrapeTaskManager.resetForTest()` 仅调用 `activeJob?.cancel()` 而不等待协程退出，一旦某个测试在任务运行中提前失败或重置 `Dispatchers.Main`，`postFinished()` 里的 `withContext(NonCancellable + Dispatchers.Main.immediate)` 会抛 `IllegalStateException` 或在下一用例期间回写 `_state`。
  4. 此外，`AuthAndCoverTestStubsRule` 未复位 `HikarinagiAuthStore.migrationDone`（`HikarinagiAuthStore.kt:99-100`）与 `GameLibraryFacade.rootsCache`（`GameLibraryFacade.kt:50-51`）；`*AuthStore.clear(context)` 也不清除 `memoryTokens` 中的测试条目。
- **修复建议**：
  - 将 `completeOAuthTokenExchange` 中的身份拉取改为可在受管线程池/测试期可等待或可关闭的辅助机制（事实上 `handleAuthorizationResponse` 与 `runLoopbackFlow` 本身已在 IO 协程/后台线程上），并在 `AuthAndCoverTestStubsRule` 等待/隔离收尾。
  - `CoverScrapeTaskManager.resetForTest()` 通过作用域取消 + `runBlocking { activeJob?.cancelAndJoin() }`（或任务代次隔离 `postFinished`）消除跨用例回写；在 `AuthAndCoverTestStubsRule` 补充重置 `HikarinagiAuthStore.migrationDone` 与 `GameLibraryFacade.rootsCache`。

### 2.【中 / #2】`CredentialTokenDialog`「清空」按钮位于 `dismissButton` 槽位误继承 `LocalDialogExit`，异步清盘超时 80ms 会闪回

- **位置**：
  - `app/src/main/java/com/tyranor/next/ui/cover/CredentialTokenDialog.kt:281-302`
  - `app/src/main/java/com/tyranor/next/ui/common/AppAlertDialog.kt:160-196, 347-349`
  - `app/src/main/java/com/tyranor/next/ui/common/DialogClickEffects.kt:31-40`
- **根因**：
  - `AppAlertDialog` 的 `dismissButton` 槽位外层包了 `CompositionLocalProvider(LocalDialogExit provides exitController)`。
  - `DialogTextButton` 在 `LocalDialogExit` 非空时会先播放下沉/淡出退场动画，然后才同步执行 `onClick`；`AppAlertDialog.runDialogExitAction` 随后 `delay(80)`，若弹窗仍在组合中即执行 `afterAction` 恢复可见与交互。
  - `CredentialTokenDialog` 的「清空」按钮在 `onClick` 内才置位 `clearing = true` 并启动 `scope.launch { withContext(Dispatchers.IO) { clearCredential(context) }; onDismiss() }` 后立即返回，导致退场动画期间 `clearing` 仍为 `false`，且当 `clearCredential`（持 `storeLock` + 写 prefs）耗时超过 80ms 时，弹窗已退场又被 `afterAction` 弹回，随后再瞬间被 `onDismiss()` 移除。
- **修复建议**：
  - 在「清空」按钮外层覆盖 `CompositionLocalProvider(LocalDialogExit provides null)`（或将清空操作独立于 `LocalDialogExit`），先锁定 `clearing = true` 在 IO 完成清空，再通过普通关闭路径退出。

### 3.【中 / #3】`getValidAccessTokenResult` 在 `validStoredAccessToken` 与 `readRefreshSnapshot` 之间并发新登录仍可能误判 `ConfigMissing` / `RefreshFailed`

- **位置**：
  - `app/src/main/java/com/tyranor/next/core/auth/BangumiAuthService.kt:354-357, 385-424`
  - `app/src/main/java/com/tyranor/next/core/auth/HikarinagiAuthService.kt:354-357, 385-426`
  - `app/src/main/java/com/tyranor/next/core/auth/NextMoeAuthService.kt:819-822, 850-881`
  - `app/src/main/java/com/tyranor/next/core/auth/SingleFlightRefresh.kt:29-55`
  - `app/src/main/java/com/tyranor/next/core/auth/SecureAuthStore.kt:304-326`
- **根因**：
  1. 线程 T2 在 `validStoredAccessToken(context)` 返回 `null` 后、进入 `readRefreshSnapshot(context)` 之前，若用户并发完成新登录：
     - 若新登录为 **Bangumi 个人令牌**（`refreshToken == ""`、`expiresAtMillis == 0L`），`BangumiAuthService.kt:393-398` 读到 `initialRefreshToken.isBlank()` 后未复查 `validStoredAccessToken`，直接返回 `ConfigMissing`。
     - 若新登录为 **OAuth 令牌** 且此时已有旧 leader T1 占住 `refreshGate`，T2 读到的 `initialEpoch` 已是新代次 `E + 1`；T2 进入 `refreshGate.run` 的 `while (running)` 等待路径，T1 因旧代次 `E` 提交被拒返回 `taskResult = null`，T2 唤醒后进入 `adoptWaiter`，因 `E + 1 == E + 1`（`credentialEpochChanged == false`）而 `leaderToken == null`，`SecureAuthStore.adoptSingleFlightAccessToken`（第 317–321 行）返回 `null`（`RefreshFailed`）。
- **修复建议**：
  - 在 `refreshAccessToken` 中 `readRefreshSnapshot` 发现凭据不足或准备进闸前，复查一次 `validStoredAccessToken`；
  - 将 `initialEpoch` 采样置于 `validStoredAccessToken` 之前（或让 `SingleFlightRefresh` 在 `leaderToken == null` 且 `recheck()` 命中有效快路径时先核对是否为新代次有效令牌），消除快照窗口分裂。

### 4.【中 / #4】`MainLibraryViewModel.replaceGame` 事后取 `before` 快照并在异步窗口覆写整行，可能回滚并发更新

- **位置**：
  - `app/src/main/java/com/tyranor/next/ui/main/MainLibraryViewModel.kt:217-241, 455-482, 523-534`
  - 调用点：`GameScreen.kt:1275-1290`（封面绑定）、`1308-1312`（启动文件）、`GameQuickActionsDialog.kt:205-220`（自定义封面）、`328-331, 529, 549`（改名）
- **根因**：
  - `CoverScraperService.bindCoverCandidate` 与 `VndbCoverService.saveCustomCover` 已在 IO 原子落库并调用 `notifyGamesChanged()`，随后 UI 仍回传完整 `ScanGame` 给 `replaceGame(updated)`。
  - 若在后台 IO 完成或弹窗退场动画（约 200ms）期间，并发操作（游玩状态解绑、批量刮削、标题回写或重扫）更新了 `_uiState` 或 `gamesCache` 中的其他字段，`replaceGame` 第 218 行读取的事后 `before` 已包含新字段，而 `updated` 仍持旧快照，从而在 `mergeChangedGameFields` 将并发新字段覆盖回滚为旧值（对比游玩状态绑定已改用局部合并 `applyBindingUpdate`）。
- **修复建议**：
  - 封面绑定/自定义封面/改名/启动文件改用按字段合并的局部更新方法（或像 `applyBindingUpdate` 一样在已原子落库的路径仅局部合并封面/元数据字段，不再用旧快照整行调用 `replaceGame`）。

### 5.【中 / #5】`GameLibraryFacade.cacheLock` 存在后台 `readBlocking` 与主线程 `dirPicker` / `bindingRevision` 锁竞争

- **位置**：
  - `app/src/main/java/com/tyranor/next/core/game/storage/GameLibraryFacade.kt:101-104, 185-189, 252-254, 429-441`
  - `app/src/main/java/com/tyranor/next/ui/settings/SettingsScreen.kt:216-217`、`app/src/main/java/com/tyranor/next/ui/game/GameScreen.kt:305`、`app/src/main/java/com/tyranor/next/ui/main/MainLibraryViewModel.kt:95`
- **根因**：`cacheLock` 保护的 `loadGamesLocked` / `loadRootsLocked` 在缓存未预热或 `invalidatePersistenceCaches()` 后会在锁内执行 `GameLibraryRepository.readBlocking`（`runBlocking` 等待 Room 迁移与查询）；同时主线程 `dirPicker` 回调（`saveRoot`/`loadRoots`）与 `MainLibraryViewModel` 收集 `gameUpdates` 时的 `bindingRevision` 均请求同一把 `cacheLock`。
- **修复建议**：将 `SettingsScreen`/`GameScreen` 的 `dirPicker` 回调下沉 `Dispatchers.IO`，并将 `bindingRevisions` 拆出独立锁或 `ConcurrentHashMap`。

### 6.【中 / #6】`GameOverlayMotion.dismiss` 与 `MotionSheetController.animateClose` 退场期间被旋转取消时不交付关闭回调

- **位置**：
  - `app/src/main/java/com/tyranor/next/ui/game/GameOverlayMotion.kt:84-99, 102-109`
  - `app/src/main/java/com/tyranor/next/ui/common/motion/SheetMotion.kt:272-294`
  - `app/src/main/java/com/tyranor/next/ui/game/GameScreen.kt:248, 831, 1422-1424`、`GameQuickActionsDialog.kt:119`、`GameShortcutCropDialog.kt:105-107`
- **根因**：两处关闭动画都在 `scope.launch` 中先释放窗口触摸并播放 ~200ms 退场动画，最后才调用 `action()` / `onClosed()`；若期间因屏幕旋转导致 `scope` 取消，协程在挂起点抛出 `CancellationException` 跳过回调，重建后宿主 `rememberSaveable`（如 `coverSearchSource`、`shortcutCropUriText`、`selectedGameUri`）仍为打开态，已关闭界面重新弹出。
- **修复建议**：参考 `AppAlertDialog.runDialogExitAction`，在 `finally` 中通过 `withContext(NonCancellable)` 确保已受理的关闭回调必交付（抽屉仅在未因 `reopen`/`reopenForGrab` 取消时交付）。

### 7.【中 / #7】测试质量与关键覆盖缺口清单

- **核心测试质量确认**：本分支新增/修改单测与设备测试整体真实度高，`OAuthExchangeAndIdentityTest`（真实环回 Socket）、`CustomCoverAndDownloadTest`（真实 `HttpServer`）、`PlayStatusFullOperationsTest`、`RemoteBindingPreservationTest`、`DeviceStorageAndAuthCryptoTest`（真实 `AndroidKeyStore` + `ContentResolver`）与 `PlayStatusDrawerSummaryTest`（真实多语言 `strings.xml`）均直接验证生产路径而非假桩；`InMemorySharedPreferences` 线程安全且正确处理 `null` 移除与 `StringSet` 防御拷贝（仅存在单 `Editor` 无锁、类型错读不抛 `ClassCastException`、`onCommit` 无变更也触发等与真机 `SharedPreferencesImpl` 的轻微差异）。
- **尚存质量与覆盖缺口**：
  1. `GameLibraryDatabaseMigrationTest.explicitMigrationsEmitExpectedAlterAndIndexSql`（`23-50` 行）仍为 `Proxy` SQL 字符串录制；真实 SQLite 迁移测试 `GameLibraryDatabaseDeviceMigrationTest` 已覆及 v2/v3/v4/v5→v6，但尚未在真机/模拟器实跑，且自行构建 `Room.databaseBuilder` 而未覆盖 `GameLibraryDatabase.get(context)` 的 **v1→v6 破坏性重建**与**降级重建 + 16 字段 `game_scanner` 镜像恢复**路径。
  2. `CoverSearchCandidateCodecTest` 仅测了 `decodeCoverSearchCandidate`，未覆盖 `encodeCoverSearchCandidate` 往返及 `CoverSearchCandidatesSaver` / `CoverSearchCandidateNullableSaver`。
  3. `GameLibraryFacade` + `GameLibraryRepository` + Room 真实持久化与 `writePrefsMirror` 闭环（目前单测与 `DeviceStorageAndAuthCryptoTest` 均设 `skipPersistForTest = true`）缺乏集成测试。
  4. `MainLibraryViewModel` 内部协程收集器（`gameUpdates` 逐源 `bindingRevision` 比对、`acknowledgedEventId` 去重、取消后 `refreshFromStorage` 对齐）缺乏 ViewModel 级单元测试。
  5. `PlayStatusDialog` / `PlayStatusPickDialog` / `SubjectBindDialog`、`CredentialTokenDialog`、`CoverScraperSettingsScreen` 尚无 Compose `androidTest`；`CoverAndSettingsNarrowUiTest` 仅测了 `ProfileRow` 与一级 `CoverSearchDialog`，未覆及 `AppAlertDialog` `splitEnds`、`CropDialogActions`、`AppNavItem(trailingText)` 窄屏大字体布局及 `CoverSearchDialog` 二级态 `binding` 禁用。
  6. 三端 OAuth `startAuthorization` 异常/生命周期分支、`BangumiOAuthCallbackActivity` / `HikarinagiOAuthCallbackActivity` 及 `VndbCoverService.saveCustomCover` 的 `contentResolver.getType` MIME 映射缺少自动化测试。

### 8.【中 / #8】三语言术语一致、持久化清单错归与既有更新检查取消语义

- **三语言 `strings.xml` 术语不统一**：
  - 「令牌」vs「token」：`values/strings.xml` 中 `auth_bangumi_token_exchange_failed` / `missing_fields`（L696–697）、`auth_token_show` / `auth_token_hide`（L713–714）及 `cover_vndb_save_failed`（L580）混用英文 `token` 与中文「令牌」；`values-ja/strings.xml` 在 `auth_vndb_token_*`（L709–712）、`play_error_vndb_*`（L780–784）及 `auth_*_token_exchange_failed/missing_fields` 混用 `token` 与 `トークン`。
  - 「授权」vs「登录」：`values*/strings.xml` 已将入口统一为「授权 / Authorize / 認証」，但 L671–672、L680、L690–691、L699、L707、L791 等 OAuth 文案仍混用「登录 / login / ログイン」。
- **`docs/应用持久化数据清单.md:175, 377` 事实错误（历史既有）**：第 175 行称 `<游戏根>/configuration.json`（含 `useRuby18`）由 `ArtemisPfsUnpacker.kt` 在 Artemis 自动补丁时写入/改写，第 377 行目录树亦归为 `Artemis/ONS 改写`；实际 `ArtemisPfsUnpacker.kt` 不含任何 `configuration.json` 或 `useRuby18` 逻辑，该文件实际属于 `RpgMakerExternalEngineModule`（L231–244）、`RenPyRuntimeEnvironment`（L38）与 `RuffleRuntimeEnvironment`（L56）。
- **既有代码取消语义偏差（非本分支引入）**：`app/src/main/java/com/tyranor/next/core/updater/GitHubUpdateChecker.kt:51-52` 在 `suspend fun check` 中使用 `runCatching { ... }.getOrElse { ... }` 未显式重抛 `CancellationException`（连同第 153 行本地化字符串异常在 `AGENTS.md` / `ARCHITECTURE.md` 存量偏差注记处可一并完善或排期迁移）。

### 9.【Low】其余低严重度细节汇总（#9–#15）

- **#9 OAuth `state` 恒定时间与空值校验**：`BangumiAuthService.kt:242` 与 `HikarinagiAuthService.kt:221` 使用 `response.state != response.request?.state`，应补充 `isNullOrBlank()` 拦截并改用 `MessageDigest.isEqual` 与 `NextMoeAuthService` 对齐。
- **#10 封面文件存储边界**：`CoverImageCache.deleteCachedCover`（`CoverSupport.kt:110`）应将 `filePath == cachePath` 改为仅允许 `"$cachePath${File.separator}"` 子路径并限定 `file:` scheme；`VndbCoverService.saveCustomCoverFromStream`（`L115-128`）应补齐 `MAX_COVER_BYTES` 拷贝上限；`VndbCoverService` / `CoverScraperService` 在并发删游路径返回纯内存副本时应避免遗留新建磁盘封面，并防范 `existedBefore` 同名复用时的删除竞态。
- **#11 生命周期收集与裁剪临时文件**：`MainScreen.kt:161` 应改用 `LaunchFailureBus.failure.collectAsStateWithLifecycle()`；`GameShortcutCropDialog.kt:141-143` 导出裁剪图期间若被取消应确保 `writeShortcutCropBitmap` 产物可即时清理。
- **#12 状态同步与 HTTP 边界**：`GameScreen.kt:876` 的 `vndbReady` 应允许 `VndbAuthError.PERMISSION` 通过以支持网页补权后打开抽屉即自愈；`GameScreen.kt:840-857` 抽屉打开期间发生 401 仅递增 `statusVersion` 不更新 `sessionEpoch` 导致尾注不刷，同页 `L1115` `search_cover` 应补 `sheetMotion.interact()`；`PlayStatusService.kt:1008` 应在 2xx 前预检 `RATE_LIMITED` / `QUOTA_EXCEEDED` 与 `NextMoeCoverService` 对齐；`CoverScraperSettingsActivity` 先消费 `clearFinished` 时分屏下 `MainLibraryViewModel` 跳过 `refreshFromStorage` 的边缘可加校验。
- **#13 极端窄屏/大字号 UI 细节**：`AppAlertDialog.kt:350-352` 与 `GameShortcutCropDialog.kt:372` 在 `FlowRow` 内插 `Spacer(Modifier.weight(1f))` 当首按钮接近满宽拆行时会留下约 4dp 空行间隔；`NoRippleActionText` 单行省略在超长单按钮时截断文案；`AppNavItem.kt:170-204` 在 320dp 且长 `trailingText` 时挤压左侧标题。
- **#14 代码整洁与重复**：`GameScreen.kt:82, 217` 重复导入 `mutableIntStateOf`；`NextMoeAuthService.kt:303-305` `isCurrentFlow` 与 `VndbAuthStore.kt:158-165` `savePermissions` 无调用方；`CoverScraperSettingsActivity.kt:340` 存在冗余包名限定；`PlayStatusService.kt:764-1018` 三处请求流与 `CoverScraperSettingsScreen` 三处授权触发可适度抽公共辅助函数。
- **#15 注释、三语言微瑕与文档行号**：`PlayStatusService.kt:314` `HikarinagiStatus.exists` KDoc 仍写 `GET 404`（实际 200 `data:null`）；`values-ja/strings.xml:782` 漏译「改完权限即可用」；`values/strings.xml:733` 句末句号差异；`docs/应用持久化数据清单.md` 多处代码行号偏移（如第 92、95–97、114、116–117、131、134–135、156、158、176、188–189 行）；基线 `GameShortcutManager.kt:56-75` `runCatching` 未放行 `CancellationException`。

---

## 六、已核实合规的关键领域

1. **三层架构与错误协议**：`core/` 无 Compose/UI 依赖，`ui/` 无引擎层越层引用，`engine/` 未被业务污染，`core/net/HttpJson.kt` 保持中性；本分支全部新增/修改 `core` 接口统一返回类型化结果或携带错误码异常，`CancellationException` 均原样向上传播。
2. **数据持久化、OAuth 与密钥安全**：`GameEntity` / `GameLibraryDao` / `GameLibraryDatabase` v3→v6 与 `app/schemas/{4,5,6}.json`、`GameRecordCodec`（16 字段）与 `EngineScanner` 完全一致；`backup_rules.xml` 与 `data_extraction_rules.xml`（云备份与设备迁移）完整排除全部 4 套 `*_auth_secure.xml`、`hikarinagi_auth.xml`、`webdav_sync_secure.xml` 与 `game_library.db*`；`AndroidManifest.xml` 回调 Activity `exported="false"`、AppAuth `PendingIntent.setPackage`、PKCE S256、`NextMoeAuthService` 仅绑定 `127.0.0.1:0` 且优先恒定时间比对 `state`、OAuth `nonce` 恒定时间校验及日志脱敏均核验通过。
3. **UI 规范、动效与门禁**：`AppSecondaryTopBar`、两档字号（`titleMedium`/`bodyMedium`）、`AppAlertDialog` + `DialogTextButton` + `NoIndication`、`AppSearchField`、`AppNavItem`、`Switch`/`SwitchPreference`、`AppNotice`、`AppComponentShape`、`Color.kt` 动态常量与具名动效入口均按 `AGENTS.md` 落地；`check-hardcoded-ui-strings.py`、`check-motion-boundaries.py`、两脚本自测与变更源码增量 Lint 均 0 违规通过。
