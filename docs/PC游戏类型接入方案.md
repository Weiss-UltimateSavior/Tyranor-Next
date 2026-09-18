# PC 游戏类型接入方案（手动添加 + 外置 Winlator 启动）

> 状态：**已实施（P1–P4）**；实现说明见文末「实施记录」
> 目标：新增 `EngineType.PC`，用于**手动添加**任意 Windows PC 游戏（不参与目录扫描），在游戏页顶部栏提供添加入口（弹窗选择目录 + 指定启动 exe），启动经外置 Winlator；PC 游戏**不纳入存档管理与引擎配置**，但支持「启动文件」切换（与 YU-RIS 一致）。
> 关联：`docs/外置主机模拟器跳转接入方案.md` §12（YU-RIS/Winlator）、`ExternalEmulatorLauncher`、`WinlatorContract`。

---

## 1. 需求与范围

| 项 | 要求 |
|---|---|
| 入库方式 | **不扫描**：游戏页顶部栏新增图标 → 弹窗内选择目录 + 从目录检索 exe 并指定启动 exe → 确认加入游戏页 |
| 启动方式 | 外置 Winlator（复用 YU-RIS 的 `dir_path` + 相对 `exe_path` 协议） |
| 存档 | 不纳入统一存档管理（`GameSaveManager` 不支持、详情页隐藏存档管理） |
| 引擎配置 | 无（不新增 `EngineSettingsKind`；单游戏设置页仅展示信息卡） |
| 启动文件切换 | 需要：游戏详情抽屉「启动文件」可重新选择 exe（复用现有选择器） |
| 展示 | 游戏页正常卡片（标题=目录名，可改名）、引擎徽标 `PC`、封面可搜索/本地 |

**非目标（一期不做）**：exe 子目录递归检索、PC 游戏的进程/参数配置、自动识别 Linux/其他平台游戏、PC 游戏的批量导入。

---

## 2. 现状与可复用资产

| 资产 | 位置 | 复用方式 |
|---|---|---|
| Winlator 跳转协议 | `core/engine/external/WinlatorContract.kt` | 直接复用（`dir_path`、相对 `exe_path`、`confirm`、`launch_id`） |
| 跳转器 | `ExternalEmulatorLauncher.launchWinlator(...)` | 直接复用 |
| 目标注册表 | `ExternalEmulatorRegistry` / `EmulatorTarget` | 扩展「一个目标服务多个引擎」 |
| exe 候选过滤/排序 | `core/game/launch/YurisLaunchFiles.kt` | 泛化为 `WindowsLaunchFiles`，新增 PC 分支 |
| 启动文件选择器 | `ui/game/GameScreen.kt` `LaunchFileDialog` + `EngineLauncher.listLaunchFiles/currentLaunchFileName` | 增加 PC 分支并放开抽屉入口条件 |
| 手动条目写入 | `GameLibraryFacade.updateGames` + `MainLibraryViewModel` FIFO 命令队列 | 新增「添加手动游戏」命令 |
| SAF 目录选择 + 持久授权 | `SettingsScreen.kt` 扫描目录添加（`OpenDocumentTree` + `takePersistableUriPermission`） | 弹窗内目录选择照此实现 |
| 顶部栏 | `ui/common/AppTopBar(trailing = ...)` | 在 `trailing` 增加图标 |
| 弹窗规范 | `ui/common/AppAlertDialog`（项目唯一弹窗） | 添加弹窗使用 |

---

## 3. 关键决策

1. **数据模型零迁移**：`uri` = 所选目录的 SAF tree URI；`engine = PC`；`launchTarget = "DIR"`；**`launchFile` = 添加时指定的 exe 文件名**（相对目录）。不加新字段、不改 Room 结构（`launchFile` 已存在）。
2. **启动 exe 解析沿用 YU-RIS 语义**：`launchFile` 优先，否则自动挑（目录名匹配 → 体积最大 → 名称）。添加时必选 exe，保证首启即用；之后可在「启动文件」里切换。
3. **一个 Winlator 目标服务两个引擎**：`EmulatorTarget` 的 `engine: EngineType` 改为 `engines: List<EngineType>`（YURIS + PC），`forEngine` 命中任一即可；**外置跳转支持弹窗仍只列一次 Winlator**（避免重复条目）。
4. **重扫保护（关键）**：`EngineScanner.rescanLibrary` 当前以扫描结果**重建**整个游戏列表（`activeScanned.map{...}`），会把手动 PC 条目删掉。必须在合并时保留 `engine == PC` 的现存条目（不依赖扫描根、不做 `isGameUnderRoot` 过滤）。
5. **入队写入**：添加动作走 `MainLibraryViewModel` 的命令队列（与扫描/删除串行），避免「添加后立刻重扫」互相覆盖；UI 立即发布结果（与 `acceptPersistedGames` 同模式）。
6. **存档/设置显式排除**：`GameSaveManager.resolveSaveLocation` 增加 `PC`（unavailable）；`shouldShowSaveManagement(PC) = false`；`PerGameSettingsScreen` 增加 PC 信息卡。
7. **引擎页展示（可选但建议）**：`PC` 进 GAL 分组，描述「手动添加的 Windows 游戏，经外置 Winlator 运行」；点击行进入「PC 引擎列表」弹窗（仅 Winlator，与 YU-RIS 同构，用于查看安装状态/跳转下载）。
8. **exe 检索范围**：目录**根层** exe（过滤 settings/unins/setup/install/update/patch/crack/keygen；排序：目录名匹配 → 体积降序 → 名称）。子目录/手动输入列为二期。
9. **权限**：添加时用 SAF 列表（不需要权限）；启动时沿用 `requestAllFilesAccessIfNeeded` 获取真实路径（Winlator 需要）。

---

## 4. 详细改动清单

### 4.1 core：类型与跳转

| 文件 | 改动 |
|---|---|
| `core/engine/EngineType.kt` | 新增 `PC("PC")` |
| `core/engine/external/EmulatorTarget.kt` | `engine: EngineType` → `engines: List<EngineType>`（默认单元素，构造兼容）；新增 `supports(engine)` |
| `core/engine/external/ExternalEmulatorRegistry.kt` | Winlator 条目 `engines = listOf(YURIS, PC)`；`forEngine` 遍历匹配；聚合弹窗仍单条 |
| `core/game/launch/WindowsLaunchFiles.kt`（由 `YurisLaunchFiles.kt` 泛化） | 保留 `candidates(File)`；新增 `candidatesOf(entries: List<Pair<String, Long>>, dirName)`（供 SAF/DocumentFile 用）；`resolveExe/resolveExeName/currentLaunchFileName` 增加 PC 分支（行为同 YURIS）；保留旧名调用点或一次性改名（`EngineLauncher` 内） |
| `core/game/launch/EngineLauncher.kt` | `listLaunchFiles`/`currentLaunchFileName` 的 `when(engine)` 增加 `PC`（exe 候选）；`supportedEngines` 增加 `PC`；启动分流无需改（`ExternalEmulatorRegistry.forEngine(PC)` 已命中 Winlator） |
| `core/game/save/GameSaveManager.kt` | `resolveSaveLocation` 的「不支持」分支加入 `EngineType.PC` |

### 4.2 core：扫描保护与手动添加

| 文件 | 改动 |
|---|---|
| `core/game/scan/EngineScanner.kt` | `rescanLibrary` 合并时保留手动条目：`val manual = currentGames.filter { it.engine == EngineType.PC && activeScanned.none { s -> s.uri == it.uri } }`，`updateGames { manual + activeScanned.map{...} }`；`cleanupDeletedGame`/`GameRootMatcher` 不涉及（PC 无扫描根） |
| `ui/main/MainLibraryViewModel.kt` | 新增 `fun addManualGame(game: ScanGame, onResult: (Boolean) -> Unit)`：校验 uri 未存在（存在则返回 false/提示）→ 立即 `acceptGames` 发布 → `enqueuePersistence` 写库（`updateGames { it + game }`） |
| `core/game/library/GameLibraryFacade.kt` | 复用 `updateGames`（无需改动）；如需「按 uri 查询」用 `loadGames` |

### 4.3 UI：添加入口与弹窗（游戏页）

| 文件 | 改动 |
|---|---|
| `ui/game/GameScreen.kt` | ① `AppTopBar.trailing` 新增图标（新增 `R.drawable.ic_game_add_pc`，或先复用现有图标）；② 新增「PC 游戏添加弹窗」状态与组件 `PcGameAddDialog`（`AppAlertDialog`）：目录行（点击 `OpenDocumentTree`）→ 候选 exe 列表（单选，`AppNavItem`/Radio 行）→ 标题行（默认目录名，可编辑，可选）→ 「添加」；③ 抽屉「启动文件」条件增加 `PC`；④ `EngineType.coverColor()` 增加 PC 占位色 |
| 新增 `ui/game/PcGameAddDialog.kt`（或置于 GameScreen 内） | 弹窗状态机：`idle → picking → loading → ready(entries, selected) → adding → done`；错误态（目录不可读/无 exe/已存在）就地提示 |
| `ui/engine/EngineScreen.kt` | `engineTabOf` → GAL；`engineDescription` 增加 PC；Winlator 专属弹窗条件由 `engine == YURIS` 泛化为「目标为 WINLATOR_EXTERNAL」 |
| `ui/settings/PerGameSettingsScreen.kt` | 新增 `EngineType.PC` 分支：信息卡（说明经 Winlator 运行、启动文件可切换、无存档管理） |
| `ui/game/GameScreen.kt`（shouldShowSaveManagement） | `PC` 保持 `false`（在现有 `ExternalEmulatorRegistry.forEngine != null` 判据下自动成立，需单测锁定） |

### 4.4 文案（三语言严格一致）

| 键 | zh | ja | en |
|---|---|---|---|
| `game_add_pc_content_description` | 添加 PC 游戏 | PC ゲームを追加 | Add PC game |
| `pc_add_dialog_title` | 添加 PC 游戏 | PC ゲームを追加 | Add PC game |
| `pc_add_pick_directory` | 选择游戏目录 | ゲームフォルダーを選択 | Choose game folder |
| `pc_add_directory_empty` | 未选择目录 | フォルダー未選択 | No folder selected |
| `pc_add_exe_label` | 启动程序 | 起動プログラム | Executable |
| `pc_add_exe_empty` | 目录内未找到可启动的 .exe | 起動可能な .exe が見つかりません | No runnable .exe found |
| `pc_add_loading` | 正在读取目录…… | フォルダーを読み込み中… | Reading folder… |
| `pc_add_duplicate` | 该目录已在游戏库中 | このフォルダーは既に追加済みです | This folder is already in the library |
| `pc_add_failed` | 添加失败，请重试 | 追加に失敗しました | Failed to add, please retry |
| `engine_desc_pc` | 手动添加的 Windows 游戏，经外置 Winlator 运行 | 手動追加の Windows ゲーム。外部 Winlator で実行 | Manually added Windows games, run via external Winlator |
| `engine_settings_pc_hint` | 该游戏经外置 Winlator 运行；启动程序可在游戏详情「启动文件」中切换，无存档管理与引擎参数。 | このゲームは外部 Winlator で実行されます。起動プログラムはゲーム詳細の「起動ファイル」で変更できます。セーブ管理とエンジン設定はありません。 | Runs via external Winlator. Change the executable via "Launch file" in game details; no save management or engine options. |

（图标资源：如无专门图标，一期先复用 `R.drawable.ic_sheet_launch_file`，后续替换。）

---

## 5. 交互细节

### 5.1 添加流程

```
[游戏页顶栏 + 图标]
  → AppAlertDialog:
      目录行：未选择 → 点击打开 SAF 目录选择器（OpenDocumentTree + takePersistableUriPermission）
      → 选中后异步列目录：DocumentFile.listFiles() → 过滤 *.exe（排除 settings/unins/setup/install/update/patch/crack/keygen）
        → 排序：目录名匹配 → 体积（DocumentFile.length()）降序 → 名称
        → 弹窗内单选列表（默认选中第一名）
      → 标题输入（默认目录名，可空=目录名）
      → [取消] [添加]（未选目录/exe 时禁用）
  → 添加：ScanGame(title, uri=treeUri, engine=PC, launchTarget=DIR, launchFile=选中exe, coverUri=本地封面可选)
      → MainLibraryViewModel.addManualGame（FIFO 队列 + 立即发布）
      → 成功 Toast/关闭；重复目录 → `pc_add_duplicate` 就地提示
```

### 5.2 启动

- `EngineLauncher.launchInternalChecked` → `ExternalEmulatorRegistry.forEngine(PC)` 命中 Winlator → `launchYurisViaWinlator`（同一函数，建议改名为 `launchWindowsViaWinlator`）→ 解析真实路径 + all-files → `WindowsLaunchFiles.resolveExeName(game, path)`（`launchFile` 优先）→ `launchWinlator(dir, exe)`。
- 目录解析失败/无 exe → 复用现有 `GameDirUnresolved`/`YurisExeMissing` 类型化失败与文案（`launch_yuris_exe_missing` 文案需泛化为「未找到可启动的 .exe」）。

### 5.3 启动文件切换

- 抽屉「启动文件」对 PC 显示；`LaunchFileDialog` 复用 `EngineLauncher.listLaunchFiles`（PC → exe 候选）与 `currentLaunchFileName`；确认写 `launchFile`（`onGameUpdated` → VM `replaceGame`）。
- 若所选 exe 被删除：启动时 `resolveExe` 回退自动挑选（现有语义），并在失败时提示。

### 5.4 存档与设置

- 详情抽屉不显示「存档管理」（`shouldShowSaveManagement(PC)=false`）。
- 单游戏设置页 PC 分支仅信息卡（同 PSP/Switch 结构）；不显示任何可调项。

---

## 6. 边界与风险

| 风险 | 处理 |
|---|---|
| **重扫删除手动条目**（当前 `rescanLibrary` 重建列表） | §4.2 的保留逻辑 + 单测（扫描结果不含 PC 时保留、含同 uri 时以扫描结果为准） |
| 目录在可移动存储 | 添加时 SAF 可列；启动时真实路径解析失败 → 提示（与 YURIS 同） |
| 目录/ exe 被删除或改名 | 启动失败提示；「启动文件」可重新选择（候选列表为空时提示） |
| 重复添加同目录 | 添加前按 uri 查重（`acceptGames`/`loadGames`），提示 `pc_add_duplicate` |
| exe 在子目录 | 一期不支持；备注「启动文件仅列根目录 exe」，二期加「浏览子目录/手动输入相对路径」 |
| 封面 | 不自动抓取；用户可「搜索封面/修改封面」（现有能力） |
| Winlator 未安装/版本旧 | 复用现有 `package_not_installed` / `external_launch_unsupported` 提示与下载引导 |

---

## 7. 实施清单（按文件）

1. `EngineType.PC`；`EmulatorTarget.engines` + 注册表；`WindowsLaunchFiles`（PC 分支）
2. `EngineLauncher`（supportedEngines/listLaunchFiles/currentLaunchFileName）+ `GameSaveManager`（PC 不支持）+ `shouldShowSaveManagement`
3. `rescanLibrary` 保留 PC 条目 + VM `addManualGame`
4. `PcGameAddDialog` + 顶栏图标 + 抽屉启动文件条件 + `coverColor`
5. `EngineScreen`（GAL/描述/Winlator 弹窗泛化）+ `PerGameSettingsScreen` PC 分支
6. 三语言文案；测试（exe 候选、重扫保留、存档隐藏）；README/CONTEXT 更新

---

## 8. 测试与验收

**单测**
- `WindowsLaunchFilesTest`（原 `YurisLaunchFilesTest` 扩展）：PC 分支候选过滤/排序、`launchFile` 优先
- `EngineScannerManualGameTest`：重扫后 PC 条目保留；被扫描命中的同 uri 用扫描结果
- `GameCardItemKeyTest`：`shouldShowSaveManagement(PC) == false`
- `EngineType`/注册表：`forEngine(PC)` 命中 Winlator 且聚合弹窗不重复

**真机验收**
1. 顶栏图标 → 选目录 → 列出 exe（默认选中目录名匹配/最大）→ 添加 → 游戏页出现 PC 卡（徽标 PC）
2. 启动 → Winlator 确认框（显示挂载盘符）→ 进入游戏
3. 详情「启动文件」切换另一个 exe → 再次启动生效
4. 详情无「存档管理」；单游戏设置仅信息卡
5. 扫描/重扫后 PC 条目仍在；同目录重复添加被拒
6. 目录被删除/改名 → 启动提示可读

---

## 9. 里程碑

| 阶段 | 内容 | 预估 |
|---|---|---|
| P1 | 类型/注册表/exe 候选泛化 + `EngineLauncher`/存档排除 | 0.5 天 |
| P2 | 重扫保护 + VM 手动添加命令 + 单测 | 0.5 天 |
| P3 | 顶栏入口 + 添加弹窗 + 启动文件切换 + 文案 | 1 天 |
| P4 | 引擎页/单游戏信息卡 + README/CONTEXT + 真机验收 | 0.5 天 |

---

## 10. 实施记录（P1–P4）

- **类型与跳转**：`EngineType.PC("PC")`；`EmulatorTarget.engine: EngineType` → `engines: List<EngineType>`（保留单引擎便捷构造），Winlator 目标 `engines = [YURIS, PC]`；`forEngine` 用 `supports` 命中，聚合弹窗仍单条 Winlator
- **exe 解析**：`YurisLaunchFiles` 泛化为「名称+体积」候选（新增 `ExeCandidate` 与 `candidatesOf`，供 SAF 侧使用；`candidates(File)` 保持原行为）
- **启动**：`EngineLauncher.launchWindowsViaWinlator`（原 `launchYurisViaWinlator` 更名），PC 复用；`supportedEngines`/`listLaunchFiles`/`currentLaunchFileName` 增加 PC；抽屉「启动文件」对 PC 放开
- **存档/设置**：`GameSaveManager` 对 PC 返回不支持；`shouldShowSaveManagement(PC)=false`（经注册表自动成立，已加断言）；`PerGameSettingsScreen` 新增 PC 信息卡
- **重扫保护**：`EngineScanner.mergeScannedWithManual`（internal 纯函数）：重扫时保留未被扫描命中的 `engine==PC` 条目，同 uri 以扫描结果为准；`incrementalScan` 本身为「现有 + 新发现」，不受影响
- **手动添加**：`MainLibraryViewModel.addManualGame`（查重 + 立即发布 + FIFO 队列落库）；`PcGameAddDialog`（SAF 选目录 + 持久授权 → SAF 列根目录 exe → 单选 → 确认；无标题输入，标题=目录名；重复目录就地提示）；接入本地封面探测（`EngineScanner.applyLocalCover`）
- **入口**：游戏页顶栏新增图标（`R.drawable.ic_game_add_pc`，来自用户提供的 `数据.png`），位于搜索图标左侧
- **引擎页**：PC 进 GAL 分组 + `engine_desc_pc`；Winlator 系专属弹窗条件泛化为 `EmulatorLaunchStyle.WINLATOR_EXTERNAL`（YURIS/PC 共用）
- **文案**：三语言新增 12 键（`engine_desc_pc`、`engine_settings_pc_hint`、`game_add_pc_content_description`、`pc_add_*`）
- **测试**：`EngineScannerManualGameTest`（保留手动 PC / 同 uri 扫描优先 / 非 PC 不保护）、`GameCardItemKeyTest`（PC 隐藏存档管理）
- 校验：`assembleDebug`、`testDebugUnitTest`、`check-hardcoded-ui-strings.py`、`git diff --check` 通过；真机验证待设备连接后补做（§8 验收 1–6）
