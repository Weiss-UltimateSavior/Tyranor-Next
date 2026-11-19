# `codex/bangumi-vndb-play-status` 分支审计报告（2026-11-15 · R6 工作区最新完整状态）

- **审计对象**：`codex/bangumi-vndb-play-status`（`HEAD cf56a52e` 叠加当前工作区 56 个已修改跟踪文件及 3 个新增未跟踪源码/测试文件 `GameLibraryDatabaseDeviceMigrationTest.kt`、`RemoteBindingPreservationTest.kt`、`CoverSearchCandidateCodecTest.kt`；相对基线 `origin/main` `709687a3` 共涉及 98 个跟踪文件变更 `+17885 / -932` 行及 3 个新增未跟踪代码/测试文件）。
- **审计基准**：[AGENTS.md](../AGENTS.md)、[CONTEXT.md](../CONTEXT.md)、[ARCHITECTURE.md](../ARCHITECTURE.md)、[SPEC.md](../SPEC.md)。
- **审计方式**：严格遵循 `AGENTS.md` 测试与审核规范，分派 5 个相互独立的审查子 Agent（代码逻辑与并发竞态、架构边界与文档一致性、代码健壮性与生命周期、回归风险与测试覆盖、代码规范与 UI/动效规范）对当前磁盘最新状态执行只读独立审查；主 Agent 对全部核心发现回源核对源码、行号与资源文件，并实际运行本地静态门禁、定向与全量 JVM 单测、`androidTest` 编译与 Android Lint 增量检查。

> 说明：工作区既有的历史快照 `docs/play-status-audit-20261114.md`、`docs/play-status-audit-20261115.md`、`docs/play-status-audit-20261115-cf56a52e.md`、`docs/play-status-audit-20261115-post-cf56a52e.md` 与 `docs/play-status-audit-20261115-r5.md` 均完整保留不覆盖。上一轮 R5 指出的两项中严重度问题及多项低严重度问题（`applyScrapedGame` 反向复活已解绑 ID、`CoverScraperService.bindCoverCandidate` 原子落库、`AGENTS.md`/`ARCHITECTURE.md` 移除 `SaveLocation.description`、Bangumi `needsReauth` 门控、解绑条目 `titleMaxLines = 2` 与 `leadingIconTint`、`deleteCoverFile` 分隔符边界、退场期重试行防连点等）均已在当前工作区落实修复；本报告基于最新完整代码状态重新审计。

---

## 一、已执行验证与实测结果

| 检查项 | 命令 | 结果 |
| --- | --- | --- |
| 三语言字符串与硬编码 CJK 门禁 | `python tools/check-hardcoded-ui-strings.py` | **通过**（`Hardcoded UI string check passed.`，zh / en / ja 三语言键对齐，无 Kotlin 硬编码 CJK） |
| 动效边界门禁 | `python tools/check-motion-boundaries.py` | **通过**（`Motion boundaries: 366 Kotlin files, 0 violations.`） |
| 本地门禁脚本单测 | `python tools/test-hardcoded-ui-strings.py` & `python tools/test_motion_boundaries.py` | **通过**（5 用例 + 11 用例全通过） |
| 本分支定向 JVM 单元测试 | 清空旧结果后执行 `./gradlew.bat :app:testDebugUnitTest --rerun --tests "com.tyranor.next.core.auth.*" --tests "com.tyranor.next.core.cover.*" --tests "com.tyranor.next.core.status.*" --tests "com.tyranor.next.core.net.*" --tests "com.tyranor.next.core.backup.*" --tests "com.tyranor.next.core.game.scan.*" --tests "com.tyranor.next.core.game.storage.*" --tests "com.tyranor.next.ui.auth.*" --tests "com.tyranor.next.ui.cover.*" --tests "com.tyranor.next.ui.game.*" --tests "com.tyranor.next.ui.main.*" --no-daemon` | **通过**（62 个测试文件、476 个用例、0 失败、0 错误、0 跳过） |
| 全量 JVM 单元测试 | `./gradlew.bat :app:testDebugUnitTest --no-daemon` | **本分支新增/修改用例 0 失败**（共 1806 项完成、31 项失败、18 项跳过；31 项失败均为 Windows 宿主环境既有路径/符号链接测试 `RuffleRuntimeEnvironmentTest`、`EthornellOperationGateTest`、`LaunchEntryPathTest`、`EthornellSaveStoreTest`、`EthornellSettingsTest`） |
| AndroidTest 编译检查 | `./gradlew.bat :app:compileDebugAndroidTestKotlin --no-daemon` | **通过**（`BUILD SUCCESSFUL`，含新增 `GameLibraryDatabaseDeviceMigrationTest`） |
| Android Lint 增量检查 | `./gradlew.bat :app:lintDebug --no-daemon` + `python tools/check-lint-diff.py --base origin/main app/build/reports/lint-results-debug.xml` | **变更源码 0 Error**（`Lint errors in reports: 1. Errors on changed sources: 0.`，唯一 Error 为本机 `local.properties:1` `[PropertyEscape]`；变更源码无新增 Error） |
| 未执行项 | 真机/模拟器 `connectedDebugAndroidTest`（`adb devices` 无在线设备且 CI 未运行）、真实浏览器 OAuth 回调闭环、真实 AndroidKeyStore 密钥失效恢复、360dp 窄屏真机渲染、`:engine` 相关任务（本分支 `engine/` 零改动） | **未执行**（不得视同已验收） |

---

## 二、总体审计结论

- **核心链路评估**：架构边界（`ui -> core -> engine` 与中性 `core/net`）、错误处理协议（`core -> ui` 类型化结果与 `CancellationException` 透传）、Room v3→v6 显式迁移与 schema 导出、4 套 `*_auth_secure.xml` Keystore 隔离及云备份/设备迁移排除、PKCE S256 / `state` / `nonce` / `PendingIntent.setPackage` 与环回防护、`CoverScrapeTaskManager` 逐源修订号并发防回滚及 `CoverScraperService.bindCoverCandidate` 原子落库整体稳健。
- **仍需关注的问题**：本次 5 维独立审查与主 Agent 交叉核验确认 **2 项高严重度（High）问题**（1 项主线程 OAuth 回调/Socket 绑定/Keystore 锁竞争，1 项 JVM `Proxy` 假数据库迁移测试与真机未实跑）、**9 项中严重度（Medium）问题**（编号 #3–#11）与 **5 项低严重度（Low）问题组**（编号 #12–#16），重点集中在：
  1. **主线程阻塞与锁竞争**：OAuth `startAuthorization`（NextMoe 主线程绑定 `ServerSocket`、三端读取代次触发 `ensureMigrated` / `storeLock`）与 AppAuth `performTokenRequest` 主线程回调执行 `Keystore` 加解密。
  2. **自定义封面原地覆盖缺陷**：`VndbCoverService.saveCustomCover` 仍以固定文件名原地写入并在失败时 `target.delete()`，且同名替换（如 `.jpg` 换 `.jpg`）不会刷新 `CoverBitmapCache`。
  3. **多按钮窄屏省略截断回归**：`NoPressFeedbackButtons.NoRippleActionText` 新增 `maxLines = 1, overflow = TextOverflow.Ellipsis` 后，`InputSettingsScreen`（最多 5 个均分宽按钮 `Row`）与 `CoverSearchDialog` 底部按钮栏未迁 `FlowRow`；`CoverSearchDialog` 在 `binding` 期间未禁用 `BackHandler` 与二级返回，退场动画期也未门控 `onCandidateClick`。
  4. **后台生命周期收集与 `SingleFlightRefresh` 换号边缘**：`GameScreen`、`PlayStatusDialog`、`CoverScraperSettingsActivity` 多处使用 `collectAsState()` 在 `ON_STOP` 后台态触发重组/解密/网络请求；`SingleFlightRefresh` 在等待刷新期间并发完成新登录时会误拒读路径采纳新令牌。
  5. **文档滞后与测试质量/缺口**：`SPEC.md` 第 9 节未体现四源授权、`AGENTS.md`/`ARCHITECTURE.md` 存量偏差注记漏列 `core/updater`、`docs/应用持久化数据清单.md` 行号偏移与版本信息滞后；10 份测试自复制非线程安全 `SharedPreferences` `Proxy` 桩、部分测试绕过协同门面及关键 HTTP/UI 路径缺口。

---

## 三、前序（R5）重点问题修复核查表

| 前序编号 | 原问题摘要 | 当前工作区核查 | 代码位置与验证依据 |
| --- | --- | --- | --- |
| R5 #1（中） | 批量刮削 `gameUpdates` 与并发 `unbindSubject` 交错，导致 `applyScrapedGame` 复活已解绑 ID | **已完整修复** | `GameLibraryFacade.kt:107-123, 198-213` 原子采集并更新 `BindingRevisionSnapshot`；`CoverScrapeTaskManager.kt:36-42, 72-86, 130-140` 传递 `ScrapedCoverUpdate`；`MainLibraryViewModel.kt:93-103, 146-151, 561-578` 逐源比对实时修订号并在取消/异常后主动 `refreshFromStorage()`。 |
| R5 #2（中） | 封面绑定先删旧图后回 UI 持久化导致旧图丢失或误关抽屉 | **主路径已修，留 `VndbCoverService` 与退场期边缘** | `CoverScraperService.kt:218-253` 已将「下载 → 原子落库 → `notifyGamesChanged` → 删旧图」收敛至 core 原子段；`CoverSearchDialog` 已在 `GameScreen.kt:1495, 1509, 1731` 禁用 `binding` 期间关闭。**遗留**：`VndbCoverService.saveCustomCover` 原地覆盖与 `CoverSearchDialog` 二级返回/退场门控见下节 #2、#5。 |
| R5 #3（低） | `AGENTS.md`/`ARCHITECTURE.md` 仍提及 `SaveLocation.description`；`持久化数据清单` 偏移 | **R5 指定项已修，另有邻近文档滞后** | `AGENTS.md:136` 与 `ARCHITECTURE.md:175` 已删去 `SaveLocation.description`；`docs/应用持久化数据清单.md:57, 115` 已修正。其余文档滞后见下节 #7、#8、#14。 |
| R5 #4（低） | Bangumi 摘要门控未检 `needsReauth`；重试行退场连点；`writeStatus` 读旧 `game`；`deleteCoverFile` 边界 | **已全部修复** | `GameScreen.kt:860-863` 校验 `!status.needsReauth`；`GameScreen.kt:381-389` 校验 `File.separator`；`PlayStatusDialog.kt:412, 944` 改用 `latestGame` 并校验 `onRetry == null`。 |
| R5 #5（低） | 刮削设置页返回重复提示、4 处缺 `Log.w`、登出在主线程调 `clear()` | **主体已修复** | `CoverScrapeTaskManager.kt:53-54, 116` + `MainLibraryViewModel.kt:114-123, 132` 经 `acknowledgedEventId` 去重；`CoverScraperSettingsActivity.kt:143-145` & `CredentialTokenDialog.kt:288-291` 将 `clear()` 下沉 `Dispatchers.IO`；`AuthFormHttp.kt:51`、`GameScreen.kt:1267`、`GameQuickActionsDialog.kt:208`、`VndbCoverService.kt:162` 已补 `Log.w`。OAuth 回调与其余几处 `Log.w` 见下节 #1、#15。 |
| R5 #6（低） | KDoc 漏列、解绑单行、CropDialog 折行与部分文案细节 | **主体已修复，留少量新/存量项** | `PlayStatusDialog.kt:977, 1216` 与 `GameShortcutCropDialog.kt:361-397` 已修正；剩余三语言与 `CoverSearchDialog` 按钮栏见下节 #4、#16。 |

---

## 四、问题清单总览（当前工作区状态）

| No. | 级别 | 维度 | 问题摘要 | 主要位置 |
| --- | --- | --- | --- | --- |
| 1 | 高 | 健壮性/线程 | OAuth 发起（NextMoe 主线程 `ServerSocket` 绑定、`storeLock`）与 AppAuth 主线程回调执行 Keystore 加解密及锁竞争 | `BangumiAuthService.kt:127-239`、`HikarinagiAuthService.kt:114-232`、`NextMoeAuthService.kt:160-219, 696-700` |
| 2 | 高 | 测试质量 | `GameLibraryDatabaseMigrationTest` 为 `Proxy` 假 SQLite 镜像；真机实库迁移 `androidTest` 尚未实跑且缺 v3/v4/v5 起点 | `GameLibraryDatabaseMigrationTest.kt:22-49`、`GameLibraryDatabaseDeviceMigrationTest.kt:35-140` |
| 3 | 中 | 健壮性/逻辑 | `VndbCoverService.saveCustomCover` 原地覆盖且异常时删旧文件；同名覆盖后 `CoverBitmapCache` 永久缓存旧图 | `VndbCoverService.kt:81-126`、`GameScreen.kt:2451-2472` |
| 4 | 中 | 回归风险 | `NoRippleActionText` 强制单行省略导致 `InputSettingsScreen` 5 按钮 `Row` 与 `CoverSearchDialog` 底部按钮栏存在窄屏截断风险 | `NoPressFeedbackButtons.kt:72-76`、`InputSettingsScreen.kt:589-616`、`GameScreen.kt:1713-1744` |
| 5 | 中 | 健壮性/逻辑 | `CoverSearchDialog` 在 `binding` 期间未禁用二级返回键/按钮，且退场动画期间未拦截 `onCandidateClick` 仍可关停抽屉 | `GameScreen.kt:1259-1280, 1481-1500, 1722-1727`、`GameOverlayMotion.kt:80-96` |
| 6 | 中 | 健壮性/生命周期 | `GameScreen`、`PlayStatusDialog`、`CoverScraperSettingsActivity` 多处 `collectAsState()` 在 `ON_STOP` 后台态仍触发重组、Keystore 解密及网络请求 | `GameScreen.kt:252, 830-833, 1317`、`PlayStatusDialog.kt:308-311`、`CoverScraperSettingsActivity.kt:146-149, 152` |
| 7 | 中 | 逻辑/并发 | `SingleFlightRefresh` 等待方在「在途刷新期间并发完成新登录」场景下因 `leaderToken == null` 误判失败 | `SingleFlightRefresh.kt:52-68`、`SecureAuthStore.kt:304-320`、`*AuthService.kt` |
| 8 | 中 | 架构/文档 | `SPEC.md` 第 9 节授权描述滞后于四源体系；`AGENTS.md`/`ARCHITECTURE.md` 存量偏差漏列 `core/updater`；`CHANGELOG.md` 顶部条目在 `## 未发布` 之外 | `SPEC.md:250-252`、`AGENTS.md:136`、`ARCHITECTURE.md:175`、`CHANGELOG.md:4-7` |
| 9 | 中 | 测试质量 | 10 份单测各自复制 `Proxy` 版内存 `SharedPreferences`（3 份非线程安全、多份缺 `null` 移除/集合类型支持，掩盖真实加解密路径） | `AuthStatusPersistenceTest.kt`、`AuthStoreBatchWriteTest.kt`、`PlayStatusWriteDecisionTest.kt`、`CoverScraperPolicyTest.kt` 等 |
| 10 | 中 | 测试质量 | 进程级全局测试桩与单例状态依赖 `@After` 复位，且部分单测通过空库/跳落库绕过真实协同分支 | `SecureAuthStore.kt:61,64`、`HttpJson.kt:30`、`CoverScraperBindAndHikarinagiTest.kt:65-66`、`CoverScrapeMergeTest.kt:189-190` |
| 11 | 中 | 测试覆盖 | 关键生产路径缺少自动化测试（库内 `bindCoverCandidate` / `saveCustomCover`、`MainLibraryViewModel` 协程、VNDB/NextMoe HTTP、OAuth 回调等） | 见第六节 |
| 12 | 低 | 逻辑/健壮性 | `CredentialTokenDialog`「清空」无并发忙碌门禁；`NextMoeAuthService.activeFlowId` 预检无 `@Volatile`；三域 `refreshAccessToken` 前置顺序不对称 | `CredentialTokenDialog.kt:279-294`、`NextMoeAuthService.kt:103, 655`、`*AuthService.kt` |
| 13 | 低 | 健壮性/生命周期 | `CoverScraperSettingsActivity` 旋转丢失列表滚动位置；`CoverSearchDialog` 二级界面旋转退回一级；`AppAlertDialog` `SpaceBetween` 折行左对齐；`CoverSourcePickerDialog` 首帧闪烁 | `CoverScraperSettingsActivity.kt:284-286`、`GameScreen.kt:1322-1356, 1387-1391`、`AppAlertDialog.kt:335-340` |
| 14 | 低 | 健壮性/原子性 | 几处异常路径遗漏脱敏 `Log.w`、`SubjectBindDialog.search` 与 `writeShortcutCropBitmap` 缺 `finally`、`CoverScrapeTaskManager.clearFinished` 与 `bumpCoverScraperSettingsVersion` 非原子 | `BangumiAuthService.kt:398-444`、`PlayStatusDialog.kt:1050-1055`、`CoverScrapeTaskManager.kt:112-118`、`AppSettingsStore.kt:808`、`GameShortcutImageProcessor.kt:59-62` |
| 15 | 低 | 文档/注释 | `AGENTS.md:400, 407-408`、`ARCHITECTURE.md:154`、`PlayStatusErrors.kt:12-15`、`docs/应用持久化数据清单.md` 多处行号/版本/标签偏移；`DrawerAction.showArrow` 注释澄清 | 见第五节 #14 |
| 16 | 低 | 规范/三语言文案 | 三语言术语/空格/句号微瑕、`PlayStatusServiceHttpTest.kt:11` 未使用 import，以及涉及文件的 3 处存量 UI 规范债 | `values*/strings.xml`、`PlayStatusServiceHttpTest.kt:11`、`GameScreen.kt:1555, 1874`、`GameQuickActionsDialog.kt:404, 444` |

---

## 五、重点问题详情与修复建议

### 1.【高 / #1】OAuth 发起与 AppAuth 回调存在主线程 Keystore 加解密、`SharedPreferences` 读写、Socket 绑定及锁竞争
- **位置**：
  - `app/src/main/java/com/tyranor/next/core/auth/BangumiAuthService.kt:127, 150-165, 211-236, 246-282`
  - `app/src/main/java/com/tyranor/next/core/auth/HikarinagiAuthService.kt:114, 138-150, 195-228, 239-271`
  - `app/src/main/java/com/tyranor/next/core/auth/NextMoeAuthService.kt:160-189, 211, 215, 696-700`
  - 调用点：`CoverScraperSettingsActivity.kt:314, 335, 361`、`BangumiOAuthCallbackActivity.kt:21`、`HikarinagiOAuthCallbackActivity.kt:21`
- **根因**：
  1. AppAuth `performTokenRequest` 回调运行在主线程，直接执行 `completeOAuthTokenExchange` → `SecureAuthStore.encryptToMemory`（触发 AndroidKeyStore `load` / `generateKey` / `Cipher.doFinal` TEE IPC）及 `SharedPreferences.edit().apply()`，同时持有 `storeLock`。
  2. `NextMoeAuthService.startAuthorization` 在主线程点击回调中同步执行 `ServerSocket(0, 4, 127.0.0.1)` 绑定系统调用，并在 `synchronized(loopbackLock)` 内读取代次与写入 `authInProgressAt`（获取 `NextMoeAuthStore.storeLock`）；与此同时，后台环回线程或请求线程正持有 `loopbackLock` / `storeLock` 执行 Keystore 加解密，存在主线程锁等待与 StrictMode 风险。
  3. `BangumiAuthService.startAuthorization` 与 `HikarinagiAuthService.startAuthorization` 在主线程同步持有 `storeLock` 读取 `credential_generation`（Hikarinagi 首次访问还会触发 `ensureMigrated` 生成密钥并加密迁移），且 `synchronized(this)` 与设置页 `Dispatchers.IO` 预热争锁。
- **修复建议**：
  - 将 `BangumiAuthService` / `HikarinagiAuthService` 回调内的 `completeOAuthTokenExchange` 与 `setAuthInProgress(..., 0L)` 切到 `Dispatchers.IO` 执行后再切回主线程回调 `onResult`。
  - 将三端 `startAuthorization` 中 `ServerSocket` 绑定、代次读取、`authInProgress` 写入与 `AuthorizationService` 构造下沉 `Dispatchers.IO`（配合防连点状态），主线程仅负责拉起浏览器 Intent。

### 2.【中 / #3】`VndbCoverService.saveCustomCover` 原地覆盖且异常删图会破坏已有封面，同名替换后 `CoverBitmapCache` 命中旧图
- **位置**：
  - `app/src/main/java/com/tyranor/next/core/cover/VndbCoverService.kt:81-126`
  - `app/src/main/java/com/tyranor/next/ui/game/GameScreen.kt:2451-2472`
- **根因**：
  1. `saveCustomCover`（第 90 行）固定写入 `custom_${stableKey(game.uri)}.$ext` 并直接以 `FileOutputStream(target)` 打开，未像 `CoverImageCache.download` 一样先写临时文件再重命名。当游戏已有同扩展名自定义封面（如 `custom_<key>.jpg`）时，重新选择另一张 `.jpg` 会先截断原文件；若拷贝流失败或协程被取消，第 97/100 行的 `target.delete()` 会把**原先已生效的封面文件一起删除**。
  2. 即使拷贝成功，因新旧 `coverUri` 与 `coverSource` 字符串完全一致，`GameLibraryFacade.updateGameCoverMetadata`（第 107 行）判定七列未变而返回 `null`，跳过 `notifyGamesChanged()`；同时 `CoverBitmapCache`（`GameScreen.kt:2451`）仅以 `coverUri` 为键且无淘汰接口，导致 UI 永久显示内存中的旧位图直到进程重启。
- **修复建议**：
  - 使用临时文件写入或将内容短哈希纳入文件名（如 `custom_<gameKey>_<hash>.<ext>`），落库成功后再经 `CoverImageCache.deleteCachedCover` 回收旧文件；失败/取消仅删临时文件。

### 3.【中 / #4】`NoRippleActionText` 强制单行省略导致既有多按钮 `Row` 发生窄屏截断风险
- **位置**：
  - `app/src/main/java/com/tyranor/next/ui/common/NoPressFeedbackButtons.kt:72-76`
  - 波及处：`app/src/main/java/com/tyranor/next/ui/settings/InputSettingsScreen.kt:589-616`、`app/src/main/java/com/tyranor/next/ui/game/GameScreen.kt:1713-1744`、`app/src/main/java/com/tyranor/next/ui/archive/ArchiveUnpackActivity.kt:535-536`
- **根因**：本分支为 `NoRippleActionText`（`NoRippleTextButton` 与 `DialogTextButton` 共用）加入 `maxLines = 1, overflow = TextOverflow.Ellipsis` 后，仅迁移了 `AppAlertDialog`、`CoverSourceRow` 与 `GameShortcutCropDialog` 至 `FlowRow`。`InputSettingsScreen.kt:589-616` 仍在 `Row` 中并排放置最多 5 个 `Box(Modifier.weight(1f))` 均分按钮，`CoverSearchDialog`（`GameScreen.kt:1713-1744`）底部操作栏也仍是 `Row`，在 360dp 窄屏、大字体或长语种下按钮文案会被省略号静默截断。
- **修复建议**：将 `InputSettingsScreen` 方案按钮栏与 `CoverSearchDialog` 底部按钮栏迁至 `FlowRow`（或按需开放 `maxLines` 参数）。

### 4.【中 / #5】`CoverSearchDialog` 绑定期间未禁用二级返回，且退场期间未拦截 `onCandidateClick`
- **位置**：
  - `app/src/main/java/com/tyranor/next/ui/game/GameScreen.kt:1259-1280, 1481-1500, 1722-1727`
  - `app/src/main/java/com/tyranor/next/ui/game/GameOverlayMotion.kt:80-96`
- **根因**：
  1. `CoverSearchDialog` 在 `binding == true` 时禁用了遮罩点击、关闭按钮与 `onDismissRequest`，但 `BackHandler(enabled = drillDownWork != null)`（第 1500 行）和二级「返回」按钮（第 1723-1726 行）未检查 `!binding`，下载绑定期间仍可退回一级列表。
  2. `onBind` 协程运行在父组件 `GameActionsSheet` 的 `scope` 上（第 1259 行），而 `onCandidateClick`（第 1481 行）未检查 `overlay` 是否已开始退场（约 200ms 关闭窗口）。若在关闭瞬间通过快速点击/键盘/无障碍触发 `onCandidateClick`，弹窗关闭后 `onBind` 仍会在后台完成并执行 `navigation.close()`（第 1276 行）把整个操作抽屉一起关闭。
- **修复建议**：
  - 补全 `BackHandler(enabled = drillDownWork != null && !binding)` 与二级返回按钮 `enabled = !binding`。
  - 在 `GameOverlayMotion` 暴露只读 `isClosing` 门控 `onCandidateClick` / `search` / `openWork`，且 `onBind` 完成后先确认当前弹窗仍处于对应会话才调用 `navigation.close()`。

### 5.【中 / #6】后台常驻页与二级页混用 `collectAsState()`，在 `ON_STOP` 后台态仍触发重组与 Keystore/网络工作
- **位置**：
  - `app/src/main/java/com/tyranor/next/ui/game/GameScreen.kt:252, 830-833, 1317`
  - `app/src/main/java/com/tyranor/next/ui/game/PlayStatusDialog.kt:308-311`
  - `app/src/main/java/com/tyranor/next/ui/cover/CoverScraperSettingsActivity.kt:146-149, 152`
- **根因**：`GameScreen`（含 `stayDuringNavigation` 的 `GameActionsSheet`）在切换至设置页/浏览器时处于 `ON_STOP` 后台态仍保持组合；`CoverScraperSettingsActivity` 在拉起浏览器授权时同样处于 `ON_STOP`。上述多处直接调用 `collectAsState()`，导致后台期间 `statusVersion` / `sessionEpoch` / `CoverScrapeTaskManager.state` 变化时立即驱动后台重组、`produceState` Keystore 解密及 `ensureUsernameLoaded`/`ensureIdentityLoaded` 请求。
- **修复建议**：将上述业务状态流统一改用 `collectAsStateWithLifecycle()`。

### 6.【中 / #7】`SingleFlightRefresh` 等待方在「在途刷新期间并发完成新登录」场景下误判失败
- **位置**：
  - `app/src/main/java/com/tyranor/next/core/auth/SingleFlightRefresh.kt:52-68`
  - `app/src/main/java/com/tyranor/next/core/auth/SecureAuthStore.kt:304-320`
  - `app/src/main/java/com/tyranor/next/core/auth/BangumiAuthService.kt:310-355`、`HikarinagiAuthService.kt:300-347`、`NextMoeAuthService.kt:761-798`
- **根因**：领导者线程持旧 `expectedEpoch = 1` 发起刷新 IO 期间，若用户完成新登录使 `credentialEpoch` 推进至 `2`，领导者回写因代次不匹配被拒并返回 `lastResult = null`。此时 `SingleFlightRefresh` 等待方（如封面搜索/状态读取）被唤醒后调用 `adoptSingleFlightAccessToken(leaderToken = null, ...)`，在第 313 行因 `leaderToken == null` 直接返回 `null`（`RefreshFailed`），忽视了存储中已经就绪的新会话有效令牌。
- **修复建议**：当 `leaderToken == null` 但等待前后的 `credentialEpoch` 已推进且 `validStoredAccessToken(context)` 命中新有效令牌时，允许读取侧等待方采纳该有效令牌。

### 7.【中 / #8】`SPEC.md`、`AGENTS.md`/`ARCHITECTURE.md` 存量偏差注记与 `CHANGELOG.md` 结构同步不足
- **位置**：
  - `SPEC.md:250-252`（对照 `SPEC.md:159-160`）
  - `AGENTS.md:136` & `ARCHITECTURE.md:175`（对照 `GitHubUpdateChecker.kt:123, 153`、`UpdateApkDownloader.kt:23, 85`）
  - `CHANGELOG.md:4-7`
- **详情**：
  1. `SPEC.md:252`（「9. 授权与应用更新」）仍只写 `Hikarinagi OAuth 支持授权、回调校验及 token 刷新`，落后于第 6 节与现行四来源授权体系。
  2. `AGENTS.md:136` 与 `ARCHITECTURE.md:175` 移除了 `HikarinagiAuthService` 后仅保留 `KrkrOnlinePatchService` 存量偏差，但 `core/updater` 下的 `GitHubUpdateChecker` 与 `UpdateApkDownloader` 同样仍在 core 层拼接本地化字符串返回。
  3. `CHANGELOG.md:4-6` 位于 `## 未发布`（第 7 行）标题之上，脱离版本小节。
- **修复建议**：同步补充 `SPEC.md:252` 四源授权说明、在存量偏差处补充 `core/updater`（或作为待迁移项列明），并将 `CHANGELOG.md:4-6` 移入 `## 未发布` 之下。

### 8.【低】其余代码逻辑、健壮性、文档与 UI 规范细节（#12–#16）
- **#12 逻辑/并发微瑕**：
  - `CredentialTokenDialog.kt:279-294`：点击「清空」未置位忙碌态（可引入 `clearing` 与 `verifying` 合并为 `busy`），在 IO 锁争用期间连点或与「确定」并发，对无代次校验的 `VndbTokenDialog` 存在写-清竞态。
  - `NextMoeAuthService.kt:103, 655`：`activeFlowId` 为普通 `var`，`exchangeAuthorizationCode` 第 655 行无锁预检存在可见性竞态，建议加 `@Volatile`。
  - 三端 `refreshAccessToken` 前置顺序不对称：`BangumiAuthService` 先判 `needsReauth` 再判 `ConfigMissing`，`HikarinagiAuthService` / `NextMoeAuthService` 相反（当 `clientId` 缺失或 `refresh_token` 解密失败时返回 `ConfigMissing` 而非 `RefreshFailed`；消费侧目前映射同档文案，未造成用户侧错判）。
- **#13 生命周期与布局细节**：
  - `CoverScraperSettingsActivity.kt:284-286`：`LazyColumn` 位于 `if (statuses != null)` 内且未在外层持有 `rememberLazyListState()`，屏幕旋转重建期间 `authStatuses` 短暂为 `null` 会丢失滚动位置。
  - `GameScreen.kt:1387-1391`：`CoverSearchDialog` 的 `drillDownWork` / `workCovers` 未走 `rememberSaveable`，在 NextMoe 多封面二级页旋转会退回一级；`GameScreen.kt:1322-1356` `CoverSourcePickerDialog` 首帧异步加载 `HikarinagiAuthStore.getStatus` 期间该行呈短暂不可点置灰。
  - `AppAlertDialog.kt:335-340`：改用 `FlowRow` 后，若调用方传 `Arrangement.SpaceBetween`（如 `GraphicsFilterPicker.kt:103-109`）且在极端大字体下折行，第二行的「取消」会左对齐而非右对齐。
- **#14 异常日志、`finally` 与原子递增**：
  - 三端 `*AuthService.performTokenRefresh` 捕获 `completeTokenRefresh` 异常（`BangumiAuthService.kt:398-444`、`HikarinagiAuthService.kt:374-420`、`NextMoeAuthService.kt:819-862`）、`NextMoeAuthService.startAuthorization`（`L162`）/ `runLoopbackFlow`（`L309`）以及 `CoverScraperSettingsActivity.verifyAndSaveBangumiToken`（`L982`）缺少脱敏 `Log.w`。
  - `PlayStatusDialog.kt:1050-1055`（`SubjectBindDialog.search` 的 `searching = false`）与 `GameShortcutImageProcessor.kt:59-62`（`cropped.recycle()`）未置于 `try/finally`；`AppSettingsStore.kt:808` 应改用 `coverScraperSettingsVersion.update { it + 1 }`；`CoverScrapeTaskManager.kt:112-118` 的 `clearFinished` 建议纳入 `synchronized(lock)` 避免与并发 `start()` 交错。
- **#15 文档与注释精确度**：
  - `AGENTS.md:400` 仍写旧弹窗名 `VndbSearchDialog`（实际已为共用 `CoverSearchDialog`）；`AGENTS.md:407-408` 写作 `CredentialTokenDialog.kt（VndbTokenDialog...）`（两子弹窗实定义于 `CoverScraperSettingsActivity.kt:870, 932`）；`ARCHITECTURE.md:154` 将 `verifyVndbToken` 与 `checkVndbPermissions` 共同归因于「唯一消费场景（条目绑定后的权限提示）」，实则前者供封面刮削设置页校验使用。
  - `PlayStatusErrors.kt:12-15` KDoc 漏列 `ui/auth/HikarinagiAuthMessages.kt:24` 与 `NextMoeAuthMessages.kt:24` 对 `play_error_*` 的借用；`GameScreen.kt:765` `DrawerAction` 建议补注“仅跳转独立 Activity 显示箭头，抽屉内弹窗隐藏箭头”以消除与 `AGENTS.md` 字面理解的分歧。
  - `docs/应用持久化数据清单.md`：第 73/74/114/121/122/156/192/221 行引用邻近源码行号明显偏移；第 73 行 Ren'Py 版本仍为 `8.5`（现为 `8.5.3`）；第 78 行 `hikarinagi_auth_secure.xml` 归属漏列 `HikarinagiAuthStore.kt` 且残留过程性标记 `审计 R9-A`；第 169 行混用全局/覆盖键名；全清单漏收 `update_checker.xml`。
- **#16 三语言文案、未使用 import 与存量 UI 债**：
  - `values-en/strings.xml:777` 与 `values-ja/strings.xml:777` 的 `play_error_bangumi_auth_missing` 仍用 `Log in / ログイン`，未与 `values/strings.xml:777`（「授权」）及同组键对齐；`values-ja/strings.xml:740`（`%1$sの状態を選択` 缺空格）、`values/strings.xml:737`（`未在 未萌NextMoe` 多空格）、`values-ja/strings.xml:746`（`エントリ` vs `項目`）、`values-ja/strings.xml:580, 713-714`（`token` vs `トークン`）、`cover_write_content_summary` 句末句号与同组摘要不一致。
  - `app/src/test/java/com/tyranor/next/core/status/PlayStatusServiceHttpTest.kt:11` 存在未使用 `import com.tyranor.next.core.auth.NextMoeAuthError`。
  - 涉及文件的存量 UI 规范债（非本分支引入）：`CoverSearchDialog`（`GameScreen.kt:1555-1597`）未包裹 `LocalAppDialogContent provides true` 与 `NoIndication`；`CoverCandidateOverlay`（`GameScreen.kt:1874-1897`）使用 `NavWhite`/`TextColor` 而非 `CoverBadgeBackground`/`CoverBadgeText`；`GameQuickActionsDialog.kt:404, 444` 两处纯图片/删除按钮未挂 `pressSquish`。

---

## 六、测试质量与覆盖缺口核查（对应 #2、#9–#11）

### 1. 测试质量问题
1. **【高】假 SQLite 迁移测试未在运行时验证 + 设备迁移缺中间起点**：
   - `GameLibraryDatabaseMigrationTest.kt:22-49` 仍用 `Proxy.newProxyInstance(SupportSQLiteDatabase)` 仅录制 `execSQL` 字符串比对；虽已新增真实 SQLite 设备迁移测试 `GameLibraryDatabaseDeviceMigrationTest.kt` 并通过编译，但尚未在真机/模拟器实际跑通，且仅测试 `v2 -> v6` 一条路径，缺少 **`v3 -> v6`（含存量 `runtime_module_package` 行）**、**`v4 -> v6`**、**`v5 -> v6`** 起点及迁移后的 `getRecentGames` / `getQuickLaunchGames` 校验。
2. **【中】10 份测试自复制 `Proxy` 版内存 `SharedPreferences`**：
   - `AuthStatusPersistenceTest.kt`、`AuthStoreBatchWriteTest.kt`、`PlayStatusWriteDecisionTest.kt` 使用非线程安全 `mutableMapOf`/`mutableListOf`；`CoverScraperPolicyTest.kt:80-83` 对 `putString(k, null)` 会引发 `ConcurrentHashMap` 空指针；所有 `Proxy` 均缺 `getStringSet`/`putStringSet`；且在启用 `SecureAuthStore.testCipherDelegate` 时跳过了真实密文读取与损坏密文恢复校验。
   - 核实确认：`SecureAuthStore.kt:96, 173` 已使用 `"$prefsName:$field"` 作为代理键，早先的跨源键碰撞已不存在。
3. **【中】全局测试桩与局部状态绕过**：
   - `testCipherDelegate`、`testHttpRoundTrip`、`GameLibraryFacade.gamesCache` 与各 Store `statusVersion`/`sessionEpoch` 均为进程级共享可变态，依赖 `@After` 复位，且部分测试方法内直接覆写桩（如 `CredentialTokenCleanupTest.kt:224`、`CoverScraperPolicyTest.kt:117`）。
   - `CoverScraperBindAndHikarinagiTest.kt:65-66` 种子空库使 `bindCoverCandidate` 每次都走「游戏不在库」兜底分支；`CoverScrapeMergeTest.kt:189-190` 设 `skipPersistForTest = true` 未验证 `recentGamesCache` / `quickLaunchCache` 与 `GameLibraryRepository.updateCover` 的联动。

### 2. 未覆盖的关键路径
- **封面绑定与自定义封面原子链路**：`CoverScraperService.bindCoverCandidate` 游戏已在库内的落库 + `notifyGamesChanged()` + 旧图清理分支；`VndbCoverService.saveCustomCover` 全部路径；`CoverImageCache.download` 真实字节流读取（`read == 0`、`MAX_COVER_BYTES`）。
- **绑定搜索与批量调度编排**：`CoverScraperService.searchCandidatesForBinding(..., requireCover = false)`（尤其 `BangumiCoverService` 不过滤无封面条目与非法来源拒答）；`CoverScrapeTaskManager.start` 的重入保护与取消态发布；`MainLibraryViewModel` 实时收集 `gameUpdates` / `acknowledgedEventId` / 取消回源刷新及并发交互。
- **游玩状态 HTTP 与绑定方法**：`PlayStatusService.verifyVndbToken`、`checkVndbPermissions`、`readVndbStatus`、`writeVndbStatus`、`readNextMoeStatus`（200 `completion` / 404 `NOT_FOUND` / 410 / 429）、`writeHikarinagiStatus`（200 `success=false`）、`bindSubject` / `unbindSubject` 幂等与空库分支。
- **OAuth 与 UI 层**：三端 OAuth 完整换码与 `NextMoeAuthService.runLoopbackFlow`、身份回填（`ensureUsernameLoaded` / `ensureIdentityLoaded`）、`playStatusDrawerSummary` 四源摘要组合，以及 Compose 弹窗交互与窄屏折行设备测试。

---

## 七、已核实合规的关键领域

1. **三层架构与错误协议**：`core/` 零 Compose/UI 依赖，`ui/` 零引擎层或非规范直连，`engine/` 零改动，`core/net/HttpJson.kt` 完全中性；新增/修改接口全面使用类型化结果或错误码异常，`CancellationException` 均原样向上传播。
2. **数据持久化与安全隔离**：Room `GameEntity` / `GameLibraryDao` / `GameLibraryDatabase` v3→v6 与 `app/schemas/{4,5,6}.json`、`GameRecordCodec`、`EngineScanner` 完全对齐；`backup_rules.xml` 与 `data_extraction_rules.xml` 完整排除全部敏感凭据与库文件；`AndroidManifest.xml` 回调 Activity `exported="false"`、AppAuth `PendingIntent.setPackage` 与 PKCE/恒定时间 `state`/`nonce` 校验均严密成立。
3. **UI 与门禁规范**：`AppSecondaryTopBar`、两档字号（`titleMedium`/`bodyMedium`）、`AppAlertDialog` + `DialogTextButton` + `NoIndication`、`AppSearchField`、`AppNavItem`、`Switch`/`SwitchPreference`、`AppNotice` 与具名动效入口均按 `AGENTS.md` 落地，`check-hardcoded-ui-strings.py`、`check-motion-boundaries.py` 与变更源码增量 Lint 全部 0 违规通过。
