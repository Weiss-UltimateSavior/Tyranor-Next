# `codex/bangumi-vndb-play-status` 分支审计报告（2026-11-15 · `cf56a52e`）

- **审计对象**：`codex/bangumi-vndb-play-status`（HEAD `cf56a52e`，基线 `origin/main` `709687a3`），共 93 个变更文件、`+16146 / -743` 行（提交范围 `c122d821..cf56a52e`，共 9 个提交）。
- **审计基准**：[AGENTS.md](../AGENTS.md)、[CONTEXT.md](../CONTEXT.md)、[ARCHITECTURE.md](../ARCHITECTURE.md)、[SPEC.md](../SPEC.md)。
- **审计方式**：按 `AGENTS.md` 强制要求，分派 5 个相互独立的审查子 Agent（代码逻辑、架构、代码健壮性、回归风险与测试覆盖、代码与 UI 规范）对当前 HEAD `cf56a52e` 重新逐文件实证审查，主 Agent 逐条回源核对关键代码行并执行本地静态门禁、定向单元测试与 Lint 增量核查。

> 说明：工作区既有 `docs/play-status-audit-20261114.md` 与 `docs/play-status-audit-20261115.md` 分别对应早期快照与 `79a4160e` 基线。此前 `79a4160e` 报告中列出的 8 项中严重度问题（封面来源 `optString` `"null"` 漏防、Keystore 永久失效无自愈、OAuth `detail` 原文透传 UI、Hikarinagi 省略 `id_token` 跳过 `nonce`、并发写放弃乐观更新后选择弹窗未关、`CoverScraperSettingsActivity` `needsReauth` 兜底文案误配与窄屏 `maxLines = 2` 截断、`SubjectBindDialog`/`CredentialTokenDialog` 交互规范以及 `docs/应用持久化数据清单.md` 漂移）在提交 `cf56a52e` 中均已修复；本报告基于 `cf56a52e` 完整现状重新独立审计并保留历史快照不覆盖。

---

## 一、已执行验证与工具输出

| 检查项 | 命令 | 结果 |
| --- | --- | --- |
| 三语言字符串与硬编码 CJK 门禁 | `python tools/check-hardcoded-ui-strings.py` | **通过**（`Hardcoded UI string check passed.`，zh/en/ja 均为 1157 个 key，0 缺漏/占位符错配） |
| 动效边界门禁 | `python tools/check-motion-boundaries.py` | **通过**（`Motion boundaries: 366 Kotlin files, 0 violations.`） |
| 本地门禁脚本单测 | `python tools/test-hardcoded-ui-strings.py` & `python tools/test_motion_boundaries.py` | **通过**（5 用例 + 11 用例全通过） |
| 本分支定向 JVM 单元测试 | `./gradlew.bat :app:testDebugUnitTest --rerun --tests "com.tyranor.next.core.auth.*" --tests "com.tyranor.next.core.cover.*" --tests "com.tyranor.next.core.status.*" --tests "com.tyranor.next.core.net.*" --tests "com.tyranor.next.core.backup.*" --tests "com.tyranor.next.core.game.scan.*" --tests "com.tyranor.next.core.game.storage.*" --tests "com.tyranor.next.ui.auth.*" --tests "com.tyranor.next.ui.cover.*" --tests "com.tyranor.next.ui.game.PlayStatus*" --no-daemon` | **通过**（清空旧测试 XML 后重跑统计：54 个测试类，425 个用例，0 失败，0 错误，0 跳过） |
| Android Lint 增量检查 | `./gradlew.bat :app:lintDebug --no-daemon` + `python tools/check-lint-diff.py --base origin/main app/build/reports/lint-results-debug.xml` | **变更源码 0 Error**（`Lint errors in reports: 1. Errors on changed sources: 0.`，唯一 Error 为本机 `local.properties:1` `[PropertyEscape]`；变更文件无新增 Error 或 `[StaticFieldLeak]` / `[ModifierParameter]`） |
| 未执行项 | 全量 `:app:testDebugUnitTest`（本轮未重跑，上轮基线存在 Windows 宿主环境既有失败）、`:engine` 检查（本分支未改动 `engine/`）、真机 Room v3→v6 迁移、真实浏览器 OAuth 回调与 360dp 窄屏真机渲染 | **未执行**（不得视同已验收） |

---

## 二、总体审计结论

- **架构与主链路结论**：**无阻断（Blocker / High）级架构或编译缺陷**。三层架构（`ui -> core -> engine` 与中性 `core/net`）、类型化 `core -> ui` 错误协议、`CancellationException` 传播、Room v3→v6 迁移与 schema 导出、4 套 `*_auth_secure.xml` 加密及云备份排除、OAuth PKCE/S256 与环回监听并发隔离均核验通过；`cf56a52e` 亦准确修复了上一轮 8 项中严重度问题。
- **剩余主要风险**：本轮独立审计新检出 **1 项中-高（Medium-High）问题**、**7 项中严重度（Medium）问题** 以及 **12 项低严重度（Low）与单测覆盖缺口**，集中在：
  1. `PlayStatusService.readVndbStatus` 绕过 `needsReauth` 门控并可能抹除 `SECURE_STORAGE_ERROR` 哨兵；
  2. `PlayStatusDialog` 与 `GameScreen` 读路径 `LaunchedEffect` 及 `PlayStatusSummaryCache` 仍以 `credentialEpoch` 而非 `sessionEpoch` 键控，后台静默刷新令牌会取消在途读取并清空全库缓存；
  3. `CredentialTokenDialog` 每次按键重组均在主线程同步触发 Keystore 解密；
  4. `PlayStatusDialog` 的 `unbindSource` / `SubjectBindDialog.bind` 协程取消后主库视图与摘要缓存脱节风险；
  5. `NextMoeAuthService.parseIdTokenUsernameRaw` 回退到 `"sub"` 抑制 `/userinfo` 昵称拉取，以及 `BangumiAuthStore.saveTokensLocked` 未清零 `auth_in_progress_at`、`NextMoeAuthService.authEvent` 跨页面滞留、360dp 下 `PlaySourceRow` 报错摘要两行截断等。

建议在合入 `main` 前优先修复第 1–8 项（中-高 / 中严重度）问题。

---

## 三、问题清单总览

| 编号 | 级别 | 维度 | 问题摘要 | 主要位置 |
| --- | --- | --- | --- | --- |
| 1 | 中-高 | 健壮性/逻辑 | `readVndbStatus` 绕过 `needsReauth` 门控，存储降级期间打开弹窗会清空 `SECURE_STORAGE_ERROR` 哨兵 | `PlayStatusService.kt:434-472, 717-722`、`VndbAuthStore.kt:70-82, 116-125` |
| 2 | 中 | 逻辑 | `PlayStatusDialog` / `GameScreen` 读路径与 `PlayStatusSummaryCache` 使用 `credentialEpoch` 而非 `sessionEpoch`，静默刷新会打断读取并震荡缓存 | `PlayStatusDialog.kt:295-302, 333-385`、`GameScreen.kt:820-837, 877-895` |
| 3 | 中 | 健壮性/UI | `CredentialTokenDialog` 每次输入重组都在主线程调用 `getStatus` 同步解密 Keystore | `CredentialTokenDialog.kt:170-173, 262-273`、`CoverScraperSettingsActivity.kt:822, 877` |
| 4 | 中 | 健壮性 | `unbindSource` / `SubjectBindDialog.bind` 未在 IO 内推进刷新/缓存失效，关闭弹窗或旋转取消 `scope` 后主库 UI 与缓存脱节 | `PlayStatusDialog.kt:535-571, 693-713, 1002-1066`、`GameLibraryFacade.kt:185-224` |
| 5 | 中 | 逻辑 | `NextMoeAuthService.parseIdTokenUsernameRaw` 回退 `"sub"` 导致跳过 `/userinfo` 昵称加载 | `NextMoeAuthService.kt:718-728, 847-881` |
| 6 | 中 | 健壮性 | `BangumiAuthStore.saveTokensLocked` 显式新登录未清零 `KEY_AUTH_IN_PROGRESS_AT` | `BangumiAuthStore.kt:310-325` |
| 7 | 中 | 健壮性 | `NextMoeAuthService.authEvent` 在离开设置页后长期滞留，下次进入弹出陈旧提示 | `NextMoeAuthService.kt:99-100, 119-136`、`CoverScraperSettingsActivity.kt:193-207` |
| 8 | 中 | UI 规范 | `PlaySourceRow` 使用 `AppNavItem`（`maxLines = 2`），360dp 下截断长权限报错及末尾绑定 ID | `PlayStatusDialog.kt:725-739`、`AppNavItem.kt:159-160` |
| 9–20 | 低 | 综合 | NextMoe 非 JSON 404 误报条目缺失、Bangumi/VNDB 候选空 ID、`vndbAuth` 双锁读取、`expires_in` 溢出/短 TTL、`urlEncode` 路径空格、`read<=0`、`shakeOnTrigger`/形状/标签/`strings.xml`/未使用 import 与注释、以及 4 类单测覆盖缺口 | 见第五、六节 |

---

## 四、重点问题详情（中-高 / 中严重度）

### 1.【中-高】`PlayStatusService.readVndbStatus` 绕过 `needsReauth` 门控并可能抹除 `SECURE_STORAGE_ERROR` 哨兵

- **位置**：
  - `app/src/main/java/com/tyranor/next/core/status/PlayStatusService.kt:434-472, 717-722`
  - `app/src/main/java/com/tyranor/next/core/auth/VndbAuthStore.kt:70-82, 116-125`
  - `app/src/main/java/com/tyranor/next/ui/game/PlayStatusDialog.kt:333-344`
- **根因与影响**：
  1. `VndbAuthStore.saveToken` 在新 token 加密失败时，故意保留原有 `KEY_TOKEN` / `KEY_USER_ID` 并仅写入 `KEY_LAST_ERROR = SecureAuthStore.SECURE_STORAGE_ERROR`（使设置页显示安全存储错误并进入 `needsReauth = true`）。
  2. 与 `GameScreen.kt:857-858` 抽屉摘要侧先检查 `!status.needsReauth` 不同，`PlayStatusService.readVndbStatus` 仅通过 `vndbAuth(context)` 判断旧 `token` 与 `userId` 非空即发起 `POST /ulist`，而 `PlayStatusDialog` 在 `LaunchedEffect` 中直接调用 `readVndbStatus`。
  3. 若设备处于安全存储降级期（或用户刚换填新 token 加密落盘失败），打开已绑定 VNDB 的 `PlayStatusDialog` 会用保留的旧 token 请求 `/ulist` 并成功，随即在 `PlayStatusService.kt:471` 执行 `VndbAuthStore.markLastErrorIfTokenMatches(context, auth.token, "")` 将 `SECURE_STORAGE_ERROR` 清空，导致设置页错误恢复为「已授权」；若旧 `last_error` 为 `TOKEN_INVALID` / `PERMISSION`，打开弹窗也会固定多发一次注定 401/403 的请求。
- **建议**：在 `readVndbStatus` 校验存储哨兵（至少当 `lastError == SecureAuthStore.SECURE_STORAGE_ERROR` 时跳过/拒绝网络读并禁止第 471 行静默清错）；对 `TOKEN_INVALID` 也可视需求在 `PlayStatusDialog` 或 `readVndbStatus` 同步门控。

---

### 2.【中】`PlayStatusDialog` / `GameScreen` 读路径与 `PlayStatusSummaryCache` 使用 `credentialEpoch` 而非 `sessionEpoch`

- **位置**：
  - `app/src/main/java/com/tyranor/next/ui/game/PlayStatusDialog.kt:295-302, 333-385`
  - `app/src/main/java/com/tyranor/next/ui/game/GameScreen.kt:820-837, 877-895`
- **根因与影响**：
  - `HikarinagiAuthStore.credentialEpoch` 与 `NextMoeAuthStore.credentialEpoch` 在**每次**令牌保存时自增（含同账号后台静默 `refresh_token` 轮转），而 `sessionEpoch` 仅在显式登录/登出时变化。
  - `PlayStatusDialog` 的写入决策（`evaluatePlayStatusWriteDecision`）和结果状态 `remember(...)`（`L305-315`）已改用 `sessionEpoch`，但读路径 `LaunchedEffect`（`PlayStatusDialog.kt:333-385`）以及 `GameScreen.kt:820-837, 877-895` 的抽屉副文本 `LaunchedEffect` 与 `PlayStatusSummaryCache.key(...)` 仍以 `credentialEpoch`（`*AuthEpoch`）作为 key。
  - 当 Hikarinagi/NextMoe access token 临期时，打开弹窗或抽屉触发的首个 `read*Status` 在刷新后会立即自增 `credentialEpoch`，导致 Compose **取消正在进行的 `LaunchedEffect` 并重跑一次**（触发节流延迟和重复 `GET`；在多来源绑定的 `GameScreen` 中甚至会中断后重跑前面已查询的 VNDB 请求），同时 `NextMoeCoverService` 的后台封面刷新也会使全库所有游戏的 `PlayStatusSummaryCache` 命中失效。
- **建议**：`PlayStatusDialog` 读 `LaunchedEffect` 与 `GameScreen` `PlayStatusSummaryCache` 改以 `sessionEpoch`（及登录/错误恢复所需状态，如 `!needsReauth`）键控，隔离后台静默刷新引发的无效重读与缓存震荡。

---

### 3.【中】`CredentialTokenDialog` 每次按键重组均在主线程同步执行 Keystore 解密

- **位置**：
  - `app/src/main/java/com/tyranor/next/ui/cover/CredentialTokenDialog.kt:170-173, 262-273`
  - `app/src/main/java/com/tyranor/next/ui/cover/CoverScraperSettingsActivity.kt:822, 877`
  - `app/src/main/java/com/tyranor/next/core/auth/VndbAuthStore.kt:70-82`、`BangumiAuthStore.kt`
- **根因与影响**：
  - `CredentialTokenDialog` 在 `dismissButton` 插槽直接执行 `if (hasStoredCredential(context))`。`VndbTokenDialog` 与 `BangumiTokenDialog` 分别传入 `{ VndbAuthStore.getStatus(it).authorized }` 与 `{ BangumiAuthStore.getStatus(it).authorized }`，内部每次均获取 `storeLock` 并调用 `SecureAuthStore.decryptField(...)`（AndroidKeyStore Binder IPC + AES/GCM 解密，Bangumi 还会解密 access 与 refresh 两个字段）。
  - 用户在 `AppSearchField` 每输入/删除一个字符（`token = it`）或切换密码可见性时都会触发弹窗重组并在主线程重复解密 Keystore，存在 UI 掉帧与 StrictMode 风险。
- **建议**：在弹窗组合入口结合 `sessionEpoch`/`credentialEpoch` 以 `remember` 或异步加载快照一次 `hasStoredCredential`，避免在输入重组热路径重复解密 Keystore。

---

### 4.【中】`PlayStatusDialog` 的 `unbindSource` / `SubjectBindDialog.bind` 协程取消窗口状态脱节（含旧快照与旋转丢失）

- **位置**：
  - `app/src/main/java/com/tyranor/next/ui/game/PlayStatusDialog.kt:535-571, 693-713, 1002-1008, 1039-1066`
  - `app/src/main/java/com/tyranor/next/core/game/storage/GameLibraryFacade.kt:185-224`
  - `app/src/main/java/com/tyranor/next/ui/main/MainLibraryViewModel.kt:98-102`
- **根因与影响**：
  1. `GameLibraryFacade.updateGameCoverMetadata` 会更新内存缓存与持久化队列，但**不递增** `libraryRevision`（`MainLibraryViewModel` 通过 `libraryRevision.drop(1)` 自动重载）。因此 `unbindSource` 和 `SubjectBindDialog.bind` 完全依赖 `withContext(Dispatchers.IO)` 返回后在 UI `scope` 内调用 `onGameUpdated(updated)`；且 `unbindSource` 把 `PlayStatusSummaryCache.invalidate(game.uri)` 也放在 `withContext(Dispatchers.IO)` 之后（`L560-561`，对比 `writeStatus` 在 `L453` 的 IO 块内失效）。若 IO 写入完成前用户关闭弹窗或旋转屏幕取消了 `scope`，数据库已改但 `MainLibraryViewModel`（及解绑时的 `PlayStatusSummaryCache`）会停留旧状态。
  2. `unbindSource` 第 541 行传入形参 `game` 而非 `latestGame`（`L294` 的 `rememberUpdatedState(game)`）。
  3. `SubjectBindDialog`（`L1003-1006`）使用 `remember` 保存 `candidates` / `noMatch` / `errorCode`，屏幕旋转后仅 `keyword` 保留而搜索结果清空。
- **建议**：将 `PlayStatusSummaryCache.invalidate` 移入 `PlayStatusService.unbindSubject` / IO 块内；或使绑定元数据变更同步通知 `MainLibraryViewModel`/递增修订（或使用 `NonCancellable` 配合父级回调）；`unbindSource` 使用 `latestGame`；搜索结果考虑跨旋转保留策略。

---

### 5.【中】`NextMoeAuthService.parseIdTokenUsernameRaw` 回退到 `"sub"` 抑制 `/userinfo` 昵称加载

- **位置**：`app/src/main/java/com/tyranor/next/core/auth/NextMoeAuthService.kt:718-728, 847-881`
- **根因与影响**：
  - `parseIdTokenUsernameRaw`（`L872-881`）解码 `id_token` 载荷后直接复用 `parseUserinfoName(JSONObject(payload))`（候选键：`name`、`preferred_username`、`nickname`、`username`、`sub`）。
  - 在 `completeOAuthTokenExchange`（`L718-728`）中，只要 `usernameHint` 非空就直接持久化 `KEY_USERNAME` 并**跳过** `fetchAndSaveUsername(USERINFO_URL)`；后续 `ensureUsernameLoaded`（`L849`）见到 `status.username.isNotBlank()` 也会直接返回。
  - 在常见 OIDC 实践中，`id_token` 必定携带不可读的账号 ID `"sub"` 而未必携带展示昵称（昵称由 `/userinfo` 下发）。当前实现会导致未在 `id_token` 附带 `name`/`username` 时把原始 `sub` 当作用户名并永久跳过 `/userinfo` 拉取。
- **建议**：`parseIdTokenUsernameRaw` 仅读取语义化昵称声明（排除 `"sub"`），将 `"sub"` 兜底仅保留在 `fetchAndSaveUsername` 查询 `/userinfo` 之后。

---

### 6.【中】`BangumiAuthStore.saveTokensLocked` 显式新登录未清零 `KEY_AUTH_IN_PROGRESS_AT`

- **位置**：
  - `app/src/main/java/com/tyranor/next/core/auth/BangumiAuthStore.kt:310-325`（对比 `NextMoeAuthStore.kt:340-344`）
  - `app/src/main/java/com/tyranor/next/ui/cover/CoverScraperSettingsActivity.kt:154-158`
- **根因与影响**：
  - `NextMoeAuthStore.saveTokensLocked` 在 `clearIdentity = true` 时同步写入 `.putLong(KEY_AUTH_IN_PROGRESS_AT, 0L)`，而 `BangumiAuthStore.saveTokensLocked` 仅移除了 `KEY_USER_ID` 与 `KEY_USERNAME`。若用户曾发起 Bangumi OAuth 但未完成（留下 `auth_in_progress_at`），随后改用个人令牌登录成功；之后若因临时存储读取失败呈现 `authorized = false && !needsReauth`，残留超过 15 分钟的 `authInProgressAtMillis` 会使 `bangumiAuthStale` 误判。
- **建议**：在 `BangumiAuthStore.saveTokensLocked` 的 `if (clearIdentity)` 分支中同步清零 `KEY_AUTH_IN_PROGRESS_AT`（或 `remove`）。

---

### 7.【中】`NextMoeAuthService.authEvent` 在离开设置页后长期滞留，下次进入弹出陈旧提示

- **位置**：
  - `app/src/main/java/com/tyranor/next/core/auth/NextMoeAuthService.kt:99-100, 119-136`
  - `app/src/main/java/com/tyranor/next/ui/cover/CoverScraperSettingsActivity.kt:193-207`
- **根因与影响**：
  - 环回等待线程超时（300s）或取消后写入进程级单例 `mutableAuthEvent: MutableStateFlow<NextMoeAuthEvent?>`。若用户拉起外部浏览器后退出 `CoverScraperSettingsActivity`，事件无订阅者消费会永久保留在 `StateFlow` 中；直到用户很久之后再次进入封面设置页时，才会弹出一次过时的完成/失败/超时提示。
- **建议**：为 `NextMoeAuthEvent` 附带时间戳或由后台直接通过 `applicationContext` 触发 `AppNotice`/限制事件有效期（或退出授权流超期自动忽略）。

---

### 8.【中】`PlaySourceRow` 在 360dp 窄屏下将长报错摘要与绑定 ID 截断在 2 行内

- **位置**：
  - `app/src/main/java/com/tyranor/next/ui/game/PlayStatusDialog.kt:725-739`
  - `app/src/main/java/com/tyranor/next/ui/common/AppNavItem.kt:159-160`
- **根因与影响**：
  - `PlaySourceRow` 通过 `AppNavItem` 的 `summary` 展示 `statusLine + " · " + boundId`，而 `AppNavItem` 对 `summary` 固定 `maxLines = 2, overflow = TextOverflow.Ellipsis`。
  - 在 360dp 宽手机的弹窗内（扣除弹窗外边距、内边距、左侧图标及右侧箭头后文本区约 204dp），较长的权限报错（如 `play_error_vndb_token_permission` 中文 66 字 / 英文 138 字符、`play_error_bangumi_token_permission` 中文 39 字）会在第 2 行截断，导致后面的权限勾选指引和 `· boundId` 被省略。
- **建议**：在 `PlaySourceRow` 中对状态错误提示取消 2 行限制（如在 `AppNavItem` 增加可选 `summaryMaxLines` 参数，或将报错引导放在可完整换行的区域）。

---

## 五、低严重度问题（Low）与规范细节

### 代码逻辑与健壮性
9. **NextMoe 非 JSON 404 误报条目缺失**（`PlayStatusService.kt:194-203, 970-978`）：`isNextMoeNotFoundUnmarked` 已拦截 `json == null` 不视作「未标记」，但随后 `nextMoeNonSuccessToError(404, null)` 将任何 404 映射到 `NEXTMOE_SUBJECT_NOT_FOUND`。当 CDN/反代返回 HTML 404 时会误导用户以为条目被删/合并而执行解绑，应先判 `result.json == null -> NEXTMOE_NETWORK`；此外 `isNextMoeNotFoundUnmarked` 建议显式要求 `problemCode == "NOT_FOUND"`。
10. **`BangumiCoverService` 与 `VndbCoverService` 未过滤空 `id` 候选**（`BangumiCoverService.kt:66-80`、`VndbCoverService.kt:164`）：不同于 `HikarinagiCoverSource` 与 `NextMoeCoverService.kt:158` 丢弃 `id.isBlank()` 条目，前两者允许 `id = ""` 进入 `SubjectBindDialog`；若上游返回两条缺 `id` 条目，会导致 `LazyColumn`（`PlayStatusDialog.kt:1138`，`key = { it.source + ":" + it.id }`）因重复 key 崩溃。
11. **`PlayStatusService.vndbAuth` 跨两次 `storeLock` 读取 `token` 与 `userId`**（`PlayStatusService.kt:717-722`）：分别调用 `getToken` 与 `getUserId`；若读取瞬间并发换号/清号可能拼出新旧混合凭据发起一次 `/ulist`（写错标志已有 `markLastErrorIfTokenMatches` 保护）。建议在 `VndbAuthStore` 提供单锁快照读取。
12. **OAuth `expires_in` 上界缺失与 `SingleFlightRefresh` 短有效期冲突**（`BangumiAuthService.kt:434-435`、`HikarinagiAuthService.kt:409-410`、`NextMoeAuthService.kt:838-839`、`SingleFlightRefresh.kt:26-45`）：`expiresIn * 1000L` 未限制上界，极端超大值可 `Long` 溢出为负数；此外若服务端返回 `expires_in` 处于 `1..60` 秒，主刷新者直接返回新 token，而同批等待的 follower 走 `recheck()`（`REFRESH_SKEW_MS = 60_000L`）会判定已临期并返回 `null`。
13. **极端输入与流边界小问题**：
    - `PlayStatusService.kt:385, 413, 493, 521, 550, 592` 直接将 `id.urlEncode()`（`URLEncoder.encode`，空格编为 `+`）用于 URL 路径段；
    - `CoverSupport.kt:78` `if (read <= 0) break` 若底层流返回 `0` 会提前截断图片读取；
    - `GameScreen.kt:1762-1774` `decodeCoverSearchCandidate` 的 6 个非空字符串字段未做 `!json.isNull(...)` 防护（内部生成安全，可统一用 `optNonBlankString`）；
    - `VndbCoverService.kt:80-83` 非挂起函数 `saveCustomCover` 的 `catch (_: Exception)` 未重抛 `CancellationException`（当前为阻塞文件 IO 风险极低）。

### UI / 动效 / 形状 / 文案规范与坏味道
14. **`CredentialTokenDialog` 缺 `shakeOnTrigger` 与非豁免 `CircleShape`**（`CredentialTokenDialog.kt:110-115, 170-192`）：空输入提交与校验失败未对 `AppSearchField` 挂载 `shakeOnTrigger`（与 `SubjectBindDialog` / `CoverSearchDialog` 不一致）；密码显隐 `Box(44.dp)` 使用 `.clip(CircleShape)` 未列入 `AGENTS.md` 形状豁免清单（建议改用 `AppComponentShape`）。
15. **`BlurCrossfade` 缺少 `label` 与 `SubjectBindContentState.Error` 2 行截断**：`CoverScraperSettingsActivity.kt:528` 与 `PlayStatusDialog.kt:909` 的 `BlurCrossfade` 省略 `label` 参数；`PlayStatusDialog.kt:1124-1125` 绑定搜索报错限制 `maxLines = 2` 在 360dp 大字体下易截断错误详情。
16. **3 处确认未使用的 import**：`AppAlertDialog.kt:10`（`background`）、`CredentialTokenDialog.kt:13`（`padding`）、`GameScreen.kt:166`（`AppDialogMaxWidth`）。
17. **命名一致性、KDoc 与轻微重复**：
    - `CoverScraperSettingsActivity.kt:550-554, 683-693`：`COVER_SOURCE_HIKARINAGI` 复用 `nextMoeAuthRowActions` / `NextMoeAuthRowActions`，建议中性命名；同文件多处内联全限定名 `com.tyranor.next.core.auth.*`；
    - `PlayStatusDialog.kt:730` 在 `@Composable PlaySourceRow` 内用 `AppLocaleController.wrap(context).getString(...)` 而非 `stringResource(...)`；
    - 过时文档/注释：`SecureAuthStore.kt:107, 135` 仍写「三 store」；`GameRecordCodec.kt` `serializeGame` 注释漏列 `nextmoeId`；`AGENTS.md:497-508` 的 `AppNavItem` 现有调用点表尚未列入 `PlayStatusDialog.kt` 新增调用点；
    - `PlayStatusService.kt:735-983` 三来源请求包装存在样板重复。
18. **三语言 `strings.xml` 文案细节**：
    - `app/src/main/res/values/strings.xml:710` 混用 `令牌` 与 `token` 且英文 UI 标签前后缺空格；`:740, 747` `选择%1$s状态` / `绑定%1$s条目` 缺空格；
    - `app/src/main/res/values-ja/strings.xml:572-573` 含简体汉字 `鲲`（建议用 `Kungal Forum` 等日文常用表记）；`:746, 775, 788` 混用 `紐付け` 与其它键的 `バインド`；
    - `app/src/main/res/values-en/strings.xml:570`（`cover_vndb_authorized_read_only_summary`）缺少 `"play status"` 语境且标点与 `:569` 不统一。

---

## 六、单元测试审查与剩余覆盖缺口

本分支在 `cf56a52e` 补齐了大量高质量单元测试（`AuthStoreBatchWriteTest`、`SingleFlightRefreshTest`、`BangumiTokenRefreshTest`、`HikarinagiTokenRefreshTest`、`NextMoeAuthServiceTest` 含真实环回 Socket、`CoverScrapeMergeTest`、`CoverScraperBindAndHikarinagiTest`、`NextMoeCoverServiceTest`、`PlayStatusServiceHttpTest`、`PlayStatusWriteDecisionTest`、`PlayStatusSummaryCacheTest`、`CredentialTokenCleanupTest`、`BackupRulesTest` 等），针对并发闸门、会话代次、批量落盘原子性与 HTTP 状态映射覆盖充分。仍存在以下有价值的覆盖缺口：

1. **无真实 SQLite 执行的 Room 迁移与 DAO 往返测试**：`GameLibraryDatabaseMigrationTest.kt` 使用 `Proxy` 模拟 `SupportSQLiteDatabase` 正则匹配 `execSQL` 字符串，未真正执行 SQLite DDL、`room_master_table` 校验或 `GameLibraryDao.updateCover` 7 列读写以及 `LegacyGameLibraryImportPlan` 16 字段导入。
2. **新绑定 ID 在既有共享主链路上的集成测试缺口**：
   - `EngineScanner.mergeScannedGame`（`EngineScanner.kt:264-299`）未测重扫保留 `bangumiId`/`hikarinagiId`/`nextmoeId`；
   - `MainLibraryViewModel.mergeChangedGameFields`（`MainLibraryViewModel.kt:409-418`）未测 4 个新 ID 字段的并发保留；
   - `ScanGameIntents` 新增 Extra（`ScanGame.kt:42-84`）与 `GameRecordCodecTest` 对 `nextmoeId` 分隔符清洗未覆盖；
   - `GameScreen.cleanupDeletedGame`（`GameScreen.kt:366`）未验证删游时调用 `PlayStatusSummaryCache.invalidate(target.uri)`。
3. **恒真/纯布尔单测**：`CoverScraperPolicyTest.xs4WithoutCoverStillSearchesOnlineFirst`（`L29-33`）仅断言两个纯布尔函数组合，未驱动 `CoverScraperService.scrapeLibraryCovers` 主流程。
4. **OAuth 发起/回调与 UI 行为未覆盖路径**：`BangumiAuthService.handleAuthorizationResponse`、`NextMoeAuthService.startAuthorization`（`NoBrowser` / `LaunchFailed` 清理）、`NextMoeAuthService.ensureUsernameLoaded`、`AuthFormHttp.postAuthForm` 实网连接逻辑，以及 `PlayStatusDialog` / `CoverSearchCandidatesSaver` 的 Compose 组合行为测试。
