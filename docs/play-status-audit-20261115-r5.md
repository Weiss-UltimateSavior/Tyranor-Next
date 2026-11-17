# `codex/bangumi-vndb-play-status` 分支审计报告（2026-11-15 · R5 工作区最新完整状态）

- **审计对象**：`codex/bangumi-vndb-play-status`（`HEAD cf56a52e` 叠加当前工作区 50 个已修改跟踪文件及 3 个新增未跟踪代码/测试文件 `GameLibraryDatabaseDeviceMigrationTest.kt`、`RemoteBindingPreservationTest.kt`、`CoverSearchCandidateCodecTest.kt`；相对基线 `origin/main` `709687a3` 共涉及 97 个跟踪文件变更 `+17641 / -923` 行及 3 个新增未跟踪源码/测试文件）。
- **审计基准**：[AGENTS.md](../AGENTS.md)、[CONTEXT.md](../CONTEXT.md)、[ARCHITECTURE.md](../ARCHITECTURE.md)、[SPEC.md](../SPEC.md)。
- **审计方式**：严格遵循 `AGENTS.md` 测试与审核规范，分派 5 个相互独立的审查子 Agent（代码逻辑、架构与文档一致性、代码健壮性与生命周期、回归风险与测试覆盖、代码与 UI 规范）对当前完整代码状态独立只读审查；主 Agent 对全部核心发现回源核对源码与行号，并实际运行静态门禁、定向及全量 JVM 单测、`androidTest` 编译与 Android Lint 增量检查。

> 说明：工作区既有 `docs/play-status-audit-20261114.md`、`docs/play-status-audit-20261115.md`、`docs/play-status-audit-20261115-cf56a52e.md` 与 `docs/play-status-audit-20261115-post-cf56a52e.md` 均完整保留历史快照不覆盖。针对上一轮 `post-cf56a52e` 指出的 5 项中严重度问题与绝大部分低严重度问题，当前工作区已基本完成针对性修复（详见第三节）；本报告基于当前最新状态重新独立审计。

---

## 一、已执行验证与实测结果

| 检查项 | 命令 | 结果 |
| --- | --- | --- |
| 三语言字符串与硬编码 CJK 门禁 | `python tools/check-hardcoded-ui-strings.py` | **通过**（`Hardcoded UI string check passed.`，zh / en / ja 三语言键对齐，无 Kotlin 硬编码 CJK） |
| 动效边界门禁 | `python tools/check-motion-boundaries.py` | **通过**（`Motion boundaries: 366 Kotlin files, 0 violations.`） |
| 本地门禁脚本单测 | `python tools/test-hardcoded-ui-strings.py` & `python tools/test_motion_boundaries.py` | **通过**（5 用例 + 11 用例全通过） |
| 本分支定向 JVM 单元测试 | 清空旧结果后执行 `./gradlew.bat :app:testDebugUnitTest --rerun --tests "com.tyranor.next.core.auth.*" --tests "com.tyranor.next.core.cover.*" --tests "com.tyranor.next.core.status.*" --tests "com.tyranor.next.core.net.*" --tests "com.tyranor.next.core.backup.*" --tests "com.tyranor.next.core.game.scan.*" --tests "com.tyranor.next.core.game.storage.*" --tests "com.tyranor.next.ui.auth.*" --tests "com.tyranor.next.ui.cover.*" --tests "com.tyranor.next.ui.game.*" --tests "com.tyranor.next.ui.main.*" --no-daemon` | **通过**（62 个测试类、475 个用例、0 失败、0 错误、0 跳过） |
| 全量 JVM 单元测试 | `./gradlew.bat :app:testDebugUnitTest --no-daemon` | **本分支新增/修改用例 0 失败**（共 1805 项完成、31 项失败、18 项跳过；31 项失败均为 Windows 宿主环境既有路径/符号链接测试 `RuffleRuntimeEnvironmentTest`、`EthornellOperationGateTest`、`LaunchEntryPathTest`、`EthornellSaveStoreTest`、`EthornellSettingsTest`） |
| AndroidTest 编译检查 | `./gradlew.bat :app:compileDebugAndroidTestKotlin --no-daemon` | **通过**（`BUILD SUCCESSFUL`，新增 `GameLibraryDatabaseDeviceMigrationTest` 编译无误） |
| Android Lint 增量检查 | `./gradlew.bat :app:lintDebug --no-daemon` + `python tools/check-lint-diff.py --base origin/main app/build/reports/lint-results-debug.xml` | **变更源码 0 Error**（`Lint errors in reports: 1. Errors on changed sources: 0.`，唯一 Error 为本机 `local.properties:1` `[PropertyEscape]`；变更源码无新增 Error 或 `[StaticFieldLeak]` / `[ModifierParameter]`） |
| 未执行项 | 真机/模拟器 `connectedDebugAndroidTest`（`adb devices` 无连接设备且 CI 未运行）、真实浏览器 OAuth 回调闭环、真实 Keystore 密钥失效恢复、360dp 窄屏真机渲染、`:engine` 相关任务（本分支 `engine/` 零改动） | **未执行**（不得视同已验收） |

---

## 二、总体审计结论

- **总体可合并性评价**：**无阻断（Blocker / High）级缺陷**，前序 `post-cf56a52e` 的全部 5 项中严重度问题均已在当前工作区落实修复，架构边界、错误处理协议、加密存储原子性、OAuth 安全校验及 UI/动效规范整体稳健。
- **剩余中严重度（Medium）问题（2 项）**：
  1. `CoverScrapeTaskManager.gameUpdates` 与并发 `PlayStatusService.unbindSubject` 交错时，`MainLibraryStateReducer.applyScrapedGame` 可能在 `_uiState` 中复活刚解绑的远端条目 ID；
  2. 封面绑定/换图期间 UI 协程被取消或提前关闭 `CoverSearchDialog`，可能造成「旧封面已被删除 + DB 未更新 + 新封面孤儿」或异步完成后意外关停整个 `GameActionsSheet`。
- **低严重度（Low）与测试覆盖缺口**：主要集中在 1 处文档引用偏差（`AGENTS.md` / `ARCHITECTURE.md` 仍提及已移除的 `GameSaveManager.SaveLocation.description`）、`GameScreen` Bangumi 摘要未检 `needsReauth`、部分异常处理缺脱敏 `Log.w`、细微三语言排版一致性，以及真实 SQLite 设备迁移尚未实跑和部分边界单测缺口。

建议在整理提交前优先收敛上述 **2 项中严重度问题** 及 **`AGENTS.md` / `ARCHITECTURE.md` 文档小瑕疵**。

---

## 三、前序（`post-cf56a52e`）问题修复核查表

| 前序编号 | 原问题摘要 | 当前工作区核查 | 代码位置与验证依据 |
| --- | --- | --- | --- |
| #1（中） | `MainLibraryViewModel.replaceGame` 与 `CoverScrapeTaskManager.gameUpdates` 并发覆盖新封面 | **主路径已修，留反向解绑竞态** | `MainLibraryViewModel.kt:220-222, 499-520` 新增局部合并 `applyBindingUpdate` 取代整对象 `replaceGame`；反向 `applyScrapedGame` 竞态见下节 #1。 |
| #2（中） | `HikarinagiAuthStore` / `NextMoeAuthStore` / `VndbAuthStore` 并发擦除 `SECURE_STORAGE_ERROR` | **已完整修复** | `HikarinagiAuthStore.kt:461, 514`、`NextMoeAuthStore.kt:361, 370`、`VndbAuthStore.kt:191, 209` 均已添加哨兵不可被并发读回/写错覆盖的守卫。 |
| #3（中） | `CoverScraperSettingsActivity` 非生命周期感知收集 `authEvent` 导致后台吞提示 | **已修复** | `CoverScraperSettingsActivity.kt:142` 已改为 `collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)`。 |
| #4（中） | `PlayStatusDialog` 旋转重建后 `rememberSaveable` 二级弹窗脱节 | **已修复** | `PlayStatusDialog.kt:132-140, 333-337` 新增 `reconcilePlayStatusSubDialogSources` 按最新 `game` 绑定态自动收敛过期二级弹窗。 |
| #5（中） | 组合期主线程解密 Keystore 与 `SecureAuthStore` 单例全局锁竞争 | **已修复** | `CoverScraperSettingsActivity.kt:153-168`、`GameScreen.kt:851-886, 1309-1311`、`CredentialTokenDialog.kt:99-106` 均下沉 `Dispatchers.IO`；`SecureAuthStore.kt:66-74, 204-279` 改为按 `keyAlias` 分段加锁并缓存 `SecretKey`。 |
| #6（低-中） | 解绑图标缺 error 色、候选截断、缺搜索按钮、按钮行换行 | **主体已修复** | `PlayStatusDialog.kt:973, 1164, 1190-1203` 已补 `leadingIconTint`、`titleMaxLines = 2` 与「搜索」按钮；`AppAlertDialog.kt:335` 与 `GameShortcutCropDialog.kt:362` 已改 `FlowRow`（留局部细节见下节 #6）。 |
| #7（低） | Hikarinagi 非 JSON 404 误归未标记、403 读失败未清摘要缓存 | **已修复** | `PlayStatusService.kt:182-186, 404` 改为 `treatNotFoundAsNull = false`；`PlayStatusDialog.kt:354, 376, 397` 读失败即时调用 `PlayStatusSummaryCache.invalidate`。 |
| #8–#11（低） | 短 TTL 采纳、`startAuthorization` 清事件、`listCovers` 404、点段、孤儿封面、测试键碰撞与实库迁移测试 | **主体已修复** | `SingleFlightRefresh.kt:31, 54`、`SecureAuthStore.kt:304-320`、`NextMoeAuthService.kt:182-184`、`NextMoeCoverService.kt:107, 260`、`HttpJson.kt:154-160`、`CoverScrapeTaskManager.kt:114-121`、`SecureAuthStore.kt:96, 173`（`"$prefsName:$field"`）及新增 `GameLibraryDatabaseDeviceMigrationTest.kt`（待设备实跑）。 |

---

## 四、问题清单总览（当前工作区状态）

| No. | 级别 | 维度 | 问题摘要 | 主要位置 |
| --- | --- | --- | --- | --- |
| 1 | 中 | 逻辑/并发 | 批量刮削 `gameUpdates` 与并发 `unbindSubject` 交错时，`applyScrapedGame` 可能在 `_uiState` 中复活已解绑的条目 ID | `MainLibraryViewModel.kt:91-95, 523-541`、`CoverScrapeTaskManager.kt:53-59, 97-108` |
| 2 | 中 | 健壮性/生命周期 | 封面绑定/换图先删旧图后回 UI 持久化，取消协程或 `CoverSearchDialog` 提前关闭会导致旧封面丢失或误关整个操作抽屉 | `GameScreen.kt:1242-1270, 1476-1492, 1710`、`CoverScraperService.kt:225-237`、`VndbCoverService.kt:88-90` |
| 3 | 低 | 架构/文档 | `AGENTS.md` 与 `ARCHITECTURE.md` 引用已不存在的 `GameSaveManager.SaveLocation.description`；`docs/应用持久化数据清单.md` 有局部偏移 | `AGENTS.md:136`、`ARCHITECTURE.md:175`、`GameSaveManager.kt:51-57`、`docs/应用持久化数据清单.md:57, 115` |
| 4 | 低 | 逻辑/边界 | `GameActionsSheet` Bangumi 摘要门控未检 `needsReauth`；`PlayStatusPickDialog` 重试行退场期可连点；`writeStatus` 读 `game` 非 `latestGame`；`deleteCoverFile` 前缀缺少分隔符边界 | `GameScreen.kt:380-387, 852`、`PlayStatusDialog.kt:411, 919, 935-946` |
| 5 | 低 | 健壮性/性能 | 登出 `clear()`/AppAuth 回调/`ServerSocket` 在主线程持锁或绑定；刮削设置页返回 `GameScreen` 可能重复提示；4 处异常分支缺 `Log.w`；旧版 Hikarinagi 中断续迁与短 TTL 微窗口 | `CoverScraperSettingsActivity.kt:247-260, 315`、`NextMoeAuthService.kt:160-164`、`AuthFormHttp.kt:50`、`GameScreen.kt:1254`、`GameQuickActionsDialog.kt:206`、`VndbCoverService.kt:133`、`HikarinagiAuthStore.kt:126-146`、`SingleFlightRefresh.kt:38-55` |
| 6 | 低 | 规范/三语言文案 | `PlayStatusWriteDecision` KDoc 漏列字段；`game_play_status_unbind` 默认单行；`CropDialogActions` 右侧两钮未折行；三语言文案空格/标点/用词微瑕 | `PlayStatusDialog.kt:969-980, 1207-1216`、`GameShortcutCropDialog.kt:374-400`、`values-ja/strings.xml:710, 747`、`values*/strings.xml:561, 702` |
| 7 | 低 | 测试质量与覆盖 | `GameLibraryDatabaseMigrationTest` 为 `Proxy` 镜像；`androidTest` 迁移未实跑且缺 v3/v4/v5 起点；`testCipherDelegate` `clear()` 残留与多处业务/UI 覆盖缺口 | 见第六节 |

---

## 五、重点问题详情与修复建议

### 1.【中】`applyScrapedGame` 与并发 `unbindSubject` 交错可能复活已解绑 ID

- **位置**：
  - `app/src/main/java/com/tyranor/next/ui/main/MainLibraryViewModel.kt:91-95, 220-222, 523-541`
  - `app/src/main/java/com/tyranor/next/core/cover/CoverScrapeTaskManager.kt:53-74, 91-108`
  - `app/src/main/java/com/tyranor/next/ui/game/GameScreen.kt:1104`
- **根因与触发路径**：
  1. 批量刮削期间允许用户打开「游玩状态」并发解绑。假设 `CoverScrapeTaskManager` 对游戏 `G` 在 `persistScrapedCover`（`CoverScrapeTaskManager.kt:57`）落库时，当时 `G` 仍携带（或刚被刮削填补）`vndbId = "v10"`，随后释放锁并在第 59 行向 `MutableSharedFlow` 发送 `_gameUpdates.emit(persisted)`。
  2. 在 `MainLibraryViewModel` 的收集器（`MainLibraryViewModel.kt:91-95`）调度执行 `applyScrapedGame` 之前，用户执行解绑：`PlayStatusService.unbindSubject` 将数据库与 `GameLibraryFacade` 中 `G.vndbId` 置为 `null`，UI 通过 `applyBindingUpdate` 将 `_uiState` 中 `G.vndbId` 更新为 `null`。
  3. 紧接着迟到的 `persisted` 到达 `MainLibraryStateReducer.applyScrapedGame`（`MainLibraryViewModel.kt:529-533`）：
     ```kotlin
     vndbId = current.vndbId?.takeIf { it.isNotBlank() } ?: updated.vndbId
     ```
     因 `current.vndbId` 此时已是 `null` 而 `updated.vndbId` 仍为 `"v10"`，**`_uiState` 会把刚解绑的 `"v10"` 重新填回**（四个来源字段均存在同一逻辑）。
  4. 若该批量刮削后续耗时很长或中途被取消/异常结束（`CoverScrapeTaskManager.kt:68, 73` 仅发布 `result = null`，不触发 `acceptPersistedGames` 整库校准），`_uiState` 将与 Room 分叉，直至下次 `onForeground()` 才恢复。
- **修复建议**：
  - 让 `CoverScrapeTaskManager.gameUpdates` 连同更新后的 `BindingRevisionSnapshot`（或仅推送封面变更，或在 `applyScrapedGame` 中仅当 `current` 与 `updated` 对应的该源修订号一致/未发生解绑时才允许回填），或者在刮削取消/失败后也调用 `refreshFromStorage()` 对齐快照。

### 2.【中】封面绑定/换图期间取消或关闭弹窗导致旧封面被删但未落库，或意外关停整个操作抽屉

- **位置**：
  - `app/src/main/java/com/tyranor/next/ui/game/GameScreen.kt:1242-1270, 1476-1492, 1710`
  - `app/src/main/java/com/tyranor/next/core/cover/CoverScraperService.kt:210-238`
  - `app/src/main/java/com/tyranor/next/core/cover/VndbCoverService.kt:74-98`
  - `app/src/main/java/com/tyranor/next/ui/game/GameQuickActionsDialog.kt:200-218`
- **根因与触发路径**：
  1. `CoverScraperService.bindCoverCandidate`（`CoverScraperService.kt:226`）与 `VndbCoverService.saveCustomCover`（`VndbCoverService.kt:89`）在下载/拷贝完新封面后、返回 `ScanGame` 给 UI 执行 `onGameUpdated(updated)` 持久化之前，已提前调用 `CoverImageCache.deleteCachedCover` 删除旧封面文件。若此时发生屏幕旋转或关闭抽屉导致 UI `scope` 取消，`withContext(Dispatchers.IO)` 恢复时抛 `CancellationException`，`onGameUpdated` 不会执行——DB 仍指向已被删除的旧 `coverUri`，新文件则变成孤儿残留在 `covers_remote/`。
  2. `CoverSearchDialog` 在 `binding == true` 时，未像 `SubjectBindDialog` / `CredentialTokenDialog` 一样门控关闭：遮罩点击（`GameScreen.kt:1491`）与「关闭」按钮（`GameScreen.kt:1710`）及返回键在 `binding` 期间均可关闭弹窗；而 `onBind` 协程挂在 `GameActionsSheet` 的 `scope`（`GameScreen.kt:1248`），即使用户已关掉 `CoverSearchDialog` 回到抽屉继续操作，绑定完成后仍会执行 `navigation.close()`（`GameScreen.kt:1263`）把整个 `GameActionsSheet` 一起关掉。
- **修复建议**：
  - 将 `CoverSearchDialog` 的遮罩/返回/关闭按钮在 `binding` 期间一致禁用（与 `SubjectBindDialog` / `CredentialTokenDialog` 保持同一 BK-56 等待语义），或将单行封面落库（含旧封面删除）收敛到 core 层原子方法内部完成，避免半持久化状态。

---

## 六、其余低严重度发现与测试覆盖核查

### 1. 低严重度细节（#3–#6）

- **#3 文档同步偏差**：
  - `AGENTS.md:136` 与 `ARCHITECTURE.md:175` 仍写有「`GameSaveManager.SaveLocation.description` 和 `KrkrOnlinePatchService` 仍有本地化字符串载荷」；核查 `GameSaveManager.kt:51-57`，`SaveLocation` 已使用 `@get:StringRes val descriptionRes: Int`，不存在 `description` 字段，建议删去 `GameSaveManager.SaveLocation.description` 存量偏差描述。
  - `docs/应用持久化数据清单.md:115` 对 `files/covers_remote/` 仍写为「VNDB 下载封面 + 自定义封面」，且代码行号 `CoverSupport.kt:49,103`、`VndbCoverService.kt:69`、`GameScreen.kt:336` 已随版本演进发生偏移（另第 57 行「可重学缓存」为错别字）。
- **#4 逻辑与健壮性微瑕**：
  - `GameScreen.kt:852`：`bangumiReady` 仅检 `authorized` 而未像 `vndbReady` / `hikarinagiReady` / `nextmoeReady`（`853-864`）那样校验 `!status.needsReauth`；当 Bangumi 令牌被吊销（`authorized = true && needsReauth = true`）时抽屉仍显示并缓存 `"Bangumi · 已绑定"`。
  - `PlayStatusDialog.kt:919, 935-946`：`PlayStatusPickDialog` 重试行用 `rememberLastNonNull(onRetry)` 保留退场帧渲染，但 `onClick` 仅检 `!writing` 未检 `onRetry != null`，退场动画窗口内连点可重复递增 `nextmoeRefreshNonce`。
  - `PlayStatusDialog.kt:411`：`writeStatus` 读取捕获的 `game` 而非第 `483, 550, 1062` 行的 `latestGame`，建议统一为 `latestGame`。
  - `GameScreen.kt:380-387`：`deleteCoverFile` 仅判断 `startsWith(coverDir)` 未拼接 `File.separator`，严谨度低于 `CoverSupport.kt:109`。
- **#5 性能、线程锁与日志**：
  - `CoverScraperSettingsActivity.kt:315, 318, 339, 367` 与 `CredentialTokenDialog.kt:285` 在主线程调用 `*AuthStore.clear(ctx)`，若 IO 线程正持有同一 `storeLock` 执行 Keystore 操作会产生锁等待；`BangumiAuthService.kt:215-236` 与 `HikarinagiAuthService.kt:195-228` 的 AppAuth 回调仍在主线程执行令牌加密写回；`NextMoeAuthService.kt:160-164` 在主线程绑定 `127.0.0.1:0` `ServerSocket`（不触发 DNS，但会触动 `StrictMode.detectNetwork`）。
  - `CoverScraperSettingsActivity.kt:247-260` 消费批量刮削结果并 `clearFinished` 后，`MainLibraryViewModel.kt:104-128` 仍可能把同一次 `scrapeEventId`/`scrapeMessage` 写入 `MainLibraryUiState`，返回 `GameScreen`（`GameScreen.kt:259-263`）时可能再次弹提示。
  - `AuthFormHttp.kt:50-51`、`GameScreen.kt:1254-1256`、`GameQuickActionsDialog.kt:206-208`、`VndbCoverService.kt:133-135` 捕获异常后未记录脱敏 `Log.w`，与 `HttpJson.kt:88` / `PlayStatusDialog.kt:561, 1073` 不一致。
  - `HikarinagiAuthStore.kt:126-146` 中断续迁分支（若历史残留仅写完 `refresh_token` 而 `access_token` 为空）缺乏单测兜底；`SingleFlightRefresh.kt:38-55` 在极短 `1..60s` TTL 下存在极窄抢占微窗口。
- **#6 UI 规范、注释与三语言文案细节**：
  - `PlayStatusDialog.kt:1207-1216`：`PlayStatusWriteDecision` KDoc 漏列新增字段 `triggerNextMoeReread`。
  - `PlayStatusDialog.kt:969-980`：`game_play_status_unbind` 条目使用默认 `titleMaxLines = 1`（对比第 1164 行候选条目已设 `titleMaxLines = 2`），大字体窄屏存在截断风险。
  - `GameShortcutCropDialog.kt:374-400`：外层已迁 `FlowRow`，但右侧 `Row { Cancel, Confirm }` 内部未折行，极端大字体下受 `NoRippleActionText` 的 `maxLines = 1, overflow = TextOverflow.Ellipsis` 约束可能省略号截断。
  - 三语言微瑕：`values-ja/strings.xml:747`（`%1$s項目をバインド` 缺空格）、`values-ja/strings.xml:710`（同句混用「トークン」与「token」）、`values*/strings.xml:702 vs 710`（Bangumi 指示句末有句号而 VNDB 无句号）、`values-en/strings.xml:702`（`"web grant"` 与 `"authorization"` 措辞略异）、`values/strings.xml:561, 564, 777`（Bangumi 摘要沿用「登录」而行内按钮已改「授权」）。

### 2. 单元测试质量与覆盖缺口核查

- **已修复的测试质量问题**：
  - `SecureAuthStore.testCipherDelegate`（`SecureAuthStore.kt:96, 173`）已改为传 `"$prefsName:$field"`，**不再发生跨源键碰撞**（注：因四源 `clear()` 只清空 `SharedPreferences` 而不清桩内 `memoryTokens`，直接调用 `SecureAuthStore.decryptField` 仍会命中残留内存值，且真实 Keystore 加解密/`recoverKeyAndEncrypt` 在 JVM 测试中零执行）。
  - `app/src/androidTest/java/com/tyranor/next/core/game/storage/GameLibraryDatabaseDeviceMigrationTest.kt` 已补齐真机/模拟器 SQLite 实库迁移测试，当前已通过 `:app:compileDebugAndroidTestKotlin` 编译校验。
- **保留的测试质量隐患与覆盖缺口**：
  1. `GameLibraryDatabaseMigrationTest.kt:22-49` 仍采用 `Proxy.newProxyInstance(SupportSQLiteDatabase)` 仅录制 `execSQL` 字符串比对（镜像实现），而真实 SQLite 迁移测试 `GameLibraryDatabaseDeviceMigrationTest.kt` **尚未在设备/CI 实际执行**，且仅覆盖 `v2 -> v6` 一条起点，缺少独立 **`v3 -> v6`（含存量 `runtime_module_package` 行）**、**`v4 -> v6`**、**`v5 -> v6`** 起点及 `GameLibraryDatabase.get(context)` 真实 Builder 配置校验。
  2. `HikarinagiAuthStore.ensureMigrated` 旧明文 `hikarinagi_auth.xml` 迁移（正常迁移、校验失败保留、中断续迁、`"PERMISSION"` 分离）当前 **0 单测覆盖**。
  3. `GameLibraryRepository` 真实 Room 集成写（单测均 `skipPersistForTest = true`）、`CoverScrapeTaskManager.start` 端到端任务调度、`VndbCoverService.searchCandidates`（直连 `HttpURLConnection` 异常/超限分支）、`PlayStatusService`（VNDB 读写 HTTP 分支、Hikarinagi `write` 200/404/`success=false`、NextMoe 404/429/401）、`NextMoeAuthService.runLoopbackFlow`、`AuthFormHttp.postAuthForm`、`MainLibraryViewModel` 协程队列并发集成，以及全部 Compose UI 交互均缺乏自动化测试覆盖。

---

## 七、已核对无问题的关键领域

- **三层架构与 `core/net` 中性边界**：`core/` 零 Compose/UI 依赖，`ui/` 零引擎层或 `core/net` 越层依赖，`engine/` 零改动，`core/net/HttpJson.kt` 完全中性无业务引用。
- **错误协议与 `CancellationException`**：`core/auth`、`core/cover`、`core/status` 全部返回类型化结果或携带错误码的异常，UI 文案映射收口；全部新增/修改 `catch` 块均原样上抛 `CancellationException`。
- **Room Schema 与备份排除**：`GameLibraryDatabase` v3→v6 显式迁移、`app/schemas/{4,5,6}.json` 与 `GameEntity` 21 列/5 索引对齐；四套 `*_auth_secure.xml` 与 `hikarinagi_auth.xml`、`game_library.db*` 均排除出云备份与设备迁移。
- **OAuth 安全与跨流程竞态**：PKCE S256、恒定时间 `state`/`nonce` 校验、`exported="false"` 回调 Activity + `PendingIntent.setPackage`、环回 `127.0.0.1:0` 上限与隔离、`credential_generation` 防滞留覆盖、`sessionEpoch` 与 `credentialEpoch` 双代次隔离均核验成立。
- **UI、动效与门禁**：`AppSecondaryTopBar`、两档字号（`titleMedium`/`bodyMedium`）、`AppAlertDialog` + `DialogTextButton` + `NoIndication`、`AppSearchField`、`AppNavItem`（含 `DialogItemSurface` 与 `trailingText` 50% 限宽）、`AppNotice`、`pressSquish` / `shakeOnTrigger` / `BlurCrossfade` / `ExpandVisibility`、`check-hardcoded-ui-strings.py`、`check-motion-boundaries.py` 与变更源码 Lint 均核验通过。
