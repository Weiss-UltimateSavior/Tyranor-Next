# 外置 Ren'Py 模块配置接入引擎设置清单计划

> 来源：JoiPlay 逆向分析（主 App `/Users/weiss/Desktop/decompiled/joiplay_jadx` + RenPy 插件 `renpy_out` 8.5.0-1.01.00）。
> 目标：把 JoiPlay 对 Ren'Py 外置模块（`cyou.joiplay.runtime.renpy.*`）的设置能力，落到 TyranorNext 现有「应用 / 引擎 / 单游戏」三级设置体系中，通过启动 Intent 的 `settings` extra 与可选 `configuration.json` 下发给插件。
> 范围：`EngineType.RENPY`（Ren'Py 8.5 / 7.7.1 外置模块）。不含插件内部的 renpy 运行时自更新、存档重定向（`JOIPLAY_SAVEDIR`）与游戏菜单注入脚本（xde/xun）。
>
> 实施状态：**已完成**（阶段 0-3；P2 screenTimeout 未实施）。落地要点：
> - `settings` extra 下发 `app.cheats` + 8 个 `renpy_*`（`RenPyRuntimeModule.buildSettingsJson`）；
> - 全局 `RenPySettingsCard` + 单游戏 9 项覆盖；
> - `configuration.json` 策略 A：插件会读的配置文件存在时同步受管键（`RenPyRuntimeEnvironment`）；
> - 此计划记录了初始接入。其 GL2 反选方案已由现行规格替代。全局与单游戏界面开启「禁用模型渲染（GL2）」时均写入 `renpy_dont_use_gl2=true`。见 [外置引擎启动规格](../SPEC.md)。

---

## 1. 背景

JoiPlay 对 Ren'Py 模块的配置分两条通道（插件 `RenPyConfigurationParser`）：

1. **Intent `settings`（嵌套）**：整包 Settings JSON，插件只读 `app` 与 `renpy` 两节；
2. **`configuration.json`（扁平）**：插件 `loadConfig()` 在 Intent 解析**之后**读取，同键覆盖 Intent 值。

设置内容为 8 个 `renpy_*` 布尔 + `app.cheats`（+ `app.screenTimeout`）。版本不在 settings 里，而由「游戏 type + script_version」决定外置包选择（`r0.d`）。

TyranorNext 当前状态：

- 版本链路完整：`RenPyVersionDetector`（`script_version.txt/rpy` + `lib/pythonlib2.7` → 7.7.1/8.5）、`ExternalEngineModuleRegistry.resolveRenpyModule`（显式版本 > 探测 > 默认 8.5）、全局/单游戏版本下拉；
- `RenPyExternalEngineModule.buildLaunchIntent` 固定 `settings = "{}"`（`RenPyExternalEngineModule.kt:29`）；
- 无 `configuration.json` 读写，无 8 项运行时设置，无 `cheats`；
- 引擎页已有模块安装状态/下载入口；启动已传 `orientation=6`（Rinne 参考，JoiPlay 不传，保持现状）。

---

## 2. JoiPlay 功能盘点与现状对照

| # | JoiPlay 能力 | JoiPlay 实现 | TyranorNext 现状（已实施） |
| --- | --- | --- | --- |
| 1 | `settings` 下发 `renpy` 节 8 项 | `RenPyConfigurationParser.parse`（smali:281-462） | `RenPyRuntimeModule.buildSettingsJson`，空值回退默认模型 |
| 2 | `app.cheats` 金手指 | `app.cheats` → `JoiPad.cheats()`（PythonSDLActivity:1965） | `renpy_cheats`（全局/单游戏），JSON 发 `app.cheats` |
| 3 | `renpy_hw_video` | env `JOIPLAY_HW_VIDEO` | 已接入 |
| 4 | `renpy_autosave` | env `JOIPLAY_AUTOSAVE` | 已接入 |
| 5 | `renpy_phonesmallvariant` | env `JOIPLAY_VARIANT_PHONE` | 已接入 |
| 6 | `renpy_vsync` | env `RENPY_GL_VSYNC` | 已接入 |
| 7 | `renpy_less_memory` | env `RENPY_LESS_MEMORY` | 已接入 |
| 8 | `renpy_less_updates` | env `RENPY_LESS_UPDATES` | 已接入 |
| 9 | `renpy_dont_use_gl2` | env `RENPY_DONT_USE_GL2` | 已接入（UI 反选） |
| 10 | `renpy_recompile` | env `RENPY_RECOMPILE` | 已接入（高级区） |
| 11 | 全局设置页 9 开关 | `SettingsFragment` + `w0.java`（含 `dont_use_gl2` 反选） | `RenPySettingsCard.kt`（分组 + 高级收起） |
| 12 | 单游戏覆盖 9 项 | 游戏菜单 Options（`f0.java:408`，`app.cheats` + 8） | 单游戏 9 项 OverrideSwitch |
| 13 | `configuration.json` 扁平覆盖 | 路径 `<game.folder>` 或 `/sdcard/JoiPlay/games/<id>/`；Intent 之后加载，文件优先 | `RenPyRuntimeEnvironment` 策略 A 同步受管键 |
| 14 | 版本自动选择 | `r0.d`：script_version → 最小可用 `supportedVersion`；类型含 7/legacy → <80000 | `RenPyVersionDetector` + 版本白名单（等价效果，机制简化），已有 |
| 15 | `runtime_better_available` 提示 | Ren'Py 独有，目录版本优于已装时弹窗 | 未实施（可选项） |
| 16 | 模块安装状态/下载 | `runtimes.json` + RuntimeFragment | `EngineScreen` 已有 |
| 17 | 权限门 | 插件 `PermissionActivity` 自行处理所有文件权限 | 插件自理 |
| 18 | `RENPY_PATCHPATH` / `renpy/common` 拷贝 / `zj.rpy` | 插件内部运行时准备 | 不纳入（插件自理） |
| 19 | 主 App 注入 `xde.rpy`（RPA 解包）/`xun*.rpy`（rpyc 反编译） | 游戏菜单工具 | 不纳入（游戏工具） |
| 20 | `app.screenTimeout` 自动关闭 | 全局设置 → `AutoCloseTimer`（RPGM 插件同读） | 未实施（P2，应用级，另列计划） |

不复刻项：`runtime_better_available` 弹窗（TyranorNext 以显式版本设置代替）；`orientation`（JoiPlay 不传，TyranorNext 保持传 6）。

---

## 3. 目标设置模型

沿用 RPGM 已落地的三级链路，新增 Ren'Py 一组键：

```text
EngineSettingsStore(全局默认, 含既有 renpy_engine_version)
  + PerGameSettingsStore(单游戏覆盖, null=跟随全局)
    → EffectiveEngineSettings.mergeRenPy
      → ResolvedEngineSettings.renpy
        → EngineLauncher.launchInternal 构造 ExternalEngineLaunchRequest(resolvedSettings)
          → RenPyRuntimeModule.buildLaunchIntent
            → Intent extra "settings" (app + renpy 嵌套 JSON)
            → 可选同步 configuration.json (扁平 JSON, 插件优先读取)
```

关键改造点：

- [x] `RenPyRuntimeModule.buildLaunchIntent` 消费 `request.resolvedSettings?.renpy`，替换固定 `"{}"`；空值回退默认模型（不破坏现有无设置启动）。
- [x] `ExternalEngineLaunchRequest.resolvedSettings` 已存在（RPGM 阶段新增），无需改动。
- [x] 版本仍由既有 `renpyVersion` / `detectedRenpyVersion` / `resolveRenpyModule` 决定，**不进 settings JSON**。
- [x] `cheats` 与 `screenTimeout` 协议位置在 `app` 节，不在 `renpy` 节。

---

## 4. 键位与默认值总表

### 4.1 引擎设置项（settings JSON 键）

| 设置项 | JSON 位置 | JSON 键 | 默认值（对齐 JoiPlay） | 全局键名 | UI 控件 |
| --- | --- | --- | --- | --- | --- |
| 金手指 | `app` | `cheats` | `true` | `renpy_cheats` | Switch |
| 硬件视频解码 | `renpy` | `renpy_hw_video` | `true` | `renpy_hw_video` | Switch |
| 自动存档 | `renpy` | `renpy_autosave` | `false` | `renpy_autosave` | Switch |
| 手机版变体 | `renpy` | `renpy_phonesmallvariant` | `false` | `renpy_phonesmallvariant` | Switch |
| 垂直同步 | `renpy` | `renpy_vsync` | `false` | `renpy_vsync` | Switch |
| 低内存模式 | `renpy` | `renpy_less_memory` | `false` | `renpy_less_memory` | Switch |
| 减少更新检查 | `renpy` | `renpy_less_updates` | `false` | `renpy_less_updates` | Switch |
| 禁用 GL2 渲染 | `renpy` | `renpy_dont_use_gl2` | `false` | `renpy_dont_use_gl2` | Switch（UI 反选，见 §8） |
| 重新编译脚本 | `renpy` | `renpy_recompile` | `false` | `renpy_recompile` | Switch（高级区） |
| 自动关闭（分钟） | `app` | `screenTimeout` | `0`（关闭） | 应用级，P2 | 下拉（0/15/30/45/60） |

默认值来源：`SettingsFactory.java:112-115`（`renpy` 节默认）+ 插件 `RenPyConfiguration` 模型；`cheats` 默认 `true` 与 RPGM 侧一致。

### 4.2 单游戏覆盖键（PerGameSettingsStore）

与全局一一对应，`F_` 前缀，null=跟随全局：

- [x] `F_RENPY_CHEATS`、`F_RENPY_HW_VIDEO`、`F_RENPY_AUTOSAVE`、`F_RENPY_PHONE_SMALL_VARIANT`
- [x] `F_RENPY_VSYNC`、`F_RENPY_LESS_MEMORY`、`F_RENPY_LESS_UPDATES`、`F_RENPY_DONT_USE_GL2`、`F_RENPY_RECOMPILE`

### 4.3 不纳入项

- 版本号本身（既有 `F_RENPY_VERSION` / `KEY_RENPY_ENGINE_VERSION` 已覆盖，且不是插件配置项）；
- `RENPY_PATCHPATH`、`JOIPLAY_SAVEDIR`、`ANDROID_EXTRAS`、`lib/pythonlib2.7` 探测（插件/探测链路自理）；
- xde/xun 注入脚本（属游戏菜单工具）。

---

## 5. settings JSON 契约

插件 `RenPyConfigurationParser.parse(String)` 读取嵌套格式（kotlinx `PrimitiveData` 包装）：

```json
{
  "app": {
    "cheats": { "boolean": true },
    "screenTimeout": { "int": 0 }
  },
  "renpy": {
    "renpy_hw_video": { "boolean": true },
    "renpy_autosave": { "boolean": false },
    "renpy_phonesmallvariant": { "boolean": false },
    "renpy_vsync": { "boolean": false },
    "renpy_less_memory": { "boolean": false },
    "renpy_less_updates": { "boolean": false },
    "renpy_dont_use_gl2": { "boolean": false },
    "renpy_recompile": { "boolean": false }
  }
}
```

- [x] 用 `org.json.JSONObject` 构造（对齐 `RpgMakerExternalEngineModule.buildSettingsJson`）。
- [x] 全量下发（不做“仅变化键”优化），保证与 JoiPlay 行为一致、插件侧无缺键。
- [x] `screenTimeout` 未实施时不下发该键（插件保持默认 0）。
- [x] 单测覆盖：包装类型正确、`cheats` 在 `app` 节、空设置回退默认。

---

## 6. configuration.json 契约

插件读取路径（`PythonSDLActivity.loadConfig`，smali:478-590）：

```text
game.folder 以 /sdcard（ExternalStorageDirectory）开头
  → <game.folder>/configuration.json
否则
  → /sdcard/JoiPlay/games/<game.id>/configuration.json
```

加载顺序：`parse(settings)` → `loadFromFile(configuration.json)`，**文件存在时同键覆盖 Intent**，且文件为扁平格式：

```json
{
  "screenTimeout": 0,
  "cheats": true,
  "renpy_hw_video": true,
  "renpy_autosave": false,
  "renpy_phonesmallvariant": false,
  "renpy_vsync": false,
  "renpy_less_memory": false,
  "renpy_less_updates": false,
  "renpy_dont_use_gl2": false,
  "renpy_recompile": false
}
```

处理策略（待决策，推荐 A）：

- [x] A（采用，与 RPGM `syncGameConfiguration` 一致）：文件存在且为合法 JSON 时，仅更新上述受管键为生效值；文件不存在时不创建（保持 JoiPlay 行为）；非 JSON 文件跳过并记日志。
- [ ] B（未采用）：完全不动 `configuration.json`，仅在文档标记「已有文件会覆盖 App 设置」的风险。
- [x] `game.id` 沿用现有 `Integer.toHexString(folder.hashCode())`（与 RPGM `gameIdFor` 一致），保证两模块路径契约统一。

---

## 7. 插件侧消费映射（验收参考）

| settings 键 | 插件行为 |
| --- | --- |
| `app.cheats` | `JoiPad.cheats(enabled)` |
| `app.screenTimeout` | `>0` 时创建 `AutoCloseTimer`（分钟后自动关闭） |
| `renpy_hw_video` | `JOIPLAY_HW_VIDEO=1/0` |
| `renpy_autosave` | `JOIPLAY_AUTOSAVE=1/0` |
| `renpy_phonesmallvariant` | `JOIPLAY_VARIANT_PHONE=1/0` |
| `renpy_vsync` | `RENPY_GL_VSYNC=1/0` |
| `renpy_less_memory` | 仅 true 时设 `RENPY_LESS_MEMORY=1` |
| `renpy_less_updates` | 仅 true 时设 `RENPY_LESS_UPDATES=1` |
| `renpy_dont_use_gl2` | 仅 true 时设 `RENPY_DONT_USE_GL2=1` |
| `renpy_recompile` | 仅 true 时设 `RENPY_RECOMPILE=1` |

---

## 8. 引擎设置 UI 清单

### 8.1 全局引擎设置页（`SettingsScreen.kt` 的 `EngineSettingsKind.RENPY`）

- [x] 现有版本下拉保留在上方；
- [x] 新增“Ren'Py 模块设置”卡片，分组：
  - 兼容/性能：`renpy_hw_video`、`renpy_vsync`、`renpy_less_memory`、`renpy_dont_use_gl2`、`renpy_phonesmallvariant`；
  - 存档/更新：`renpy_autosave`、`renpy_less_updates`；
  - 高级（默认收起）：`renpy_recompile`、`cheats`；
- [x] `renpy_dont_use_gl2` 反选语义与 JoiPlay 一致：开关文案“禁用模型渲染（GL2）”，`checked = !dontUseGl2`；
- [x] 模块安装状态行与下载入口（决定不在卡片内重复，引擎总页已有）；
- [x] 建议将 Ren'Py 卡片抽成独立文件 `ui/settings/RenPySettingsCard.kt`（与 `RpgMakerSettingsCard.kt` 对称），`SettingsScreen.kt` 只挂载。

### 8.2 单游戏设置页（`PerGameSettingsScreen.kt` 的 `EngineType.RENPY` 分支）

- [x] 保留版本 `OverrideChoice`；
- [x] 新增 9 项 `OverrideSwitch`（含 cheats），语义「null=跟随全局」；
- [x] 文案说明改为“各项默认跟随全局，可单独覆盖”（现文案需同步校准）。

### 8.3 文案键（三语言同步）

```text
engine_settings_renpy_card                 Ren'Py 模块设置
engine_settings_renpy_hw_video             硬件视频解码
engine_settings_renpy_autosave             自动存档
engine_settings_renpy_phone_small_variant  手机版变体
engine_settings_renpy_vsync                垂直同步
engine_settings_renpy_less_memory          低内存模式
engine_settings_renpy_less_updates         减少更新检查
engine_settings_renpy_dont_use_gl2         禁用模型渲染（GL2）
engine_settings_renpy_recompile            重新编译脚本
engine_settings_renpy_cheats               金手指
engine_settings_renpy_advanced             高级设置
```

`tools/check-hardcoded-ui-strings.py` 必须通过。

---

## 9. 分阶段实施清单

### 阶段 0：设置模型与下发协议

- [x] `EngineSettingsStore.kt`：新增 `RenPy` 数据类（9 布尔）+ `KEY_RENPY_*` 全局键 + `loadRenPy/saveRenPy/resetRenPy`；与既有 `getRenpyVersion` 并存。
- [x] `PerGameSettingsStore.kt`：新增 9 个 `F_RENPY_*` 覆盖键 + `toRenPyOverride`。
- [x] `EffectiveEngineSettings.kt`：新增 `RenPyOverride` + `mergeRenPy(global, override)`（风格对齐 `mergeRpgMaker`）。
- [x] `EngineSettingsResolver.kt`：产出 `ResolvedEngineSettings.renpy`。
- [x] `GameOverridePartitions.kt`：将 9 个 `F_RENPY_*` 显式归入 renpy 分区（现有 `RENPY_KEYS` 扩充），补契约测试。
- [x] `RenPyRuntimeModule.buildLaunchIntent`：`settings = buildSettingsJson(resolvedSettings?.renpy)`，用 `JSONObject` 组装 `app` + `renpy`。
- [x] 新增 `RenPyRuntimeEnvironment.kt`（或模块内私有方法）：`configuration.json` 的条件同步（策略 A）。
- [x] 纯 JVM 单测：合并规则、JSON 包装/位置、空设置回退。

### 阶段 1：全局 UI

- [x] `ui/settings/RenPySettingsCard.kt` 新增卡片与分组/反选逻辑。
- [x] `SettingsScreen.kt` RENPY 分支挂载卡片，状态接入 `saveAll()`。
- [x] 模块安装状态提示（决定不做，引擎总页已有入口，避免重复）。

### 阶段 2：单游戏 UI

- [x] `PerGameSettingsScreen.kt` RENPY 分支新增 9 项覆盖行；校准顶部说明文案。
- [x] `save()` 接入覆盖写盘（null 删除键）。

### 阶段 3：文案与测试

- [x] zh/ja/en `strings.xml` 同步新增。
- [x] `check-hardcoded-ui-strings.py` 通过。
- [x] 单测 + 构建验证（见 §10）。

### 阶段 4（可选 P2）：应用级 screenTimeout

- [ ] `AppSettingsStore` 新增 `app_screen_timeout_minutes`（默认 0）。
- [ ] Ren'Py 与 RPGM 的 `buildSettingsJson` 均在 `app.screenTimeout` 下发（`{"int": N}`）。
- [ ] 全局设置页“屏幕超时”入口（对齐 JoiPlay 全局设置）。
- [ ] 抽成独立小计划，避免阻塞前四阶段。

---

## 10. 测试与验收清单

### 10.1 单元测试

- [x] `RenPySettingsKeysTest`：默认值（hw_video=true，其余 false，cheats=true）、白名单/键名契约。
- [x] `EffectiveEngineSettingsTest#mergeRenPy*`：覆盖优先、null 跟随全局、逐字段合并。
- [x] `RenPyExternalEngineModuleTest` 扩展：
  - `settings` JSON 含 `app.cheats` 与 8 个 `renpy_*` 包装；
  - `resolvedSettings=null` 时回退默认值且仍为完整 JSON；
  - 既有 game JSON / action / 版本选择用例保持通过。
- [x] `GameOverridePartitionsTest`：`F_RENPY_*` 归入 renpy 分区并可组装回。
- [x] `RenPyRuntimeEnvironmentTest`：受管键覆盖、未知键保留、缺失文件跳过。
- [x] `RenPyVersionDetectorTest` 保持通过（本计划不改版本链路）。

### 10.2 构建验证

```bash
git diff --check
./gradlew testDebugUnitTest --no-daemon
./gradlew assembleDebug --no-daemon
python3 tools/check-hardcoded-ui-strings.py
```

### 10.3 实机验收

1. 未安装模块：引擎页 Ren'Py 显示未安装与下载入口（现状回归）。
2. 安装 8.5 模块：全局关闭 `renpy_autosave`、开启 `renpy_less_memory`，启动后日志/行为可感知（`JOIPLAY_AUTOSAVE=0`）。
3. 单游戏覆盖：仅单个游戏关闭 `renpy_hw_video`，其余游戏跟随全局。
4. `renpy_dont_use_gl2` 反选 UI 与下发值一致（开=不发 GL2 禁用）。
5. `cheats` 关闭后插件 JoiPad 金手指面板不启用。
6. 预置 `configuration.json`（含冲突值）后启动，验证策略 A 下受管键被同步为 App 生效值。
7. 版本链路回归：`auto` 探测 7.7.1/8.5 与显式选择仍正确路由到对应包。

---

## 11. 风险与决策点

| 风险/决策 | 说明 | 建议 |
| --- | --- | --- |
| `cheats` 的归属 | JoiPlay 是应用级 `app.cheats`；TyranorNext RPGM 已落为引擎级 `rpg_cheats` | 本计划跟随后者用 `renpy_cheats`（JSON 仍发 `app.cheats`）；后续如做应用级统一再迁移 |
| `configuration.json` 策略 | 插件文件优先于 Intent，不处理会静默覆盖 App 设置 | 采用策略 A：存在则仅同步受管键，不存在不创建 |
| `dont_use_gl2` 语义易错 | JoiPlay UI 为反选，误实现会反向 | UI 文案写明“禁用”，`checked=!dontUseGl2`，单测固化 |
| 7.7.1 模块 action/manifest | TyranorNext 固定发 `cyou.joiplay.runtime.renpy.run`；官方 7.7.1 包需同 action | 实施前用 aapt2 核对 7.7.1 APK 的 intent-filter，不匹配则在 `RenPy77ExternalEngineModule` 覆写 action |
| 全量下发 vs 增量 | 全量可避免插件缺键默认值不一致 | 全量下发 + 默认模型兜底 |
| 不应影响版本选择 | 设置与版本选择是两条链路 | 不改 `RenPyVersionDetector`/`resolveRenpyModule`；测试回归 |

---

## 12. 完成标准

- [x] `settings` extra 按嵌套协议下发 `app.cheats` + 8 个 `renpy_*`，空设置回退默认。
- [x] Ren'Py 全局设置页含版本 + 9 项开关（高级分组、GL2 反选正确）。
- [x] 每项均可在单游戏页覆盖，遵循「null=跟随全局」。
- [x] `configuration.json` 按选定策略（A）处理，单测通过；实机验证待执行。
- [x] 三语言一致、单测通过、`assembleDebug` 通过。
- [x] 不回归现有版本探测/模块解析/模块安装状态链路。
- [x] P2（screenTimeout）另列计划，未完成不阻塞完成标准。
