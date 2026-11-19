# `codex/bangumi-vndb-play-status` 分支审计报告

- **审计对象**：`codex/bangumi-vndb-play-status`（对比公共祖先 `a4f4280c..HEAD`，共 84 个变更文件）
- **审计基准**：`AGENTS.md`、`CONTEXT.md`、`SPEC.md`、`ARCHITECTURE.md`
- **审计方式**：5 个独立子 Agent（代码逻辑、架构、代码健壮性、回归风险、代码与 UI 规范）并行审查 + 本地自动化脚本与单元测试验证

---

## 一、审计结论概览

整体架构边界、三语言一致性、Room v3→v6 迁移、OAuth PKCE/S256 与安全排除规则总体质量较高；**未发现阻塞编译或直接数据损坏的阻断（Blocker）级缺陷，但发现 4 项高严重度、8 项中严重度及若干低严重度与单测覆盖问题**，建议在合入前修复高/中严重度问题。

---

## 二、已核实问题清单（按严重度排序）

### 高严重度（High）

#### 1. `CredentialTokenDialog` 校验期间遗漏 `dismissEnabled = !verifying`，导致按返回键或点遮罩后弹窗滑出隐身僵死
- **位置**：`app/src/main/java/com/tyranor/next/ui/cover/CredentialTokenDialog.kt:152-155`（对照 `app/src/main/java/com/tyranor/next/ui/common/AppAlertDialog.kt:200-237`、`PlayStatusDialog.kt:839,969`）
- **原因与影响**：代码注释约定 `verifying == true` 时应禁止遮罩点击与系统返回键关闭，但只传了 `onDismissRequest = { if (!verifying) onDismiss() }`，未传 `dismissEnabled = !verifying`（默认 `true`）。校验期间点击遮罩/按返回键会触发 `AppAlertDialog.dismiss()` 放行触摸并将卡片完全滑出屏幕，随后 `onDismissRequest` 拦截了关闭动作且无恢复逻辑，弹窗处于“仍在组合中但隐身卡死”的状态。
- **建议**：补充 `dismissEnabled = !verifying`。

#### 2. `BangumiAuthService` 与 `HikarinagiAuthService` 快路径 `validStoredAccessToken` 未校验 `needsReauth`，导致 401/加密失败后的刷新短路失效
- **位置**：
  - `app/src/main/java/com/tyranor/next/core/auth/BangumiAuthService.kt:297-309`
  - `app/src/main/java/com/tyranor/next/core/auth/HikarinagiAuthService.kt:287-299`
  - 对照已正确实现：`app/src/main/java/com/tyranor/next/core/auth/NextMoeAuthService.kt:658-664`
- **原因与影响**：`getValidAccessTokenResult` 先走 `validStoredAccessToken` 快路径，未先判断 `needsReauth`。当未过期令牌（或 `expiresAt == 0L` 的 Bangumi 个人令牌）被服务端 401 吊销或因 Keystore 加密失败置位 `needsReauth` 后，后续请求仍会在快路径直接拿到已失效旧令牌并重放请求，绕过了 `refreshAccessToken` 中的短路保护；而 `BangumiTokenRefreshTest` / `HikarinagiTokenRefreshTest` 仅测试了过期令牌场景，掩盖了该问题。
- **建议**：在两者的 `validStoredAccessToken` 开头检查 `needsReauth`（优先经单锁快照读取），并补齐「未过期/`expiresAt == 0L` + `needsReauth`」单测。

#### 3. `NextMoeAuthService` 浏览器拉起失败时与环回监听线程发生状态竞态，导致重复报错与残留失败事件
- **位置**：`app/src/main/java/com/tyranor/next/core/auth/NextMoeAuthService.kt:195-208, 222-233, 278-285`
- **原因与影响**：`listenerThread.start()` 在 `launchBrowser` 之前启动。当设备无浏览器或拉起异常时，catch 块同步返回 `NO_BROWSER` / `LAUNCH_FAILED` 并调用 `abortLoopbackFlow(flowId, serverSocket)`，但该方法只关闭 `serverSocket` **未作废 `activeFlowId`**。`serverSocket.close()` 立即唤醒 `listenerThread` 抛 `SocketException`，而 `isCurrentFlow(flowId)` 仍返回 `true`，导致后台线程紧接着再发射一次 `authEvent(FAILED)`，造成双提示或残留待消费错误事件。
- **建议**：在 `abortLoopbackFlow` 内当 `activeFlowId == flowId` 时推进/递增 `activeFlowId`（或将 `listenerThread.start()` 延后至 `launchBrowser` 成功后，失败直接关闭 socket）。

#### 4. `AndroidManifest.xml` 中 `LauncherDefault` / `LauncherClassic` 写死 `android:label`，使 `TYRANOR_APP_LABEL` 对桌面启动器失效
- **位置**：`app/src/main/AndroidManifest.xml:42, 177, 190`；`app/build.gradle.kts:125-127, 346`
- **原因与影响**：`MainActivity` 不再声明 `MAIN/LAUNCHER`，桌面入口由 `LauncherDefault` 与 `LauncherClassic` 两个 `<activity-alias>` 承载，两者仍写死 `android:label="@string/app_name"`，会覆盖 `<application android:label="${launcherLabel}">`，导致注入 `TYRANOR_APP_LABEL` 时桌面名称不变。
- **建议**：两处 `<activity-alias>` 的 `android:label` 同步改为 `${launcherLabel}`。

---

### 中严重度（Medium）

#### 5. `BangumiAuthService` / `HikarinagiAuthService` 未防 JSON `null`（`optString` 变成 `"null"` 覆写有效 `refresh_token`）且在 `SingleFlightRefresh` 闸门外读快照
- **位置**：
  - `optString` 问题：`BangumiAuthService.kt:436-440`、`HikarinagiAuthService.kt:411-415`、`PlayStatusService.kt:368, 374, 377`、`BangumiCoverService.kt:65-67, 73`、`VndbCoverService.kt:138, 162-164`（对照 `NextMoeAuthService.kt:781-782, 849-850`）
  - 闸门外读快照：`BangumiAuthService.kt:314-331`、`HikarinagiAuthService.kt:303-323`（对照 `NextMoeAuthService.kt:668-690`）
- **影响**：服务端返回 `"refresh_token": null` 时 `optString` 返回字面量 `"null"` 并绕过 `isNotBlank()` 覆写既有刷新令牌；闸门外读刷新快照与 `needsReauth` 在并发临界窗口可能用旧凭证发起冗余/注定失败的刷新。
- **建议**：在 `core/net/HttpJson.kt` 抽出 `optNonBlankString` 统一替换；将 Bangumi / Hikarinagi 的 `readRefreshSnapshot` 与 `needsReauth` 复检移入 `refreshGate.run` 的 `task` 内。

#### 6. `BangumiAuthStore`、`HikarinagiAuthStore`、`VndbAuthStore` 的 `getStatus()` 读取未加 `storeLock`
- **位置**：`BangumiAuthStore.kt:88-115, 172-179`、`HikarinagiAuthStore.kt:173-190, 259-276`、`VndbAuthStore.kt:69-81`（对照 `NextMoeAuthStore.kt:87-124`）
- **影响**：跨多字段读取状态时未持 `storeLock`，与并发写入（换号、刷新、清错）交叉可能产生新旧字段混杂的撕裂状态快照。

#### 7. `BangumiOAuthCallbackActivity` 与 `HikarinagiOAuthCallbackActivity` 在配置变更或用户返回时丢弃授权提示
- **位置**：`app/src/main/java/com/tyranor/next/ui/auth/BangumiOAuthCallbackActivity.kt:13-36`、`HikarinagiOAuthCallbackActivity.kt:13-36`
- **原因**：`performTokenRequest` 网络换码回调里判断 `if (!isFinishing && !isDestroyed)` 后才以 Activity context 调用 `AppNotice.makeText(this, ...).show()`。换码期间发生配置变更（新实例立即 `finish()`、旧实例 `isDestroyed == true`）或用户提前按返回键时，登录结果虽已落盘但提示被吞掉。
- **建议**：使用 `applicationContext`（或同 `NextMoeAuthService.authEvent` 一样经进程级事件投递通知）。

#### 8. `Bangumi` 与 `Hikarinagi` 的 `AuthService` 持有 `AuthorizationService` 静态引用（Android Lint `[StaticFieldLeak]`）
- **位置**：`BangumiAuthService.kt:75`、`HikarinagiAuthService.kt:59`
- **原因**：单例 `object` 上缓存 `private var authorizationService: AuthorizationService? = null`（内部持有传入的 `Context`），由 `prewarm` 触发。应确保仅用 `context.applicationContext` 构造或改为局部按需创建。

#### 9. 封面来源设置页 Bangumi 行在窄屏下 4 个按钮挤压/折行
- **位置**：`app/src/main/java/com/tyranor/next/ui/cover/CoverScraperSettingsActivity.kt:543-586`
- **原因**：未授权时 `COVER_SOURCE_BANGUMI` 在不换行的 `Row` 内同时放置「上移」「下移」「授权」「令牌」4 个 `NoRippleTextButton`（单按钮水平内边距 24dp），在 360dp 宽手机（尤其英文/日文）会超出可用宽度导致文字折行或挤压。
- **建议**：改用 `FlowRow` 或收紧操作排布。

#### 10. 内容切换与状态变化违反 `AGENTS.md`「内容永不瞬间替换」规范
- **位置**：
  - `CredentialTokenDialog.kt:194-198, 209-228`（`verifying` 转圈、`statusText`、可见性图标 `tint`）
  - `PlayStatusDialog.kt:990-1032`（`SubjectBindDialog` 加载/错误/空态/列表切换）
  - `CoverScraperSettingsActivity.kt:570-596`（`CoverSourceRow` 中 Bangumi / VNDB 按钮切换未使用 `BlurCrossfade`，与同组件 Hikarinagi / NextMoe 不一致）
  - `AppNavItem.kt:164-191`（新增 `trailingText` 瞬间显隐与替换，未沿用 `summary` 的 `ExpandVisibility` / `BlurCrossfade` 模式）

#### 11. `VndbAuthStore` 与 UI 存在魔法字符串哨兵跨层耦合
- **位置**：`VndbAuthStore.kt:54`（复制私有常量 `SECURE_STORAGE_ERROR = "secure storage unavailable"`）、`CoverScraperSettingsActivity.kt:105, 693-699`（UI 复制同一字面量并硬编码比较）
- **建议**：统一复用 `SecureAuthStore.SECURE_STORAGE_ERROR` 并在 `VndbAuthStatus` 提供类型化 `lastErrorKind` 供 UI 分支，消除跨层字符串契约。

#### 12. `ARCHITECTURE.md`（及部分 `docs/`）与当前实现存在架构漂移
- **位置**：
  - `ARCHITECTURE.md:52, 68, 154, 171, 175`：仍写 `schema v3`、遗漏 NextMoe 游玩状态/绑定/环回 OAuth 与 `nextmoe_auth_secure`，且仍称 Hikarinagi 授权接口携带本地化文本。
  - `docs/应用持久化数据清单.md:274-275, 295-300`：附录树与第 9 节风险点遗漏 `nextmoe_auth_secure.xml`（及 Bangumi/VNDB 对应条）。
  - `CHANGELOG.md:41`：同一条目内前段写 `schema v6`、末尾仍留有旧的 `schema v5` 表述。

---

### 低严重度（Low）与单测覆盖建议

- **`PlayStatusSummaryCache` 缓存键分隔符脆弱**（`PlayStatusDialog.kt:140-158, 196, 208`）：以 `"|"` 拼接再用 `substringBefore('|')` 还原 `uri`，文件名含 `|` 时会导致 `invalidate(uri)` 失效；建议改用 `data class CacheKey(...)`。
- **NextMoe 初始回读未完成前允许点击选态**（`PlayStatusDialog.kt:393-397, 596-605`）：在 `nextmoeResult == null`（初始 GET 加载中）点选非 `wish` 状态时 `preservedNextMoeCompletion` 为 `null`，PUT 不带 `completion` 字段可能清掉网页端已有完成度。
- **`readVndbStatus` 缺少空 ID 防御**（`PlayStatusService.kt:436-443`）：建议对齐其他源做 `trim()` + `require(id.isNotEmpty())`。
- **日语翻译用词**（`app/src/main/res/values-ja/strings.xml:775`）：`play_error_bangumi_token_permission` 中的「収蔵」为汉语直译，建议改为日语常用「コレクション」。
- **零星过期文档/注释/未使用 import**：`AGENTS.md:407-408`（Token 弹窗文件路径已拆入 `CredentialTokenDialog.kt`）、`NextMoeCoverService.kt:5` 未使用 import `NextMoeAuthError`、`PlayStatusService.kt:285,322-326` 与 `PlayStatusDialog.kt:246,892` 及 `GameLibraryDatabase.kt:11-24` 过时 KDoc。
- **单测覆盖缺口**：
  1. `BangumiTokenRefreshTest` 与 `HikarinagiTokenRefreshTest` 缺少「未过期令牌/`expiresAt == 0L` + `needsReauth = true` 短路」及「刷新返回 `"refresh_token": null`」用例；
  2. `PlayStatusWriteDecisionTest` 缺少 `writeNextMoeStatus` 的切号拦截用例，缺少 `finishNextMoeStatusRead` 与 NextMoe 404 `NOT_FOUND` vs `ENTITY_MERGED/DELETED` 单测；
  3. `AuthStatusLastErrorKindTest` / `AuthStoreBatchWriteTest` 尚未纳入 `NextMoeAuthStore`；
  4. `NextMoeCoverServiceTest` 未覆盖 `searchCandidates`（`refs=vndb:` vs 模糊搜索清空 `vndbId`）与 `listCovers`（`portrait_pinned` 排序）。

---

## 三、已核验通过的关键规范与功能

- **三层架构与错误协议**：`core/` 无 Compose / UI 依赖，`core/net` 无任何业务域依赖；`BangumiAuthService`、`HikarinagiAuthService`（已迁移移除 `R.string`）、`NextMoeAuthService`、`CoverScraperService`、`PlayStatusService` 均遵守类型化错误码协议，所有协程挂起点均正确重抛 `CancellationException`。
- **OAuth 安全与持久化**：NextMoe `127.0.0.1:0` 环回绑定、PKCE S256、常量时间 `state` 校验与先验 `state` 后判 `error`、HTML 转义、HTTP 头/体大小上限（16KB/4MB）、4 个 `*_auth_secure.xml` Keystore 加密与备份排除均核对无误；Token 输入框均使用 `remember` + 掩码 + `KeyboardType.Password` + `ContentType.Password`。
- **Room 迁移与回归链路**：`MIGRATION_3_4` / `4_5` / `5_6` 与 `app/schemas/.../{4,5,6}.json` 完全对应，`GameRecordCodec` 兼容旧行且重扫/改名保留绑定；已有封面刮削、绑定优先级（「已有绑定优先，不静默覆写手工绑定」）、Bangumi 只写乐观更新与 VNDB/Hikarinagi/NextMoe 读写语义实现正确。
- **UI 核心规范**：`AppSecondaryTopBar`、`AppAlertDialog` + `DialogTextButton` + `NoIndication`、`AppSearchField`、`AppNavItem`、`Switch`/`SwitchPreference`、两档文字尺寸（含既定 `AppNavItem` 例外）、动态色彩常量（`NavWhite`/`DialogItemSurface`/`PageTextColor`/`glassBorder`）与 `AppNotice` 均符合 `AGENTS.md` 要求。

---

## 四、本地工具与测试执行记录

- `python tools/check-hardcoded-ui-strings.py`：**通过**（三语言键值与占位符一致，Kotlin 无硬编码 CJK 文案）。
- `python tools/check-motion-boundaries.py`：**通过**（366 个 Kotlin 文件，0 违规）。
- `python tools/test-hardcoded-ui-strings.py` & `python tools/test_motion_boundaries.py`：**通过**（5 + 11 用例）。
- `./gradlew.bat :app:testDebugUnitTest`（限定本分支相关包：`core.auth.*`、`core.cover.*`、`core.status.*`、`core.net.*`、`core.backup.*`、`core.game.scan.*`、`ui.auth.*`、`ui.cover.*`、`ui.game.PlayStatus*`）：**全部通过**。
- 全量 `./gradlew.bat :app:testDebugUnitTest --no-daemon`：1739 用例中 31 个失败（集中在 `RuffleRuntimeEnvironmentTest`、`EthornellOperationGateTest`、`LaunchEntryPathTest`、`EthornellSaveStoreTest`、`EthornellSettingsTest`，均为 Windows 宿主符号链接/POSIX 路径限制导致，非本分支改动引入）；`:engine:testDebugUnitTest` 652 用例中 18 个同类 Windows 环境失败（本分支未改动 `engine/`）。
- `./gradlew.bat :app:lintDebug --no-daemon`：在变更文件内命中 2 条 `[StaticFieldLeak]` 警告（已列为上文第 8 项）、1 条 `AndroidManifest.xml` `[IntentFilterUniqueDataAttributes]` 建议及若干既有/多语言复数提示，无本分支新增编译或源码级 Lint Error（总览唯一 Error 为本机 `local.properties` Windows 反斜杠格式提示）。
- **未执行项**：真机设备 Room v3→v6 迁移、真实浏览器 OAuth 回调闭环、窄屏设备 Compose UI 渲染实测。
