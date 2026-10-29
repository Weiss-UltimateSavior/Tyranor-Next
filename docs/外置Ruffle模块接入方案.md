# 外置 Ruffle 模块接入方案（SWF / Flash）

> 来源：`core/engine/external/RuffleExternalEngineModule.kt`、`RuffleRuntimeEnvironment.kt`、`EngineScanner` 的 SWF 分支，以及本机安装包 `cyou.joiplay.runtime.ruffle` 1.02.00 的协议信息。
> 范围：Ruffle 外置 APK 模块的识别、启动、设置与 `configuration.json` 同步；不接管存档，不提供虚拟按键重映射，不内置 Flash 运行时。
> 实施状态：代码已接入，定向 JVM 回归通过。**设备与插件运行验证尚未执行**，本文不构成兼容性或设备验收结论。

## 1. 模块身份与安装

| 项 | 值 |
| --- | --- |
| 包名 | `cyou.joiplay.runtime.ruffle` |
| 引擎类型 | `EngineType.RUFFLE`（显示名 Ruffle，角标 SWF） |
| 模块别名 | 默认 `internal.flash`；兼容 `external.flash` / `internal.ruffle` / `external.ruffle` |
| 模块版本 | 1.02.00（界面显示 `Ruffle-1.02.00`） |
| 入口 Activity | `cyou.joiplay.runtime.ruffle.PermissionActivity`（extras 实际由插件内部 MainActivity 消费） |
| 接受的 action | `cyou.joiplay.runtime.flash.run`、`cyou.joiplay.runtime.ruffle.run` |
| TyranorNext 使用的 action | `cyou.joiplay.runtime.flash.run` |
| 安装方式 | 用户另行安装；主 App 只探测安装状态并拉起，不提供手动启用开关 |
| 获取入口 | `https://github.com/Weiss-UltimateSavior/RinneMobile/releases/download/test/RufflePlugin-1.02.00.apk`（APK 直链） |
| 屏幕方向 | 仅横屏；启动 extra `orientation = 6` |
| 存储权限 | 主 App 首次启动可能需要「所有文件访问」才能把 SWF 记录解析为真实路径；插件可能另行申请自身存储权限 |

`AndroidManifest.xml` 的 `<queries>` 已声明该包名，Android 11+ 可做安装探测。未安装时引擎页的 Ruffle 条目（分类显示时归入「网页」）显示未安装，点击安装入口打开项目托管的 RufflePlugin-1.02.00.apk 直链；启动返回 `PACKAGE_NOT_INSTALLED`，由 UI 映射文案。插件自身的权限请求以其安装包与运行时申请为准，主 App 只处理自身的「所有文件访问」门禁。

## 2. 启动契约

### 2.1 extras

| extra | 内容 |
| --- | --- |
| `game` | JSON 字符串，见 2.2 |
| `settings` | JSON 字符串，见 2.3 |
| `orientation` | `6`（横屏） |
| `rootUri` | 游戏记录原始 URI（文件或目录） |
| `launchTarget` | 入口 SWF 文件名 |

### 2.2 `game`

```json
{
  "title": "游戏标题",
  "id": "<SWF 完整路径的 SHA-256 前 24 位十六进制>",
  "folder": "/storage/emulated/0/Games/example",
  "execFile": "game.swf",
  "type": "flash"
}
```

- `folder` 为 SWF 所在目录。文件记录在启动前由 `EngineLauncher.resolveLaunchEntryDirectory` 解析父目录；包装目录记录直接使用目录 URI 对应的真实路径。
- `execFile` 优先取单游戏 `launchFile`，其次 `launchTarget`，最后回退目录内唯一 SWF；显式 SWF 缺失或越界时启动失败，不静默换成同目录其它 SWF。
- `id` 由 `folder/execFile` 的完整路径取 SHA-256 前 24 个十六进制字符，同一目录多个 SWF 不会碰撞。
- 标题为空时先回退目录名，再回退 `Flash Game`。

### 2.3 `settings`

值必须带 `int` / `string` 包装：

```json
{
  "app": {
    "screenTimeout": { "int": 0 }
  },
  "ruffle": {
    "backend": { "string": "opengl" },
    "quality": { "string": "high" },
    "scaleMode": { "string": "showall" },
    "letterbox": { "string": "on" },
    "loadBehavior": { "string": "streaming" }
  }
}
```

| 字段 | 允许值 | 默认 |
| --- | --- | --- |
| `app.screenTimeout` | 0 / 5 / 10 / 15 / 30 / 45 / 60（分钟，0 = 不自动关闭） | 0 |
| `ruffle.backend` | `auto` / `opengl` / `vulkan` | `opengl` |
| `ruffle.quality` | `low` / `medium` / `high` / `best` | `high` |
| `ruffle.scaleMode` | `exactfit` / `noborder` / `noscale` / `showall` | `showall` |
| `ruffle.letterbox` | `on` / `off` | `on` |
| `ruffle.loadBehavior` | `streaming` / `delayed` / `blocking` | `streaming` |

未解析到生效设置时按默认模型全量下发，避免插件缺键时走自身默认。

## 3. `configuration.json` 与同步策略

插件读取顺序（`loadConfig`）：

1. 游戏目录位于外部存储根的子目录：`<gameFolder>/configuration.json`；
2. 其它情况（含存储根本身、可移动存储等）：`/storage/emulated/0/JoiPlay/games/<game.id>/configuration.json`。

**文件存在时，文件值覆盖 intent `settings`。**

TyranorNext 采用策略 A：

- 仅在文件已存在、长度不超过 1 MiB 且内容为合法 JSON 对象时同步；
- 只写受管键（`screenTimeout`、`backend`、`quality`、`scaleMode`、`letterbox`、`loadBehavior`），保留未知键；
- 不创建、不删除配置文件；文件缺失、损坏或超限时直接跳过，由插件按自身默认处理；
- 跳过同一目录多个 SWF 的共享目录与外部存储根，避免目录级配置把某个游戏的设置串给同目录其它游戏；
- 写入使用同目录 `.tmp` + rename；rename 失败保留原文件，同步失败不影响启动。

## 4. 扫描规则与包装目录边界

### 4.1 默认：一 SWF 一条记录

扫描目录的直接子项中，每个顶层 `.swf` 默认都是一条独立记录：

```text
ScanGame(
    uri = <SWF 文件 URI>,
    engine = EngineType.RUFFLE,
    launchTarget = <SWF 文件名>,
    externalModuleAlias = internal.flash,
)
```

- 无根 `index.html/index.htm` 的单 SWF 目录仍按文件记录，不会因为目录里只有这一个文件就变成目录型记录。
- 同目录多个 SWF 时逐条入库；标题取文件主名（去扩展名），单 SWF 时取父目录名，目录名缺失再回退文件主名。
- 同一目录仅一个 ROM/SWF 时复用目录内的本地封面；多个 SWF 不绑定单张封面。

### 4.2 例外：包装目录

同时满足以下条件时，整个目录作为一条记录：

- 目录是叶子目录，没有子目录；
- 根目录存在 `index.html` 或 `index.htm`；
- 根目录恰有一个顶层 `.swf`；
- 没有 KRKR/ONS/XSYS4/主机 ROM 特征（`.xp3`、`startup.tjs`、`.nsa`、`nscript.dat`、System4 ini、PSP/Switch ROM 等）；
- 没有 Tyrano / RPG Maker MV/MZ / VN / Node（`app.asar`）特征。

命中后记录为目录 URI、`launchTarget = <SWF 文件名>`，启动时模块收到目录与 `execFile`。该判定排在通用 `index.html → WebOther` 之前，但命中条件已排除既有引擎特征，不会抢走 KRKR / ONS / XSYS4 / 主机 ROM 或 Tyrano / RPGM / VN / Node 记录。

**不自动识别的情况**：目录含子目录、根目录多个 SWF、没有根 `index.html/index.htm`。这些目录仍按 4.1 逐文件入库；包装目录里有子目录时不会递归再判。

## 5. 设置映射

| 作用域 | 存储 | 键 |
| --- | --- | --- |
| 全局 | `tyranor_prefs.xml` | `ruffle_screen_timeout`、`ruffle_backend`、`ruffle_quality`、`ruffle_scale_mode`、`ruffle_letterbox`、`ruffle_load_behavior` |
| 单游戏 | `tyranor_game_overrides.xml` 的游戏 blob | 同名 `ruffle_*` 字段；`ruffle_screen_timeout` 以字符串保存，缺失 = 跟随全局 |

- 全局入口：设置 → 引擎设置 → Ruffle；单游戏入口：游戏操作面板 → 引擎设置 → Ruffle。
- 启动前由 `EngineSettingsResolver` 读取全局与单游戏覆盖，`EffectiveEngineSettings.mergeRuffle` 逐字段合并：覆盖值非法时回退全局，全局也非法时回退插件默认。
- 启用 `configuration.json` 时，文件值最终优先于本次 intent；该文件的受管键同步见第 3 节。

## 6. 错误、安装行为与已知限制

| 场景 | 行为 |
| --- | --- |
| 模块未安装 | `PACKAGE_NOT_INSTALLED`；引擎页与启动失败弹窗均可打开项目托管的 RufflePlugin-1.02.00.apk 直链 |
| 入口 Activity 不存在 | `ACTIVITY_NOT_FOUND` |
| 无权限 / 安全异常 | `SECURITY_EXCEPTION` |
| 启动异常 | `LAUNCH_EXCEPTION` |
| 入口 URI 无法解析为真实路径 | `GameDirUnresolved`（UI 提示无法解析游戏目录） |
| 找不到 SWF 或入口越界 | `PREPARE_FAILED`，文案 `external_ruffle_resolve_swf_failed` |
| `configuration.json` 同步失败 | 非致命；保留原文件，启动继续 |

已知限制：

- 安装入口使用项目托管的 RufflePlugin-1.02.00.apk 直链；插件后续版本需要同步更新 `installUrl` 与协议核对。
- 不提供统一存档管理；Ruffle 运行时与存档由插件自行负责，主 App 不读取、不导出、不删除。
- 不提供虚拟按键 / 手柄重映射；主 App 不向该模块注入按键层或手柄映射。
- 插件仅横屏；主 App 不改变该方向。
- 包装目录只在叶子目录、单 SWF、根 index 的窄条件下自动识别；有子目录或多 SWF 的目录不会合并为一条。
- 多 SWF 共享目录即使已存在 `configuration.json`，主 App 也不会改写它；插件仍会以该文件覆盖 intent，单游戏设置可能不生效。
- 协议按 1.02.00 实现；插件后续版本若调整 action、extras 或配置键，需要重新核对。
- 设备与插件运行验证尚未执行，不能据此声明任何 SWF 游戏可运行或兼容。

## 7. 验证清单

### 7.1 已执行（JVM）

```bash
./gradlew :app:testDebugUnitTest --no-daemon \
  --tests 'com.tyranor.next.core.engine.external.RuffleExternalEngineModuleTest' \
  --tests 'com.tyranor.next.core.engine.external.RuffleRuntimeEnvironmentTest' \
  --tests 'com.tyranor.next.core.game.scan.EngineScannerSwfTest' \
  --tests 'com.tyranor.next.core.settings.RuffleSettingsKeysTest' \
  --tests 'com.tyranor.next.core.game.launch.LaunchEntryPathTest' \
  --tests 'com.tyranor.next.core.engine.external.ExternalEngineModuleRegistryTest' \
  --tests 'com.tyranor.next.core.settings.EffectiveEngineSettingsTest' \
  --tests 'com.tyranor.next.core.game.storage.GameOverridePartitionsTest'
```

结果：通过。覆盖 `game` / `settings` JSON、文件记录取父目录、单/多 SWF 标题、包装目录边界、既有引擎优先、设置键与默认值、`configuration.json` 策略 A、文件入口路径解析。

### 7.2 待执行（设备 / 插件）

- [ ] 安装 `cyou.joiplay.runtime.ruffle` 1.02.00，引擎页与 Ruffle 设置页均显示已安装。
- [ ] 授予主 App「所有文件访问」，启动单 SWF（无根 index）记录，确认进入横屏且画面正常。
- [ ] 启动「根 index + 唯一 SWF」包装目录记录，确认 `folder` / `execFile` 正确。
- [ ] 同目录多个 SWF：确认逐条入库、标题取文件主名、单游戏设置互不串用。
- [ ] 修改全局与单游戏设置，确认 `settings` 生效；已有 `configuration.json` 时确认文件值优先且只改受管键。
- [ ] 未安装模块、入口 SWF 缺失、目录无法解析三种失败路径的提示与安装入口。
- [ ] 确认游戏操作面板没有统一存档入口；插件自身存档可正常读写。
- [ ] 记录设备型号、系统版本、插件版本与样本，再判断兼容性。
