# 外置 APK 引擎模块接入方案

> 实施状态：首期 Ren'Py 外置 APK 模块支持已按本文方案落地。后续 RPG Maker XP/VX/VX Ace、Godot 等外置引擎可复用 `core/engine/external` 注册、安装状态检查和启动协议抽象。

## 1. 背景与目标

TyranorNext 当前已经内置 Kirikiri / ONScripter / Tyrano / Artemis / RPG Maker MV/MZ / VN / WebOther 等运行路径，其中原生引擎与 Web 引擎都由当前 APK 自身承担启动与宿主逻辑。

后续若继续把 RenPy、RPG Maker XP/VX/VX Ace、Godot 等完整运行时直接集成进主 APK，会带来几个问题：

- APK 体积膨胀明显。
- 各引擎运行时依赖、ABI、权限与生命周期差异大。
- 外部引擎更新频率和主 App 不一致，耦合后维护成本高。
- 部分引擎已经有现成外部运行时 APK，可复用其 intent 协议启动。

因此计划引入“外置 APK 引擎模块”体系：主 App 负责扫描、识别、管理、校验模块状态和发起启动；具体游戏运行交给已安装的外置引擎 APK。

首个落地目标：

- 接入 RenPy 外置引擎 APK。
- 同时抽象出通用外置 APK 模块能力，为后续 RPG Maker XP/VX/VX Ace、Godot 等引擎接入复用。

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
└── RenPyExternalEngineModule.kt
```

后续可继续加入：

```text
RpgMakerExternalEngineModule.kt
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
| `action` | 外置 APK 接收的启动 action |
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

首期只注册 RenPy：

```kotlin
RenPyExternalEngineModule
```

后续接 RPGM / Godot 时只新增模块定义和策略，不修改 UI 多处硬编码。

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
    <package android:name="cyou.joiplay.runtime.renpy.v8d4d1" />
</queries>
```

后续 RPGM / Godot 接入时继续追加：

```xml
<package android:name="cyou.joiplay.runtime.rpgmaker" />
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
