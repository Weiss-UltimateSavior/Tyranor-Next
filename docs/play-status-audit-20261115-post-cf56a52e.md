# `codex/bangumi-vndb-play-status` 分支审计报告（2026-11-15 · `cf56a52e` + 工作区最新修订）

- **审计对象**：`codex/bangumi-vndb-play-status`（HEAD `cf56a52e` 叠加当前工作区 34 个已修改跟踪文件及 2 个新增未跟踪单测文件 `RemoteBindingPreservationTest.kt`、`CoverSearchCandidateCodecTest.kt`，相对基线 `origin/main` `709687a3` 共涉及 95 个文件变化）。
- **审计基准**：[AGENTS.md](../AGENTS.md)、[CONTEXT.md](../CONTEXT.md)、[ARCHITECTURE.md](../ARCHITECTURE.md)、[SPEC.md](../SPEC.md)。
- **审计方式**：严格按 `AGENTS.md` 强制要求，分派 5 个相互独立的审查子 Agent（代码逻辑、架构、代码健壮性、回归风险与测试覆盖、代码与 UI 规范）对当前完整状态逐文件实证审查；主 Agent 对全部核心发现与改动文件回源核对，并实际执行本地静态门禁、定向单元测试与增量 Lint 检查。

> 说明：工作区既有的 `docs/play-status-audit-20261114.md`、`docs/play-status-audit-20261115.md` 与 `docs/play-status-audit-20261115-cf56a52e.md` 分别记录了早期与 `cf56a52e` 提交态的历史快照，本报告完整保留既有文档不覆盖。此前 `cf56a52e` 报告中的 8 项中-高 / 中严重度问题（`readVndbStatus` 绕过 `needsReauth`、读路径/缓存使用 `credentialEpoch`、`CredentialTokenDialog` 按键重复解密 Keystore、`unbindSource`/`SubjectBindDialog.bind` 协同与旋转保存、`parseIdTokenUsernameRaw` 回退 `"sub"`、`BangumiAuthStore` 漏清 `KEY_AUTH_IN_PROGRESS_AT`、`NextMoeAuthEvent` TTL 过滤、`PlaySourceRow` 2 行截断，以及多项低级别细节）均已在当前工作区修改中得到针对性修复；本报告基于最新完整代码状态重新审计。

---

## 一、已执行验证与实测结果

| 检查项 | 命令 | 结果 |
| --- | --- | --- |
| 三语言字符串与硬编码 CJK 门禁 | `python tools/check-hardcoded-ui-strings.py` | **通过**（`Hardcoded UI string check passed.`，zh / en / ja 三语言键对齐，无 Kotlin 硬编码 CJK） |
| 动效边界门禁 | `python tools/check-motion-boundaries.py` | **通过**（`Motion boundaries: 366 Kotlin files, 0 violations.`） |
| 本地门禁脚本单测 | `python tools/test-hardcoded-ui-strings.py` & `python tools/test_motion_boundaries.py` | **通过**（5 用例 + 11 用例全通过） |
| 本分支定向 JVM 单元测试 | 清空旧结果后执行 `./gradlew.bat :app:testDebugUnitTest --rerun --tests "com.tyranor.next.core.auth.*" --tests "com.tyranor.next.core.cover.*" --tests "com.tyranor.next.core.status.*" --tests "com.tyranor.next.core.net.*" --tests "com.tyranor.next.core.backup.*" --tests "com.tyranor.next.core.game.scan.*" --tests "com.tyranor.next.core.game.storage.*" --tests "com.tyranor.next.ui.auth.*" --tests "com.tyranor.next.ui.cover.*" --tests "com.tyranor.next.ui.game.*" --no-daemon` | **通过**（60 个测试文件、452 个用例、0 失败、0 错误、0 跳过） |
| Android Lint 增量检查 | `./gradlew.bat :app:lintDebug --no-daemon` + `python tools/check-lint-diff.py --base origin/main app/build/reports/lint-results-debug.xml` | **变更源码 0 Error**（`Lint errors in reports: 1. Errors on changed sources: 0.`，唯一 Error 为本机 `local.properties:1` `[PropertyEscape]`；变更文件无新增 Error 或 `[StaticFieldLeak]` / `[ModifierParameter]`） |
| 未执行项 | 全量 `:app:testDebugUnitTest`、`:engine` 相关任务（本分支 `engine/` 零改动）、真机 Room v3→v6 迁移、真实浏览器 OAuth 回调闭环、360dp 窄屏真机渲染 | **未执行**（不得视同已验收） |

---

## 二、总体审计结论

- **架构与主链路结论**：**无阻断（Blocker / High）级缺陷**。三层依赖方向（`ui -> core -> engine` 与中性 `core/net`）、类型化 `core -> ui` 错误协议、`CancellationException` 透传、Room v3→v6 迁移与 `app/schemas/*.json` 对齐、4 套 `*_auth_secure.xml` Keystore 隔离及云备份/设备迁移排除、OAuth PKCE S256 / 环回监听 / `SingleFlightRefresh` 并发去重、`*SessionEpoch` 隔离与 `AppSecondaryTopBar` / `AppAlertDialog` / `AppSearchField` / `AppNavItem` / `AppNotice` / 动效规范均核验通过；`cf56a52e` 之后工作区补齐的多项修复（含 Keystore 永久失效重建、`SecureAuthStore.oauthExpiresAtMillis` / `adoptSingleFlightAccessToken`、`urlEncodePathSegment`、`GameLibraryFacade.notifyGamesChanged` 等）整体稳健。
- **剩余关注点**：本次 5 维独立审查与主 Agent 交叉核验共确认 **5 项中严重度（Medium）问题**与 **11 项低严重度（Low）/ 测试覆盖缺口问题**，集中在：
  1. `MainLibraryViewModel.replaceGame` 与批量刮削并发交错时可能覆盖新刮削封面；
  2. `HikarinagiAuthStore` / `NextMoeAuthStore` 的读后清错与 `VndbAuthStore` 非空错误写回在并发窗口可能擦除 `SECURE_STORAGE_ERROR` 哨兵；
  3. `CoverScraperSettingsActivity` 非生命周期感知收集 `authEvent` 导致后台吞提示；
  4. `PlayStatusDialog` 旋转重建后 `rememberSaveable` 二级弹窗状态可能与已更新绑定脱节；
  5. 组合期主线程 Keystore 解密与 `SecureAuthStore` 全局单锁粒度问题，以及少数 UI 规范细节（危险条目图标缺 error 染色等）和单测真实性/覆盖缺口。

建议在合入 `main` 前优先收敛第 1–4 项中严重度问题，并将当前工作区修改提交整理。

---

## 三、前序（`cf56a52e`）问题修复核查表

| 前序编号 | 原问题摘要 | 当前工作区核查 | 代码位置与验证依据 |
| --- | --- | --- | --- |
| #1（中-高） | `readVndbStatus` 绕过 `needsReauth` 门控并可能抹除 `SECURE_STORAGE_ERROR` | **主路径已修，留跨源边界** | `PlayStatusService.kt:452, 592, 743-748` 新增 `vndbPreRequestBlockedError` 阻断 `TOKEN_INVALID` 与 `SECURE_STORAGE_ERROR`；`VndbAuthStore.kt:206` 禁止空 `reason` 擦除 `SECURE_STORAGE_ERROR`。同类读后清错守卫在 Hikarinagi/NextMoe 尚缺（见下节 #2）。 |
| #2（中） | 读路径与 `PlayStatusSummaryCache` 使用 `credentialEpoch` 而非 `sessionEpoch` | **已修复** | `PlayStatusDialog.kt:294-379` 与 `GameScreen.kt:820-895` 均已改用 `*SessionEpoch`，同账号后台刷新不中断读取或震荡缓存。 |
| #3（中） | `CredentialTokenDialog` 每次键入重组同步解密 Keystore | **已修复** | `CredentialTokenDialog.kt:98-100` 改为 `remember` + `remember(authVersion)`（`CoverScraperSettingsActivity.kt:444, 451` 传入版本键）。 |
| #4（中） | `unbindSource` / `SubjectBindDialog.bind` 协程取消脱节与旋转丢结果 | **已修复** | `PlayStatusService.kt:644` 调用 `GameLibraryFacade.notifyGamesChanged()`；`PlayStatusDialog.kt:531, 537, 1003-1007, 1042, 1048` 完成 cache 失效下沉 IO、`latestGame` 与 `rememberSaveable`（留旋转二级弹窗状态校验，见下节 #4）。 |
| #5（中） | `parseIdTokenUsernameRaw` 回退 `"sub"` 抑制 `/userinfo` | **已修复** | `NextMoeAuthService.kt:883-907` 增加 `includeSubFallback = false`。 |
| #6（中） | `BangumiAuthStore.saveTokensLocked` 未清零 `KEY_AUTH_IN_PROGRESS_AT` | **已修复** | `BangumiAuthStore.kt:314` 在 `clearIdentity` 时置 `0L`。 |
| #7（中） | `NextMoeAuthService.authEvent` 跨页面长期滞留 | **TTL 已缓解，后台消费尚存** | `NextMoeAuthService.kt:57-65` 与 `CoverScraperSettingsActivity.kt:201-204` 增加 2 分钟 `isFresh()`；但 `collectAsState()` 在后台提前消费问题见下节 #3。 |
| #8（中） | `PlaySourceRow` 在 360dp 下两行截断报错与 ID | **已修复** | `AppNavItem.kt:87, 160` 新增 `summaryMaxLines`，`PlayStatusDialog.kt:727` 传 `Int.MAX_VALUE`。 |
| #9–#16（低） | 非 JSON 404、空 id、VNDB 快照、`expires_in`/短 TTL、路径空格、`read==0`、CE 与 UI/文档细节 | **主体已修复** | 已在工作区修改及新增单测 `RemoteBindingPreservationTest.kt`、`CoverSearchCandidateCodecTest.kt` 等落地。 |

---

## 四、问题清单总览（当前工作区状态）

| No. | 级别 | 维度 | 问题摘要 | 主要位置 |
| --- | --- | --- | --- | --- |
| 1 | 中 | 并发/回归 | `MainLibraryViewModel.replaceGame` 与 `CoverScrapeTaskManager.gameUpdates` 并发交错可能回滚刚刮削的封面或绑定 | `MainLibraryViewModel.kt:190-214, 409-436`、`GameScreen.kt:1108`、`PlayStatusDialog.kt:556, 695` |
| 2 | 中 | 逻辑/健壮性 | `HikarinagiAuthStore` / `NextMoeAuthStore` 读后清错与 `VndbAuthStore` 非空错误写回在并发窗口可能擦除 `SECURE_STORAGE_ERROR` | `HikarinagiAuthStore.kt:408-413, 493-513`、`NextMoeAuthStore.kt:248-253, 366-371`、`VndbAuthStore.kt:199-213` |
| 3 | 中 | 健壮性/生命周期 | `CoverScraperSettingsActivity` 用非生命周期感知 `collectAsState()` 收集 `authEvent`，浏览器切走期间回调提前消费导致回前台丢提示 | `CoverScraperSettingsActivity.kt:138, 198-217`、`NextMoeAuthService.kt:53-66` |
| 4 | 中 | 健壮性/生命周期 | `PlayStatusDialog` 在 `bind`/`unbind` 落库后发生屏幕旋转，`rememberSaveable` 会恢复过时的 `pickSource` / `bindSource` | `PlayStatusDialog.kt:317-318, 660-708` |
| 5 | 中 | 健壮性/性能 | 组合期/`LaunchedEffect` 主线程同步解密 Keystore 及 `SecureAuthStore` 单例锁竞争 | `SecureAuthStore.kt:82-103, 188-255`、`CoverScraperSettingsActivity.kt:147-154`、`GameScreen.kt:850-871, 1313` |
| 6 | 低-中 | UI 规范 | `PlayStatusDialog` 解绑危险条目未同步设置 `leadingIconTint = colorScheme.error`；绑定候选单行截断；`NoRippleActionText` 单行截断与未迁 `FlowRow` 弹窗兼容性 | `PlayStatusDialog.kt:950-960, 1142-1152`、`NoPressFeedbackButtons.kt:75-76`、`GameShortcutCropDialog.kt:361` |
| 7 | 低 | 逻辑 | `readHikarinagiStatus` 对 HTTP 404 未区分结构化错误与网关 HTML 404；回读失败（403）未失效 `PlayStatusSummaryCache` | `PlayStatusService.kt:394-420, 921`、`PlayStatusDialog.kt:329-379` |
| 8 | 低 | 逻辑/健壮性 | `SingleFlightRefresh` 短 TTL 并发穿插误判失败、`startAuthorization` 未清旧 `authEvent`、`NextMoeCoverService.listCovers` 404、`urlEncodePathSegment` 点段与孤儿封面残留 | `SingleFlightRefresh.kt:34-68`、`SecureAuthStore.kt:280-296`、`NextMoeAuthService.kt:150-215`、`NextMoeCoverService.kt:107, 255`、`CoverScrapeTaskManager.kt:91-113` |
| 9 | 低 | 架构边界 | `core/status` 承担 VNDB `/authinfo` 校验且直连 `core/cover` 内部 `throttle`；`ui/game` 引用 `core/net` 工具与跨包文案 | `PlayStatusService.kt:339-390, 458, 599`、`GameScreen.kt:149` |
| 10 | 低 | 规范/文档/文案 | `AppAlertDialog.kt:37` 未使用 `setValue` import；`strings.xml` 日/中局部措辞标点；局部重复代码、KDoc 与文档微瑕 | `AppAlertDialog.kt:37`、`values-ja/strings.xml:570`、`values/strings.xml:713-714, 737`、`PlayStatusDialog.kt:116-126` |
| 11 | 低 | 测试质量与覆盖 | `GameLibraryDatabaseMigrationTest` 假 SQLite `Proxy`、多份测试 `testCipherDelegate` 跨源键碰撞、两处浅验证与未覆盖主链路 | 见第六节 |

---

## 五、重点问题详情与修复建议

### 1.【中】`MainLibraryViewModel.replaceGame` 与 `CoverScrapeTaskManager.gameUpdates` 并发交错可能回滚新封面

- **位置**：
  - `app/src/main/java/com/tyranor/next/ui/main/MainLibraryViewModel.kt:91-95, 190-214, 409-436`
  - `app/src/main/java/com/tyranor/next/ui/game/GameScreen.kt:1108`
  - `app/src/main/java/com/tyranor/next/ui/game/PlayStatusDialog.kt:556, 695`
- **根因与触发条件**：
  1. `GameActionsSheet` 在批量封面刮削运行期间并不禁止打开「游玩状态」（`GameScreen.kt:1108` 仅调用 `sheetMotion.interact()`，未检查 `isBatchScrapingActive()`）。
  2. 用户在游玩状态弹窗中绑定或解绑条目时，`PlayStatusService.bindSubject` / `unbindSubject` 先在 IO 线程经 `GameLibraryFacade.updateGameCoverMetadata` 单行落库并返回 `updated`（此时其 `coverUri` 仍为绑定前旧值，如 `null`），同时调用 `GameLibraryFacade.notifyGamesChanged()`。
  3. 若在此 `withContext(Dispatchers.IO)` 返回至主线程执行 `onGameUpdated(updated)` -> `MainLibraryViewModel.replaceGame(updated)` 的窗口内，`CoverScrapeTaskManager.gameUpdates` 恰好发布并把新刮削的 `coverUri`/`coverSource` 合入 `_uiState.value.games`：
     - `replaceGame(updated)` 在第 191 行取到已含新封面的 `before`，然后第 193 行先用旧封面的 `updated` 覆写 `_uiState`；
     - 第 199 行 `mergeChangedGameFields(current, before, updated)` 因 `updated.coverUri != before.coverUri`，**将刚刮削落库的 `coverUri` 与 `coverSource`（或并发补全的他源绑定 ID）覆盖回旧值/`null`**。
- **修复建议**：
  - `bindSubject`/`unbindSubject` 已在 `PlayStatusService` 内完成持久化与 `notifyGamesChanged()` 广播；可让绑定/解绑后的 UI 同步仅局部更新变更的绑定字段与 `metadataTitle`（或新增只合并指定绑定字段的 ViewModel 方法），避免整对象 `replaceGame` 与批量刮削的封面列互相踩踏。

---

### 2.【中】`HikarinagiAuthStore` / `NextMoeAuthStore` 读后清错与 `VndbAuthStore` 并发写回可能擦除 `SECURE_STORAGE_ERROR`

- **位置**：
  - `app/src/main/java/com/tyranor/next/core/auth/HikarinagiAuthStore.kt:408-413, 493-513`
  - `app/src/main/java/com/tyranor/next/core/auth/NextMoeAuthStore.kt:248-253, 325-329, 366-371`
  - `app/src/main/java/com/tyranor/next/core/status/PlayStatusService.kt:417, 441, 470-474`
  - `app/src/main/java/com/tyranor/next/core/auth/VndbAuthStore.kt:199-213`
- **根因与触发条件**：
  1. `HikarinagiAuthStore.saveTokensLocked` 与 `NextMoeAuthStore.saveTokensLocked` 在 Keystore 加密失败（`!allOk`）时，刻意**保留旧 `access_token` 密文**不覆盖，仅写入 `last_error = SecureAuthStore.SECURE_STORAGE_ERROR`。
  2. 若此时恰有在途的 `readHikarinagiStatus` / `readNextMoeStatus`（持旧 `access_token`）返回 200/404，`finishHikarinagiStatusRead`（`PlayStatusService.kt:417`）与 `finishNextMoeStatusRead`（`PlayStatusService.kt:441`）调用 `clearLastErrorIfAccessTokenMatches(context, token)`。
  3. 两者的 `clearLastErrorLocked` 未校验 `current == SecureAuthStore.SECURE_STORAGE_ERROR`（对照 `VndbAuthStore.kt:206` 已保护），会直接将安全存储不可用告警清空。
  4. 同理，`VndbAuthStore.markLastErrorIfTokenMatches` 仅在 `normalized.isEmpty()` 时拦截；若并发 `readVndbStatus` 在 `saveToken` 加密失败窗口收到 `403`，传入 `"PERMISSION"` 仍会覆盖 `SECURE_STORAGE_ERROR` 并重新放行前置门控。
- **修复建议**：
  - 在 `HikarinagiAuthStore.clearLastErrorLocked`、`NextMoeAuthStore.clearLastErrorLocked` 及 `VndbAuthStore.markLastErrorIfTokenMatches`（所有 `reason` 分支）中统一加入 `current == SecureAuthStore.SECURE_STORAGE_ERROR` 守卫，仅允许成功加密写盘新令牌或 `clear()` 清除该哨兵。

---

### 3.【中】`CoverScraperSettingsActivity` 非生命周期感知收集 `authEvent` 导致后台吞提示

- **位置**：
  - `app/src/main/java/com/tyranor/next/ui/cover/CoverScraperSettingsActivity.kt:138, 198-217`
  - `app/src/main/java/com/tyranor/next/core/auth/NextMoeAuthService.kt:53-66, 107-108`
- **根因与触发条件**：
  1. 第 138 行使用 `NextMoeAuthService.authEvent.collectAsState()` 而非 `collectAsStateWithLifecycle()`。
  2. 用户发起未萌 NextMoe OAuth 后，CustomTabs / 系统浏览器处于前台、`CoverScraperSettingsActivity` 处于 `ON_STOP` 后台态。环回线程完成换码并呼叫 `emitAuthEvent` 时，虽然 `Activity` 在后台，`LaunchedEffect(nextmoeAuthEvent?.eventId)` 仍立即触发并调用 `AppNotice.makeText(...).show()`（因无 resumed host，1.5s 后回落为后台 `Toast`，Android 11+ 会抑制非前台应用的文本 Toast 且易被浏览器遮挡），随后第 216 行执行 `clearAuthEvent(event.eventId)`，导致用户关闭浏览器返回设置页后看不到授权结果通知。
  3. 另一方面，若用户放弃授权并直接离开设置页，5 分钟环回超时发射的 `CANCELLED` 事件仍会在 `StateFlow` 中滞留 2 分钟（`AUTH_EVENT_TTL_MS`）。
- **修复建议**：
  - 在 `CoverScraperSettingsActivity.kt:138` 改用 `collectAsStateWithLifecycle()`，确保仅在页面回到可见态时消费、弹提示并清除事件。

---

### 4.【中】`PlayStatusDialog` 在异步 `bind`/`unbind` 落库后旋转屏幕，`rememberSaveable` 二级弹窗状态脱节

- **位置**：
  - `app/src/main/java/com/tyranor/next/ui/game/PlayStatusDialog.kt:317-318, 529-566, 660-708, 1040-1069`
  - `app/src/main/AndroidManifest.xml:166`
- **根因与触发条件**：
  - `pickSource`（第 317 行）与 `bindSource`（第 318 行）使用 `rememberSaveable(game.uri)`。`MainActivity` 仅配置 `android:configChanges="uiMode"`，屏幕旋转会销毁并重建 Activity。
  - 若 `unbindSource` 或 `SubjectBindDialog.bind` 已在 `Dispatchers.IO` 完成落库并经 `notifyGamesChanged()` 触发库刷新，但在切回主线程重置 `pickSource = null` / `bindSource = null` 之前发生旋转，重建后的 `PlayStatusDialog` 会从父级收到已更新的 `game`（已解绑或已绑定），而 `rememberSaveable` 仍恢复旧的 `pickSource` / `bindSource`，第 660 / 688 行未核对目标来源当前绑定态，导致已解绑来源仍弹状态选择窗或已绑定来源仍弹搜索绑定窗。
- **修复建议**：
  - 在第 660 行与第 688 行渲染前（或通过 `LaunchedEffect(game, pickSource, bindSource)`）校验：若 `pickSource` 对应的当前绑定 ID 为空则立即归 `null`；若 `bindSource` 对应的当前绑定 ID 已非空则立即归 `null`。

---

### 5.【中】组合期/`LaunchedEffect` 主线程同步解密 Keystore 及 `SecureAuthStore` 单例全局锁竞争

- **位置**：
  - `app/src/main/java/com/tyranor/next/core/auth/SecureAuthStore.kt:82-103, 188-255`
  - `app/src/main/java/com/tyranor/next/core/auth/HikarinagiAuthStore.kt:111-200`
  - `app/src/main/java/com/tyranor/next/ui/cover/CoverScraperSettingsActivity.kt:147-154`
  - `app/src/main/java/com/tyranor/next/ui/cover/CredentialTokenDialog.kt:98-100`
  - `app/src/main/java/com/tyranor/next/ui/game/GameScreen.kt:845-871, 1313`
- **根因与说明**：
  - `CoverScraperSettingsScreen` 组合期同步调用 4 家 `getStatus()`（共 7 次 `decryptField` 与潜在的 Hikarinagi 旧版迁移密钥生成）；`GameActionsSheet` `LaunchedEffect` 在切入 `Dispatchers.IO` 前先在主线程执行最多 4 次 `getStatus()`；`CoverSourcePickerDialog` 与 `CredentialTokenDialog` 亦在组合期主线程解密。
  - 更重要的是 `SecureAuthStore.secretKey()` 与 `recoverKeyAndEncrypt()` 使用 `synchronized(SecureAuthStore)` 单例全局锁，后台任务进行 Keystore `load` / `deleteEntry` / `generateKey`（TEE IPC）期间可能阻塞主线程取状态。
  - 虽然代码注释已将主线程读取登记为观察注记（BK-01B），仍建议：**① 将 `GameActionsSheet` 4 处门控下沉到其已有的 `withContext(Dispatchers.IO)` 块内（零新增协程成本）；② `SecureAuthStore` 按 `keyAlias` 分段加锁（或缓存 `SecretKey` 并仅在失效时重建），消除后台密钥恢复对主线程的锁阻塞；③ 后续按规划将 `CoverScraperSettingsActivity` / `CredentialTokenDialog` 统一下沉 `produceState` IO 加载。**

---

### 6. 低严重度问题（Low）与细节说明

6. **【低-中 / UI 规范】危险条目图标缺错误色与窄屏截断细节**：
   - `PlayStatusDialog.kt:950-960`：`game_play_status_unbind` 仅设置 `titleColor = MaterialTheme.colorScheme.error`，未同步传 `leadingIconTint = MaterialTheme.colorScheme.error`（不符合 `AGENTS.md` 功能跳转条目规范第 1 节“危险条目用 `colorScheme.error` 上色”及与 `SettingsScreen.kt:528` 的一致性）。
   - `PlayStatusDialog.kt:1142-1152`：`SubjectBindDialog` 候选 `AppNavItem` 未设 `titleMaxLines`，长标题在 360dp 窄屏单行截断；且 `SubjectBindDialog` 底部无独立「搜索」按钮，仅依赖键盘 IME `Search`（对照 `CoverSearchDialog`）。
   - `NoPressFeedbackButtons.kt:75-76`：`NoRippleActionText` 新增 `maxLines = 1, overflow = TextOverflow.Ellipsis` 后，`GameShortcutCropDialog.kt:361` 等仍用 `Row`（而非 `FlowRow`）水平排布 3 个 `DialogTextButton` 的弹窗在窄屏/大字体/长译文下可能截断按钮文字。
7. **【低 / 逻辑】Hikarinagi 404 未区分网关 HTML 404 与 403 缓存脱节**：
   - `PlayStatusService.kt:394-420, 921`：`readHikarinagiStatus` 传入 `treatNotFoundAsNull = true`，且 `L921` 无条件将 HTTP 404 视为「未在 Hikarinagi 中标记」并清空 `last_error`/`permission_error`；若网关/CDN 返回非 JSON HTML 404 或站点返回 `GALGAME_NOT_FOUND`，不会像 NextMoe（`L999` 严格校验 `json != null && problemCode == "NOT_FOUND"`）那样提示网络/条目缺失错误。
   - `PlayStatusDialog.kt:329-379`：回读失败（尤其是 403 写入独立 `permission_error`、不改变 `needsReauth` 与 `sessionEpoch`）时未调用 `PlayStatusSummaryCache.invalidate(game.uri)`，导致关闭弹窗后抽屉副文本最长 5 分钟内仍命中旧成功缓存。
8. **【低 / 逻辑与健壮性】并发短 TTL、事件清理、URL 路径点段与孤儿封面文件**：
   - `SingleFlightRefresh.kt:34-68` & `SecureAuthStore.kt:280-296`：服务端返回 `1..60s` 极短 `expires_in` 时，若线程 C 在 Leader 放锁与前批等待者执行 `adoptWaiter` 之间抢先刷新换发新令牌 `T2`，`adoptSingleFlightAccessToken` 的 `it == leaderToken` 校验会误判返回 `null`。
   - `NextMoeAuthService.kt:150-215`：`startAuthorization` 发起新授权前未清零 `mutableAuthEvent` 中的遗留事件；`NextMoeCoverService.kt:109, 255`：`listCovers` 开启 `emptyOnNotFound = true` 时 `requestJson` 未检查 `result.json != null`，非 JSON 404 会表现为空封面列表而非网络错。
   - `HttpJson.kt:154-155`、`PlayStatusService.kt:399,427,508,536,565,608`、`NextMoeCoverService.kt:107`：`urlEncodePathSegment()` 未拒绝 `"."` / `".."` 纯点段路径；且 `NextMoeCoverService.kt:107` 重复写了 `.urlEncode().replace("+", "%20")` 而未直接复用 `urlEncodePathSegment()`。
   - `CoverScrapeTaskManager.kt:91-113`：并发换封面/删游导致 `persistScrapedCover` 返回 `null` 时，新下载落盘的 `scraped.coverUri` 孤儿文件未被回收。
   - `CoverScraperSettingsActivity.kt:863-865`：`verifyAndSaveVndbToken` 捕获非 `PlayStatusException` 时静默转 `UNKNOWN` 而未打 `Log.w`。
9. **【低 / 架构边界】跨域小耦合**：
   - `PlayStatusService.kt:339-390, 370, 458, 599`（`core/status` 承担 VNDB `/authinfo` 校验并直接访问 `core/cover/VndbCoverService.throttle`）与 `ui/cover/PlayStatusMessages.kt`（跨包供 `ui/game` 引用）均已在 `ARCHITECTURE.md:154` / `PlayStatusErrors.kt:7-10` 登记；`GameScreen.kt:149` 直接 import `core.net.optNonBlankString` 建议后续收口至 `core/cover`。
10. **【低 / 规范、三语言文案、重复代码与文档】**：
    - 未使用 import：`AppAlertDialog.kt:37`（`setValue`）。
    - `values-ja/strings.xml:570`（`cover_vndb_authorized_read_only_summary` 缺少「プレイ状況の」且用全角冒号 `：`）；`values/strings.xml:737`（`game_play_status_nextmoe_not_marked`「未在未萌NextMoe 中标记」空格不对称）；`values/strings.xml:713-714` 与 `values-ja/strings.xml:709-714` 中 `token` / `令牌` / `トークン` 跨弹窗复用略有不齐。
    - `PlayStatusDialog.kt:116-126` 四个 `*StatusOptionText` 函数重复，可合并为通用辅助函数；`AppNavItem.kt:61-90` KDoc `@param` 漏列 `summaryMaxLines`；`ARCHITECTURE.md:56` 表未列绑定字段、`CHANGELOG.md:45` 同段重复提及一次 `Migration(5,6)`。

---

## 六、单元测试质量核查与剩余覆盖缺口

本分支已具备高价值回归套件（共 60 个定向测试文件、452 个用例在 JVM 全通过），针对并发闸门、`sessionEpoch`、`SingleFlightRefresh`、`AuthStore` 单 editor 批量写、环回 Socket、HTTP 映射与缓存键覆盖扎实，但仍有以下质量隐患与缺口：

1. **假 SQLite 迁移测试（高关注测试质量问题）**：`GameLibraryDatabaseMigrationTest.kt:21-49` 通过 `Proxy.newProxyInstance(SupportSQLiteDatabase)` 仅录制 `execSQL` 字符串，未真正启动 SQLite 执行 DDL 与验证 `room_master_table` / `PRAGMA table_info`；且 `androidTest` 中亦无 Room 实库迁移用例。
2. **多份单测中 `SecureAuthStore.testCipherDelegate` 存在跨 Provider 键碰撞**：`PlayStatusServiceHttpTest.kt:52-60`、`PlayStatusWriteDecisionTest.kt:83-90`、`CoverScraperBindAndHikarinagiTest.kt:52-60`、`AuthStatusPersistenceTest.kt:41-49`、`AuthStoreBatchWriteTest.kt:42-50`、`NextMoeAuthServiceTest.kt:58-66` 均只以 `field`（如 `"access_token"`）为 key 缓存明文而忽略 `prefsName`/`keyAlias`。在 `PlayStatusServiceHttpTest.kt:311-348`（同测依次写入 NextMoe 与 Hikarinagi）中，导致后者覆盖了前者的 `"access_token"`（因 stub 未校验 `Authorization` header 而被掩盖）。
3. **两处浅验证用例**：`CoverScraperPolicyTest.kt:30-33`（未驱动 `CoverScraperService.scrapeLibraryCovers` 实际编排）与 `BangumiTokenVerificationTest.kt:31-41`（仅对比密封类构造属性）。
4. **未覆盖关键路径**：
   - `MainLibraryViewModel.replaceGame` 与 `CoverScrapeTaskManager.gameUpdates` 并发交错竞态、以及 `CoverScraperService.scrapeLibraryCovers` / `CoverScrapeTaskManager.start` 端到端编排；
   - `EngineScanner.mergeRescanResults` 与 `mergeScannedGame` 中 `RUNTIME_MODULE` / `builtInSystemNnn` 分支对四源绑定的保留；
   - `CoverScraperService.searchCandidatesForBinding(requireCover = false)`、各来源 `fetchBestCover` / `SteamCoverSource`、`BangumiAuthService.verifyPersonalToken`、`PlayStatusService` 的 VNDB/Bangumi HTTP 读写分支、以及 `playStatusDrawerSummary` / `*StatusLine` 格式化逻辑；
   - `NextMoeAuthService.startAuthorization` / `ensureUsernameLoaded`、Compose UI 弹窗交互与真机 OAuth/Room 迁移验收。
