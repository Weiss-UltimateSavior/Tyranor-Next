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

- 新增功能按域放入 `core/game`、`core/engine`、`core/cover`、`core/patch`、`core/unpack`、`core/settings`、`core/auth`、`core/updater`、`core/process` 等包。

### 3. 界面 UI 交互层

- 目录：`app/src/main/java/com/tyranor/next/ui/`

- 职责：Compose 页面、Activity 壳、弹窗、导航、顶部栏、搜索框、用户输入、加载态与错误态。

- 规则：只能调用功能抽象层；禁止直接 import `com.core`、`com.akira`、`com.yuri`、`org.tvp`、`org.libsdl`、`org.cocos2dx`、`bridge` 等底层引擎包。

- 页面按功能域放入 `ui/home`、`ui/game`、`ui/engine`、`ui/settings`、`ui/cover`、`ui/patch`、`ui/save`、`ui/archive`、`ui/auth`、`ui/onboarding`，公共组件放入 `ui/common`。

### 4. 资源归属

- `engine/src/main/assets`：底层引擎运行资源与共享引擎脚本源头。

- `app/src/main/assets`：App 自有资源（当前为 RTP）。共享脚本由 Gradle 从 `engine/src/main/assets` 同步到 `app/build/generated/assets/engine`，不要手工建立第二份脚本源。

- `engine/src/main/nativeplugins`：共享 Native 插件 so 源头。
- `app/src/main/nativeplugins`：插件 manifest 与 app-only 插件源头。

- 插件 zip 生成到 `app/build/generated/assets/nativeplugins`（未压缩 zip 容器再压成 `<engine>.zip.xz`，减小 APK 体积）。禁止在 app 与 engine 两边手工维护同一份二进制或共享脚本；插件源合并通过 `DuplicatesStrategy.FAIL` 拒绝重名，脚本单一源归属仍需评审检查。

- 大体积引擎运行库（当前 `libsiglus.so`、`librfvp.so`、`libethornell.so`、`libkrkrsdl3.so`、IJK 三件套、System35/4 + `libSDL2x.so`）仍以 `engine/src/main/jniLibs` 为源头，但**不进 APK 的 `lib/`**：Gradle 打成 `app/build/generated/assets/engine-runtime/<pack>.zip.xz`，首次启动对应引擎时由 `com.core.nativeplugin.NativeRuntimePack` 解压到 `files/engine_runtime/<pack>/`，JNI 桥按绝对路径 dlopen（IJK 走 `IjkRuntime` 的 `IjkLibLoader`）。新增此类运行库时须同步 `NativeRuntimePack` 的分片常量与 `app/build.gradle.kts` 的 `bundledEngineRuntimePacks`（两处 id 与库名保持一致）；`packaging.jniLibs.excludes` 由该清单自动推导，不要再手写。

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

一级页面（导航对应的首页 / 游戏 / 引擎 / 设置）使用 `com.tyranor.next.ui.common.AppTopBar`；
二级页面使用 `com.tyranor.next.ui.common.AppSecondaryTopBar`（加粗返回箭头 + 标题，可保留右侧操作）。
禁止页面手写顶栏结构；二级占位页可复用 `PlaceholderPage`。

### 0. 统一入口（AppTopBar / AppSecondaryTopBar）

- 一级页面调用 `AppTopBar(title, ...)`，二级页面调用 `AppSecondaryTopBar(title, ...)`；两者共用状态栏留白、64dp 标题行和附加区布局。
- 参数约定：
  - Material 页面（默认）：不传 `background`/`contentColor`，组件默认 `Color.Transparent` / `PageTextColor`。
  - Miuix 风格页面（设置类 MiuixScaffold 的 `topBar` 槽）：传 `background = Color.Transparent`（顶部栏背景恒为透明，交由根部背景层——`MiuixTheme.colorScheme.background` 实为 `PageGrey`，传它会盖住玻璃/根部背景）、`contentColor = PageTextColor`，并设 `contentWindowInsets = WindowInsets(0.dp)`。
  - 需要按页面背景取文字色的页面：保持 `background = Color.Transparent`，传 `contentColor = TextColor`，背景仍由页面根层提供。
  - 右侧图标用 `trailing` 槽传入 `TopBarIcon`；标题下方的折叠内容（如游戏页搜索框）用 `underTitle` 槽。
- 新页面/组件禁止再书写「背景层 + statusBarsPadding + 64dp + titleLarge Bold」结构。

### 1. 结构

- 顶部栏由公共组件内部使用 **Column + Row**，页面不自行使用 Material3 `TopAppBar` 替代；Miuix 页面壳按下文约定接入。

- 页面由外层 `Column` 组装为「顶部栏 → 正文内容」，Material 页与 Miuix 页均保持上下分离布局；Miuix 页由透明 `MiuixScaffold` 的 `topBar` 槽承载对应层级的公共顶栏，正文位于顶栏下方，不启用顶栏模糊叠层。

- 一级顶部栏不放返回按钮；二级顶部栏内置无底色的加粗返回箭头（24dp 图标、48dp 触控区），触控区距标题 9dp，图标用 drawable，颜色跟随标题的 `contentColor`（默认 `PageTextColor`）。按钮通过宿主 `OnBackPressedDispatcher` 触发系统返回，复用页面的返回处理与退出转场，无需页面传回调。

### 2. 高度

- 标题区高度固定为 **64dp**。

- 顶部栏整体无需在 64dp 之外再叠加额外高度。

### 3. 标题

- 标题**居左**，水平内边距 `horizontal = 16.dp`，纵向居中。

- 标题字号使用 `MaterialTheme.typography.titleLarge`。

- 标题**必须加粗**：`fontWeight = FontWeight.Bold`。

### 4. 背景色

- 顶部栏背景**恒为透明**（`AppTopBar` 默认 `Color.Transparent`），露出页面根部背景层：默认外观风格为纯色底（`PageGrey`）+ 主题色粒子缓慢漂浮（`theme/DefaultPageBackground.kt`，约 30fps、仅在绘制阶段更新，系统关闭动画时静止），复古玻璃为玻璃页面渐变，白雾玻璃为模糊图片底图。标题与图标统一使用 `PageTextColor`（白雾玻璃为近白；Miuix 页须显式传入，不能依赖 `MiuixTheme.colorScheme.onBackground` 的深色卡片文字槽）。
- 页面根容器（`MiuixScaffold(containerColor = …)`、页面级 Box）同样**不得铺不透明背景色**（一律 `Color.Transparent`），背景由根部背景层负责；否则会把根部背景整片盖掉。

- **玻璃系外观风格（复古玻璃 / 白雾玻璃）**：页面背景透明，顶栏保持透明（露出渐变/图片/色斑与环境光）。
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

> 设置类页面若使用 `MiuixScaffold`，`containerColor` 取 `Color.Transparent`（背景由根部背景层绘制）；`topBar` 槽调用对应层级的公共顶栏，并设 `contentWindowInsets = WindowInsets(0.dp)`，不要自行重写顶栏结构。

### 7. 顶部栏图标按钮

- 顶部栏右侧图标按钮**必须使用公共组件** `com.tyranor.next.ui.common.TopBarIcon`（排版规范对齐游戏页顶部栏），**禁止**自行用 `IconButton`/`Icon` 拼装。

- 组件排版：`Image` 渲染 + `size(34.dp)` 触控区 + `clip(RoundedCornerShape(6.dp))` 圆角 + `clickable` + `padding(4.dp)` 内边距，并 `ColorFilter.tint` 主题色；组件左侧自带 `padding(start = 2.dp)`，保证相邻图标之间留白统一。

- `tint` 由调用方传入当前主题色：Material 页面传 `MaterialTheme.colorScheme.primary`，Miuix 页面传 `MiuixTheme.colorScheme.primary`。

- 顶部栏图标一律使用 drawable 资源（`R.drawable.ic_*`）；需要新图标时在 `res/drawable` 新建资源（PNG 或 vector XML），禁止直接用 `Icons.*` 矢量图标充当顶部栏按钮。

### 8. 明文豁免

- **明文豁免（按键方案布局编辑器）**：`ui/settings/PadLayoutEditScreen.kt`（`PadLayoutEditActivity`）**不设顶部栏**，编辑画布占满全屏（含系统栏区域）——为有意设计，不受本节 0–7 条约束。理由：该页的核心契约是与游戏内按键层**同视口**（按钮位置按视口归一化存储，横竖屏按比例重映射），顶栏会压缩画布高度、使「所见即所得」失效。操作方式与退出方式由进屏 Toast 说明，保存经编辑面板内的「保存」触发，退出走系统返回键。**该豁免仅限本页**，其余页面一律使用对应层级的公共顶栏，不得援引。
- **同一豁免的延伸（该页复用 engine 原生编辑 UI）**：本页把 engine 侧的原生按键层与编辑面板（`com.core.input.VirtualPadView` / `VirtualPadEditPanel`，由 core 门面 `PadEditorHost` 提供 `rootView`）整体当作画布，因此**该页内的自绘弹窗与文字尺寸**同样随本页豁免应用统一弹窗材质、圆角与两档排版（面板为深色卡片、随游戏画面同视口呈现，见 `VirtualPadEditPanel.showDarkDialog`）。延伸豁免**同样仅限本页与游戏内按键层**：设置域的其它弹窗（`AppAlertDialog`）必须照常遵守弹窗点击反馈、动态面板取色与 `AppComponentShape` 规范。**理由修正**：该页 Activity 固定 `sensorLandscape`（见 `AndroidManifest.xml`），编辑器本身不经历横竖屏切换；「横竖屏按比例重映射」描述的是**游戏内**按键层的行为。

***

## 动效与视觉反馈的实现边界（强制）

页面只声明发生了什么（选中、内容就绪、删除、错误、开始关闭等），通过下表的具名入口播放效果。**同一种效果只有一处实现**。不得在另一个页面复制动画协程、曲线、阈值、绘制公式或手势状态机；只共用一个弹簧 token 不等于共用效果。已有公共控件内置的动效直接使用，不再在调用处包一层。

| 页面意图 | 应使用的入口 / 实现归属 |
| --- | --- |
| 主标签与引擎分类切页；独立页面推入、返回 | 常驻页共用 `TabPageMotion` 的弹簧与 `tabPageTransform`，两种宿主保留各自的交互时钟。Activity 走 `startActivityWithPageTransition` / `AppScreenActivity`；背景、翻折和遮罩由 `PageTransitionBackground`、`depthFoldStack`、`pageEnterFold` 处理。 |
| 抽屉、侧栏、弹窗、下拉、提示条 | 抽屉使用 `AppBottomSheet`（内部复用 `MotionSheetController` / `SheetBackdrop`），其余复用 `AppSidePanel`、`AppAlertDialog`、`OverlayDropdownPreference`、`AppNotice`。公共窗口负责遮罩与释放触摸；灵动岛的变形、阴影、排队、按住/滑动与离页时机归 `NoticeHost` / `NoticeCenter`。业务页只提交内容及回调。 |
| 入场、重播、增删补位、拖拽排序、删除消散 | 入场用 `EntranceState` / `staggeredEntrance`；有稳定 key 的 lazy 列表、网格、瀑布流在原修饰符位置调用 `listItemMotion()`。仅原本快速补位的场景用 `ListItemPlacement.Reflow`。普通 Column 的补位用 `reorderPlacement`；滤镜链的拖动归 `GraphicsChainMotion.kt`；游戏删除走 `GameRemovalMotion.kt` 与 `dissolve`。 |
| 内容替换、区块显隐、读数与反馈 | 用 `BlurCrossfade`、`BlurVisibility`、`ExpandVisibility`、`ExpandSectionVisibility` 或 `RollingNumberText`，按原效果选有无模糊与是否等待退场。失败、等待、占位、图标回弹、滚动、触觉走 `shakeOnTrigger`、`DelayedProgress` / `breathing`、`shimmerSweep`、`bounceOnChange`、`scrollJelly`、`MotionHaptics`。 |
| 控件、按压、拖动 | 行和卡片用已有公共控件、`pressSquish` / `pressSquishGroup`；开关、滑条、搜索、进度、可用态、刷新、分类指示器由对应控件持有。跟手输入与松手弹簧分别处理；不要给拖动每一帧重新建动画。 |
| 封面、横向卡片、可见性与换肤 | 图像加载用 `rememberCoverReveal`，卡片翻页、视差和指示点用 `ParallaxCardPager` / `CardPagerIndicator`，封面流用 `CoverFlowMotion.kt`。常驻页显隐走 `skipDrawWhenHidden`，重设置页分帧用 `stagedItem`。换肤、语言与系统深浅色交接走 `rememberThemeSwitch`、`ThemeRevealHost` / `SystemThemeSync`。 |
| 只有一个业务域理解的编排 | 引擎容量环用 `rememberStorageSweep`；归档步骤、选色与进度归 `ArchiveMotion.kt`；游戏浮层、裁剪与引导分别归游戏域、引导域的具名组件。业务对象和导航留在页面；可复用的运动计算归公共层。不要为各页另建只转发相同参数的壳。 |

- **新增效果**：先找同语义入口。没有入口时，跨页面原语放进 `ui/common/motion/`，组件固有的效果放进对应公共组件，独有行为封装在功能域内，补充上表或入口说明。禁止在页面内用 `Animatable`、`animate*AsState`、`animateItem` 等另写一份。窗口 XML、平台/库的原版效果和自绘效果由各自的入口管理。
- **两档与性能**：每个入口负责自己的普通档回退，原有补间按原时长保留，不一概改成瞬时。增强档保留曲线、时序、阈值、打断/取消、触摸和无障碍语义。共用规格不等于共享控件的时钟。整页变换走绘制阶段图层；底栏样式、图标运动与底栏玻璃不参与迁移。
- **验收**：运行 `python tools/check-motion-boundaries.py`（[检测范围与例外](tools/motion-boundaries.md)）。评审还须核对自绘和手势、修饰符顺序、普通与增强档、取消/重入、系统动画关闭；涉及尺寸、遮罩或窗口时检查手机与平板横竖屏。静态门禁不能证明画面或性能等价。
- **窗口和返回手势**：Android 14 及以上的二级页通过 `AppScreenActivity` 在创建和切换档位时预注册同一组关闭动画；旧系统在 `finish()` 后设置。切到最近任务本身不能当作页面返回。主界面左缘系统返回从手势开始记边缘，提交才打开侧栏；取消不导航，返回键不能继承上一次的边缘。Android 12 及以上的桌面根任务正常返回时移到后台，重新打开保留当前标签。

- **启动检查与抽屉关闭**：SystemNNN 正在准备启动时，重复点启动不能发起第二次请求；返回、点遮罩和下拉仍遵循当前档位的抽屉关闭规则。不要用“正在启动”锁住抽屉，也不要让已关掉的抽屉因异步回调重新出现。

## 跨页面一致性要求

- 一级页统一使用 `AppTopBar`，二级页统一使用 `AppSecondaryTopBar`（或二级占位骨架 `PlaceholderPage`），禁止页面手写顶部栏。

- 新增页面时：页面主体内容放入 `Column` 的正文区域即可，顶部栏保持相同。

- 顶部栏下方禁止放置页面说明/描述文案；正文区域应直接展示该页面的实际内容或列表。

- 页面切换动画必须保持统一：主 Screen 可见 Tab（首页可关闭；默认四页，关闭后三页）间切换使用水平弹簧移动动画；其他独立 Activity 在增强档统一**水平侧推**（普通档保留原有上下转场）——进入时新页从右侧推入、上一级向左让开 30% 并压暗，退出/返回原路倒放。统一经 `ui/common/motion/PageTransitions.kt`（`startActivityWithPageTransition` / `AppScreenActivity.finish()` 已接入），不得再为单页手写窗口动画。侧推还带「景深」（只改绘制，停稳不变）：下层页面随转场压暗并向后翻折、褪色（翻折景深）——`PageDepth` 一个进度同时驱动压暗 `pageTransitionScrim(depth)` 与翻折 `depthFoldStack(…, push = depth)`，翻折只作用在页面**内容**上（页面根背景不动）；主界面只有页面区翻折（`LocalPageDepth`，`MainTabPages` 读取），**底栏不参与**（底栏样式与图标的运动一律不动，只随整个窗口一起左移与压暗）。`AppScreenScaffold` 已内置，新增 Activity 页面沿用即自动具备；不要用 XML 窗口动画给整窗缩放 / 旋转 / 模糊（会连带底栏）；也不要给新页面左缘加渐变阴影（实测像一条脏的暗带，已撤掉）。侧推曲线（`page_push.xml` 与 `MotionSprings.PushEasing`，两处数值必须一致）是单调减速曲线（0.22, 0.61, 0.36, 1）：起步就在全速、越近停位越慢；旧曲线「先加速再减速」前几帧每帧 17 → 40px，手感发黏。窗口在整窗滑入时，页面内容**不再自己淡入 / 升起**——两套动画叠在一起，页面滑动途中只有半透明的卡片影子，像发虚；`AppScreenScaffold` 在侧推进场的那段时间里关闭入场闸门（`EntranceGate`，`rememberEntranceState` 读取），闸门打开后（约 0.7s）才弹出的弹窗等照常播放入场。**下层页面的压暗 / 翻折要等新页第一帧画出才开始**（`PageTransitions.deferPush` / `notifyPushStarted`，`AppScreenScaffold` 在第一帧后通知；新页没通知时按 220ms 兜底）：新 Activity 创建 + 第一帧会占住主线程几十毫秒，若在 onPause 就开始，这段时间动画时钟已经在走，阻塞结束的第一帧会一下跳过 25~40% 的进度（像顿一下再突然变暗），与窗口侧推也不同步；同步后压暗 / 翻折从侧推出现的那一帧才起步，前几帧的增量均匀（约 0.1 / 帧）。景深的收回只在「确实被推入过下一页」之后才在 onResume 执行（`PageDepth` 观察者里的 `pushed` 标记，onPause 同步置位），普通的 onResume 与「普通」档不空跑补间。**新页自己也带入场效果**（`PageEnter` + `pageEnterFold`，`AppScreenActivity.pageEnter`）：侧推只有 0.4 秒，下层页面被盖住后一闪而过，只给下层页面加景深会「进得快就看不到」；新页入场时绕右缘向后倾 14° 并褪色，随滑入转正、恢复颜色，退出（`finish()`，只在第一次调用时倒放）倒放，与窗口侧推同曲线同时长，不增加任何时间；**退出倒放必须走 `PageEnter.playTo`**，它只在页面组合的作用域（`AppScreenScaffold` 里 `rememberCoroutineScope()` 绑定、离开组合时解绑，带 MonotonicFrameClock）里跑——Activity 的 `lifecycleScope` 没有帧时钟，在那里 `Animatable.animateTo` 会直接抛 `IllegalStateException`，返回时整个 App 进程崩溃重启（表现为「所有进出界面都要重新加载、超级卡顿」，曾因此发生过一次，`PageEnterTest` 守着），不要在 `lifecycleScope` / `viewModelScope` / 自建作用域里做任何 Compose 动画；系统动画倍率为 0 时直接就位；Activity 被系统重建（旋转 / 折叠屏 / 字体变化）时从「已就位」开始，不重播；**「普通」档创建的页面同样从「已就位」开始**（`AppScreenActivity.onCreate` 必须先同步动效档位再创建 `PageEnter`）：普通档不播入场，进度若停在 0，之后在本页里把「界面动效」切到「增强」，翻折会按「还没进来」画出来，页面永远停在最大倾角并褪色（复现场景：普通档进入应用设置页 → 切到增强）；新增任何「随进度驱动、普通档不跑动画」的状态，初值都要取「已就位」，不要取动画起点。**从抽屉进入二级页面时整个界面一起翻折**：页面区（`depthFoldStack` 外层绕左缘翻）与抽屉窗口（`motionSheetSurface` 里的图层按同一个 `PageDepth` 进度、按窗口高度的 `DepthPivotY` 换算本图层枢轴并绕左缘翻，页面区则取自身半高）共用一个进度，抽屉的压暗与褪色盖在图层里面、跟着一起翻。抽屉的褪色**不能用渲染效果**：渲染效果的离屏缓冲只有 `motionSheetSurface` 的布局框那么大，而抽屉画在 Material3 的偏移之后，会被整块裁掉；改用「饱和度」混合模式（API 31+，与页面区的褪色同档）盖一层灰；抽屉的翻折单独一层，不与下面的位移 / 拉伸共用枢轴（冲过停位时拉伸以底边为枢轴，共用会把抽屉拉歪）；水平枢轴取窗口左缘（抽屉比窗口窄时在布局框左侧）。翻折进度按 1/32 一档量化（`FoldSteps`，`depthFold` / `pageEnterFold` / `depthFoldLeft` 共用）：每帧都在变时，游戏页（封面网格）上抽屉打开那几帧要多花 7~10ms，量化可以让图层属性不变的帧不用重新合成。**量化不能太粗**：曾按 1/8 一档（每档 1.5°），而运动是「先快后慢」的减速曲线，最后一档要停留一百多毫秒再突然归零，看起来就是「快要抬起 / 快要落稳时顿一下才动」（所有翻折类效果——页面推入 / 返回、抽屉、弹窗、侧栏——都有）；现在 1/32 一档，最后一档不到半度。**起步要立刻**：窗口侧推从新页第一帧可见起就在走，而动画的第一帧进度恒为 0、第二帧才动；所以下层页压暗翻折与新页入场翻折起步都垫 `PushStartLead`（0.06），且不再多等一帧（实测倾角开始转正的时间从首帧后 53ms 提前到约 27ms）；返回时下一层页面在上层 `finish()` 时就开始收回（`PageTransitions.notifyPopStarted`，与窗口返回动画同一个起点），不等它 onResume。曾试过让页面点击时就提前翻折（以为能利用启动 Activity 那一百多毫秒的空档），实测没有任何效果：启动新 Activity 与旧页同在主线程，这段时间旧页根本画不出帧，已撤。
- **动效统一规范**：页面动效统一使用 `ui/common/motion/` 工具集（`MotionSprings` 弹簧 token、`pressSquish` Q 弹按压、`staggeredEntrance` 入场、`BlurCrossfade` / `BlurVisibility` / `ExpandVisibility` 内容变换、`RollingNumberText` 数字滚动、`bounceOnChange` 图标弹一下），新增动画优先用弹簧而非固定时长缓动。动效只改变运动过程：停稳后组件的位置、尺寸、颜色、圆角与排版必须与无动效时完全一致；系统关闭动画时弹簧瞬间完成。
- **状态反馈原语与「内容永不瞬间替换」**：除上述工具外，`ui/common/motion/FeedbackMotion.kt` 提供 `shakeOnTrigger`（输入无效 / 操作被拒绝，传失败计数器）、`breathing`（加载中 / 等待中的文字或图标）、`shimmerSweep`（仅用于有限数量的加载占位块）；`ScrollMotion.kt` 提供 `rememberScrollJellyState` + `scrollJellyHost` / `scrollJelly`（只用于卡片式宫格 / 列表，设置行不用）。文字、图标、颜色、可见性、条件显示的块的变化都应过渡而不是瞬间切换。页面调用 `BlurCrossfade` / `ExpandVisibility` 等具名入口；颜色插值由相应控件或效果入口实现。
- **按压反馈统一**：所有可点控件都要有按压反馈，且规则统一——**单控件卡片**（卡片高度不超过约一行、里面只有一个可反馈控件）按压时**整张卡片连同里面的文字一起收拢**；**多控件合并卡片**（里面有两个及以上可反馈控件）按压某个控件时**只有那个控件自己的文字 / 值收拢**（卡片和其它行不动，参考应用设置页的「深浅色切换」）。实现：卡片统一挂 `pressSquishGroup()`，它按**内部行级反馈控件的数量**（≤ 1）加高度（≤ 约一行）判定是否整卡反馈，而不是只看高度；行级反馈**内置在条目组件里**（`SwitchPreference` / `OverlayDropdownPreference` / `ArrowPreference`，经 `Modifier.withRowPressSquish`），**调用处不要再手写** `pressSquish`——手写的也不会叠两层（`withRowPressSquish` 发现传进来的修饰符里已有行级形变就不再加）。新增条目类组件照此内置，不要让每个调用点各写各的（曾因此「有的控件有反馈、有的没有」）。带底部控件（滑条行）的行不做行级形变（拖动滑条时坐标会随图层偏移）；开关拇指拖动按**根坐标**计算位移，不受所在行正在做的按压形变影响。
- **按压与结果同拍**：`pressSquish` 在**按下的同一帧**就开始反馈（先朝浅一档落下，45ms 内没被滚动 / 拖动接手再压到位，同一条弹簧接力、速度不断），不要再「先观望一会儿再反馈」——真实的轻点常常只有 40–80ms，那样手指按着的整个过程什么也看不到、抬手那一刻才凹一下，和抽屉 / 弹窗挤在同一帧，完全不跟手。于是一次点按是两拍：按下 = 凹下去，抬手 = Q 弹回去，同时结果面出现。被滚动接手时平静收回，不回弹。
- **回弹的过冲不能被容器裁掉**：`pressSquish` 松手回弹会略微冲过停位（长按蓄力后松手约 3~5%，向外拉动时还会再拉长一点），卡片外面若包着会裁切到自身边界的滚动容器（`HorizontalPager` 等），冲出去的那一圈会被整齐地切掉——四个圆角变成直角、卡片像被框住一样「卡」一下（首页快捷启动卡曾因此被裁，松手回弹那几帧四个圆角都变直角）。这类容器用 `Modifier.overshootRoom(horizontal, vertical)`（`ui/common/motion/OvershootRoom.kt`）把容器放大一圈、对外布局尺寸不变，同时把同样的值作为容器的 `contentPadding`，页面内容仍是原来的大小，过冲就画在容器边界之内；横向余量要小于页间距，免得相邻页从余量里露出来（快捷启动卡：横向 9dp、纵向 6dp，页间距 10dp）；「普通」档没有 `pressSquish` 过冲，保持原样。新增「按压卡片放进 Pager」的场景照此处理；不被裁切的容器（普通 `Row` / `Column` / 懒网格内部）不用加。
- **弹窗与侧栏的遮罩 / 进场**：「增强」档下弹窗（`AppAlertDialog`）与侧栏的整屏遮罩只用窗口 dim（0.5，经 `syncDialogWindow(baseDimOverride = …)`，盖住状态栏与导航栏区域），不再叠一层 0.5 的自绘遮罩——原来两层叠起来约 0.8，整个页面几乎全黑，过重；「普通」档沿用原版两层。弹窗的进场弹簧是 `DialogEnterSpec`（响应 0.42s、阻尼 0.74），侧栏是 `SidePanelEnterSpec`（0.44s、0.78），不要用通用大面板进场 `surfaceEnter`（阻尼 0.2，小面板来回晃好几下显得轻浮）。
- **长按蓄力与拒绝按压**：有长按动作的卡片在 `pressSquish(charge = true)`（与 `combinedClickable(onLongClick)` 同挂）：按住约 0.15s 后缩放随时间线性继续加深（输入直驱，不是弹簧），长按成立（时长取 `viewConfiguration.longPressTimeoutMillis`，与 `combinedClickable` 相同）的一刻弹回，手指移出触摸范围则取消蓄力；普通点按完全不出现蓄力。外观与可点控件相同但当前不可点的控件传 `pressSquish(enabled = false, refuse = true)`：按下只沉一点点（临界阻尼、无过冲），抬起（没被滚动接手）横向轻摇约 3dp 并给拒绝触觉，不改颜色。
- **触觉门面**：新增业务动作的触觉统一经 `ui/common/motion/MotionHaptics.kt`，开关/离散滑条保留公共控件内已有的原生触觉，不在调用处叠加反馈。该门面（`ProvideMotionEnvironment` 绑定宿主）：只在「增强」档、只回应手指（距最近一次触摸 > 1.5s 的事件不震）、任意两次间隔 >= 30ms、刻度类 >= 50ms、拒绝类 200ms 内只一次。只在「提交」和「越过离散边界」时震（翻页过半 / 抽屉下拉越过关闭线 / 侧栏拉过打开线 / 删除确认后消散 / 拒绝 / 抖动；长按成立沿用系统 `combinedClickable` 自带的触觉，门面不重复震），普通点按、滚动、连续接近一律不震；`shakeOnTrigger` 自带拒绝触觉，调用处不要再手写。底栏与分类栏透镜的既有触觉不动。
- **翻页（Pager）类**：横向翻页统一用 `ui/common/motion/PagerMotion.kt`：偏移 `pageOffsetOf(page)` 驱动层差视差（背景层放大并走得慢、前景层多走一段并淡出，停稳恒等）、`rememberPagerSnapFling`（增强档吸附用布局弹簧）、`PagerTickHaptics`（手指拖动使当前页变化时轻点一下）、`dotCloseness`（指示点宽度 / 颜色 / 光晕随连续位置变化，布局 / 绘制阶段读取，翻页途中 0 重组）。首页窄屏快捷启动是第一个使用者；封面流保持原有 3D 形变（甩动时另加顺势侧倾，见下）。
- **应用内提示条（替代系统 Toast）**：提示一律走 `ui/common/notice/AppNotice.makeText(context, text 或 @StringRes, Toast.LENGTH_*, AppNotice.Kind.X).show()`，**不要再直接用 `Toast.makeText`**。「增强」档显示为窗口顶部居中的「灵动岛」胶囊（照 mochi-compose 的灵动岛提示重写，Apache-2.0，署名见 `THIRD_PARTY_NOTICES.md`；`NoticeHost` 槽位放在页面根 `ProvideMotionEnvironment` 与每个能画到顶部的 Compose 弹窗窗口里（含妙妙工具侧栏、封面搜索与快捷方式裁剪）：弹窗打开时画在弹窗窗口里、遮罩之上，弹窗关闭时在下一层槽位原位继续，不重播进场；状态都在进程级 `NoticeCenter`，不在槽位里）；「普通」档仍是系统 Toast（原版行为）。没有可用窗口时自动退回系统 Toast：没有前台界面（无界面的 Activity、后台完成）、最上层是抽屉（抽屉窗口画不到屏幕顶部，`MotionSheetEffects` 登记遮挡槽位）、槽位全部消失超过 1.5s——所以任何时候都不会丢提示。类型 `Kind` 决定图标与重音：`Success`（胶囊正文色勾）/ `Failure`（与胶囊底色配对的错误色叉，落稳后整条横摇 + 拒绝触觉，驻留 +0.8s）/ `Info`（胶囊正文色 i）/ `Limit`（操作被拒绝的说明，轻摇）；`@StringRes` 重载默认 `Auto`，按资源名关键词推断（`running/busy/full/limit/conflict/exists/already/unsupported/require` → 限制；`fail/error/unavailable/invalid/denied/missing/cannot` → 失败；`saved/imported/installed/success/done/enabled/copied/exported/written/applied/updated` → 成功；其余提示），**新代码请显式传类型**，不要依赖命名推断；字符串变量 / 结果文案传 `Info` 或按结果选类型。驻留 `max(基准, 1.2s + 55ms × 字数)`（SHORT 基准 2s、LONG 3.5s，上限 5s），手指按住提示条时暂停自动关闭的计时（未主动关闭时，松手后至少再留 0.8s）；跨页的 350ms 按实际显示时间独立计算，同条重发也不重置；同一条（同文案同类型）再来不重播进场，只轻弹一下并重新计时；不同的内容在同一容器里图标 + 文字模糊交叉替换、胶囊尺寸同步过渡；失败 / 限制类在最短驻留 1.2s 内不被成功 / 提示顶掉（排队，队列上限 2，旧的丢弃）；提示条只在自己的矩形内拦截触摸，其余区域完全穿透。**离开界面就收**：「离开当前界面」指进入二级页、退出二级页（页面 Activity 离开前台，`rememberHostEntry` 的 `ON_PAUSE` → `NoticeCenter.setResumed`），同级界面互切（底栏切页、引擎分类、弹窗开关）不算；离开时已显示满 350ms 的提示轻收淡出（由原绘制槽位收尾，旧槽位不能覆盖新提示的动画），不跟到下一个界面，刚发出不到 350ms 的（「保存后返回」这类）带到下一个界面继续显示；抽屉升起（`NoticeCover` 登记）时提示直接收掉。原因：抽屉窗口画不到屏幕顶部，提示被盖住后计时停了但没清掉，关抽屉后早已过期的提示又冒出来（从二级页返回、上一页抽屉仍开着时必现）；提示没有任何能画它的槽位超过 1.5s 才退回系统 Toast。**灵动岛的形态与手势**（常量与 mochi 原版一致）：进场是 120×36dp 的「岛」随 `NoticeCenter.appear` 长成内容大小的胶囊（自定义 `Layout` 在布局阶段按进度插值尺寸；内容透明度 `smoothstep(0.12, 0.6, appear)`；进场弹簧响应 0.4s / 阻尼 0.9），退场 190ms（`NoticeCenter.depart`：缩 3.5%、上移 18dp、灰着淡出）；按住胶囊整条放大 1.04、朝手指偏 4dp，并以手指为中心发光；上滑超过 24dp 或速度 > 600px/s 收起（与 mochi 原版一致；位移门槛仍是 dp），往下拉有橡皮筋，没有移动的松手（点按，长按后松手也算）= 收起，其余弹簧回位。手势的 `pointerInput` 必须放在图层变换**之外**，否则胶囊跟手上移的位移会把手指位置抵消掉。**配色按主题深浅**（`NoticeHost.noticeColors`）：浅色类（默认浅色、白雾玻璃）黑胶囊白字，深色类（深色模式、复古玻璃）白胶囊黑字。**投影**：整颗胶囊的两层高斯模糊（贴边小阴影 σ3dp / α0.06 + 往外衰减的大光晕 σ12dp / α0.15 / 下偏 2dp；参数是从 mochi 原版录屏逐像素量出来再拟合的，残差 < 0.6%），用 `BlurMaskFilter` 画，API 28 以下不画；**不要改回「几层渐扩的圆角矩形」**（线性衰减，会有一圈圈的环，层间边缘过于明显），也不要用系统 `shadow` / `Modifier.shadow`（底边一道实影）。**淡入淡出必须用 `CompositingStrategy.ModulateAlpha`**：默认的离屏缓冲只有胶囊布局框那么大，画在框外面的阴影会被切成一个灰方框（淡入 / 淡出时可见）。与行抖动同时出现的提示（`shakeOnTrigger` + 提示）触觉由门面去重为一次；提示出现时给一次轻点触觉（失败 / 限制类由横摇给拒绝触觉）。进度写在 `NoticeCenter.appear` / `depart`，只在图层块 / 布局块读取，提示期间 0 重组。
- **侧栏（妙妙工具）唤出手势：子内容优先，绝不误滑出**：`rememberSidePanelSwipeModifier` 挂在主界面根布局上，分两阶段——**观察阶段在 Main 传递**（子内容先处理）只观察、不抢：手势期间只要有任何控件消费了这次手势的位移（横向分页器 / 封面流翻页、滑条与开关拇指拖动、下拉滑选、拖拽排序、竖向列表滚动、分类栏透镜、滑动删除……），侧栏就整段放弃；竖向为主 / 向左 / 多指也放弃；没人接手且已是「向右、横向位移 >= 2 倍纵向位移」后才**接管，此后改在 Initial 传递消费**每个事件（Compose 的点击只在手指移出控件或事件被消费时才取消，不吞掉的话，在卡片上横着划一下并在卡片内抬手会被当成点击）。只有从**空白背景 / 左缘留白**起手（起点落在可点击控件上的不算，它们会消费按下事件）且滑过 40dp 才唤出；起点在可点击控件上的右滑被吞掉，既不唤出也不触发点击。**不要再把侧栏手势改回一上来就在 Initial 传递判定并消费**（曾因此从首页快捷启动卡 / 滑条 / 分页器的左侧往右滑时误弹出侧栏）。新增横向可拖动控件只需正常消费自己的拖动即可，不必再向侧栏登记排除；例外只有不消费事件的自绘拖动（用 `excludeStart` 或 `SidePanelGestureExclusions`）。
- **打断与重入**：抽屉收起途中（下沉到完全离屏之前，约 0.2 秒内）点抽屉内的条目 / 按钮 = 先把抽屉接回来再执行（`MotionSheetController.interact()` → `reopen()`），按下再拖 = 抓住抽屉从当前位置继续跟手（`dragBy` 内的 `reopenForGrab`）；收起一旦越过离屏线（抽屉当即被移出组合，此时执行动作会被取消丢失、连点还会重复启动），或页面跳转 / 交接收起已提交（`busy`），条目点击一律拒绝并给拒绝触觉。抽屉随页面被二级页盖住（`stayDuringNavigation`）时，「页面跳转已提交」的门控只撑到下层页面开始收回的那一刻（上层 `finish()` 的 `PageTransitions.notifyPopStarted`，或回到前台 RESUMED）就释放，不等压暗收完，否则返回过程中再点条目会被当成拒绝（无法再次进入 + 拒绝触觉）；推入 / 返回的起点登记按层级栈（`popLevels`）配对，连点只启动一个实例。主题揭开途中再次触发（连续切换深浅色 / 外观 / 色调轮盘确认）不再直接应用：把当前合成画面（含进行中的揭开）重新截下来当旧画面、从新的按压点重新扩散（进度时钟按代数接管，旧时钟自行结束）。
- **加载 / 状态变化补充**：被动等待的加载转圈（页面 / 区块首次加载）一律包 `DelayedProgress`（「增强」档 180ms 后才用模糊 + 缩放浮现，更快加载完的场景不闪转圈；「普通」档立刻显示）；用户刚点了按钮的进度（顶栏刮削转圈等）不要延迟。`ArrowPreference` 在「增强」档下带 `summary` 时，摘要变化原位模糊替换（用 Miuix 槽位重载自绘标题与摘要，字号 / 字重 / 颜色与字符串重载逐像素一致，已用截图比对）。顶栏刷新类图标传 `TopBarIcon(spin = true)`：点击转一圈（目标始终是 360° 的整数倍，连点每次再转一圈）。
- **封面流与拖拽排序补充**：封面流甩动时卡片顺势侧倾（`rememberPagerVelocity`：滚动期间逐帧低通估速，只在图层块读取，停下归零；空闲时无逐帧工作），并在「增强」档的滑动 / 甩动中暂缓封面解码（`rememberCoverBitmap(uri, isScrolling)`，「普通」档与原版一致）；封面流的卡也挂 `dissolve`，删除确认后与瀑布 / 网格卡一样播放消散；滤镜链拖拽排序：拿起的行除缩放外浮起一层投影（白雾玻璃不加，随提升弹簧进出），拿起给 `MotionHaptics.Kind.LongPress`、每次换位给 `Tick`。
- **删除消散与扯动**：删除一经确认，对象在逻辑上已经不存在，只是视觉还在播——消散期间该卡片的触摸由 `Modifier.dissolve` 自己吞掉并退出无障碍树（点击 / 长按处理器里**不要**再写 `if (dissolve.running) return` 之类的守卫）；确认后的删除用 `NonCancellable` 保证做完。卡片类删除用 `ui/common/motion/DeleteEffect.kt` 的 `DissolveState` + `Modifier.dissolve` / `dissolveTint`（先播消散再删数据；游戏库经 `dissolvingGames` 按 uri 登记）；`pressSquish` 的单个控件拖出范围后被橡皮筋「扯动」并弹回，整卡（`pressSquishGroup`，仅限约一行高的入口卡片）不扯动。这些只改变交互过程中的绘制，静止外观不变。首页「快捷启动 + 最近打开」共用同一个 `LazyVerticalGrid`，快捷启动卡是稳定 key 的整行网格项，「加载中 / 空状态 / 有记录」只替换其后的项；不要再拆成各画一份快捷启动卡的两个容器交叉淡入（删光记录时快捷启动卡会抖动）。删除消散播到约一半就删数据，补位用 `MotionTokens.reflow`。
- **下拉条目**：设置类下拉一律使用 `ui/common/OverlayDropdownPreference.kt`（与 Miuix 同名同参的替身，右侧当前值文字原地模糊替换），不要直接 import Miuix 的 `OverlayDropdownPreference`。凡内容发生变化且不是用户直接点击形成的反馈（值、读数、摘要、状态文案），都用原地模糊替换，且凡走 `OverlayDropdownPreference` 的下拉在「增强」档支持**滑选**：① 长按条目展开弹层后手指不抬起、直接滑向选项；② 弹层已展开时，在弹层里按住并上下滑动——一块统一高度、与文字居中的玻璃质感选择块**直接跟着手指走**（被所在行的中心轻轻吸住，用保留速度的弹簧追随，运动越快越被拉长），手指一按下按住的区域就出现主题色高光；松手时震动反馈、该选项立刻变成「已选定」的样子（主题色文字 + 勾选，不是整行染色），选择块（完整胶囊形）吸附到该项并轻轻一压再落稳，约 0.1 秒后与弹层一起渐隐收起（整套退出在 0.3 秒内结束，动画服务于效率、不拖时间；留下选项自己的「已选定」样子）；长按条目弹出弹层后那次触摸常被系统取消、其手势协程可能一直挂着，**不要用「长按手势是否还活着」之类的标记去拦截弹层里的新触摸**（曾因此选择块时有时无）；选项行本身不平移不缩放（否则它的方形背景会跟着动、露出直角边）；选择块的中心与显隐由**逐帧积分的弹簧**驱动（快照态，绘制阶段读取，与底栏透镜同款思路），不要用 `snapshotFlow + collectLatest + animateTo`：目标每个触摸事件都在变，会让动画每次只走第一帧就被取消，按下就滑时选择块一直透明 / 很浅；高光必须画**整颗胶囊的形状**（渐变在上半部消失），只画上半截高度的圆角矩形会因圆角被压小而从胶囊上角凸出（深色下可见）；深色下白色高光与描边要比浅色淡得多；选中生效的那几帧重组过去后才开始播放确认动画；只按纵向位置选项；普通点按不受影响；弹层位置与 Miuix 原版一致。只替换真正变化的那一段文字：值 / 读数文字用 `ui/common/motion/BlurText.kt` 的 `BlurText` / `BlurTextWith`，永远只有新旧两层、不会出现两个清晰的字重叠。**模糊替换的时序（用户明确要求「丝滑的变换，不要先快后慢，不要突然出现、突然消失，点到就跟着糊」）**：新旧两层共用同一个进度 s（`tween(360ms, CubicBezierEasing(0.3, 0, 0.3, 1))`，起止对称柔和，快速连续变化时 200ms），**互补地**同时变化——旧层透明度 `1 − smooth(s)`、模糊 `smooth(s, 0, 0.85)`，新层透明度 `smooth(s, 0, 0.8)`、模糊 `1 − smooth(s, 0.15, 1)`——合成可见度始终接近 1，叠在一起的时刻两层都是模糊的；起始进度带 `START_LEAD`（0.05），点选那一帧画面就已经在动（弹簧从静止起步，头几十毫秒几乎没变化，像没反应）。不要再把旧层「先淡出、新层再淡入」错开：那会在中段出现几十毫秒几乎什么都看不见的空档（实测可见度最低 0.08，现在最低 0.62，度量：录屏逐帧取文字区域的亮度能量 / 最终文字能量）。两种模式：**数值读数**（默认 `partial = true`）去掉相同前后缀后只模糊变化的那一段，其中**一串数字算一个整体**（「3 级」→「4 级」只有数字在糊；「20 dp」→「29 dp」十位个位一起糊，「dp」不动）；**选项值**（下拉当前值、`ArrowPreference(value)`、档位文字，传 `partial = false`）整段一起模糊替换，即使后半截相同也整个换（「浅色」→「深色」）；条目右侧当前值优先用 `ArrowPreference(value = …)` / `OverlayDropdownPreference`，滑条上方的「标题 + 读数」一律用 `SliderValueHeader`，不要自己拼 `Row { Text; Text }`；其它整块内容替换用 `BlurCrossfade`。语言切换造成的整屏文字变化默认**不**动画（`animateOnLocaleChange = false`），只有被点选的控件自己的值（语言下拉的当前值）才动画，避免整屏模糊造成卡顿。新增带值 / 读数的选项务必走这些通用组件，不要裸写会变化的 `Text`。
- **主题切换过渡**：切换深浅色、外观主题等整屏换皮的设置，把「应用新主题」交给 `ui/common/motion/ThemeReveal.kt` 的 `rememberThemeSwitch()` 返回的函数执行：先截下当前画面（半分辨率），应用新主题后从点击处向四周扩散揭开新界面：前沿是很宽的羽化渐变（没有一条清晰的圆边），叠一层主题色柔光与几粒飘散的柔光点，旧画面轻微放大后退、新主题略大地落稳；动画在新主题首次重组的重帧之后才开始计时，进度由「每帧最多推进 24ms」的帧时钟驱动（重帧不会吞掉动画的一大段）；宿主已挂在 `ProvideMotionEnvironment`，新页面自动具备，「普通」档与截屏失败时直接应用。**切换界面语言（应用设置「语言」）也走同一个 `rememberThemeSwitch()`**：整屏文字一起换，先截旧画面、再换语言，从点击处揭开新语言的界面（整屏文字不再各自模糊替换，见 `BlurText` 的 `animateOnLocaleChange`；选了当前语言什么都没变，不播放过渡）；今后新增任何会让整屏重组的全局设置（语言、主题、外观……）都照此接入。系统深浅色自动变化（下拉通知栏 / 定时切换）走同一套过渡：`MainActivity` 与所有 `AppScreenActivity` 子类在清单里声明 `android:configChanges="uiMode"`，由 `SystemThemeSync` 在 `onConfigurationChanged` 里先保持旧主题（`AppThemeColors.systemDarkHold`）并截下旧画面，再应用新主题、从屏幕顶部中央揭开；新增 Compose 页面 Activity 必须同样声明 `uiMode`，否则系统切换会销毁重建页面、没有过渡。揭开的绘制保持「离屏图层 + `DstOut` 混合擦除」，不要改用 `clipPath(ClipOp.Difference)`（实测每帧更贵，掉帧率 0.6% → 5.7%）。 **揭开的节奏（主题 / 语言 / 配色统一，同一个控制器）**：时长按「前沿推进速度恒定」定——`revealMillis`：圆心到最远屏幕角的距离（dp）× 1.4ms，夹在 [720, 1000]ms，所以从屏幕上方的语言行、下方的深浅色行、系统自动切换（顶部中央）揭开，前沿每毫秒扫过的 dp 数一样（原来固定 900ms，上方圆心的前沿比下方快约 1/4）；进度曲线 `PathInterpolator(0.3, 0.1, 0.55, 1)`（起步柔和但立刻有反应、峰值约平均速度 1.6 倍、尾部缓收，原曲线 (0.25, 0.2, 0.2, 1) 峰值 2.5 倍）；羽化前沿外沿 = 最远角距离 × 2.0 × 进度（原 2.6：前沿太宽，整屏被擦掉的 90% 在前 0.28 秒内就完成，其余 0.6 秒是几乎看不见的拖尾，体感急促又拖沓；现在肉眼可见的扫过约占整段的 57%），远角残留的旧画面在最后 20% 进度里整体淡出收尾。度量方法：录屏逐帧与最终帧求平均差，取「完成度 10%→90%」的时间（深浅色切换：原 183ms → 现 300ms，前沿速度因圆心不同而不一的情况消除）。新增会让整屏重组的入口照旧接 `rememberThemeSwitch()`，不要在调用处自带时长 / 曲线。
- **公共组件已内置的动效不得在页面侧重复**：`AppTopBar` 标题与 `TopBarIcon` 的页面入场（页面只需把 `AppTopBar` 放进 `ProvideEntrance`）、`AppSearchField` 的聚焦弹跳（IME 提交仍委托给调用方）、`AppNavItem` 的摘要出现 / 文案替换与可用态过渡、`DialogTextButton` / `NoRipple*Button` 的可用态颜色过渡、`AppSidePanel` 条目扇出，页面侧不得再包一层同类入场或弹跳，否则会叠加两层。
- **动效 token 与可打断**：新增 / 修改动画一律按语义选用 `ui/common/motion/MotionTokens.kt` 的 token（出现 / 消失 / 内容替换 / 布局 / 弹出 / 控件 / 按压 / 大面板进退场 / 页面 / 数值），不要随手写时长数字，保证全应用节奏一致；动画一律用弹簧并允许随时打断（从当前位置与速度转向新目标），不得用「动画进行中忽略操作」的守卫，不得在动画途中 `snapTo` 造成跳变。动画值尽量在 `graphicsLayer` / `drawBehind` / `layout` 等绘制或布局阶段读取，避免逐帧重组。
- **常驻页面的渲染开销（性能，不改显示效果）**：HWUI 每帧都要遍历整棵渲染节点树（`prepareTree`，同时阻塞 UI 线程做同步），而主界面（四页）与引擎页（四个分类页）为了切页不卡**常驻组合**了所有页面——隐藏页 alpha=0 不可见，几十上百个图层节点照样每帧被遍历。隐藏页一律挂 `Modifier.skipDrawWhenHidden { 可见 }`（`ui/common/motion/PageVisibility.kt`，挂在页面容器的 `graphicsLayer` 之后）：容器不画内容 = 子节点不在显示列表里 = 不被遍历，页面一显现当帧再画回来；`visible` 必须是只在显隐边界翻转的 `derivedStateOf`（`alpha > HiddenPageAlpha`），不能直接读逐帧变化的 alpha，否则切页动画每帧都要重录容器的显示列表；靠 Compose 绘制回调才画出来的原生视图（WebView 首页）不参与；「普通」档不加。实测（真机 debug 包）：首页空闲时渲染线程 CPU 16.9% → 9.2%、整进程 36.8% → 28%，设置页滚动整进程 79% → 71%、卡顿帧 1.45% → 0.79%，抽屉打开卡顿帧 3.7% → 2.6%。新增「常驻多页」的容器照此处理。
- **页面「是否在前台」别当普通参数传**：主界面页面的 `isActive` 真正有人用的只有两处——新手引导的锚点上报（只在引导期间有意义，完成后上报是空回调）与 Web 首页的暂停 / 恢复，所以 `MainTabPages` 只在引导期间（`onboarding`）或 Web 首页才传真值，其余时间传常量 `false`；否则切页时旧页和新页的根函数都因这个参数变化而整页重组（切页卡顿帧 6.7~7.1% → 1.6~4.7%，最长一帧 57~61ms → 25~36ms），锚点的位置回调节点也不再常驻。新增 Tab 页同理，不要把会随切页变化的值作为普通参数传进页面根函数。
- **整页变换走绘制阶段图层，别写在整页容器的 `graphicsLayer` 里**：Compose 的 `RectManager` 在图层的**位置类属性**（缩放 / 位移 / 旋转 / 透视距离 / 枢轴）变化时，会把该节点整棵子树的包围盒沿每层变换重新算一遍（`insertOrUpdateTransformedNodeSubhierarchy` → `boundingRectInRoot`，O(子树节点数 × 深度)）；透明度、渲染效果、裁剪不是位置类属性，不触发。整页容器（几百个节点）上的翻折 / 切页位移与缩放每帧一次，真机 debug 包上抽屉打开 / 页面推入的每一帧多花约 3ms（主线程 20%~27%）。所以这些统一用 `ui/common/motion/DrawPhaseLayers.kt` 的 `drawPhaseLayers`（翻折：`depthFoldStack` / `pageEnterFold`）与 `TabPageMotion.kt` 的 `tabPageTransform`（主界面与引擎页切页）：变换放在自己持有的 `GraphicsLayer` 里，进度变化只改图层属性并 `view.invalidate()`，Compose 只看见恒等的外层图层。要点：①录制必须用 DrawScope 的成员扩展 `layer.record(size) { drawContent() }`，不要用带 `density / layoutDirection` 的 `GraphicsLayer.record`（它用图层自己的作用域，`drawContent()` 会直接画到外面，图层是空的，表现为「翻折 / 褪色没了」）；②读进度由 `LayerSourceReads` 的 `SnapshotStateObserver` 单独订阅，不放进 `draw()`，否则每帧重录内容；③外层要有恒等的 `graphicsLayer`（`tabPageTransform` 已有透明度层，`drawPhaseLayers` 自带），让显示列表被缓存；④代价是命中测试不再跟随变换——只适合变换期间不接收触摸、或偏移很小的整页容器；**小控件（按压形变、条目入场、滚动果冻）不要这么做**，它们的子树本来就小，多一层 RenderNode 不划算；⑤页面与抽屉共用 `FoldAxis` 的角度/透视及褪色查表；抽屉使用自己的窗口坐标枢轴。实测对照见 CHANGELOG；诊断用 `simpleperf record --call-graph dwarf` + `--children`，看 `NodeCoordinator.updateLayerParameters` 占比。
- **重内容的二级页面分帧组合**：二级页面一次性组合太重时（应用设置约 12 个下拉 / 滑条 / 开关），新 Activity 的第一帧要 70~90ms，侧推一开始就顿一下，旧页的压暗翻折、新页的入场翻折也跟着卡。用 `rememberStagedCompose(last)` + `LazyListScope.stagedItem(order, staged) { … }`（`ui/common/StagedCompose.kt`）把列表项按组分帧放行：第一帧只组合页面骨架（第 0 组留空），之后每帧补一组，内容随页面滑入自上而下依次出现——窗口侧推由系统驱动、不受主线程拖累。只在「新页正在侧推进场」且「增强」档分帧（系统重建 / 恢复、「普通」档一次组合完）。组号要让每组的组合成本大致相当（按卡片分，重的卡片单独一组）。真机实测（应用设置，120Hz 屏）：点击后最长一帧 69~93ms → 29~44ms，点击到侧推可见约快 50ms；代价是 6~7 个 13~16ms 的中等帧（60Hz 下都在预算内，120Hz 下算卡顿帧，卡顿帧计数反而更多）。其它很重的二级页面照此接入；列表项已经很轻的页面不要用，白白多出几帧延迟。
- **性能度量（debug 包）**：`dumpsys gfxinfo <包名> reset` → 操作 → `dumpsys gfxinfo`（卡顿帧、分位数）+ 读 `/proc/<pid>/task/<tid>/stat` 的 utime/stime 算主线程 / RenderThread 的 CPU 占用（比卡顿帧更能反映低端设备的压力）；定位热点用 perfetto（`gfx view input wm am` 类别，`Choreographer#doFrame` 下看 `Recomposer:recompose` / `traversal` / `HardwareRenderer#syncAndDrawFrame`，渲染线程看 `prepareTree` / `Drawing`）与 `simpleperf record --app`（debug 包可直接采样，JIT 方法名可见）；需要看「每个可组合函数花多少」时临时实现 `androidx.compose.runtime.CompositionTracer` 并 `Composer.setTracer`，按 `info` 聚合自身 / 含子级耗时，用完删除。几个已知事实：debug（debuggable）包只跑解释器 + JIT，AOT 与基线配置都不生效（系统把 debuggable 包的编译过滤器强制降为 `verify`，`cmd package compile -m speed -f` 也无效），所以主线程代码量本身就是成本；同一份代码改成不可调试（`isDebuggable = false`）再 AOT（`cmd package compile -m speed -f <包名>`）后真机实测：冷启动 1.45s → 0.25~0.3s，首页空闲整进程 28% → 17%（主线程 16% → 6%），切页最长帧 24~32ms → 13~14ms，二级页推入最长帧 61~69ms → 20ms，抽屉打开卡顿帧 2.2% → 0.8%——debug 包「卡」的大头是运行时模式，不是代码；复现这个对照要先关掉 LeakCanary（它在不可调试包里启动即崩溃：`java.lang.Error: LeakCanary in non-debuggable build`，在 `src/debug/res` 里放 `leak_canary_watcher_auto_install=false`），LeakCanary 开 / 关本身对这些流程没有可测量的差别；常驻粒子背景（`DefaultPageBackgroundLayer`）约 30fps 整窗重绘，是空闲功耗的大头（空闲时整进程约 28~36% 单核），因为要保持动效显示效果，没有降帧率，每帧只能压低渲染树的开销；开着无障碍服务（如 GKD）的设备上 Compose 的语义同步会额外占用主线程，页面语义节点越少越好。；**评估「真实流畅度」不要用 debug 包**：隔离包名下直接 `TYRANOR_APPLICATION_ID=com.tyranor.next.motionv2 ./gradlew :app:assembleRelease`（没有 CI 签名时 release 用 debug 密钥签名，可覆盖安装；LeakCanary 只在 debugImplementation，不会崩），`adb install -r` 后 `adb shell cmd package compile -m speed -f <包名>`，再按同样流程测。同一份代码、同一部手机：二级页推入 / 返回的卡顿帧 5.6% → 1.5%，抽屉打开 2.8% → 0.8%，切页 3~4% → 0.6%，主线程 CPU 20% / 27% / 55% → 7% / 10% / 16%；首次进入引擎设置页最长一帧 99ms → 30ms，返回时销毁二级页 20~37ms → 5ms。用户说「第一次卡、之后几次好点」基本就是 debug 运行时的冷路径（解释执行 + JIT 未预热），不是代码里某处的问题；**进出二级页的「最后一刻」卡顿的已知来源（debug 包，真机 trace 实测）**：①返回动画收尾约 0.5s 处，系统派发 `activityDestroy`，主线程被 `performDestroy` 占住 20~37ms（几乎全是 `CompositionImpl.dispose`：几百个节点的 `onForgotten`、协程取消、懒列表节点释放，稳态下每次约 20ms），正好落在动画视觉结束之后的那几帧；释放组合无法拆开或提前（页面还在屏幕上），目前不处理；②首次进入重页面（引擎设置页，KRKR 内核约 15 个下拉 / 开关行）新 Activity 的第一帧约 90~100ms，之后各次不会更好，这是组合本身的量（解释执行下每行约几 ms），分帧组合试过（第一帧降到约 50~60ms，但每张重卡片单独要 15~46ms，落在窗口侧推刚开始的那段，等于把「开头停一下」换成「滑动中掉帧」，总量不变），已撤——只有卡片很轻、可以细分到每帧 ≤10ms 时才值得用 `StagedCompose`；③窗口侧推本身（系统驱动）在尾部是平滑的，逐帧测量页面左缘位置与内容位置都是单调衰减、倾斜约 0.35s 就不到 1 像素，不是「衔接」问题；动画结束后显示刷新率会从 120Hz 降到 30Hz（只剩 30fps 粒子背景），属于正常的降档。真机逐帧运动分析方法：`screenrecord` 拉下来用 `ffmpeg -vf showinfo` 取时间戳，按行 / 列剖面互相关估页面位移与倾斜，`dumpsys`/perfetto 加 `android.surfaceflinger.frametimeline` 数据源看显示帧间隔与 jank 类型
- **界面动效档位**：应用设置中独立的「界面动效」卡片可选「增强」（默认，新动效）与「普通」（原版 Tyranor 动效）。全局状态为 `ui/common/motion/MotionMode.kt` 的 `MotionMode.enhanced`（持久化 `AppSettingsStore.KEY_MOTION_ENHANCED`）。工具集函数已自行按档位退化；具名效果内部涉及两档的分支必须等价于 `if (MotionMode.enhanced) 新动效 else 原版动效`，保证「普通」档与原版完全一致。缺陷修复（如弹窗关闭后遮罩残留，见 `DialogWindowMotion.kt`）两档都生效。
- 原生 Dialog 必须由窗口宿主统一调用 `syncDialogWindow` 并在关闭时放行触摸（公共弹窗/侧栏走 `DialogWindowScrim`，搜索封面/裁剪走 `GameOverlayMotion.BindWindow` 与退出控制器），页面不要再同步一次：否则平台窗口自带的背景变暗会在卡片退场后再残留一段，并在此期间拦截点击。
- **常驻分页的列表必须按页面下标做 key（关闭首页后整页点不动的恶性 bug）**：`MainTabPages` 的页面列表随「显示首页」开关增删首页（`visiblePages`），`forEach` 里每页的 `remember { derivedStateOf … }`（`pageSettled`）和整页内容的组合状态如果不加 `key(page)`，会按「位置」错位复用——游戏页拿到首页「没停稳」的状态，选中页被判成不可交互，整页被「消费所有触摸」的挡层盖住：底栏还能切页，但所有页面内容（卡片、设置行）点不动、无障碍树里也没有页面节点；关闭首页后必现，冷启动也一样（状态流初值是默认「开」，第一次组合按四页画、随后收敛成三页，错位发生在这一次）。现在 `forEach` 内整体包 `key(page)`、`pageSettled` 用 `remember(motion)`，并在第一次组合前同步 `initHomePageEnabled`（避免先按四页画一遍）。**任何「列表 + 逐项 remember」的常驻结构，只要列表会增删头部项，都必须 key；`remember` 的值引用了外部变量就要把它写进 remember 的 key。** 诊断方法：`uiautomator dump` 看页面内容有没有节点、临时在 `SideEffect` 里打印每页的 `selected / interactive / settled / offset / alpha`。
- **多页切换不要现场组合**：同一屏内的分页（如引擎页顶部分类）提前组合好并常驻（空闲时预组合其余页），切换时只改各页的图层位移 / 透明度；用 `AnimatedContent` 现场组合新页会在切换那一帧掉帧。引擎分类与主标签**共用增强档效果**（普通档分别保留瞬切和主标签补间）（`TabPagerState` 的 `pageOffset` / `pageAlpha` + 宿主的景深缩放 `TabPageMotion.DepthScale`）：整宽水平弹簧位移（`MotionTokens.page`）+ 淡入淡出弹簧（`MotionTokens.fadeIn`）+ 景深缩放 0.06，只有旧页和新页参与，分页容器 `clipToBounds`；改其一必须与 `MainTabPages` 保持一致。列表内容里不要直接捕获会随切换变化的值（改捕获 `rememberUpdatedState`），否则切换时所有页的条目一起重组。页面顶层作用域也不要读选中项：用 `derivedStateOf` / lambda 传递（`rememberTabPagerState({ selectedTab }, …)`），每个分页的外壳单独成 composable（`EnginePageHost`）、每页的列表用 `remember(engines, …)` 固定，否则切页那一帧整页重组（实测 25ms → 12ms）。几个分页叠在同一个 `Box` 里时，**当前页必须用 `zIndex` 放在最上层**（`EnginePageHost`）：命中测试从最上层的兄弟开始，排在后面的「非当前页」挡触摸层会把压在它下面的当前页的触摸整片吃掉（曾导致引擎页中间区域列表滑不动、卡片点不了，只剩最左侧一条能响应）。新增引擎：加进 `EngineLauncher.supportedEngines`，并在 `engineTabOf` 的穷举 `when` 里指定分类，其余自动生效。
- **设置状态流订阅**：界面里读取设置类 `StateFlow`（`AppSettingsStore.*State` 等）一律用 `ui/common/SettingsState.kt` 的 `collectSettingAsState()`（随生命周期订阅），不要裸用 `collectAsState()`。设置页是另一个 Activity，盖在主界面上时主界面只是 STOPPED、组合却没停：裸订阅会让设置页里每拨一个开关，后台主界面都立刻重组（增删首页 / 引擎页四个分页 / 游戏卡片），占满同一个主线程，把设置页开关的动画挤掉（有的开关卡、有的不卡，取决于后台重组有多重；实测显示首页 99 分位 57~93ms、引擎页分类 32~46ms，改后全部 ≤ 9ms、0 卡顿帧）。例外（必须在后台也立刻生效）：界面语言 `ProvideAppLocale`、动效档 `ProvideMotionEnvironment`，以及受保护的导航文件里的导航样式 / 按钮位置。新增设置开关与设置状态流照此对齐。
- **动效性能**：新增逐元素动效优先写成 `Modifier.Node`（参考 `Entrance.kt` / `PressSquish.kt`），不要用 `composed`；动画值只在图层块 / 绘制 / 布局阶段读取，图层块里读到的持有者必须是快照状态（`invalidateLayer()` 不会重新执行图层块）。`app/src/main/baseline-prof.txt` 是真机运行收集的类级预编译规则，大量新增页面后可按同样方式重新生成。

- 组件统一圆角由应用设置「应用圆角调整」控制（**5–50dp，十档：5/10/15/20/25/30/35/40/45/50，默认 15dp**），默认外观、复古玻璃与白雾玻璃共用该值；列表项卡片、功能项卡片、弹窗等圆角组件统一引用 `theme/AppShapes.kt`：
  `AppComponentShape`、Miuix 组件用 `AppComponentCornerRadius`、
  抽屉顶部用 `AppSheetTopShape`；禁止再散落圆角字面量。外观风格不得自带圆角默认值或按风格覆盖该设置。

- **平板侧栏**：`isSideRailLayout()`（`isTabletScreen()` 命中且应用设置「平板侧边栏」开关开启，默认开）命中时主导航移到侧边（`ui/common/AppNavigationRail.kt`）：侧栏形态按外观风格取该主题「经典 MD 导航栏」（原「默认导航栏」）形态（默认与白雾玻璃用 Material3 侧栏，复古玻璃用悬浮玻璃柱），液态玻璃两档与「悬浮按钮」不适配侧栏；侧栏占布局宽度，底部留白归零。选中态图标动画统一用 `ui/common/NavigationTabIcon.kt`。

- **圆角豁免（液态玻璃 · 透镜底栏）**：应用设置「导航栏样式」选到「液态玻璃 · 透镜」后挂载的
  `ui/common/glass/EnhancedLiquidGlassNavigation.kt`（栏体 / 图标副本行 / 移动透镜三处）使用
  `theme/AppShapes.kt` 的 `AppNavCapsuleShape`（胶囊，圆角 = 半高），为本项目为该样式既定的连续胶囊轮廓，为有意设计。
  **该组件内也必须统一引用 `AppNavCapsuleShape`，不得就地新建等价的圆角形状**；该豁免仅限该组件，其余组件不得援引。

- **圆角豁免（悬浮按钮导航）**：「导航栏样式 = 悬浮按钮」后挂载的 `ui/common/FloatingGlassNavButton.kt`
  主按钮与弧上导航项使用 `CircleShape`（圆形是本样式既定形态），为有意设计；该豁免仅限该组件，其余组件不得援引。
  **材质固定为液态玻璃的浅 / 深两档**（浅色 = 白玻璃、深色 = 深色玻璃），**只按 `AppThemeColors.isDark` 取档，
  不随外观风格（默认 / 复古玻璃 / 白雾玻璃）分档**（本样式自带固定观感，是 3.5 节「组件必须走动态常量」的豁免；
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

- **圆角豁免（应用内提示条）**：`ui/common/notice/NoticeHost.kt` 的「灵动岛」胶囊（`NoticeSurface`）使用 `CircleShape`（圆角 = 半高，随尺寸从「岛」长成内容大小时始终是完整胶囊），为该提示既定形态，有意设计；该豁免仅限该组件，其余组件不得援引。

- 标准应用弹窗使用 `AppAlertDialog`（全屏封面搜索与裁剪等专用窗口见上方窗口宿主规则），背景由动态主题常量决定、圆角统一取 `AppComponentShape`：默认浅色通常为白底，深色模式使用 `NavWhite`，玻璃风格使用对应面板材质；不得写死白色或圆角数值。原生按键编辑器的明文豁免仍仅限该页与游戏内按键层。

## 页面内容文字尺寸规范

页面内容（顶部栏除外）只允许使用**两种**文字尺寸，与引擎设置页保持一致：

1. `MaterialTheme.typography.titleMedium`（大字号，15sp，在 `theme/Type.kt` 中全局覆盖，Material 默认 16sp）—— 卡片头、对话框标题、列表项主标题（可加粗）。
2. `MaterialTheme.typography.bodyMedium` —— 正文、行值、辅助描述、按钮文字、空态/错误提示。

- **禁止**使用 `bodySmall` / `bodyLarge` / `labelMedium` / `labelLarge` / `headlineMedium` / `headlineSmall` 等其它排版尺寸。

- 存量组件例外：`AppNavItem` 内部摘要当前使用 `bodySmall`，仅限该共享组件既有实现；页面不得据此新增第三档字号。

- Miuix preference 组件标题默认用 `headline1`(17sp)，已在 `MiuixSettingsTheme` 中全局覆盖为 15sp（`defaultTextStyles(headline1 = TextStyle(fontSize = 15.sp))`），使其严格落入两档并匹配 `titleMedium`；不要自行在单行上改字号。

- 顶部栏标题不受此限制，仍用 `MaterialTheme.typography.titleLarge` Bold。

- **明文豁免**：首页快捷启动卡（`ui/home/HomeScreen.kt` 的 QuickLaunchCard）以封面模糊图 + 黑色压暗遮罩为背景、白色文字展示，卡片内游戏名使用 `MaterialTheme.typography.titleLarge` Bold、引擎名使用 `MaterialTheme.typography.headlineSmall` Bold，均不受两档制限制；该卡片其余文字仍遵循两档制。

- **明文豁免**：游戏页卡片左上角的引擎类型角标（`ui/game/GameScreen.kt` 的 `GameCoverEngineBadge`，`GameCard` 与 `UniformGameCard` 共用，仅覆盖在封面上）使用 `MaterialTheme.typography.labelSmall`（11sp）——角标属封面上的小型标注，按两档制会过大；仅此一处豁免，其余正文仍遵循两档制。角标底色/文字色为**固定样式**（`theme/Color.kt` 的 `CoverBadgeBackground` / `CoverBadgeText`：半透明黑底 + 白字），**不随主题色与外观模式变化**。

- **明文豁免**：游戏页瀑布流卡片（`ui/game/GameScreen.kt` 的 `GameCard`）标题在 `bodyMedium` 基础上缩小 2sp（12sp），用于 3/6 列窄卡片；仅此一处豁免，其余正文仍遵循两档制。

***

## 搜索/输入框统一规范

全 App 的搜索过滤框与弹窗内单行文本输入框，必须统一使用公共组件
`com.tyranor.next.ui.common.AppSearchField`（`app/src/main/java/com/tyranor/next/ui/common/AppSearchField.kt`）。
**禁止**在页面内直接拼装 Miuix `SearchBar`/`InputField`，也**禁止**使用 Material 的
`TextField`/`OutlinedTextField` 充当搜索框或弹窗输入框。

### 1. 组件形态（Miuix 风格，非展开内嵌式）

- 组件内部固定为 `MiuixSettingsTheme + SearchBar` 模板，`expanded` 恒为
  `false`（内嵌式），不使用 Miuix 的展开式全屏搜索页形态。默认参数组合走 `InputField`；
  API 26/27 兼容、非恒等文本变换或显式键盘/IME/autofill 语义走组件内部
  `EmbeddedSearchInputField`，页面不得自行复制该分支。

- 前导图标固定 **26dp**，内边距固定 `SearchBarDefaults.LeadingIconStartPadding /
  LeadingIconEndPadding`，`tint` 统一取 `MiuixTheme.colorScheme.primary`——以上均由组件
  内部处理，调用方不要传色值/尺寸，保证全局样式单点可改。

### 2. 参数约定

- `query` / `onQueryChange`：必传，输入状态由调用方持有。

- `onSearch`：键盘 IME 动作回调。本地即时过滤场景可不传（默认空实现）；
  需显式触发时传入（如网络请求、回车即保存）。

- `imeAction`：键盘 IME 动作，默认 `ImeAction.Search`；普通搜索依赖默认值，用户名、URL、
  密码及弹窗内其它非搜索身份输入显式传 `ImeAction.Done`。

- `autofillContentType`：Compose autofill 内容类型，默认 `null`。用户名与现有密码按身份分别传
  `ContentType.Username` / `ContentType.Password`；URL 等无对应语义的字段保持 `null`。
  文本变换、键盘类型和 autofill 类型彼此独立，不得根据 `visualTransformation` 自动推断。

- `keyboardType`：键盘类型，默认 `KeyboardType.Text`；URL 字段传 `KeyboardType.Uri`，密码字段传
  `KeyboardType.Password`，用户名使用默认文本键盘。

- `visualTransformation`：只控制文字显示变换，默认 `VisualTransformation.None`；密码掩码不代表
  autofill 身份。密码字段必须另传 `keyboardType = KeyboardType.Password` 与
  `autofillContentType = ContentType.Password`，内部按密码键盘/身份补充 `password()` 无障碍语义。
  当前 API 没有 `secureInput` 参数；不得引用不存在的参数。

- `leadingIcon`：drawable 资源（`R.drawable.ic_*`）。搜索语义用默认 `ic_game_search`；
  其他语义必须传对应图标（如名称修改用 `ic_sheet_rename`），禁止搜索图标滥用。

- `iconContentDescription`：保留此参数名以兼容既有调用；实际同时作为输入框的无障碍 label，
  文案跟随字段语义，并不只描述前导图标。

- `modifier`：仅用于布局微调（如外边距），宽度由组件内部 `fillMaxWidth` 保证。

### 3. 现有调用点（新增场景照此对齐）

| 场景          | 位置                                 | 图标                | onSearch        |
| ----------- | ---------------------------------- | ----------------- | --------------- |
| 游戏库过滤       | `GameScreen.kt`（顶栏下方）              | `ic_game_search`  | 即时过滤，不传         |
| VNDB 封面搜索   | `GameScreen.kt`（VndbSearchDialog）  | `ic_game_search`  | `search()` 网络请求 |
| 安卓游戏选择过滤 | `AndroidGameAddDialog.kt`（已安装应用列表） | `ic_game_search` | 即时过滤，不传 |
| krkr 在线补丁过滤 | `KrkrOnlinePatchActivity.kt`（列表首项） | `ic_game_search`  | 即时过滤，不传         |
| 名称修改        | `GameQuickActionsDialog.kt`（RenameGameDialog） | `ic_sheet_rename` | Done，内容有效即保存 |
| WebDAV 地址/用户名/密码 | `WebDavSettingsActivity.kt` | 对应字段语义图标 | Done，显式 Uri/Text/Password 与身份 autofill |
| 云端游戏标识 | `SaveVersionsPanel.kt`（身份编辑） | `ic_sheet_rename` | Done，内容有效即保存 |
| 图形倍率 | `GraphicsNodeParameters.kt` | `ic_graphics_parameters` | Done，Decimal 键盘 |
| 输入目录 | `SettingsScreen.kt`（路径弹窗） | `ic_sheet_folder` | Done，内容有效即保存 |
| 目录/方案名称/端口/自定义 URL | `SettingsScreen.kt`（通用文本弹窗）、`PerGameSettingsScreen.kt`（`OverrideText`）、`InputSettingsScreen.kt`、`AppSettingsActivity.kt` | 当前为 `ic_sheet_rename` | Done；URL 为 Uri，端口为 Number |

***

## 标签栏统一规范

全 App 的「标签栏 / 分类栏」（横向排布、单选切换、选中项带背景指示器的场景）**必须**统一使用公共组件
`com.tyranor.next.ui.common.SlidingTabRow`（`app/src/main/java/com/tyranor/next/ui/common/SlidingTabRow.kt`）。
**禁止**在页面内手写指示器偏移、等分宽度、选中背景与文字取色（引擎页原自绘 `GlassTabRow` 已并入本组件）。

### 1. 组件形态与参数

- 选中项为**独立背景指示器**（不是逐项背景），切换时以弹簧「蠕动」滑到目标标签（前沿先到、后沿跟上，到位轻微回弹）；滚动态标签宽度不齐时宽度同步过渡。指示器圆角统一 `AppComponentShape`，玻璃系由组件内部调用 `glassBorder` 补 0.5dp 发丝描边。
- 指示器底色与文字颜色由组件内部按外观风格分派，**调用方不得传色值**：
  默认风格指示器 `NavWhite`、选中文字 `TextColor`（不跟随主题色，加粗作选中标识）、未选中 `onSurfaceVariant`；
  复古玻璃 `GlassSurfaceHigh` + `GlassText`/`GlassTextSecondary`；
  白雾玻璃指示器 `NavWhite`（白纱）、选中 `TextColor`（深色）、未选中 `PageSecondaryTextColor`（深底浅字）。
- 标签高度统一 42dp，文字统一 `MaterialTheme.typography.titleMedium`，选中加粗；无障碍统一 `selectable(selected, role = Tab)`。
- 参数：`tabs`（标签文案）、`selectedIndex`（越界自动收敛）、`onTabSelected`、`modifier`（仅外边距）、`scrollable`（形态开关，默认 false）。

### 2. 两种形态

- `scrollable = false`（默认）：标签**等分宽度**，适合数量固定、宽度一致的分页。
- `scrollable = true`：标签按**内容宽度横向滚动**，适合数量多 / 宽度不齐的分类；两端内置 12dp 留白，选中项在屏幕外（程序化回退、进程恢复）时自动滚动到可见（首次直接定位，之后带动画）。页面侧不得用 `LazyRow` 自行拼装等价物。

### 3. 现有调用点（新增场景照此对齐）

| 场景 | 位置 | 形态 |
| --- | --- | --- |
| 引擎页顶部分页（玻璃外观） | `EngineScreen.kt` | 等分 |

> 等分形态可传入 `TabPagerState`（`ui/common/TabPagerState.kt`）：选中块就是**底栏「液态玻璃 · 透镜」同一个运动控制器**（`ui/common/glass/LensMotionController`，赴按 / 跟手 / 吸附刚度统一 1000、阻尼比 1，取法与 `EnhancedLiquidGlassNavigation` 完全一致，不做速度拉伸），所以分类栏选中块的运动与底栏透镜完全一致：点选时带按压脉冲滑过去，静止时与原白色胶囊指示器一模一样。配合 `Modifier.tabDragSelect` 在分类栏 / 标题区唤出玻璃透镜：分类栏**按下即抓取**（与底栏透镜一致：DOWN 同帧起跳，滑块马上朝手指飞去，不等长按；分类栏传 `grabImmediately = true`，点按也走这条路径），标题区没有标签可按，仍是长按（约 0.26s）或开始横向拖动后抓取；抓取后先原地按压、同帧朝指尖移动（分类栏的透镜**不放大**：按压时选中块与里面的文字都保持原大小，放大只属于底栏透镜），按住时以绝对位置连续跟手，**松手才切换**（只切到所选的那一页，分页只动旧页和新页，中间的分页不会闪过）；长按未满的点按 / 滑动（含左缘右滑唤出侧栏）原样放行，长按接管期间经 `SidePanelGestureExclusions.suppressed` 暂时屏蔽侧栏手势。透镜玻璃层近乎不透明、直接盖住底下的原文字，只露出里面的那一份——不要把原文字淡出一部分，否则会和里面的文字叠出错位重影；里面的「选中样式」（粗体）只在透镜静下来时才平滑出现（`lensSettle`），飞速经过标签时文字保持常规，不要让文字随透镜经过快速闪动（像抽搐）；透镜里的粗体 / 常规两份文字在绘制阶段交叉淡入，滑动中不重组。改动透镜运动请改 `LensMotionController` 的统一参数，不要在分类栏里另起一套弹簧。分类栏透镜按压时不放大（`LensSpec` 的 `pressedScaleX/Y = 1`，运动参数与底栏一致）；边缘描边是中性的白 → 淡灰，不带主题色；阴影用系统 `shadowElevation`（自己画的多层阴影观感更差）；描边不要用 `Modifier.border`（胶囊形路径在左右正中会冒出斜接尖角），用圆角连接的 `drawOutline`。
> 引擎页所有外观风格（含默认）现在都走本组件（静止外观与原 Miuix `TabRow` 一致：白色胶囊指示器、42dp 高、6dp 间距）：切换时指示器 Q 弹「蠕动」（前沿带过冲、后沿慢跟、被拉长越多纵向压得越扁），刚选中的标签文字弹一下；新增场景一律用本组件。标签文字的按压反馈只做以中心为轴的轻微缩放（`pressSquish(follow = 0.dp)`），**不要**让文字跟手偏移或被扯动：它会随手指落点滑到指示器的上下半边，看起来「太自由」。

***

## 功能跳转条目统一规范

所有「功能跳转列」——即点击后进入 / 跳转 / 打开下一级的条目（如封面来源列表、弹窗内的群聊/频道项、设置里的二级跳转项等），**必须**统一使用公共组件
`com.tyranor.next.ui.common.AppNavItem`（`app/src/main/java/com/tyranor/next/ui/common/AppNavItem.kt`）。
**禁止**在页面/弹窗内手写 `Row`/`Column` 拼装此类条目，也禁止混用 Material 的 `ListItem`/`ColumnItem` 等。

### 1. 组件形态与参数

- 排版固定：圆角 `AppComponentShape`（统一取「应用圆角调整」值，见 `theme/AppShapes.kt`）+ 背景取 `theme/Color.kt` 常量 `NavWhite`（页面场景默认）+ 内边距（横向 16dp / 纵向 12dp）+ 左侧图标 24dp + 右侧指示箭头 `BoldChevronRight`（统一加粗）。均由组件内部处理。

- 背景色约定（与白底弹窗对偶，详细见 3.5）：**页面上的条目**默认 `NavWhite`（页面背景 `PageGrey` → 灰底白卡）；**弹窗内的条目**必须传 `containerColor = DialogItemSurface`（默认风格 = `PageGrey` 白底弹窗灰卡；玻璃风格 = 亮玻璃面），保证条目与弹窗背景反色、层次分明；玻璃风格下 `PageGrey` 透明、`NavWhite` 变为半透明玻璃面，`DialogItemSurface` 自动切换为亮玻璃面。

- `drawSurface`：是否绘制条目自身的圆角背景与描边，默认 `true`。条目已位于
  `MiuixCard` 等卡片表面时传 `drawSurface = false`，使用卡内平面样式，避免卡片套卡片；
  点击、内边距、图标与文字排版保持不变。

- 标题用 `MaterialTheme.typography.bodyMedium`、颜色取 `TextColor`；摘要可选，用 `bodySmall` + 半透明辅助色。均不依赖 `colorScheme.surface*`（遵循「组件背景色统一规范」）。

- `leadingIcon`：左侧图标 drawable；未提供时组件自动使用**默认占位图标** `DEFAULT_LEADING_ICON`，不允许调用方在不该出现空图标时留白。

- `showLeadingIcon`：是否展示左侧图标，默认 `true`。**纯动作条目**（如存档导出/导入/删除等无图标的动作项）可传 `false` 隐藏图标位，此时标题顶格排列；不得为隐藏图标而乱传占位图。

- `onClick`：点击回调；传 `null` 表示不可用（整条变灰且不可点击）。

- 进入跳转的 icon 统一用共享的 `BoldChevronRight`（加粗），组件内置，调用方不传。

- 设置类页面的 `ArrowPreference` 统一使用 `ui/common/ArrowPreference.kt`（Miuix 同名组件的加粗箭头版本），不要直接 import Miuix 的 `ArrowPreference`。

- `showArrow`：是否显示右侧跳转箭头。**「进入下一级」的跳转条目必须 `true`（默认）**；「执行动作」的条目（如启动游戏、删除等不产生跳转的动作）传 `false` 隐藏箭头，避免误导为可跳转。

- `verticalPadding`（高度豁免条款）：条目纵向内边距默认 **12dp**。**豁免**：底部抽屉面板（`ModalBottomSheet`）内展示的条目可使用更大纵向内边距（当前游戏操作抽屉取 17dp，条目总高 +10dp），仅限抽屉内条目；`AppAlertDialog` 弹窗内条目一律保持默认 12dp。

- `leadingIconTint` / `titleColor`：显式覆盖左侧图标与标题颜色（如游戏操作面板用主题色 `MaterialTheme.colorScheme.primary` 统一图标、危险条目用 `colorScheme.error` 上色）。传 `null`（默认）时使用面板默认行为（深色染白、浅色原图 / `TextColor`）。

- 深色模式适配：左侧图标（PNG drawable）与右侧箭头在深色模式下自动染色为 `Color.White`，浅色模式保持原色不变；不可点击状态（`onClick = null`）保留 `contentAlpha` 衰减。这一适配由组件内部完成，调用方无需处理。

- 设置页入口卡片（`SettingsScreen.kt` 的 `SettingsItemIcon`）左侧图标统一染 `MiuixTheme.colorScheme.primary`，跟随主题色调。

- 颜色判断依赖全局 `AppThemeColors.isDark` 快照，切换外观模式自动重组刷新，与整体主题保持同步。

### 2. 侧边栏面板（AppSidePanel）内条目

妙妙工具侧边栏（`ui/common/AppSidePanel.kt` 的 `content` 槽）内的条目必须使用 `AppNavItem`，统一采用**与弹窗条目一致的完整形态（带图标 + 右侧指示标）**：

- `containerColor = DialogItemSurface`（面板底色为 `NavWhite`，条目反色灰卡，与弹窗内条目为同一对偶关系）。
- `leadingIcon` 必须传与功能语义对应的具体图标，不得使用组件默认占位图标，也不得隐藏图标位。
- `leadingIconTint = MaterialTheme.colorScheme.primary`：左侧图标统一跟随主题色调。
- `showArrow = true`（默认）：面板内条目统一保留右侧指示标，**「执行动作」条目（如启动 kr2 原生）同样不隐藏箭头**——本条优先于上文「执行动作传 `false`」条款。
- 条目之间的纵向间距由 `AppSidePanel` 内部统一加（`SidePanelItemSpacing` = 8dp，与弹窗内条目列表一致），调用方不得自加外边距。

### 3. 现有调用点（新增场景照此对齐）

| 场景              | 位置                                       | leadingIcon                         |
| --------------- | ---------------------------------------- | ----------------------------------- |
| 选择封面来源（五源统一）    | `GameScreen.kt`（CoverSourcePickerDialog） | `ic_cover_source`（云端）               |
| 加入群聊（企鹅群聊/飞机频道） | `SettingsScreen.kt`（加入群聊弹窗）              | `ic_group_qq` / `ic_group_telegram` |
| 妙妙工具面板（启动 kr2 原生） | `MainScreen.kt`（`AppSidePanel` 的 `content` 槽） | `ic_settings_engine` |
| 妙妙工具面板（解封包工具） | `MainScreen.kt`（`AppSidePanel` 的 `content` 槽） | `ic_sheet_archive` |
| 妙妙工具面板（WebDAV 存档同步） | `MainScreen.kt`（`AppSidePanel` 的 `content` 槽） | `ic_save_cloud` |
| 妙妙工具面板（输入与手柄） | `MainScreen.kt`（`AppSidePanel` 的 `content` 槽） | `ic_input_gamepad` |

***

## 开关按钮统一规范

所有需要**布尔二态开关**（开/关）的场景，必须统一使用 **Miuix 风格** 的开关组件，禁止混用 Material3 的 `Switch` 或其他库的开关，避免深浅色 / 主题色 / 触感样式不一致。

### 1. 组件选型（按场景二选一）

- **设置条目式开关**（标题行 + 右侧开关，如设置清单中的配置项）：统一使用
  `com.tyranor.next.ui.common.SwitchPreference`（与 Miuix 同名同参，外观完全一致；「增强」动效档下右侧开关为 iOS 26 手感：
  拖动跟手、果冻回弹、轨道颜色随拇指位置由灰到主题色连续过渡；**按住（含按住拖动）时拇指保持正圆、只放大 1.127，与 Miuix 原版一致，不能拉成椭圆 / 胶囊**，只有松手后的滑动 / 回弹里才按速度略微拉长（果冻感，按压程度越大拉长越少，平滑过渡）；「普通」档直接使用 Miuix 原组件）。

- **行内独立开关**（列表项右端内嵌、无标题行的纯开关）：统一使用
  `com.tyranor.next.ui.common.Switch`（checked / onCheckedChange，规则同上）。

- 带刻度的离散滑条（扫描深度、应用圆角调整等）统一使用 `com.tyranor.next.ui.common.Slider`（与 Miuix 同名同参、外观一致；
  「增强」档支持单点跳格、逐格轻震、拖出两端拉伸回弹；连续滑条与「普通」档沿用 Miuix 原组件）。

### 2. 现有调用点（新增场景照此对齐）

| 场景                                   | 位置                                                | 组件                 |
| ------------------------------------ | ------------------------------------------------- | ------------------ |
| 设置清单开关（KRKR/ONS/Artemis/RPG Maker 等） | `SettingsScreen.kt`                               | `SwitchPreference` |
| 应用设置开关（平板侧边栏等）                        | `AppSettingsActivity.kt`                          | `SwitchPreference` |
| 应用设置「导航栏样式」四选一                      | `AppSettingsActivity.kt`                          | `OverlayDropdownPreference`（非布尔选择，见本节第 3 条例外） |
| WebDAV 总开关与同步提示 | `WebDavSettingsActivity.kt` | `SwitchPreference` |
| 封面来源启用开关（行内）                         | `CoverScraperSettingsActivity.kt`（CoverSourceRow） | `Switch`           |

### 3. 例外

- 三态「跟随全局 / 开 / 关」等**非布尔**选择（如 `PerGameSettingsScreen.kt` 的 `OverrideSwitch`）不属于开关按钮范畴，用 Miuix `OverlayDropdownPreference` 实现，不受本节约束。

- 引擎原生代码（engine 模块）内的开关不属于 Compose 层，不适用本节。

***

## 弹窗点击反馈统一规范

`AppAlertDialog` 弹窗（含 `title` / `text` / `confirmButton` / `dismissButton` 内容）内的
**所有按钮与组件不得使用涟漪、水波、高亮、透明度/颜色变化等点击反馈效果**。
唯一允许的按压反馈是动效规范中的 Q 弹按压（`ui/common/motion/PressSquish.kt` 的 `pressSquish`，只改绘制图层的缩放/跟手偏移，
松手回弹、静止时与原样一致）；`DialogTextButton`、`NoRippleButton`、`NoRippleTextButton`、`AppNavItem` 已内置。

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

- **Material 页面**（首页/游戏/引擎等 Tab、在线补丁等 Activity）：统一使用 `MaterialTheme.colorScheme.primary`（选中态图标/文字、按钮、开关、输入框聚焦指示等）。

- **Miuix 页面**（设置页/引擎设置/单游戏设置/应用设置/存档管理等）：统一使用 `MiuixTheme.colorScheme.primary`（Preference 图标、Slider、Switch、下拉选中等）。

- 图标 tint、高亮文字、选中态等一切"强调色"位置只能从上述 colorScheme 获取，**禁止**在页面里硬编码 `Color(0x...)`、`#307DEF`、`Blue40` 或任何品牌蓝。

### 3. 中性色与语义色（非主题强调色）

以下中性色/语义色不是主题强调色；它们包含随外观与主题色计算的动态值，统一从 `theme/Color.kt` 引用，**禁止**在页面中直接写 `Color(0x...)`：

- `PageGrey` 页面背景色、`NavWhite` 卡片/导航栏组件色。`TextColor` 正文深灰、`UnselectedGrey` 导航栏未选中灰。

- 语义色：`colorScheme.error`（错误/删除）、引擎封面色（`EngineType.coverColor()`）、封面占位白字等。

- **主题色实底组件**（背景为不透明 `primary` 的按钮/徽标等）内的文字与图标**固定使用白色**（`Color.White`），
  **禁止**使用 `MaterialTheme.colorScheme.onPrimary` 等会随主题色变化的跟随色；浅底（primary 加透明度）
  的次级样式才使用主题色文字。

- 新增任何颜色先检查 `Color.kt` 是否已有现成常量；中性色必须统一收口到 `Color.kt`，不在页面内散落硬编码。

### 3.5 组件容器/背景色的取色约定

组件（卡片、列表项、弹窗内容项、设置项容器等）的背景色**不得依赖**
`colorScheme.surface*` / `surfaceContainer` 等 scheme 颜色来自动跟随外观模式——这些 scheme
颜色可能被主题函数固定写死（如 `MiuixSettingsTheme` 深浅色分支都设为 `NavWhite`），且弹窗等
`Dialog` 组合可能不在目标 Miuix/Material 主题作用域内，导致背景色不随「外观模式」变化。

统一做法：需要随「外观模式」变色的组件容器，**直接引用** **`theme/Color.kt`** **的计算常量**（`get()`
读取 `AppThemeColors.isDark` 的 snapshot state，变化即触发重组）：

- 卡片/导航栏/组件容器（含弹窗背景） → `NavWhite`

- 弹窗内的条目容器（如 `AppNavItem` 传 `containerColor = DialogItemSurface`、手写条目行用
  `DialogItemSurface`） → 与弹窗背景形成对偶反差

- **复古玻璃外观风格（应用设置 → 外观主题 = 复古玻璃）**：页面背景固定深色画面
  （黑灰渐变 + 主题色对角环境光，见 `theme/GlassStyle.kt`），
  `PageGrey` 透明、`TextColor` 恒浅色；卡片/条目/弹窗/输入框统一 0.5dp 发丝描边（`Modifier.glassBorder()`）。
  此模式下「外观模式」不可用（置灰），色调轮盘保持可用（环境光随主题色变化）。
  新增组件必须走动态常量与 `glassBorder`，不得硬编码玻璃色值。真 backdrop 采样仅限白名单
  （液态玻璃底栏、透镜底栏、悬浮按钮导航的主子按钮）；弹窗/抽屉是独立窗口，**无法**采样主窗口内容，
  只能用半透明面板 + 遮罩 + 光学描边，不要为它们接 backdrop。

- **白雾玻璃外观风格（应用设置 → 外观主题 = 白雾玻璃）**：页面背景为模糊图片底图
  （`drawable-nodpi/bg_milk_glass.webp`，运行期 64dp 高斯模糊）+ 16% 白纱 + 38% 压暗 + 主题色低透明度环境光；
  `PageGrey` 透明，页面级文字用 `PageTextColor`（近白），条目正文用 `TextColor`（深灰黑）。
  `NavWhite` 为 70% 白纱（`MilkSurface`，目标 L≈211），条目直接叠在模糊底图上；弹窗/抽屉面板用
  `MilkGlassPanelBackground`（同一张页面底图 + 50% 白纱），弹窗内条目用 `DialogItemSurface`
  （`MilkItemOnPanel` = 45% 白纱；面板已有 50% 白纱，叠加后最终 L≈215，与页面条目视觉一致）。
  白雾条目**不加**
  `glassBorder`、不加投影；圆角仍只取「应用圆角调整」统一值（默认 15dp）。
  Miuix 的 `onBackground` 在该风格下取深色（库内 preference 标题与卡片共用该色槽），
  因此 Miuix 页顶栏必须使用 `PageTextColor`（`AppTopBar` 默认值已处理；不得再传
  `MiuixTheme.colorScheme.onBackground`）。
  此模式下「外观模式」不可用（置灰），色调轮盘保持可用。
  底部导航保持默认「液态玻璃 · 经典」，不新增导航栏形态；仅做亮玻璃 + 主题色轻染的配色适配
  （表面亮度高于页面 item，阴影同样减弱），主题色变化时导航栏同步换色。

- **底部抽屉/面板（`ModalBottomSheet`）→ 按「页面灰底」处理**：`ModalBottomSheet` 的 `containerColor` 通常取 `colorScheme.background`（浅/深随外观模式，等同页面背景），因此抽屉内条目（`AppNavItem` 等）必须传 `NavWhite`（灰底白卡），**不要**套用「弹窗白底灰卡」用 `PageGrey`——否则 item 与抽屉背景同色融为一体（如游戏操作抽屉 GameActionsSheet）。
  白雾玻璃是例外：抽屉面板用 `MilkGlassPanelBackground`（页面底图 + 白纱），条目用
  `DialogItemSurface`（`MilkItemOnPanel` 45% 白纱；叠加在 50% 面板后与页面条目同亮度），
  仍保持亮面板/亮条目的层级。

- **游戏操作抽屉（`GameActionsSheet`）关闭 Material 拖拽手势**：统一传 `sheetGesturesEnabled = false`。
  「增强」动效档由 `ui/common/motion/SheetMotion.kt` 的 `MotionSheetController` 接管抽屉运动，另加跟手下拉关闭：
  松手时以「位置 + 约 0.12s 惯性投影」判定，超过抽屉高度约 1/3 收起，否则回弹——慢拉按位置，明显的快速下甩也能收起；
  只有直接拉抽屉（内容 / 把手）才计速度，列表滚到顶后的剩余惯性不会转交抽屉，因此不会重现下述快速滑动误关的问题。
  「普通」档只允许遮罩/返回键关闭。 增强档的遮罩不用 Material3 自带的（`scrimColor` 经 `MotionSheetController.nativeScrim` 给透明，仍负责点遮罩关闭），由 `motionSheetSurface(controller, scrim)` 自绘：透明度 = 遮罩色 alpha × 抽屉露出比例（`controller.cover`），升起时渐显、下沉 / 下拉时同步渐隐，停稳时与原遮罩逐像素一致（原来是抽屉一出现整片「啪」地变暗、抽屉走完了灰色再慢慢褪，两件事各演各的）；收起 = 位移独自下沉，抽屉底边越过屏幕底边（位移 ≥ 抽屉高度）的那一帧直接移除，不再调 Material3 的 `hide()`（它会在抽屉已离屏后再空等一段动画、窗口还拦着点击）。展开弹簧要克制（阻尼 0.82，过冲约 1%），像抽屉被推上来再轻轻落稳，不要做成果冻式来回弹。抽屉背后的页面随露出比例褪色、妙妙工具侧栏背后的主界面页面区随遮罩进度整体轻轻后退（翻折景深：被盖住的页面随进度绕远离浮层的那条边向后翻折最多 12° 并褪色（饱和度最多降 50%，Android 12+），**只有左右方向**；**只保留左右方向的翻折**：侧栏绕右缘、页面推入绕左缘（参数共用 `depthFold`）；抽屉与弹窗（`AppAlertDialog`）从下方升起时**不再上下翻折页面**（`FoldAxis` 里没有 `Top`，不要加回来），改成页面「下沉」：整页以**正中**为枢轴缩小到约 0.94、正着往里沉（页面不裁圆角），浮层收起 / 被下拉时同步升回，再加褪色；缩小量取自 mochi 底部面板录屏逐帧量出的值（宽 1149 → 1082px；页面压暗约四成由浮层自己的遮罩负责，下沉不再叠压暗）。**枢轴不要放偏下**：录屏里量出的不动点在高度 71% 处（顶部下沉、底部上收），但那是整屏页面、底部被面板盖住；放进底栏固定的主界面，偏下的枢轴看上去就是整个界面往下缩。**下沉幅度是设计过的**，不是处处全幅：强度随浮层盖住屏幕的比例走（`sinkStrengthFor`：满屏面板 1、半屏抽屉约 0.75、下限 0.5；弹窗固定 `DialogSinkStrength` 0.7），`SheetBackdrop.sink` = 各浮层「展开进度 × 强度」取最大（与翻折 / 褪色一样不叠加）；宽屏（平板 / 横屏）两侧最多收进去 20dp（`SinkMaxInsetDp`），否则按比例会缩进几十 dp；进度按 1/64 一档量化。下沉是 `depthFoldStack` 的**最内层**（`drawPhaseLayers` 三层：下沉 / 侧栏翻折 / 推入翻折 + 褪色，仍是绘制阶段图层，不要改成整页容器的 `graphicsLayer`），命中测试不跟随，只在浮层遮住页面（不接收触摸）时有意义；同一页面区上多个来源只挂一层（`depthFoldStack`），按进度最大的那个走，不叠加、不互相抵消；Web 首页（WebView）不褪色，免得与 Chromium 的离屏绘制打架；曾试过「缩小 + 圆角卡片」后退（页面整体漂移、显得怪，已撤；现在的下沉不裁圆角、只缩放位移）与磨砂模糊（全屏模糊让抽屉打开那几帧 GPU 耗时涨到约 20ms，掉帧耗电），磨砂模糊不要再加；与遮罩同步，只改绘制）：页面根（主界面页面区经 `ProvideEntranceAndBackdrop`、二级页面壳 `AppScreenScaffold`）`rememberSheetBackdrop()` 并经 `LocalSheetBackdrop` 提供给子树（没提供的地方抽屉 / 弹窗不会翻折页面，这个必须检查），再把句柄交给 `depthFoldStack`；抽屉里 `BindBackdrop`、`AppAlertDialog` 里 `Bind { 遮罩进度 }` 登记，侧栏经 `AppSidePanel(backdrop = …)` 登记（用遮罩进度而不是面板本体的回弹驱动），新增浮层照此对齐；底部导航不在页面区里，不受影响。侧栏面板自己的进场用克制的弹簧（`SidePanelEnterSpec`，响应 0.44s、阻尼 0.78），不要用通用大面板进场（`surfaceEnter` 阻尼 0.2，弹得太狠、来回晃好几下显得轻浮）。浮层打开期间页面保持翻折（属于浮层的打开态，关闭后精确回到原样，不改变页面自身的静止外观）；浮层不经收起动画就离开组合时（外部移除、跳转交接结束），后退进度由 `SheetBackdrop` 弹簧退回 0，页面不会瞬间弹回。 设置页「游戏目录」抽屉（`SettingsScreen.kt`）同样接入该控制器与入场节拍，目录增删用 `listItemMotion(ListItemPlacement.Reflow)` 过渡。
  原因：M3 `ModalBottomSheet` 的关闭判定是「位移 > 56dp 或速度 > 125dp/s」任一命中即关闭（`BottomSheetDefaults`
  的 `PositionalThreshold` / `VelocityThreshold`，1.4.0 起 `rememberModalBottomSheetState` 不暴露这两个阈值），
  且列表到顶后的剩余手势/惯性会转交抽屉，导致用户"滑动稍微快一点"就误关抽屉。新增抽屉照此对齐；
  内容滚动与条目点击不受影响。

- 页面背景 → `PageGrey`

- 文字 → `TextColor`

通过 `MiuixTheme`/`MaterialTheme` 的 `colorScheme` 取容器背景属于**反例**（例：弹窗内项目用
`MiuixTheme.colorScheme.surfaceContainer` 不会随外观模式变色，应改用 `theme/Color.kt` 对应容器常量——
页面条目 `NavWhite`、弹窗内条目 `DialogItemSurface`）。
仅当某 scheme 颜色确为实时计算且随外观模式变化时才允许引用。

### 4. 层级要求

- 任何页面根组件必须包在 `TyranorNextTheme {}` 或 `MiuixSettingsTheme {}` 内，且主题必须最外层（Activity `setContent` 中包裹）。

- `MiuixSettingsTheme` 只提供 Miuix 主题，页面内部若用到 `MaterialTheme.*`（如弹窗、typography），外层仍须有 `TyranorNextTheme`（所有现有 Activity 均已满足，新增页面须遵循）。

- XML / drawable 资源中**禁止**出现主题色（启动图标等除外）。

***

## 测试与审核

使用 Android CLI 做设备审查前，先发现并阅读其中适用的审查技能；工具或技能不可用时记录缺失条件，继续可执行的本地审查，不编造已安装或已通过的结果。需要指定 SDK 时，将实际路径传给 `--sdk`。

必须强制性多个相互独立的 Agent 或 子Agent 从不同角度重新检查代码，例如：
代码逻辑审核：检查实现逻辑、状态流转、边界条件以及潜在逻辑错误。
架构审核：检查模块职责、依赖关系、代码耦合以及是否破坏现有架构。
代码健壮性审核：检查异常处理、空值、资源释放、线程安全、生命周期以及极端输入。
回归风险审核：分析修改是否可能影响现有功能、其他引擎或既有调用链。
代码规范审核：检查代码风格、命名、重复代码、无效代码以及是否符合 AGENTS.md 和项目现有规范。

JVM 回归使用 JUnit 4，位于 app/engine 各自的 `src/test/`；覆盖扫描、设置解析、存档、输入、宿主生命周期与图形策略。测试聚焦行为和边界，不镜像实现，也不为纯文档修改新增业务测试。真机用例与 release R8 probe 的构建、安装和能力边界分别见 [androidTest](app/src/androidTest/README.md) 与 [releaseProbe](app/src/releaseProbe/README.md)；普通 Android CI 未自动运行设备验收。

设备测试 JNI probe 的构建脚本按 Windows/Linux/macOS 选择 NDK host 与编译器文件名；本机须安装上述 NDK。WebDAV 专用设备验收与不加载原生库的旧版系统验证边界见 [WebDAV 设备记录](docs/WebDAV-device-acceptance.md)，不得把框架测试当作游戏运行库验收。

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
| `python tools/check-motion-boundaries.py` | 检查业务页面是否绕开具名动效入口 |
| `./gradlew -p buildSrc graphicsResourcesRegression --no-daemon` | 验证图形资源归属与校验逻辑 |
| `./gradlew :engine:jsHarness --no-daemon` | 用本地 Node.js 验证 Web v2 桥；也接入 `:engine:check` |
| `cargo test --manifest-path engine/rust/Cargo.toml --locked` | 修改 XP3 Rust 时运行宿主测试 |
| `./gradlew :app:printAppVersion --no-daemon` | 输出版本名与自动推导的 versionCode |

Windows PowerShell 使用 `./gradlew.bat`；Linux/macOS 使用 `./gradlew`。Python、Node、Rust 按相关任务安装；普通 Gradle 构建不运行 Rust 重编。执行 `check` 时仅在明确缺 Node 的场景可用 `-PskipJsHarness` 跳过，并在验证记录中说明。

`HIKARINAGI_CLIENT_ID` 可由环境、`local.properties` 或 Gradle 属性提供（按此顺序取值）；它配置 OAuth 客户端，不应填写用户 token。不要提交签名密钥、认证数据或商业游戏资产。测试安装采用隔离包名 `TYRANOR_APPLICATION_ID=com.tyranor.next.ethornelltest`，避免覆盖正式安装；PowerShell 用 `$env:TYRANOR_APPLICATION_ID = 'com.tyranor.next.ethornelltest'`，操作后恢复。

- 发版规则（issue #79）：只修改 `app/build.gradle.kts` 的 `appVersionName`；`versionCode` 由 `versionCodeOf()` 自动推导
  （`major*1_000_000 + minor*1_000 + patch`，minor/patch 必须 < 1000），禁止手写或回退 `versionCode`。
  beta-release workflow 会前置校验 versionCode 严格递增，并在构建后核对 APK 实际 versionCode（不一致即失败）。

