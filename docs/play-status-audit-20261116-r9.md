# `codex/bangumi-vndb-play-status` 分支审计报告（2026-11-16 · R9 工作区最新完整状态）

- **审计对象**：`codex/bangumi-vndb-play-status`（[wt-binding](file:///d:/Tyranor-Next/wt-binding)，`HEAD cf56a52e` 叠加当前工作区未提交修改；相对 `origin/main` `709687a3` 共涉及 **107** 个跟踪文件改动及 **15** 个新增未跟踪代码/测试文件：`androidTest/` 8 个、`test/` 7 个，`engine/` 源码仅 `TyranoStorage.kt` 被文档引用，本分支 `engine/` 零改动）。
- **审计基准**：[AGENTS.md](../AGENTS.md)、[CONTEXT.md](../CONTEXT.md)、[ARCHITECTURE.md](../ARCHITECTURE.md)、[SPEC.md](../SPEC.md)。
- **审计方式**：严格遵循 `AGENTS.md`「测试与审核」强制要求，派发 **5 个相互独立的只读审查子 Agent**（代码逻辑、架构与文档一致性、代码健壮性/线程安全/生命周期与安全、回归风险与测试质量、代码规范/UI/动效与三语言文案）对当前磁盘最新文件执行盲审，并由主 Agent 逐项交叉核实精确文件路径与行号，重新执行本地静态门禁、图形资源回归、定向与全量 App JVM 单测、`androidTest` 编译与 Android Lint 增量检查。

> 说明：历史审计快照（`docs/play-status-audit-20261114.md`、`20261115*.md`、`20261116.md`、`20261116-r8.md`）均完整保留不覆盖。R8 列出的唯一 High（`SettingsScreen.savePath` 主线程 `saveRoot`）及 6 项 Medium 中的 5 项已在工作区后续修订中修复或补齐测试，但新增设备测试暴露出 1 项 High 桩路径错配及 1 项 Medium 日文状态互换，同时独立深审确认其余 10 项 Medium 与 10 项 Low 问题。

---

## 一、已执行验证与实测结果

| 检查项 | 命令 | 实测结果 |
| --- | --- | --- |
| 三语言字符串与硬编码 CJK 门禁 | `python tools/check-hardcoded-ui-strings.py` | **通过**（`Hardcoded UI string check passed.`，zh / en / ja 键集对齐，无新增 Kotlin 硬编码 CJK） |
| 动效边界门禁 | `python tools/check-motion-boundaries.py` | **通过**（`Motion boundaries: 366 Kotlin files, 0 violations.`） |
| 本地门禁脚本单测 | `python tools/test-hardcoded-ui-strings.py` & `python tools/test_motion_boundaries.py` | **通过**（5 项 + 11 项全通过） |
| 图形资源归属回归 | `./gradlew.bat -p buildSrc graphicsResourcesRegression --no-daemon` | **通过**（`Graphics resource regressions passed: 24`，`BUILD SUCCESSFUL`） |
| 本分支定向 JVM 单元测试 | `./gradlew.bat :app:testDebugUnitTest --rerun --tests "com.tyranor.next.core.auth.*" --tests "com.tyranor.next.core.cover.*" --tests "com.tyranor.next.core.status.*" --tests "com.tyranor.next.core.net.*" --tests "com.tyranor.next.core.backup.*" --tests "com.tyranor.next.core.game.scan.*" --tests "com.tyranor.next.core.game.storage.*" --tests "com.tyranor.next.ui.auth.*" --tests "com.tyranor.next.ui.cover.*" --tests "com.tyranor.next.ui.game.*" --tests "com.tyranor.next.ui.main.*" --no-daemon` | **全部通过**（`files=65 tests=497 failures=0 errors=0 skipped=0`） |
| 全量 App JVM 单元测试 | `./gradlew.bat :app:testDebugUnitTest --no-daemon --continue` | **本分支定向用例全通过**（`files=207 tests=1827 failures=31 errors=0 skipped=18`；31 项失败均为 Windows 宿主环境既有路径/符号链接测试：`RuffleRuntimeEnvironmentTest` 3、`EthornellOperationGateTest` 5、`LaunchEntryPathTest` 7、`EthornellSaveStoreTest` 14、`EthornellSettingsTest` 2） |
| AndroidTest 编译检查 | `./gradlew.bat :app:compileDebugAndroidTestKotlin --no-daemon` | **通过**（`BUILD SUCCESSFUL`） |
| Android Lint 增量检查 | `./gradlew.bat :app:lintDebug --no-daemon` + `python tools/check-lint-diff.py --base origin/main app/build/reports/lint-results-debug.xml` | **变更源码 0 Error**（报告总计 `Errors=1, Warnings=403, Info/Hints=10`，`Errors on changed sources: 0.`；唯一 Error 为本机 `local.properties:1` `[PropertyEscape]`） |
| 未执行项 | 真机 `connectedDebugAndroidTest`（`adb devices` 检测到在线设备 `R52Y90DWSMW`，本轮按离线代码审计边界未安装隔离 APK 实跑）、真实浏览器 OAuth 回调闭环、真实 AndroidKeyStore 密钥失效恢复、360dp 窄屏真机渲染、`:engine` 相关检查（`engine/` 零改动） | **未执行**（不得视同已验收；其中 #1 High 显示 `PlayStatusDialogUiTest` 若真机实跑将在 Bangumi 搜索步失败） |

---

## 二、总体审计结论

- **架构、协议与核心安全基线稳固**：`core/` 零 Compose/UI 依赖，`core/net/HttpJson.kt` 保持中性，`core/` 包依赖呈严格 DAG 无环，`ui/` 无引擎层越层引用；新增与修改 `core` 接口全部遵循 `core -> ui` 类型化错误协议并透传 `CancellationException`；Room v3→v6 迁移、schema 导出与 `backup_rules.xml` / `data_extraction_rules.xml` 备份排除一致；OAuth PKCE S256、环回监听、`state`/`nonce` 恒定时间比对与日志脱敏均已闭环，65 个定向单测文件 497 项用例全部通过。
- **当前发现 1 项 High、11 项 Medium 与 10 项 Low 问题**：
  - **High（1 项）**：新增设备测试 `PlayStatusDialogUiTest.kt:119-133` Mock 了错误的 Bangumi 搜索 URL（`api.bgm.tv/search/subject/`）和响应字段（`list` / `air_date`），而生产 `BangumiCoverService.kt:17,53-76` 实际请求 `https://api.bgm.tv/v0/search/subjects` 并解析 `data` / `date`，导致该用例在真机运行时必超时失败。
  - **Medium（11 项）**：涵盖非 UI 删游清理滞留 `GameScreen.kt` 引发 `ui/main -> ui/game` 耦合及重复清理、`GameActionsSheet` 摘要在仅 `statusVersion` 变更（门控未变）时触发无意义重复拉取、日文 `play_status_bangumi_done` 与 `play_status_vndb_finished` 翻译对调、集成/迁移 `androidTest` 未在交换 DB 前冲洗 `GameLibraryRepository.init` / 防抖 `mirrorJob`、并发测试缺 `finally` 保护、`GameLibraryRepository.writePrefsMirror` 未持 `writeMutex` / 事务导致潜在跨表撕裂、`BangumiAuthService` 正常回调路径无条件清 `authInProgress`、快捷方式图标/裁剪输入流缺 `file:` scheme 校验与 `finally` 位图回收、`GameOverlayMotion` 无键 `remember` 及 `ON_PAUSE` 未隔离异常、`GitHubUpdateChecker` 无上限 `readText()` 与 errorStream 回退，以及 `PlayStatusService` / `PlayStatusDialog` 死参数与重复实现。

---

## 三、前序（R8）重点问题修复核查表

| 前序编号 | 原问题摘要 | 当前核查状态 | 核实位置与说明 |
| --- | --- | --- | --- |
| R8 #1（High） | `SettingsScreen` 手动输入目录 `savePath` 在主线程调用 `GameLibraryFacade.saveRoot` | **已修复** | `SettingsScreen.kt:617-619` 已将 `GameLibraryFacade.saveRoot(ctx, target.absolutePath)` 包入 `withContext(Dispatchers.IO)`。 |
| R8 #2（Medium） | `GameLibraryRepository.init` 协程内 3 处 `catch (t: Throwable)` 吞掉 `CancellationException` | **已修复** | `GameLibraryRepository.kt:81,87,93` 已全部补充 `if (t is CancellationException) throw t`。 |
| R8 #3（Medium） | `AppAlertDialog` 退场缺少 `ON_PAUSE` 提前交付导致旋转复活 | **已修复** | `AppAlertDialog.kt:166-211` 已加入 `DisposableEffect(lifecycleOwner)`，在 `ON_PAUSE` 提前交付 `pendingExitAction`（含非 RESUMED 同步兜底与异常隔离）。 |
| R8 #4（Medium） | `AppBottomSheet` 未提供 `NoIndication`，抽屉 `AppNavItem` 残留涟漪 | **已修复** | `AppBottomSheet.kt:125-128` 已提供 `LocalIndication provides NoIndication`，`GameScreen.kt:1160,1247` 抽屉 `AppNavItem` 均显式传 `indication = null`。 |
| R8 #5（Medium） | `GameLibraryDatabaseDeviceMigrationTest` 绕过 `ensureMigrated`；`PlayStatusDrawerSummaryTest` 缺 `try/finally`；`DeviceStorageAndAuthCryptoTest` 残留 Keystore 别名与裸 `Activity()` | **已修复（JVM `Proxy` 迁移作为辅助补充仍保留）** | `GameLibraryDatabaseDeviceMigrationTest.kt:252,280` 已改走 `GameLibraryRepository.ensureMigrated`；`PlayStatusDrawerSummaryTest.kt:61-75` 已加 `try/finally`；`DeviceStorageAndAuthCryptoTest.kt:118-124,167-185` 已在 `finally` 删除 Keystore 别名并改用 `ActivityScenario`。 |
| R8 #6（Medium） | 5 项自动化测试覆盖缺口（`ensureMigrated` 降级自愈、回调 Activity、`PlayStatusDialog`、`CredentialTokenDialog`/`CoverScraperSettingsActivity`、扫描快捷方式链路） | **已补文件，但 `PlayStatusDialogUiTest` 存在 High 桩错配** | 已新增 `OAuthCallbackActivityDeviceTest.kt`、`CoverSettingsAndTokenUiTest.kt`、`PlayStatusDialogUiTest.kt`、`ScanPersistAndShortcutIntegrationTest.kt` 并补齐 `ensureMigrated` 用例；但 `PlayStatusDialogUiTest.kt:119-133` Bangumi 搜索桩写错 URL/JSON 键（见 #1），且未在设备运行。 |
| R8 #7（Medium） | 三语言 `cover_logout`、英文 `Signed-in` vs `Authorized`、日文 `認証状態` 与 `カバー画像取得`/`項目` 不统一 | **已修复（同时暴露日文状态互换 #11）** | `cover_logout` 已统一为「取消授权 / Revoke authorization / 認証解除」；`values-en/strings.xml:745` 已统一 `Authorized`；`values-ja/strings.xml` 已统一 `認証アカウント`、`カバー画像取得設定` 与 `項目`；但 `:754` 与 `:759` 日文译文仍互换（见 #11）。 |
| R8 #8–#14（Low） | `MainLibraryViewModel` 终态刷新、`SettingsScreen` `collectAsStateWithLifecycle`、`CredentialTokenDialog` 死分支等 | **多数已修复，部分保留** | `SettingsScreen.kt:179` 已改 `collectAsStateWithLifecycle()`；`MainLibraryViewModel.kt:148` 已改 `refreshFromStorage()`；`CredentialTokenDialog.kt` 已移除死状态；`GameScreen.kt:1161,1249` 已去重 `sheetMotion.interact()`；剩余细节及新发现归入下文第四、五节。 |

---

## 四、问题清单总览（R9 最新工作区状态）

| No. | 级别 | 维度 | 问题摘要 | 主要位置 |
| --- | --- | --- | --- | --- |
| 1 | **High** | 测试正确性 | `PlayStatusDialogUiTest` 拦截旧 Bangumi 搜索路径 `/search/subject/` 并返回 `list`/`air_date`，与生产 `v0/search/subjects` (`data`/`date`) 不匹配，真机运行必失败 | `PlayStatusDialogUiTest.kt:119-133`（对比 `BangumiCoverService.kt:17,53-76`） |
| 2 | Medium | 架构/耦合 | 非 UI 删游清理 `cleanupDeletedGame` 与封面删除 `deleteCoverFile` 位于 `GameScreen.kt`，导致 `ui/main -> ui/game` 耦合、重复实现且差异化 `coverUri` 会重复清理存档与单游配置 | `MainLibraryViewModel.kt:19,302-305`、`GameScreen.kt:380-416` |
| 3 | Medium | 逻辑/并发效率 | `GameActionsSheet` 摘要加载期任意 `statusVersion` 变更（如令牌自动刷新、403/200 权限位变更、身份回填）在门控不变时仍触发 `gatesChangedDuringFetch = true` 整轮重拉并跳过缓存 | `GameScreen.kt:871,951-965,984-988` |
| 4 | Medium | 测试隔离/并发 | `ScanPersistAndShortcutIntegrationTest` 与 `GameLibraryDatabaseDeviceMigrationTest` 切换 DB 单例或改 `GAME_SCANNER_PREFS` 前未冲洗 `GameLibraryRepository.init` / 防抖 1.5s `mirrorJob`；`PlayStatusDialogUiTest` 漏清 `NextMoeAuthStore` | `ScanPersistAndShortcutIntegrationTest.kt:60-90,100-137`、`GameLibraryDatabaseDeviceMigrationTest.kt:249-304,307-378`、`PlayStatusDialogUiTest.kt:65-94` |
| 5 | Medium | 测试健壮性 | `CoverAndSettingsNarrowUiTest` 缺 `try/finally` 释放 `allowClearFinish`；`SingleFlightRefreshTest` `lateinit var waiter` 遇前置失败会抛 `UninitializedPropertyAccessException`；三份刷新测试 `.join` 位于 `finally` 外 | `CoverAndSettingsNarrowUiTest.kt:150-163`、`SingleFlightRefreshTest.kt:161-181`、`BangumiTokenRefreshTest.kt:613-614`、`HikarinagiTokenRefreshTest.kt:558-559`、`NextMoeAuthServiceTest.kt:808-809` |
| 6 | Medium | 数据一致性/并发 | `GameLibraryRepository.writePrefsMirror` 在 `writeMutex` 与 Room 事务外连续执行 4 个挂起 DAO 查询，可能与让出单线程 `writeExecutor` 的 `post()` 交叉写出跨表撕裂快照 | `GameLibraryRepository.kt:334-348,372-385,403-408` |
| 7 | Medium | 逻辑/状态竞态 | `BangumiAuthService.handleAuthorizationResponse` 正常换码分支未先校验 `expectedGeneration` 即清零 `authInProgress`，滞留旧回调可误清后续新授权的「授权中」标记 | `BangumiAuthService.kt:272-274`（对比 `:232-234`） |
| 8 | Medium | 健壮性/安全 | 快捷方式图标/裁剪输入流回退 `File(uri.path)` 未校验 `file:`/`null` scheme；位图解码/裁剪/缩放中间 `Bitmap` 回收不在 `finally` 且 `runCatching` 吞并 `CancellationException` / `Error` | `GameShortcutManager.kt:89-132`、`GameShortcutImageProcessor.kt:17-38,87-92,122-124` |
| 9 | Medium | 健壮性/生命周期 | `GameOverlayMotion` 中 `remember { GameOverlayTracks(kind, enhanced) }` 缺少 `(kind, enhanced)` 键；`ON_PAUSE` 调用 `deliverPendingDismissIfClosing()` 时 `pending?.invoke()` 无异常隔离 | `GameOverlayMotion.kt:92-99,180,192-193` |
| 10 | Medium | 健壮性/安全 | `GitHubUpdateChecker.fetchReleases`（基线）无字节上限 `reader.readText()` 存在超大响应 OOM 风险，且非 2xx 时 `errorStream ?: inputStream` 在空 body 下会抛出 `FileNotFoundException` 掩盖状态码 | `GitHubUpdateChecker.kt:146-157` |
| 11 | Medium | 三语言文案 | `values-ja/strings.xml` 中 `play_status_bangumi_done`（`クリア`）与 `play_status_vndb_finished`（`プレイ済み`）和中英文「玩过/Played」vs「通关/Finished」正好互换 | `values-ja/strings.xml:754,759` |
| 12 | Medium | 代码规范/DRY | `PlayStatusService` 存在恒为 `false` 的死参数 `treatNotFoundAsNull` 与生产不可达 `json == null` 分支，及三端鉴权映射与来源校验重复；`PlayStatusDialog` 三段读取 `LaunchedEffect`、来源 ID 映射重复且单函数长达 455 行 | `PlayStatusService.kt:419,669-718,806-988`、`PlayStatusDialog.kt:302-756` |
| 13–22 | Low | 边界/性能/规范/文档 | 含读路径并发登出错误码归属、`!!` 与耦合限流器、权限写同值短路、`CoverSourcePickerDialog` 刷新闪烁、`busy` 标志复位、`ON_PAUSE` 裁剪取舍、冷启动主线程读 prefs、测试夹具/覆盖缺口、冗余全限定名与文案/文档微瑕 | 见第五节 #13–#22 |

---

## 五、重点问题详情与修复建议

### 1.【High / #1】`PlayStatusDialogUiTest` Bangumi 搜索桩 URL 与 JSON 字段不匹配
- **位置**：
  - `app/src/androidTest/java/com/tyranor/next/ui/game/PlayStatusDialogUiTest.kt:119-133, 156, 176-181`
  - `app/src/main/java/com/tyranor/next/core/cover/BangumiCoverService.kt:17, 53-76`
- **根因与影响**：`PlayStatusDialogUiTest` 的 `testHttpRoundTrip` 匹配 `url.contains("api.bgm.tv/search/subject/")` 并返回 `{"list": [{..., "air_date": "..."}]}`；但生产 `BangumiCoverService.searchCandidates` 实际向 `https://api.bgm.tv/v0/search/subjects?limit=...&offset=0` 发送 POST，并解析 `"data"` 数组与 `"date"` 字段。请求会命中 `:156` 的 `else -> HttpJsonResult(404, null)`，导致真机执行时 `"CLANNAD"` 候选永远不出现并在 `:176` 超时失败。
- **修复建议**：将 `PlayStatusDialogUiTest.kt:119-133` 改为匹配 `url.contains("api.bgm.tv/v0/search/subjects")` 并返回 `"data"` 数组与 `"date"` 字段。

### 2.【Medium / #2】非 UI 删游清理逻辑滞留 `GameScreen.kt` 导致跨页面耦合与重复清理
- **位置**：
  - `app/src/main/java/com/tyranor/next/ui/main/MainLibraryViewModel.kt:19, 302-305`
  - `app/src/main/java/com/tyranor/next/ui/game/GameScreen.kt:380-416`
  - `app/src/main/java/com/tyranor/next/core/cover/CoverSupport.kt:103-114`
- **根因与影响**：`MainLibraryViewModel` 直接从 `ui/game` 导入 `cleanupDeletedGame`。该函数执行存档清理（`SaveSyncService.clearGame`）、单游戏配置清理（`PerGameSettingsStore.clear`）、最近/快捷移除及封面删除（`deleteCoverFile` 重复实现了 `CoverImageCache.deleteCachedCover` 逻辑），违反 `AGENTS.md:81-83,90-98` 职责分层；此外当 `target.coverUri != liveTarget.coverUri` 时，`MainLibraryViewModel.kt:302-305` 再次调用 `cleanupDeletedGame` 会重复执行 `SaveSyncService.clearGame`、`PerGameSettingsStore.clear` 等无关操作。
- **修复建议**：将核心存储/封面清理序列下沉至 `core/game/storage/GameLibraryFacade`（复用 `CoverImageCache.deleteCachedCover`），仅当 `target.coverUri != liveTarget.coverUri` 时补删旧封面文件；`ui/` 层仅在回调后执行 `PlayStatusSummaryCache.invalidate(target.uri)`。

### 3.【Medium / #3】`GameActionsSheet` 摘要在仅 `statusVersion` 变更且门控未变时触发重复网络拉取
- **位置**：`app/src/main/java/com/tyranor/next/ui/game/GameScreen.kt:871, 951-965, 984-988`
- **根因与影响**：摘要拉取进行中（`activeFetchSeq != 0`），任何不改变 `resolvePlayStatusGates` 结果的 `statusVersion` 递增（如 OAuth 令牌自动刷新、403 写入 `permission_error`、200 清除权限标记、用户名异步回填）都会由第二个 `LaunchedEffect` 置位 `statusVersionChangedWhileFetching = true`（`:986`）。`:954` 的 `gatesChangedDuringFetch = postGates != gates || statusVersionChangedWhileFetching` 即使在 `postGates == gates` 时也判为 `true`，丢弃刚拿到的 `summary.line` 并令 `playStatusRefresh += 1`，对所有已绑定且就绪来源重发整轮请求。
- **修复建议**：在 `:951` 计算 `postGates` 前先将 `statusVersionChangedWhileFetching` 清零，若返回主线程前再次被置位可重算一次 `postGates`，最终仅以 `postGates != gates` 决定是否触发 `playStatusRefresh`。

### 4.【Medium / #4】设备集成/迁移测试未先隔离冲洗 `GameLibraryRepository.init` 与防抖 `mirrorJob`
- **位置**：
  - `app/src/androidTest/java/com/tyranor/next/core/game/scan/ScanPersistAndShortcutIntegrationTest.kt:60-90, 100-137`
  - `app/src/androidTest/java/com/tyranor/next/core/game/storage/GameLibraryDatabaseDeviceMigrationTest.kt:249-304, 307-378`
  - `app/src/androidTest/java/com/tyranor/next/ui/game/PlayStatusDialogUiTest.kt:65-94`
- **根因与影响**：`Application.onCreate` 会启动 `GameLibraryRepository.init`，且每次 DB 写入都会挂起 1500ms 防抖 `mirrorJob`。上述两个设备测试在 `setUp()` / `openConfiguredDbAsSingleton` 读写 `GAME_SCANNER_PREFS` 或替换 `GameLibraryDatabase` 单例前未先调用 `flushPrefsMirrorForTest` 排空队列与取消旧 `mirrorJob`，存在跨用例/初始化写回污染或写入旧 DB 单例的风险。此外 `PlayStatusDialogUiTest` 未在 `setUp`/`tearDown` 清理 `NextMoeAuthStore`。
- **修复建议**：在 `setUp()` 读写 prefs 和替换数据库实例前（以及 `openConfiguredDbAsSingleton` 释放前）调用 `runBlocking { GameLibraryRepository.flushPrefsMirrorForTest(ctx) }`；在 `PlayStatusDialogUiTest` 补齐 4 个 AuthStore 的清零。

### 5.【Medium / #5】并发单测/设备 UI 测试缺少 `finally` 清理保护
- **位置**：
  - `app/src/androidTest/java/com/tyranor/next/ui/game/CoverAndSettingsNarrowUiTest.kt:150-163`
  - `app/src/test/java/com/tyranor/next/core/auth/SingleFlightRefreshTest.kt:161-181`
  - `app/src/test/java/com/tyranor/next/core/auth/BangumiTokenRefreshTest.kt:613-614`
  - `app/src/test/java/com/tyranor/next/core/auth/HikarinagiTokenRefreshTest.kt:558-559`
  - `app/src/test/java/com/tyranor/next/core/auth/NextMoeAuthServiceTest.kt:808-809`
- **根因与影响**：`CoverAndSettingsNarrowUiTest` 若在 `:151-159` 断言失败将跳过 `allowClearFinish.countDown()` 导致 IO 线程阻塞 5s；`SingleFlightRefreshTest` 若在 `:163` 超时断言失败，`:180` 的 `waiter.join` 会因 `lateinit var waiter` 未初始化抛出 `UninitializedPropertyAccessException` 掩盖真实失败；3 个 Token 刷新测试的 `runner.join`/`waiter.join` 位于 `finally` 外部。
- **修复建议**：将 `allowClearFinish.countDown()` 与全部 `.join()` 移入 `try/finally`，并将 `SingleFlightRefreshTest` 的 `waiter` 改为可空 `var waiter: Thread? = null`。

### 6.【Medium / #6】`GameLibraryRepository.writePrefsMirror` 跨表读取未持 `writeMutex` / Room 事务
- **位置**：`app/src/main/java/com/tyranor/next/core/game/storage/GameLibraryRepository.kt:334-348, 372-385, 403-408`
- **根因与影响**：`schedulePrefsMirror` 在 `writeScope` 上调用 `writePrefsMirror(app)` 时未持有 `writeMutex`（对比 `flushPrefsMirrorForTest` at `:367-369`），且 `writePrefsMirror` 内依次挂起执行 4 个独立 DAO 查询（`getAllGames`、`getRoots`、`getRecentGames`、`getQuickLaunchGames`）。由于 `post()` 内 DAO 切换 `Dispatchers.IO` 挂起期间会让出单线程 `writeExecutor`，`writePrefsMirror` 可在表更新中途切入读出不一致的跨表快照并写入 `game_scanner` prefs。
- **修复建议**：让 `schedulePrefsMirror` 在 `writeMutex.withLock` 内调用 `writePrefsMirror(app)`（并将 4 次查询包入 Room `withTransaction`）。

### 7.【Medium / #7】`BangumiAuthService.handleAuthorizationResponse` 正常分支无条件清零 `authInProgress`
- **位置**：`app/src/main/java/com/tyranor/next/core/auth/BangumiAuthService.kt:272-274`（对比 `:232-234`）
- **根因与影响**：`completeAsyncFailure`（`:232`）先校验 `expectedGeneration == null || BangumiAuthStore.readCredentialGeneration(appContext) == expectedGeneration` 再清零 `authInProgress`；但正常换码分支（`:274`）直接调用 `BangumiAuthStore.setAuthInProgress(appContext, 0L)`。若旧浏览器回调在用户登出/粘贴个人令牌并再次发起 OAuth 授权后才抵达，`:274` 会误清当前新流程的「授权中」标记。
- **修复建议**：在 `:274` 补充与 `:232` 相同的 `expectedGeneration` 匹配判断。

### 8.【Medium / #8】快捷方式输入流缺 `file:` scheme 校验，且位图管道缺 `finally` 回收与取消透传
- **位置**：
  - `app/src/main/java/com/tyranor/next/core/game/shortcut/GameShortcutManager.kt:89-132`
  - `app/src/main/java/com/tyranor/next/core/game/shortcut/GameShortcutImageProcessor.kt:17-38, 87-92, 122-124`
- **根因与影响**：
  1. `openShortcutIconInputStream`（`GameShortcutManager.kt:127-132`）与 `openShortcutCropInputStream`（`GameShortcutImageProcessor.kt:87-92`）在 `contentResolver.openInputStream(uri)` 返回 `null` 后直接回退 `File(uri.path).inputStream()`，未检查 `uri.scheme.equals("file", true) || uri.scheme.isNullOrBlank()`，对异常 `content://` 或远端 URI 可能误读本地同路径文件。
  2. `decodeSquareIcon` 与 `decodeShortcutCropBitmap` / `applyShortcutCropExifOrientation` 将中间 `Bitmap` 回收写在成功路径尾部而非 `finally`，异常时可能泄漏 native 像素内存；且外层 `runCatching` 会吞掉 `CancellationException` 与 `Error`。
- **修复建议**：限制 `File(path)` 回退仅用于 `file` / 空 scheme；用 `try { ... } finally { ... }` 保证中间位图必然回收，并将 `runCatching` 替换为放行 `CancellationException` 的捕获块。

### 9.【Medium / #9】`GameOverlayMotion` 无键 `remember` 与 `ON_PAUSE` 回调缺少异常隔离
- **位置**：`app/src/main/java/com/tyranor/next/ui/game/GameOverlayMotion.kt:92-99, 180, 192-193`
- **根因与影响**：`:180` `val tracks = remember { GameOverlayTracks(kind, enhanced) }` 未传入 `(kind, enhanced)` 键（而 `:186` 传了这些键），若 `kind` 或动效档位变化会导致动画轨道与控制器失配；此外 `ON_PAUSE` 触发 `deliverPendingDismissIfClosing()` 时直接执行 `pending?.invoke()` 而未捕获 `Exception`（对比 `AppAlertDialog.kt:179-189` 与 `SheetMotion.kt:447-453`），回调异常会中断生命周期派发。
- **修复建议**：`:180` 改为 `remember(kind, enhanced) { GameOverlayTracks(kind, enhanced) }`，并在 `ON_PAUSE` 调用处捕获 `Exception`（重抛 `CancellationException` 并记日志）。

### 10.【Medium / #10】`GitHubUpdateChecker.fetchReleases` 无上限读响应且空错误流抛 `FileNotFoundException`
- **位置**：`app/src/main/java/com/tyranor/next/core/updater/GitHubUpdateChecker.kt:146-157`
- **根因与影响**：`fetchReleases`（基线既有实现，本分支仅在 `:122` 补齐取消重抛）先用 `reader.readText()` 无字符上限读完整体响应才检查状态码，面对超大/恶意响应存在 OOM 风险；且当服务端返回非 2xx 且 `errorStream == null` 时，`connection.errorStream ?: connection.inputStream` 访问 `inputStream` 会抛 `FileNotFoundException` 掩盖真实的 HTTP 状态码报错（`:154`）。
- **修复建议**：在读流前先检查 `connection.responseCode !in 200..299` 并直接抛错；2xx 响应改用带长度上限的读取工具（如 `MAX_JSON_CHARS`）。

### 11.【Medium / #11】`values-ja/strings.xml` 中 Bangumi「玩过」与 VNDB「通关」日文翻译对调
- **位置**：`app/src/main/res/values-ja/strings.xml:754, 759`（对比 `values/strings.xml:754,759`、`values-en/strings.xml:754,759` 与 `values-ja/strings.xml:763,769`）
- **详情**：`play_status_bangumi_done` 在中文为「玩过」、英文为 `Played`，但日文却写为 `クリア`；而 `play_status_vndb_finished` 在中文为「通关」、英文为 `Finished`（同页 Hikarinagi 与 NextMoe 的完成/通关均译为 `クリア`），日文却写为 `プレイ済み`。两者正好互换。
- **修复建议**：将 `values-ja/strings.xml:754` 改为 `プレイ済み`，`:759` 改为 `クリア`。

### 12.【Medium / #12】`PlayStatusService` 死参数与 `PlayStatusService` / `PlayStatusDialog` 重复实现
- **位置**：
  - `app/src/main/java/com/tyranor/next/core/status/PlayStatusService.kt:419, 669-672, 692-696, 714-718, 806-828, 860, 878, 887-909, 938, 966-988`
  - `app/src/main/java/com/tyranor/next/ui/game/PlayStatusDialog.kt:302-756`（`350-408`, `530-536`）
- **详情**：
  1. `hikarinagiRequest` / `hikarinagiRequestWithToken` 的 `treatNotFoundAsNull` 参数恒为 `false`（`:404, 514` 唯一两处调用均传 `false`），且 `:945` 拦截 `result.json == null`，致使 `finishHikarinagiStatusRead` 的 `if (json == null)` 分支（`:419`）在生产中不可达。
  2. `PlayStatusService` 三端请求方法重复了相同的 `ConfigMissing` / `RefreshFailed` 映射与 `sessionEpoch` 校对块，绑定来源合法性判断重复 3 次。
  3. `PlayStatusDialog` 单 Composable 长达 455 行，包含 3 段同构回读 `LaunchedEffect`（`:350-408`），且 `:530-536` 重复编写四源 `when` 分支而未复用同文件 `:121-127` 的 `boundIdForSource`。
- **修复建议**：移除死参数 `treatNotFoundAsNull` 与不可达分支（同步调整 `HikarinagiTokenRefreshTest.kt:430,435`）；抽取来源校验常量与 OAuth token 失败映射辅助函数；在 `PlayStatusDialog` 复用 `boundIdForSource` 并拆分状态加载与子视图。

---

### 6.【Low】其余低严重度细节汇总（#13–#22）

- **#13 `PlayStatusService` 读路径并发登出错误码与 `!!`（`PlayStatusService.kt:767,774-777,809-816,890-897,969-976`）**：读路径（`expectedSessionEpoch == null`）若在 `isAuthorized()` 与 `acquireToken()` 之间发生并发登出导致 `ConfigMissing`，当前抛出 `*_TOKEN_EXPIRED` 而非 `*_AUTH_MISSING`；`resolveOAuthRequestToken` 的 `acquireToken: () -> String?` 可改为 `() -> String` 去掉 `:777` 的 `!!`。
- **#14 `core/status` 耦合 `VndbCoverService.throttle` 与可见性收缩（`PlayStatusService.kt:371,461,602`、`CoverScraperService.kt:190-205`）**：共享 VNDB 限流桶可下沉至 `core/net`；`CoverScraperService.searchCandidatesForBinding` 抛出 `internal CoverSearchException`，方法自身应同步收窄为 `internal` 或返回 `CoverSearchResult`。
- **#15 `AuthStore` 短路与异常收口（`VndbAuthStore.kt:158-169`、`HikarinagiAuthStore.kt:170-173`）**：`savePermissionsIfTokenMatches` 缺少同值短路；`HikarinagiAuthStore.ensureMigrated` 捕获 `Throwable` 应仅捕获 `Exception` 或显式放行 `CancellationException`。
- **#16 `CoverSourcePickerDialog` 刷新重挂载与 `PlayStatusDialog` 忙标志复位（`GameScreen.kt:409,1423-1427`、`PlayStatusDialog.kt:444-446,452-481,558-572,1103-1117`）**：`CoverSourcePickerDialog` 用 `produceState(initialValue = null, authVersion)` 在 `authVersion` 刷新时先置 `null` 会导致弹窗短暂关闭重开；`deleteCoverFile` `:409` 的 `persistentCoverDir(context).canonicalPath` 应在 `runCatching` 内；`PlayStatusDialog` 写/解绑/绑定的 `writingSource`/`binding` 复位宜入 `finally`，`:444-446` 为不可达的静默 return 守卫。
- **#17 `GameOverlayMotion.finishCropExport` 退场期旋转取舍（`GameOverlayMotion.kt:124-136`）**：导出完成后 200ms 退场期内若旋转，由于 `pendingDismissAction == null`，`onConfirm` 回调会被取消跳过并删除临时裁剪图；安全无泄漏但建议补充设计注释或调整提交时机。
- **#18 冷启动主线程 `SharedPreferences` 读盘风险（`MainScreen.kt:170-173`、`SettingsScreen.kt:347`、`CoverScraperSettingsActivity.kt:156-158`）**：部分冷启动组合路径直接同步读 `app_settings` prefs，可由启动预热或 `Dispatchers.IO` 异步加载统一消除 StrictMode 磁盘读告警。
- **#19 测试夹具细节与剩余覆盖缺口**：`AuthAndCoverTestStubsRule.kt:71` 调 `seedGamesForTest(null)` 未同步调 `resetCachesForTest()` 清理 `GameOverridesRepository` 行缓存；尚缺 `AppAlertDialog`/`SheetMotion`/`GameOverlayMotion` 的 `ON_PAUSE`/`ON_STOP` 旋转中断测试、`MainLibraryViewModel` 协程集成测试、`GameActionsSheet` 摘要响应式 `LaunchedEffect` 集成测试、`PlayStatusDialog` Hikarinagi/NextMoe 真机 UI 交互及 `AppAlertDialog`/`GameShortcutCropDialog` 内嵌窄屏大字号测试。
- **#20 三语言资源重复与措辞微瑕**：`cover_batch_result`（三语言 `:543`）与 `cover_scrape_done`（`:729`）文案完全重复；`values-en/strings.xml` 中 `"cover scraping settings"`（`:253-254`）与 `"cover scraper settings"`（`:721,773...`）及 `"Fetching covers"`（`:551`）混用；`values/strings.xml:259` `搜索 未萌NextMoe 封面` 多一个空格；`values-ja/strings.xml:547` 混用 `スクレイピング`（其余均用 `取得`）及 `:579-580` 句号 `。` 与冒号 `：` 不一致。
- **#21 冗余全限定名**：`NextMoeAuthService.kt:742`、`GameScreen.kt:390,403,1028,1109,1390`、`GameQuickActionsDialog.kt:139,183,234,285`、`PlayStatusDialog.kt:568,1113`、`PlayStatusErrors.kt:93`、`HttpJson.kt:88,100`、`VndbAuthStore.kt:217`、`BangumiAuthStore.kt:393`、`NextMoeAuthStore.kt:394`、`HikarinagiAuthStore.kt:555`、`CoverScraperSettingsActivity.kt:905,985`、`CoverSupport.kt:118`、`AppBottomSheet.kt:136` 等处直接使用包名全限定写法，宜改为标准 `import`。
- **#22 文档行号与措辞微偏**：`docs/应用持久化数据清单.md:116` 引用 `GameScreen.kt:403-415`（实际为 `:404-416`）、`:156,224` 引用 `TyranoStorage.kt:9-111`（实际完整类为 `:9-136`）；`AGENTS.md:138` 建议将 `CoverScraperSettingsActivity 摘要` 补全为 `CoverScraperSettingsActivity 摘要与未知异常兜底` 以与 `PlayStatusErrors.kt:13-15` 及 `CoverScraperSettingsActivity.kt:926,987,1004` 完全一致。

---

## 六、已核实合规的关键领域

1. **三层架构与错误协议**：`core/` 零 Compose/UI 导入，[HttpJson.kt](../app/src/main/java/com/tyranor/next/core/net/HttpJson.kt) 保持严格中性，`core/` 包依赖呈严格 DAG 无环，`ui/` 无引擎层越层引用，`engine/` 本分支零修改；全部新增/修改 `core` 接口返回类型化结果或携带错误码异常，`CancellationException` 均正确传播，[AGENTS.md](../AGENTS.md#L136) 与 [ARCHITECTURE.md](../ARCHITECTURE.md) 所列存量偏差与代码完全吻合。
2. **数据持久化、OAuth 与密钥安全**：`GameEntity` / `GameLibraryDao` / `GameLibraryDatabase` v3→v6 迁移、`app/schemas/{4,5,6}.json`（`6.json` `identityHash = 897c37ef83c1cf83c989f4c0dd5f70d5`）、`GameRecordCodec`（16 字段）与 `EngineScanner` 合并逻辑一致；`backup_rules.xml` 与 `data_extraction_rules.xml` 完整排除全部 `*_auth_secure.xml`、`hikarinagi_auth.xml`、`webdav_sync_secure.xml` 与 `game_library.db*`；回调 Activity `exported="false"`、`PendingIntent.setPackage`、PKCE S256、NextMoe `127.0.0.1:0` 绑定、`state`/`nonce` 恒定时间比对与日志脱敏均核实合规。
3. **UI 规范、动效与门禁**：`AppSecondaryTopBar`、两档字号（`titleMedium`/`bodyMedium`）、`AppAlertDialog` + `DialogTextButton` + `NoIndication`、`AppBottomSheet` + `NoIndication`、`AppSearchField`、`AppNavItem`（含 `indication = null` 与面板反色）、`Switch`/`SwitchPreference`、`AppNotice`（无新增 `Toast.makeText`）、`AppComponentShape`、`Color.kt` 动态常量与具名动效入口均符合 `AGENTS.md`；三语言文案门禁、动效边界门禁、门禁脚本自测、图形资源归属回归、65 个定向单测文件 497 项用例与增量 Lint 均通过检查。
