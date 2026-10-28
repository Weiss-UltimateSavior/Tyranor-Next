# Repository Guidelines

本文档是 AI Agent 在本项目内开发时必须遵循的统一规范。新增或修改代码前请先阅读，与既有实现保持一致。

> 领域术语：项目核心概念（引擎/游戏库/设置/封面/存档/界面规范等）的权威定义见 `CONTEXT.md`（仓库根目录）。开发与代码评审时若涉及上述领域词汇，以 `CONTEXT.md` 中的术语与 `_Avoid_` 意见为准，避免引入异名同义的新词。

## 仓库导航与工作范围

- [ARCHITECTURE.md](ARCHITECTURE.md)：模块、进程、数据流、资源构建与已知架构偏差。
- [SPEC.md](SPEC.md)：当前功能行为、兼容边界与回归验收项；设计方案不等于已实现功能。
- [CONTRIBUTING.md](CONTRIBUTING.md)：Issue 与 PR 流程；[README.md](README.md) 为用户入口。
- `app/src/main/java/com/tyranor/next/{core,ui,theme}`：业务能力、Compose 页面与主题；`app/schemas/` 保存 Room schema。
- `engine/src/main/{java,cpp,assets,jniLibs,nativeplugins}`：宿主、JNI 桥、脚本及预编译运行库；`engine/rust/` 为 XP3 Rust 源码。
- 两模块的 `src/test/` 放 JVM 回归；`engine/src/test/js/` 放 Web 桥测试；`app/src/androidTest/` 和 `app/src/releaseProbe/` 放设备测试。
- `buildSrc/` 管理图形资源生成与校验，`tools/` 放本地工具，`.github/workflows/` 定义 CI；`docs/`、`.archify/` 保存专题说明与图表。

开始修改前检查 `git status --short`，保留已有工作；先读相关实现与调用方，再做最小必要修改。优先复用已有门面、错误类型和公共组件，遵循 KISS、YAGNI、DRY 与 SOLID，避免顺手重排无关代码。

## 编码、命名与文档

- 文件使用 UTF-8 无 BOM；Kotlin 遵循 `kotlin.code.style=official`，新增 Kotlin 代码通常使用四个空格缩进，已有文件保持局部风格，Gradle 配置也沿用所在文件格式。
- 类型与 Composable 用 `PascalCase`，函数/属性用 `camelCase`，常量用 `UPPER_SNAKE_CASE`，包名小写；测试命名 `<Subject>Test.kt` / `.java`。
- 用户可见文案同步维护两模块各自的 `values/`、`values-ja/`、`values-en/strings.xml`。运行 `python tools/check-hardcoded-ui-strings.py`；它不覆盖 Java 与注入 JS 的全部文案，后者须人工复核。
- 当前未配置 ktlint、detekt 或覆盖率百分比门槛，不要宣称这些检查已通过；Android lint 与专项回归按改动范围执行。
- 功能变更同步更新规格与 README，架构边界变化更新架构说明，术语变化更新 `CONTEXT.md`；维护 `CHANGELOG.md`，若存在 `README_EN.md` 也同步更新。

## Commit 与 Pull Request

历史采用 `feat:`、`fix:`、`refactor(scope):`、`docs:`、`build:` 等类型前缀，描述语言并不统一；本次起新提交建议使用英文祈使短句，例如 `docs: clarify engine launch boundaries`。每个提交聚焦一类修改。

PR 说明问题、最终行为、影响的引擎/页面、验证命令及结果，并关联对应 Issue（如 `Fixes #123`）。UI 改动附前后截图及相关手机/平板、深浅色场景；原生引擎改动附设备、系统、运行库身份与真实样本证据。明确未执行的检查，不把编译、合成夹具或静态哈希验证当作游戏兼容性验收。

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

- Android Jetpack Compose + Material 3 + Miuix

- Kotlin、Java 17、C++ JNI，XP3 使用 Rust；版本以 `gradle/libs.versions.toml` 和构建脚本为准。

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

- 职责：游戏扫描、游戏模型、启动编排、封面抓取、存档管理、在线补丁、XP3 解包/封包、应用/引擎/单游戏配置、授权、后台更新。

- 规则：可以依赖 `engine` 模块；不得依赖 Compose（含 `androidx.compose.ui/foundation/material*` UI 组件，以及 `androidx.compose.runtime` 的状态原语与 `@Immutable` 注解）；不得把页面类作为普通业务依赖。
  全局可观察状态一律用 `kotlinx.coroutines.flow`（`StateFlow`）或纯数据载荷（`ThemeColorPayload` 模式）由 UI 层 `collectAsState` 订阅。

- 新增功能按域放入 `core/game`、`core/engine`、`core/cover`、`core/patch`、`core/unpack`、`core/settings`、`core/auth`、`core/updater` 等包。

### 3. 界面 UI 交互层

- 目录：`app/src/main/java/com/tyranor/next/ui/`

- 职责：Compose 页面、Activity 壳、弹窗、导航、顶部栏、搜索框、用户输入、加载态与错误态。

- 规则：只能调用功能抽象层；禁止直接 import `com.core`、`com.akira`、`com.yuri`、`org.tvp`、`org.libsdl`、`org.cocos2dx`、`bridge` 等底层引擎包。

- 页面按功能域放入 `ui/home`、`ui/game`、`ui/engine`、`ui/settings`、`ui/cover`、`ui/patch`、`ui/save`、`ui/archive`、`ui/auth`，公共组件放入 `ui/common`。

### 4. 资源归属

- `engine/src/main/assets`：底层引擎运行资源与共享引擎脚本源头。

- `app/src/main/assets`：App 自有资源（当前为 RTP）。共享脚本由 Gradle 从 `engine/src/main/assets` 同步到 `app/build/generated/assets/engine`，不要手工建立第二份脚本源。

- `engine/src/main/nativeplugins`：共享 Native 插件 so 源头。
- `app/src/main/nativeplugins`：插件 manifest 与 app-only 插件源头。

- 插件 zip 生成到 `app/build/generated/assets/nativeplugins`。禁止在 app 与 engine 两边手工维护同一份二进制或共享脚本；插件源合并通过 `DuplicatesStrategy.FAIL` 拒绝重名，脚本单一源归属仍需评审检查。

- 原生运行库通常是已提交的预编译 `.so`；普通 Gradle 构建只编 JNI 桥，不重建全部运行时。更新运行库时必须同步固定来源、校验清单、许可证与设备验收证据，详见 [ARCHITECTURE.md](ARCHITECTURE.md)。

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

存量偏差：`GameSaveManager.SaveLocation.description`、`HikarinagiAuthService` 和 `KrkrOnlinePatchService` 仍有本地化字符串载荷。上述协议是新增/改动代码的约束，并非全仓已完成迁移；修改相关接口时连同 UI 映射和调用方一起对齐，不新增并行错误风格。

***

## 页面顶部栏统一规范

所有页面（首页 / 游戏 / 引擎 / 设置）的顶部栏必须统一。**一律使用统一组件
`com.tyranor.next.ui.common.AppTopBar`**，禁止任何页面手写该结构；占位页可复用
`com.tyranor.next.ui.common.PlaceholderPage`（内部同样走 AppTopBar）。

### 0. 统一入口（AppTopBar）

- 页面顶部栏必须调用 `AppTopBar(title, ...)`；标题、结构、取色由组件兜底。
- 参数约定：
  - Material 页面（默认）：不传 `background`/`contentColor`，组件默认 `Color.Transparent` / `colorScheme.onBackground`。
  - Miuix 风格页面（设置类 MiuixScaffold 的 `topBar` 槽）：传 `background = Color.Transparent`（顶部栏背景恒为透明，交由根部背景层——`MiuixTheme.colorScheme.background` 实为 `PageGrey`，传它会盖住玻璃/渐变根背景）、`contentColor = MiuixTheme.colorScheme.onBackground`，并设 `contentWindowInsets = WindowInsets(0.dp)`。
  - 需要「色调切换」参与文字取色的页面：保持 `background = Color.Transparent`，传 `contentColor = TextColor`，背景仍由页面根层提供。
  - 右侧图标用 `trailing` 槽传入 `TopBarIcon`；标题下方的折叠内容（如游戏页搜索框）用 `underTitle` 槽。
- 新页面/组件禁止再书写「背景层 + statusBarsPadding + 64dp + titleLarge Bold」结构。

### 1. 结构

- 顶部栏由公共组件内部使用 **Column + Row**，页面不自行使用 Material3 `TopAppBar` 替代；Miuix 页面壳按下文约定接入。

- 页面由外层 `Column` 组装为「顶部栏 → 正文内容」，Material 页与 Miuix 页均保持上下分离布局；Miuix 页由透明 `MiuixScaffold` 的 `topBar` 槽承载 `AppTopBar`，正文位于顶栏下方，不启用顶栏模糊叠层。

- **禁止在顶部栏放置任何返回按钮/图标**。返回统一依赖系统返回键/手势（`Activity` 默认 `finish()`），不要通过 `onBack` 参数下发返回回调。

### 2. 高度

- 标题区高度固定为 **64dp**。

- 顶部栏整体无需在 64dp 之外再叠加额外高度。

### 3. 标题

- 标题**居左**，水平内边距 `horizontal = 16.dp`，纵向居中。

- 标题字号使用 `MaterialTheme.typography.titleLarge`。

- 标题**必须加粗**：`fontWeight = FontWeight.Bold`。

### 4. 背景色

- 顶部栏背景**恒为透明**（`AppTopBar` 默认 `Color.Transparent`），露出页面根部背景层：默认外观风格为「纯色 + 主题色/近似色渐变（浅色含柔光+高斯模糊，深色为黑底对角渐变）」（`theme/DefaultPageBackground.kt`，可在应用设置「默认主题渐变」关闭，关闭后为纯色底），玻璃系风格为玻璃页面渐变。标题与图标统一使用 `colorScheme.onBackground`。
- 页面根容器（`MiuixScaffold(containerColor = …)`、页面级 Box）同样**不得铺不透明背景色**（一律 `Color.Transparent`），背景由根部背景层负责；否则会把渐变背景整片盖掉。

- **玻璃系外观风格（复古玻璃 / 高级玻璃）**：玻璃下页面背景透明，顶栏保持透明（露出渐变/色斑与环境光）。
  因此**页面内容必须整体垫在顶栏下方**（用持久 `Modifier.padding(top = 顶栏高度)`，而不是滚动区的
  `contentPadding`），否则滚动内容会从顶栏下方穿过与标题重叠。设置类页面（MiuixScaffold）的
  `innerPadding` 顶部值一律加到列表 modifier 上，`contentPadding` 只保留额外的间距。

- 禁止使用主题色 `primary` 作为顶部栏背景。

### 5. 状态栏

- 状态栏必须是**透明沉浸式**，经 `enableEdgeToEdge` 配置，顶部栏的
  页面背景色向上延伸覆盖状态栏区域。

- 状态栏/导航栏图标跟随实际外观：浅色背景使用深色图标（`SystemBarStyle.light`），深色与玻璃风格使用浅色图标（`SystemBarStyle.dark`）；二级页复用 `AppScreenActivity`，不要写死浅色模式。

- 顶部栏需使用 `Modifier.statusBarsPadding()`，使标题内容避开状态栏但背景色连续延伸。

- 不要自行给状态栏设置非透明背景色。

### 6. 位置顺序

```
Column(fillMaxSize)                                // 页面根
├── AppTopBar(background = Color.Transparent)       // 根背景透出；内部管理状态栏留白
│   └── Row(height 64dp, padding horizontal 16dp)    // 标题与 TopBarIcon
└── 正文内容
```

> 设置类页面若使用 `MiuixScaffold`，`containerColor` 取 `Color.Transparent`（背景由根部背景层绘制）；`topBar` 槽调用 `AppTopBar`，并设 `contentWindowInsets = WindowInsets(0.dp)`，不要自行重写顶栏结构。

### 7. 顶部栏图标按钮

- 顶部栏右侧图标按钮**必须使用公共组件** `com.tyranor.next.ui.common.TopBarIcon`（排版规范对齐游戏页顶部栏），**禁止**自行用 `IconButton`/`Icon` 拼装。

- 组件排版：`Image` 渲染 + `size(34.dp)` 触控区 + `clip(RoundedCornerShape(6.dp))` 圆角 + `clickable` + `padding(4.dp)` 内边距，并 `ColorFilter.tint` 主题色；组件左侧自带 `padding(start = 2.dp)`，保证相邻图标之间留白统一。

- `tint` 由调用方传入当前主题色：Material 页面传 `MaterialTheme.colorScheme.primary`，Miuix 页面传 `MiuixTheme.colorScheme.primary`。

- 顶部栏图标一律使用 drawable 资源（`R.drawable.ic_*`）；需要新图标时在 `res/drawable` 新建资源（PNG 或 vector XML），禁止直接用 `Icons.*` 矢量图标充当顶部栏按钮。

### 8. 明文豁免

- **明文豁免（按键方案布局编辑器）**：`ui/settings/PadLayoutEditScreen.kt`（`PadLayoutEditActivity`）**不设顶部栏**，编辑画布占满全屏（含系统栏区域）——为有意设计，不受本节 0–7 条约束。理由：该页的核心契约是与游戏内按键层**同视口**（按钮位置按视口归一化存储，横竖屏按比例重映射），顶栏会压缩画布高度、使「所见即所得」失效。操作方式与退出方式由进屏 Toast 说明，保存经编辑面板内的「保存」触发，退出走系统返回键。**该豁免仅限本页**，其余页面一律使用 `AppTopBar`，不得援引。
- **同一豁免的延伸（该页复用 engine 原生编辑 UI）**：本页把 engine 侧的原生按键层与编辑面板（`com.core.input.VirtualPadView` / `VirtualPadEditPanel`，由 core 门面 `PadEditorHost` 提供 `rootView`）整体当作画布，因此**该页内的自绘弹窗与文字尺寸**同样随本页豁免应用统一弹窗材质、圆角与两档排版（面板为深色卡片、随游戏画面同视口呈现，见 `VirtualPadEditPanel.showDarkDialog`）。延伸豁免**同样仅限本页与游戏内按键层**：设置域的其它弹窗（`AppAlertDialog`）必须照常遵守弹窗点击反馈、动态面板取色与 `AppComponentShape` 规范。**理由修正**：该页 Activity 固定 `sensorLandscape`（见 `AndroidManifest.xml`），编辑器本身不经历横竖屏切换；「横竖屏按比例重映射」描述的是**游戏内**按键层的行为。

***

## 跨页面一致性要求

- 所有页面共用 `PlaceholderPage`（或与之一致的结构），禁止各页面各自实现不同样式的顶部栏。

- 新增页面时：页面主体内容放入 `Column` 的正文区域即可，顶部栏保持相同。

- 顶部栏下方禁止放置页面说明/描述文案；正文区域应直接展示该页面的实际内容或列表。

- 页面切换动画必须保持统一：主 Screen 四个 Tab 间切换使用水平移动动画；其他独立 Activity 页面进入使用向上翻页动画，退出/返回使用向下翻页动画。

- 组件统一圆角数值为 **8dp**；列表项卡片、功能项卡片、弹窗等圆角组件统一引用 `theme/AppShapes.kt`：
  `AppComponentShape`（默认 8dp / 玻璃外观风格与悬浮导航一致 32dp）、Miuix 组件用 `AppComponentCornerRadius`、
  抽屉顶部用 `AppSheetTopShape`；禁止再散落圆角字面量。

- **平板侧栏**：`isSideRailLayout()`（`isTabletScreen()` 命中且应用设置「平板侧边栏」开关开启，默认开）命中时主导航移到侧边（`ui/common/AppNavigationRail.kt`）：侧栏形态按外观风格取该主题「经典 MD 导航栏」（原「默认导航栏」）形态，液态玻璃两档与「悬浮按钮」不适配侧栏；侧栏占布局宽度，底部留白归零；高级玻璃侧栏采样单独的纯背景层（`railBackdrop`，不能采样内容层——坐标会越界）。选中态图标动画统一用 `ui/common/NavigationTabIcon.kt`。

- **圆角豁免**：液态玻璃导航（`ui/common/LiquidGlassNavigation.kt`）的栏体与导航项胶囊使用 **16dp**（8dp 基础上加大 8dp），为有意设计，不受 8dp 条款约束；其余组件不得援引此豁免。

- **圆角豁免（液态玻璃 · 透镜底栏）**：应用设置「导航栏样式」选到「液态玻璃 · 透镜」后挂载的
  `ui/common/glass/EnhancedLiquidGlassNavigation.kt`（栏体 / 图标副本行 / 移动透镜三处）使用
  `theme/AppShapes.kt` 的 `AppNavCapsuleShape`（胶囊，圆角 = 半高），为本项目为该样式既定的连续胶囊轮廓，为有意设计。
  **该组件内也必须统一引用 `AppNavCapsuleShape`，不得就地新建等价的圆角形状**；该豁免仅限该组件，其余组件不得援引。

- **圆角豁免（悬浮按钮导航）**：「导航栏样式 = 悬浮按钮」后挂载的 `ui/common/FloatingGlassNavButton.kt`
  主按钮与弧上导航项使用 `CircleShape`（圆形是本样式既定形态），为有意设计；该豁免仅限该组件，其余组件不得援引。
  **材质固定为液态玻璃的浅 / 深两档**（浅色 = 白玻璃、深色 = 深色玻璃），**只按 `AppThemeColors.isDark` 取档，
  不随外观风格（默认 / 复古玻璃 / 高级玻璃）分档**（本样式自带固定观感，是 3.5 节「组件必须走动态常量」的豁免；
  固定色取自 `theme/Color.kt` 的 `FloatingNavGlassSurface` / `FloatingNavGlassSurfaceDark` /
  `GlassUnselected` / `FloatingNavGlassUnselectedDark`，
  沿用原版图标，图标强调色与接近光效跟随主题色，触边使用半透明主题色玻璃覆盖），组件内不得引入 `AppThemeColors` 的**风格**判断（深浅取档是允许且必须的）。
  **长按拖动位置**：位置以归一化坐标存 `AppSettingsStore`（安全区内换算，见 `floatingNavPositionPx` /
  `clampFloatingNavPosition`），展开方向按象限自适应（`floatingNavArcStartAngle`），设置页有「重置悬浮按钮位置」；
  收起时长按展开并连续滑选；展开后长按主按钮收起并进入自由移动，同一手指可持续拖动。
  展开后单点子控件切页保持展开，连续滑选松手确认后收起；主控件维持 64dp，主子图标统一为 28dp。
  展开位置固定，触碰子控件不得带动其它子控件移动；圆球靠近时只缩放并按固定间距限制相交。
  页面点击由共同父层观察，事件不得消费：同一次点击同时收起导航并执行页面选项，不铺全屏拦截层。
  触觉反馈保留展开/收起、确认选中、长按进入移动与实际落位；接近、移入或切换子控件候选时不得震动。
  悬浮按钮在平板也保持圆球导航，弧形半径与选项尺寸按安全区自适应；其它样式的平板侧栏规则不变。

- 所有应用弹窗使用 `AppAlertDialog`，背景与圆角由动态主题常量决定：默认浅色通常为白底 8dp，深浅色/色调切换使用 `NavWhite`，玻璃风格使用对应面板材质与 32dp；统一引用 `AppComponentShape`，不得写死白色或 8dp。原生按键编辑器的明文豁免仍仅限该页与游戏内按键层。

## 页面内容文字尺寸规范

页面内容（顶部栏除外）只允许使用**两种**文字尺寸，与引擎设置页保持一致：

1. `MaterialTheme.typography.titleMedium`（大字号，15sp，在 `theme/Type.kt` 中全局覆盖，Material 默认 16sp）—— 卡片头、对话框标题、列表项主标题（可加粗）。
2. `MaterialTheme.typography.bodyMedium` —— 正文、行值、辅助描述、按钮文字、空态/错误提示。

- **禁止**使用 `bodySmall` / `bodyLarge` / `labelMedium` / `labelLarge` / `headlineMedium` / `headlineSmall` 等其它排版尺寸。

- 存量组件例外：`AppNavItem` 内部摘要当前使用 `bodySmall`，仅限该共享组件既有实现；页面不得据此新增第三档字号。

- Miuix preference 组件标题默认用 `headline1`(17sp)，已在 `MiuixSettingsTheme` 中全局覆盖为 15sp（`defaultTextStyles(headline1 = TextStyle(fontSize = 15.sp))`），使其严格落入两档并匹配 `titleMedium`；不要自行在单行上改字号。

- 顶部栏标题不受此限制，仍用 `MaterialTheme.typography.titleLarge` Bold。

- **明文豁免**：首页快捷启动卡（`ui/home/HomeScreen.kt` 的 QuickLaunchCard）以封面模糊图 + 黑色压暗遮罩为背景、白色文字展示，卡片内游戏名使用 `MaterialTheme.typography.titleLarge` Bold、引擎名使用 `MaterialTheme.typography.headlineSmall` Bold，均不受两档制限制；该卡片其余文字仍遵循两档制。

- **明文豁免**：游戏页卡片左上角的引擎类型角标（`ui/game/GameScreen.kt` 的 `GameCard`，仅覆盖在封面上）使用 `MaterialTheme.typography.labelSmall`（11sp）——角标属封面上的小型标注，按两档制会过大；仅此一处豁免，其余正文仍遵循两档制。角标底色/文字色为**固定样式**（`theme/Color.kt` 的 `CoverBadgeBackground` / `CoverBadgeText`：半透明黑底 + 白字），**不随主题色、外观模式与色调切换变化**。

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

## 标签栏统一规范

全 App 的「标签栏 / 分类栏」（横向排布、单选切换、选中项带背景指示器的场景）**必须**统一使用公共组件
`com.tyranor.next.ui.common.SlidingTabRow`（`app/src/main/java/com/tyranor/next/ui/common/SlidingTabRow.kt`）。
**禁止**在页面内手写指示器偏移、等分宽度、选中背景与文字取色（引擎页原自绘 `GlassTabRow` 已并入本组件）。

### 1. 组件形态与参数

- 选中项为**独立背景指示器**（不是逐项背景），切换时以 200ms 线性动画滑到目标标签；滚动态标签宽度不齐时宽度同步动画。指示器圆角统一 `AppComponentShape`，玻璃系由组件内部调用 `glassBorder` 补 0.5dp 发丝描边。
- 指示器底色与文字颜色由组件内部按外观风格分派，**调用方不得传色值**：
  默认风格指示器 `NavWhite`、选中文字 `TextColor`（不跟随主题色，加粗作选中标识）、未选中 `onSurfaceVariant`；
  复古玻璃 `GlassSurfaceHigh` + `GlassText`/`GlassTextSecondary`；高级玻璃 `AdvancedGlassSurfaceHigh` + `GlassText`/`AdvancedGlassTextSecondary`。
- 标签高度统一 42dp，文字统一 `MaterialTheme.typography.titleMedium`，选中加粗；无障碍统一 `selectable(selected, role = Tab)`。
- 参数：`tabs`（标签文案）、`selectedIndex`（越界自动收敛）、`onTabSelected`、`modifier`（仅外边距）、`scrollable`（形态开关，默认 false）。

### 2. 两种形态

- `scrollable = false`（默认）：标签**等分宽度**，适合数量固定、宽度一致的分页。
- `scrollable = true`：标签按**内容宽度横向滚动**，适合数量多 / 宽度不齐的分类；两端内置 12dp 留白，选中项在屏幕外（程序化回退、进程恢复）时自动滚动到可见（首次直接定位，之后带动画）。页面侧不得用 `LazyRow` 自行拼装等价物。

### 3. 现有调用点（新增场景照此对齐）

| 场景 | 位置 | 形态 |
| --- | --- | --- |
| 引擎页顶部分页（玻璃外观） | `EngineScreen.kt` | 等分 |
| 首页「分类仓库」分类栏（全部/快捷/最近/各引擎类型） | `CategoryWarehouse.kt` | 横向滚动 |

> 例外：引擎页在默认外观风格下沿用 Miuix `TabRow`（等分分页的既有观感，含动画指示器），仅在玻璃外观下走本组件；新增场景（含默认风格）一律用本组件。

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

### 2. 侧边栏面板（AppSidePanel）内条目

妙妙工具侧边栏（`ui/common/AppSidePanel.kt` 的 `content` 槽）内的条目必须使用 `AppNavItem`，统一采用**与弹窗条目一致的完整形态（带图标 + 右侧指示标）**：

- `containerColor = DialogItemSurface`（面板底色为 `NavWhite`，条目反色灰卡，与弹窗内条目为同一对偶关系）。
- `leadingIcon` 必须传与功能语义对应的具体图标，不得使用组件默认占位图标，也不得隐藏图标位。
- `showArrow = true`（默认）：面板内条目统一保留右侧指示标，**「执行动作」条目（如启动 kr2 原生）同样不隐藏箭头**——本条优先于上文「执行动作传 `false`」条款。
- 条目之间的纵向间距由 `AppSidePanel` 内部统一加（`SidePanelItemSpacing` = 8dp，与弹窗内条目列表一致），调用方不得自加外边距。

### 3. 现有调用点（新增场景照此对齐）

| 场景              | 位置                                       | leadingIcon                         |
| --------------- | ---------------------------------------- | ----------------------------------- |
| 选择封面来源（五源统一）    | `GameScreen.kt`（CoverSourcePickerDialog） | `ic_cover_source`（云端）               |
| 加入群聊（企鹅群聊/飞机频道） | `SettingsScreen.kt`（加入群聊弹窗）              | `ic_group_qq` / `ic_group_telegram` |
| 妙妙工具面板（启动 kr2 原生） | `MainScreen.kt`（`AppSidePanel` 的 `content` 槽） | `ic_settings_engine` |
| 妙妙工具面板（解封包工具） | `MainScreen.kt`（`AppSidePanel` 的 `content` 槽） | `ic_sheet_archive` |
| 妙妙工具面板（输入与手柄） | `MainScreen.kt`（`AppSidePanel` 的 `content` 槽） | `ic_input_gamepad` |

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
| 应用设置开关（色调切换等）                        | `AppSettingsActivity.kt`                          | `SwitchPreference` |
| 应用设置「导航栏样式」四选一                      | `AppSettingsActivity.kt`                          | `OverlayDropdownPreference`（非布尔选择，见本节第 3 条例外） |
| 封面来源启用开关（行内）                         | `CoverScraperSettingsActivity.kt`（CoverSourceRow） | `Switch`           |

### 3. 例外

- 三态「跟随全局 / 开 / 关」等**非布尔**选择（如 `PerGameSettingsScreen.kt` 的 `OverrideSwitch`）不属于开关按钮范畴，用 Miuix `OverlayDropdownPreference` 实现，不受本节约束。

- 引擎原生代码（engine 模块）内的开关不属于 Compose 层，不适用本节。

***

## 弹窗点击反馈统一规范

`AppAlertDialog` 弹窗（含 `title` / `text` / `confirmButton` / `dismissButton` 内容）内的
**所有按钮与组件不得使用任何点击或按压反馈效果**：涟漪、水波、缩放、高亮、透明度/颜色变化等一律禁止。
点击行为本身不受影响，仅去掉视觉/触感反馈。

### 1. 组件选型

- 弹窗文本按钮统一使用 `ui/common/DialogClickEffects.kt` 的 `DialogTextButton`（内部
  `Modifier.clickable(indication = null)`）；**禁止**在弹窗内直接使用 Material3 `TextButton` /
  `Button` / `IconButton`，也禁止使用自带按压动效的按钮组件。
- 弹窗正文中的可点击组件（输入框、列表项、开关、单选等）必须禁用点击反馈：
  - 区域级：外层包 `CompositionLocalProvider(LocalIndication provides NoIndication)`（`NoIndication` 由 `ui/common` 提供）；
  - 单点级：对 `Modifier.clickable` 显式传 `indication = null`；
  - 共享条目组件：`AppNavItem` 显式传 `indication = null`。
- 遮罩层点击关闭已由 `AppAlertDialog` 内置 `indication = null`，无需调用方处理。

### 2. 现有调用点（新增场景照此对齐）

| 场景 | 位置 | 处理方式 |
| --- | --- | --- |
| Winlator / Web 端口等文本值弹窗（全局） | `ui/settings/SettingsScreen.kt` | `DialogTextButton` + 输入区包 `NoIndication` |
| 单游戏覆盖文本弹窗（Winlator 容器、Web 端口等） | `ui/settings/PerGameSettingsScreen.kt`（`OverrideText`） | `DialogTextButton` + 输入区包 `NoIndication` |
| 添加 PC 游戏弹窗 | `ui/game/PcGameAddDialog.kt` | `DialogTextButton` + 行内 `indication = null` |

### 3. 页内按钮（同一意图，非弹窗）

`AppScreenScaffold` 的 `WithoutPressIndication` 只影响走 `LocalIndication` 的组件；Material3 的
`Button` / `TextButton` 内部显式使用 `ripple()`，**不受其约束**。页内需要无按压反馈的按钮统一使用
`ui/common/NoPressFeedbackButtons.kt`：

- 填充按钮：`NoRippleButton`（`tonal = true` 为浅底次级样式，可用于「前往授权」类动作）；
- 文本按钮：`NoRippleTextButton`。

| 场景 | 位置 | 组件 |
| --- | --- | --- |
| 解包 / 封包页（授权 / 选择目录 / 解包 / 封包 / 重新扫描 / 更换目录） | `ui/archive/ArchiveUnpackActivity.kt` | `NoRippleButton` / `NoRippleTextButton` |

### 4. 存量对齐

其余既有弹窗（`GameScreen`、`LaunchErrorDialog`、`CoverScraperSettingsActivity` 等仍有 Material
`TextButton`）与页内 M3 `Button` 按「改动即对齐」迁移：任何页面/弹窗被修改时，须同步替换为无点击反馈实现。

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

### 3. 中性色与语义色（非主题强调色）

以下中性色/语义色不是主题强调色；它们包含随外观与色调计算的动态值，统一从 `theme/Color.kt` 引用，**禁止**在页面中直接写 `Color(0x...)`：

- `PageGrey` 页面背景色、`NavWhite` 卡片/导航栏组件色；二者由应用设置「色调切换」控制是否互换。`TextColor` 正文深灰、`UnselectedGrey` 导航栏未选中灰。

- 语义色：`colorScheme.error`（错误/删除）、引擎封面色（`EngineType.coverColor()`）、封面占位白字等。

- **主题色实底组件**（背景为不透明 `primary` 的按钮/徽标等）内的文字与图标**固定使用白色**（`Color.White`），
  **禁止**使用 `MaterialTheme.colorScheme.onPrimary` 等会随主题色变化的跟随色；浅底（primary 加透明度）
  的次级样式才使用主题色文字。

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

- **玻璃系外观风格（应用设置 → 外观风格 = 复古玻璃 / 高级玻璃）**：页面背景固定深色画面
  （复古 = 黑灰渐变 + 主题色对角环境光；高级 = 游戏封面拼贴的盒式模糊底图 + 压暗 + 暗角，
  见 `ui/common/glass/AmbientBackdrop.kt` 与 `theme/AdvancedGlassStyle.kt`），
  `PageGrey` 透明、`TextColor` 恒浅色；卡片/条目/弹窗/输入框统一 0.5dp 发丝描边（`Modifier.glassBorder()`，
  高级玻璃为「上亮下暗」渐变描边并可在面板上叠 `Modifier.glassSpecular()` 顶边高光），
  高级玻璃卡片为浅色磨砂膜（14% 白），弹窗/抽屉面板为 `rememberAdvancedGlassPanelSurface()` 从页面背景取色的渐变（不得写死灰色；抽屉渐变必须画在内容层，外层 modifier 会因 anchors 布局偏移而错位）。
  此模式下「外观模式」
  与「色调切换」不可用（置灰），色调轮盘保持可用（兜底色斑随主题色变化）。
  高级玻璃的绘制是独立实现（`theme/AdvancedGlassStyle.kt`），复古玻璃的 `GlassStyle.kt` 不参与其材质；
  新增组件必须走动态常量与 `glassBorder`，不得硬编码玻璃色值。真 backdrop 采样仅限白名单
  （液态玻璃底栏、透镜底栏、高级玻璃悬浮默认导航条、悬浮按钮导航的主子按钮）；弹窗/抽屉是独立窗口，**无法**采样主窗口内容，
  只能用背景取色渐变 + 遮罩 + 光学描边，不要为它们接 backdrop。

- **底部抽屉/面板（`ModalBottomSheet`）→ 按「页面灰底」处理**：`ModalBottomSheet` 的 `containerColor` 通常取 `colorScheme.background`（浅/深随色调切换，等同页面背景），因此抽屉内条目（`AppNavItem` 等）必须传 `NavWhite`（灰底白卡），**不要**套用「弹窗白底灰卡」用 `PageGrey`——否则 item 与抽屉背景同色融为一体（如游戏操作抽屉 GameActionsSheet）。

- **游戏操作抽屉（`GameActionsSheet`）关闭拖拽手势**：统一传 `sheetGesturesEnabled = false`，只允许遮罩/返回键关闭。
  原因：M3 `ModalBottomSheet` 的关闭判定是「位移 > 56dp 或速度 > 125dp/s」任一命中即关闭（`BottomSheetDefaults`
  的 `PositionalThreshold` / `VelocityThreshold`，1.4.0 起 `rememberModalBottomSheetState` 不暴露这两个阈值），
  且列表到顶后的剩余手势/惯性会转交抽屉，导致用户"滑动稍微快一点"就误关抽屉。新增抽屉照此对齐；
  内容滚动与条目点击不受影响。

- 页面背景 → `PageGrey`

- 文字 → `TextColor`

通过 `MiuixTheme`/`MaterialTheme` 的 `colorScheme` 取容器背景属于**反例**（例：弹窗内项目用
`MiuixTheme.colorScheme.surfaceContainer` 不会随色调切换变色，应改用 `theme/Color.kt` 对应容器常量——
页面条目 `NavWhite`、弹窗内条目 `DialogItemSurface`）。
仅当某 scheme 颜色确为实时计算且随色调切换变化时才允许引用。

### 4. 层级要求

- 任何页面根组件必须包在 `TyranorNextTheme {}` 或 `MiuixSettingsTheme {}` 内，且主题必须最外层（Activity `setContent` 中包裹）。

- `MiuixSettingsTheme` 只提供 Miuix 主题，页面内部若用到 `MaterialTheme.*`（如弹窗、typography），外层仍须有 `TyranorNextTheme`（所有现有 Activity 均已满足，新增页面须遵循）。

- XML / drawable 资源中**禁止**出现主题色（启动图标等除外）。

***

## 测试与审核

使用 Android CLI 做设备审查前，先发现并阅读其中适用的审查技能；工具或技能不可用时记录缺失条件，继续可执行的本地审查，不编造已安装或已通过的结果。`--sdk=/tmp/androidsdk` 是既有验收环境示例，须替换为本机 SDK 路径。

必须强制性多个相互独立的 Agent 或 子Agent 从不同角度重新检查代码，例如：
代码逻辑审核：检查实现逻辑、状态流转、边界条件以及潜在逻辑错误。
架构审核：检查模块职责、依赖关系、代码耦合以及是否破坏现有架构。
代码健壮性审核：检查异常处理、空值、资源释放、线程安全、生命周期以及极端输入。
回归风险审核：分析修改是否可能影响现有功能、其他引擎或既有调用链。
代码规范审核：检查代码风格、命名、重复代码、无效代码以及是否符合 AGENTS.md 和项目现有规范。

JVM 回归使用 JUnit 4，位于 app/engine 各自的 `src/test/`；覆盖扫描、设置解析、存档、输入、宿主生命周期与图形策略。测试聚焦行为和边界，不镜像实现，也不为纯文档修改新增业务测试。真机用例与 release R8 probe 的构建、安装和能力边界分别见 [androidTest](app/src/androidTest/README.md) 与 [releaseProbe](app/src/releaseProbe/README.md)；普通 Android CI 未自动运行设备验收。

设备测试 JNI probe 的构建脚本当前只选择 Linux/macOS NDK host 路径，Windows 不能直接照搬这些测试 APK 构建命令；这不影响普通 App 构建与 JVM 测试命令的适用说明。

Room schema 变更须提交 `app/schemas/` 并添加显式 `Migration`；仅既有 v1→v2 与降级恢复保留破坏性重建策略，不扩大例外。

***

## 构建

- 使用仓库 Gradle Wrapper；CI 使用 JDK 17。安装 app 平台 `android-37.0`、engine 平台 `android-36`、Build Tools 37.0.0、NDK `28.0.13004108` 与 CMake `3.22.1`，通过未跟踪的 `local.properties`（`sdk.dir=...`）或 `ANDROID_HOME` 指定 SDK。`minSdk=26`、`targetSdk=36`，原生库仅 arm64-v8a。

| 命令（仓库根目录） | 用途 |
| --- | --- |
| `./gradlew assembleDebug --no-daemon` | 构建 Debug APK，输出到 `app/build/outputs/apk/debug/` |
| `./gradlew testDebugUnitTest --no-daemon` | 执行 app 与 engine 的 JVM 回归 |
| `./gradlew :app:lintDebug :engine:lintDebug --no-daemon` | 执行两模块 Android lint |
| `python tools/check-hardcoded-ui-strings.py` | 离线检查三语言字符串键与 Kotlin CJK 字面量 |
| `./gradlew -p buildSrc graphicsResourcesRegression --no-daemon` | 验证图形资源归属与校验逻辑 |
| `./gradlew :engine:jsHarness --no-daemon` | 用本地 Node.js 验证 Web v2 桥；也接入 `:engine:check` |
| `./gradlew :engine:dkToolsHarness --no-daemon` | 用本地 Node.js 验证 DKTools 索引接管、模式隔离与回退；也接入 `:engine:check` |
| `cargo test --manifest-path engine/rust/Cargo.toml --locked` | 修改 XP3 Rust 时运行宿主测试 |
| `./gradlew :app:printAppVersion --no-daemon` | 输出版本名与自动推导的 versionCode |

Windows PowerShell 使用 `./gradlew.bat`；Linux/macOS 使用 `./gradlew`。Python、Node、Rust 按相关任务安装；普通 Gradle 构建不运行 Rust 重编。执行 `check` 时仅在明确缺 Node 的场景可用 `-PskipJsHarness` 跳过，并在验证记录中说明。

`HIKARINAGI_CLIENT_ID` 可由环境、`local.properties` 或 Gradle 属性提供（按此顺序取值）；它配置 OAuth 客户端，不应填写用户 token。不要提交签名密钥、认证数据或商业游戏资产。测试安装采用隔离包名 `TYRANOR_APPLICATION_ID=com.tyranor.next.ethornelltest`，避免覆盖正式安装；PowerShell 用 `$env:TYRANOR_APPLICATION_ID = 'com.tyranor.next.ethornelltest'`，操作后恢复。

- 发版规则（issue #79）：只修改 `app/build.gradle.kts` 的 `appVersionName`；`versionCode` 由 `versionCodeOf()` 自动推导
  （`major*1_000_000 + minor*1_000 + patch`，minor/patch 必须 < 1000），禁止手写或回退 `versionCode`。
  beta-release workflow 会前置校验 versionCode 严格递增，并在构建后核对 APK 实际 versionCode（不一致即失败）。

