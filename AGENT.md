# Tyranor Next — AGENT 规范

本文档是 AI Agent 在本项目内开发时必须遵循的统一规范。新增或修改代码前请先阅读，与既有实现保持一致。

> 领域术语：项目核心概念（引擎/游戏库/设置/封面/存档/界面规范等）的权威定义见 `CONTEXT.md`（仓库根目录）。开发与代码评审时若涉及上述领域词汇，以 `CONTEXT.md` 中的术语与 `_Avoid_` 意见为准，避免引入异名同义的新词。

## 文档工作流 Skills（docs/skills）

项目内置三类文档工作流 skill（与全局用户级 skill 同名，以本仓库版本为准），用于打磨设计与沉淀领域知识：

| Skill | 位置 | 用途 |
| --- | --- | --- |
| grilling | `docs/skills/grilling/SKILL.md` | 对计划/决策/想法进行逐轮质询，以「设计树 + 轮次」方式收敛到共享理解 |
| grill-with-docs | `docs/skills/grill-with-docs/SKILL.md` | grilling 之上叠加文档产出：质询过程中同步落 ADR 与术语表 |
| domain-modeling | `docs/skills/domain-modeling/SKILL.md` | 构建/打磨项目领域模型：维护 `CONTEXT.md` 术语表、撰写 ADR（格式见 `docs/skills/domain-modeling/CONTEXT-FORMAT.md` 与 `ADR-FORMAT.md`） |

约束：

- **质询纪律**：grilling 类流程仅在用户明确要求「打磨/质询/审方案」时启用；round 间必须等待用户回答，不得自行代答或跳过 frontier。
- **领域模型维护**：任何术语或领域决策的变更，须同步更新仓库根 `CONTEXT.md`；`CONTEXT.md` 只收项目特有术语（通用编程概念不得入表），定义保持一至两句。
- **ADR 三门槛**：只有当某决策同时满足「难逆转、无上下文费解、真实取舍」三点时，才创建 `docs/adr/` 下的 ADR 并引用对应 `ADR-FORMAT.md`；不满足则跳过，不强行沉淀。
- **文档与实践一致**：术语表或 ADR 与代码冲突时，以代码为事实源当场指出差异，而不是静默改写文档。

## 技术栈

- Android Jetpack Compose + Material 3

- Kotlin

- 底部导航结构：`app/src/main/java/com/tyranor/next/ui/main/MainScreen.kt`

- 页面按功能域归类到 `app/src/main/java/com/tyranor/next/ui/`

- 应用业务能力按三层架构归类到 `app/src/main/java/com/tyranor/next/core/`

## 三层架构目录规范

当前项目按职责划分为三层，依赖方向固定为：

```
界面 UI 交互层 -> 功能抽象层 -> 底层引擎层
```

### 1. 底层引擎层

- 目录：`engine/`

- 职责：KRKR/Kirikiroid、krkrsdl3、ONS、Artemis、Tyrano、SDL/Cocos/IJK、Native/JNI、引擎宿主 Activity、引擎资源、Native 插件底层加载。

- 规则：不得依赖 `com.tyranor.next.ui.*`；不得关心 App 页面、Compose 状态或列表展示。

### 2. 功能抽象层

- 目录：`app/src/main/java/com/tyranor/next/core/`

- 职责：游戏扫描、游戏模型、启动编排、封面抓取、存档管理、在线补丁、应用/引擎/单游戏配置、授权、后台更新。

- 规则：可以依赖 `engine` 模块；不得依赖 Compose（含 `androidx.compose.ui/foundation/material*` UI 组件，以及 `androidx.compose.runtime` 的状态原语与 `@Immutable` 注解）；不得把页面类作为普通业务依赖。
  全局可观察状态一律用 `kotlinx.coroutines.flow`（`StateFlow`）或纯数据载荷（`ThemeColorPayload` 模式）由 UI 层 `collectAsState` 订阅。

- 新增功能按域放入 `core/game`、`core/engine`、`core/cover`、`core/patch`、`core/settings`、`core/auth`、`core/updater` 等包。

### 3. 界面 UI 交互层

- 目录：`app/src/main/java/com/tyranor/next/ui/`

- 职责：Compose 页面、Activity 壳、弹窗、导航、顶部栏、搜索框、用户输入、加载态与错误态。

- 规则：只能调用功能抽象层；禁止直接 import `com.core`、`com.akira`、`com.yuri`、`org.tvp`、`org.libsdl`、`org.cocos2dx`、`bridge` 等底层引擎包。

- 页面按功能域放入 `ui/home`、`ui/game`、`ui/engine`、`ui/settings`、`ui/cover`、`ui/patch`、`ui/save`、`ui/auth`，公共组件放入 `ui/common`。

### 4. 资源归属

- `engine/src/main/assets`：底层引擎运行资源与共享引擎脚本源头。

- `app/src/main/assets/engine`：App 专属注入脚本；共享脚本由 Gradle 从 `engine/src/main/assets` 生成到 app assets。

- `engine/src/main/nativeplugins`：共享 Native 插件 so 源头。
- `app/src/main/nativeplugins`：插件 manifest 与 app-only 插件源头。

- 禁止在 app 与 engine 两边手工维护同一份二进制或共享脚本；构建脚本会检查重复源文件。

***

## 错误处理协议（core → ui）

功能抽象层（core）对 UI 的错误协议固定为以下三种，禁止再引入其它风格：

1. **类型化失败结果**：可预期的业务失败返回 sealed Result / 类型化错误 DTO，由 UI 映射文案。
   现有模板：`LaunchResult`（`core/game/launch/LaunchResult.kt`）、`ExternalEngineLaunchResult`、
   `UpdateCheckResult`、`CoverScraperService` 的 sealed 结果。
2. **抛类型化异常**：确实需要异常语义时，异常必须携带错误码而非文案（如
   `GameSaveException(SaveErrorCode, detail)`），UI 层按错误码映射 string 资源。
3. **无结果即 null / 空集合**；调用方容错自行 `runCatching`（仅限边角工具函数）。

硬性约束：

- **core 不得把本地化文案当错误消息/返回值**（禁止 `throw IOException(text(R.string...))`
  或 `return text(R.string...)`）；需要提示时返回错误码，文案组装一律上移 UI
  （映射文件放 `ui/<域>/` 或 `ui/common/`，如 `ui/common/LaunchErrorMessages.kt`）。
- 新增错误分支时优先扩展已有 sealed 类型/错误码枚举，不新增并行协议。
- 取消（`CancellationException`）不属于失败，必须原样向上传播。

***

## 页面顶部栏统一规范

所有页面（首页 / 游戏 / 书库 / 设置）的顶部栏必须统一。**一律使用统一组件
`com.tyranor.next.ui.common.AppTopBar`**，禁止任何页面手写该结构；占位页可复用
`com.tyranor.next.ui.common.PlaceholderPage`（内部同样走 AppTopBar）。

### 0. 统一入口（AppTopBar）

- 页面顶部栏必须调用 `AppTopBar(title, ...)`；标题、结构、取色由组件兜底。
- 参数约定：
  - Material 页面（默认）：不传 `background`/`contentColor`，组件默认 `colorScheme.background` / `colorScheme.onBackground`。
  - Miuix 风格页面（设置类 MiuixScaffold 的 `topBar` 槽）：传 `background = MiuixTheme.colorScheme.background`、`contentColor = MiuixTheme.colorScheme.onBackground`，并设 `contentWindowInsets = WindowInsets(0.dp)`。
  - 需要「色调切换」参与取色的页面：传 `background = PageGrey`、`contentColor = TextColor`。
  - 右侧图标用 `trailing` 槽传入 `TopBarIcon`；标题下方的折叠内容（如游戏页搜索框）用 `underTitle` 槽。
- 新页面/组件禁止再书写「背景层 + statusBarsPadding + 64dp + titleLarge Bold」结构。

### 1. 结构

- 顶部栏使用 **Column + Centre**，**不使用** Material3 的 `TopAppBar` / `Scaffold`。

- 页面整体由外层 `Column` 组装，顺序固定为：顶部栏 → 正文内容。

- **禁止在顶部栏放置任何返回按钮/图标**。返回统一依赖系统返回键/手势（`Activity` 默认 `finish()`），不要通过 `onBack` 参数下发返回回调。

### 2. 高度

- 标题区高度固定为 **64dp**。

- 顶部栏整体无需在 64dp 之外再叠加额外高度。

### 3. 标题

- 标题**居左**，水平内边距 `horizontal = 16.dp`，纵向居中。

- 标题字号使用 `MaterialTheme.typography.titleLarge`。

- 标题**必须加粗**：`fontWeight = FontWeight.Bold`。

### 4. 背景色

- 顶部栏**使用页面背景色** **`colorScheme.background`（不透明）**（`Modifier.background(colorScheme.background)`），标题与图标统一使用 `colorScheme.onBackground`。

- 禁止使用主题色 `primary` 作为顶部栏背景。

### 5. 状态栏

- 状态栏必须是**透明沉浸式**（`window.statusBarColor = Color.TRANSPARENT`），顶部栏的
  页面背景色向上延伸覆盖状态栏区域。

- 状态栏/导航栏图标使用**深色**（`SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)`），因为页面背景为浅色。

- 顶部栏需使用 `Modifier.statusBarsPadding()`，使标题内容避开状态栏但背景色连续延伸。

- 不要自行给状态栏设置非透明背景色。

### 6. 位置顺序

```
Column(fillMaxSize)                                // 页面根
├── Column(fillMaxWidth, background(background))    // 页面背景色容器（不透明）
│   ├── Column(statusBarsPadding)                    // 避开状态栏（背景延伸至状态栏）
│   └── Column(height 64dp, padding horizontal 16dp)  // 标题区
└── 正文内容
```

> 设置类页面若使用 `MiuixScaffold`，顶部栏在 `topBar` 槽中按同样规则实现：
> `Column(background(background)) { Column(statusBarsPadding) { Row(height 64dp, padding horizontal 16dp) { ... } } }`，
> 并设 `contentWindowInsets = WindowInsets(0.dp)` 避免系统 inset 再次叠加间距。

### 7. 顶部栏图标按钮

- 顶部栏右侧图标按钮**必须使用公共组件** `com.tyranor.next.ui.common.TopBarIcon`（排版规范对齐游戏页顶部栏），**禁止**自行用 `IconButton`/`Icon` 拼装。

- 组件排版：`Image` 渲染 + `size(34.dp)` 触控区 + `clip(RoundedCornerShape(6.dp))` 圆角 + `clickable` + `padding(4.dp)` 内边距，并 `ColorFilter.tint` 主题色；组件左侧自带 `padding(start = 2.dp)`，保证相邻图标之间留白统一。

- `tint` 由调用方传入当前主题色：Material 页面传 `MaterialTheme.colorScheme.primary`，Miuix 页面传 `MiuixTheme.colorScheme.primary`。

- 顶部栏图标一律使用 drawable 资源（`R.drawable.ic_*`）；需要新图标时在 `res/drawable` 新建资源（PNG 或 vector XML），禁止直接用 `Icons.*` 矢量图标充当顶部栏按钮。

***

## 跨页面一致性要求

- 所有页面共用 `PlaceholderPage`（或与之一致的结构），禁止各页面各自实现不同样式的顶部栏。

- 新增页面时：页面主体内容放入 `Column` 的正文区域即可，顶部栏保持相同。

- 顶部栏下方禁止放置页面说明/描述文案；正文区域应直接展示该页面的实际内容或列表。

- 页面切换动画必须保持统一：主 Screen 四个 Tab 间切换使用水平移动动画；其他独立 Activity 页面进入使用向上翻页动画，退出/返回使用向下翻页动画。

- 组件统一圆角数值为 **8dp**；列表项卡片、功能项卡片、弹窗等圆角组件统一引用 `theme/AppShapes.kt`：
  `AppComponentShape`（默认 8dp / 玻璃外观风格与悬浮导航一致 32dp）、Miuix 组件用 `AppComponentCornerRadius`、
  抽屉顶部用 `AppSheetTopShape`；禁止再散落圆角字面量。

- **圆角豁免**：液态玻璃导航（`ui/common/LiquidGlassNavigation.kt`）的栏体与导航项胶囊使用 **16dp**（8dp 基础上加大 8dp），为有意设计，不受 8dp 条款约束；其余组件不得援引此豁免。

- 所有弹窗背景必须为白色，且圆角必须使用统一圆角数值 **8dp**。

## 页面内容文字尺寸规范

页面内容（顶部栏除外）只允许使用**两种**文字尺寸，与引擎设置页保持一致：

1. `MaterialTheme.typography.titleMedium`（大字号，15sp，在 `theme/Type.kt` 中全局覆盖，Material 默认 16sp）—— 卡片头、对话框标题、列表项主标题（可加粗）。
2. `MaterialTheme.typography.bodyMedium` —— 正文、行值、辅助描述、按钮文字、空态/错误提示。

- **禁止**使用 `bodySmall` / `bodyLarge` / `labelMedium` / `labelLarge` / `headlineMedium` / `headlineSmall` 等其它排版尺寸。

- Miuix preference 组件标题默认用 `headline1`(17sp)，已在 `MiuixSettingsTheme` 中全局覆盖为 15sp（`defaultTextStyles(headline1 = TextStyle(fontSize = 15.sp))`），使其严格落入两档并匹配 `titleMedium`；不要自行在单行上改字号。

- 顶部栏标题不受此限制，仍用 `MaterialTheme.typography.titleLarge` Bold。

- **明文豁免**：首页快捷启动卡（`ui/home/HomeScreen.kt` 的 QuickLaunchCard）以封面模糊图 + 黑色压暗遮罩为背景、白色文字展示，卡片内游戏名使用 `MaterialTheme.typography.titleLarge` Bold、引擎名使用 `MaterialTheme.typography.headlineSmall` Bold，均不受两档制限制；该卡片其余文字仍遵循两档制。

***

## 搜索/输入框统一规范

全 App 的搜索过滤框与弹窗内单行文本输入框，必须统一使用公共组件
`com.tyranor.next.ui.common.AppSearchField`（`app/src/main/java/com/tyranor/next/ui/common/AppSearchField.kt`）。
**禁止**在页面内直接拼装 Miuix `SearchBar`/`InputField`，也**禁止**使用 Material 的
`TextField`/`OutlinedTextField` 充当搜索框或弹窗输入框。

### 1. 组件形态（Miuix 风格，非展开内嵌式）

- 组件内部固定为 `MiuixSettingsTheme + SearchBar + InputField` 模板，`expanded` 恒为
  `false`（内嵌式），不使用 Miuix 的展开式全屏搜索页形态。

- 前导图标固定 **26dp**，内边距固定 `SearchBarDefaults.LeadingIconStartPadding /
  LeadingIconEndPadding`，`tint` 统一取 `MiuixTheme.colorScheme.primary`——以上均由组件
  内部处理，调用方不要传色值/尺寸，保证全局样式单点可改。

### 2. 参数约定

- `query` / `onQueryChange`：必传，输入状态由调用方持有。

- `onSearch`：键盘 IME 动作回调。本地即时过滤场景可不传（默认空实现）；
  需显式触发时传入（如网络请求、回车即保存）。

- `leadingIcon`：drawable 资源（`R.drawable.ic_*`）。搜索语义用默认 `ic_game_search`；
  其他语义必须传对应图标（如名称修改用 `ic_sheet_rename`），禁止搜索图标滥用。

- `iconContentDescription`：无障碍描述，跟随图标语义。

- `modifier`：仅用于布局微调（如外边距），宽度由组件内部 `fillMaxWidth` 保证。

### 3. 现有调用点（新增场景照此对齐）

| 场景          | 位置                                 | 图标                | onSearch        |
| ----------- | ---------------------------------- | ----------------- | --------------- |
| 游戏库过滤       | `GameScreen.kt`（顶栏下方）              | `ic_game_search`  | 即时过滤，不传         |
| VNDB 封面搜索   | `GameScreen.kt`（VndbSearchDialog）  | `ic_game_search`  | `search()` 网络请求 |
| krkr 在线补丁过滤 | `KrkrOnlinePatchActivity.kt`（列表首项） | `ic_game_search`  | 即时过滤，不传         |
| 名称修改        | `GameScreen.kt`（RenameGameDialog）  | `ic_sheet_rename` | 内容有效即保存         |

***

## 功能跳转条目统一规范

所有「功能跳转列」——即点击后进入 / 跳转 / 打开下一级的条目（如封面来源列表、弹窗内的群聊/频道项、设置里的二级跳转项等），**必须**统一使用公共组件
`com.tyranor.next.ui.common.AppNavItem`（`app/src/main/java/com/tyranor/next/ui/common/AppNavItem.kt`）。
**禁止**在页面/弹窗内手写 `Row`/`Column` 拼装此类条目，也禁止混用 Material 的 `ListItem`/`ColumnItem` 等。

### 1. 组件形态与参数

- 排版固定：圆角 `AppComponentShape`（默认 8dp / 玻璃风格 32dp，见 `theme/AppShapes.kt`）+ 背景取 `theme/Color.kt` 常量 `NavWhite`（页面场景默认）+ 内边距（横向 16dp / 纵向 12dp）+ 左侧图标 24dp + 右侧指示箭头 `KeyboardArrowRight`。均由组件内部处理。

- 背景色约定（与白底弹窗对偶，详细见 3.5）：**页面上的条目**默认 `NavWhite`（页面背景 `PageGrey` → 灰底白卡）；**弹窗内的条目**必须传 `containerColor = DialogItemSurface`（默认风格 = `PageGrey` 白底弹窗灰卡；玻璃风格 = 亮玻璃面），保证条目与弹窗背景反色、层次分明。「色调切换」开启时 `NavWhite`/`PageGrey` 同步互换，反差关系不变；玻璃风格下 `PageGrey` 透明、`NavWhite` 变为半透明玻璃面，`DialogItemSurface` 自动切换为亮玻璃面。

- 标题用 `MaterialTheme.typography.bodyMedium`、颜色取 `TextColor`；摘要可选，用 `bodySmall` + 半透明辅助色。均不依赖 `colorScheme.surface*`（遵循「组件背景色统一规范」）。

- `leadingIcon`：左侧图标 drawable；未提供时组件自动使用**默认占位图标** `DEFAULT_LEADING_ICON`，不允许调用方在不该出现空图标时留白。

- `showLeadingIcon`：是否展示左侧图标，默认 `true`。**纯动作条目**（如存档导出/导入/删除等无图标的动作项）可传 `false` 隐藏图标位，此时标题顶格排列；不得为隐藏图标而乱传占位图。

- `onClick`：点击回调；传 `null` 表示不可用（整条变灰且不可点击）。

- 进入跳转的 icon 统一用 `KeyboardArrowRight`，组件内置，调用方不传。

- `showArrow`：是否显示右侧跳转箭头。**「进入下一级」的跳转条目必须 `true`（默认）**；「执行动作」的条目（如启动游戏、删除等不产生跳转的动作）传 `false` 隐藏箭头，避免误导为可跳转。

- `verticalPadding`（高度豁免条款）：条目纵向内边距默认 **12dp**。**豁免**：底部抽屉面板（`ModalBottomSheet`）内展示的条目可使用更大纵向内边距（当前游戏操作抽屉取 17dp，条目总高 +10dp），仅限抽屉内条目；`AppAlertDialog` 弹窗内条目一律保持默认 12dp。

- `leadingIconTint` / `titleColor`：显式覆盖左侧图标与标题颜色（如游戏操作面板用主题色 `MaterialTheme.colorScheme.primary` 统一图标、危险条目用 `colorScheme.error` 上色）。传 `null`（默认）时使用面板默认行为（深色染白、浅色原图 / `TextColor`）。

- 深色模式适配：左侧图标（PNG drawable）与右侧箭头在深色模式下自动染色为 `Color.White`，浅色模式保持原色不变；不可点击状态（`onClick = null`）保留 `contentAlpha` 衰减。这一适配由组件内部完成，调用方无需处理。

- 颜色判断依赖全局 `AppThemeColors.isDark` 快照，切换外观模式自动重组刷新，与整体主题保持同步。

### 2. 现有调用点（新增场景照此对齐）

| 场景              | 位置                                       | leadingIcon                         |
| --------------- | ---------------------------------------- | ----------------------------------- |
| 选择封面来源（四源统一）    | `GameScreen.kt`（CoverSourcePickerDialog） | `ic_cover_source`（云端）               |
| 加入群聊（企鹅群聊/飞机频道） | `SettingsScreen.kt`（加入群聊弹窗）              | `ic_group_qq` / `ic_group_telegram` |

***

## 开关按钮统一规范

所有需要**布尔二态开关**（开/关）的场景，必须统一使用 **Miuix 风格** 的开关组件，禁止混用 Material3 的 `Switch` 或其他库的开关，避免深浅色 / 主题色 / 触感样式不一致。

### 1. 组件选型（按场景二选一）

- **设置条目式开关**（标题行 + 右侧开关，如设置清单中的配置项）：统一使用
  `top.yukonga.miuix.kmp.preference.SwitchPreference`（title / checked / onCheckedChange）。

- **行内独立开关**（列表项右端内嵌、无标题行的纯开关）：统一使用
  `top.yukonga.miuix.kmp.basic.Switch`（checked / onCheckedChange）。

### 2. 现有调用点（新增场景照此对齐）

| 场景                                   | 位置                                                | 组件                 |
| ------------------------------------ | ------------------------------------------------- | ------------------ |
| 设置清单开关（KRKR/ONS/Artemis/RPG Maker 等） | `SettingsScreen.kt`                               | `SwitchPreference` |
| 应用设置开关（色调切换 / 圆角导航等）                 | `AppSettingsActivity.kt`                          | `SwitchPreference` |
| 封面来源启用开关（行内）                         | `CoverScraperSettingsActivity.kt`（CoverSourceRow） | `Switch`           |

### 3. 例外

- 三态「跟随全局 / 开 / 关」等**非布尔**选择（如 `PerGameSettingsScreen.kt` 的 `OverrideSwitch`）不属于开关按钮范畴，用 Miuix `OverlayDropdownPreference` 实现，不受本节约束。

- 引擎原生代码（engine 模块）内的开关不属于 Compose 层，不适用本节。

***

## 主题色调统一使用规范

应用主题色（primary）由用户通过 **应用设置 → 色调轮盘** 修改，必须全局统一生效。规范如下：

### 1. 主题色的唯一定义与入口

- 全局主题色唯一状态：`com.tyranor.next.theme.AppThemeColors.primary`（`mutableStateOf`，变化即触发全 App 重组）。

- 持久化：`com.tyranor.next.core.settings.AppSettingsStore`（独立 prefs 文件 `app_settings`，key `theme_color`，默认 `#307DEF`）。

- 修改主题色的唯一入口：持久化后调用 `AppThemeColors.refresh(context)`；任何页面不得自行修改 `primary`。

- `TyranorNextTheme`（Material 页面）与 `MiuixSettingsTheme`（Miuix 页面）的 `primaryColor` 参数**必须为** **`Color? = null`**，并在**函数体内**以 `val primary = primaryColor ?: AppThemeColors.primary` 读取全局主题色；两个主题函数都必须标注 `@NonSkippableComposable`（配合函数体内读取，保证轮盘切换时主题必然重组、全局同步变色，不依赖调用点对默认参数表达式的订阅）。内部均会先 `ensureLoaded` 从存储加载，禁止传入写死颜色。

### 2. 页面如何获取主题色

- **Material 页面**（首页/游戏/引擎等 Tab、存档管理、在线补丁等 Activity）：统一使用 `MaterialTheme.colorScheme.primary`（选中态图标/文字、按钮、开关、输入框聚焦指示等）。

- **Miuix 页面**（设置页/引擎设置/单游戏设置/应用设置等）：统一使用 `MiuixTheme.colorScheme.primary`（Preference 图标、Slider、Switch、下拉选中等）。

- 图标 tint、高亮文字、选中态等一切"强调色"位置只能从上述 colorScheme 获取，**禁止**在页面里硬编码 `Color(0x...)`、`#307DEF`、`Blue40` 或任何品牌蓝。

### 3. 中性色与语义色（固定，不属于主题色）

以下颜色固定不变，从 `theme/Color.kt` 常量引用，**禁止**在页面中直接写 `Color(0x...)`：

- `PageGrey` 页面背景色、`NavWhite` 卡片/导航栏组件色；二者由应用设置「色调切换」控制是否互换。`TextColor` 正文深灰、`UnselectedGrey` 导航栏未选中灰。

- 语义色：`colorScheme.error`（错误/删除）、引擎封面色（`EngineType.coverColor()`）、封面占位白字等。

- 新增任何颜色先检查 `Color.kt` 是否已有现成常量；中性色必须统一收口到 `Color.kt`，不在页面内散落硬编码。

### 3.5 组件容器/背景色的色调跟随

组件（卡片、列表项、弹窗内容项、设置项容器等）的背景色**不得依赖**
`colorScheme.surface*` / `surfaceContainer` 等 scheme 颜色来自动跟随色调切换——这些 scheme
颜色可能被主题函数固定写死（如 `MiuixSettingsTheme` 深浅色分支都设为 `NavWhite`），且弹窗等
`Dialog` 组合可能不在目标 Miuix/Material 主题作用域内，导致背景色不随「外观模式 / 色调切换」变化。

统一做法：需要随「色调切换」变色的组件容器，**直接引用** **`theme/Color.kt`** **的计算常量**（`get()`
读取 `AppThemeColors.isDark` / `toneSwitchEnabled` 的 snapshot state，变化即触发重组）：

- 卡片/导航栏/组件容器（含弹窗背景） → `NavWhite`

- 弹窗内的条目容器（如 `AppNavItem` 传 `containerColor = DialogItemSurface`、手写条目行用
  `DialogItemSurface`） → 与弹窗背景形成对偶反差

- **玻璃外观风格（应用设置 → 外观风格 = 玻璃）**：页面背景固定黑灰渐变（`GlassBackground`），
  `PageGrey` 透明、`NavWhite` = `GlassSurface`、`TextColor` 恒浅色；卡片/条目/弹窗/输入框统一
  0.5dp 发丝描边（`Modifier.glassBorder()`），弹窗/抽屉面板用 `GlassPanel`。此模式下「外观模式」
  与「色调切换」不可用（置灰），色调轮盘保持可用。新增组件必须走上述动态常量与 `glassBorder`，
  不得硬编码玻璃色值。

- **底部抽屉/面板（`ModalBottomSheet`）→ 按「页面灰底」处理**：`ModalBottomSheet` 的 `containerColor` 通常取 `colorScheme.background`（浅/深随色调切换，等同页面背景），因此抽屉内条目（`AppNavItem` 等）必须传 `NavWhite`（灰底白卡），**不要**套用「弹窗白底灰卡」用 `PageGrey`——否则 item 与抽屉背景同色融为一体（如游戏操作抽屉 GameActionsSheet）。

- 页面背景 → `PageGrey`

- 文字 → `TextColor`

通过 `MiuixTheme`/`MaterialTheme` 的 `colorScheme` 取容器背景属于**反例**（例：弹窗内项目用
`MiuixTheme.colorScheme.surfaceContainer` 不会随色调切换变色，应改用 `theme/Color.kt` 对应容器常量——
页面条目 `NavWhite`、弹窗内条目 `PageGrey`）。
仅当某 scheme 颜色确为实时计算且随色调切换变化时才允许引用。

### 4. 层级要求

- 任何页面根组件必须包在 `TyranorNextTheme {}` 或 `MiuixSettingsTheme {}` 内，且主题必须最外层（Activity `setContent` 中包裹）。

- `MiuixSettingsTheme` 只提供 Miuix 主题，页面内部若用到 `MaterialTheme.*`（如弹窗、typography），外层仍须有 `TyranorNextTheme`（所有现有 Activity 均已满足，新增页面须遵循）。

- XML / drawable 资源中**禁止**出现主题色（启动图标等除外）。

***

## 测试与审核

必须让Agent使用阅读 Android CLI 中的审查技能

应由多个相互独立的 Agent 或 子Agent 从不同角度重新检查代码，例如：
代码逻辑审核：检查实现逻辑、状态流转、边界条件以及潜在逻辑错误。
架构审核：检查模块职责、依赖关系、代码耦合以及是否破坏现有架构。
代码健壮性审核：检查异常处理、空值、资源释放、线程安全、生命周期以及极端输入。
回归风险审核：分析修改是否可能影响现有功能、其他引擎或既有调用链。
代码规范审核：检查代码风格、命名、重复代码、无效代码以及是否符合 AGENT.md 和项目现有规范。

***

## 构建

- 构建命令：`./gradlew assembleDebug --no-daemon`

- 使用 Android CLI（`--sdk=/tmp/androidsdk`）安装到实机。

- 发版规则（issue #79）：只修改 `app/build.gradle.kts` 的 `appVersionName`；`versionCode` 由 `versionCodeOf()` 自动推导
  （`major*1_000_000 + minor*1_000 + patch`，minor/patch 必须 < 1000），禁止手写或回退 `versionCode`。
  beta-release workflow 会前置校验 versionCode 严格递增，并在构建后核对 APK 实际 versionCode（不一致即失败）。

