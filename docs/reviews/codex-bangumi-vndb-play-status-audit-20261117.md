# `codex/bangumi-vndb-play-status` 全量审计报告（2026-11-17）

- **审查对象**：分支 `codex/bangumi-vndb-play-status`（工作区 `D:\Tyranor-Next\wt-binding`，`HEAD = b14b7f3f`「feat: add NextMoe source, Bangumi play-status read-back, and audit fixes」，工作树干净，仅未跟踪 `tools/__pycache__/`）。该提交为「设备实机验收通过后的整体快照提交」——提交时工作树含多个会话的在途工作（NextMoe 集成、r5–r9 修复）与 Bangumi 读回修复，已作为整体通过实机验收
- **对比基线**：`origin/main = 709687a3`（`origin/main..HEAD` 共 10 个非合并提交：`c122d821`/`051d43b2`/`b7bbc2fc`/`1deafb5e`/`a839e42c`/`7e2d81eb`/`45df653c`/`79a4160e`/`cf56a52e`/`b14b7f3f`）
- **变更规模**：10 个提交 / 134 文件（+24,756 / −1,495）；范围文件 `scope-commits-1117.txt` / `scope-files-1117.txt` / `scope-diffstat-1117.txt` / `scope-full-1117.diff`
- **审查日期**：2026-11-17
- **审查依据**：[AGENTS.md](../../AGENTS.md)、[CONTEXT.md](../../CONTEXT.md)、[ARCHITECTURE.md](../../ARCHITECTURE.md)、[SPEC.md](../../SPEC.md)、[CHANGELOG.md](../../CHANGELOG.md)、`docs/应用持久化数据清单.md`（仅作待验证声称对待，关键声称均独立核实）
- **审计方式**：五视角相互独立盲审子代理（代码逻辑 / 架构 / 代码健壮性 / 回归风险 / 代码规范），盲审禁读 `docs/play-status-audit-*.md` 九份既有快照与 `docs/reviews/`；交叉对照、矛盾裁决与定级校准由当轮汇总裁决主控（本报告）完成，每条 P1/P2 均经主控亲自打开 HEAD 工作树复核 file:line 后定级
- **合入结论**：**No-Go（0 P0 / 1 P1 / 4 P2，其中 2 条 P2 须实际改动）**——一句话依据：CONTEXT.md/CHANGELOG 的「Bangumi 只写模型」旧措辞与代码直接矛盾（P1，合入前必须修复），且 Bangumi 读回 uid 兜底放大为「恒未收藏 + 假自愈」（P2）与 VNDB 校验现位未登记 §8 已知偏差清单（P2）均须在修复轮实际整改。

---

## 一、发现总览（合并去重）

五视角原始计数（logic 0P0/1P1/1P2/5P3、arch 0/0/2/7、rob 0/0/0/7、reg 0/0/1/20、sty 0/0/3/5；注：reg 视角计数行写「P3 = 21」系笔误，其实际枚举 REG-2..REG-21 为 20 条，主控点数核实）合计 **52 条：0 P0 / 1 P1 / 7 P2 / 44 P3**。合并同根因后为 **48 条：0 P0 / 1 P1 / 4 P2 / 43 P3**（压缩 4 条：LOGIC-1≡ARCH-1≡STY-1 三合一、STY-2 同根因并入、LOGIC-2≡ROB-3 二合一）。

| 级别 | 合并后 | 编号（含来源视角） | 摘要 | 处置 |
| --- | --- | --- | --- | --- |
| **P0** | 0 | — | — | — |
| **P1** | 1 | **M-1**（LOGIC-1 ≡ ARCH-1 ≡ STY-1，STY-2 并入） | 「Bangumi 只写模型」旧措辞残留：CONTEXT.md:267 术语权威与代码矛盾；CHANGELOG.md:46 与 :15/:170 三处并存 | 合入前必须修复（文档改写） |
| **P2** | 4 | **M-2**（LOGIC-2 ≡ ROB-3 并入） | Bangumi 读回 uid 兜底：身份回查一次性失败即永久锁死，读回恒 404 → 误显「未收藏」+ 双清假自愈 | 修复轮整改（代码） |
| **P2** | ↑ | **M-3**（ARCH-2） | VNDB token 校验/权限解析落 core/status，§7 有内联论证但 §8 已知偏差清单未登记 | 修复轮整改（§8 补登记一句话；或迁移） |
| **P2** | ↑ | **M-4**（REG-1） | NextMoe 关键词搜索候选携带 id/标题并随批量刮削回填，逆转 origin/main 的显式防护理由 | 【已论证】SPEC.md:157 补「未经验证」限定语 + 验收用例 |
| **P2** | ↑ | **M-5**（STY-3） | 9/10 提交描述为英文（STY 原文计 8/10 系点数误差，79a4160e 亦英文）+ b14b7f3f 跨类型混装 | 【不可追溯整改】标注维持 + 修复轮恢复中文提交惯例 |
| **P3** | 43 | LOGIC-3..7 / ARCH-3..9 / ROB-1/2/4/5/6/7 / REG-2..21 / STY-4..8 | 见第六节待裁决清单（11 条建议修复、32 条维持观察） | 逐项待裁决 |

### 合并对照表

| 合并编号 | 原始编号（视角·原级） | 合并/校准说明 |
| --- | --- | --- |
| **M-1（P1）** | LOGIC-1（logic·P1）≡ ARCH-1（arch·P2）≡ STY-1（sty·P2）；**STY-2（sty·P2）并入** | 三视角独立命中同一措辞（CONTEXT.md:267）；STY-2（CHANGELOG 同块三处自相矛盾）与 ARCH-1 的 CHANGELOG 侧引证同根因（b14b7f3f 未清扫旧措辞），一并并入。定级取 LOGIC 的 P1：校准上调理由 = ①三视角收敛；②CONTEXT.md 是术语权威（AGENTS.md:47），与代码冲突时按 AGENTS.md:49 须当场指出并消除；③修复成本为一段话，属「合入前必须修复」而非可论证豁免项。ARCH/STY 的 P2 原级上调 |
| **M-2（P2）** | LOGIC-2（logic·P2）≡ ROB-3（rob·P3，自评「未证实」） | 同一故障链的两段取证（uid 占位落库 + 读回 404 归一假自愈）。主控裁定维持 P2 并入 ROB-3：ROB 以「服务端接受度未实机证实」降 P3，但 ①代码链路（uid 占位 → ensureIdentityLoaded 永久跳过 → uid 作路径参数 → 404 归一 + 双清）全部本地可证；②CHANGELOG.md:170 记载了同签名实机事故（「实测恒 404 并被误显示为未收藏」），证明错误形状的后果真实；③PlayStatusService.kt:118-121 与 :330-332 两处注释对官方 spec 的转述互相矛盾（已亲核），其中一处恰声称「数字 uid 可兜底」。综合按 P2 |
| **M-3（P2）** | ARCH-2（arch·P2，自标【存量偏差】） | 单条维持。§7 内联论证在案（ARCHITECTURE.md:154），但 §8 清单（:173-175）未收录，按该清单集中登记约定补一句话即闭环 |
| **M-4（P2）** | REG-1（reg·P2，自标【存量偏差·论证成立】） | 单条维持。性质校准：严格说这是**本分支对 origin/main 防护立场的有意逆转**（非历史存量），按「明确论证并标注」路径闭环——缓解护栏（fill-if-empty、逐源修订号、写前显式确认）与文档记载（CHANGELOG.md:46、SPEC.md:157）亲核在案；缺口收敛为 SPEC.md:157 补「关键词命中未经人工验证」限定语 + 一条验收用例 |
| **M-5（P2）** | STY-3（sty·P2） | 单条维持，事实校准：10 提交中 **9 条英文**（STY 原文「8/10」与「仅 cf56a52e、79a4160e 为中文」点数有误，79a4160e 主题为英文）。基线规则在案（origin/main AGENTS.md 即含「本次起新提交统一使用中文描述……每个提交聚焦一类修改」，已 `git show` 亲核）。处置见第五节 M-5 |

---

## 二、各视角详情摘要

- **logic（代码逻辑）**：0P0/1P1/1P2/5P3。方法：逐行亲核 PlayStatusService、四源 Auth Store/Service、SingleFlightRefresh、SecureAuthStore、HttpJson、Room Migration+schemas、Facade/Repository/Dao、Codec/Scanner merge、DeletionCleanup、ViewModel+Reducer、弹窗/抽屉、刮削链、凭据弹窗、备份规则与三语言 parity。17 项正向确认（迁移逐列核对、404/401/403 分档、写路径单侧约束、单飞采纳规则、TTL 缓存键、并发防护、备份排除等）。发现：LOGIC-1（P1）CONTEXT 术语矛盾、LOGIC-2（P2）uid 兜底放大、LOGIC-3..7（P3）写 404 分档/数据清单措辞/来源名取法/写守卫静默/clearSaveSync 取消窗口。
- **arch（架构）**：0P0/0P2×2/7P3。方法：通读 30.5K 行 diff + 自写 Tarjan SCC 对 HEAD 与 origin/main 各跑 import 图求环。12 项正向确认（无新增包级环、core/net 中性层成立、core 零 Compose、Room/凭据/分位对称、androidTest 归位等）。发现：ARCH-1（P2）CONTEXT 术语、ARCH-2（P2）VNDB 校验跨域未登记 §8、ARCH-3..9（P3）storage 门面四源语义/合并规则四处副本/弹窗编排归位/Hikarinagi 未拆文件/docs 索引缺九份/acknowledgedEventId UI 协议入 core/设备测试命名错位。
- **rob（代码健壮性）**：0P0/0P1/0P2/7P3。方法：逐文件亲读全部新增/重写主链。11 项正向确认（HTTP 层、加密存储、token 不泄漏、OAuth 常量时间比较、并发锁序、Bangumi 读回语义、生命周期、Room/Codec、UI 并发防护、备份域、测试桩 DEBUG 守卫）。发现：ROB-1 环回 catch 丢已验证回调、ROB-2 awaitUninterruptibly 不可中断等待、ROB-3（并入 M-2）、ROB-4 bindingRevisions 累积、ROB-5 id_token 仅验 nonce 不验签（无安全视角，主控裁决见第六节）、ROB-6 节流 sleep、ROB-7 Hikarinagi 临时 AuthorizationService 兜底 dispose 缺口。
- **reg（回归风险）**：0P0/0P1/1P2/20P3。方法：逐文件对照 origin/main 亲读 diff。14 项正向确认（迁移逐字段、绑定列回填、Codec 双向兼容、三语言 parity、删除管线等价、存储兼容、调用点无悬空引用等）。发现：REG-1（P2）NextMoe 关键词回填；REG-2..21（P3）四类行为变化确认（合并方向翻转、HTTP 层下沉行为差、节流共享、错误分位细化、封面哈希文件名、共享组件行为、快捷方式管线、Codec 追加、迁移、DAO 扩参、删除管线、strings、Manifest/构建、auth 兼容、API 变更、updater、IO 下沉、刮削收尾）——均附等价性论证或真机回归建议；R0 失败分诊附录正确指出「分支新增测试须以实跑记录另行确认」。
- **sty（代码规范）**：0P0/0P1/3P2/5P3。方法：以 AGENTS.md 全文为标尺逐节核对。9 组正向确认（三语言 1157 键 parity、无硬编码 CJK、core 零 R import、CE 透传全改动点覆盖、UI 组件/动效/主题/提示条/设置流订阅、资源归属、命名、文档同步、消重正向项）。发现：STY-1（P2）CONTEXT 术语、STY-2（P2）CHANGELOG 块内矛盾（并入 M-1）、STY-3（P2）提交信息语言/聚焦、STY-4..8（P3）借用枚举漏第四点/OAuth 编排三副本/来源行渲染三段复制/NextMoe 命名注释/域分居【存量偏差·已论证】。

---

## 三、重点问题展开（P1/P2）

### M-1【P1】「Bangumi 只写模型」旧措辞残留族（LOGIC-1 ≡ ARCH-1 ≡ STY-1，STY-2 并入）

- **现象与证据**（主控逐一亲核）：
  - `CONTEXT.md:267`：「对 VNDB、Hikarinagi 与未萌NextMoe 支持远端状态回读与显式写入，**Bangumi 为只写模型（仅显式写入、不做 GET 回读，写后乐观更新）**」；
  - 与代码直接矛盾：`app/src/main/java/com/tyranor/next/core/status/PlayStatusService.kt:514-530`（`readBangumiStatus`，GET `/v0/users/{username}/collections/{subject_id}`，`treatJsonNotFoundAsNull`）、`PlayStatusDialog.kt:373-375`（弹窗回读消费点）、`PlayStatusService.kt:954-956`（404 JSON 归一）；
  - 同分支内 `ARCHITECTURE.md:154`、`SPEC.md:160`、`CHANGELOG.md:15`（修复节）均已按读回描述；
  - STY-2 侧：`CHANGELOG.md:46`（新增节）同块仍写「Bangumi 为只写模型（不做 GET 回读……）」，`:15` 修复节宣布读回上线，`:170` 历史注记以「（见「新增」节：Bangumi 不做 GET 回读……）」交叉引用旧措辞——同一未发布块三处并存（STY 原文引 :163 系行号漂移，实际 :170，内容属实）。
- **影响**：CONTEXT.md 是术语权威（AGENTS.md:47），术语表与代码冲突会误导后续维护者按「只写」语义改代码（如简化掉读回管道）；CHANGELOG 叙事自相矛盾违反 AGENTS.md:26 文档同步义务。
- **处置（修复轮必须完成）**：① `CONTEXT.md:267` 改写为四源读写模型（Bangumi 经 GET 读回、404 归一未收藏、读 401/403 分位）；② `CHANGELOG.md:46` 只写括号按 :170 同风格补「该措辞已被读回取代（见修复节）」注记或直接改写；③ `CHANGELOG.md:170` 交叉引用句按需同步。纯文档改动，无代码连带。
- **校准说明**：LOGIC-1 原级 P1 维持为合并主条目；ARCH-1/STY-1 的 P2 上调（三视角收敛 + 术语权威 + 义务明确 + 修复成本极低）；STY-2 的 P2 并入不单独计数。

### M-2【P2】Bangumi 读回 uid 兜底放大为「恒未收藏 + 假自愈」（LOGIC-2 ≡ ROB-3）

- **现象与证据**（主控逐一亲核）：
  - OAuth 换码成功即以数字 uid 占位 username：`BangumiAuthService.kt:345-347`（`saveUserIdentityIfEpochMatches(context, uid, uid.toString(), savedEpoch)`）；`BangumiAuthStore.kt:117-121`（getStatus 对空白 username 以 uid 兜底返回）、`:201-211`（saveUserIdentityLocked 落库即含兜底形态）；
  - 身份回查为 best-effort 且**永不补查**：`:348-350` identityScope 一次性回查；`BangumiAuthService.kt:558-567` `ensureIdentityLoaded` 因 `status.username.isNotBlank()`（uid 占位串非空）恒提前 return，注释明确「此行对这类账号恒跳过：不再补解析真实用户名」——该注释论证的是**展示层**降级（BK-19），但新读回路径把它升级为**功能层**降级；
  - 读回以 uid 作路径参数：`PlayStatusService.kt:123-127`（`resolveBangumiReadUsername`）、`:514-530`（GET 拼接 username 段）、`:330-332`（`BANGUMI_USER_COLLECTION_READ_BASE` 注释声称「数字 uid 可兜底」——与 `:117-121` KDoc 引用的官方 spec「设置了用户名之后无法使用 UID」直接矛盾，两处转述已亲核）；
  - 后果：对**设有用户名**的账号，uid 路径参数按本代码自引 spec 恒 404 → `:954-956` 归一为「未收藏」→ `:537-546` `finishBangumiStatusRead` 视为读成功并**双清** lastError/permission_error（假自愈）。用户写路径走 `-` 不受影响，写成功后重开弹窗/抽屉恒显「未收藏」，无自愈途径直至重新授权/重贴令牌。
- **佐证**：`CHANGELOG.md:170` 记载了同签名实机事故——「单条收藏 GET 不支持 `me`/`-` 通配（**实测恒 404 并被误显示为「未收藏」**）」：错误形状（错误用户名形态 → 404 → 误显未收藏）经实机证实，未证实的只剩「uid 对设有用户名的账号是否被接受」一点；即便该点侥幸成立，两处 spec 转述矛盾本身也必须收敛。
- **影响**：触发窗口 = 授权完成时 /v0/me 身份回查一次性失败且账号设有用户名；后果 = 读回层持续错误展示 + 权限位假自愈，无数据丢失。触发窄、后果为错误展示而非损坏，故 P2 非 P1。
- **处置（修复轮整改，代码）**：方案任选其一——(a) 身份回查失败时不以 uid 占位 username（保留空白并允许后续补查，展示层降级由 getStatus 兜底承担）；(b) `resolveBangumiReadUsername` 检测「username == 数字 uid 且 userId > 0」形态时先补一次 /v0/me 回查再读回（注意 uid 直用可能是无用户名账号的唯一形态，须保留该分支）；无论取哪条，须 ①消除 `PlayStatusService.kt:330-332` 与 `:117-121` 两处 spec 转述矛盾（以一次实机 curl 收敛「uid 是否可用」并落注释），②在 SPEC/持久化清单标注该降级面已从「展示层」扩及「读回层」，③补 JVM 用例钉住「身份回查失败账号的读回行为」。
- **校准说明**：LOGIC-2 原级 P2 维持；ROB-3 原级 P3 并入上调（理由见合并对照表）。

### M-3【P2】VNDB 校验能力落 core/status，§8 已知偏差清单未登记（ARCH-2）

- **现象与证据**（主控亲核）：`verifyVndbToken`/`checkVndbPermissions`/私有 `authInfo` 位于 `core/status/PlayStatusService.kt:387`/`:396`；消费点为 `ui/cover/CoverScraperSettingsActivity.kt`（粘贴校验）与 `ui/game/PlayStatusDialog.kt`（绑定后权限提示）；AGENTS.md:83 规定 core 职责含「授权」（属 core/auth 域）；`ARCHITECTURE.md:154` §7 已内联论证（「若迁 core/auth 需连带上述两处调用点，触碰时评估」），但 `ARCHITECTURE.md:173-175` §8 已知架构偏差清单（仅 KrkrOnlinePatchService / core/updater / 旧数据清单）未收录本条。
- **影响**：后续给 VNDB 授权加功能者会在 core/auth 找不到校验入口，或在两处调用点旁各写一份校验；§8 清单的集中登记约定被绕过。
- **处置（修复轮整改，文档一句话）**：二选一——(a) 迁 core/auth（连带两处 import 与 ARCHITECTURE.md:154 描述改写）；(b) 保持现位，在 §8 补一句登记（推荐，成本最低）。两处都算完成整改。
- **校准说明**：ARCH-2 原级 P2【存量偏差】维持——§7 论证在案但登记形式未完成，不满足「明确论证**并标注**」的完整闭环，须补 §8 一句。

### M-4【P2】NextMoe 关键词候选携带 id/标题并随批量刮削自动回填（REG-1）

- **现象与证据**（主控亲核）：origin/main `NextMoeCoverService.kt:63-65`：「关键词模糊匹配命中哪部作品并未被验证，故不写回 VNDB 元数据（避免把异作的 id/标题落库）」+ `.map { it.copy(vndbId = null, metadataTitle = null) }`（关键词候选不携带任何 id/标题）；HEAD `NextMoeCoverService.kt:90-93` 注释改写为「候选自身的 nextmoeId 与标题仍保留，供用户手工绑定或缺失时补全」+ `.map { it.copy(vndbId = null) }`，`:188` metadataTitle 条件放宽为 `resolvedNextMoeId != null || vndbId != null`；`CoverScrapeTaskManager.kt:238-243`（mergeScrapedCover）对空绑定行以刮削命中回填 nextmoe_id 与 metadataTitle（带逐源修订号护栏）。合流效果：既有安装的批量刮削可对无绑定游戏自动写入**未经人工验证的关键词命中作品 id**，并成为后续游玩状态写入的目标条目。
- **缓解护栏（亲核在案）**：仅 fill-if-empty（已有绑定优先）；解绑/改绑窗口的修订号护栏（:221-241 注释）；状态写入前 UI 必须显式确认；`CHANGELOG.md:46`、`SPEC.md:157` 已记载「适用候选携带 VNDB/Bangumi/Hikarinagi/未萌NextMoe 关联 ID 与元数据标题（条目绑定）；批量刮削以已有绑定优先，仅在缺失时以刮削命中填充」。
- **影响**：逆转 origin/main 显式声明的防护理由（避免异作 id 落库），关键词误命中时状态会写到错误远端条目（用户可见且可解绑，无静默数据损坏）。
- **处置（修复轮附带完成，文档一句 + 用例）**：① `SPEC.md:157` 补限定语「关键词命中回填的 nextmoe_id 未经验证，写状态前以弹窗展示为准」（现文只描述行为未声明出处可信度）；② 回归验收补一条「NextMoe 关键词批量刮削后抽屉摘要与绑定 id 一致」用例。代码回改不做（护栏与文档论证成立）。
- **校准说明**：REG-1 原级 P2 维持；性质由「存量偏差」精确化为「本分支有意设计变更，按论证标注闭环」。

### M-5【P2】提交信息 9/10 英文 + b14b7f3f 跨类型混装（STY-3）

- **现象与证据**（主控亲核）：`git log origin/main..HEAD` 10 条主题中仅 `cf56a52e` 为中文，其余 9 条（含 `79a4160e`）为英文——STY 原文「8/10 英文、仅 cf56a52e/79a4160e 为中文」点数有误，但违规事实成立且更重；规则在案：origin/main 的 AGENTS.md 即含「本次起新提交统一使用中文描述，类型前缀保持小写英文……每个提交聚焦一类修改」（`git show origin/main:AGENTS.md` 亲核，10 条提交均在规则之下产生）；`b14b7f3f` 单提交混装 NextMoe 新功能 + Bangumi 读回 + audit fixes + 文档 + 设备测试（约 80 文件）。
- **特殊上下文裁决**：b14b7f3f 为「设备实机验收通过后的整体快照提交」——当时工作树含多个会话的在途工作与 Bangumi 读回修复，已作为整体通过实机验收；**拆分将伪造历史且不可行**。对其余 9 条英文主题做 rebase reword 将使全链 SHA 变更，与本轮台账/范围文件（scope-commits-1117.txt 逐条锚定 SHA）及验收记录冲突，收益（纯文字语言）不抵成本。
- **处置（以【不可追溯整改】标注闭环）**：①不重写既有 10 条提交（上述理由写入本报告作为标注）；②修复轮起恢复提交惯例——新提交统一中文描述 + 每提交聚焦一类修改（本轮已按用户口径明确「修复轮恢复提交惯例」）；③如主循环后续要求，可仅对 tip 提交做中文改写（amend 只变一个 SHA），默认不做。
- **校准说明**：STY-3 原级 P2 维持，按「明确论证并标注」路径闭环，不计入「未整改 P2」。

---

## 四、实跑验证（主控在本轮执行，HEAD = b14b7f3f，工作树干净）

| # | 命令（仓库根执行） | 退出/结果 | 关键输出 |
| --- | --- | --- | --- |
| 1 | `python tools/check-hardcoded-ui-strings.py` | 通过（退出码 0） | `Hardcoded UI string check passed.`（scratch `check-strings-1117.log`） |
| 2 | `python tools/check-motion-boundaries.py` | 通过（退出码 0） | `Motion boundaries: 367 Kotlin files, 0 violations.`（scratch `check-motion-1117.log`） |
| 3 | `gradlew.bat -p buildSrc graphicsResourcesRegression --no-daemon` | 通过（退出码 0） | `Graphics resource regressions passed: 24`，`BUILD SUCCESSFUL in 7s`（scratch `check-graphics-1117.log`） |
| 4 | `gradlew.bat :app:testDebugUnitTest --no-daemon --continue`（另补 `:engine:testDebugUnitTest --no-daemon --continue` 对照引擎侧） | 按预期 BUILD FAILED（cmd 外壳 `%errorlevel%` 显 0 系既有解析伪影，以 `BUILD FAILED` + `tests completed` 行为准） | engine：**652 tests / 18 failed / 4 skipped**；app：**1840 tests / 31 failed / 18 skipped**（scratch `check-engine-jvm-1117.log` / `check-app-jvm-1117.log`） |
| 5 | `gradlew.bat :app:lintDebug --rerun-tasks --no-daemon` + `python tools/check-lint-diff.py --base origin/main app/build/reports/lint-results-debug.xml` | lint BUILD FAILED（1 error）；lint-diff **通过** | `Lint found 1 error, 407 warnings, 10 hints`；`Lint errors in reports: 1. Errors on changed sources: 0.`（scratch `check-lint-1117-fresh.log` / `check-lint-diff-1117.log`） |

### 4.1 测试失败集合与基线对账（check-app-jvm-obs.log 为准，比对脚本 `compare-failures-1117.py`/`.out`）

- obs 基线（2026-11-14 12:40 记录）：engine 16 + app 27 = **43 条失败**；本轮实测：engine 18 + app 31 = **49 条**。
- **obs 基线 43 条在本轮 0 消失、逐一复现**（engine 16/16、app 27/27，比对脚本 MISSING 计数均为 0）。
- 相对 obs 清单**新增 6 条**（engine 2 + app 4），主控取证**全部为本分支基线（origin/main = 709687a3）既有、非本分支引入**：
  - app 4 条：`EthornellSaveStoreTest > deletingMigratedSavesDoesNotCauseLegacyFilesToReturn / interruptedLegacyCopyCanRetryAndNeverOverwritesAlreadyCommittedFiles / migrationCountsImplicitTargetParentsBeforeWriting / legacyPrivateFilesMigrateWithoutOverwritingGameSavesAndMarkerIsNotExported`，异常均为 `java.nio.file.AccessDeniedException`；
  - engine 2 条：`EthornellPathsTest > gameUserRootRejectsOtherRelativeExternalAndSymlinkAliasPaths / gameUserRootRejectsSymlinkedSavedataAndRequiresExistingDirectoryRoot`，异常均为 `java.nio.file.FileSystemException`。
  - 证据链：①上述测试类与被测类（EthornellSaveStore/Paths、EthornellOperationGate、EthornellSettings、RuffleRuntimeEnvironment、LaunchEntryPath、Xs4ManualUrl、ArtemisVorbisRepair、EthornellDispatchRegistry）在 `origin/main..HEAD` diff **零命中**（`git diff --stat` 亲核为空），engine/ 整树零改动、`scope-files-1117.txt` 无 engine 条目；②6 个测试方法经 `git show origin/main:<test>` 亲核**全部已存在于 origin/main 测试源**（scratch `est-origin-main.kt`、`ept-origin-main.java`）；③异常签名与 obs 基线 43 条同为 Windows 文件权限/符号链接环境类。结论：obs 清单记于 2026-11-14，早于基线 origin/main 前移至 709687a3（其间 origin/main 侧新增了这些测试方法）；**相对本轮审计基线的失败集合 49=49 对账成立，0 条分支相关新增**。
- 用例规模对账：app 1490 → 1840（+350）、engine 617 → 652（+35），新增部分除上述 6 条外**全部通过**——本分支 10 提交新增的 JVM 测试（auth/cover/net/status/ui 等）实跑零失败。
- 注：任务口径「engine 16+6 环境性 / app 27，共 49」与实测分布（engine 18 / app 31、合计恰 49）对账一致；obs 清单中的 `EthornellSaveStoreTest` 失败行号（:42/:240）与本轮（:45/:329）漂移系该测试文件在基线前移期间的版本演进，非分支改动（该文件不在 origin/main..HEAD diff 内，已亲核）。

### 4.2 lint 对账

- 本轮 `:app:lintDebug`（`--rerun-tasks` 全量重跑，3m30s）：**1 error / 407 warnings / 10 hints**。唯一 error 为 `local.properties:1 PropertyEscape`——该文件为 gitignore 的本机文件（含 sdk.dir 与 BANGUMI/NEXTMOE 等 BuildConfig 注入键），系快照前会话编辑时将 `sdk.dir` 写成未转义形态所致，**非分支内容**（`git status` 干净可证）；`check-lint-diff` 口径（app/src + engine/src 变更行）下**变更源码 error = 0**，门槛达成。
- 与 obs lint 基线（30 errors / 334 warnings）的差异对账：既有 30 个 error 的首项为 `ui/common/glass/EnhancedLiquidGlassNavigation.kt:641 NewApi` 等 glass 族文件——**glass 文件不在本分支 134 文件范围内**（scope-files-1117.txt 零命中），该 30 error 系基线前移期间在 origin/main 侧已被修复；407 warnings 相对 R10 的 367 增加 40 条，来自本分支新增代码与基线演进，均在 error=0 门槛之外。`tools/__pycache__/` 未跟踪目录为既有审计脚本运行残留（STY 附注亦提及），不属分支内容。

---

## 五、P3 待裁决清单（43 条：11 条建议修复、32 条维持观察）

> 用户口径：合理挂账 = 待裁决，不是不改。以下逐项给出建议与理由；「修复」项给出方案要点与改动面，「维持」项给出触发条件。修复轮按此清单逐项明确处置（修或论证维持），不得默认搁置。

### 5.1 建议修复（11 条）

> R11fix 终稿：以下 11 条全部落地修复，逐项处置见第八节 8.2（LOGIC-3/4/5/6/7、ARCH-7/9、ROB-1/4、STY-4/7）。

| # | 来源 | 摘要 | 建议 | 理由 | 改动面 |
| --- | --- | --- | --- | --- | --- |
| 1 | LOGIC-3 | Bangumi 写路径对不存在/已删除 subject id 的服务端 404（JSON 体）落 `BANGUMI_NETWORK` 文案；Hikarinagi/NextMoe 同场景分档 `SUBJECT_NOT_FOUND`（枚举已存在，主控亲核 PlayStatusErrors.kt） | 修复：写路径 404（JSON 体）加 SUBJECT_NOT_FOUND 分档 | 误导性文案 + 无效重试；与同族对齐，`bangumiStatusToError`（:98-102）加一分支 | `PlayStatusService.kt` 写分支 + `PlayStatusMappingTest` 镜像用例；复用既有文案键 |
| 2 | LOGIC-4 | `docs/应用持久化数据清单.md:79` 称 `bangumi_auth_secure.xml` 的 last_error「写方含……个人令牌校验拒绝」，代码中个人令牌校验失败仅返回弹窗展示不落盘（主控亲核 verifyPersonalToken :588-604 无 store 写路径） | 修复：修正清单措辞（删除「个人令牌校验拒绝」或改注「仅弹窗展示不落盘」） | 文档与代码不一致；行为本身合理（校验失败无持久化必要），改文档优先 | 数据清单 1 行 |
| 3 | LOGIC-5 | 抽屉摘要同一行内来源名取法不一致：VNDB/Hikarinagi/Bangumi 用 `coverSourceTitleRaw`（:919/:926/:941），NextMoe 用本地化 `coverSourceTitle`（:933，主控亲核） | 修复：统一为 `coverSourceTitle(context,…)` 本地化取法（缓存键含 localeTag，本地化安全） | 同界面同一行显示口径不一致，纯观感 | `PlayStatusDialog.kt` 3 处调用 |
| 4 | LOGIC-6 | NextMoe 写纵深守卫（初始回读未成功拒绝写）静默 `return`（:433-437，主控亲核），选择弹窗保持打开无反馈 | 修复：给 Limit 提示并关闭 pickSource（与空绑定分支同口径） | UI 层禁点使路径正常不可达，仅「弹窗打开后回读失败」快照漂移窗口可触发；修复小、反馈一致性 | `PlayStatusDialog.kt` :433-437 |
| 5 | LOGIC-7 | `cleanupDeletedGame` 仅 `clearSaveSync` 在 `NonCancellable` 之外（GameDeletionCleanup.kt:20-46），VM 销毁窗口取消时后续清理被跳过 | 修复：把 clearSaveSync 一并纳入 NonCancellable（删除已确认语义），或最低限度注明取舍 | 清理完整性缺口（孤儿文件/覆盖行残留可能）；需真机回归删除场景 | `GameDeletionCleanup.kt` + 注释 |
| 6 | ARCH-7 | docs/README.md「验收 / 历史记录」节未收录 9 份 `docs/play-status-audit-*.md`（AGENTS.md:16 索引约定，主控亲核 grep 零命中） | 修复：按日期补 9 条目或一条汇总条目 + 快照时效说明 | 索引约定与实际状态脱节，纯文档 | `docs/README.md` 数行 |
| 7 | ARCH-9 | `androidTest/.../core/cover/DeviceStorageAndAuthCryptoTest.kt` 包归属 core 域、实际驱动 Activity 级 UI 验收，命名/包归属与被测对象不一致 | 修复（注记路线）：文件头注明「经封面设置页 UI 验证存储与授权加密」的归位理由 | 零风险注记优先（迁包需设备验证环境） | 1 行注记（或后续迁 `androidTest/.../ui/cover/`） |
| 8 | ROB-1 | NextMoe 环回回调：state 校验已通过的回调在 catch 路径被丢弃（`:509-521` catch 分支 `verifiedResult ?: run{...}` 求值结果未赋回），与 KDoc「写失败亦保留已验证回调」不符 | 修复：两个 catch 分支改 `parsedResult = verifiedResult`（或命中即 return），使实现与注释一致 | 现实可达面极窄（`Socket.close()` 抛 IOException），但修复明确、消除注释-实现偏差 | `NextMoeAuthService.kt` :509-521 + 可补 JVM 用例 |
| 9 | ROB-4 | `GameLibraryFacade.bindingRevisions` 按 URI 单调累积、游戏删除不清理（:307-327，主控亲核 removeGame 不删条目） | 修复：`removeGame`/`removeRootAndGames` 顺带 `bindingRevisions.remove(uri)` | 卫生问题；量级可忽略但修复为一行级，防长期驻留增长 | `GameLibraryFacade.kt` + 单测 |
| 10 | STY-4 | AGENTS.md:138 与 PlayStatusErrors.kt KDoc 的 `play_error_*` 借用枚举漏第四点 `BangumiAuthMessages.kt`（:21-23 PERMISSION → `play_error_bangumi_token_permission`，主控亲核） | 修复：两处枚举补 `BangumiAuthMessages.kt` | 行为符合约定、无文案双源，但规范枚举不全，后续按枚举「清理借用」时会漏改 | AGENTS.md 1 处 + KDoc 1 处 |
| 11 | STY-7 | PlayStatusService NextMoe 系命名混用 `nextmoe*`/`nextMoe*` 前缀、两处纯映射缺同族 KDoc、:1124-1125 英文注释 | 修复：统一 `nextMoe*` 前缀 + 补同族 KDoc + 注释改中文 | 命名/风格一致性；纯改名零行为 | `PlayStatusService.kt` 数处 |

### 5.2 维持观察（32 条）

**架构/健壮性/规范类（12 条）**（R11fix 终稿：全部维持确认，触发条件与复核入口逐条在案；复核入口以「文件 · 符号」为稳定锚点，括注 b14b7f3f 行号供回溯）

| # | 来源 | 摘要 | 维持理由 | 触发条件（重议时机） | 复核入口（文件 · 符号 @ b14b7f3f） |
| --- | --- | --- | --- | --- | --- |
| 1 | ARCH-3 | 四源条目绑定修订号驻 `core/game/storage` 门面 + core/game→core/cover 反向知识依赖（单向无环已证） | 过渡形态已论证（单向依赖、无环）；ARCH 自评「可接受为过渡形态」 | 新增第五绑定来源或触碰 GameLibraryFacade 修订号结构时，按建议泛化为「行级绑定代次 + 来源键集合」 | `GameLibraryFacade.kt` · BindingRevisionSnapshot/bindingRevisions（:38-45）+ `GameDeletionCleanup.kt` · import CoverImageCache（:8） |
| 2 | ARCH-4 | 「已有绑定优先、仅补缺」合并规则在 DB/任务/UI 两域三层 4 处各一份实现（双护栏有意设计、各自有单测） | 结构为有意双护栏；语义演化前收敛反而引入抽象成本 | 合并语义演化（如允许解绑后补缺）时，先把纯映射收敛为单一共享函数 | `CoverScrapeTaskManager.kt` · mergeScrapedCover（:208）/ `CoverScraperService.kt` · resolveBoundIdOnCoverBind（:544）/ `MainLibraryViewModel.kt` · applyCoverUpdate/applyScrapedGame/applyBindingUpdate（:564/:616/:640） |
| 3 | ARCH-5 | PlayStatusDialog.kt（1334 行）承载 TTL 缓存 + 四源读写编排 + 写决策，属 core/status 自然归属（BK-64 注记在案） | 错误协议与线程约束均遵守；下沉是纯重构，无行为收益 | 触碰 core/status 或弹窗消费点扩展时，把 PlayStatusSummaryCache 与编排下沉 | `PlayStatusDialog.kt` · PlayStatusSummaryCache（:152）+ `PlayStatusErrors.kt` · BK-64 域归位注记（:13-22） |
| 4 | ARCH-6 | Hikarinagi 封面源仍为 CoverScraperService 内 private object，四源文件布局不对称（部分存量形态） | 本轮已扩其能力但未拆文件；拆分纯移动 | 下次触碰封面域 Hikarinagi 逻辑时拆为 `HikarinagiCoverService.kt`（与三源同形） | `CoverScraperService.kt` · private object HikarinagiCoverSource（:330）对照 `BangumiCoverService.kt`/`VndbCoverService.kt`/`NextMoeCoverService.kt` 独立文件 |
| 5 | ARCH-8 | `acknowledgedEventId`（UI 提示确认协议）驻 core/cover 任务管理器 | 功能正确、有注释与测试；单 UI 语义出口 | 出现第三个消费界面时，收敛为 UI 侧共享会话状态 | `CoverScrapeTaskManager.kt` · acknowledgedEventId（:55-56/:123/:179）+ `MainLibraryViewModel.kt` · acknowledgedEventId 收集与 alreadyAcknowledged 判定 |
| 6 | ROB-2 | SingleFlightRefresh 等待方 `awaitUninterruptibly` 不可中断（最长 ~35s 线程占用） | 结果正确性不受影响（醒后 recheck）；IO 池可扩 | 出现取消语义可感问题（旋转/退出时线程占用告警）再改可中断等待；仅补注释可选 | `SingleFlightRefresh.kt` · awaitUninterruptibly 等待（:38-41） |
| 7 | ROB-5 | NextMoe OIDC id_token 仅验 nonce 不验签（AuthFormHttp.kt:70-88，主控亲核仅解 payload 取 nonce 比对） | **本循环无安全视角，主控代裁**：部署面（Public Client、TLS 直取 token endpoint、id_token claims 未用于访问判定、API 服务端独立校验令牌）下风险 ≈ 0，为防御纵深缺失而非可达漏洞；nonce 常量时间比较、解析异常收敛类型化错误码 | 触发即必须补 JWS 验签（RS256 + JWKS）：①引入令牌中继/代理或第三方注入面；②应用开始信任 id_token 身份声称（如以 sub 作账号身份）；③NextMoe 侧要求规范符合（OIDC Core 3.1.3.7）验收 | `AuthFormHttp.kt` · validateOidcIdTokenNonce（:70-88）+ `NextMoeAuthService.kt` · nonce 校验消费点（:750-752） |
| 8 | ROB-6 | `RequestThrottle.await()` 持监视器 sleep（共享 vndbThrottle 跨域串行至多 ~1.55s） | 单机串行限流有意设计；睡眠不可取消但上限小 | 出现跨域排队体感问题再按域拆桶 | `HttpJson.kt` · RequestThrottle.await + vndbThrottle（:118-138/:145） |
| 9 | ROB-7 | Hikarinagi 换码临时 AuthorizationService 依赖 AppAuth 回调必然触发才 dispose | 未观察到不回路径；单对象进程内自然回收 | 发现 AppAuth 回调不回的真实路径或内存告警，再补超时兜底 dispose | `HikarinagiAuthService.kt` · handleAuthorizationResponse 临时 AuthorizationService（:246-305 全分支 dispose） |
| 10 | STY-5 | 三源 OAuth 取令牌编排约 100 行 ×3 平行副本（差异已注释固化；resolveOAuthRequestToken 已示范参数化模板） | 公共底座已抽取，编排差异有意保留 | 第四源接入或编排 bug 需同步三处时，按 resolveOAuthRequestToken 先例参数化下沉 | `BangumiAuthService.kt`（:386-555）/ `HikarinagiAuthService.kt`（:383-539）/ `NextMoeAuthService.kt`（:837-970）三段同构；已抽取底座 `SingleFlightRefresh.kt`/`AuthFormHttp.kt`/`SecureAuthStore.adoptSingleFlightAccessToken` + `PlayStatusService.resolveOAuthRequestToken`（:842-887）先例 |
| 11 | STY-6 | CoverSourceRow 三段授权行渲染约 100 行平行复制（决策逻辑已抽 oauthRowActions 纯函数） | 渲染块未参数化但决策已共享 | 第四授权行或改渲染时抽 `OAuthRowActionsSlot` 小组合函数 | `CoverScraperSettingsActivity.kt` · CoverSourceRow 三段 when(source) 渲染 + oauthRowActions/authorizedSourceTitle 共享纯函数 |
| 12 | STY-8 | 游玩状态域文件跨包分居（弹窗驻 ui/game、文案映射驻 ui/cover）【存量偏差·已论证】 | BK-64 KDoc 固化取舍并给出迁 ui/common 连带清单，符合豁免条件 | 下次触碰两包 import 时按 KDoc 指引评估迁 ui/common | `PlayStatusErrors.kt`（:9-12）+ `PlayStatusDialog.kt` KDoc（:350-356）双注记 |

**回归风险类（20 条）**：REG-2..REG-21，REG 视角均附等价性/有意性论证，主控抽核关键条（REG-1 的 NextMoe 比对、REG-12 的 Room 逐字段、REG-15 的三语言 parity 论证）成立。（R11fix 终稿：全部维持确认，触发条件与复核入口逐条在案）

| # | 来源 | 摘要 | 维持理由 | 触发条件 | 复核入口（文件 · 符号 @ b14b7f3f） |
| --- | --- | --- | --- | --- | --- |
| 13 | REG-2 | 四源刮削合并方向整体翻转：刮削命中优先 → 已有绑定/标题优先（重复刮削不再刷新标题） | CHANGELOG/SPEC 已记载；恢复路径 = 重新绑定 | 真机回归「重刮已刮削游戏」确认标题不刷新可接受（下次真机验收批次） | `CoverScrapeTaskManager.kt` · mergeScrapedCover（:226-243）/ `CoverScraperService.kt` · asCoverOnlyUpdateFrom（:560-570）/ `VndbCoverService.kt`（:55-59）/ `NextMoeCoverService.kt`（:67-70）/ `BangumiCoverService.kt`（:29-32） |
| 14 | REG-3 | core/net 下沉行为差：Content-Type 去 charset、强制 no-cache、日志脱敏、CE 透传 | 等价性论证成立（415 修复有据） | 无需整改；留意 Steam/Hikarinagi 仍为手写连接（未统一），触碰时评估收编 | `HttpJson.kt` · httpJsonResult（:63-101） |
| 15 | REG-4 | VNDB 节流 1100→1550ms 并与游玩状态共享串行桶 | 有意为之（VNDB 软上限防跨域绕过） | 真机记录一次 VNDB 全库刮削耗时；体感变慢再议拆桶 | `HttpJson.kt` · vndbThrottle(1550ms)（:147-151）+ `VndbCoverService.kt` · await 消费（:215-217） |
| 16 | REG-5 | Hikarinagi 封面搜索错误分位细化（AUTH_MISSING 新码、401 置 needsReauth、429 独立） | UI 映射与三语键齐备，行为更准确 | 真机确认「未授权置灰 + 搜索报未授权」两条路径文案 | `CoverScraperService.kt`（:360-376/:404-414）+ `CoverErrors.kt`（:19-21）+ `CoverErrorMessages.kt`（:12） |
| 17 | REG-6 | CoverImageCache 细节变化（read==0 continue、deleteCachedCover 限 file: scheme、首拍延迟消除） | 等价性论证成立 | 无需整改 | `CoverSupport.kt`（:82-84/:107-113/:129-141）+ `HttpJson.kt`（:127-143） |
| 18 | REG-7 | 自定义封面文件名 → 内容哈希（换封面产生新 URI，旧图落库成功后回收） | 行为变化有意（同名不同图刷新缓存）；孤儿回收闭环 | 真机回归「连续更换自定义封面后 covers_remote 无孤儿」 | `VndbCoverService.kt` · saveCustomCoverFromStream/persistCustomCover（:96-140/:166-198）+ `CoverSupport.kt`（:107-124） |
| 19 | REG-8 | 封面搜索弹窗绑定期间遮罩/返回/关闭一律禁用；NextMoe 归属标注条件放宽 | 有意交互变化（BK-56，修复半持久态） | 真机确认弱网长下载时有等待提示（breathing 文案仍在） | `GameScreen.kt` · BK-56 注释与守卫（:1577-1596/:1601-1604/:1625-1631） |
| 20 | REG-9 | 共享 UI 组件行为变化（NoIndication、按钮折行、退场延后交付 + ON_PAUSE 兜底、closing 守卫扩展等） | 规范内对齐或缺陷修复，带默认值加法 | 按 AGENTS.md 弹窗验收矩阵真机过一遍普通/增强两档（下次真机验收批次） | `AppBottomSheet.kt`（:27-30）/ `AppAlertDialog.kt`（:147-284）/ `AppNavItem.kt`（:79-97/:106-108）/ `SheetMotion.kt` · commitClose / `GameOverlayMotion.kt` |
| 21 | REG-10 | 快捷方式/裁剪位图管线内存回收收紧、本地文件回退限 file:/空 scheme | 等价性论证成立（content:// 回退本就无意义） | 无需整改；真机冒烟覆盖快捷方式创建/更新 | `GameShortcutImageProcessor.kt`（:20-108）+ `GameShortcutManager.kt`（:71-110） |
| 22 | REG-11 | GameRecordCodec 追加 13-15 字段 + vndbId clean()，旧新双向兼容 | BK-58 论证成立 | 无需整改 | `GameRecordCodec.kt`（:16-63）+ `GameRecordCodecTest` |
| 23 | REG-12 | Room v3→v6 两段迁移逐字段核对一致（REG 亲读 + 主控采纳），破坏性例外未扩大 | 符合 AGENTS Room schema 规则；JVM+设备双覆盖 | 真机跑一次 v3→v6 升级（下次真机验收批次） | `GameLibraryDatabase.kt` · MIGRATION_3_4/4_5/5_6（:58-77/:92-96）+ `app/schemas/…/{4,5,6}.json` + `GameLibraryDatabaseMigrationTest` / `GameLibraryDatabaseDeviceMigrationTest` |
| 24 | REG-13 | DAO updateCover 5→8 参，唯一调用链写入取 cacheLock 内权威行、七列无变化不落库 | 差量保护闭环 | 无需整改 | `GameLibraryDao.kt` · updateCover（:38-54）+ `GameLibraryFacade.kt` · updateGameCoverMetadataWithRevisions（:216-272） |
| 25 | REG-14 | 删除管线迁 core/game + liveTarget 增强，步骤与旧实现逐一等价 | 等价 + 补清增强；GameDeletionCleanupTest 覆盖 | 真机回归删除场景（与 LOGIC-7 修复项同批验证） | `GameDeletionCleanup.kt`（b14b7f3f :20-50；R11fix 后全部步骤纳入 NonCancellable）+ `MainLibraryViewModel.kt`（:292-306）+ `GameDeletionCleanupTest` |
| 26 | REG-15 | 三语言 strings 1157 键 parity + 31 死键清理（引用零命中） | ET 解析比对成立 | 无需整改 | `values/values-en/values-ja/strings.xml`（三语言 parity 由 `python tools/check-hardcoded-ui-strings.py` 门禁持续复核） |
| 27 | REG-16 | Manifest/构建：launcherLabel 占位符、Bangumi 回调组件、configChanges、backup 排除 | 默认值下行为不变 | 冒烟默认构建 + 一次 Bangumi OAuth（下次真机验收批次） | `AndroidManifest.xml`（:42/:136-145/:155-160/:179/:192/:195）+ `app/build.gradle.kts`（:122-126/:343-346）+ `backup_rules.xml`/`data_extraction_rules.xml` |
| 28 | REG-17 | SecureAuthStore/Hikarinagi 存储协议兼容、PERMISSION 残留一次性归位 | 兼容性逐项论证成立 | 无需整改 | `SecureAuthStore.kt`（:110-133/:197-260）+ `HikarinagiAuthStore.kt`（:188-205/:234-247/:412-441） |
| 29 | REG-18 | 既有调用点 API 变更无悬空引用（bindCandidate 删除、更名、类型变更均同步） | git grep 亲核成立 | 无需整改 | `git diff origin/main..HEAD --name-only` 134 文件面对照调用点（bindCandidate/updateGameCoverMetadata/gameUpdates/startAuthorization/CoverErrorMessages.coverErrorResource） |
| 30 | REG-19 | GitHubUpdateChecker 限长 + CE 透传 + errorStream 收尾 | 行为等价；仍在存量偏差清单内未新增并行风格 | 无需整改 | `GitHubUpdateChecker.kt`（:122-125/:146-160）+ AGENTS.md §错误处理协议存量偏差清单 |
| 31 | REG-20 | 授权读取下沉 IO（produceState + 首快照前整弹窗不渲染） | 风险极低，注释已声明目标 | 真机确认来源选择弹窗无首帧空白闪烁 | `GameScreen.kt`（:1389-1401）+ `CoverScraperSettingsActivity.kt`（:163-178）+ `HikarinagiAuthStore.kt`（:188-205） |
| 32 | REG-21 | 批量刮削收尾改 refreshFromStorage + acknowledgedEventId 提示去重 | 等价或更稳 | 真机验收「刮削完成瞬间改名/删游戏」场景 | `MainLibraryViewModel.kt`（:109-152/:292-306）+ `CoverScrapeTaskManager.kt`（:55-56/:118-125） |

---

## 六、正向确认要点（主控抽核 + 五视角亲核汇总）

1. **Room schema v4→v6**：MIGRATION_3_4/4_5/5_6 与 schemas 4/5/6.json 逐列一致（三绑定列 TEXT 可空无默认、无新索引），破坏性回退未扩大（仅 downgrade + from(1)），JVM（GameLibraryDatabaseMigrationTest）与设备测试双覆盖（logic/arch/reg 三视角独立核对一致）。
2. **Bangumi 读回分档**：404+JSON 归一未收藏、非 JSON 404/传输失败走网络档不误报、requireJsonBody 防 2xx 空体、读 401 → needsReauth（同值短路 + token 匹配守卫防跨号误标）、读 403 独立 permission_error 位、读 2xx（含未收藏）双清、SECURE_STORAGE_ERROR 哨兵不可被覆盖。
3. **四源写路径单侧约束**：写失败不回写授权态、写成功不清档（BK-23）；唯 Bangumi 写 401 落档（BK-05A）差异在 SPEC/ARCHITECTURE 已如实记载。
4. **授权状态机与单飞**：三 OAuth 源 markNeedsReauthLocked 同值短路、saveTokensLocked 单 editor 原子、credential/session 双代次、SingleFlightRefresh 谓词循环 + adoptWaiter 规则自洽、网络 IO 在临界区外、三源独立 refreshGate。
5. **NextMoe 环回 OAuth**：PKCE S256 + state/nonce 常量时间双校验、伪造请求不终止监听（ignored 上限 32）、flowId 双重防串台、refresh_token 轮换经双匹配守卫、300s 超时 → CANCELLED。
6. **TTL 缓存与并发护栏**：缓存键含四源绑定 id + 逐源 ready 位 + localeTag + 逐源 sessionEpoch；仅全源成功入缓存；opEpoch/refreshNonce 防「旧在途回读覆盖刚确认写入」；写/绑/解绑/删除四处均 invalidate。
7. **绑定回填三层防护**：DB 侧 mergeScrapedCover + 任务快照同锁原子 + UI 侧 Reducer，先绑后解再写回不复活（BK-40）。
8. **core/net 中性层与协议**：不依赖业务域包；无新增包级依赖环（arch 自写 Tarjan SCC 对 HEAD/origin/main 双跑证实）；本轮新代码零 R import、零硬编码 CJK、CE 透传全改动点覆盖、4MB 限长、URL 日志脱敏。
9. **备份域**：backup_rules.xml / data_extraction_rules.xml 双场景排除四个 *_auth_secure prefs 与 webdav，与清单文档逐项一致。
10. **三语言与资源**：values/values-en/values-ja 各 1157 键完全齐平、占位符全等；`play_error_*` 18 键齐备；ic_sheet_play_status/ic_visibility 命名合规且被引用。
11. **实机验收快照的整体性**：b14b7f3f 作为实机验收通过后的整体快照，其 NextMoe 集成、r5–r9 修复与 Bangumi 读回修复已在真机验收覆盖；本轮全部新增 JVM 测试（app +350 例、engine +35 例）实跑通过。

---

## 七、审计边界声明

1. **盲审隔离**：五视角报告均声明未读取 `docs/play-status-audit-*.md`、`docs/reviews/` 既有材料与其他视角输出；本主控仅读五份视角报告 + 四个 scope 文件 + AGENTS.md，及风格样例（main 工作树 `docs/reviews/` 既有报告，非本轮材料）。五视角对同一代码面独立取证的收敛点（CONTEXT.md 术语、uid 兜底）可信度高。
2. **报告落盘位置说明**：`docs/reviews/README.md`（a4f4280c 后）曾声明该目录「仅用于存放 PR 审核模板、不得新增 review」。R11fix 修复轮按任务口径把本报告以 `docs:` 入库，并把该 README 条款修订为「允许 PR 审核模板 + 分支审计循环轮次审计报告（`codex-<分支>-audit-<日期>.md`）」两类，评审证据材料（日志/截图/命令输出）的禁止条款不变——理由：轮次报告是分支开发过程的正式裁决文档与 P3 跨轮次台账（本报告第五节 5.2/5.3 即 32 条维持观察项的触发条件与复核入口台账），非「PR 评审证据材料」；修订说明已写入 docs/reviews/README.md 条款修订段。
3. **测试基线时效**：`check-app-jvm-obs.log` 记于 2026-11-14，早于审计基线 origin/main 前移至 709687a3 与快照提交 b14b7f3f；本轮按「以 obs 失败清单为准 + 对 6 条新增失败逐一取证为基线既有」完成对账（4.1 节）。下一轮修复轮复跑时应以本轮 `check-app-jvm-1117.log` / `check-engine-jvm-1117.log` 的 49 条清单为新基线。
4. **本机环境项**：`local.properties` 的 PropertyEscape error 使 `:app:lintDebug` 以 BUILD FAILED 收尾（变更源码 error=0 门槛不受影响）；如需 lint 以退出码 0 收尾，把 `sdk.dir` 改回 `D\:/android-sdk` 转义形态即可（本机文件，不属于分支整改范围）。`tools/__pycache__/` 未跟踪目录为审计脚本运行残留。
5. **未执行项**：真机/实服验证（Bangumi uid 直用服务端接受度实机 curl、Hikarinagi scope 升级 403 自愈、v3→v6 真机升级、VNDB 全库刮削耗时、弹窗矩阵真机回归等第六节真机挂账项）、androidTest 设备套件、`:app:assembleRelease`。

---

## 八、修复轮处置终稿（R11fix，2026-11-17，修复代理）

> 本节为第五节待裁决清单的处置终稿（报告即台账）。M1..M5 与 P3 修复 11 条全部落地；维持观察 32 条全部确认维持（触发条件与复核入口已誊入 5.2 表格新增列）。逐项证据与提交 SHA 见修复摘要 `local-notes/audit-bangumi-vndb-status-20261111/round-r11fix/fix-summary-r11fix.md`。

### 8.1 P1/P2 处置

| 编号 | 处置 | 落地要点 |
| --- | --- | --- |
| M-1（P1） | **文档改写闭环** | CONTEXT.md「游玩状态（同步）」改为四源读写模型（Bangumi 经 GET 单条收藏读回、404 归一未收藏、读 401/403 分位）；CHANGELOG.md「新增」节只写括号改写为读回语义并注明历史措辞被「修复」节取代；「修复」节历史注记交叉引用同步收敛（消除同块三处矛盾） |
| M-2（P2） | **代码整改（方案 b + 身份门）** | `isBangumiIdentityResolved` 纯映射（空白/数字 uid 占位 = 未解析）；`ensureIdentityLoadedForResult` 带结果回查变体（uid 占位不再永久跳过，失败可重试，成功幂等落盘收敛）；`readBangumiStatus` 读回前置身份门——身份未解析时先补一次 /v0/me 回查，仍失败按新枚举 `BANGUMI_IDENTITY_UNRESOLVED`（新键 `play_error_bangumi_identity_unresolved` ×3 语言）挂起读回，**不以 uid 发起读回、不把 404 归一「未收藏」+ 双清**（假自愈消除）；未设用户名账号（回查确认）保留 uid 唯一合法形态；`:330-332` 与 `:117-121` 两处 spec 转述矛盾收敛（统一引「设置了用户名之后无法使用 UID」）；SPEC.md/持久化清单标注读回身份门。JVM 用例：PlayStatusBangumiReadBackTest 4 例（身份完全未解析零网络挂起 / uid 占位回查失败按身份未解析挂起且不双清 / 回查恢复以真实用户名读回并双清 / 未设用户名账号保留 uid 路径）+ OAuthExchangeAndIdentityTest 2 例（isBangumiIdentityResolved 纯映射 / 回查失败可重试恢复后零网络） |
| M-3（P2） | **§8 补登记闭环** | ARCHITECTURE.md §8 已知偏差清单补录 VNDB token 校验/权限解析落 core/status 一句（与 §7 内联论证互相援引；未迁 core/auth，取侵入最小路线） |
| M-4（P2） | **限定语 + 验收用例闭环** | SPEC.md §6 候选回填句补「关键词搜索命中的 NextMoe 候选同样携带自身作品 ID 与标题并参与缺失回填——该命中未经人工验证，写游玩状态前以弹窗显式确认为准」；CoverScrapeMergeTest 新增 2 例（关键词命中回填 nextmoe_id 与标题 / 与既有绑定冲突时已有绑定优先）；代码回改不做（护栏与文档论证成立） |
| M-5（P2） | **按【不可追溯整改】标注闭环** | 既有 10 条提交不重写（快照拆分/全链 reword 不可行，理由见第三节）；本修复轮起恢复提交惯例——新提交统一中文描述 + 每提交聚焦一类修改（见 8.3 提交清单） |

### 8.2 P3 修复 11 条处置

| # | 来源 | 处置 | 落地要点与测试 |
| --- | --- | --- | --- |
| 1 | LOGIC-3 | **修复** | 新纯映射 `bangumiWriteStatusToError(status, hasJsonBody)`：写路径 404+JSON 体 → 新枚举 `BANGUMI_SUBJECT_NOT_FOUND`（新键 `play_error_bangumi_not_found` ×3 语言），非 JSON 404/其余维持网络档；读路径 404 归一前置不受影响。PlayStatusMappingTest 镜像用例 1 例 |
| 2 | LOGIC-4 | **修复（文档）** | `docs/应用持久化数据清单.md` bangumi_auth_secure 行 last_error 措辞修正：删除「个人令牌校验拒绝」、注明「个人令牌粘贴校验失败仅弹窗展示、不落盘」 |
| 3 | LOGIC-5 | **修复** | `playStatusDrawerSummary` 四行来源名统一走 `coverSourceTitle(context, …)` 本地化取法（缓存键含 localeTag，本地化安全）；VNDB/Hikarinagi/Bangumi 无本地化键时输出不变，纯口径统一 |
| 4 | LOGIC-6 | **修复** | NextMoe 写纵深守卫命中（弹窗打开后回读失败快照漂移窗口）改为 Limit 提示（新键 `game_play_status_nextmoe_read_pending` ×3 语言）并关闭 pickSource，与空绑定分支同口径，不再静默 return |
| 5 | LOGIC-7 | **修复** | `cleanupDeletedGame` 全部清理步骤（含 clearSaveSync）统一纳入 NonCancellable（删除已确认语义）；GameDeletionCleanupTest 新增取消窗口用例（调用方中途取消仍全链完成 + CE 显式抛出语义保持） |
| 6 | ARCH-7 | **修复（文档）** | docs/README.md「验收 / 历史记录」节补一条汇总条目收录九份 `docs/play-status-audit-*.md` 并声明快照时效（每份仅对其标注 HEAD 有效） |
| 7 | ARCH-9 | **修复（注记路线）** | `DeviceStorageAndAuthCryptoTest.kt` 文件头 KDoc 补「经封面设置页 UI 验证存储与授权加密」的归位理由注记（不迁包，零风险） |
| 8 | ROB-1 | **修复（结构性收敛）** | 取证发现 catch 分支 `verifiedResult ?: run{…}` 的求值结果**其实已随 try 表达式赋回 parsedResult**（审计描述「未赋回」不成立，行为本与 KDoc 一致）；仍按报告给出的「或命中即 return」变体重构两个 catch 分支为显式 `if (verifiedResult != null) return verifiedResult`，使保留语义不依赖 try 表达式求值链。NextMoeAuthServiceTest 新增连接异常断开（RST→IOException）后合法回调照常唤醒用例（对偶分支钉住；close-throws 子路径无注入缝隙，经结构性收敛+取证说明，见修复摘要） |
| 9 | ROB-4 | **修复** | `GameLibraryFacade` 新增 `forgetBindingRevisions`，`removeGame`/`removeRootAndGames` 移除游戏行后顺带清空对应 URI 修订号条目；重加同 URI 从零代次、与先绑后解护栏不冲突。新增 GameLibraryFacadeTest 2 例（单删清理 + 重加从零代次；removeRootAndGames 走同一助手，根读取经 Room 不可 JVM 测，注明） |
| 10 | STY-4 | **修复** | AGENTS.md 错误处理协议节借用枚举与 PlayStatusErrors.kt BK-37 KDoc 均补 `ui/auth/BangumiAuthMessages.kt` |
| 11 | STY-7 | **修复** | PlayStatusService NextMoe 系统一 `nextMoe*` 前缀（nextmoeThrottle→nextMoeThrottle、nextmoeRequestWithToken→nextMoeRequestWithToken）；nextMoeTokenRefreshFailureToError/isNextMoeWorkState/nextMoeNonSuccessToError 补同族 KDoc；:1124-1125 英文注释改中文；纯改名零行为 |

### 8.3 提交清单（R11fix，未 push）

按域逻辑拆分提交（类型前缀小写英文 + 中文描述），SHA 与门禁结果见台账 LOOP-STATE.md 的 R11fix 行与本节同步记录的 fix-summary；P3 维持观察 32 条无代码动作。

### 8.4 维持观察 32 条确认

- 32/32 全部确认**维持观察**：ARCH-3/4/5/6/8、ROB-2/5/6/7、STY-5/6/8（12 条）与 REG-2..REG-21（20 条）；无改判、无顺带闭环。
- 触发条件逐条誊入 5.2 两张表格「触发条件」列（原有内容保留），复核入口逐条誊入新增「复核入口」列（稳定锚点 = 文件 · 符号，括注 b14b7f3f 行号）。
- 真机类触发条件（REG-2/4/5/7/8/9/12/14/16/20/21 等）汇入下次真机验收批次执行。
