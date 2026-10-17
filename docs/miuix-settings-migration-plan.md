# 设置页 Miuix 化改造实施方案

> 目标：引入 `miuix-preference` 依赖，用 Miuix 的 `Card + BasicComponent（Preference 系列）` 体系
> 替换 `SettingsScreen.kt` 中的现有设置列表项，使设置页获得 HyperOS（MIUI）风格视觉与交互。

---

## 1. 背景与现状

### 1.1 现有实现（改造前）

文件：`app/src/main/java/com/tyranor/next/ui/settings/SettingsScreen.kt`

| 现有组件 | 作用 | 视觉 |
|---|---|---|
| `SettingsEntryCard` | 设置入口卡片（4 个引擎入口） | M3 `Card` + `RoundedCornerShape(8dp)` + `NavWhite`，手写 chevron |
| `ListSwitch` | 开关行 | M3 `Row` + `Switch`，手动 clickable |
| `SingleChoiceRow` | 单选行 | M3 `Row` + `AppAlertDialog` + `RadioButton` 弹窗选择 |
| `FontRow` | 字体选择行 | M3 `Row` + `AppAlertDialog` 二选一弹窗 |
| `EngineCard` | 引擎详情分组容器 | M3 `Card` 8dp 圆角 |

痛点：组件手写、样式与 HyperOS 风格差距大；单选交互需弹窗 + 单选钮，层级深。

### 1.2 Miuix 体系（参考 KernelSU-Style-UI-Kit 与 miuix 0.9.2 源码）

- `BasicComponent`：所有设置项的地基 —— 最小高 56dp、三栏布局（startAction / 标题+summary / endActions）、
  整行可点、按压态反馈、endActions 最宽占 60%。
- `SwitchPreference` / `ArrowPreference` / `OverlayDropdownPreference` 等由 `BasicComponent` 派生。
- `Card`：超椭圆（squircle）圆角默认 16dp，`surfaceContainer` 底色，一组相关设置共用一张卡。
- `OverlayDropdownPreference` 依赖 miuix `Scaffold` 提供的 `MiuixPopupHost`（`renderInRootScaffold`）。

## 2. 依赖引入

`gradle/libs.versions.toml`：

```toml
[versions]
androidxComposeBom = "2026.05.01"   # 由 2026.03.01 升级，与 miuix 0.9.2 传递依赖对齐
miuix = "0.9.2"

[libraries]
miuix-ui = { module = "top.yukonga.miuix.kmp:miuix-ui-android", version.ref = "miuix" }
miuix-preference = { module = "top.yukonga.miuix.kmp:miuix-preference-android", version.ref = "miuix" }
```

`app/build.gradle.kts`：

```kotlin
implementation(libs.miuix.ui)
implementation(libs.miuix.preference)
```

**版本说明（已验证）**：
- miuix 0.9.2 传递依赖 `kotlin-stdlib 2.4.0`、`org.jetbrains.compose.foundation:foundation:1.11.1`、
  `miuix-core-android`、`miuix-squircle-android`、`miuix-shader-android`（均来自 Maven Central，当前仓库配置已含 mavenCentral）。
- Compose BOM 升至 2026.05.01，避免 BOM 旧版本与 foundation 1.11.1 混用导致 ABI 不一致
  （该组合与 KernelSU-Style-UI-Kit 生产验证组合一致）。
- Kotlin 保持 2.3.20：Kotlin 元数据支持向前一个次版本读取（2.3.20 可读 2.4.0 元数据），构建已验证通过。

**构建链升级（实施中实际落地）**：

miuix 0.9.2 的 `miuix-squircle-android` / `miuix-shader-android` 等模块硬性要求 **compileSdk 37**，
因此同步升级（与参考项目完全一致的组合）：

| 项 | 原值 | 新值 |
|---|---|---|
| compileSdk (app) | `compileSdk = 36` | `compileSdk { version = release(37) { minorApiLevel = 0 } }` |
| AGP | 9.0.1 | 9.2.1 |
| Gradle Wrapper | 9.1.0 | 9.5.1 |
| targetSdk / minSdk | 36 / 26 | 不变 |
| engine 模块 compileSdk | 36 | 不变（无 miuix 依赖） |

## 3. 组件映射（核心设计）

| 原组件 | 新组件（miuix） | 说明 |
|---|---|---|
| `SettingsEntryCard`（M3 Card） | `Card` + `ArrowPreference` | 4 个引擎入口合并进 1 张卡，带 `startAction` 图标 |
| `ListSwitch` | `SwitchPreference` | 整行点击切换，HyperOS 胶囊开关 |
| `SingleChoiceRow`（弹窗+RadioButton） | `OverlayDropdownPreference` | 点击展开覆盖式下拉浮层，选中即回填；替代深弹窗 |
| `FontRow` | `ArrowPreference` + `endActions` 显示当前字体 | 二选一弹窗保留原 `AppAlertDialog`（符合 8dp 白色弹窗规范） |
| `EngineCard` | miuix `Card` | 白底 squircle 卡片，header 保留 |

### 3.1 选项数据结构调整

原 `Map<String, String>` 改为 `List<Pair<String, String>>`（保序）：

- 为「值可能为空（=引擎默认）」的选项显式补充 `"" to "引擎默认"` 条目
  （软件/OpenGL 纹理压缩、内存用量、FPS 限制），选择该项写回空串，语义与原“未设置”一致；
- 线程数、纹理尺寸补充 `"" to "自动"` 作为首项兜底。

### 3.2 主题适配（保持 App 固定浅色风格）

新建 `theme/MiuixSettingsTheme.kt`：

```kotlin
private val TyranorMiuixLightColors = lightColorScheme(
    primary = Blue40,          // #307DEF，与应用主色一致
    background = PageGrey,     // 页面背景与其他 Tab 一致
    surface = PageGrey,
    surfaceContainer = NavWhite, // 组件/卡片色，由「色调切换」控制
    onBackground = TextColor,
    onSurface = TextColor,
    onSurfaceContainer = TextColor,
)
```

仅在设置相关界面局部包裹 `MiuixSettingsTheme { ... }`，不影响其他页面。

### 3.3 页面结构（遵守 AGENTS.md 顶部栏规范）

```
MiuixSettingsTheme
└── miuix Scaffold（containerColor = PageGrey；popupHost 默认提供 MiuixPopupHost）
    ├── topBar = Column(statusBarsPadding) + Row(64dp, titleLarge)   ← 结构与现有完全一致
    └── LazyColumn(horizontal = 12.dp, spacedBy(12.dp))
        ├── Card { ArrowPreference × 4（入口）/ SwitchPreference · DropdownPreference（详情） }
        └── 底部导航栏高度占位
```

- `contentWindowInsets = WindowInsets(0.dp)`，内边距全部手动控制（沿用现有行为，避免重复 inset）。

## 4. 与 AGENTS.md 规范的差异说明

| 规范条目 | 本次处理 |
|---|---|
| 顶部栏 Column + 64dp、透明、statusBarsPadding | ✅ 保持不变（miuix Scaffold 仅作容器） |
| 禁用 Material3 TopAppBar/Scaffold | ✅ 未使用 M3 Scaffold；miuix Scaffold 为下拉浮层宿主所需 |
| 卡片圆角统一 8dp | ⚠️ 设置页改用 Miuix 默认 16dp squircle（Miuix 风格核心特征），其他页面维持 8dp |
| 弹窗白底 8dp | ✅ 字体选择弹窗保留 `AppAlertDialog`；新增的下拉浮层为 Miuix 原生样式 |
| 页面切换：主 Tab 水平移动 / Activity 上进下出 | ✅ 不变 |

## 5. 实施步骤

1. [x] 调研 miuix 0.9.2 API（已完成：AAR 反编译验证 `lightColorScheme`/`MiuixTheme(colors)`/`Card`/`Scaffold`/Preference 签名）
2. [x] `libs.versions.toml`：BOM 升级 + miuix 版本与库声明 + AGP 9.2.1
3. [x] `app/build.gradle.kts`：添加 miuix 依赖 + compileSdk 37 DSL
4. [x] 新建 `theme/MiuixSettingsTheme.kt`
5. [x] 重写 `SettingsScreen.kt`（`SettingsScreen` 入口 + `EngineSettingsDetailScreen` 详情；删除 `ListSwitch`/`SingleChoiceRow` 手写组件）
6. [x] `./gradlew assembleDebug --no-daemon` 构建验证（BUILD SUCCESSFUL，产出 app-debug.apk）
7. [x] 修复记录：① compileSdk 36→37 + AGP/Gradle 升级（miuix 硬性要求）；② `SwitchPreference` 尾随 lambda 改为显式 `onCheckedChange = { }`（其末参数非函数类型）

## 6. 验收标准

- 构建通过，APK 可安装；
- 设置页：单卡 4 个引擎入口（带图标 + 右箭头），Miuix 视觉（白卡、16dp squircle、56dp 行高、胶囊开关）；
- 引擎详情：开关行为、下拉选择、字体选择、保存逻辑与改造前完全等价（写盘字段不变）；
- 顶部栏结构、页面切换动画与其他 Tab 视觉一致性不受影响。

## 7. 后续可选扩展（不在本次范围）

- `PerGameSettingsScreen` / `SaveManagementActivity` 同体系改造；
- 引入 `overScrollVertical` / `scrollEndHaptic` / 顶部栏滚动渐变等 Miuix 滚动体验。
