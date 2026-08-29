# 外置 APK 引擎模块接入方案

> 实施状态：Ren'Py 与 RPG Maker XP/VX/VX Ace/mkxp-z 外置 APK 模块支持已按本文方案落地。后续 Godot 等外置引擎继续复用 `core/engine/external` 注册、安装状态检查和启动协议抽象。

## 1. 背景与目标

TyranorNext 当前已经内置 Kirikiri / ONScripter / Tyrano / Artemis / RPG Maker MV/MZ / VN / WebOther 等运行路径，其中原生引擎与 Web 引擎都由当前 APK 自身承担启动与宿主逻辑。

后续若继续把 RenPy、RPG Maker XP/VX/VX Ace、Godot 等完整运行时直接集成进主 APK，会带来几个问题：

- APK 体积膨胀明显。
- 各引擎运行时依赖、ABI、权限与生命周期差异大。
- 外部引擎更新频率和主 App 不一致，耦合后维护成本高。
- 部分引擎已经有现成外部运行时 APK，可复用其 intent 协议启动。

因此计划引入“外置 APK 引擎模块”体系：主 App 负责扫描、识别、管理、校验模块状态和发起启动；具体游戏运行交给已安装的外置引擎 APK。

首个落地目标：

- 已接入 RenPy 外置引擎 APK。
- 同时抽象出通用外置 APK 模块能力，为后续 RPG Maker XP/VX/VX Ace、Godot 等引擎接入复用。

RPGM 落地目标：

- 接入 RPG Maker RGSS 系外置引擎 APK。
- 支持 RPG Maker XP / VX / VX Ace / mkxp-z 四个子运行时。
- RPG Maker MV / MZ 继续走当前内置 Web/Tyrano 路线，不归入 RPGM 外置 APK 模块。

## 2. 参考实现结论

参考 RinneMobile 的外置模块链路：

- RenPy / RPGM / Godot 属于“外部独立 APK 模块”。
- 主 App 不内置这些运行时，只检查外部 APK 是否安装。
- 游戏库中保存一个内部别名，例如 `external.renpy` 或 `internal.renpy`。
- 启动时由策略层把内部别名翻译为真实外部 APK 包名、intent action 和 extras。
- 外置 APK 模块默认可用，不提供手动开关；主 App 只检查目标 APK 是否已安装。
- RenPy 启动协议核心为：
  - package：`cyou.joiplay.runtime.renpy.v8d4d1`
  - action：`cyou.joiplay.runtime.renpy.run`
  - extra `game`：JSON 字符串，含 `title / id / folder / execFile / type`
  - extra `settings`：JSON 字符串，RenPy 可先传 `{}`
  - extra `orientation`：横屏可传 `6`
- RPG Maker RGSS 外置模块与 RenPy 不同：一个真实 APK 包承载四个子运行时，主 App 必须保存或推断子类型，再映射到不同 action。
  - package：`cyou.joiplay.runtime.rpgmaker`
  - XP action：`cyou.joiplay.runtime.rpgmxp.run`
  - VX action：`cyou.joiplay.runtime.rpgmvx.run`
  - VX Ace action：`cyou.joiplay.runtime.rpgmvxace.run`
  - mkxp-z action：`cyou.joiplay.runtime.mkxp-z.run`
  - extra `game`：JSON 字符串，含 `title / id / folder / execFile / type`
  - extra `settings`：JSON 字符串；RPGXP 必须传 `{"rpg":{"useRuby18":{"boolean":true}}}`，否则容易把 RGSS1 脚本交给 Ruby 1.9/3.x 解析导致语法错误。
  - extra `orientation`：横屏可传 `6`
  - RPGM 模块还需要准备 JoiPlay 风格 RTP 目录与 `configuration.json`，这是它比 RenPy 多出来的关键适配点。

## 3. 架构定位

项目现有三层架构保持不变：

```text
界面 UI 交互层 -> 功能抽象层 -> 底层引擎层
```

外置 APK 模块属于功能抽象层，不属于底层引擎层。

原因：

- 外置 APK 并不由 `engine/` 模块加载。
- 主 App 与外置 APK 之间是 Android intent 协议，不是 JNI/native 内部调用。
- UI 只应展示安装状态与下载引导，不应拼装外置引擎 intent。

推荐目录：

```text
app/src/main/java/com/tyranor/next/core/engine/external/
├── ExternalEngineModule.kt
├── ExternalEngineModuleRegistry.kt
├── ExternalEngineLauncher.kt
├── ExternalEngineLaunchRequest.kt
├── ExternalEngineLaunchResult.kt
├── RenPyExternalEngineModule.kt
└── RpgMakerExternalEngineModule.kt
```

后续可继续加入：

```text
GodotExternalEngineModule.kt
```

## 4. 通用抽象设计

### 4.1 ExternalEngineModule

描述一个外置 APK 引擎模块的静态协议。

建议字段：

```kotlin
data class ExternalEngineModule(
    val id: String,
    val engine: EngineType,
    val displayName: String,
    val packageName: String,
    val action: String,
    val defaultAlias: String,
    val supportedAliases: Set<String>,
    val installUrl: String?,
)
```

字段说明：

| 字段 | 用途 |
| --- | --- |
| `id` | 主 App 内部模块 ID，例如 `renpy` |
| `engine` | 对应 `EngineType.RENPY` |
| `displayName` | UI 展示名称 |
| `packageName` | 外置 APK 真实包名 |
| `action` | 外置 APK 接收的默认启动 action；RPGM 这类多 action 模块需在 `buildLaunchIntent` 内按子类型动态覆盖 |
| `defaultAlias` | 游戏库保存的默认别名，例如 `external.renpy` |
| `supportedAliases` | 兼容历史别名或后续变体 |
| `installUrl` | 未安装时用于跳转下载页，首期可为空或写死 GitHub release 地址 |

### 4.2 ExternalEngineLaunchRequest

统一描述一次外置引擎启动请求。

建议字段：

```kotlin
data class ExternalEngineLaunchRequest(
    val game: ScanGame,
    val gameDirectoryPath: String,
    val launchTarget: String,
)
```

说明：

- `game` 保留游戏标题、URI、引擎、封面等上下文。
- `gameDirectoryPath` 是已经解析出的真实文件路径。
- `launchTarget` 是 `[游戏目录]` 或具体入口文件。

### 4.3 ExternalEngineLauncher

负责通用检查与启动。

建议职责：

- 根据 `EngineType` 或 alias 查找模块。
- 检查外置 APK 是否安装。
- 构建通用 intent。
- 注入 `FLAG_ACTIVITY_NEW_TASK`。
- 捕获 `ActivityNotFoundException` / `SecurityException` / 其他异常。
- 返回结构化启动结果，不直接弹 Toast。

建议结果：

```kotlin
data class ExternalEngineLaunchResult(
    val success: Boolean,
    val message: String? = null,
    val reason: String? = null,
)
```

常见 `reason`：

| reason | 含义 |
| --- | --- |
| `module_not_found` | 未配置对应外置模块 |
| `package_not_installed` | 外置 APK 未安装 |
| `invalid_game_path` | 游戏目录真实路径不可用 |
| `activity_not_found` | 外置 APK 没有匹配 action 的 Activity |
| `launch_exception` | 其他启动异常 |

### 4.4 模块默认可用策略

外置 APK 模块默认可用，不新增手动开关状态。

也就是说：

- APK 存在：认为模块可用，引擎页右侧显示打勾状态图标，启动时直接按协议拉起。
- APK 不存在：认为模块不可用，引擎页右侧显示打叉状态图标；点击该引擎 item 弹窗提示下载模块。
- 不提供“已安装但未启用”的中间状态，也不做用户开关拦截。
- 后续若需要临时停用某个外置模块，应作为独立需求再引入开关，不作为首期 RenPy 支持的一部分。

### 4.5 ExternalEngineModuleRegistry

模块注册表，作为单一来源。

当前已注册：

```kotlin
RenPyExternalEngineModule
RpgMakerExternalEngineModule
```

后续接 Godot 时只新增模块定义和策略，不修改 UI 多处硬编码。

## 5. RenPy 外置引擎模块设计

### 5.1 EngineType

新增：

```kotlin
RENPY("Ren'Py")
```

注意：

- 需要更新所有 `when (EngineType)`，避免 Kotlin exhaustive when 编译失败。
- 游戏页封面色、引擎页展示文案、存档管理不支持说明都要补齐。

### 5.2 扫描识别

在 `EngineScanner.detectEngine` 中加入 RenPy 特征。

识别优先级建议放在 WebOther / Kirikiri / ONS 之前，但不高于 Artemis / Tyrano / RPG Maker MV/MZ。

特征：

| 特征 | 置信度 | launchTarget |
| --- | --- | --- |
| 任意 `.rpa` | 96 | `.rpa` 文件相对路径，或 `[游戏目录]` |
| `game/script.rpy` | 94 | `[游戏目录]` |
| `game/options.rpy` | 94 | `[游戏目录]` |
| `renpy/` 目录 + `.rpy` / `.rpyc` | 90 | `[游戏目录]` |
| `game/` 目录 + `.rpy` | 85 | `[游戏目录]` |

首期建议：

- 如果识别到 `.rpa`，仍优先用 `[游戏目录]` 作为启动目标，避免外置 RenPy 误把 `.rpa` 当执行文件。
- `launchTarget` 保持 `[游戏目录]`。

### 5.3 启动协议

RenPy 外置 APK：

```text
package = cyou.joiplay.runtime.renpy.v8d4d1
action  = cyou.joiplay.runtime.renpy.run
```

Intent extras：

```json
game = {
  "title": "游戏标题",
  "id": "稳定 ID",
  "folder": "/storage/emulated/0/Games/RenPyGame",
  "execFile": "",
  "type": "renpy"
}
settings = "{}"
orientation = 6
rootUri = "content://..."
launchTarget = "[游戏目录]"
```

`game.id` 推荐：

- 首期：`game.uri.hashCode().toString(16)` 或真实路径 hash。
- 后续若引入数据库 ID，可改为稳定数据库 ID。

### 5.4 路径策略

RenPy 外置 APK 通常需要真实文件路径，不是 SAF URI。

复用当前项目已有：

```kotlin
EngineScanner.safUriToPath(uriText)
```

要求：

- 支持 `primary:path` → `/storage/emulated/0/path`
- 支持 SD 卡卷标 → `/storage/<volume>/path`
- 支持 `file://`
- 支持直接 `/storage/...` 路径

如果无法解析真实路径：

- 不复制整个 RenPy 游戏目录。
- 返回明确错误：`无法解析 RenPy 游戏目录真实路径，外置 RenPy 模块无法启动`

原因：

- RenPy 游戏目录可能很大，静默复制会造成耗时、空间占用和同步问题。
- 外置 APK 运行时也可能依赖原始目录结构和读写路径。

### 5.5 权限策略

主 App 当前已有 `MANAGE_EXTERNAL_STORAGE` 逻辑。

外置 APK 启动时需要注意：

- 主 App 对 SAF 的授权不能自动转移成外置 APK 对真实路径的访问能力。
- 传 `FLAG_GRANT_READ_URI_PERMISSION` 只能授权 URI，不能授权 `/storage/...` 字符串路径。
- 因此外置 RenPy APK 自身通常也需要存储权限或所有文件访问。

首期策略：

- 主 App 只检查路径能否解析，并发起启动。
- 如果外置 APK 内部要求权限，由外置 APK 自己处理。
- 如果启动失败，提示用户检查 RenPy 模块是否已安装并授予存储权限。

后续增强：

- 模块兼容页增加“权限说明”。
- 可检测目标 APK 是否存在，但不尝试替它申请私有权限。

## 6. UI 方案

外置 APK 模块状态直接整合进现有“引擎页”的引擎 item，不新增独立开关页。

### 6.1 引擎页状态展示

现有内置引擎 item 右侧为打勾状态图标，表示该引擎随 App 内置可用。

外置 APK 引擎 item 使用同一套状态位置，但含义改为“外部 APK 是否安装”：

| 引擎类型 | 检查方式 | 右侧状态图标 |
| --- | --- | --- |
| 内置引擎 | 随 App 内置 | 打勾 |
| 外置 APK 引擎已安装 | `PackageManager.getPackageInfo(packageName, ...)` 成功 | 打勾 |
| 外置 APK 引擎未安装 | `PackageManager` 查不到目标包 | 打叉 |

首期 RenPy item：

- 显示名称：`Ren'Py`
- 目标 APK：`cyou.joiplay.runtime.renpy.v8d4d1`
- 已安装：右侧打勾
- 未安装：右侧打叉

### 6.2 引擎页点击行为

内置引擎：

- 保持现有行为，仅展示引擎说明或保持静态展示。

外置 APK 引擎：

- 已安装：点击可弹窗提示“RenPy 模块已安装，可直接启动 RenPy 游戏”。
- 未安装：点击弹窗提示“未检测到 RenPy 外置引擎模块”，并提供“去下载”按钮。

下载按钮：

- 使用 `Intent.ACTION_VIEW` 打开模块下载 URL。
- 如果没有浏览器或打开失败，弹窗提示用户稍后重试。

### 6.3 UI 调用边界

UI 层可以调用：

```kotlin
ExternalEngineModuleRegistry.modules
ExternalEngineModuleRegistry.moduleForEngine(engine)
ExternalEngineLauncher.isPackageInstalled(context, module)
ExternalEngineLauncher.openInstallPage(context, module)
```

UI 层禁止：

- 拼接 RenPy intent action。
- 拼接 RenPy `game` JSON。
- 直接保存外置模块开关状态。
- 在页面里硬编码多个外置 APK 包名。

## 7. Manifest 方案

Android 11+ 包可见性需要声明外置 APK 包名。

在 `app/src/main/AndroidManifest.xml` 的 `<manifest>` 下加入：

```xml
<queries>
    <package android:name="cyou.joiplay.runtime.rpgmaker" />
    <package android:name="cyou.joiplay.runtime.renpy.v8d4d1" />
</queries>
```

后续 Godot 接入时继续追加：

```xml
<package android:name="cyou.joiplay.runtime.godot3" />
<package android:name="cyou.joiplay.runtime.godot4" />
```

## 8. 与现有启动链路的集成点

当前启动入口：

```text
EngineLauncher.launch(context, game)
  -> resolveGameDirectory(...)
  -> requestAllFilesAccessIfNeeded(...)
  -> EnginePluginBootstrap.ensureForLaunch(...)
  -> buildIntent(...)
  -> context.startActivity(...)
```

接入后建议：

```text
EngineLauncher.launch(context, game)
  -> resolveGameDirectory(...)
  -> if ExternalEngineModuleRegistry.supports(game.engine):
       ExternalEngineLauncher.launch(...)
       recordRecentGame on success
       return result.message
  -> 原有内置引擎逻辑
```

注意：

- 外置 APK 不走 `EnginePluginBootstrap.ensureForLaunch`。
- 外置 APK 不应进入 `buildIntent` 的内部 Activity 分支。
- 外置 APK 成功发起后同样记录最近游戏。
- 外置 APK 启动失败时不记录最近游戏。

## 9. 与现有扫描持久化的集成点

当前 `ScanGame` 没有 `emulatorPackage` 字段。

首期可不新增字段：

- `engine = EngineType.RENPY`
- `launchTarget = "[游戏目录]"`
- 启动时由 `engine` 直接找到默认 RenPy 模块。

后续若需要一个引擎对应多个外置 APK 或多个 runtime subtype，再扩展：

```kotlin
val externalModuleAlias: String? = null
```

不建议直接加入 `packageName`：

- 真实外部包名属于策略层细节。
- 数据库/SharedPreferences 里保存真实包名会增加未来迁移成本。

## 9.5 RPG Maker RGSS 外置模块接入补充

本节基于 `/Users/weiss/opencode/RinneMobile` 的 RPGM 外置模块链路整理，目标是在 TyranorNext 中复刻同等能力，并继续保持三层架构边界。

### 9.5.1 支持范围边界

RPGM 外置 APK 模块只负责 RGSS / mkxp 系游戏：

| 子类型 | 常见名称 | 脚本/运行时 | 外置模块 action |
| --- | --- | --- | --- |
| `rpgmxp` | RPG Maker XP | RGSS1 / Ruby 1.8 | `cyou.joiplay.runtime.rpgmxp.run` |
| `rpgmvx` | RPG Maker VX | RGSS2 / Ruby 1.9 | `cyou.joiplay.runtime.rpgmvx.run` |
| `rpgmvxace` | RPG Maker VX Ace | RGSS3 / Ruby 3.x | `cyou.joiplay.runtime.rpgmvxace.run` |
| `mkxp-z` | mkxp-z 自定义运行时 | mkxp-z / Ruby 3.x | `cyou.joiplay.runtime.mkxp-z.run` |

不纳入本模块：

- RPG Maker MV
- RPG Maker MZ

这两类是 HTML5/Web runtime，TyranorNext 当前已有 `EngineType.RPG_MV` / `EngineType.RPG_MZ`，应继续走内置 Web/Tyrano 宿主，不要混进 `RpgMakerExternalEngineModule`。

### 9.5.2 RinneMobile 接入方式结论

RinneMobile 的核心实现位于：

```text
/Users/weiss/opencode/RinneMobile/app/src/main/java/com/core/launcher/ExternalRpgMakerPluginStrategy.kt
/Users/weiss/opencode/RinneMobile/app/src/main/java/com/apps/game/EnginePackageResolver.kt
/Users/weiss/opencode/RinneMobile/app/src/main/java/com/apps/game/AddGameSavePipeline.kt
/Users/weiss/opencode/RinneMobile/app/src/main/java/com/core/scanner/EngineDetector.kt
/Users/weiss/opencode/RinneMobile/app/src/main/java/com/core/scanner/NodeEngineDetector.kt
/Users/weiss/opencode/RinneMobile/app/src/main/java/com/core/launcherbridge/LauncherModuleBridge.kt
```

它的设计不是给 RPGXP/VX/VXAce 各安装一个 APK，而是：

1. 统一检测真实包名 `cyou.joiplay.runtime.rpgmaker` 是否安装。
2. 游戏库里保存内部别名，表达具体 RPGM 子类型。
3. 启动时根据别名或扫描结果得到 `game.type`。
4. 再由 `game.type` 映射到真实 action。
5. 启动前尽力准备 JoiPlay 兼容目录和配置文件。

RinneMobile 使用的内部别名：

| 内部别名 | 子类型 |
| --- | --- |
| `internal.rpgmaker` | 自动识别 |
| `internal.rpgmxp` | `rpgmxp` |
| `internal.rpgmvx` | `rpgmvx` |
| `internal.rpgmvxace` | `rpgmvxace` |
| `internal.mkxp-z` | `mkxp-z` |
| `internal.mkxpz` | `mkxp-z` 兼容写法 |

TyranorNext 已在 `ScanGame` 增加 `externalModuleAlias` 通用字段，用于保存 RPGM/RenPy/Godot 等外置模块的内部别名。否则保存到游戏库后只剩 `EngineType` 和 `launchTarget`，再次启动时无法稳定区分 XP/VX/VX Ace/mkxp-z。

### 9.5.3 TyranorNext 数据模型建议

建议在 `ScanGame` 增加一个通用字段，而不是专门增加 `rpgMakerSubtype`：

```kotlin
val externalModuleAlias: String? = null
```

保存值建议：

| 游戏类型 | 保存值 |
| --- | --- |
| RenPy | `internal.renpy` |
| RPG Maker XP | `internal.rpgmxp` |
| RPG Maker VX | `internal.rpgmvx` |
| RPG Maker VX Ace | `internal.rpgmvxace` |
| mkxp-z | `internal.mkxp-z` |
| Godot 4 | `internal.godot4` |

原因：

- 一个字段可以复用给 RenPy / RPGM / Godot。
- UI 和持久层不保存真实 APK 包名。
- 外置模块协议仍集中在 `core/engine/external`。
- 未来模块包名或 action 改动时不需要迁移游戏库数据。

持久化同步修改：

- `EngineScanner.serializeGame`
- `EngineScanner.parseGame`
- `ScanGameIntents.putGame`
- `ScanGameIntents.getGame`
- 任何复制/更新 `ScanGame.copy(...)` 的地方，注意保留该字段。

### 9.5.4 扫描识别方案

在 `EngineScanner` 中加入 RPG Maker RGSS 特征字段。RinneMobile 的规则可以直接复刻：

| 特征 | 子类型 | 置信度 | launchTarget |
| --- | --- | --- | --- |
| 任意 `.rgss3a` | `rpgmvxace` | 96 | `.rgss3a` 相对路径 |
| 任意 `.rgss2a` | `rpgmvx` | 96 | `.rgss2a` 相对路径 |
| 任意 `.rgssad` | `rpgmxp` | 96 | `.rgssad` 相对路径 |
| `Game.ini` + `Data/*.rvdata2` | `rpgmvxace` | 92 | `[游戏目录]` |
| `Game.ini` + `Data/*.rvdata` | `rpgmvx` | 92 | `[游戏目录]` |
| `Game.ini` + `Data/*.rxdata` | `rpgmxp` | 92 | `[游戏目录]` |

落库结果：

```text
engine = EngineType.RPGMAKER
externalModuleAlias = internal.<subtype>
launchTarget = 检测到的归档相对路径，或 [游戏目录]
```

新增 `EngineType.RPGMAKER` 会触发多处 `when` 补齐，至少需要同步：

- `EngineLauncher.supportedEngines`
- `EngineLauncher.buildIntent`：外置 RPGM 不能进入内部 Activity 分支。
- `EngineScreen` 展示名称/描述/封面色。
- `GameScreen` 引擎筛选和封面占位色。
- `SaveManager` / 游戏抽屉：外置 APK 模块不应该出现存档管理 item。
- README 支持范围。

### 9.5.5 RpgMakerExternalEngineModule 协议

新增：

```text
app/src/main/java/com/tyranor/next/core/engine/external/RpgMakerExternalEngineModule.kt
```

模块静态信息：

```kotlin
object RpgMakerExternalEngineModule : ExternalEngineModule {
    override val id = "rpgmaker"
    override val engine = EngineType.RPGMAKER
    override val displayName = "RPGM 模块"
    override val packageName = "cyou.joiplay.runtime.rpgmaker"
    override val defaultAlias = "internal.rpgmaker"
    override val supportedAliases = setOf(
        "external.rpgmaker",
        "internal.rpgmxp",
        "internal.rpgmvx",
        "internal.rpgmvxace",
        "internal.mkxp-z",
        "internal.mkxpz",
    )
}
```

`ExternalEngineModule.action` 对 RenPy 够用，但 RPGM 是多 action 模块。实现时建议：

- 保留 `action` 作为默认 action，例如 VX Ace：`cyou.joiplay.runtime.rpgmvxace.run`。
- `RpgMakerExternalEngineModule.buildLaunchIntent(request)` 内根据 alias/subtype 重建真正 action。
- 或者把接口升级为 `fun actionFor(request: ExternalEngineLaunchRequest): String`，RenPy 返回固定 action，RPGM 返回子类型 action。

子类型推断优先级：

1. `request.game.externalModuleAlias`
2. `request.launchTarget` 后缀：
   - `.rgssad` → `rpgmxp`
   - `.rgss2a` → `rpgmvx`
   - `.rgss3a` → `rpgmvxace`
3. 默认 `rpgmxp`

默认走 `rpgmxp` 是 RinneMobile 的保守选择：未识别的老 RGSS 游戏更多是 XP，Ruby 1.8 兼容性也更适合 RGSS1。

### 9.5.6 Intent extras

RPGM 模块启动 intent：

```text
package = cyou.joiplay.runtime.rpgmaker
action  = 按子类型动态选择
```

`game` JSON：

```json
{
  "title": "游戏标题",
  "id": "稳定 ID",
  "folder": "/storage/emulated/0/Games/RPGXPGame",
  "execFile": "",
  "type": "rpgmxp"
}
```

字段说明：

| 字段 | 说明 |
| --- | --- |
| `title` | 游戏标题，优先用库内标题；为空时用目录名 |
| `id` | 稳定 ID，可用真实目录路径 hash |
| `folder` | 游戏目录真实 `/storage/...` 路径 |
| `execFile` | RinneMobile 传空字符串 |
| `type` | `rpgmxp` / `rpgmvx` / `rpgmvxace` / `mkxp-z` |

`settings` JSON：

```json
{}
```

RPGXP 必须特殊处理：

```json
{
  "rpg": {
    "useRuby18": {
      "boolean": true
    }
  }
}
```

原因：

- RPGM 插件的 `MKXPConfigurationParser.parse(String)` 读取的是嵌套格式。
- 如果传扁平 `{"useRuby18": true}`，解析不会按预期生效。
- `useRuby18=true` 会加载 `libmkxp18.so`，避免 RPGXP / RGSS1 脚本被 Ruby 1.9/3.x 解析。

其他 extras：

```text
orientation = 6
rootUri = 原始 SAF/file URI
launchTarget = [游戏目录] 或归档相对路径
```

### 9.5.7 路径与 launchTarget 处理

RPGM 插件需要真实路径。沿用 RenPy 的路径策略：

- `content://...primary:path` → `/storage/emulated/0/path`
- `content://...9C33-6BBD:path` → `/storage/9C33-6BBD/path`
- `file://...` → `uri.path`
- 已经是 `/storage/...` 则直接使用

如果 `launchTarget` 是 `.rgssad` / `.rgss2a` / `.rgss3a` 归档：

- `folder` 应落到归档父目录。
- `type` 根据归档后缀决定。
- `title` 可以继续用游戏库标题，不必改成归档名。

如果 `launchTarget` 是 `[游戏目录]` / `DIR` / 空：

- `folder` 使用游戏根目录。
- `type` 使用 `externalModuleAlias` 或扫描子类型。

不要静默复制整个 RPGM 游戏目录。RGSS 游戏可能体积大，并且插件期望的是原始目录结构与真实路径。

### 9.5.8 RTP 与 configuration.json 适配

这是 RPGM 模块接入的高风险点，也是它和 RenPy 最大的不同。

RinneMobile 发现 RPGM 插件会无条件尝试挂载：

```text
/sdcard/JoiPlay/RTP/<engineName>/app
```

RTP 目录名映射：

| 子类型 | RTP 目录名 |
| --- | --- |
| `rpgmxp` | `RPGXP` |
| `rpgmvx` | `RPGVX` |
| `rpgmvxace` | `RPGVXACE` |
| `mkxp-z` | `mkxp-z` |

TyranorNext 启动前应尽力创建：

```text
/sdcard/JoiPlay/RTP/<engineName>/app/
```

并尝试放入：

```text
sf.sf2
```

说明：

- 目录缺失时，插件底层 mkxp 可能因为 `PHYSFS_mount(..., fatalError=true)` 失败而退出。
- `sf.sf2` 缺失主要影响 MIDI 音色，通常不是致命启动错误，但有条件应一并准备。
- 如果当前 App 还没拿到所有文件权限，创建目录可能失败；该失败不应阻断启动，由 RPGM 插件自己的权限流程兜底。

RPGXP 还需要 `configuration.json` 双保险：

```json
{"useRuby18":true}
```

可创建位置：

```text
<game.folder>/configuration.json
/sdcard/JoiPlay/games/<gameId>/configuration.json
```

规则：

- 仅 `rpgmxp` 必须创建。
- 文件已存在时不覆盖，尊重用户已有 JoiPlay 配置。
- 创建失败只记录日志，不阻断启动。

注意 `configuration.json` 是插件 `loadFromFile()` 读取的扁平格式；intent extra `settings` 是 `parse(String)` 读取的嵌套格式，二者不能混用。

### 9.5.9 引擎页状态与点击行为

RPGM 作为外置 APK 模块，默认启用，不需要手动开关。

引擎页 item 规则：

| 状态 | 右侧图标 | 单击行为 |
| --- | --- | --- |
| `cyou.joiplay.runtime.rpgmaker` 已安装 | 打勾 | 弹窗提示 RPGM 模块已安装，可直接启动 RGSS 游戏 |
| 未安装 | 打叉 | 弹窗提示下载 RPGM 模块 |

RPGM 下载地址沿用 RinneMobile：

```text
https://github.com/Weiss-UltimateSavior/RinneMobile/releases/download/test/RPGM-Plugin.apk
```

### 9.5.10 游戏抽屉与存档管理

外置 APK 模块不应该在游戏抽屉中展示“存档管理”item。

原因：

- 存档目录、存档格式和写入位置由外置 APK runtime 决定。
- 主 App 当前的存档管理能力针对内置引擎/可控目录设计。
- 对 RPGM 外置模块强行展示存档管理容易造成误删、误导或空页面。

建议规则：

```text
if (ExternalEngineModuleRegistry.isExternalEngine(game.engine)) {
    hideSaveManagementItem()
}
```

如果未来某个外置模块公开稳定存档路径协议，再为该模块单独声明 `supportsSaveManagement = true`，不要默认开放。

### 9.5.11 Manifest 包可见性

Android 11+ 需要声明包可见性：

```xml
<queries>
    <package android:name="cyou.joiplay.runtime.rpgmaker" />
</queries>
```

当前 RenPy 已有：

```xml
<package android:name="cyou.joiplay.runtime.renpy.v8d4d1" />
```

RPGM 接入时确认不要遗漏，否则 `PackageManager` 可能查不到已安装模块，导致引擎页误显示打叉。

### 9.5.12 测试清单

单元测试：

```text
EngineScannerRpgMakerTest
RpgMakerExternalEngineModuleTest
ExternalEngineModuleRegistryTest
```

覆盖项：

- `.rgssad` → `EngineType.RPGMAKER` + `internal.rpgmxp`
- `.rgss2a` → `EngineType.RPGMAKER` + `internal.rpgmvx`
- `.rgss3a` → `EngineType.RPGMAKER` + `internal.rpgmvxace`
- `Game.ini + Data/*.rxdata` → `internal.rpgmxp`
- `Game.ini + Data/*.rvdata` → `internal.rpgmvx`
- `Game.ini + Data/*.rvdata2` → `internal.rpgmvxace`
- `internal.mkxp-z` / `internal.mkxpz` 都映射到 `mkxp-z`
- XP intent action 为 `cyou.joiplay.runtime.rpgmxp.run`
- VX intent action 为 `cyou.joiplay.runtime.rpgmvx.run`
- VX Ace intent action 为 `cyou.joiplay.runtime.rpgmvxace.run`
- mkxp-z intent action 为 `cyou.joiplay.runtime.mkxp-z.run`
- RPGXP intent `settings` 为嵌套 `useRuby18` 格式
- RPGXP `configuration.json` 为扁平 `{"useRuby18":true}`
- 未安装模块返回 `package_not_installed`
- 游戏抽屉外置模块不显示存档管理 item

实机验证：

1. 安装 TyranorNext debug 包。
2. 未安装 RPGM 插件时进入引擎页，确认 RPGM item 为打叉。
3. 单击 RPGM item，确认弹窗提示去下载。
4. 安装 RPGM 插件后返回引擎页，确认 item 变为打勾。
5. 扫描 RPGXP / VX / VX Ace 三类样本，确认子类型正确保存。
6. 分别启动三类样本，确认拉起同一个外置 APK 但 action 不同。
7. 用 SD 卡路径 `/storage/<volume>/...` 验证路径映射。
8. 用 RPGXP 样本验证 `useRuby18=true` 后不再出现 Ruby 版本语法错误。
9. 验证 RPGM 游戏抽屉没有存档管理 item。

### 9.5.13 分阶段实施建议

建议拆成三个小提交，降低回归面：

1. 模型与扫描：
   - 新增 `EngineType.RPGMAKER`。
   - 新增 `ScanGame.externalModuleAlias`。
   - 扫描器加入 RGSS 特征和子类型落库。
   - 持久化/Intent 序列化兼容旧数据。
2. 外置模块协议：
   - 新增 `RpgMakerExternalEngineModule`。
   - 注册进 `ExternalEngineModuleRegistry`。
   - 补 `ExternalEngineLaunchRequest` alias/subtype 能力。
   - 实现 action 映射、game/settings JSON、RTP/config 准备。
   - Manifest 增加 RPGM 包可见性。
3. UI 与验证：
   - 引擎页加入 RPGM 模块 item 和下载弹窗。
   - 游戏抽屉隐藏外置模块存档管理 item。
   - README 支持范围补充 RPG Maker XP/VX/VX Ace/mkxp-z。
   - 补单元测试、构建验证和实机验证。

## 10. 测试方案

### 10.1 单元测试

新增测试：

```text
app/src/test/java/com/tyranor/next/core/game/scan/EngineScannerRenPyTest.kt
app/src/test/java/com/tyranor/next/core/engine/external/ExternalEngineModuleRegistryTest.kt
app/src/test/java/com/tyranor/next/core/engine/external/RenPyExternalEngineModuleTest.kt
```

覆盖：

- `.rpa` 识别为 `EngineType.RENPY`
- `game/script.rpy` 识别为 RenPy
- `renpy/ + .rpyc` 识别为 RenPy
- 普通 `index.html` 仍识别为 WebOther / Tyrano，不被 RenPy 抢占
- RenPy module alias 能正确命中模块
- RenPy intent extras 中 `game.type == "renpy"`
- 未安装模块返回 `package_not_installed`

### 10.2 构建验证

必须执行：

```bash
git diff --check
./gradlew testDebugUnitTest --no-daemon
./gradlew assembleDebug --no-daemon
android describe --project_dir=/Users/weiss/opencode/rma/TyranorNext
```

### 10.3 实机验证

需要安装 RenPy 外置 APK 的设备验证：

1. 安装 TyranorNext debug 包。
2. 安装 RenPy 外置 APK。
3. 打开 TyranorNext → 引擎页，确认 RenPy item 右侧显示打勾。
4. 卸载 RenPy 外置 APK 后重新进入引擎页，确认 RenPy item 右侧显示打叉。
5. 未安装状态下单击 RenPy item，确认弹窗提示下载模块。
6. 添加 RenPy 游戏目录或扫描包含 RenPy 游戏的根目录。
7. 确认游戏识别为 `Ren'Py`。
8. 点击启动。
9. 确认外置 RenPy APK 被拉起。

需要额外验证 SD 卡路径：

```text
/storage/<volume>/...
```

如果外置 APK 无法读取 SD 卡目录，应记录为外置 APK 权限/路径兼容问题，而不是主 App 扫描失败。

## 11. 实施步骤

### 步骤 1：新增方案文档

新增：

```text
docs/外置APK引擎模块接入方案.md
```

### 步骤 2：新增 RenPy EngineType

修改：

```text
app/src/main/java/com/tyranor/next/core/engine/EngineType.kt
```

新增：

```kotlin
RENPY("Ren'Py")
```

同步补齐：

- `EngineLauncher.supportedEngines`
- `EnginePluginBootstrap.ensureForLaunch`
- `GameSaveManager`
- `PerGameSettingsScreen`
- `EngineScreen.engineDisplayName`
- `EngineScreen.engineDescription`
- `GameScreen.coverColor`
- README 支持范围

### 步骤 3：新增外置 APK 模块抽象

新增：

```text
app/src/main/java/com/tyranor/next/core/engine/external/ExternalEngineModule.kt
app/src/main/java/com/tyranor/next/core/engine/external/ExternalEngineModuleRegistry.kt
app/src/main/java/com/tyranor/next/core/engine/external/ExternalEngineLauncher.kt
app/src/main/java/com/tyranor/next/core/engine/external/ExternalEngineLaunchRequest.kt
app/src/main/java/com/tyranor/next/core/engine/external/ExternalEngineLaunchResult.kt
app/src/main/java/com/tyranor/next/core/engine/external/RenPyExternalEngineModule.kt
```

### 步骤 4：扫描器加入 RenPy 识别

修改：

```text
app/src/main/java/com/tyranor/next/core/game/scan/EngineScanner.kt
```

新增 RenPy 特征字段：

- `hasRenpyDir`
- `hasGameDir`
- `hasRpy`
- `hasRpyc`
- `hasGameScriptRpy`
- `hasOptionsRpy`
- `firstRpa`

新增判定：

```text
.rpa
game/script.rpy
game/options.rpy
renpy/ + .rpy/.rpyc
game/ + .rpy
```

### 步骤 5：启动器接入外置引擎分支

修改：

```text
app/src/main/java/com/tyranor/next/core/game/launch/EngineLauncher.kt
```

在 native/web 内置分支前加入：

```kotlin
if (ExternalEngineModuleRegistry.supports(game.engine)) {
    val result = ExternalEngineLauncher.launch(...)
    if (result.success) EngineScanner.recordRecentGame(context, game)
    return result.message
}
```

### 步骤 6：Manifest 增加包可见性

修改：

```text
app/src/main/AndroidManifest.xml
```

新增：

```xml
<queries>
    <package android:name="cyou.joiplay.runtime.renpy.v8d4d1" />
</queries>
```

### 步骤 7：引擎页增加外置 APK 模块状态

修改：

```text
app/src/main/java/com/tyranor/next/ui/engine/EngineScreen.kt
```

要求：

- 内置引擎 item 右侧保持打勾。
- RenPy 等外置 APK 引擎 item 右侧按 APK 安装状态显示打勾/打叉。
- 未安装状态下点击 item 弹窗提示下载模块。
- 已安装状态下点击 item 弹窗提示模块可用。

### 步骤 8：补测试

新增 RenPy 扫描和外置模块抽象测试。

### 步骤 9：更新 README

补充：

- 支持范围新增 RenPy。
- 技术架构新增外置 APK 引擎模块说明。
- 目录结构新增 `core/engine/external`。

## 12. 风险与注意事项

### 12.1 外置 APK 协议不稳定

JoiPlay 系插件 action、package、extra JSON 字段来自逆向和实际行为验证，未来版本可能变化。

缓解：

- 所有协议集中在 `RenPyExternalEngineModule.kt`。
- 不在 UI / 扫描器散落 package/action 字符串。

### 12.2 SAF URI 与真实路径割裂

主 App 可以通过 SAF 扫描目录，但外置 APK 可能只认真实路径。

缓解：

- 使用 `EngineScanner.safUriToPath` 做路径映射。
- 映射失败时明确报错。
- 不做静默复制。

### 12.3 权限不可代申请

主 App 无法替外置 APK 完成所有文件访问授权。

缓解：

- 引擎页未安装/启动失败弹窗说明需要安装外置 APK，并给外置 APK 授予存储权限。
- 启动失败文案提示检查外置模块权限。

### 12.4 UI 与外置协议耦合

如果 UI 直接拼 intent，后续每加一个模块都会扩散。

缓解：

- UI 只展示模块状态。
- 外置协议全部放入 core 抽象。

### 12.5 EngineType 扩散修改

新增 `RENPY` 后所有 exhaustive `when` 都必须补齐。

缓解：

- 用编译器暴露遗漏。
- 添加测试覆盖扫描、颜色、存档不可用说明等路径。

## 13. 首期完成标准

首期 RenPy 支持完成后，应满足：

- 扫描 RenPy 游戏目录可识别为 `Ren'Py`。
- 游戏列表能正常展示 RenPy 卡片。
- 引擎页能展示 RenPy。
- 引擎页能看到 RenPy 安装状态：已安装打勾，未安装打叉。
- 未安装状态下单击 RenPy item 会弹窗提示下载模块。
- 未安装 RenPy APK 时启动返回清晰错误。
- 已安装时可发起外置 APK intent。
- RenPy intent 构造逻辑位于通用外置模块抽象内。
- 后续新增 RPGM/Godot 不需要复制 RenPy 启动框架，只需新增模块定义和少量特化构造逻辑。
- `testDebugUnitTest` 与 `assembleDebug` 通过。
